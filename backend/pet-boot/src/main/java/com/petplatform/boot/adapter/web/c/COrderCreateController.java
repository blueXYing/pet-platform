package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.boot.config.MerchantJsonReaderFactory;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.order.api.command.OrderCreationApi;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderCommand;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderResult;
import com.petplatform.payment.api.command.PaymentInitiationApi;
import com.petplatform.payment.api.dto.PaymentInitiationTypes.InitiatedPayment;
import com.petplatform.payment.api.dto.PaymentPreparationTypes.PreparePaymentCommand;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * POST /api/v1/c/orders and POST /api/v1/c/orders/{orderId}/payments (HTTP contract 10 §3.5/§3.6,
 * OpenAPI11 createOrder/createOrderPayment): the C-side write face over the existing booking and
 * payment kernels (contract 38 atomic create, contract 41 dispatch). The controller only assembles
 * and projects — every booking-eligibility, ownership, idempotency and payability rule stays in the
 * kernel. Strict JSON (unknown field, wrong type, explicit null on a non-nullable field, duplicate
 * key and trailing tokens are all 400), terminal X-Request-Id UUID per supplement 23, no query
 * string, Cache-Control: no-store on every reply. The request body is exactly the pinned
 * CreateOrderRequest shape: the 36/38 selection-window sync slice (2026-10-07) added
 * selectedGeneralWindowId, selectedPickupWindowId, selectedReturnWindowId and serviceAddress to
 * the public contract, unlocking PICKUP_DELIVERY creation. The four-field top-level requirement
 * plus the fulfillment oneOf branches (IN_STORE: appointment pair required, directional ids and
 * service address forbidden; PICKUP_DELIVERY: pickupStart, returnStart, both directional ids and
 * a non-blank serviceAddress required, appointment pair and GENERAL id forbidden) mirror the
 * OpenAPI11 schema shape; start-equals-window, same-kind OPEN, 120-minute interval, ownership and
 * capacity rules all stay in the kernel's locked re-verification. The kernel beans ride
 * their own switches (pet.order.creation.enabled, pet.payment.foundation/dispatch.enabled); when
 * they are not assembled the routes fail closed with 503 instead of half-executing.
 */
@RestController
@RequestMapping("/api/v1/c/orders")
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class COrderCreateController {

    private static final ObjectReader STRICT = MerchantJsonReaderFactory.strictReader();
    /** CreateOrderRequest (OpenAPI11): exactly these fields, nothing else. */
    private static final Set<String> ORDER_FIELDS = Set.of("storeId", "serviceId", "petId",
            "fulfillmentType", "appointmentStart", "appointmentEnd", "pickupStart", "returnStart",
            "selectedGeneralWindowId", "selectedPickupWindowId", "selectedReturnWindowId",
            "couponInstanceId", "remark", "serviceAddress");
    /** Explicitly nullable in the schema; null is the "absent" spelling and equals omission. */
    private static final Set<String> ORDER_NULLABLE = Set.of("pickupStart", "returnStart",
            "selectedGeneralWindowId", "selectedPickupWindowId", "selectedReturnWindowId",
            "couponInstanceId", "serviceAddress");
    private static final Set<String> ORDER_REQUIRED = Set.of("storeId", "serviceId", "petId",
            "fulfillmentType");
    private static final Set<String> APPOINTMENT_FIELDS = Set.of("appointmentStart",
            "appointmentEnd");
    private static final Set<String> PICKUP_REQUIRED = Set.of("pickupStart", "returnStart",
            "selectedPickupWindowId", "selectedReturnWindowId", "serviceAddress");
    /** 38号 protected-text technical cap for the raw service address (UTF-8 bytes). */
    private static final int ADDRESS_MAX_BYTES = 65536;
    private static final Set<String> CHANNEL_FIELDS = Set.of("channel");

    private final ObjectProvider<OrderCreationApi> creations;
    private final ObjectProvider<PaymentInitiationApi> payments;

    public COrderCreateController(ObjectProvider<OrderCreationApi> creations,
            ObjectProvider<PaymentInitiationApi> payments) {
        this.creations = creations;
        this.payments = payments;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestBody String raw, HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid();
        MiniSessionView session = session(req);
        JsonNode body = strictObject(raw, ORDER_FIELDS, ORDER_NULLABLE);
        require(body, ORDER_REQUIRED);
        String fulfillment = oneOf(text(body, "fulfillmentType"), "IN_STORE", "PICKUP_DELIVERY");
        boolean pickup = "PICKUP_DELIVERY".equals(fulfillment);
        // The oneOf branch of the schema: each fulfillment requires its own pair and rejects the
        // other branch's fields. Presence means a non-null value here — nullable fields spell
        // absence as explicit null, exactly like omission.
        if (pickup) {
            require(body, PICKUP_REQUIRED);
            reject(body, APPOINTMENT_FIELDS, "selectedGeneralWindowId");
            address(body);
        } else {
            require(body, APPOINTMENT_FIELDS);
            reject(body, PICKUP_REQUIRED);
        }
        CreateOrderCommand command = new CreateOrderCommand(
                new CommandContext(CShared.requestId(req), trace(req), OperatorType.USER,
                        session.userId(), "MINIAPP"),
                id(text(body, "storeId")), id(text(body, "serviceId")), id(text(body, "petId")),
                fulfillment,
                time(body, "appointmentStart"), time(body, "appointmentEnd"),
                time(body, "pickupStart"), time(body, "returnStart"),
                optionalId(text(body, "selectedGeneralWindowId")),
                pickup ? id(text(body, "selectedPickupWindowId")) : null,
                pickup ? id(text(body, "selectedReturnWindowId")) : null,
                optionalId(text(body, "couponInstanceId")), remark(body),
                pickup ? address(body) : null);
        CreateOrderResult result = creation().create(command);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", result.orderId());
        data.put("orderNo", result.orderNo());
        data.put("displayStatus", result.displayStatus());
        data.put("payAmount", result.payAmount());
        data.put("paymentExpireAt", format(result.paymentExpireAt()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success(data, trace(req)));
    }

    @PostMapping(path = "/{orderId}/payments",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> pay(
            @PathVariable String orderId, @RequestBody String raw, HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid();
        MiniSessionView session = session(req);
        // Contract 10 §3.6: the V1 mini program shows no channel choice — the body carries the
        // one fixed value, anything else is a 400 rather than a server-side guess.
        JsonNode body = strictObject(raw, CHANNEL_FIELDS, Set.of());
        oneOf(text(body, "channel"), "WECHAT_MINI_PROGRAM");
        InitiatedPayment payment = payment().create(new PreparePaymentCommand(
                new CommandContext(CShared.requestId(req), trace(req), OperatorType.USER,
                        session.userId(), "MINIAPP"), id(orderId)));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("timeStamp", payment.wechatPayParameters().timeStamp());
        parameters.put("nonceStr", payment.wechatPayParameters().nonceStr());
        parameters.put("package", payment.wechatPayParameters().packageValue());
        parameters.put("signType", payment.wechatPayParameters().signType());
        parameters.put("paySign", payment.wechatPayParameters().paySign());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", payment.paymentId());
        data.put("paymentNo", payment.paymentNo());
        data.put("channel", payment.channel());
        data.put("wechatPayParameters", parameters);
        return ApiResponse.success(data, trace(req));
    }

    private OrderCreationApi creation() {
        OrderCreationApi kernel = creations.getIfAvailable();
        if (kernel == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "下单内核未装配");
        }
        return kernel;
    }

    private PaymentInitiationApi payment() {
        PaymentInitiationApi kernel = payments.getIfAvailable();
        if (kernel == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "支付内核未装配");
        }
        return kernel;
    }

    /** Unknown fields, explicit nulls on non-nullable fields, duplicates and tails are all 400. */
    private static JsonNode strictObject(String raw, Set<String> fields, Set<String> nullable) {
        try {
            JsonNode root = STRICT.readTree(raw);
            if (root == null || !root.isObject()) throw invalid();
            for (String name : root.propertyNames()) {
                if (!fields.contains(name)) throw invalid();
                if (root.get(name).isNull() && !nullable.contains(name)) throw invalid();
            }
            return root;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** The schema's required list: an absent field is a 400 before any kernel is reached. */
    private static void require(JsonNode body, Set<String> names) {
        for (String name : names) {
            if (text(body, name) == null) throw invalid();
        }
    }

    /** The oneOf branch's not-clause: a field of the other branch being present is a 400. */
    private static void reject(JsonNode body, Set<String> names, String... more) {
        for (String name : names) {
            if (text(body, name) != null) throw invalid();
        }
        for (String name : more) {
            if (text(body, name) != null) throw invalid();
        }
    }

    /**
     * Pickup service address (contract 38): non-blank shape here; the kernel owns the content
     * rules and the protected encrypted snapshot. Returns the trimmed-free raw text as sent.
     */
    private static String address(JsonNode body) {
        String value = text(body, "serviceAddress");
        if (value == null || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > ADDRESS_MAX_BYTES) {
            throw invalid();
        }
        return value;
    }

    private static String text(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || value.isNull() || !value.isTextual()) return null;
        return value.asText();
    }

    private static String oneOf(String value, String... allowed) {
        if (value == null || !Set.of(allowed).contains(value)) throw invalid();
        return value;
    }

    private static String id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try {
            Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw invalid();
        }
        return value;
    }

    /** Nullable PublicId (couponInstanceId): absent or explicit null passes through as null. */
    private static String optionalId(String value) {
        return value == null ? null : id(value);
    }

    private static OffsetDateTime time(JsonNode body, String name) {
        String value = text(body, name);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    /** remark has no nullable marker in the schema: shape limit 500 here, the kernel owns the rest. */
    private static String remark(JsonNode body) {
        String value = text(body, "remark");
        if (value != null && value.codePointCount(0, value.length()) > 500) throw invalid();
        return value;
    }

    private static String format(OffsetDateTime value) {
        return value == null ? null
                : new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                        .format(value.toInstant());
    }

    private static MiniSessionView session(HttpServletRequest req) {
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        if (!(view instanceof MiniSessionView session)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        return session;
    }

    private static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    }
}
