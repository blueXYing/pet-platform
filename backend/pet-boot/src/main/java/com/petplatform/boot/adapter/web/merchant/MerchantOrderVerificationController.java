package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.query.OrderVerificationCredentialFactsApi;
import com.petplatform.verification.api.command.VerificationCompletionApi;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * Contract 48 K2 HTTP face (contract 10 §4.7 route): POST /merchant/orders/{orderId}/verification
 * with body {verificationCode} only. merchantId/storeId are resolved server-side from the order
 * (locate); the operator identity is resolved by the K1 v0.2 chain inside the guarded command
 * (OWNER first, then the contract-52 staff action gate when both staff switches are on) — the
 * client never declares store or staff. expectedCredentialVersion is resolved from the current VER
 * state (scan semantics; the command re-checks everything under lock). Failed code checks are
 * committed business receipts (HTTP 200 with resultCode and null tails), not transport errors.
 */
@RestController
@RequestMapping(value = "/api/v1/merchant/orders", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "pet.verification.completion.http.enabled", havingValue = "true")
public final class MerchantOrderVerificationController {
    private static final ObjectReader JSON = com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
    private final VerificationCompletionApi completion;
    private final OrderVerificationCredentialFactsApi orderFacts;
    private final com.petplatform.verification.api.command.VerificationCredentialApi credentials;

    public MerchantOrderVerificationController(VerificationCompletionApi completion,
            OrderVerificationCredentialFactsApi orderFacts,
            com.petplatform.verification.api.command.VerificationCredentialApi credentials) {
        this.completion = completion;
        this.orderFacts = orderFacts;
        this.credentials = credentials;
    }

    @ModelAttribute void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping(value = "/{orderId}/verification", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> verify(@PathVariable String orderId, @RequestBody String raw,
            HttpServletRequest request) {
        onlyParameters(request);
        mini(request);
        JsonNode body = body(raw);
        String code = requiredText(body, "verificationCode");
        String order = id(orderId);
        // Server-side scope: the order itself names merchant/store; never a client claim.
        var location = orderFacts.locate(order,
            new QueryContext(trace(request), OperatorType.SYSTEM, null));
        var receipt = completion.verify(new VerificationCompletionApi.Command(
            userCommand(request), order, location.storeId(), code,
            credentials.currentVersion(order), true));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", receipt.orderId());
        data.put("attemptId", receipt.attemptId());
        data.put("resultCode", receipt.resultCode());
        data.put("verificationId", receipt.verificationId());
        data.put("verifiedAt", receipt.verifiedAt());
        data.put("orderVersion", receipt.orderVersion());
        return envelope(data, request);
    }

    /** Strict single-field body; unknown field, explicit null, duplicate key, trailing tokens 400. */
    private static JsonNode body(String raw) {
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject()) throw invalid();
            for (String name : root.propertyNames()) {
                if (!"verificationCode".equals(name) || root.get(name).isNull()) throw invalid();
            }
            return root;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static String requiredText(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isTextual()) throw invalid();
        return value.asText();
    }
}
