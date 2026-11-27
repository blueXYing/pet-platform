package com.petplatform.boot.adapter.web.payment;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.payment.api.command.WalletCommandApi;
import com.petplatform.payment.biz.center.PaymentCallbackRouter;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol;
import com.petplatform.payment.biz.settlement.MerchantOnboardingService;
import com.petplatform.payment.biz.settlement.ReceiverAccountService;
import com.petplatform.payment.biz.settlement.SettlementReceiptWriter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract 80 §6 public Lakala 1009-signed async notification endpoints (Stage 7-BE):
 * <ul>
 *   <li>{@code POST /api/v1/callbacks/lakala/pay} (1112 trade payment notification)</li>
 *   <li>{@code POST /api/v1/callbacks/lakala/separate} (1297 split/fallback notification)</li>
 *   <li>{@code POST /api/v1/callbacks/lakala/withdrawals} (1272 withdrawal notification)</li>
 *   <li>{@code POST /api/v1/callbacks/lakala/ledger} (1290 receiver bind audit notification)</li>
 *   <li>{@code POST /api/v1/callbacks/lakala/onboard} (1083 进件回调)</li>
 *   <li>{@code POST /api/v1/callbacks/lakala/onboard/ec} (1208 signing result)</li>
 *   <li>{@code POST /api/v1/callbacks/lakala/onboard/ledger-open} (1284 audit result)</li>
 *   <li>{@code POST /api/v1/callbacks/lakala/onboard/bind} (1290 bind audit, onboarding path)</li>
 * </ul>
 *
 * <p>Every endpoint delegates raw headers + exact raw bytes to its domain verifier/writer so 1009
 * signature verification runs on untampered bytes before any persistence. Returns Lakala's exact
 * ack JSON {@code {"code":"SUCCESS","message":"执行成功"}} only after durable persistence.
 */
@RestController
@RequestMapping(value = "/api/v1/callbacks/lakala",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.payment", name = "enabled", havingValue = "true")
public final class LakalaCallbackController {
    private static final Logger LOG = LoggerFactory.getLogger(LakalaCallbackController.class);
    private static final String ACK = LakalaV2Protocol.NOTIFICATION_ACK_JSON;

    private final ObjectProvider<PaymentCallbackRouter> paymentCallbacks;
    private final ObjectProvider<SettlementReceiptWriter> settlementReceipts;
    private final ObjectProvider<WalletCommandApi> walletCommands;
    private final ObjectProvider<ReceiverAccountService> receiverServices;
    private final ObjectProvider<MerchantOnboardingService> onboardingServices;

    public LakalaCallbackController(
            ObjectProvider<PaymentCallbackRouter> paymentCallbacks,
            ObjectProvider<SettlementReceiptWriter> settlementReceipts,
            ObjectProvider<WalletCommandApi> walletCommands,
            ObjectProvider<ReceiverAccountService> receiverServices,
            ObjectProvider<MerchantOnboardingService> onboardingServices) {
        this.paymentCallbacks = paymentCallbacks;
        this.settlementReceipts = settlementReceipts;
        this.walletCommands = walletCommands;
        this.receiverServices = receiverServices;
        this.onboardingServices = onboardingServices;
    }

    @PostMapping("/pay")
    public ResponseEntity<String> payCallback(@RequestBody(required = false) byte[] rawBody,
            HttpServletRequest req) {
        return handle("pay", req, rawBody, () -> {
            PaymentCallbackRouter router = paymentCallbacks.getIfAvailable();
            if (router == null) throw unavailable("payment callback router unavailable");
            router.receive(headersMap(req), rawBody);
        });
    }

    @PostMapping("/separate")
    public ResponseEntity<String> separateCallback(@RequestBody(required = false) byte[] rawBody,
            HttpServletRequest req) {
        return handle("separate", req, rawBody, () -> {
            SettlementReceiptWriter writer = settlementReceipts.getIfAvailable();
            if (writer == null) throw unavailable("settlement receipt writer unavailable");
            writer.receiveNotification(headersMap(req), rawBody);
        });
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<String> withdrawalsCallback(@RequestBody(required = false) byte[] rawBody,
            HttpServletRequest req) {
        return handle("withdrawals", req, rawBody, () -> {
            WalletCommandApi wallet = walletCommands.getIfAvailable();
            if (wallet == null) throw unavailable("wallet callback handler unavailable");
            wallet.handleWithdrawalNotification(headersMap(req), rawBody);
        });
    }

    @PostMapping("/ledger")
    public ResponseEntity<String> ledgerCallback(@RequestBody(required = false) byte[] rawBody,
            HttpServletRequest req) {
        return handle("ledger", req, rawBody, () -> {
            ReceiverAccountService receiver = receiverServices.getIfAvailable();
            if (receiver == null) throw unavailable("receiver ledger callback handler unavailable");
            receiver.receiveBindNotification(headersMap(req), rawBody);
        });
    }

    // ---- CCR-PAY-ONBOARD-001 (contract 81 §5) -------------------------------------------

    @PostMapping("/onboard")
    public ResponseEntity<String> onboardingContractCallback(@RequestBody(required = false) byte[] rawBody,
            HttpServletRequest req) {
        return handle("onboard-contract", req, rawBody,
                onboarding(req, rawBody, MerchantOnboardingService::receiveContractNotification));
    }

    @PostMapping("/onboard/ec")
    public ResponseEntity<String> onboardingEcCallback(@RequestBody(required = false) byte[] rawBody,
            HttpServletRequest req) {
        return handle("onboard-ec", req, rawBody,
                onboarding(req, rawBody, MerchantOnboardingService::receiveEcNotification));
    }

    @PostMapping("/onboard/ledger-open")
    public ResponseEntity<String> onboardingLedgerOpenCallback(@RequestBody(required = false) byte[] rawBody,
            HttpServletRequest req) {
        return handle("onboard-ledger-open", req, rawBody,
                onboarding(req, rawBody, MerchantOnboardingService::receiveLedgerOpenNotification));
    }

    @PostMapping("/onboard/bind")
    public ResponseEntity<String> onboardingBindCallback(@RequestBody(required = false) byte[] rawBody,
            HttpServletRequest req) {
        return handle("onboard-bind", req, rawBody,
                onboarding(req, rawBody, MerchantOnboardingService::receiveBindNotification));
    }

    @FunctionalInterface
    private interface OnboardingAction {
        void run(MerchantOnboardingService service, Map<String, String> headers, byte[] rawBody);
    }

    private Runnable onboarding(HttpServletRequest req, byte[] rawBody, OnboardingAction action) {
        return () -> {
            MerchantOnboardingService service = onboardingServices.getIfAvailable();
            if (service == null) throw unavailable("merchant onboarding callback handler unavailable");
            action.run(service, headersMap(req), rawBody);
        };
    }

    private ResponseEntity<String> handle(String kind, HttpServletRequest req, byte[] rawBody, Runnable action) {
        if (rawBody == null || rawBody.length == 0 || rawBody.length > 65_536) {
            return ResponseEntity.badRequest().body("{\"code\":\"FAIL\",\"message\":\"请求体无效\"}");
        }
        try {
            action.run();
            return ResponseEntity.ok(ACK);
        } catch (ApiException known) {
            if (CommonApiCodes.INVALID_ARGUMENT.equals(known.code())) {
                LOG.warn("Lakala callback rejected kind={} code={}", kind, known.code());
                return ResponseEntity.badRequest().body("{\"code\":\"FAIL\",\"message\":\"验签或报文校验失败\"}");
            }
            if (CommonApiCodes.NOT_FOUND.equals(known.code())) {
                LOG.warn("Lakala callback target not found kind={}", kind);
                return ResponseEntity.status(404).body("{\"code\":\"FAIL\",\"message\":\"业务单据不存在\"}");
            }
            if (CommonApiCodes.CONFLICT.equals(known.code())) {
                LOG.warn("Lakala callback conflict kind={}", kind);
                return ResponseEntity.status(409).body("{\"code\":\"FAIL\",\"message\":\"状态或金额校验冲突\"}");
            }
            LOG.warn("Lakala callback unavailable kind={} code={}", kind, known.code());
            return ResponseEntity.status(503).body("{\"code\":\"FAIL\",\"message\":\"服务暂不可用\"}");
        } catch (RuntimeException failure) {
            LOG.error("Lakala callback failed kind={} type={}", kind, failure.getClass().getSimpleName());
            return ResponseEntity.status(503).body("{\"code\":\"FAIL\",\"message\":\"处理失败请稍后重试\"}");
        }
    }

    private static Map<String, String> headersMap(HttpServletRequest req) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            String value = req.getHeader(name);
            if (value != null) {
                headers.put(name, value);
                if ("authorization".equalsIgnoreCase(name)) {
                    headers.put("Authorization", value);
                }
            }
        }
        return headers;
    }

    private static ApiException unavailable(String msg) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, msg);
    }
}
