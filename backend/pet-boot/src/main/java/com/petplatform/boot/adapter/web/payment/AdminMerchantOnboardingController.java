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
import com.petplatform.payment.api.command.MerchantOnboardingApi;
import com.petplatform.payment.api.dto.OnboardingTypes.AdoptContractCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.DiscardContractCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.ListOnboardingsQuery;
import com.petplatform.payment.api.dto.OnboardingTypes.MerchantProfileInput;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingAuditView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingAssetView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingDefaultsView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingPage;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingStepView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingSummaryView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingView;
import com.petplatform.payment.api.dto.OnboardingTypes.RevealOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.RetryOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SaveOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SettlementProfileInput;
import com.petplatform.payment.api.dto.OnboardingTypes.SubmitOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SyncOnboardingCommand;
import com.petplatform.payment.api.query.MerchantOnboardingQueryApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * Contract 81 §4 operations web controller for merchant acquiring onboarding (phase 1: operator-entered on
 * behalf of the merchant, Ruling 44). Enforces the contract's RBAC action codes
 * ({@code settlement.onboarding.write}, {@code settlement.onboarding.read-sensitive},
 * {@code settlement.report.read}) and {@code X-Request-Id} idempotency on every write. There is no
 * "mark as activated" endpoint: the acquiring pair is only ever written by the onboarding chain.
 */
@RestController
@RequestMapping(value = "/api/v1/admin/settlement/onboardings", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet", name = {"auth.admin.enabled", "payment.enabled"}, havingValue = "true")
public final class AdminMerchantOnboardingController {
    private static final ObjectReader STRICT = MerchantJsonReaderFactory.strictReader();
    private static final long UPLOAD_MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> MERCHANT_FIELDS = Set.of(
            "merRegName", "merBizName", "merRegDistCode", "merRegAddr", "mccCode",
            "merBlisName", "merBlisNo", "merBlisStDt", "merBlisExpDt",
            "larName", "larIdcard", "larIdcardStDt", "larIdcardExpDt", "larMobile",
            "contactName", "contactMobile");
    private static final Set<String> SETTLEMENT_FIELDS = Set.of(
            "acctTypeCode", "acctName", "acctNo", "acctIdType", "acctIdcard", "acctIdExpDt",
            "openningBankCode", "openningBankName", "clearingBankCode");
    private static final Set<String> ATTACHMENT_TYPES = Set.of(
            "FR_ID_CARD_FRONT", "FR_ID_CARD_BEHIND", "BANK_CARD", "BUSINESS_LICENCE",
            "MERCHANT_PHOTO", "SHOPINNER", "ID_CARD_FRONT", "ID_CARD_BEHIND",
            "NO_LEGAL_PERSON_SETT_AUTH_LETTER");

    private final ObjectProvider<MerchantOnboardingApi> commands;
    private final ObjectProvider<MerchantOnboardingQueryApi> queries;

    public AdminMerchantOnboardingController(
            ObjectProvider<MerchantOnboardingApi> commands,
            ObjectProvider<MerchantOnboardingQueryApi> queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
    }

    // ---- 4.1 Platform defaults and admission ------------------------------------------------

    @GetMapping("/{merchantId}/defaults")
    Map<String, Object> defaults(@PathVariable String merchantId, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdminAny(req, "settlement.report.read", "settlement.onboarding.write");
        MerchantOnboardingQueryApi api = requireBean(queries, "商家收单开通服务未启用");
        return ok(defaultsView(api.defaults(requireId(merchantId), adminQuery(req, session))), req);
    }

    // ---- 4.2 / 4.3 List and detail ----------------------------------------------------------

    @GetMapping
    Map<String, Object> list(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {
        onlyParameters(req, "merchantId", "status", "page", "size");
        validatePage(page, size);
        AdminSessionView session = requireAdmin(req, "settlement.report.read");
        MerchantOnboardingQueryApi api = requireBean(queries, "商家收单开通服务未启用");
        OnboardingPage result = api.list(new ListOnboardingsQuery(
                adminQuery(req, session), optionalId(merchantId), blankToNull(status), page, size));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(AdminMerchantOnboardingController::summaryView).toList());
        data.put("total", result.total());
        data.put("page", result.page());
        data.put("size", result.size());
        return ok(data, req);
    }

    @GetMapping("/{merchantId}")
    Map<String, Object> get(@PathVariable String merchantId, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.report.read");
        MerchantOnboardingQueryApi api = requireBean(queries, "商家收单开通服务未启用");
        return ok(view(api.getByMerchantId(requireId(merchantId), adminQuery(req, session))), req);
    }

    // ---- 4.4 Save profile -------------------------------------------------------------------

    @PutMapping(value = "/{merchantId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> save(@PathVariable String merchantId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.onboarding.write");
        MerchantOnboardingApi api = requireBean(commands, "商家收单开通服务未启用");
        JsonNode body = readObject(json);
        exactKeys(body, Set.of("expectedVersion", "merchant", "settlement", "attachments",
                "cooperationAssetId", "reason"), "请求体");
        requireVersion(body);
        JsonNode merchant = requireObject(body, "merchant");
        exactKeys(merchant, MERCHANT_FIELDS, "merchant");
        JsonNode settlement = requireObject(body, "settlement");
        exactKeys(settlement, SETTLEMENT_FIELDS, "settlement");
        Map<String, String> attachments = new LinkedHashMap<>();
        JsonNode attachmentNode = body.get("attachments");
        if (attachmentNode != null && !attachmentNode.isNull()) {
            if (!attachmentNode.isObject()) throw invalid("attachments 须为对象");
            exactKeys(attachmentNode, ATTACHMENT_TYPES, "attachments");
            for (String type : ATTACHMENT_TYPES) {
                String assetId = optionalString(attachmentNode, type);
                if (assetId != null) attachments.put(type, assetId);
            }
        }
        SaveOnboardingCommand command = new SaveOnboardingCommand(
                adminCommand(req, session), requireId(merchantId), requireVersion(body),
                new MerchantProfileInput(
                        optionalString(merchant, "merRegName"), optionalString(merchant, "merBizName"),
                        optionalString(merchant, "merRegDistCode"), optionalString(merchant, "merRegAddr"),
                        optionalString(merchant, "mccCode"), optionalString(merchant, "merBlisName"),
                        optionalString(merchant, "merBlisNo"), optionalString(merchant, "merBlisStDt"),
                        optionalString(merchant, "merBlisExpDt"), optionalString(merchant, "larName"),
                        optionalString(merchant, "larIdcard"), optionalString(merchant, "larIdcardStDt"),
                        optionalString(merchant, "larIdcardExpDt"), optionalString(merchant, "larMobile"),
                        optionalString(merchant, "contactName"), optionalString(merchant, "contactMobile")),
                new SettlementProfileInput(
                        optionalString(settlement, "acctTypeCode"), optionalString(settlement, "acctName"),
                        optionalString(settlement, "acctNo"), optionalString(settlement, "acctIdType"),
                        optionalString(settlement, "acctIdcard"), optionalString(settlement, "acctIdExpDt"),
                        optionalString(settlement, "openningBankCode"), optionalString(settlement, "openningBankName"),
                        optionalString(settlement, "clearingBankCode")),
                attachments, optionalString(body, "cooperationAssetId"), optionalString(body, "reason"));
        return ok(view(api.saveProfile(command)), req);
    }

    // ---- 4.5 – 4.9 Start, sync, retry, adopt, discard -----------------------------------------

    @PostMapping(value = "/{merchantId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> submit(@PathVariable String merchantId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.onboarding.write");
        MerchantOnboardingApi api = requireBean(commands, "商家收单开通服务未启用");
        JsonNode body = readObject(json);
        exactKeys(body, Set.of("expectedVersion"), "请求体");
        return ok(view(api.submit(new SubmitOnboardingCommand(
                adminCommand(req, session), requireId(merchantId), requireVersion(body)))), req);
    }

    @PostMapping("/{merchantId}/sync")
    Map<String, Object> sync(@PathVariable String merchantId, @RequestBody(required = false) String json,
            HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.onboarding.write");
        MerchantOnboardingApi api = requireBean(commands, "商家收单开通服务未启用");
        if (json != null && !json.isBlank()) exactKeys(readObject(json), Set.of(), "请求体");
        return ok(view(api.sync(new SyncOnboardingCommand(
                adminCommand(req, session), requireId(merchantId)))), req);
    }

    @PostMapping(value = "/{merchantId}/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> retry(@PathVariable String merchantId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.onboarding.write");
        MerchantOnboardingApi api = requireBean(commands, "商家收单开通服务未启用");
        JsonNode body = readObject(json);
        exactKeys(body, Set.of("expectedVersion", "cooperationAssetId", "reason"), "请求体");
        return ok(view(api.retry(new RetryOnboardingCommand(
                adminCommand(req, session), requireId(merchantId), requireVersion(body),
                optionalString(body, "cooperationAssetId"), optionalString(body, "reason")))), req);
    }

    @PostMapping(value = "/{merchantId}/contract/adopt", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> adopt(@PathVariable String merchantId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.onboarding.write");
        MerchantOnboardingApi api = requireBean(commands, "商家收单开通服务未启用");
        JsonNode body = readObject(json);
        exactKeys(body, Set.of("expectedVersion", "contractId", "reason"), "请求体");
        return ok(view(api.adoptContract(new AdoptContractCommand(
                adminCommand(req, session), requireId(merchantId), requireVersion(body),
                requiredString(body, "contractId"), requiredString(body, "reason")))), req);
    }

    @PostMapping(value = "/{merchantId}/contract/discard", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> discard(@PathVariable String merchantId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.onboarding.write");
        MerchantOnboardingApi api = requireBean(commands, "商家收单开通服务未启用");
        JsonNode body = readObject(json);
        exactKeys(body, Set.of("expectedVersion", "reason"), "请求体");
        return ok(view(api.discardContract(new DiscardContractCommand(
                adminCommand(req, session), requireId(merchantId), requireVersion(body),
                requiredString(body, "reason")))), req);
    }

    // ---- 4.10 Reveal ------------------------------------------------------------------------

    @PostMapping(value = "/{merchantId}/reveal", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> reveal(@PathVariable String merchantId, @RequestBody String json, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.onboarding.read-sensitive");
        MerchantOnboardingApi api = requireBean(commands, "商家收单开通服务未启用");
        JsonNode body = readObject(json);
        exactKeys(body, Set.of("reason"), "请求体");
        var sensitive = api.reveal(new RevealOnboardingCommand(
                adminCommand(req, session), requireId(merchantId), requiredString(body, "reason")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchantId", sensitive.merchantId());
        data.put("merBlisNo", sensitive.merBlisNo());
        data.put("larName", sensitive.larName());
        data.put("larIdcard", sensitive.larIdcard());
        data.put("larMobile", sensitive.larMobile());
        data.put("contactName", sensitive.contactName());
        data.put("contactMobile", sensitive.contactMobile());
        data.put("acctName", sensitive.acctName());
        data.put("acctNo", sensitive.acctNo());
        data.put("acctIdType", sensitive.acctIdType());
        data.put("acctIdcard", sensitive.acctIdcard());
        return ok(data, req);
    }

    // ---- 4.11 Audits ------------------------------------------------------------------------

    @GetMapping("/{merchantId}/audits")
    Map<String, Object> audits(@PathVariable String merchantId, HttpServletRequest req) {
        onlyParameters(req);
        AdminSessionView session = requireAdmin(req, "settlement.report.read");
        MerchantOnboardingQueryApi api = requireBean(queries, "商家收单开通服务未启用");
        List<Map<String, Object>> items = new ArrayList<>();
        for (OnboardingAuditView audit : api.listAudits(requireId(merchantId), adminQuery(req, session))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("auditId", audit.auditId());
            row.put("actionType", audit.actionType());
            row.put("operatorType", audit.operatorType());
            row.put("operatorId", audit.operatorId());
            row.put("reason", audit.reason());
            row.put("detail", detail(audit.detailJson()));
            row.put("createdAt", formatTime(audit.createdAt()));
            items.add(row);
        }
        return ok(items, req);
    }

    // ---- 4.12 Operator attachment upload ----------------------------------------------------

    @PostMapping(value = "/{merchantId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> upload(@PathVariable String merchantId, @RequestPart("file") MultipartFile file,
            HttpServletRequest req) throws IOException {
        onlyParameters(req);
        if (!(req instanceof MultipartHttpServletRequest multipart)
                || !multipart.getParameterMap().isEmpty()
                || !multipart.getMultiFileMap().keySet().equals(Set.of("file"))
                || multipart.getFiles("file").size() != 1
                || file == null || file.isEmpty() || file.getSize() > UPLOAD_MAX_BYTES) {
            throw invalid("附件须为 jpg/png 图片且不超过 5MB，且只能包含一个 file 部件");
        }
        AdminSessionView session = requireAdmin(req, "settlement.onboarding.write");
        MerchantOnboardingApi api = requireBean(commands, "商家收单开通服务未启用");
        CommandContext context = adminCommand(req, session);
        OnboardingAssetView stored;
        try (InputStream raw = file.getInputStream(); PushbackInputStream content = new PushbackInputStream(raw, 8)) {
            String mediaType = sniff(content);
            stored = api.uploadAttachment(context, merchantId, mediaType, file.getSize(), content);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assetId", stored.assetId());
        data.put("mediaType", stored.mediaType());
        data.put("bytes", stored.bytes());
        return ok(data, req);
    }

    /** The declared content type is ignored; the real type comes from the magic bytes. */
    private static String sniff(PushbackInputStream content) throws IOException {
        byte[] head = new byte[8];
        int read = 0;
        for (int n; read < head.length && (n = content.read(head, read, head.length - read)) != -1; ) read += n;
        content.unread(head, 0, read);
        if (read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (read >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return "image/png";
        }
        throw invalid("附件须为 jpg/png 图片");
    }

    // ---- View serializers (decimal-string IDs, ISO-8601 UTC millis) --------------------------

    private static Map<String, Object> defaultsView(OnboardingDefaultsView view) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merchantId", view.merchantId());
        m.put("eligible", view.eligible());
        m.put("ineligibleReason", view.ineligibleReason());
        m.put("merchantName", view.merchantName());
        m.put("merchantTypeCode", view.merchantTypeCode());
        m.put("cityCode", view.cityCode());
        m.put("districtCode", view.districtCode());
        m.put("configReady", view.configReady());
        m.put("missingConfig", view.missingConfig());
        m.put("platform", platformView(view.platform()));
        return m;
    }

    private static Map<String, Object> platformView(com.petplatform.payment.api.dto.OnboardingTypes.PlatformDefaultsView platform) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("posType", platform.posType());
        m.put("merBusiContent", platform.merBusiContent());
        m.put("settlePeriod", platform.settlePeriod());
        m.put("mccCode", platform.mccCode());
        m.put("feeRates", platform.feeRates().stream().map(fee -> Map.of(
                "feeRateTypeCode", String.valueOf(fee.feeRateTypeCode()),
                "feeRateTypeName", String.valueOf(fee.feeRateTypeName()),
                "feeRatePct", String.valueOf(fee.feeRatePct()))).toList());
        m.put("splitLowestRatio", platform.splitLowestRatio());
        m.put("platformReceiverConfigured", platform.platformReceiverConfigured());
        return m;
    }

    private static Map<String, Object> summaryView(OnboardingSummaryView view) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("onboardingId", view.onboardingId());
        m.put("merchantId", view.merchantId());
        m.put("status", view.status());
        m.put("merCupNo", view.merCupNo());
        m.put("termNo", view.termNo());
        m.put("rejectReason", view.rejectReason());
        m.put("submittedAt", formatTime(view.submittedAt()));
        m.put("completedAt", formatTime(view.completedAt()));
        m.put("updatedAt", formatTime(view.updatedAt()));
        return m;
    }

    private static Map<String, Object> view(OnboardingView view) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("onboardingId", view.onboardingId());
        m.put("merchantId", view.merchantId());
        m.put("status", view.status());
        m.put("version", view.version());
        Map<String, Object> merchant = new LinkedHashMap<>();
        merchant.put("merchantTypeCode", view.merchant().merchantTypeCode());
        merchant.put("merRegName", view.merchant().merRegName());
        merchant.put("merBizName", view.merchant().merBizName());
        merchant.put("merRegDistCode", view.merchant().merRegDistCode());
        merchant.put("merRegAddr", view.merchant().merRegAddr());
        merchant.put("merBlisName", view.merchant().merBlisName());
        merchant.put("merBlisNoMasked", view.merchant().merBlisNoMasked());
        merchant.put("merBlisStDt", view.merchant().merBlisStDt() == null ? null : view.merchant().merBlisStDt().toString());
        merchant.put("merBlisExpDt", view.merchant().merBlisExpDt() == null ? null : view.merchant().merBlisExpDt().toString());
        merchant.put("larName", view.merchant().larName());
        merchant.put("larIdType", view.merchant().larIdType());
        merchant.put("larIdcardMasked", view.merchant().larIdcardMasked());
        merchant.put("larIdcardStDt", view.merchant().larIdcardStDt() == null ? null : view.merchant().larIdcardStDt().toString());
        merchant.put("larIdcardExpDt", view.merchant().larIdcardExpDt() == null ? null : view.merchant().larIdcardExpDt().toString());
        merchant.put("larMobileMasked", view.merchant().larMobileMasked());
        merchant.put("contactName", view.merchant().contactName());
        merchant.put("contactMobileMasked", view.merchant().contactMobileMasked());
        m.put("merchant", merchant);
        Map<String, Object> settlement = new LinkedHashMap<>();
        settlement.put("acctTypeCode", view.settlement().acctTypeCode());
        settlement.put("acctName", view.settlement().acctName());
        settlement.put("acctNoMasked", view.settlement().acctNoMasked());
        settlement.put("acctNoLast4", view.settlement().acctNoLast4());
        settlement.put("acctIdType", view.settlement().acctIdType());
        settlement.put("acctIdcardMasked", view.settlement().acctIdcardMasked());
        settlement.put("acctIdExpDt", view.settlement().acctIdExpDt() == null ? null : view.settlement().acctIdExpDt().toString());
        settlement.put("openningBankCode", view.settlement().openningBankCode());
        settlement.put("openningBankName", view.settlement().openningBankName());
        settlement.put("clearingBankCode", view.settlement().clearingBankCode());
        m.put("settlement", settlement);
        m.put("platform", platformView(view.platform()));
        m.put("attachments", view.attachments());
        m.put("cooperationAssetId", view.cooperationAssetId());
        m.put("steps", view.steps().stream().map(AdminMerchantOnboardingController::stepView).toList());
        m.put("ecSignUrl", view.ecSignUrl());
        m.put("ecNo", view.ecNo());
        m.put("contractId", view.contractId());
        m.put("merCupNo", view.merCupNo());
        m.put("termNo", view.termNo());
        m.put("rejectReason", view.rejectReason());
        m.put("lastErrorCode", view.lastErrorCode());
        m.put("lastErrorMessage", view.lastErrorMessage());
        m.put("allowedActions", view.allowedActions());
        m.put("submittedAt", formatTime(view.submittedAt()));
        m.put("completedAt", formatTime(view.completedAt()));
        m.put("createdAt", formatTime(view.createdAt()));
        m.put("updatedAt", formatTime(view.updatedAt()));
        return m;
    }

    private static Map<String, Object> stepView(OnboardingStepView step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", step.step());
        m.put("status", step.status());
        m.put("submittedAt", formatTime(step.submittedAt()));
        m.put("finishedAt", formatTime(step.finishedAt()));
        m.put("summary", step.summary());
        m.put("reason", step.reason());
        return m;
    }

    private static JsonNode detail(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) return null;
        try {
            return STRICT.readTree(detailJson);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    // ---- Request parsing --------------------------------------------------------------------

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

    private static JsonNode requireObject(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isObject()) throw invalid(field + " 须为对象");
        return node;
    }

    private static void exactKeys(JsonNode node, Set<String> allowed, String label) {
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) throw invalid(label + " 包含不支持的字段：" + name);
        }
    }

    private static long requireVersion(JsonNode body) {
        JsonNode node = body.get("expectedVersion");
        if (node == null || !node.isIntegralNumber() || node.longValue() < 0) {
            throw invalid("expectedVersion 须为非负整数");
        }
        return node.longValue();
    }

    private static String requiredString(JsonNode body, String field) {
        String value = optionalString(body, field);
        if (value == null) throw invalid(field + " 不能为空");
        return value;
    }

    private static String optionalString(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isString()) throw invalid(field + " 须为字符串");
        String trimmed = node.asString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---- Auth & helpers (same rules as the other settlement controllers) ----------------------

    private static AdminSessionView requireAdmin(HttpServletRequest req, String action) {
        AdminSessionView session = requireSession(req);
        if (!session.permissions().actionCodes().contains(action)) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该收单开通操作");
        }
        return session;
    }

    private static AdminSessionView requireAdminAny(HttpServletRequest req, String... actions) {
        AdminSessionView session = requireSession(req);
        for (String action : actions) {
            if (session.permissions().actionCodes().contains(action)) return session;
        }
        throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该收单开通操作");
    }

    private static AdminSessionView requireSession(HttpServletRequest req) {
        Object attr = req.getAttribute(AdminBearerAuthenticationFilter.VIEW);
        if (attr instanceof AdminSessionView session) return session;
        throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
    }

    private static QueryContext adminQuery(HttpServletRequest req, AdminSessionView session) {
        return new QueryContext(trace(req), OperatorType.PLATFORM_OPERATOR, session.principal().operatorId());
    }

    private static CommandContext adminCommand(HttpServletRequest req, AdminSessionView session) {
        String requestId;
        try {
            requestId = PublicContractChecks.requireTerminalRequestId(req.getHeader("X-Request-Id"));
        } catch (RuntimeException bad) {
            throw invalid("缺少或非法的 X-Request-Id");
        }
        return new CommandContext(requestId, trace(req), OperatorType.PLATFORM_OPERATOR,
                session.principal().operatorId(), "ADMIN_WEB");
    }

    private static String requireId(String raw) {
        if (raw == null || !raw.trim().matches("[1-9][0-9]{0,18}")) throw invalid("ID 格式不合法");
        return raw.trim();
    }

    private static String optionalId(String raw) {
        return raw == null || raw.isBlank() ? null : requireId(raw);
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || page > 10_000 || size < 1 || size > 100) throw invalid("分页参数不合法");
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

    private static String formatTime(OffsetDateTime value) {
        return value == null ? null
                : new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value.toInstant());
    }

    private static ApiException invalid(String msg) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, msg);
    }
}
