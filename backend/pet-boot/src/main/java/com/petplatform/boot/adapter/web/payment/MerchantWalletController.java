package com.petplatform.boot.adapter.web.payment;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.boot.config.MerchantJsonReaderFactory;
import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantMembershipDTO;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.payment.api.command.WalletCommandApi;
import com.petplatform.payment.api.dto.ReceiverTypes.MerchantReceiverStatusView;
import com.petplatform.payment.api.dto.WalletTypes.ListWithdrawalsQuery;
import com.petplatform.payment.api.dto.WalletTypes.MerchantWalletView;
import com.petplatform.payment.api.dto.WalletTypes.WalletPage;
import com.petplatform.payment.api.dto.WalletTypes.WithdrawCommand;
import com.petplatform.payment.api.dto.WalletTypes.WithdrawalOrderView;
import com.petplatform.payment.api.query.ReceiverQueryApi;
import com.petplatform.payment.api.query.WalletQueryApi;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * Contract 80 §5 merchant miniapp OWNER-only wallet, D1 withdrawal, and read-only receiver status
 * controller (Stage 7-BE).
 *
 * <p>Hard rules (Ruling 40 §4.2, §5.5, §7, §8, §9):
 * <ul>
 *   <li>Strictly OWNER-only: every request re-proves {@code membershipKind == "OWNER"} via
 *       {@link MerchantAdmissionQueryApi}; STAFF or non-merchant callers receive HTTP 403.</li>
 *   <li>No merchant receiver write endpoint exists (operator-filled only).</li>
 *   <li>No SMS OTP on withdrawal; {@code X-Request-Id} idempotency enforced.</li>
 *   <li>Merchant wallet never exposes platform subsidy payable.</li>
 * </ul>
 */
@RestController
@RequestMapping(value = "/api/v1/merchant", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet", name = {"auth.c.enabled", "payment.enabled"}, havingValue = "true")
public final class MerchantWalletController {
    private static final ObjectReader STRICT = MerchantJsonReaderFactory.strictReader();
    private static final Set<String> WITHDRAWAL_STATUSES = Set.of("CREATED", "PROCESSING", "SUCCEEDED", "FAILED");

    private final ObjectProvider<MerchantAdmissionQueryApi> admissionApi;
    private final ObjectProvider<ReceiverQueryApi> receiverQueryApi;
    private final ObjectProvider<WalletQueryApi> walletQueryApi;
    private final ObjectProvider<WalletCommandApi> walletCommandApi;

    public MerchantWalletController(
            ObjectProvider<MerchantAdmissionQueryApi> admissionApi,
            ObjectProvider<ReceiverQueryApi> receiverQueryApi,
            ObjectProvider<WalletQueryApi> walletQueryApi,
            ObjectProvider<WalletCommandApi> walletCommandApi) {
        this.admissionApi = admissionApi;
        this.receiverQueryApi = receiverQueryApi;
        this.walletQueryApi = walletQueryApi;
        this.walletCommandApi = walletCommandApi;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
    }

    @GetMapping("/settlement/receiver")
    Map<String, Object> receiverStatus(HttpServletRequest req) {
        onlyParameters(req);
        OwnerContext owner = requireOwner(req);
        ReceiverQueryApi api = requireBean(receiverQueryApi, "分账接收方服务未启用");
        MerchantReceiverStatusView view = api.getMerchantStatusReadOnly(
                owner.merchantId(), ownerQuery(req, owner));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", view.status());
        data.put("statusLabel", view.statusLabel());
        data.put("receiverNo", view.receiverNo());
        data.put("acctNameMasked", view.acctNameMasked());
        data.put("acctNoLast4", view.acctNoLast4());
        data.put("bankName", view.bankName());
        data.put("active", view.active());
        data.put("rejectReason", view.rejectReason());
        data.put("updatedAt", formatTime(view.updatedAt()));
        return ok(data, req);
    }

    @GetMapping("/wallet")
    Map<String, Object> wallet(HttpServletRequest req) {
        onlyParameters(req);
        OwnerContext owner = requireOwner(req);
        WalletQueryApi api = requireBean(walletQueryApi, "商家钱包服务未启用");
        MerchantWalletView view = api.getMerchantWallet(owner.merchantId(), ownerQuery(req, owner));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("receiverStatus", view.receiverStatus());
        data.put("receiverActive", view.receiverActive());
        data.put("availableBalance", formatAmount(view.availableBalance()));
        data.put("pendingSettlementAmount", formatAmount(view.pendingSettlementAmount()));
        data.put("pendingSettlementCount", view.pendingSettlementCount());
        data.put("currency", view.currency());
        data.put("balanceAsOf", formatTime(view.balanceAsOf()));
        data.put("channelBalanceAvailable", view.channelBalanceAvailable());
        return ok(data, req);
    }

    @GetMapping("/wallet/withdrawals")
    Map<String, Object> listWithdrawals(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {
        onlyParameters(req, "status", "page", "size");
        if (page < 1 || page > 10_000 || size < 1 || size > 100) throw invalid("分页参数不合法");
        String normStatus = status == null || status.isBlank() ? null : status.trim();
        if (normStatus != null && !WITHDRAWAL_STATUSES.contains(normStatus)) throw invalid("提现状态筛选不合法");
        OwnerContext owner = requireOwner(req);
        WalletQueryApi api = requireBean(walletQueryApi, "商家钱包服务未启用");
        WalletPage<WithdrawalOrderView> result = api.listWithdrawals(
                new ListWithdrawalsQuery(ownerQuery(req, owner), owner.merchantId(), normStatus, page, size));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(MerchantWalletController::withdrawalView).toList());
        data.put("total", result.total());
        data.put("page", result.page());
        data.put("size", result.size());
        return ok(data, req);
    }

    @PostMapping(value = "/wallet/withdrawals", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> createWithdrawal(@RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        OwnerContext owner = requireOwner(req);
        WalletCommandApi api = requireBean(walletCommandApi, "商家提现服务未启用");
        JsonNode body = readObject(json);
        if (body.size() != 1 || !body.has("amount")) {
            throw invalid("仅允许传入 amount 字段");
        }
        BigDecimal amount = parsePositiveAmount(body.get("amount"));
        CommandContext ctx = new CommandContext(
                requireRequestId(req), trace(req), OperatorType.MERCHANT_STAFF, owner.userId(), "MINIAPP");
        WithdrawalOrderView created = api.withdraw(new WithdrawCommand(ctx, owner.merchantId(), amount));
        return ok(withdrawalView(created), req);
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private record OwnerContext(String userId, String merchantId) {}

    private OwnerContext requireOwner(HttpServletRequest req) {
        Object attr = req.getAttribute(CBearerSessionFilter.VIEW);
        if (!(attr instanceof MiniSessionView session)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        MerchantAdmissionQueryApi admission = requireBean(admissionApi, "商家准入校验服务不可用");
        MerchantMembershipPageDTO memberships = admission.listMemberships(new MerchantMembershipQuery(
                1, 50, new QueryContext(trace(req), OperatorType.USER, session.userId())));
        List<String> ownedMerchants = memberships.items().stream()
                .filter(item -> "OWNER".equals(item.membershipKind()))
                .map(MerchantMembershipDTO::merchantId)
                .distinct()
                .toList();
        if (ownedMerchants.isEmpty()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "仅认证商家主账号（OWNER）可访问钱包与提现功能");
        }
        return new OwnerContext(session.userId(), ownedMerchants.getFirst());
    }

    private static QueryContext ownerQuery(HttpServletRequest req, OwnerContext owner) {
        return new QueryContext(trace(req), OperatorType.MERCHANT_STAFF, owner.userId());
    }

    static Map<String, Object> withdrawalView(WithdrawalOrderView view) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("withdrawalId", view.withdrawalId());
        m.put("merOrderNo", view.merOrderNo());
        m.put("merchantId", view.merchantId());
        m.put("receiverNo", view.receiverNo());
        m.put("amount", formatAmount(view.amount()));
        m.put("feeAmount", formatAmount(view.feeAmount()));
        m.put("netAmount", formatAmount(view.netAmount()));
        m.put("status", view.status());
        m.put("drawJnl", view.drawJnl());
        m.put("failReason", view.failReason());
        m.put("channelState", view.channelState());
        m.put("requestedByUserId", view.requestedByUserId());
        m.put("requestedAt", formatTime(view.requestedAt()));
        m.put("finishedAt", formatTime(view.finishedAt()));
        m.put("createdAt", formatTime(view.createdAt()));
        return m;
    }

    private static JsonNode readObject(String json) {
        if (json == null || json.isBlank()) throw invalid("请求体不能为空");
        try {
            JsonNode node = STRICT.readTree(json);
            if (node == null || !node.isObject()) throw invalid("请求体须为 JSON 对象");
            return node;
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException bad) {
            throw invalid("JSON 格式不合法");
        }
    }

    private static BigDecimal parsePositiveAmount(JsonNode node) {
        if (node == null || node.isNull()) throw invalid("提现金额不能为空");
        String raw;
        if (node.isString()) {
            raw = node.asString().trim();
        } else if (node.isNumber()) {
            raw = node.decimalValue().toPlainString();
        } else {
            throw invalid("提现金额格式不合法");
        }
        if (!raw.matches("(?:0|[1-9][0-9]{0,12})(?:\\.[0-9]{1,2})?")) {
            throw invalid("提现金额格式不合法");
        }
        BigDecimal value = new BigDecimal(raw).setScale(2, RoundingMode.UNNECESSARY);
        if (value.signum() <= 0) throw invalid("提现金额须大于 0.00");
        return value;
    }

    private static String requireRequestId(HttpServletRequest req) {
        try {
            return PublicContractChecks.requireTerminalRequestId(req.getHeader("X-Request-Id"));
        } catch (RuntimeException bad) {
            throw invalid("缺少或非法的 X-Request-Id");
        }
    }

    private static void onlyParameters(HttpServletRequest req, String... names) {
        Set<String> allowed = Set.of(names);
        if (!allowed.containsAll(req.getParameterMap().keySet())) throw invalid("包含不支持的查询参数");
        for (String name : names) {
            String[] values = req.getParameterValues(name);
            if (values != null && values.length != 1) throw invalid("重复的查询参数");
        }
    }

    private static <T> T requireBean(ObjectProvider<T> provider, String msg) {
        T bean = provider.getIfAvailable();
        if (bean == null) throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, msg);
        return bean;
    }

    private static Map<String, Object> ok(Object data, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("code", "SUCCESS");
        out.put("message", "OK");
        out.put("data", data);
        out.put("traceId", trace(req));
        return out;
    }

    private static String trace(HttpServletRequest req) {
        String trace = MDC.get(TraceContextFilter.TRACE_MDC_KEY);
        if (trace != null && !trace.isBlank()) return trace;
        String header = req.getHeader("X-Trace-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

    private static String formatAmount(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String formatTime(OffsetDateTime value) {
        return value == null ? null
                : new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value.toInstant());
    }

    private static ApiException invalid(String msg) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, msg);
    }
}
