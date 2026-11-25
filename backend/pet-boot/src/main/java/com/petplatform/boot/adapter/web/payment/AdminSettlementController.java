package com.petplatform.boot.adapter.web.payment;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.boot.config.AdminBearerAuthenticationFilter;
import com.petplatform.boot.config.MerchantJsonReaderFactory;
import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.command.ReceiverAccountApi;
import com.petplatform.payment.api.command.ReconcileAdminApi;
import com.petplatform.payment.api.command.SettlementAdminApi;
import com.petplatform.payment.api.dto.ReceiverTypes.BindReceiverCommand;
import com.petplatform.payment.api.dto.ReceiverTypes.ListReceiversQuery;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverAccountSummary;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverAccountView;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverAttachmentRef;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverPage;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverSensitiveView;
import com.petplatform.payment.api.dto.ReceiverTypes.RevealReceiverCommand;
import com.petplatform.payment.api.dto.ReceiverTypes.UpsertReceiverCommand;
import com.petplatform.payment.api.dto.ReconcileTypes.ListReconcileDiffsQuery;
import com.petplatform.payment.api.dto.ReconcileTypes.ReconcileDiffView;
import com.petplatform.payment.api.dto.ReconcileTypes.ReconcilePage;
import com.petplatform.payment.api.dto.ReconcileTypes.ResolveReconcileDiffCommand;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportQuery;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportResponse;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportRow;
import com.petplatform.payment.api.dto.SettlementTypes.ListSettlementOrdersQuery;
import com.petplatform.payment.api.dto.SettlementTypes.ResumeSettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.RetryFallbackCommand;
import com.petplatform.payment.api.dto.SettlementTypes.RetrySettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementConfigView;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementInstructionView;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementOrderView;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementPage;
import com.petplatform.payment.api.dto.SettlementTypes.UpsertSettlementConfigCommand;
import com.petplatform.payment.api.dto.WalletTypes.ListWithdrawalsQuery;
import com.petplatform.payment.api.dto.WalletTypes.WalletPage;
import com.petplatform.payment.api.dto.WalletTypes.WithdrawalOrderView;
import com.petplatform.payment.api.query.ReceiverQueryApi;
import com.petplatform.payment.api.query.SettlementQueryApi;
import com.petplatform.payment.api.query.SubsidyReportQueryApi;
import com.petplatform.payment.api.query.WalletQueryApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * Contract 80 §4 operations web controller for settlement config, operator-filled split receivers,
 * settlement orders/instructions/retries, read-only withdrawal ledger, strictly read-only platform
 * subsidy payable report, and daily reconciliation diffs (Stage 7-BE).
 *
 * <p>Enforces exact Contract 80 §2 RBAC action codes ({@code settlement.config.write},
 * {@code settlement.receiver.write}, {@code settlement.receiver.read-sensitive},
 * {@code settlement.instruction.retry}, {@code settlement.report.read}) and {@code X-Request-Id}
 * idempotency on every write. Strictly obeys Ruling 22 / Ruling 40 prohibitions: zero mark-paid
 * endpoint on subsidy report, zero withdraw-on-behalf endpoint.
 */
@RestController
@RequestMapping(value = "/api/v1/admin/settlement", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnExpression(
        "'${pet.payment.settlement.http.enabled:false}' == 'true' and '${pet.payment.settlement.enabled:false}' == 'true'")
public final class AdminSettlementController {
    private static final ObjectReader STRICT = MerchantJsonReaderFactory.strictReader();
    private static final Pattern ID_PATTERN = Pattern.compile("[1-9][0-9]{0,18}");

    private final ObjectProvider<SettlementAdminApi> settlementAdminApi;
    private final ObjectProvider<SettlementQueryApi> settlementQueryApi;
    private final ObjectProvider<ReceiverAccountApi> receiverAccountApi;
    private final ObjectProvider<ReceiverQueryApi> receiverQueryApi;
    private final ObjectProvider<WalletQueryApi> walletQueryApi;
    private final ObjectProvider<SubsidyReportQueryApi> subsidyReportQueryApi;
    private final ObjectProvider<ReconcileAdminApi> reconcileAdminApi;

    public AdminSettlementController(
            ObjectProvider<SettlementAdminApi> settlementAdminApi,
            ObjectProvider<SettlementQueryApi> settlementQueryApi,
            ObjectProvider<ReceiverAccountApi> receiverAccountApi,
            ObjectProvider<ReceiverQueryApi> receiverQueryApi,
            ObjectProvider<WalletQueryApi> walletQueryApi,
            ObjectProvider<SubsidyReportQueryApi> subsidyReportQueryApi,
            ObjectProvider<ReconcileAdminApi> reconcileAdminApi) {
        this.settlementAdminApi = settlementAdminApi;
        this.settlementQueryApi = settlementQueryApi;
        this.receiverAccountApi = receiverAccountApi;
        this.receiverQueryApi = receiverQueryApi;
        this.walletQueryApi = walletQueryApi;
        this.subsidyReportQueryApi = subsidyReportQueryApi;
        this.reconcileAdminApi = reconcileAdminApi;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
    }

    // ---- 4.1 商家结算配置 ------------------------------------------------------------------

    @GetMapping("/configs/{merchantId}")
    Map<String, Object> getConfig(@PathVariable String merchantId, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdminAny(req, "settlement.report.read", "settlement.config.write");
        SettlementAdminApi api = requireBean(settlementAdminApi, "结算配置服务未启用");
        return ok(configView(api.getConfig(requireId(merchantId), adminQuery(req, session))), req);
    }

    @PutMapping(value = "/configs/{merchantId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> upsertConfig(@PathVariable String merchantId,
            @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.config.write");
        SettlementAdminApi api = requireBean(settlementAdminApi, "结算配置服务未启用");
        JsonNode body = readObject(json);
        int settleDays = requiredInt(body, "settleDays");
        BigDecimal feeRate = requiredRate(body, "feeRate");
        boolean enabled = requiredBool(body, "enabled");
        String reason = optionalString(body, "reason");
        SettlementConfigView saved = api.upsertConfig(new UpsertSettlementConfigCommand(
                adminCommand(req, session, true), requireId(merchantId), settleDays, feeRate, enabled, reason));
        return ok(configView(saved), req);
    }

    // ---- 4.2 分账接收方开通与台账 ----------------------------------------------------------

    @GetMapping("/receivers")
    Map<String, Object> listReceivers(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "status", "page", "size");
        validatePage(page, size);
        AdminSessionView session = requireAdmin(req, "settlement.report.read");
        ReceiverQueryApi api = requireBean(receiverQueryApi, "分账接收方服务未启用");
        ReceiverPage result = api.list(new ListReceiversQuery(
                adminQuery(req, session), optionalId(merchantId), blankToNull(status), page, size));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(AdminSettlementController::receiverSummary).toList());
        data.put("total", result.total());
        data.put("page", result.page());
        data.put("size", result.size());
        return ok(data, req);
    }

    @GetMapping("/receivers/{merchantId}")
    Map<String, Object> getReceiver(@PathVariable String merchantId, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.report.read");
        ReceiverQueryApi api = requireBean(receiverQueryApi, "分账接收方服务未启用");
        return ok(receiverView(api.getByMerchantId(requireId(merchantId), adminQuery(req, session))), req);
    }

    @PostMapping(value = "/receivers/{merchantId}/reveal", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> revealReceiver(@PathVariable String merchantId,
            @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.receiver.read-sensitive");
        ReceiverAccountApi api = requireBean(receiverAccountApi, "分账接收方服务未启用");
        JsonNode body = readObject(json);
        String reason = requiredString(body, "reason");
        ReceiverSensitiveView sensitive = api.revealSensitive(new RevealReceiverCommand(
                adminCommand(req, session, false), requireId(merchantId), reason));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchantId", sensitive.merchantId());
        data.put("acctNo", sensitive.acctNo());
        data.put("acctCertificateNo", sensitive.acctCertificateNo());
        data.put("contactMobile", sensitive.contactMobile());
        data.put("licenseNo", sensitive.licenseNo());
        data.put("legalPersonCertificateNo", sensitive.legalPersonCertificateNo());
        return ok(data, req);
    }

    @PostMapping(value = "/receivers/{merchantId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> createReceiver(@PathVariable String merchantId,
            @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.receiver.write");
        ReceiverAccountApi api = requireBean(receiverAccountApi, "分账接收方服务未启用");
        UpsertReceiverCommand cmd = parseUpsertReceiver(merchantId, json, adminCommand(req, session, true));
        return ok(receiverView(api.upsertOrCreate(cmd)), req);
    }

    @PutMapping(value = "/receivers/{merchantId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> updateReceiver(@PathVariable String merchantId,
            @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.receiver.write");
        ReceiverAccountApi api = requireBean(receiverAccountApi, "分账接收方服务未启用");
        UpsertReceiverCommand cmd = parseUpsertReceiver(merchantId, json, adminCommand(req, session, true));
        return ok(receiverView(api.modify(cmd)), req);
    }

    @PostMapping(value = "/receivers/{merchantId}/bind", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> bindReceiver(@PathVariable String merchantId,
            @RequestBody(required = false) String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.receiver.write");
        ReceiverAccountApi api = requireBean(receiverAccountApi, "分账接收方服务未启用");
        JsonNode body = json == null || json.isBlank() ? null : readObject(json);
        String cooperationAssetId = body == null ? null : optionalString(body, "cooperationAssetId");
        String remark = body == null ? null : optionalString(body, "remark");
        ReceiverAccountView updated = api.bind(new BindReceiverCommand(
                adminCommand(req, session, true), requireId(merchantId), cooperationAssetId, remark));
        return ok(receiverView(updated), req);
    }

    @PostMapping("/receivers/{merchantId}/sync")
    Map<String, Object> syncReceiver(@PathVariable String merchantId, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.receiver.write");
        ReceiverAccountApi api = requireBean(receiverAccountApi, "分账接收方服务未启用");
        ReceiverAccountView synced = api.syncFromChannel(
                requireId(merchantId), adminCommand(req, session, false));
        return ok(receiverView(synced), req);
    }

    // ---- 4.3 结算单与分账/回退指令台账 -----------------------------------------------------

    @GetMapping("/orders")
    Map<String, Object> listOrders(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "status", "page", "size");
        validatePage(page, size);
        AdminSessionView session = requireAdmin(req, "settlement.report.read");
        SettlementQueryApi api = requireBean(settlementQueryApi, "结算查询服务未启用");
        SettlementPage<SettlementOrderView> result = api.listOrders(new ListSettlementOrdersQuery(
                adminQuery(req, session), optionalId(merchantId), blankToNull(status), page, size));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(AdminSettlementController::orderView).toList());
        data.put("total", result.total());
        data.put("page", result.page());
        data.put("size", result.size());
        return ok(data, req);
    }

    @GetMapping("/orders/{settlementOrderId}")
    Map<String, Object> getOrder(@PathVariable String settlementOrderId, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.report.read");
        SettlementQueryApi api = requireBean(settlementQueryApi, "结算查询服务未启用");
        return ok(orderView(api.getById(requireId(settlementOrderId), adminQuery(req, session))), req);
    }

    @GetMapping("/orders/{settlementOrderId}/instructions")
    Map<String, Object> listInstructions(@PathVariable String settlementOrderId, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.report.read");
        SettlementQueryApi api = requireBean(settlementQueryApi, "结算查询服务未启用");
        List<Map<String, Object>> items = api.listInstructions(
                requireId(settlementOrderId), adminQuery(req, session))
                .stream().map(AdminSettlementController::instructionView).toList();
        return ok(items, req);
    }

    @PostMapping(value = "/orders/{settlementOrderId}/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> resumeOrder(@PathVariable String settlementOrderId,
            @RequestBody(required = false) String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.instruction.retry");
        SettlementAdminApi api = requireBean(settlementAdminApi, "结算管理服务未启用");
        JsonNode body = json == null || json.isBlank() ? null : readObject(json);
        String reason = body == null ? null : optionalString(body, "reason");
        SettlementOrderView updated = api.resumeHeldOrder(new ResumeSettlementCommand(
                adminCommand(req, session, true), requireId(settlementOrderId), reason));
        return ok(orderView(updated), req);
    }

    @PostMapping(value = "/orders/{settlementOrderId}/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> retryOrder(@PathVariable String settlementOrderId,
            @RequestBody(required = false) String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.instruction.retry");
        SettlementAdminApi api = requireBean(settlementAdminApi, "结算管理服务未启用");
        JsonNode body = json == null || json.isBlank() ? null : readObject(json);
        String reason = body == null ? null : optionalString(body, "reason");
        SettlementOrderView updated = api.retryFailedOrder(new RetrySettlementCommand(
                adminCommand(req, session, true), requireId(settlementOrderId), reason));
        return ok(orderView(updated), req);
    }

    @PostMapping(value = "/orders/{settlementOrderId}/retry-fallback", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> retryFallback(@PathVariable String settlementOrderId,
            @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.instruction.retry");
        SettlementAdminApi api = requireBean(settlementAdminApi, "结算管理服务未启用");
        JsonNode body = readObject(json);
        String refundOrderId = requireId(requiredString(body, "refundOrderId"));
        String reason = optionalString(body, "reason");
        SettlementOrderView updated = api.retryFailedFallback(new RetryFallbackCommand(
                adminCommand(req, session, true), requireId(settlementOrderId), refundOrderId, reason));
        return ok(orderView(updated), req);
    }

    // ---- 4.4 提现台账（运营只读，22 号禁止代提现） -----------------------------------------

    @GetMapping("/withdrawals")
    Map<String, Object> listWithdrawals(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "status", "page", "size");
        validatePage(page, size);
        AdminSessionView session = requireAdmin(req, "settlement.report.read");
        WalletQueryApi api = requireBean(walletQueryApi, "商家提现台账服务未启用");
        WalletPage<WithdrawalOrderView> result = api.listWithdrawals(new ListWithdrawalsQuery(
                adminQuery(req, session), optionalId(merchantId), blankToNull(status), page, size));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(MerchantWalletController::withdrawalView).toList());
        data.put("total", result.total());
        data.put("page", result.page());
        data.put("size", result.size());
        return ok(data, req);
    }

    // ---- 4.5 平台补贴应付报表（严格只读，40 号 §3.2 严禁标记已打款） -----------------------

    @GetMapping("/subsidy-report")
    Map<String, Object> subsidyReport(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "fromDate", "toDate", "page", "size");
        validatePage(page, size);
        AdminSessionView session = requireAdmin(req, "settlement.report.read");
        SubsidyReportQueryApi api = requireBean(subsidyReportQueryApi, "平台补贴应付报表服务未启用");
        SubsidyReportResponse report = api.querySubsidyReport(new SubsidyReportQuery(
                adminQuery(req, session), optionalId(merchantId),
                optionalDate(fromDate), optionalDate(toDate), page, size));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalOrderCount", report.summary().totalOrderCount());
        summary.put("totalMerchantShare", formatAmount(report.summary().totalMerchantShare()));
        summary.put("totalSplitAmount", formatAmount(report.summary().totalSplitAmount()));
        summary.put("totalSubsidyDue", formatAmount(report.summary().totalSubsidyDue()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", summary);
        data.put("items", report.items().stream().map(AdminSettlementController::subsidyRow).toList());
        data.put("total", report.total());
        data.put("page", report.page());
        data.put("size", report.size());
        return ok(data, req);
    }

    // ---- 4.6 日对账差异与异常处理（绝不自动改账） ------------------------------------------

    @GetMapping("/reconcile-diffs")
    Map<String, Object> listReconcileDiffs(
            @RequestParam(required = false) String reconcileDate,
            @RequestParam(required = false) String diffType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {
        onlyParameters(req, "reconcileDate", "diffType", "status", "page", "size");
        validatePage(page, size);
        AdminSessionView session = requireAdmin(req, "settlement.report.read");
        ReconcileAdminApi api = requireBean(reconcileAdminApi, "日对账服务未启用");
        ReconcilePage<ReconcileDiffView> result = api.listDiffs(new ListReconcileDiffsQuery(
                adminQuery(req, session), optionalDate(reconcileDate),
                blankToNull(diffType), blankToNull(status), page, size));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(AdminSettlementController::diffView).toList());
        data.put("total", result.total());
        data.put("page", result.page());
        data.put("size", result.size());
        return ok(data, req);
    }

    @PostMapping(value = "/reconcile-diffs/{diffId}/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> resolveDiff(@PathVariable String diffId,
            @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.instruction.retry");
        ReconcileAdminApi api = requireBean(reconcileAdminApi, "日对账服务未启用");
        JsonNode body = readObject(json);
        String note = requiredString(body, "note");
        ReconcileDiffView resolved = api.resolveDiff(new ResolveReconcileDiffCommand(
                adminCommand(req, session, true), requireId(diffId), note));
        return ok(diffView(resolved), req);
    }

    // ---- Request Parsers -------------------------------------------------------------------

    private static UpsertReceiverCommand parseUpsertReceiver(String merchantId, String json, CommandContext ctx) {
        JsonNode body = readObject(json);
        boolean submitToChannel = body.has("submitToChannel") && !body.get("submitToChannel").isNull()
                && requiredBool(body, "submitToChannel");
        List<ReceiverAttachmentRef> attachments = new ArrayList<>();
        JsonNode attNode = body.get("attachments");
        if (attNode != null && !attNode.isNull()) {
            if (!attNode.isArray()) throw invalid("attachments 须为数组");
            for (JsonNode entry : attNode) {
                if (entry == null || !entry.isObject()) throw invalid("附件项须为对象");
                attachments.add(new ReceiverAttachmentRef(
                        requiredString(entry, "attType"),
                        requiredString(entry, "assetId")));
            }
        }
        return new UpsertReceiverCommand(
                ctx,
                requireId(merchantId),
                submitToChannel,
                requiredString(body, "receiverName"),
                requiredString(body, "receiverType"),
                requiredString(body, "acctNo"),
                requiredString(body, "acctName"),
                requiredString(body, "acctTypeCode"),
                requiredString(body, "acctCertificateType"),
                requiredString(body, "acctCertificateNo"),
                requiredString(body, "acctOpenBankCode"),
                requiredString(body, "acctOpenBankName"),
                requiredString(body, "acctClearBankCode"),
                requiredString(body, "contactMobile"),
                optionalString(body, "licenseNo"),
                optionalString(body, "licenseName"),
                optionalString(body, "legalPersonName"),
                optionalString(body, "legalPersonCertificateType"),
                optionalString(body, "legalPersonCertificateNo"),
                attachments,
                optionalString(body, "reason"));
    }

    // ---- View Serializers (Decimal Strings + ISO-8601 UTC Millis) --------------------------

    private static Map<String, Object> configView(SettlementConfigView view) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merchantId", view.merchantId());
        m.put("settleDays", view.settleDays());
        m.put("feeRate", formatRate(view.feeRate()));
        m.put("enabled", view.enabled());
        m.put("configured", view.configured());
        m.put("version", view.version());
        m.put("updatedAt", formatTime(view.updatedAt()));
        return m;
    }

    private static Map<String, Object> receiverSummary(ReceiverAccountSummary view) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("receiverAccountId", view.receiverAccountId());
        m.put("merchantId", view.merchantId());
        m.put("status", view.status());
        m.put("receiverNo", view.receiverNo());
        m.put("applyId", view.applyId());
        m.put("receiverName", view.receiverName());
        m.put("receiverType", view.receiverType());
        m.put("acctName", view.acctName());
        m.put("acctNoMasked", view.acctNoMasked());
        m.put("acctNoLast4", view.acctNoLast4());
        m.put("acctOpenBankName", view.acctOpenBankName());
        m.put("contactMobileMasked", view.contactMobileMasked());
        m.put("rejectReason", view.rejectReason());
        m.put("updatedAt", formatTime(view.updatedAt()));
        return m;
    }

    private static Map<String, Object> receiverView(ReceiverAccountView view) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("receiverAccountId", view.receiverAccountId());
        m.put("merchantId", view.merchantId());
        m.put("status", view.status());
        m.put("receiverNo", view.receiverNo());
        m.put("applyId", view.applyId());
        m.put("settleType", view.settleType());
        m.put("receiverName", view.receiverName());
        m.put("receiverType", view.receiverType());
        m.put("acctName", view.acctName());
        m.put("acctNoMasked", view.acctNoMasked());
        m.put("acctNoLast4", view.acctNoLast4());
        m.put("acctTypeCode", view.acctTypeCode());
        m.put("acctCertificateType", view.acctCertificateType());
        m.put("acctCertificateNoMasked", view.acctCertificateNoMasked());
        m.put("acctOpenBankCode", view.acctOpenBankCode());
        m.put("acctOpenBankName", view.acctOpenBankName());
        m.put("acctClearBankCode", view.acctClearBankCode());
        m.put("contactMobileMasked", view.contactMobileMasked());
        m.put("licenseNoMasked", view.licenseNoMasked());
        m.put("licenseName", view.licenseName());
        m.put("legalPersonName", view.legalPersonName());
        m.put("legalPersonCertificateType", view.legalPersonCertificateType());
        m.put("legalPersonCertificateNoMasked", view.legalPersonCertificateNoMasked());
        m.put("attachments", view.attachments().stream().map(att -> Map.of(
                "attType", att.attType(), "assetId", att.assetId())).toList());
        m.put("cooperationAssetId", view.cooperationAssetId());
        m.put("rejectReason", view.rejectReason());
        m.put("version", view.version());
        m.put("lastSyncedAt", formatTime(view.lastSyncedAt()));
        m.put("createdAt", formatTime(view.createdAt()));
        m.put("updatedAt", formatTime(view.updatedAt()));
        return m;
    }

    private static Map<String, Object> orderView(SettlementOrderView view) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settlementOrderId", view.settlementOrderId());
        m.put("paymentId", view.paymentId());
        m.put("orderId", view.orderId());
        m.put("bizType", view.bizType());
        m.put("bizNo", view.bizNo());
        m.put("merchantId", view.merchantId());
        m.put("storeId", view.storeId());
        m.put("originalPrice", formatAmount(view.originalPrice()));
        m.put("flashDiscountAmount", formatAmount(view.flashDiscountAmount()));
        m.put("merchantDiscountAmount", formatAmount(view.merchantDiscountAmount()));
        m.put("platformSubsidyAmount", formatAmount(view.platformSubsidyAmount()));
        m.put("paidAmount", formatAmount(view.paidAmount()));
        m.put("refundedAmount", formatAmount(view.refundedAmount()));
        m.put("splitBase", formatAmount(view.splitBase()));
        m.put("feeRateSnapshot", formatRate(view.feeRateSnapshot()));
        m.put("merchantShare", formatAmount(view.merchantShare()));
        m.put("splitAmount", formatAmount(view.splitAmount()));
        m.put("platformRetainedAmount", formatAmount(view.platformRetainedAmount()));
        m.put("platformSubsidyDue", formatAmount(view.platformSubsidyDue()));
        m.put("actualMerchantAmount", formatAmount(view.actualMerchantAmount()));
        m.put("channelSplitFee", formatAmount(view.channelSplitFee()));
        m.put("verifiedAt", formatTime(view.verifiedAt()));
        m.put("settleDaysSnapshot", view.settleDaysSnapshot());
        m.put("eligibleAt", formatTime(view.eligibleAt()));
        m.put("reversedAmount", formatAmount(view.reversedAmount()));
        m.put("activeInstructionId", view.activeInstructionId());
        m.put("status", view.status());
        m.put("holdReason", view.holdReason());
        m.put("lastErrorCode", view.lastErrorCode());
        m.put("lastErrorMessage", view.lastErrorMessage());
        m.put("version", view.version());
        m.put("createdAt", formatTime(view.createdAt()));
        m.put("updatedAt", formatTime(view.updatedAt()));
        return m;
    }

    private static Map<String, Object> instructionView(SettlementInstructionView view) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instructionId", view.instructionId());
        m.put("settlementOrderId", view.settlementOrderId());
        m.put("paymentId", view.paymentId());
        m.put("refundOrderId", view.refundOrderId());
        m.put("originInstructionId", view.originInstructionId());
        m.put("merchantId", view.merchantId());
        m.put("instructionType", view.instructionType());
        m.put("attemptNo", view.attemptNo());
        m.put("outSeparateNo", view.outSeparateNo());
        m.put("separateNo", view.separateNo());
        m.put("merchantNo", view.merchantNo());
        m.put("receiverNo", view.receiverNo());
        m.put("logNo", view.logNo());
        m.put("logDate", view.logDate() == null ? null : view.logDate().toString());
        m.put("totalAmount", formatAmount(view.totalAmount()));
        m.put("merchantAmount", formatAmount(view.merchantAmount()));
        m.put("platformAmount", formatAmount(view.platformAmount()));
        m.put("actualSeparateAmount", formatAmount(view.actualSeparateAmount()));
        m.put("actualMerchantAmount", formatAmount(view.actualMerchantAmount()));
        m.put("totalFeeAmount", formatAmount(view.totalFeeAmount()));
        m.put("merchantFeeAmount", formatAmount(view.merchantFeeAmount()));
        m.put("status", view.status());
        m.put("channelStatus", view.channelStatus());
        m.put("channelFinalStatus", view.channelFinalStatus());
        m.put("accResultDesc", view.accResultDesc());
        m.put("fallbackReason", view.fallbackReason());
        m.put("finishDate", view.finishDate() == null ? null : view.finishDate().toString());
        m.put("sentAt", formatTime(view.sentAt()));
        m.put("terminalAt", formatTime(view.terminalAt()));
        m.put("version", view.version());
        m.put("createdAt", formatTime(view.createdAt()));
        m.put("updatedAt", formatTime(view.updatedAt()));
        return m;
    }

    private static Map<String, Object> subsidyRow(SubsidyReportRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merchantId", row.merchantId());
        m.put("period", row.period());
        m.put("orderCount", row.orderCount());
        m.put("totalPaidAmount", formatAmount(row.totalPaidAmount()));
        m.put("totalMerchantShare", formatAmount(row.totalMerchantShare()));
        m.put("totalSplitAmount", formatAmount(row.totalSplitAmount()));
        m.put("totalReversedAmount", formatAmount(row.totalReversedAmount()));
        m.put("totalSubsidyDue", formatAmount(row.totalSubsidyDue()));
        return m;
    }

    private static Map<String, Object> diffView(ReconcileDiffView view) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diffId", view.diffId());
        m.put("reconcileDate", view.reconcileDate() == null ? null : view.reconcileDate().toString());
        m.put("bizType", view.bizType());
        m.put("diffType", view.diffType());
        m.put("settlementOrderId", view.settlementOrderId());
        m.put("instructionId", view.instructionId());
        m.put("outSeparateNo", view.outSeparateNo());
        m.put("separateNo", view.separateNo());
        m.put("merchantId", view.merchantId());
        m.put("localAmount", formatAmount(view.localAmount()));
        m.put("channelAmount", formatAmount(view.channelAmount()));
        m.put("localFeeAmount", formatAmount(view.localFeeAmount()));
        m.put("channelFeeAmount", formatAmount(view.channelFeeAmount()));
        m.put("localStatus", view.localStatus());
        m.put("channelStatus", view.channelStatus());
        m.put("detail", view.detail());
        m.put("status", view.status());
        m.put("resolvedByOperatorId", view.resolvedByOperatorId());
        m.put("resolveNote", view.resolveNote());
        m.put("resolvedAt", formatTime(view.resolvedAt()));
        m.put("createdAt", formatTime(view.createdAt()));
        return m;
    }

    // ---- Auth & Validation Helpers ---------------------------------------------------------

    private static AdminSessionView requireAdmin(HttpServletRequest req, String action) {
        AdminSessionView session = requireSession(req);
        if (!session.permissions().actionCodes().contains(action)) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该结算操作");
        }
        return session;
    }

    private static AdminSessionView requireAdminAny(HttpServletRequest req, String... actions) {
        AdminSessionView session = requireSession(req);
        for (String action : actions) {
            if (session.permissions().actionCodes().contains(action)) {
                return session;
            }
        }
        throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该结算操作");
    }

    private static AdminSessionView requireSession(HttpServletRequest req) {
        Object attr = req.getAttribute(AdminBearerAuthenticationFilter.VIEW);
        if (attr instanceof AdminSessionView session) return session;
        throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
    }

    private static QueryContext adminQuery(HttpServletRequest req, AdminSessionView session) {
        return new QueryContext(trace(req), OperatorType.PLATFORM_OPERATOR, session.principal().operatorId());
    }

    private static CommandContext adminCommand(HttpServletRequest req, AdminSessionView session, boolean requireHeader) {
        String reqId;
        String header = req.getHeader("X-Request-Id");
        if (requireHeader) {
            try {
                reqId = PublicContractChecks.requireTerminalRequestId(header);
            } catch (RuntimeException bad) {
                throw invalid("缺少或非法的 X-Request-Id");
            }
        } else {
            reqId = header != null && !header.isBlank() ? header.trim() : UUID.randomUUID().toString();
        }
        return new CommandContext(reqId, trace(req), OperatorType.PLATFORM_OPERATOR,
                session.principal().operatorId(), "ADMIN_WEB");
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

    private static int requiredInt(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isIntegralNumber()) throw invalid(field + " 须为整数");
        return node.intValue();
    }

    private static boolean requiredBool(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isBoolean()) throw invalid(field + " 须为布尔值");
        return node.booleanValue();
    }

    private static BigDecimal requiredRate(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) throw invalid(field + " 不能为空");
        String raw = node.isString() ? node.asString().trim()
                : node.isNumber() ? node.decimalValue().toPlainString() : null;
        if (raw == null || !raw.matches("(?:0|[1-9][0-9]?)(?:\\.[0-9]{1,4})?")) {
            throw invalid(field + " 费率格式不合法");
        }
        return new BigDecimal(raw).setScale(4, RoundingMode.UNNECESSARY);
    }

    private static String requiredString(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isString() || node.asString().isBlank()) {
            throw invalid(field + " 不能为空");
        }
        return node.asString().trim();
    }

    private static String optionalString(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isString()) throw invalid(field + " 须为字符串");
        String trimmed = node.asString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String requireId(String raw) {
        if (raw == null || !ID_PATTERN.matcher(raw.trim()).matches()) {
            throw invalid("ID 格式不合法");
        }
        try {
            if (Long.parseLong(raw.trim()) <= 0) throw invalid("ID 格式不合法");
            return raw.trim();
        } catch (NumberFormatException bad) {
            throw invalid("ID 格式不合法");
        }
    }

    private static String optionalId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return requireId(raw);
    }

    private static LocalDate optionalDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException bad) {
            throw invalid("日期格式须为 yyyy-MM-dd");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || page > 10_000 || size < 1 || size > 100) {
            throw invalid("分页参数不合法");
        }
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
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

    private static String formatRate(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String formatTime(OffsetDateTime value) {
        return value == null ? null
                : new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value.toInstant());
    }

    private static ApiException invalid(String msg) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, msg);
    }
}
