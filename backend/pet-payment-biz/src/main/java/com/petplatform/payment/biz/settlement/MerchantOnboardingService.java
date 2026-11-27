package com.petplatform.payment.biz.settlement;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.api.command.MerchantOnboardingApi;
import com.petplatform.payment.api.dto.OnboardingTypes.AdoptContractCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.DiscardContractCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.FeeRateView;
import com.petplatform.payment.api.dto.OnboardingTypes.ListOnboardingsQuery;
import com.petplatform.payment.api.dto.OnboardingTypes.MerchantProfileInput;
import com.petplatform.payment.api.dto.OnboardingTypes.MerchantProfileView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingAssetView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingAuditView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingDefaultsView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingPage;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingSensitiveView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingStepView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingSummaryView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingView;
import com.petplatform.payment.api.dto.OnboardingTypes.PlatformDefaultsView;
import com.petplatform.payment.api.dto.OnboardingTypes.RevealOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.RetryOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SaveOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SettlementProfileInput;
import com.petplatform.payment.api.dto.OnboardingTypes.SettlementProfileView;
import com.petplatform.payment.api.dto.OnboardingTypes.SubmitOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SyncOnboardingCommand;
import com.petplatform.payment.api.query.MerchantOnboardingQueryApi;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentOnboardingStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentSettlementStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentOnboardingMapper.OnboardingAuditRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentOnboardingMapper.OnboardingRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.ConfigRow;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.BindApplication;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.BindApplied;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.BindProgress;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.ContractAdded;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.ContractApplication;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.ContractProgress;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.EcApplication;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.FeeRate;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.LedgerApplication;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.LedgerApplied;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.LedgerProgress;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel.Uploaded;
import com.petplatform.payment.biz.settlement.MerchantOnboardingEligibilityPort.MerchantOnboardingFacts;
import com.petplatform.payment.biz.settlement.MerchantOnboardingTasks.Outcome;
import com.petplatform.payment.biz.settlement.ReceiverAttachmentProvider.AssetContent;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Merchant acquiring onboarding state machine (CCR-PAY-ONBOARD-001 §4/§6, contract 81, Ruling 44):
 * electronic contract → 1020 进件 → 1284 开通分账 → 1290 bind to the platform receiver → the acquiring
 * pair is written into {@code settlement_config} by the system.
 *
 * <p>Invariants: channel calls never run inside a database transaction; every transition re-locks the row
 * and checks its version; a submission whose outcome is unknown is never resent blindly (进件 goes to
 * {@code CONTRACT_UNKNOWN}, the others are confirmed by merchant-number lookups); callbacks only move the
 * state forward and every accepted or ignored event is audited; plaintext sensitive values exist only in
 * memory between a locked read and the channel call.
 */
public final class MerchantOnboardingService implements MerchantOnboardingApi, MerchantOnboardingQueryApi {
    static final String DRAFT = "DRAFT";
    static final String EC_SIGNING = "EC_SIGNING";
    static final String CONTRACT_APPLYING = "CONTRACT_APPLYING";
    static final String CONTRACT_UNKNOWN = "CONTRACT_UNKNOWN";
    static final String CONTRACT_REJECTED = "CONTRACT_REJECTED";
    static final String LEDGER_APPLYING = "LEDGER_APPLYING";
    static final String LEDGER_REJECTED = "LEDGER_REJECTED";
    static final String BIND_APPLYING = "BIND_APPLYING";
    static final String BIND_REJECTED = "BIND_REJECTED";
    static final String ACTIVE = "ACTIVE";
    static final String SYSTEM = OperatorType.SYSTEM.name();

    private static final Set<String> WAITING = Set.of(EC_SIGNING, CONTRACT_APPLYING, LEDGER_APPLYING,
            BIND_APPLYING);
    private static final Set<String> PROFILE_EDITABLE = Set.of(DRAFT, EC_SIGNING, CONTRACT_REJECTED);
    /** Error codes that stop the automatic chain until an operator acts. */
    private static final Set<String> BLOCKING_ERRORS = Set.of("UPLOAD_REJECTED", "ADD_MER_REJECTED",
            "LEDGER_OUTCOME_UNKNOWN", "BIND_OUTCOME_UNKNOWN");
    private static final int DEFAULT_SETTLE_DAYS = 7;
    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.6000");
    /** Callback paths appended to the notification base URL; the bare base carries 进件 (1020 caps retUrl at 64). */
    static final String EC_CALLBACK_PATH = "/ec";
    static final String LEDGER_CALLBACK_PATH = "/ledger-open";
    static final String BIND_CALLBACK_PATH = "/bind";
    private static final int ADD_MER_RET_URL_LIMIT = 64;

    private static final String F_LICENSE_NO = "onboarding_mer_blis_no";
    private static final String F_LAR_IDCARD = "onboarding_lar_idcard";
    private static final String F_LAR_MOBILE = "onboarding_lar_mobile";
    private static final String F_CONTACT_MOBILE = "onboarding_contact_mobile";
    private static final String F_ACCT_NO = "onboarding_acct_no";
    private static final String F_ACCT_IDCARD = "onboarding_acct_idcard";

    private static final String ATT_COOPERATION = "SPLIT_COOPERATION_FILE";
    private static final Pattern ASSET_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern CHANNEL_TOKEN = Pattern.compile("[0-9A-Za-z]{1,32}");
    private static final Set<String> UPLOAD_TYPES = Set.of("image/jpeg", "image/png");
    private static final long UPLOAD_MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_AUDITS = 200;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmmss");
    private static final DateTimeFormatter SIGN_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** Deployment-provided platform parameters (CCR §10); blank values make "发起开通" fail closed. */
    public record Settings(
            String notifyBaseUrl,
            String orgCode,
            String posType,
            String merBusiContent,
            String settlePeriod,
            List<FeeRate> feeRates,
            String mccDefault,
            Map<String, String> mccByMerchantType,
            Map<String, String> districtByCity,
            BigDecimal lowestRatio,
            String platformReceiverNo,
            String cooperationAgreementName,
            String platformCompanyName,
            String ecOrgId) {
        public Settings {
            feeRates = feeRates == null ? List.of() : List.copyOf(feeRates);
            mccByMerchantType = mccByMerchantType == null ? Map.of() : Map.copyOf(mccByMerchantType);
            districtByCity = districtByCity == null ? Map.of() : Map.copyOf(districtByCity);
        }
    }

    /** Verifies the 1009 signature of each onboarding notification and parses its business payload. */
    public interface NotificationVerifier {
        EcNotice ec(Map<String, String> headers, byte[] rawBody);
        ContractProgress contract(Map<String, String> headers, byte[] rawBody);
        LedgerOpenNotice ledgerOpen(Map<String, String> headers, byte[] rawBody);
        BindNotice bind(Map<String, String> headers, byte[] rawBody);

        record EcNotice(String ecApplyId, String orderNo, boolean completed, String ecNo) {}

        record LedgerOpenNotice(String applyId, String merCupNo, boolean approved, String remark) {}

        record BindNotice(String applyId, String merCupNo, String receiverNo, boolean approved, String remark) {}
    }

    private final SnowflakeIdGenerator ids;
    private final PaymentOnboardingStore store;
    private final PaymentSettlementStore settlement;
    private final ReceiverSensitiveCipher cipher;
    private final ReceiverAttachmentProvider attachments;
    private final OnboardingAttachmentUploader uploader;
    private final MerchantOnboardingChannel channel;
    private final JdbcAsyncTaskSubmitter tasks;
    private final MerchantOnboardingEligibilityPort merchants;
    private final NotificationVerifier notifications;
    private final Settings settings;
    private final Clock clock;
    private final TransactionTemplate tx;

    public MerchantOnboardingService(
            DataSource source,
            SnowflakeIdGenerator ids,
            ReceiverSensitiveCipher cipher,
            ReceiverAttachmentProvider attachments,
            OnboardingAttachmentUploader uploader,
            MerchantOnboardingChannel channel,
            JdbcAsyncTaskSubmitter tasks,
            MerchantOnboardingEligibilityPort merchants,
            NotificationVerifier notifications,
            Settings settings,
            Clock clock) {
        Objects.requireNonNull(source, "source");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.uploader = Objects.requireNonNull(uploader, "uploader");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.merchants = Objects.requireNonNull(merchants, "merchants");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = new PaymentOnboardingStore(source);
        this.settlement = new PaymentSettlementStore(source);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(15);
    }

    // =====================================================================================
    // Commands (contract 81 §4)
    // =====================================================================================

    @Override
    public OnboardingView saveProfile(SaveOnboardingCommand command) {
        noOuterTransaction();
        requireAdmin(command == null ? null : command.context());
        String requestId = requireRequestId(command.context());
        long merchantId = parseId(command.merchantId());
        byte[] requestKey = requestKey("profile-save", command.context(), requestId);
        String fingerprint = fingerprintOf(command);

        return locked(merchantId, (row, now) -> {
            OnboardingRow replayed = replay(requestKey, merchantId, fingerprint);
            if (replayed != null) return view(replayed);
            if (row != null && !PROFILE_EDITABLE.contains(row.status)) {
                throw conflict("当前状态 " + row.status + " 不允许修改资料");
            }
            if (row != null && row.version != command.expectedVersion()) {
                throw conflict("资料已被修改，请刷新后重试");
            }
            if (row == null && command.expectedVersion() != 0) throw conflict("开通资料不存在");
            MerchantOnboardingValidator.Profile profile = MerchantOnboardingValidator.validate(
                    mergeKeptSecrets(command, row), today());
            OnboardingRow target = row == null ? newRow(merchantId, now) : row;
            writeProfile(target, profile);
            // A saved profile always restarts the chain: any electronic contract or 进件 result is void.
            resetElectronicContract(target);
            resetContract(target);
            target.status = DRAFT;
            target.rejectReason = null;
            target.lastErrorCode = null;
            target.lastErrorMessage = null;
            if (row == null) {
                // A new record is inserted at version 0 and then saved once, so every save bumps the version.
                store.insert(target, PaymentOnboardingStore.utc(now));
            }
            save(target, now);
            audit(target, "PROFILE_SAVE", requestKey, fingerprint, command.context(), profile.reason(),
                    Map.of("status", DRAFT, "acctNoLast4", nvl(target.acctNoLast4)), now);
            return view(target);
        });
    }

    @Override
    public OnboardingView submit(SubmitOnboardingCommand command) {
        noOuterTransaction();
        requireAdmin(command == null ? null : command.context());
        String requestId = requireRequestId(command.context());
        long merchantId = parseId(command.merchantId());
        byte[] requestKey = requestKey("submit", command.context(), requestId);
        String fingerprint = sha256("SUBMIT|" + merchantId + "|" + command.expectedVersion());
        MerchantOnboardingFacts facts = merchants.find(command.merchantId());

        SubmitPrep prep = locked(merchantId, (row, now) -> {
            OnboardingRow replayed = replay(requestKey, merchantId, fingerprint);
            if (replayed != null) return new SubmitPrep(replayed, 0L, null);
            if (row == null) throw notFound("开通资料不存在，请先保存资料");
            if (!DRAFT.equals(row.status)) throw conflict("当前状态 " + row.status + " 不能发起开通");
            if (row.version != command.expectedVersion()) throw conflict("资料已被修改，请刷新后重试");
            String reason = ineligibleReason(facts);
            if (reason != null) throw conflict(ineligibleMessage(reason));
            List<String> missing = missingConfig(facts, row.mccCode);
            if (!missing.isEmpty()) {
                throw unavailable("平台级配置缺失，无法发起开通：" + String.join("、", missing));
            }
            if (blank(row.cooperationAssetId)) throw invalid("请先上传《分账合作协议》");
            // Freeze the platform parameters of this attempt; later config changes never rewrite a submission.
            row.merchantTypeCode = facts.merchantTypeCode();
            row.posType = settings.posType().trim();
            row.merBusiContent = settings.merBusiContent().trim();
            row.settlePeriod = settings.settlePeriod().trim();
            row.feeDataJson = feeJson(settings.feeRates());
            row.splitLowestRatio = settings.lowestRatio().setScale(2, RoundingMode.UNNECESSARY);
            row.mccCode = mccFor(facts, row.mccCode);
            resetElectronicContract(row);
            row.ecOrderNo = nextOrderNo();
            row.lastErrorCode = null;
            row.lastErrorMessage = null;
            save(row, now);
            return new SubmitPrep(null, row.version, ecApplication(row));
        });
        if (prep.replayed() != null) return view(prep.replayed());

        final long version = prep.version();
        MerchantOnboardingChannel.EcApplied applied;
        try {
            applied = channel.applyElectronicContract(prep.ecApplication());
        } catch (MerchantOnboardingChannel.Rejected rejected) {
            locked(merchantId, (row, now) -> {
                if (row == null || row.version != version || !DRAFT.equals(row.status)) return null;
                row.ecOrderNo = null;
                row.lastErrorCode = "EC_REJECTED";
                row.lastErrorMessage = truncate("电子合同申请被拒：" + rejected.getMessage());
                save(row, now);
                audit(row, "EC_REJECTED", requestKey, fingerprint, command.context(), null,
                        Map.of("code", rejected.code()), now);
                return null;
            });
            throw conflict("拉卡拉拒绝电子合同申请：" + nvl(rejected.getMessage()));
        } catch (MerchantOnboardingChannel.Unknown unknown) {
            locked(merchantId, (row, now) -> {
                if (row == null || row.version != version || !DRAFT.equals(row.status)) return null;
                row.ecOrderNo = null;
                row.lastErrorCode = "EC_OUTCOME_UNKNOWN";
                row.lastErrorMessage = "电子合同申请结果未知，请重新发起开通";
                save(row, now);
                audit(row, "EC_UNKNOWN", requestKey, fingerprint, command.context(), null,
                        Map.of("reason", nvl(unknown.getMessage())), now);
                return null;
            });
            throw unavailable("电子合同申请结果未知，请核实后重新发起开通");
        }
        return locked(merchantId, (row, now) -> {
            if (row == null) throw notFound("开通资料不存在");
            if (row.version != version || !DRAFT.equals(row.status)) throw conflict("资料已被修改，请刷新后重试");
            row.status = EC_SIGNING;
            row.ecApplyId = truncate(applied.ecApplyId());
            row.ecSignUrl = applied.resultUrl();
            if (row.submittedAt == null) row.submittedAt = PaymentOnboardingStore.utc(now);
            row.lastErrorCode = null;
            row.lastErrorMessage = null;
            save(row, now);
            audit(row, "EC_APPLIED", requestKey, fingerprint, command.context(), null,
                    Map.of("ecOrderNo", nvl(row.ecOrderNo), "ecApplyId", nvl(row.ecApplyId)), now);
            enqueuePoll(row, now);
            return view(row);
        });
    }

    @Override
    public OnboardingView sync(SyncOnboardingCommand command) {
        noOuterTransaction();
        requireAdmin(command == null ? null : command.context());
        long merchantId = parseId(command.merchantId());
        OnboardingRow row = store.onboarding.selectByMerchantId(merchantId);
        if (row == null) throw notFound("开通资料不存在");
        if (!WAITING.contains(row.status)) throw conflict("当前状态 " + row.status + " 无需同步");
        // Either the next submission is still due (advance) or the channel owes an answer (poll).
        if (planAdvance(merchantId) == null && planQuery(merchantId) != null) {
            pollStep(merchantId, 0);
        } else {
            advanceStep(merchantId, 0);
        }
        locked(merchantId, (latest, now) -> {
            if (latest == null) throw notFound("开通资料不存在");
            audit(latest, "SYNC", null, null, command.context(), null, Map.of("status", latest.status), now);
            return null;
        });
        OnboardingRow latest = store.onboarding.selectByMerchantId(merchantId);
        if (latest == null) throw notFound("开通资料不存在");
        return view(latest);
    }

    @Override
    public OnboardingView retry(RetryOnboardingCommand command) {
        noOuterTransaction();
        requireAdmin(command == null ? null : command.context());
        String requestId = requireRequestId(command.context());
        long merchantId = parseId(command.merchantId());
        byte[] requestKey = requestKey("retry", command.context(), requestId);
        String fingerprint = sha256("RETRY|" + merchantId + "|" + command.expectedVersion() + "|"
                + nvl(command.cooperationAssetId()));
        return locked(merchantId, (row, now) -> {
            OnboardingRow replayed = replay(requestKey, merchantId, fingerprint);
            if (replayed != null) return view(replayed);
            if (row == null) throw notFound("开通资料不存在");
            if (row.version != command.expectedVersion()) throw conflict("资料已被修改，请刷新后重试");
            if (LEDGER_REJECTED.equals(row.status)) {
                row.status = LEDGER_APPLYING;
                row.ledgerOrderNo = null;
                row.ledgerApplyId = null;
                row.ledgerSubmittedAt = null;
            } else if (BIND_REJECTED.equals(row.status)) {
                row.status = BIND_APPLYING;
                if (!blank(command.cooperationAssetId())) {
                    row.cooperationAssetId = command.cooperationAssetId().trim();
                }
                row.bindOrderNo = null;
                row.bindApplyId = null;
                row.bindSubmittedAt = null;
            } else {
                throw conflict("当前状态 " + row.status + " 不能重新提交");
            }
            row.rejectReason = null;
            row.lastErrorCode = null;
            row.lastErrorMessage = null;
            save(row, now);
            audit(row, "RETRY", requestKey, fingerprint, command.context(), command.reason(),
                    Map.of("status", row.status), now);
            enqueueAdvance(row, now);
            return view(row);
        });
    }

    @Override
    public OnboardingView adoptContract(AdoptContractCommand command) {
        noOuterTransaction();
        requireAdmin(command == null ? null : command.context());
        String requestId = requireRequestId(command.context());
        long merchantId = parseId(command.merchantId());
        byte[] requestKey = requestKey("contract-adopt", command.context(), requestId);
        String contractId = command.contractId() == null ? "" : command.contractId().trim();
        if (!CHANNEL_TOKEN.matcher(contractId).matches()) throw invalid("进件 ID 格式不正确");
        String reason = command.reason() == null ? "" : command.reason().trim();
        if (reason.length() < 2 || reason.length() > 256) throw invalid("原因须为 2～256 字");
        String fingerprint = sha256("ADOPT|" + merchantId + "|" + command.expectedVersion() + "|" + contractId);
        return locked(merchantId, (row, now) -> {
            OnboardingRow replayed = replay(requestKey, merchantId, fingerprint);
            if (replayed != null) return view(replayed);
            if (row == null) throw notFound("开通资料不存在");
            if (row.version != command.expectedVersion()) throw conflict("资料已被修改，请刷新后重试");
            if (!CONTRACT_UNKNOWN.equals(row.status)) throw conflict("当前状态 " + row.status + " 不能补录进件 ID");
            row.contractId = contractId;
            row.status = CONTRACT_APPLYING;
            row.lastErrorCode = null;
            row.lastErrorMessage = null;
            save(row, now);
            audit(row, "CONTRACT_ADOPTED", requestKey, fingerprint, command.context(), reason,
                    Map.of("contractId", contractId), now);
            enqueuePoll(row, now);
            return view(row);
        });
    }

    @Override
    public OnboardingView discardContract(DiscardContractCommand command) {
        noOuterTransaction();
        requireAdmin(command == null ? null : command.context());
        String requestId = requireRequestId(command.context());
        long merchantId = parseId(command.merchantId());
        byte[] requestKey = requestKey("contract-discard", command.context(), requestId);
        String reason = command.reason() == null ? "" : command.reason().trim();
        if (reason.length() < 2 || reason.length() > 256) throw invalid("原因须为 2～256 字");
        String fingerprint = sha256("DISCARD|" + merchantId + "|" + command.expectedVersion() + "|" + reason);
        return locked(merchantId, (row, now) -> {
            OnboardingRow replayed = replay(requestKey, merchantId, fingerprint);
            if (replayed != null) return view(replayed);
            if (row == null) throw notFound("开通资料不存在");
            if (row.version != command.expectedVersion()) throw conflict("资料已被修改，请刷新后重试");
            if (!CONTRACT_UNKNOWN.equals(row.status)) throw conflict("当前状态 " + row.status + " 不能确认未受理");
            resetContract(row);
            resetElectronicContract(row);
            row.status = DRAFT;
            row.lastErrorCode = null;
            row.lastErrorMessage = null;
            save(row, now);
            audit(row, "CONTRACT_DISCARDED", requestKey, fingerprint, command.context(), reason,
                    Map.of("status", DRAFT), now);
            return view(row);
        });
    }

    @Override
    public OnboardingSensitiveView reveal(RevealOnboardingCommand command) {
        noOuterTransaction();
        requireAdmin(command == null ? null : command.context());
        String requestId = requireRequestId(command.context());
        String reason = command.reason() == null ? "" : command.reason().trim();
        if (reason.length() < 2 || reason.length() > 256) throw invalid("原因须为 2～256 字");
        long merchantId = parseId(command.merchantId());
        byte[] requestKey = requestKey("reveal", command.context(), requestId);
        return locked(merchantId, (row, now) -> {
            if (store.onboarding.selectAuditByRequestKeyForUpdate(requestKey) != null) {
                throw idempotencyConflict("同一 requestId 不能重复查看明文");
            }
            if (row == null) throw notFound("开通资料不存在");
            audit(row, "REVEAL", requestKey, null, command.context(), reason, null, now);
            return new OnboardingSensitiveView(command.merchantId(),
                    decrypt(row, F_LICENSE_NO, row.merBlisNoCipher), row.larName,
                    decrypt(row, F_LAR_IDCARD, row.larIdcardCipher),
                    decrypt(row, F_LAR_MOBILE, row.larMobileCipher), row.contactName,
                    decrypt(row, F_CONTACT_MOBILE, row.contactMobileCipher), row.acctName,
                    decrypt(row, F_ACCT_NO, row.acctNoCipher), row.acctIdType,
                    decrypt(row, F_ACCT_IDCARD, row.acctIdcardCipher));
        });
    }

    @Override
    public OnboardingAssetView uploadAttachment(CommandContext context, String merchantId, String declaredMediaType,
            long declaredBytes, InputStream content) {
        noOuterTransaction();
        requireAdmin(context);
        requireRequestId(context);
        parseId(merchantId);
        String media = declaredMediaType == null ? "" : declaredMediaType.split(";", 2)[0].trim().toLowerCase();
        if (!UPLOAD_TYPES.contains(media) || declaredBytes < 1 || declaredBytes > UPLOAD_MAX_BYTES
                || content == null) {
            throw invalid("附件须为 jpg/png 图片且不超过 5MB");
        }
        MerchantOnboardingFacts facts = merchants.find(merchantId);
        if (facts == null) throw notFound("商家不存在");
        String assetId = uploader.upload(facts.ownerUserId(), media, declaredBytes, content, context);
        if (assetId == null || !ASSET_ID.matcher(assetId).matches()) {
            throw unavailable("私有素材存储未返回有效素材编号");
        }
        return new OnboardingAssetView(assetId, media, declaredBytes);
    }

    // =====================================================================================
    // Queries (contract 81 §4.1–§4.3, §4.11)
    // =====================================================================================

    @Override
    public OnboardingDefaultsView defaults(String merchantId, QueryContext context) {
        requireAdminQuery(context);
        long id = parseId(merchantId);
        MerchantOnboardingFacts facts = merchants.find(merchantId);
        String reason = ineligibleReason(facts);
        List<String> missing = missingConfig(facts, null);
        String mcc = facts == null ? null : mccFor(facts, null);
        return new OnboardingDefaultsView(IDS.toApi(id), reason == null, reason,
                facts == null ? null : facts.merchantName(),
                facts == null ? null : facts.merchantTypeCode(),
                facts == null ? null : facts.cityCode(),
                facts == null || facts.cityCode() == null ? null : settings.districtByCity().get(facts.cityCode()),
                missing.isEmpty(), missing, platformView(settings.posType(), settings.merBusiContent(),
                        settings.settlePeriod(), mcc, settings.feeRates(), settings.lowestRatio()));
    }

    @Override
    public OnboardingView getByMerchantId(String merchantId, QueryContext context) {
        requireAdminQuery(context);
        OnboardingRow row = store.onboarding.selectByMerchantId(parseId(merchantId));
        if (row == null) throw notFound("开通资料不存在");
        return view(row);
    }

    @Override
    public OnboardingPage list(ListOnboardingsQuery query) {
        requireAdminQuery(query == null ? null : query.context());
        int size = query.size();
        int page = query.page();
        if (page < 1 || page > 10_000 || size < 1 || size > MAX_PAGE_SIZE) throw invalid("分页参数不合法");
        String status = blank(query.status()) ? null : query.status().trim();
        Long merchantId = blank(query.merchantId()) ? null : parseId(query.merchantId());
        Map<String, Object> params = PaymentFoundationStore.values(
                "merchantId", merchantId, "status", status, "limit", size, "offset", (long) (page - 1) * size);
        List<OnboardingSummaryView> items = new ArrayList<>();
        for (OnboardingRow row : store.onboarding.listOnboardings(params)) items.add(summary(row));
        return new OnboardingPage(items, store.onboarding.countOnboardings(params), page, size);
    }

    @Override
    public List<OnboardingAuditView> listAudits(String merchantId, QueryContext context) {
        requireAdminQuery(context);
        long id = parseId(merchantId);
        List<OnboardingAuditView> out = new ArrayList<>();
        for (OnboardingAuditRow audit : store.onboarding.listAuditsByMerchant(
                PaymentFoundationStore.values("merchantId", id, "limit", MAX_AUDITS))) {
            out.add(new OnboardingAuditView(IDS.toApi(audit.id), audit.actionType, audit.operatorType,
                    audit.operatorId == null ? null : IDS.toApi(audit.operatorId), audit.reason,
                    audit.detailJson, time(audit.createdAt)));
        }
        return out;
    }

    // =====================================================================================
    // Notifications (contract 81 §5): signature first, then a locked, idempotent transition
    // =====================================================================================

    public void receiveEcNotification(Map<String, String> headers, byte[] rawBody) {
        noOuterTransaction();
        NotificationVerifier.EcNotice notice;
        try {
            notice = notifications.ec(headers, rawBody);
        } catch (LakalaProtocol.ProtocolException bad) {
            throw invalid("验签或报文校验失败");
        }
        OnboardingRow located = blank(notice.ecApplyId()) ? null : store.onboarding.selectByEcApplyId(notice.ecApplyId());
        if (located == null && !blank(notice.orderNo())) located = store.onboarding.selectByEcOrderNo(notice.orderNo());
        if (located == null) throw notFound("电子合同申请不存在");
        locked(located.merchantId, (row, now) -> {
            if (row == null) throw notFound("开通资料不存在");
            boolean same = (blank(notice.ecApplyId()) || notice.ecApplyId().equals(row.ecApplyId))
                    && (blank(notice.orderNo()) || notice.orderNo().equals(row.ecOrderNo));
            if (!EC_SIGNING.equals(row.status) || row.ecNo != null || !same) {
                audit(row, "EC_NOTIFY_IGNORED", null, null, null, null, Map.of("status", row.status), now);
                return null;
            }
            if (!notice.completed() || blank(notice.ecNo())) {
                audit(row, "EC_NOTIFY_PENDING", null, null, null, null, null, now);
                return null;
            }
            row.ecNo = truncate(notice.ecNo());
            row.ecSignedAt = PaymentOnboardingStore.utc(now);
            row.ecSignUrl = null;
            row.lastErrorCode = null;
            row.lastErrorMessage = null;
            save(row, now);
            audit(row, "EC_SIGNED", null, null, null, null, Map.of("ecNo", row.ecNo), now);
            enqueueAdvance(row, now);
            return null;
        });
    }

    public void receiveContractNotification(Map<String, String> headers, byte[] rawBody) {
        noOuterTransaction();
        ContractProgress progress;
        try {
            progress = notifications.contract(headers, rawBody);
        } catch (LakalaProtocol.ProtocolException bad) {
            throw invalid("验签或报文校验失败");
        }
        if (blank(progress.contractId())) throw invalid("进件回调缺少进件 ID");
        OnboardingRow located = store.onboarding.selectByContractId(progress.contractId());
        if (located == null) throw notFound("进件不存在");
        locked(located.merchantId, (row, now) -> {
            if (row == null) throw notFound("开通资料不存在");
            if (!CONTRACT_APPLYING.equals(row.status) || !progress.contractId().equals(row.contractId)) {
                audit(row, "CONTRACT_NOTIFY_IGNORED", null, null, null, null,
                        contractDetail(progress, row.status), now);
                return null;
            }
            if (progress.approved()) {
                applyContractApproved(row, progress, "CONTRACT_APPROVED", now);
            } else if (progress.rejected()) {
                row.status = CONTRACT_REJECTED;
                row.contractStatus = progress.contractStatus();
                row.contractDecidedAt = PaymentOnboardingStore.utc(now);
                row.rejectReason = truncate(blank(progress.contractMemo()) ? "拉卡拉驳回进件" : progress.contractMemo());
                row.lastErrorCode = null;
                row.lastErrorMessage = null;
                save(row, now);
                audit(row, "CONTRACT_REJECTED", null, null, null, null, contractDetail(progress, row.status), now);
            } else {
                row.contractStatus = progress.contractStatus();
                save(row, now);
                audit(row, "CONTRACT_NOTIFY_PENDING", null, null, null, null,
                        contractDetail(progress, row.status), now);
            }
            return null;
        });
    }

    public void receiveLedgerOpenNotification(Map<String, String> headers, byte[] rawBody) {
        noOuterTransaction();
        NotificationVerifier.LedgerOpenNotice notice;
        try {
            notice = notifications.ledgerOpen(headers, rawBody);
        } catch (LakalaProtocol.ProtocolException bad) {
            throw invalid("验签或报文校验失败");
        }
        OnboardingRow located = blank(notice.applyId()) ? null : store.onboarding.selectByLedgerApplyId(notice.applyId());
        if (located == null && !blank(notice.merCupNo())) located = store.onboarding.selectByMerCupNo(notice.merCupNo());
        if (located == null) throw notFound("开通分账申请不存在");
        locked(located.merchantId, (row, now) -> {
            if (row == null) throw notFound("开通资料不存在");
            boolean same = (blank(notice.applyId()) || notice.applyId().equals(row.ledgerApplyId))
                    && (blank(notice.merCupNo()) || notice.merCupNo().equals(row.merCupNo));
            if (!LEDGER_APPLYING.equals(row.status) || row.ledgerOpenedAt != null || !same) {
                audit(row, "LEDGER_NOTIFY_IGNORED", null, null, null, null, Map.of("status", row.status), now);
                return null;
            }
            applyLedgerDecided(row, notice.approved(), notice.remark(), now);
            return null;
        });
    }

    public void receiveBindNotification(Map<String, String> headers, byte[] rawBody) {
        noOuterTransaction();
        NotificationVerifier.BindNotice notice;
        try {
            notice = notifications.bind(headers, rawBody);
        } catch (LakalaProtocol.ProtocolException bad) {
            throw invalid("验签或报文校验失败");
        }
        if (blank(notice.receiverNo()) || !notice.receiverNo().equals(settings.platformReceiverNo().trim())) {
            throw notFound("接收方不属于本平台");
        }
        OnboardingRow located = blank(notice.applyId()) ? null : store.onboarding.selectByBindApplyId(notice.applyId());
        if (located == null && !blank(notice.merCupNo())) located = store.onboarding.selectByMerCupNo(notice.merCupNo());
        if (located == null) throw notFound("绑定申请不存在");
        locked(located.merchantId, (row, now) -> {
            if (row == null) throw notFound("开通资料不存在");
            boolean same = (blank(notice.applyId()) || notice.applyId().equals(row.bindApplyId))
                    && (blank(notice.merCupNo()) || notice.merCupNo().equals(row.merCupNo));
            if (!BIND_APPLYING.equals(row.status) || !same) {
                audit(row, "BIND_NOTIFY_IGNORED", null, null, null, null, Map.of("status", row.status), now);
                return null;
            }
            applyBindDecided(row, notice.approved(), notice.remark(), now);
            return null;
        });
    }

    // =====================================================================================
    // Durable task steps (contract 81 §6)
    // =====================================================================================

    /** Submits the next step outside any request or callback thread; business outcomes never throw. */
    public Outcome advanceStep(long merchantId, int retryCount) {
        Plan plan = planAdvance(merchantId);
        if (plan == null) return Outcome.DONE;
        try {
            switch (plan.kind()) {
                case "CONTRACT" -> submitContract(plan);
                case "LEDGER" -> submitLedger(plan);
                default -> submitBind(plan);
            }
            return Outcome.DONE;
        } catch (RuntimeException failure) {
            // Raised before the submission call (attachment read/upload) or by persistence: retried with backoff.
            recordAdvanceFailure(plan, failure);
            return Outcome.PENDING;
        }
    }

    /** One channel query of the current waiting step; the callback-missed fallback. */
    public Outcome pollStep(long merchantId, int retryCount) {
        QueryPlan plan = planQuery(merchantId);
        if (plan == null) return Outcome.DONE;
        boolean settled;
        try {
            settled = switch (plan.kind()) {
                case "EC" -> pollElectronicContract(plan);
                case "CONTRACT" -> pollContract(plan);
                case "LEDGER" -> pollLedger(plan);
                default -> pollBind(plan);
            };
        } catch (MerchantOnboardingChannel.Rejected rejected) {
            recordPollRejection(plan, rejected);
            return Outcome.PENDING;
        } catch (MerchantOnboardingChannel.Unknown unknown) {
            return Outcome.PENDING;
        }
        if (settled) return Outcome.DONE;
        if (retryCount >= MerchantOnboardingTasks.POLL_MAX_RETRY - 1) {
            timeout(plan);
            return Outcome.DONE;
        }
        return Outcome.PENDING;
    }

    // ---- plans: locked reads that fix the next order number before anything is sent ----------

    private record Plan(String kind, long merchantId, long version, String orderNo, String merCupNo,
            ContractApplication contract, LedgerApplication ledger, String cooperationAssetId) {}

    private record QueryPlan(String kind, long merchantId, long version, String token, String merCupNo) {}

    private record SubmitPrep(OnboardingRow replayed, long version, EcApplication ecApplication) {}

    private Plan planAdvance(long merchantId) {
        return locked(merchantId, (row, now) -> {
            if (row == null || BLOCKING_ERRORS.contains(nvl(row.lastErrorCode))) return null;
            if (EC_SIGNING.equals(row.status) && row.ecNo != null) {
                if (blank(row.contractOrderNo)) {
                    row.contractOrderNo = nextOrderNo();
                    save(row, now);
                }
                return new Plan("CONTRACT", merchantId, row.version, row.contractOrderNo, null,
                        contractApplication(row), null, null);
            }
            if (LEDGER_APPLYING.equals(row.status) && row.merCupNo != null && row.ledgerOpenedAt == null
                    && row.ledgerSubmittedAt == null) {
                if (blank(row.ledgerOrderNo)) {
                    row.ledgerOrderNo = nextOrderNo();
                    save(row, now);
                }
                return new Plan("LEDGER", merchantId, row.version, row.ledgerOrderNo, row.merCupNo,
                        null, ledgerApplication(row), null);
            }
            if (BIND_APPLYING.equals(row.status) && row.bindSubmittedAt == null) {
                if (blank(row.cooperationAssetId)) return null;
                if (blank(row.bindOrderNo)) {
                    row.bindOrderNo = nextOrderNo();
                    save(row, now);
                }
                return new Plan("BIND", merchantId, row.version, row.bindOrderNo, row.merCupNo,
                        null, null, row.cooperationAssetId);
            }
            return null;
        });
    }

    private QueryPlan planQuery(long merchantId) {
        return locked(merchantId, (row, now) -> {
            if (row == null) return null;
            if (EC_SIGNING.equals(row.status) && row.ecApplyId != null && row.ecNo == null) {
                return new QueryPlan("EC", merchantId, row.version, row.ecOrderNo, null);
            }
            if (CONTRACT_APPLYING.equals(row.status) && row.contractId != null) {
                return new QueryPlan("CONTRACT", merchantId, row.version, row.contractId, null);
            }
            if (LEDGER_APPLYING.equals(row.status) && row.ledgerSubmittedAt != null && row.ledgerOpenedAt == null) {
                return new QueryPlan("LEDGER", merchantId, row.version, row.ledgerOrderNo, row.merCupNo);
            }
            if (BIND_APPLYING.equals(row.status) && row.bindSubmittedAt != null) {
                return new QueryPlan("BIND", merchantId, row.version, nvl(row.bindApplyId), row.merCupNo);
            }
            return null;
        });
    }

    // ---- step submissions --------------------------------------------------------------

    private void submitContract(Plan plan) {
        List<Uploaded> files = new ArrayList<>();
        for (Map.Entry<String, String> entry : attachmentsOf(plan.merchantId()).entrySet()) {
            AssetContent content = attachments.readAsset(entry.getValue());
            if (content == null) throw unavailable("附件读取失败：" + entry.getKey());
            files.add(channel.uploadAttachment(plan.orderNo(), settings.orgCode().trim(), entry.getKey(),
                    content.extName(), content.bytes()));
        }
        ContractAdded added = null;
        MerchantOnboardingChannel.Rejected rejected = null;
        MerchantOnboardingChannel.Unknown unknown = null;
        try {
            added = channel.addMerchant(withFiles(plan.contract(), files));
        } catch (MerchantOnboardingChannel.Rejected cause) {
            rejected = cause;
        } catch (MerchantOnboardingChannel.Unknown cause) {
            unknown = cause;
        }
        final ContractAdded created = added;
        final MerchantOnboardingChannel.Rejected refused = rejected;
        final MerchantOnboardingChannel.Unknown unresolved = unknown;
        locked(plan.merchantId(), (row, now) -> {
            if (row == null) return null;
            if (created != null) {
                // 进件 always wins over a concurrent operator edit: the merchant now exists at Lakala.
                row.contractId = truncate(created.contractId());
                row.status = CONTRACT_APPLYING;
                row.contractSubmittedAt = PaymentOnboardingStore.utc(now);
                row.lastErrorCode = null;
                row.lastErrorMessage = null;
                save(row, now);
                audit(row, "CONTRACT_SUBMITTED", null, null, null, null, Map.of("contractId", row.contractId), now);
                enqueuePoll(row, now);
            } else if (unresolved != null) {
                row.status = CONTRACT_UNKNOWN;
                row.lastErrorCode = "ADD_MER_OUTCOME_UNKNOWN";
                row.lastErrorMessage = "进件请求结果未知，请向拉卡拉核实后补录进件 ID 或确认未受理";
                save(row, now);
                audit(row, "CONTRACT_UNKNOWN", null, null, null, null,
                        Map.of("contractOrderNo", nvl(row.contractOrderNo)), now);
            } else if (row.version == plan.version() && EC_SIGNING.equals(row.status)) {
                row.status = DRAFT;
                resetElectronicContract(row);
                resetContract(row);
                row.lastErrorCode = "ADD_MER_REJECTED";
                row.lastErrorMessage = truncate("进件被拒：" + refused.getMessage());
                save(row, now);
                audit(row, "CONTRACT_REJECTED", null, null, null, null,
                        Map.of("code", refused.code(), "message", nvl(refused.getMessage())), now);
            }
            return null;
        });
    }

    private void submitLedger(Plan plan) {
        LedgerApplied applied = null;
        MerchantOnboardingChannel.Rejected rejected = null;
        MerchantOnboardingChannel.Unknown unknown = null;
        try {
            applied = channel.applyLedger(plan.ledger());
        } catch (MerchantOnboardingChannel.Rejected cause) {
            rejected = cause;
        } catch (MerchantOnboardingChannel.Unknown cause) {
            unknown = cause;
        }
        final LedgerApplied accepted = applied;
        final MerchantOnboardingChannel.Rejected refused = rejected;
        final MerchantOnboardingChannel.Unknown unresolved = unknown;
        locked(plan.merchantId(), (row, now) -> {
            if (row == null) return null;
            if (row.version != plan.version() || !LEDGER_APPLYING.equals(row.status)
                    || row.ledgerSubmittedAt != null) {
                audit(row, "LEDGER_RESULT_IGNORED", null, null, null, null, Map.of("status", row.status), now);
                return null;
            }
            row.ledgerSubmittedAt = PaymentOnboardingStore.utc(now);
            if (accepted != null) {
                row.ledgerApplyId = truncate(accepted.applyId());
                row.lastErrorCode = null;
                row.lastErrorMessage = null;
                save(row, now);
                audit(row, "LEDGER_SUBMITTED", null, null, null, null, Map.of("applyId", nvl(row.ledgerApplyId)), now);
                enqueuePoll(row, now);
            } else if (unresolved != null) {
                row.lastErrorCode = "LEDGER_OUTCOME_UNKNOWN";
                row.lastErrorMessage = "开通分账申请结果未知，系统将定时向拉卡拉核实";
                save(row, now);
                audit(row, "LEDGER_UNKNOWN", null, null, null, null,
                        Map.of("ledgerOrderNo", nvl(row.ledgerOrderNo)), now);
                enqueuePoll(row, now);
            } else {
                row.status = LEDGER_REJECTED;
                row.rejectReason = truncate("拉卡拉拒绝开通分账：" + refused.getMessage());
                row.lastErrorCode = null;
                row.lastErrorMessage = null;
                save(row, now);
                audit(row, "LEDGER_REJECTED", null, null, null, null,
                        Map.of("code", refused.code(), "message", nvl(refused.getMessage())), now);
            }
            return null;
        });
    }

    private void submitBind(Plan plan) {
        AssetContent content = attachments.readAsset(plan.cooperationAssetId());
        if (content == null) throw unavailable("合作协议读取失败");
        Uploaded file = channel.uploadAttachment(plan.orderNo(), settings.orgCode().trim(), ATT_COOPERATION,
                content.extName(), content.bytes());
        BindApplied applied = null;
        MerchantOnboardingChannel.Rejected rejected = null;
        MerchantOnboardingChannel.Unknown unknown = null;
        try {
            applied = channel.applyBind(new BindApplication(plan.orderNo(), settings.orgCode().trim(),
                    plan.merCupNo(), settings.platformReceiverNo().trim(), fileName(content), file,
                    notifyUrl(BIND_CALLBACK_PATH)));
        } catch (MerchantOnboardingChannel.Rejected cause) {
            rejected = cause;
        } catch (MerchantOnboardingChannel.Unknown cause) {
            unknown = cause;
        }
        final BindApplied accepted = applied;
        final MerchantOnboardingChannel.Rejected refused = rejected;
        final MerchantOnboardingChannel.Unknown unresolved = unknown;
        locked(plan.merchantId(), (row, now) -> {
            if (row == null) return null;
            if (row.version != plan.version() || !BIND_APPLYING.equals(row.status) || row.bindSubmittedAt != null) {
                audit(row, "BIND_RESULT_IGNORED", null, null, null, null, Map.of("status", row.status), now);
                return null;
            }
            row.bindSubmittedAt = PaymentOnboardingStore.utc(now);
            if (accepted != null) {
                row.bindApplyId = truncate(accepted.applyId());
                row.lastErrorCode = null;
                row.lastErrorMessage = null;
                save(row, now);
                audit(row, "BIND_SUBMITTED", null, null, null, null, Map.of("applyId", nvl(row.bindApplyId)), now);
                enqueuePoll(row, now);
            } else if (unresolved != null) {
                row.lastErrorCode = "BIND_OUTCOME_UNKNOWN";
                row.lastErrorMessage = "绑定申请结果未知，系统将定时向拉卡拉核实";
                save(row, now);
                audit(row, "BIND_UNKNOWN", null, null, null, null,
                        Map.of("bindOrderNo", nvl(row.bindOrderNo)), now);
                enqueuePoll(row, now);
            } else {
                row.status = BIND_REJECTED;
                row.rejectReason = truncate("拉卡拉拒绝绑定：" + refused.getMessage());
                row.lastErrorCode = null;
                row.lastErrorMessage = null;
                save(row, now);
                audit(row, "BIND_REJECTED", null, null, null, null,
                        Map.of("code", refused.code(), "message", nvl(refused.getMessage())), now);
            }
            return null;
        });
    }

    // ---- step polls ----------------------------------------------------------------------

    /** @return true when this waiting step is settled (moved on, decided or no longer relevant). */
    private boolean pollElectronicContract(QueryPlan plan) {
        MerchantOnboardingChannel.EcProgress progress = channel.queryElectronicContract(plan.token(),
                settings.orgCode().trim(), ecApplyIdOf(plan.merchantId()));
        if (!progress.completed() || blank(progress.ecNo())) return false;
        return locked(plan.merchantId(), (row, now) -> {
            if (row == null) return true;
            if (!EC_SIGNING.equals(row.status) || row.ecNo != null || row.version != plan.version()) return true;
            row.ecNo = truncate(progress.ecNo());
            row.ecSignedAt = PaymentOnboardingStore.utc(now);
            row.ecSignUrl = null;
            row.lastErrorCode = null;
            row.lastErrorMessage = null;
            save(row, now);
            audit(row, "EC_SIGNED", null, null, null, null, Map.of("ecNo", row.ecNo, "source", "QUERY"), now);
            enqueueAdvance(row, now);
            return true;
        });
    }

    private boolean pollContract(QueryPlan plan) {
        ContractProgress progress = channel.queryContract(contractOrderNoOf(plan.merchantId()),
                settings.orgCode().trim(), plan.token());
        return locked(plan.merchantId(), (row, now) -> {
            if (row == null || !CONTRACT_APPLYING.equals(row.status) || row.version != plan.version()) return true;
            if (progress.approved()) {
                applyContractApproved(row, progress, "CONTRACT_APPROVED_BY_QUERY", now);
                return true;
            }
            if (progress.rejected()) {
                row.status = CONTRACT_REJECTED;
                row.contractStatus = progress.contractStatus();
                row.contractDecidedAt = PaymentOnboardingStore.utc(now);
                row.rejectReason = truncate(blank(progress.contractMemo()) ? "拉卡拉驳回进件" : progress.contractMemo());
                save(row, now);
                audit(row, "CONTRACT_REJECTED", null, null, null, null,
                        contractDetail(progress, row.status), now);
                return true;
            }
            row.contractStatus = progress.contractStatus();
            save(row, now);
            audit(row, "CONTRACT_QUERY_PENDING", null, null, null, null, contractDetail(progress, row.status), now);
            return false;
        });
    }

    private boolean pollLedger(QueryPlan plan) {
        LedgerProgress progress = channel.queryLedger(queryOrderNo(plan), settings.orgCode().trim(), plan.merCupNo());
        if (!progress.valid()) return false;
        return locked(plan.merchantId(), (row, now) -> {
            if (row == null || !LEDGER_APPLYING.equals(row.status) || row.ledgerOpenedAt != null) return true;
            applyLedgerDecided(row, true, "分账业务已开通（查询确认）", now);
            return true;
        });
    }

    private boolean pollBind(QueryPlan plan) {
        BindProgress progress = channel.queryBind(queryOrderNo(plan), settings.orgCode().trim(), plan.merCupNo(),
                settings.platformReceiverNo().trim(), blank(plan.token()) ? null : plan.token());
        if (!progress.decided()) return false;
        return locked(plan.merchantId(), (row, now) -> {
            if (row == null || !BIND_APPLYING.equals(row.status)) return true;
            applyBindDecided(row, progress.approved(), progress.remark(), now);
            return true;
        });
    }

    // ---- transitions (always under the row lock) --------------------------------------------

    private void applyContractApproved(OnboardingRow row, ContractProgress progress, String action, OffsetDateTime now) {
        row.merInnerNo = blank(progress.merInnerNo()) ? null : truncate(progress.merInnerNo());
        row.merCupNo = truncate(progress.merCupNo());
        row.termNo = truncate(progress.termNo());
        row.contractStatus = progress.contractStatus();
        row.contractDecidedAt = PaymentOnboardingStore.utc(now);
        row.status = LEDGER_APPLYING;
        row.rejectReason = null;
        row.lastErrorCode = null;
        row.lastErrorMessage = null;
        save(row, now);
        audit(row, action, null, null, null, null,
                Map.of("merCupNo", row.merCupNo, "termNo", nvl(row.termNo)), now);
        enqueueAdvance(row, now);
    }

    private void applyLedgerDecided(OnboardingRow row, boolean approved, String remark, OffsetDateTime now) {
        row.lastErrorCode = null;
        row.lastErrorMessage = null;
        if (approved) {
            row.ledgerOpenedAt = PaymentOnboardingStore.utc(now);
            row.status = BIND_APPLYING;
            row.rejectReason = null;
            save(row, now);
            audit(row, "LEDGER_OPENED", null, null, null, null,
                    Map.of("applyId", nvl(row.ledgerApplyId)), now);
            enqueueAdvance(row, now);
            return;
        }
        row.status = LEDGER_REJECTED;
        row.rejectReason = truncate(blank(remark) ? "拉卡拉拒绝开通分账" : remark);
        save(row, now);
        audit(row, "LEDGER_REJECTED", null, null, null, null,
                Map.of("applyId", nvl(row.ledgerApplyId), "remark", nvl(remark)), now);
    }

    private void applyBindDecided(OnboardingRow row, boolean approved, String remark, OffsetDateTime now) {
        row.lastErrorCode = null;
        row.lastErrorMessage = null;
        if (!approved) {
            row.status = BIND_REJECTED;
            row.rejectReason = truncate(blank(remark) ? "拉卡拉拒绝绑定" : remark);
            save(row, now);
            audit(row, "BIND_REJECTED", null, null, null, null,
                    Map.of("applyId", nvl(row.bindApplyId), "remark", nvl(remark)), now);
            return;
        }
        // 开通完成: the system becomes the owner of the acquiring pair in settlement_config (Ruling 44 §2.6).
        row.status = ACTIVE;
        row.bindApprovedAt = PaymentOnboardingStore.utc(now);
        row.completedAt = row.bindApprovedAt;
        row.rejectReason = null;
        save(row, now);
        writeAcquiringConfig(row, now);
        audit(row, "ACTIVATED", null, null, null, null,
                Map.of("merCupNo", row.merCupNo, "termNo", nvl(row.termNo)), now);
    }

    private void writeAcquiringConfig(OnboardingRow row, OffsetDateTime now) {
        LocalDateTime stamp = PaymentOnboardingStore.utc(now);
        long merchantId = row.merchantId;
        ConfigRow config = settlement.settlement.selectConfigByMerchantForUpdate(merchantId);
        long configId;
        Integer beforeDays = null;
        BigDecimal beforeFee = null;
        Boolean beforeEnabled = null;
        String beforeMerchant = null;
        String beforeTerm = null;
        if (config == null) {
            configId = ids.nextId();
            try {
                settlement.settlement.insertConfig(PaymentFoundationStore.values(
                        "id", configId, "merchantId", merchantId,
                        "settleDays", DEFAULT_SETTLE_DAYS, "feeRate", DEFAULT_FEE_RATE, "enabled", true,
                        "lakalaMerchantNo", row.merCupNo, "lakalaTermNo", row.termNo, "now", stamp));
            } catch (DuplicateKeyException race) {
                throw conflict("结算配置并发创建，请稍后重试");
            }
        } else {
            configId = config.id;
            beforeDays = config.settleDays;
            beforeFee = config.feeRate;
            beforeEnabled = config.enabled;
            beforeMerchant = config.lakalaMerchantNo;
            beforeTerm = config.lakalaTermNo;
            int updated = settlement.settlement.updateConfig(PaymentFoundationStore.values(
                    "id", config.id, "version", config.version,
                    "settleDays", config.settleDays, "feeRate", config.feeRate, "enabled", config.enabled,
                    "lakalaMerchantNo", row.merCupNo, "lakalaTermNo", row.termNo, "now", stamp));
            if (updated != 1) throw conflict("结算配置已被修改，请稍后重试");
        }
        settlement.settlement.insertConfigAudit(PaymentFoundationStore.values(
                "id", ids.nextId(), "merchantId", merchantId, "configId", configId,
                "requestKey", ("settlement.config.onboarding-activated/SYSTEM/" + merchantId + "/" + row.id)
                        .getBytes(StandardCharsets.UTF_8),
                "beforeSettleDays", beforeDays, "afterSettleDays", config == null ? DEFAULT_SETTLE_DAYS : config.settleDays,
                "beforeFeeRate", beforeFee, "afterFeeRate", config == null ? DEFAULT_FEE_RATE : config.feeRate,
                "beforeEnabled", beforeEnabled, "afterEnabled", config == null || config.enabled,
                "beforeLakalaMerchantNo", beforeMerchant, "afterLakalaMerchantNo", row.merCupNo,
                "beforeLakalaTermNo", beforeTerm, "afterLakalaTermNo", row.termNo,
                "operatorType", SYSTEM, "operatorId", null, "reason", "商家收单开通完成，系统写入收单商户号与终端号",
                "now", stamp));
    }

    private void timeout(QueryPlan plan) {
        locked(plan.merchantId(), (row, now) -> {
            if (row == null) return null;
            String timeoutStatus;
            String timeoutReason;
            if ("EC".equals(plan.kind()) && EC_SIGNING.equals(row.status) && row.ecNo == null) {
                // The unsigned electronic contract is abandoned; the operator restarts from the profile.
                resetElectronicContract(row);
                timeoutStatus = DRAFT;
                timeoutReason = "商家法人未在时限内完成签署，请重新发起开通";
            } else if ("CONTRACT".equals(plan.kind()) && CONTRACT_APPLYING.equals(row.status)) {
                timeoutStatus = CONTRACT_REJECTED;
                timeoutReason = "等待拉卡拉进件审核超时，请核实后重新发起";
            } else if ("LEDGER".equals(plan.kind()) && LEDGER_APPLYING.equals(row.status)
                    && row.ledgerOpenedAt == null) {
                timeoutStatus = LEDGER_REJECTED;
                timeoutReason = "等待拉卡拉分账开通审核超时，请重新提交";
            } else if ("BIND".equals(plan.kind()) && BIND_APPLYING.equals(row.status)) {
                timeoutStatus = BIND_REJECTED;
                timeoutReason = "等待拉卡拉绑定审核超时，请重新提交";
            } else {
                return null;
            }
            row.status = timeoutStatus;
            if (DRAFT.equals(timeoutStatus)) {
                row.lastErrorCode = "EC_SIGN_TIMEOUT";
                row.lastErrorMessage = timeoutReason;
            } else {
                row.rejectReason = timeoutReason;
            }
            save(row, now);
            audit(row, timeoutStatus + "_TIMEOUT", null, null, null, null,
                    Map.of("status", timeoutStatus), now);
            return null;
        });
    }

    private void recordAdvanceFailure(Plan plan, RuntimeException failure) {
        String code = failure instanceof MerchantOnboardingChannel.Rejected ? "UPLOAD_REJECTED" : "ADVANCE_FAILED";
        String message = truncate("UPLOAD_REJECTED".equals(code)
                ? "附件上传被拒：" + failure.getMessage() : "推进失败：" + failure.getMessage());
        locked(plan.merchantId(), (row, now) -> {
            if (row == null || row.version != plan.version()) return null;
            row.lastErrorCode = code;
            row.lastErrorMessage = message;
            save(row, now);
            audit(row, "ADVANCE_FAILED", null, null, null, null,
                    Map.of("kind", plan.kind(), "code", code), now);
            return null;
        });
    }

    private void recordPollRejection(QueryPlan plan, MerchantOnboardingChannel.Rejected rejected) {
        locked(plan.merchantId(), (row, now) -> {
            if (row == null || row.version != plan.version()) return null;
            row.lastErrorCode = "POLL_REJECTED";
            row.lastErrorMessage = truncate("渠道查询被拒：" + rejected.getMessage());
            save(row, now);
            audit(row, "POLL_REJECTED", null, null, null, null,
                    Map.of("kind", plan.kind(), "code", rejected.code()), now);
            return null;
        });
    }

    // =====================================================================================
    // Profile, applications and channel requests
    // =====================================================================================

    private OnboardingRow newRow(long merchantId, OffsetDateTime now) {
        OnboardingRow row = new OnboardingRow();
        row.id = ids.nextId();
        row.merchantId = merchantId;
        row.status = DRAFT;
        row.larIdType = MerchantOnboardingValidator.RESIDENT_ID_TYPE;
        row.attachmentsJson = "{}";
        row.version = 0;
        row.createdAt = PaymentOnboardingStore.utc(now);
        return row;
    }

    private void writeProfile(OnboardingRow row, MerchantOnboardingValidator.Profile profile) {
        long merchantId = row.merchantId;
        MerchantProfileInput merchant = profile.merchant();
        SettlementProfileInput settle = profile.settlement();
        row.merRegName = merchant.merRegName();
        row.merBizName = merchant.merBizName();
        row.merRegDistCode = merchant.merRegDistCode();
        row.merRegAddr = merchant.merRegAddr();
        row.mccCode = merchant.mccCode();
        row.merBlisName = merchant.merBlisName();
        row.merBlisNoCipher = cipher.encrypt(merchantId, F_LICENSE_NO, merchant.merBlisNo());
        row.merBlisNoMasked = ReceiverSensitiveCipher.maskLicense(merchant.merBlisNo());
        row.merBlisStDt = LocalDate.parse(merchant.merBlisStDt());
        row.merBlisExpDt = LocalDate.parse(merchant.merBlisExpDt());
        row.larName = merchant.larName();
        row.larIdType = MerchantOnboardingValidator.RESIDENT_ID_TYPE;
        row.larIdcardCipher = cipher.encrypt(merchantId, F_LAR_IDCARD, merchant.larIdcard());
        row.larIdcardMasked = ReceiverSensitiveCipher.maskIdCard(merchant.larIdcard());
        row.larIdcardStDt = LocalDate.parse(merchant.larIdcardStDt());
        row.larIdcardExpDt = LocalDate.parse(merchant.larIdcardExpDt());
        row.larMobileCipher = cipher.encrypt(merchantId, F_LAR_MOBILE, merchant.larMobile());
        row.larMobileMasked = ReceiverSensitiveCipher.maskPhone(merchant.larMobile());
        row.contactName = merchant.contactName();
        row.contactMobileCipher = cipher.encrypt(merchantId, F_CONTACT_MOBILE, merchant.contactMobile());
        row.contactMobileMasked = ReceiverSensitiveCipher.maskPhone(merchant.contactMobile());
        row.acctTypeCode = settle.acctTypeCode();
        row.acctName = settle.acctName();
        row.acctNoCipher = cipher.encrypt(merchantId, F_ACCT_NO, settle.acctNo());
        row.acctNoMasked = ReceiverSensitiveCipher.maskCard(settle.acctNo());
        row.acctNoLast4 = ReceiverSensitiveCipher.last4(settle.acctNo());
        row.acctIdType = settle.acctIdType();
        row.acctIdcardCipher = settle.acctIdcard() == null ? null
                : cipher.encrypt(merchantId, F_ACCT_IDCARD, settle.acctIdcard());
        row.acctIdcardMasked = settle.acctIdcard() == null ? null
                : ReceiverSensitiveCipher.maskIdCard(settle.acctIdcard());
        row.acctIdExpDt = settle.acctIdExpDt() == null ? null : LocalDate.parse(settle.acctIdExpDt());
        row.openningBankCode = settle.openningBankCode();
        row.openningBankName = settle.openningBankName();
        row.clearingBankCode = settle.clearingBankCode();
        row.attachmentsJson = attachmentsJson(profile.attachments());
        row.cooperationAssetId = profile.cooperationAssetId();
    }

    /**
     * Blank sensitive inputs (ID numbers, mobiles, card number, names) keep the stored value when a record
     * already exists (contract 81 §4.4), so an operator edits a draft without retyping every secret.
     * Attachments are replaced as sent because their asset ids come back in clear.
     */
    private SaveOnboardingCommand mergeKeptSecrets(SaveOnboardingCommand command, OnboardingRow existing) {
        if (existing == null) return command;
        MerchantProfileInput merchant = command.merchant();
        SettlementProfileInput settle = command.settlement();
        if (merchant == null || settle == null) return command;
        return new SaveOnboardingCommand(command.context(), command.merchantId(), command.expectedVersion(),
                new MerchantProfileInput(merchant.merRegName(), merchant.merBizName(), merchant.merRegDistCode(),
                        merchant.merRegAddr(), merchant.mccCode(), merchant.merBlisName(),
                        blank(merchant.merBlisNo()) ? decrypt(existing, F_LICENSE_NO, existing.merBlisNoCipher)
                                : merchant.merBlisNo(),
                        merchant.merBlisStDt(), merchant.merBlisExpDt(),
                        blank(merchant.larName()) ? existing.larName : merchant.larName(),
                        blank(merchant.larIdcard()) ? decrypt(existing, F_LAR_IDCARD, existing.larIdcardCipher)
                                : merchant.larIdcard(),
                        merchant.larIdcardStDt(), merchant.larIdcardExpDt(),
                        blank(merchant.larMobile()) ? decrypt(existing, F_LAR_MOBILE, existing.larMobileCipher)
                                : merchant.larMobile(),
                        blank(merchant.contactName()) ? existing.contactName : merchant.contactName(),
                        blank(merchant.contactMobile())
                                ? decrypt(existing, F_CONTACT_MOBILE, existing.contactMobileCipher)
                                : merchant.contactMobile()),
                new SettlementProfileInput(settle.acctTypeCode(),
                        blank(settle.acctName()) ? existing.acctName : settle.acctName(),
                        blank(settle.acctNo()) ? decrypt(existing, F_ACCT_NO, existing.acctNoCipher) : settle.acctNo(),
                        settle.acctIdType(),
                        // The stored settler is kept only while the request still declares a settler;
                        // switching back to "settler is the legal person" (acctIdType blank) clears it.
                        settlerDeclared(settle) && blank(settle.acctIdcard())
                                ? decrypt(existing, F_ACCT_IDCARD, existing.acctIdcardCipher) : settle.acctIdcard(),
                        settlerDeclared(settle) && blank(settle.acctIdExpDt()) && existing.acctIdExpDt != null
                                ? existing.acctIdExpDt.toString() : settle.acctIdExpDt(),
                        settle.openningBankCode(), settle.openningBankName(),
                        settle.clearingBankCode()),
                command.attachments(),
                blank(command.cooperationAssetId()) ? existing.cooperationAssetId : command.cooperationAssetId(),
                command.reason());
    }

    private static boolean settlerDeclared(SettlementProfileInput settle) {
        return settle.acctIdType() != null && !settle.acctIdType().isBlank();
    }

    private static void resetElectronicContract(OnboardingRow row) {
        row.ecOrderNo = null;
        row.ecApplyId = null;
        row.ecSignUrl = null;
        row.ecNo = null;
        row.ecSignedAt = null;
    }

    private static void resetContract(OnboardingRow row) {
        row.contractOrderNo = null;
        row.contractId = null;
        row.contractStatus = null;
        row.contractSubmittedAt = null;
        row.contractDecidedAt = null;
        row.merInnerNo = null;
        row.merCupNo = null;
        row.termNo = null;
        row.ledgerOrderNo = null;
        row.ledgerApplyId = null;
        row.ledgerSubmittedAt = null;
        row.ledgerOpenedAt = null;
        row.bindOrderNo = null;
        row.bindApplyId = null;
        row.bindSubmittedAt = null;
        row.bindApprovedAt = null;
    }

    private EcApplication ecApplication(OnboardingRow row) {
        String company = settings.platformCompanyName().trim();
        // 1208 declares org_id as Integer: the dedicated onboarding value, else the shared code when numeric.
        return new EcApplication(row.ecOrderNo, ecOrgId(), row.larName,
                decrypt(row, F_LAR_IDCARD, row.larIdcardCipher),
                decrypt(row, F_LAR_MOBILE, row.larMobileCipher),
                decrypt(row, F_LICENSE_NO, row.merBlisNoCipher), row.merBlisName,
                row.openningBankCode, row.openningBankName, row.acctTypeCode,
                decrypt(row, F_ACCT_NO, row.acctNoCipher), row.acctName, company,
                settings.cooperationAgreementName().trim(), row.splitLowestRatio.toPlainString(), company,
                SIGN_DATE.format(today()), notifyUrl(EC_CALLBACK_PATH));
    }

    private ContractApplication contractApplication(OnboardingRow row) {
        return new ContractApplication(row.contractOrderNo, row.posType, settings.orgCode().trim(), row.merRegName,
                row.merBizName, row.merRegDistCode, row.merRegAddr, row.mccCode, row.merBlisName,
                decrypt(row, F_LICENSE_NO, row.merBlisNoCipher), row.merBlisStDt.toString(),
                row.merBlisExpDt.toString(), row.merBusiContent, row.larName,
                decrypt(row, F_LAR_IDCARD, row.larIdcardCipher), row.larIdcardStDt.toString(),
                row.larIdcardExpDt.toString(), decrypt(row, F_CONTACT_MOBILE, row.contactMobileCipher),
                row.contactName, row.openningBankCode, row.openningBankName, row.clearingBankCode,
                decrypt(row, F_ACCT_NO, row.acctNoCipher), row.acctName, row.acctTypeCode,
                decrypt(row, F_ACCT_IDCARD, row.acctIdcardCipher),
                row.acctIdExpDt == null ? null : row.acctIdExpDt.toString(), row.settlePeriod,
                notifyUrl(""), row.ecNo, feeRatesOf(row.feeDataJson), List.of());
    }

    private static ContractApplication withFiles(ContractApplication application, List<Uploaded> files) {
        return new ContractApplication(application.orderNo(), application.posType(), application.orgCode(),
                application.merRegName(), application.merBizName(), application.merRegDistCode(),
                application.merRegAddr(), application.mccCode(), application.merBlisName(), application.merBlis(),
                application.merBlisStDt(), application.merBlisExpDt(), application.merBusiContent(),
                application.larName(), application.larIdcard(), application.larIdcardStDt(),
                application.larIdcardExpDt(), application.merContactMobile(), application.merContactName(),
                application.openningBankCode(), application.openningBankName(), application.clearingBankCode(),
                application.acctNo(), application.acctName(), application.acctTypeCode(), application.acctIdcard(),
                application.acctIdDt(), application.settlePeriod(), application.retUrl(), application.contractNo(),
                application.feeData(), List.copyOf(files));
    }

    private LedgerApplication ledgerApplication(OnboardingRow row) {
        return new LedgerApplication(row.ledgerOrderNo, settings.orgCode().trim(), row.merCupNo,
                decrypt(row, F_CONTACT_MOBILE, row.contactMobileCipher), row.splitLowestRatio, row.ecNo,
                notifyUrl(LEDGER_CALLBACK_PATH));
    }

    private String notifyUrl(String path) {
        return settings.notifyBaseUrl().trim() + path;
    }

    // =====================================================================================
    // Views
    // =====================================================================================

    private OnboardingView view(OnboardingRow row) {
        boolean submitted = row.posType != null;
        return new OnboardingView(IDS.toApi(row.id), IDS.toApi(row.merchantId), row.status, row.version,
                new MerchantProfileView(row.merchantTypeCode, row.merRegName, row.merBizName, row.merRegDistCode,
                        row.merRegAddr, row.merBlisName, row.merBlisNoMasked, row.merBlisStDt, row.merBlisExpDt,
                        ReceiverSensitiveCipher.maskName(row.larName), row.larIdType, row.larIdcardMasked,
                        row.larIdcardStDt, row.larIdcardExpDt, row.larMobileMasked,
                        ReceiverSensitiveCipher.maskName(row.contactName), row.contactMobileMasked),
                new SettlementProfileView(row.acctTypeCode, ReceiverSensitiveCipher.maskName(row.acctName),
                        row.acctNoMasked, row.acctNoLast4, row.acctIdType, row.acctIdcardMasked, row.acctIdExpDt,
                        row.openningBankCode, row.openningBankName, row.clearingBankCode),
                platformView(submitted ? row.posType : settings.posType(),
                        submitted ? row.merBusiContent : settings.merBusiContent(),
                        submitted ? row.settlePeriod : settings.settlePeriod(),
                        submitted ? row.mccCode : null,
                        submitted ? feeRatesOf(row.feeDataJson) : settings.feeRates(),
                        submitted ? row.splitLowestRatio : settings.lowestRatio()),
                attachmentsOf(row), row.cooperationAssetId, steps(row),
                ACTIVE.equals(row.status) || row.ecNo != null ? null : row.ecSignUrl,
                row.ecNo, row.contractId, row.merCupNo, row.termNo, row.rejectReason, row.lastErrorCode,
                row.lastErrorMessage, allowedActions(row), time(row.submittedAt), time(row.completedAt),
                time(row.createdAt), time(row.updatedAt));
    }

    private static OnboardingSummaryView summary(OnboardingRow row) {
        return new OnboardingSummaryView(IDS.toApi(row.id), IDS.toApi(row.merchantId), row.status, row.merCupNo,
                row.termNo, row.rejectReason, time(row.submittedAt), time(row.completedAt), time(row.updatedAt));
    }

    private static List<String> allowedActions(OnboardingRow row) {
        return switch (row.status) {
            case DRAFT -> List.of("SAVE_PROFILE", "SUBMIT");
            case EC_SIGNING -> List.of("SAVE_PROFILE", "SYNC");
            case CONTRACT_APPLYING, LEDGER_APPLYING, BIND_APPLYING -> List.of("SYNC");
            case CONTRACT_UNKNOWN -> List.of("ADOPT_CONTRACT", "DISCARD_CONTRACT");
            case CONTRACT_REJECTED -> List.of("SAVE_PROFILE");
            case LEDGER_REJECTED, BIND_REJECTED -> List.of("RETRY");
            default -> List.of();
        };
    }

    /** Timeline derived from the row: EC, then 进件, 开通分账, 绑定. */
    private static List<OnboardingStepView> steps(OnboardingRow row) {
        String status = row.status;
        String ecStatus = row.ecNo != null ? "DONE" : EC_SIGNING.equals(status) ? "WAITING_SIGN" : "NONE";
        String contractStatus;
        if (CONTRACT_UNKNOWN.equals(status)) contractStatus = "UNKNOWN";
        else if (CONTRACT_REJECTED.equals(status)) contractStatus = "REJECTED";
        else if (row.merCupNo != null) contractStatus = "DONE";
        else if (CONTRACT_APPLYING.equals(status) || (EC_SIGNING.equals(status) && row.ecNo != null)) contractStatus = "APPLYING";
        else contractStatus = "NONE";
        boolean ledgerReached = row.merCupNo != null && !CONTRACT_REJECTED.equals(status);
        String ledgerStatus = !ledgerReached ? "NONE" : LEDGER_REJECTED.equals(status) ? "REJECTED"
                : row.ledgerOpenedAt != null ? "DONE" : "APPLYING";
        boolean bindReached = row.ledgerOpenedAt != null;
        String bindStatus = !bindReached ? "NONE" : BIND_REJECTED.equals(status) ? "REJECTED"
                : ACTIVE.equals(status) ? "DONE" : "APPLYING";
        return List.of(
                new OnboardingStepView("EC", ecStatus, time(row.submittedAt), time(row.ecSignedAt),
                        "DONE".equals(ecStatus) ? "合同编号 " + row.ecNo : row.ecSignUrl == null ? null : "待商家法人签署",
                        null),
                new OnboardingStepView("CONTRACT", contractStatus, time(row.contractSubmittedAt), time(row.contractDecidedAt),
                        "DONE".equals(contractStatus) ? "商户号 " + row.merCupNo + "，终端号 " + nvl(row.termNo)
                                : "APPLYING".equals(contractStatus) ? "进件ID " + nvl(row.contractId)
                                : "UNKNOWN".equals(contractStatus) ? "进件请求结果未知" : null,
                        "REJECTED".equals(contractStatus) ? row.rejectReason : null),
                new OnboardingStepView("LEDGER", ledgerStatus, time(row.ledgerSubmittedAt), time(row.ledgerOpenedAt),
                        "DONE".equals(ledgerStatus) ? "分账业务已开通" : "APPLYING".equals(ledgerStatus) ? "审核中" : null,
                        "REJECTED".equals(ledgerStatus) ? row.rejectReason : null),
                new OnboardingStepView("BIND", bindStatus, time(row.bindSubmittedAt), time(row.bindApprovedAt),
                        "DONE".equals(bindStatus) ? "已绑定平台接收方" : "APPLYING".equals(bindStatus) ? "审核中" : null,
                        "REJECTED".equals(bindStatus) ? row.rejectReason : null));
    }

    private static PlatformDefaultsView platformView(String posType, String merBusiContent, String settlePeriod,
            String mcc, List<FeeRate> feeRates, BigDecimal ratio) {
        List<FeeRateView> fees = new ArrayList<>();
        for (FeeRate fee : feeRates) fees.add(new FeeRateView(fee.feeRateTypeCode(), fee.feeRateTypeName(), fee.feeRatePct()));
        return new PlatformDefaultsView(posType, merBusiContent, settlePeriod, mcc, fees,
                ratio == null ? null : ratio.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                true);
    }

    // =====================================================================================
    // Small helpers
    // =====================================================================================

    private <T> T locked(long merchantId, BiFunction<OnboardingRow, OffsetDateTime, T> work) {
        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            OnboardingRow row = store.onboarding.selectByMerchantIdForUpdate(merchantId);
            return work.apply(row, now);
        });
    }

    private OnboardingRow replay(byte[] requestKey, long merchantId, String fingerprint) {
        OnboardingAuditRow existing = store.onboarding.selectAuditByRequestKeyForUpdate(requestKey);
        if (existing == null) return null;
        if (existing.merchantId != merchantId || fingerprint == null
                || !fingerprint.equals(existing.requestFingerprint)) {
            throw idempotencyConflict("同一 requestId 不能用于不同的操作");
        }
        OnboardingRow current = store.onboarding.selectByMerchantId(merchantId);
        if (current == null) throw notFound("开通资料不存在");
        return current;
    }

    private void save(OnboardingRow row, OffsetDateTime now) {
        if (!store.update(row, PaymentOnboardingStore.utc(now))) throw conflict("资料已被修改，请刷新后重试");
    }

    private void audit(OnboardingRow row, String action, byte[] requestKey, String fingerprint,
            CommandContext context, String reason, Map<String, Object> detail, OffsetDateTime now) {
        store.onboarding.insertAudit(PaymentFoundationStore.values(
                "id", ids.nextId(), "merchantId", row.merchantId, "onboardingId", row.id, "actionType", action,
                "requestKey", requestKey, "requestFingerprint", fingerprint,
                "operatorType", context == null ? SYSTEM : context.operatorType().name(),
                "operatorId", context == null || blank(context.operatorId()) ? null
                        : Long.parseLong(context.operatorId().trim()),
                "reason", reason, "detailJson", detail == null ? null : toJson(detail),
                "now", PaymentOnboardingStore.utc(now)));
    }

    /** Task keys carry the row version: every transition saves the row first, so keys never collide. */
    private void enqueueAdvance(OnboardingRow row, OffsetDateTime now) {
        OffsetDateTime at = now.truncatedTo(ChronoUnit.MILLIS);
        tasks.enqueueAt(MerchantOnboardingTasks.advanceKey(row.merchantId, "v" + row.version),
                MerchantOnboardingTasks.OWNER_MODULE,
                MerchantOnboardingTasks.ADVANCE_TYPE, MerchantOnboardingTasks.BIZ_TYPE, row.merchantId, null,
                MerchantOnboardingTasks.payload(row.merchantId), MerchantOnboardingTasks.ADVANCE_MAX_RETRY,
                MerchantOnboardingTasks.ADVANCE_POLICY, at);
    }

    private void enqueuePoll(OnboardingRow row, OffsetDateTime now) {
        OffsetDateTime at = now.truncatedTo(ChronoUnit.MILLIS).plus(MerchantOnboardingTasks.POLL_DELAYS.getFirst());
        tasks.enqueueAt(MerchantOnboardingTasks.pollKey(row.merchantId, "v" + row.version),
                MerchantOnboardingTasks.OWNER_MODULE,
                MerchantOnboardingTasks.POLL_TYPE, MerchantOnboardingTasks.BIZ_TYPE, row.merchantId, null,
                MerchantOnboardingTasks.payload(row.merchantId), MerchantOnboardingTasks.POLL_MAX_RETRY,
                MerchantOnboardingTasks.POLL_POLICY, at);
    }

    private String ineligibleReason(MerchantOnboardingFacts facts) {
        if (facts == null) return "MERCHANT_NOT_FOUND";
        if (!"ACTIVE".equals(facts.merchantStatus())) return "MERCHANT_NOT_ACTIVE";
        if (!"APPROVED".equals(facts.applicationStatus())) return "APPLICATION_NOT_APPROVED";
        if (!"SIGNED".equals(facts.signingStatus())) return "AGREEMENT_NOT_SIGNED";
        return null;
    }

    private static String ineligibleMessage(String reason) {
        return switch (reason) {
            case "MERCHANT_NOT_FOUND" -> "商家不存在";
            case "MERCHANT_NOT_ACTIVE" -> "商家档案状态异常，不能开通收单";
            case "APPLICATION_NOT_APPROVED" -> "入驻审核尚未通过，不能开通收单";
            default -> "商家尚未签署平台协议，不能开通收单";
        };
    }

    private List<String> missingConfig(MerchantOnboardingFacts facts, String mccOverride) {
        List<String> missing = new ArrayList<>();
        if (blank(settings.notifyBaseUrl())) missing.add("notifyBaseUrl");
        else if (notifyUrl("").length() > ADD_MER_RET_URL_LIMIT) missing.add("notifyBaseUrlTooLong");
        if (blank(settings.orgCode())) missing.add("orgCode");
        if (ecOrgId() == null) missing.add("ecOrgId");
        if (blank(settings.posType())) missing.add("posType");
        if (blank(settings.merBusiContent())) missing.add("merBusiContent");
        if (blank(settings.settlePeriod())) missing.add("settlePeriod");
        if (settings.feeRates().isEmpty()) missing.add("feeRates");
        if (settings.lowestRatio() == null || settings.lowestRatio().signum() <= 0
                || settings.lowestRatio().compareTo(new BigDecimal("100")) > 0) missing.add("splitLowestRatio");
        if (blank(settings.platformReceiverNo())) missing.add("platformReceiverNo");
        if (blank(settings.cooperationAgreementName())) missing.add("cooperationAgreementName");
        if (blank(settings.platformCompanyName())) missing.add("platformCompanyName");
        if (facts != null && mccFor(facts, mccOverride) == null) missing.add("mccCode");
        return missing;
    }

    /** Integer organization id for the electronic contract, or {@code null} when none is configured. */
    private String ecOrgId() {
        if (!blank(settings.ecOrgId())) return settings.ecOrgId().trim();
        String shared = settings.orgCode().trim();
        return !shared.isEmpty() && shared.chars().allMatch(Character::isDigit) ? shared : null;
    }

    private String mccFor(MerchantOnboardingFacts facts, String override) {
        if (!blank(override)) return override.trim();
        if (facts == null || facts.merchantTypeCode() == null) return blank(settings.mccDefault()) ? null : settings.mccDefault();
        String mapped = settings.mccByMerchantType().get(facts.merchantTypeCode());
        return mapped != null ? mapped : blank(settings.mccDefault()) ? null : settings.mccDefault();
    }

    private Map<String, String> attachmentsOf(OnboardingRow row) {
        return attachmentsOf(row.attachmentsJson);
    }

    private Map<String, String> attachmentsOf(long merchantId) {
        OnboardingRow row = store.onboarding.selectByMerchantId(merchantId);
        if (row == null) throw notFound("开通资料不存在");
        return attachmentsOf(row.attachmentsJson);
    }

    private static Map<String, String> attachmentsOf(String json) {
        if (blank(json)) return Map.of();
        try {
            Map<String, String> parsed = JSON.readValue(json, new TypeReference<Map<String, String>>() {});
            Map<String, String> kept = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                if (!blank(entry.getValue()) && MerchantOnboardingValidator.KNOWN_ATTACHMENTS.contains(entry.getKey())) {
                    kept.put(entry.getKey(), entry.getValue());
                }
            }
            return java.util.Collections.unmodifiableMap(new TreeMap<>(kept));
        } catch (IOException malformed) {
            throw unavailable("附件资料损坏");
        }
    }

    private static String attachmentsJson(Map<String, String> attachments) {
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : attachments.entrySet()) {
            if (!blank(entry.getValue())) sorted.put(entry.getKey(), entry.getValue());
        }
        return toJson(sorted);
    }

    private static List<FeeRate> feeRatesOf(String json) {
        if (blank(json)) return List.of();
        try {
            List<FeeRate> rates = new ArrayList<>();
            for (Map<String, String> fee : JSON.readValue(json, new TypeReference<List<Map<String, String>>>() {})) {
                rates.add(new FeeRate(fee.get("feeRateTypeCode"), fee.get("feeRateTypeName"), fee.get("feeRatePct")));
            }
            return List.copyOf(rates);
        } catch (IOException malformed) {
            throw unavailable("费率快照损坏");
        }
    }

    private static String feeJson(List<FeeRate> feeRates) {
        List<Map<String, String>> out = new ArrayList<>();
        for (FeeRate fee : feeRates) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("feeRateTypeCode", fee.feeRateTypeCode());
            entry.put("feeRateTypeName", fee.feeRateTypeName());
            entry.put("feeRatePct", fee.feeRatePct());
            out.add(entry);
        }
        return toJson(out);
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException failure) {
            throw new IllegalStateException("onboarding JSON serialization failed", failure);
        }
    }

    private static Map<String, Object> contractDetail(ContractProgress progress, String status) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", status);
        detail.put("contractStatus", nvl(progress.contractStatus()));
        detail.put("contractMemo", nvl(progress.contractMemo()));
        return detail;
    }

    private String decrypt(OnboardingRow row, String field, String cipherText) {
        return blank(cipherText) ? null : cipher.decrypt(row.merchantId, field, cipherText);
    }

    private String ecApplyIdOf(long merchantId) {
        OnboardingRow row = store.onboarding.selectByMerchantId(merchantId);
        if (row == null) throw notFound("开通资料不存在");
        return row.ecApplyId;
    }

    private String contractOrderNoOf(long merchantId) {
        OnboardingRow row = store.onboarding.selectByMerchantId(merchantId);
        if (row == null) throw notFound("开通资料不存在");
        return row.contractOrderNo;
    }

    private String queryOrderNo(QueryPlan plan) {
        OnboardingRow row = store.onboarding.selectByMerchantId(plan.merchantId());
        if (row == null) throw notFound("开通资料不存在");
        return "LEDGER".equals(plan.kind()) ? row.ledgerOrderNo : row.bindOrderNo;
    }

    private static String fileName(AssetContent content) {
        return "cooperation-agreement." + content.extName();
    }

    private static String fingerprintOf(SaveOnboardingCommand command) {
        StringBuilder text = new StringBuilder("PROFILE|").append(command.merchantId()).append('|')
                .append(command.expectedVersion());
        MerchantProfileInput merchant = command.merchant();
        SettlementProfileInput settle = command.settlement();
        if (merchant != null) {
            text.append('|').append(nvl(merchant.merRegName())).append('|').append(nvl(merchant.merBizName()))
                    .append('|').append(nvl(merchant.merRegDistCode())).append('|').append(nvl(merchant.merRegAddr()))
                    .append('|').append(nvl(merchant.mccCode())).append('|').append(nvl(merchant.merBlisName()))
                    .append('|').append(nvl(merchant.merBlisNo())).append('|').append(nvl(merchant.merBlisStDt()))
                    .append('|').append(nvl(merchant.merBlisExpDt())).append('|').append(nvl(merchant.larName()))
                    .append('|').append(nvl(merchant.larIdcard())).append('|').append(nvl(merchant.larIdcardStDt()))
                    .append('|').append(nvl(merchant.larIdcardExpDt())).append('|').append(nvl(merchant.larMobile()))
                    .append('|').append(nvl(merchant.contactName())).append('|').append(nvl(merchant.contactMobile()));
        }
        if (settle != null) {
            text.append('|').append(nvl(settle.acctTypeCode())).append('|').append(nvl(settle.acctName()))
                    .append('|').append(nvl(settle.acctNo())).append('|').append(nvl(settle.acctIdType()))
                    .append('|').append(nvl(settle.acctIdcard())).append('|').append(nvl(settle.acctIdExpDt()))
                    .append('|').append(nvl(settle.openningBankCode())).append('|').append(nvl(settle.openningBankName()))
                    .append('|').append(nvl(settle.clearingBankCode()));
        }
        new TreeMap<>(command.attachments()).forEach((type, asset) -> text.append('|').append(type)
                .append('=').append(nvl(asset)));
        text.append('|').append(nvl(command.cooperationAssetId()));
        // The audited reason is part of the request: same requestId + other reason is a conflict.
        text.append('|').append(nvl(command.reason()));
        return sha256(text.toString());
    }

    private String nextOrderNo() {
        String time = ORDER_NO_TIME.format(clock.instant().atZone(ZoneOffset.ofHours(8)));
        return time + String.format("%08d", RANDOM.nextInt(100_000_000));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.ofHours(8)));
    }

    private static long parseId(String raw) {
        if (blank(raw)) throw invalid("ID 格式不合法");
        try {
            long id = IDS.fromApi(raw.trim());
            if (id <= 0) throw invalid("ID 格式不合法");
            return id;
        } catch (IllegalArgumentException invalid) {
            throw invalid("ID 格式不合法");
        }
    }

    private static byte[] requestKey(String action, CommandContext context, String requestId) {
        return ("settlement.onboarding." + action + "/" + context.operatorType().name() + "/"
                + nvl(context.operatorId()) + "/" + requestId).getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static OffsetDateTime time(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String text) {
        if (text == null) return null;
        String value = text.trim();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw unavailable("onboarding command must not run inside caller transaction");
        }
    }

    private static void requireAdmin(CommandContext context) {
        if (context == null || context.operatorType() != OperatorType.PLATFORM_OPERATOR) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "收单开通操作仅限运营人员");
        }
    }

    private static void requireAdminQuery(QueryContext context) {
        if (context == null || (context.operatorType() != OperatorType.PLATFORM_OPERATOR
                && context.operatorType() != OperatorType.SYSTEM)) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "收单开通查询仅限运营人员");
        }
    }

    private static String requireRequestId(CommandContext context) {
        String requestId = context.requestId();
        if (blank(requestId) || requestId.length() > 128) throw invalid("缺少或非法的 X-Request-Id");
        return requestId.trim();
    }

    private static ApiException invalid(String message) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static ApiException conflict(String message) {
        return new ApiException(CommonApiCodes.CONFLICT, message);
    }

    private static ApiException idempotencyConflict(String message) {
        return new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, message);
    }

    private static ApiException notFound(String message) {
        return new ApiException(CommonApiCodes.NOT_FOUND, message);
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
