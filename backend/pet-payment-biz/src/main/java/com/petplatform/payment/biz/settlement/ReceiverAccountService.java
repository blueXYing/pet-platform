package com.petplatform.payment.biz.settlement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.api.command.ReceiverAccountApi;
import com.petplatform.payment.api.dto.ReceiverTypes.BindReceiverCommand;
import com.petplatform.payment.api.dto.ReceiverTypes.ListReceiversQuery;
import com.petplatform.payment.api.dto.ReceiverTypes.MerchantReceiverStatusView;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverAccountSummary;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverAccountView;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverAttachmentRef;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverPage;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverSensitiveView;
import com.petplatform.payment.api.dto.ReceiverTypes.RevealReceiverCommand;
import com.petplatform.payment.api.dto.ReceiverTypes.UpsertReceiverCommand;
import com.petplatform.payment.api.query.ReceiverQueryApi;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentReceiverStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentReceiverMapper.ReceiverAccountRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentReceiverMapper.ReceiverAuditRow;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyBindInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.AttachItem;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.BindAuditStatus;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.BindNotification;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ListRelationApplyInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ModifyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.QueryReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.RelationApplyItem;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.UploadFileInput;
import com.petplatform.payment.biz.settlement.ReceiverAttachmentProvider.AssetContent;
import com.petplatform.payment.biz.task.ReceiverBindQueryTask;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 80 §4.2 & §5 / Ruling 40 §4 split-receiver onboarding kernel:
 * operator-filled draft/create (1084+1287, forced {@code settleType="01"}), modify (1084+1288),
 * async ledger bind (1084+1290 + durable {@code RECEIVER_BIND_QUERY} task), 1290 callback &
 * 1872 poll reconciliation, AES-256-GCM storage, masked reads, and audited plaintext reveal.
 */
public final class ReceiverAccountService implements ReceiverAccountApi, ReceiverQueryApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<ReceiverAttachmentRef>> ATTACH_LIST_TYPE = new TypeReference<>() {};
    private static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_PAGE_SIZE = 100;

    private static final String FIELD_ACCT_NO = "acct_no";
    private static final String FIELD_ACCT_CERT_NO = "acct_cert_no";
    private static final String FIELD_CONTACT_MOBILE = "contact_mobile";
    private static final String FIELD_LICENSE_NO = "license_no";
    private static final String FIELD_LEGAL_CERT_NO = "legal_cert_no";

    private static final Set<String> VALID_STATUSES = Set.of(
            "DRAFT", "RECEIVER_APPLYING", "RECEIVER_CREATED", "BIND_APPLYING",
            "ACTIVE", "REJECTED", "CHANGE_APPLYING");
    private static final Set<String> PERSONAL_REQUIRED_ATTS = Set.of(
            "ID_CARD_FRONT", "ID_CARD_BEHIND", "BANK_CARD");
    private static final Set<String> ENTERPRISE_REQUIRED_ATTS = Set.of(
            "FR_ID_CARD_FRONT", "FR_ID_CARD_BEHIND", "BANK_CARD", "BUSINESS_LICENCE");

    @FunctionalInterface
    public interface BindNotificationVerifier {
        BindNotification verify(Map<String, String> headers, byte[] rawBody);

        static BindNotificationVerifier fromPublicKey(PublicKey trustedNotificationKey) {
            Objects.requireNonNull(trustedNotificationKey, "trustedNotificationKey");
            return (headers, body) -> LakalaV2Protocol.verifyBindNotification(headers, body, trustedNotificationKey);
        }
    }

    public record Settings(
            String orgCode,
            String platformMerCupNo,
            String platformMerInnerNo,
            String ledgerNotifyUrl) {
        public Settings {
            boolean hasCup = platformMerCupNo != null && !platformMerCupNo.isBlank();
            boolean hasInner = platformMerInnerNo != null && !platformMerInnerNo.isBlank();
            if (!hasCup && !hasInner) {
                throw new IllegalArgumentException("platform merchant merCupNo or merInnerNo is required");
            }
        }
    }

    private final SnowflakeIdGenerator ids;
    private final PaymentReceiverStore store;
    private final ReceiverSensitiveCipher cipher;
    private final ReceiverAttachmentProvider attachmentProvider;
    private final ReceiverChannel channel;
    private final JdbcAsyncTaskSubmitter tasks;
    private final BindNotificationVerifier notificationVerifier;
    private final Settings settings;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ReceiverAccountService(
            DataSource source,
            SnowflakeIdGenerator ids,
            ReceiverSensitiveCipher cipher,
            ReceiverAttachmentProvider attachmentProvider,
            ReceiverChannel channel,
            JdbcAsyncTaskSubmitter tasks,
            BindNotificationVerifier notificationVerifier,
            Settings settings,
            Clock clock) {
        Objects.requireNonNull(source, "source");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.attachmentProvider = Objects.requireNonNull(attachmentProvider, "attachmentProvider");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.notificationVerifier = Objects.requireNonNull(notificationVerifier, "notificationVerifier");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = new PaymentReceiverStore(source);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(15);
    }

    // ---- ReceiverAccountApi ----------------------------------------------------------------

    @Override
    public ReceiverAccountView upsertOrCreate(UpsertReceiverCommand command) {
        noOuterTransaction();
        if (command == null || command.context() == null) throw invalid("missing receiver command");
        requireAdminOrSystem(command.context());
        String requestId = requireRequestId(command.context());
        ValidatedInput input = validateInput(command, command.submitToChannel());
        byte[] reqKey = requestKey("settlement.receiver.upsert", command.context(), requestId);
        String fingerprint = upsertFingerprint("UPSERT", input, command.submitToChannel());
        Long operatorId = parseNullableId(command.context().operatorId());
        String reason = normalizeReason(command.reason());
        EncryptedFields enc = encryptAndMask(input);
        String attachmentsJson = serializeAttachments(input.attachments());

        if (!command.submitToChannel()) {
            return tx.execute(status -> {
                store.session();
                OffsetDateTime now = store.now();
                ReceiverAccountView replayed = checkIdempotentReplay(reqKey, input.merchantId(), fingerprint);
                if (replayed != null) return replayed;

                ReceiverAccountRow existing = store.receiver.selectByMerchantIdForUpdate(input.merchantId());
                long accountId;
                if (existing == null) {
                    accountId = nextId();
                    insertAccountRow(accountId, input, enc, attachmentsJson, "DRAFT", null, now);
                } else {
                    if (!"DRAFT".equals(existing.status) && !("REJECTED".equals(existing.status) && existing.receiverNo == null)) {
                        throw conflict("receiver status " + existing.status + " cannot save draft");
                    }
                    accountId = existing.id;
                    updateAccountRow(existing.id, existing.version, input, enc, attachmentsJson,
                            "DRAFT", null, null, existing.lastSyncedAt, now);
                }
                insertAuditRow(input.merchantId(), accountId, "DRAFT_SAVE", reqKey, fingerprint,
                        command.context().operatorType().name(), operatorId, reason,
                        auditDetail(Map.of("status", "DRAFT", "acctNoLast4", enc.acctNoLast4())), now);
                return toAccountView(store.receiver.selectById(accountId));
            });
        }

        // submitToChannel == true: 1084 upload + 1287 sync create outside DB transaction.
        String orgCode = requireOrgCode();
        String orderNo = nextOrderNo();
        record PrepResult(ReceiverAccountView replayed, long accountId, long version, String previousStatus) {}

        PrepResult prep = tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            ReceiverAccountView replayed = checkIdempotentReplay(reqKey, input.merchantId(), fingerprint);
            if (replayed != null) return new PrepResult(replayed, 0L, 0L, null);

            ReceiverAccountRow existing = store.receiver.selectByMerchantIdForUpdate(input.merchantId());
            long accountId;
            String previousStatus;
            if (existing == null) {
                accountId = nextId();
                previousStatus = "DRAFT";
                insertAccountRow(accountId, input, enc, attachmentsJson, "RECEIVER_APPLYING", orderNo, now);
            } else {
                if (existing.receiverNo != null && !existing.receiverNo.isBlank()) {
                    throw conflict("receiver already created; use modify or bind");
                }
                if (!"DRAFT".equals(existing.status) && !"REJECTED".equals(existing.status)) {
                    throw conflict("receiver status " + existing.status + " cannot submit create");
                }
                accountId = existing.id;
                previousStatus = existing.status;
                updateAccountRow(existing.id, existing.version, input, enc, attachmentsJson,
                        "RECEIVER_APPLYING", null, orderNo, existing.lastSyncedAt, now);
            }
            ReceiverAccountRow locked = store.receiver.selectById(accountId);
            return new PrepResult(null, accountId, locked.version, previousStatus);
        });
        if (prep.replayed() != null) return prep.replayed();

        // Non-transactional 1084 attachment uploads (fresh attFileId within 24h orderNo window).
        List<AttachItem> uploaded;
        try {
            uploaded = uploadAttachments(orderNo, orgCode, input.attachments());
        } catch (RuntimeException failure) {
            revertStatusAfterPreSubmitFailure(prep.accountId(), prep.version(), prep.previousStatus(), null);
            throw failure;
        }

        // Non-transactional 1287 synchronous create (always settleType="01").
        final LakalaV2HttpClient.VerifiedReceiverAction created;
        try {
            created = channel.applyLedgerReceiver(new ApplyReceiverInput(
                    orderNo, orgCode, input.receiverName(), input.contactMobile(),
                    input.licenseNo(), input.licenseName(), input.legalPersonName(),
                    input.legalPersonCertType(), input.legalPersonCertNo(),
                    input.acctNo(), input.acctName(), input.acctTypeCode(),
                    input.acctCertificateType(), input.acctCertificateNo(),
                    input.acctOpenBankCode(), input.acctOpenBankName(), input.acctClearBankCode(),
                    uploaded));
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            String rejectReason = formatRejectReason(rejected.retCode(), rejected.retMsg());
            recordChannelRejection(prep.accountId(), prep.version(), "REJECTED", rejectReason,
                    "CREATE_REJECTED", command.context().operatorType().name(), operatorId, reason,
                    Map.of("orderNo", orderNo, "retCode", rejected.retCode()));
            throw conflict("Lakala rejected receiver creation: " + rejectReason);
        } catch (LakalaV2HttpClient.ChannelUnknownException unknown) {
            throw unavailable("Lakala receiver creation outcome unknown: " + unknown.reason());
        }

        String receiverNo = created.result().receiverNo();
        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            ReceiverAccountRow row = store.receiver.selectByIdForUpdate(prep.accountId());
            if (row == null || row.version != prep.version() || !"RECEIVER_APPLYING".equals(row.status)) {
                throw conflict("receiver state changed concurrently during create");
            }
            updateStateRow(row, "RECEIVER_CREATED", receiverNo, row.applyId, row.cooperationAssetId,
                    null, orderNo, PaymentReceiverStore.utc(now), now);
            insertAuditRow(input.merchantId(), row.id, "CREATE", reqKey, fingerprint,
                    command.context().operatorType().name(), operatorId, reason,
                    auditDetail(Map.of(
                            "status", "RECEIVER_CREATED",
                            "receiverNo", receiverNo,
                            "settleType", LakalaV2Protocol.ACTIVE_WITHDRAW_SETTLE_TYPE,
                            "orderNo", orderNo,
                            "acctNoLast4", enc.acctNoLast4())), now);
            return toAccountView(store.receiver.selectById(row.id));
        });
    }

    @Override
    public ReceiverAccountView modify(UpsertReceiverCommand command) {
        noOuterTransaction();
        if (command == null || command.context() == null) throw invalid("missing receiver modify command");
        requireAdminOrSystem(command.context());
        String requestId = requireRequestId(command.context());
        ValidatedInput input = validateInput(command, false);
        byte[] reqKey = requestKey("settlement.receiver.modify", command.context(), requestId);
        String fingerprint = upsertFingerprint("MODIFY", input, true);
        Long operatorId = parseNullableId(command.context().operatorId());
        String reason = normalizeReason(command.reason());
        EncryptedFields enc = encryptAndMask(input);
        String attachmentsJson = serializeAttachments(input.attachments());
        String orgCode = requireOrgCode();
        String orderNo = nextOrderNo();

        record PrepResult(ReceiverAccountView replayed, long accountId, long version,
                          String receiverNo, String previousStatus, String existingAttachmentsJson) {}

        PrepResult prep = tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            ReceiverAccountView replayed = checkIdempotentReplay(reqKey, input.merchantId(), fingerprint);
            if (replayed != null) return new PrepResult(replayed, 0L, 0L, null, null, null);

            ReceiverAccountRow existing = store.receiver.selectByMerchantIdForUpdate(input.merchantId());
            if (existing == null) throw notFound("receiver account not found");
            if (existing.receiverNo == null || existing.receiverNo.isBlank()) {
                throw conflict("receiver has not been created on channel yet");
            }
            if (!"RECEIVER_CREATED".equals(existing.status)
                    && !"ACTIVE".equals(existing.status)
                    && !"REJECTED".equals(existing.status)) {
                throw conflict("receiver status " + existing.status + " cannot modify");
            }
            updateStateRow(existing, "CHANGE_APPLYING", existing.receiverNo, existing.applyId,
                    existing.cooperationAssetId, existing.rejectReason, orderNo, existing.lastSyncedAt, now);
            ReceiverAccountRow locked = store.receiver.selectById(existing.id);
            return new PrepResult(null, existing.id, locked.version, existing.receiverNo,
                    existing.status, existing.attachmentsJson);
        });
        if (prep.replayed() != null) return prep.replayed();

        List<AttachItem> uploaded;
        try {
            uploaded = input.attachments().isEmpty()
                    ? List.of()
                    : uploadAttachments(orderNo, orgCode, input.attachments());
        } catch (RuntimeException failure) {
            revertStatusAfterPreSubmitFailure(prep.accountId(), prep.version(), prep.previousStatus(), null);
            throw failure;
        }

        try {
            channel.modifyLedgerReceiver(new ModifyReceiverInput(
                    orderNo, orgCode, prep.receiverNo(),
                    input.receiverName(), input.contactMobile(), input.acctNo(),
                    input.acctTypeCode(), input.acctOpenBankCode(), input.acctOpenBankName(),
                    input.acctClearBankCode(), uploaded, null));
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            String rejectReason = formatRejectReason(rejected.retCode(), rejected.retMsg());
            recordChannelRejection(prep.accountId(), prep.version(), prep.previousStatus(), rejectReason,
                    "MODIFY_REJECTED", command.context().operatorType().name(), operatorId, reason,
                    Map.of("orderNo", orderNo, "receiverNo", prep.receiverNo(), "retCode", rejected.retCode()));
            throw conflict("Lakala rejected receiver modification: " + rejectReason);
        } catch (LakalaV2HttpClient.ChannelUnknownException unknown) {
            throw unavailable("Lakala receiver modification outcome unknown: " + unknown.reason());
        }

        String restoredStatus = "REJECTED".equals(prep.previousStatus())
                ? "RECEIVER_CREATED" : prep.previousStatus();
        String finalAttachmentsJson = input.attachments().isEmpty()
                ? prep.existingAttachmentsJson() : attachmentsJson;

        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            ReceiverAccountRow row = store.receiver.selectByIdForUpdate(prep.accountId());
            if (row == null || row.version != prep.version() || !"CHANGE_APPLYING".equals(row.status)) {
                throw conflict("receiver state changed concurrently during modify");
            }
            updateAccountRow(row.id, row.version, input, enc, finalAttachmentsJson,
                    restoredStatus, null, orderNo, PaymentReceiverStore.utc(now), now);
            insertAuditRow(input.merchantId(), row.id, "MODIFY", reqKey, fingerprint,
                    command.context().operatorType().name(), operatorId, reason,
                    auditDetail(Map.of(
                            "status", restoredStatus,
                            "receiverNo", prep.receiverNo(),
                            "settleType", LakalaV2Protocol.ACTIVE_WITHDRAW_SETTLE_TYPE,
                            "orderNo", orderNo,
                            "acctNoLast4", enc.acctNoLast4())), now);
            return toAccountView(store.receiver.selectById(row.id));
        });
    }

    @Override
    public ReceiverAccountView bind(BindReceiverCommand command) {
        noOuterTransaction();
        if (command == null || command.context() == null) throw invalid("missing receiver bind command");
        requireAdminOrSystem(command.context());
        String requestId = requireRequestId(command.context());
        long merchantId = parsePositiveId(command.merchantId(), "merchantId");
        String requestedAssetId = normalizeOptionalId(command.cooperationAssetId(), 64, "cooperationAssetId");
        String remark = normalizeReason(command.remark());
        byte[] reqKey = requestKey("settlement.receiver.bind", command.context(), requestId);
        String fingerprint = sha256("BIND|" + merchantId + "|"
                + (requestedAssetId == null ? "" : requestedAssetId) + "|" + (remark == null ? "" : remark));
        Long operatorId = parseNullableId(command.context().operatorId());
        String orgCode = requireOrgCode();
        String notifyUrl = requireLedgerNotifyUrl();
        String orderNo = nextOrderNo();

        record PrepResult(ReceiverAccountView replayed, long accountId, long version,
                          String receiverNo, String coopAssetId, String previousStatus) {}

        PrepResult prep = tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            ReceiverAccountView replayed = checkIdempotentReplay(reqKey, merchantId, fingerprint);
            if (replayed != null) return new PrepResult(replayed, 0L, 0L, null, null, null);

            ReceiverAccountRow existing = store.receiver.selectByMerchantIdForUpdate(merchantId);
            if (existing == null) throw notFound("receiver account not found");
            if (existing.receiverNo == null || existing.receiverNo.isBlank()) {
                throw conflict("receiver must be created on channel before binding");
            }
            if (!"RECEIVER_CREATED".equals(existing.status) && !"REJECTED".equals(existing.status)) {
                throw conflict("receiver status " + existing.status + " cannot initiate bind");
            }
            String coopAssetId = requestedAssetId != null ? requestedAssetId : existing.cooperationAssetId;
            if (coopAssetId == null || coopAssetId.isBlank()) {
                throw invalid("cooperationAssetId is required for ledger bind");
            }
            updateStateRow(existing, "BIND_APPLYING", existing.receiverNo, null,
                    coopAssetId, null, orderNo, existing.lastSyncedAt, now);
            ReceiverAccountRow locked = store.receiver.selectById(existing.id);
            return new PrepResult(null, existing.id, locked.version, existing.receiverNo,
                    coopAssetId, existing.status);
        });
        if (prep.replayed() != null) return prep.replayed();

        // Non-transactional 1084 upload of cooperation agreement + 1290 async bind.
        final String entrustFileName;
        final String entrustFilePath;
        try {
            AssetContent content = attachmentProvider.readAsset(prep.coopAssetId());
            if (content == null) throw unavailable("cooperation asset content absent");
            var uploaded = channel.uploadFile(new UploadFileInput(
                    orderNo, orgCode, "SPLIT_COOPERATION_FILE", content.extName(), content.bytes()));
            entrustFilePath = uploaded.result().attFileId();
            entrustFileName = resolveFileName(content, "cooperation");
        } catch (RuntimeException failure) {
            revertStatusAfterPreSubmitFailure(prep.accountId(), prep.version(), prep.previousStatus(), null);
            throw failure;
        }

        final LakalaV2HttpClient.VerifiedApplyBind bound;
        try {
            bound = channel.applyBind(new ApplyBindInput(
                    orderNo, orgCode, settings.platformMerInnerNo(), settings.platformMerCupNo(),
                    prep.receiverNo(), entrustFileName, entrustFilePath, notifyUrl, List.of()));
        } catch (LakalaV2HttpClient.ChannelRejectedException rejected) {
            String rejectReason = formatRejectReason(rejected.retCode(), rejected.retMsg());
            recordChannelRejection(prep.accountId(), prep.version(), prep.previousStatus(), rejectReason,
                    "BIND_REJECTED", command.context().operatorType().name(), operatorId, remark,
                    Map.of("orderNo", orderNo, "receiverNo", prep.receiverNo(), "retCode", rejected.retCode()));
            throw conflict("Lakala rejected ledger bind: " + rejectReason);
        } catch (LakalaV2HttpClient.ChannelUnknownException unknown) {
            throw unavailable("Lakala ledger bind outcome unknown: " + unknown.reason());
        }

        String applyId = bound.result().applyId();
        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            ReceiverAccountRow row = store.receiver.selectByIdForUpdate(prep.accountId());
            if (row == null || row.version != prep.version() || !"BIND_APPLYING".equals(row.status)) {
                throw conflict("receiver state changed concurrently during bind");
            }
            updateStateRow(row, "BIND_APPLYING", row.receiverNo, applyId, prep.coopAssetId(),
                    null, orderNo, PaymentReceiverStore.utc(now), now);
            insertAuditRow(merchantId, row.id, "BIND", reqKey, fingerprint,
                    command.context().operatorType().name(), operatorId, remark,
                    auditDetail(Map.of(
                            "status", "BIND_APPLYING",
                            "receiverNo", row.receiverNo,
                            "applyId", applyId,
                            "cooperationAssetId", prep.coopAssetId(),
                            "orderNo", orderNo)), now);
            enqueueBindQueryTask(merchantId, applyId, now);
            return toAccountView(store.receiver.selectById(row.id));
        });
    }

    @Override
    public ReceiverAccountView syncFromChannel(String merchantIdRaw, CommandContext context) {
        noOuterTransaction();
        if (context == null) throw invalid("missing sync command context");
        requireAdminOrSystem(context);
        long merchantId = parsePositiveId(merchantIdRaw, "merchantId");
        Long operatorId = parseNullableId(context.operatorId());

        ReceiverAccountRow snapshot = store.receiver.selectByMerchantId(merchantId);
        if (snapshot == null) throw notFound("receiver account not found");
        if (snapshot.receiverNo == null || snapshot.receiverNo.isBlank()) {
            return toAccountView(snapshot);
        }

        String orgCode = requireOrgCode();
        // 1289 detail verification.
        try {
            channel.queryReceiverDetail(new QueryReceiverInput(nextOrderNo(), orgCode, snapshot.receiverNo));
        } catch (LakalaV2HttpClient.ChannelRejectedException | LakalaV2HttpClient.ChannelUnknownException failed) {
            throw unavailable("Lakala receiver detail sync failed: " + failed.getMessage());
        }

        // If awaiting bind audit, also query 1872 and advance state if decided.
        if ("BIND_APPLYING".equals(snapshot.status)) {
            RelationApplyItem matched = queryBindItem(orgCode, snapshot.receiverNo, snapshot.applyId);
            if (matched != null) {
                if (matched.auditStatus().isTerminal()) {
                    return applyBindAuditOutcome(snapshot.id, matched.applyId(), snapshot.receiverNo,
                            matched.auditStatus(), matched.remark(), "SYNC",
                            context.operatorType().name(), operatorId);
                }
                if (snapshot.applyId == null && matched.applyId() != null) {
                    tx.executeWithoutResult(txStatus -> {
                        store.session();
                        OffsetDateTime now = store.now();
                        ReceiverAccountRow row = store.receiver.selectByIdForUpdate(snapshot.id);
                        if (row != null && "BIND_APPLYING".equals(row.status) && row.applyId == null) {
                            updateStateRow(row, "BIND_APPLYING", row.receiverNo, matched.applyId(),
                                    row.cooperationAssetId, row.rejectReason, row.lastChannelOrderNo,
                                    PaymentReceiverStore.utc(now), now);
                            enqueueBindQueryTask(merchantId, matched.applyId(), now);
                        }
                    });
                }
            }
        }

        return tx.execute(txStatus -> {
            store.session();
            OffsetDateTime now = store.now();
            ReceiverAccountRow row = store.receiver.selectByIdForUpdate(snapshot.id);
            if (row == null) throw notFound("receiver account not found");
            updateStateRow(row, row.status, row.receiverNo, row.applyId, row.cooperationAssetId,
                    row.rejectReason, row.lastChannelOrderNo, PaymentReceiverStore.utc(now), now);
            insertAuditRow(merchantId, row.id, "SYNC", null, null,
                    context.operatorType().name(), operatorId, null,
                    auditDetail(Map.of("status", row.status, "receiverNo", row.receiverNo)), now);
            return toAccountView(store.receiver.selectById(row.id));
        });
    }

    @Override
    public ReceiverSensitiveView revealSensitive(RevealReceiverCommand command) {
        noOuterTransaction();
        if (command == null || command.context() == null) throw invalid("missing reveal command");
        if (command.context().operatorType() != OperatorType.PLATFORM_OPERATOR) {
            throw new ApiException(CommonApiCodes.FORBIDDEN,
                    "only PLATFORM_OPERATOR may reveal sensitive receiver fields");
        }
        String reason = command.reason() == null ? null : command.reason().trim();
        if (reason == null || reason.isBlank() || reason.length() > 256) {
            throw invalid("reveal reason is required and must be <= 256 chars");
        }
        long merchantId = parsePositiveId(command.merchantId(), "merchantId");
        Long operatorId = parseNullableId(command.context().operatorId());

        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            ReceiverAccountRow row = store.receiver.selectByMerchantId(merchantId);
            if (row == null) throw notFound("receiver account not found");

            String acctNo = cipher.decrypt(merchantId, FIELD_ACCT_NO, row.acctNoCipher);
            String acctCertNo = cipher.decrypt(merchantId, FIELD_ACCT_CERT_NO, row.acctCertNoCipher);
            String contactMobile = cipher.decrypt(merchantId, FIELD_CONTACT_MOBILE, row.contactMobileCipher);
            String licenseNo = cipher.decrypt(merchantId, FIELD_LICENSE_NO, row.licenseNoCipher);
            String legalCertNo = cipher.decrypt(merchantId, FIELD_LEGAL_CERT_NO, row.legalCertNoCipher);

            List<String> revealed = new ArrayList<>();
            revealed.add("acctNo");
            revealed.add("acctCertificateNo");
            revealed.add("contactMobile");
            if (licenseNo != null) revealed.add("licenseNo");
            if (legalCertNo != null) revealed.add("legalPersonCertificateNo");

            insertAuditRow(merchantId, row.id, "REVEAL", null, null,
                    OperatorType.PLATFORM_OPERATOR.name(), operatorId, reason,
                    auditDetail(Map.of("revealedFields", revealed)), now);
            return new ReceiverSensitiveView(
                    IDS.toApi(merchantId), acctNo, acctCertNo, contactMobile, licenseNo, legalCertNo);
        });
    }

    // ---- 1290 Callback & 1872 Durable Task Poll --------------------------------------------

    /**
     * Verified 1290 ledger bind callback entry ({@code POST /api/v1/callbacks/lakala/ledger}).
     * Rejects invalid 1009 signatures before any DB write; row-locked & idempotent across races
     * with {@link #pollBindStatus(long, String)}.
     */
    public ReceiverAccountView receiveBindNotification(Map<String, String> headers, byte[] rawBody) {
        noOuterTransaction();
        if (headers == null || rawBody == null || rawBody.length == 0 || rawBody.length > 65_536) {
            throw invalid("invalid ledger bind notification");
        }
        byte[] copy = rawBody.clone();
        Map<String, String> safeHeaders = Map.copyOf(headers);
        final BindNotification notification;
        try {
            notification = notificationVerifier.verify(safeHeaders, copy);
        } catch (LakalaProtocol.ProtocolException | LakalaV2Protocol.ProtocolException badSig) {
            throw invalid("ledger bind notification signature or payload invalid");
        }
        ReceiverAccountRow hint = store.receiver.selectByApplyId(notification.applyId());
        if (hint == null) {
            hint = store.receiver.selectByReceiverNo(notification.receiverNo());
        }
        if (hint == null || !notification.receiverNo().equals(hint.receiverNo)) {
            throw unavailable("unknown receiver account for bind notification");
        }
        return applyBindAuditOutcome(hint.id, notification.applyId(), notification.receiverNo(),
                notification.auditStatus(), notification.remark(), "BIND_NOTIFY",
                OperatorType.SYSTEM.name(), null);
    }

    /** Polls Lakala 1872 for a pending {@code BIND_APPLYING} account; called by {@link ReceiverBindQueryTask}. */
    public ReceiverBindQueryTask.Outcome pollBindStatus(long merchantId, String applyId) {
        noOuterTransaction();
        if (merchantId <= 0 || applyId == null || applyId.isBlank()) {
            return ReceiverBindQueryTask.Outcome.NOT_FOUND;
        }
        ReceiverAccountRow snapshot = store.receiver.selectByMerchantId(merchantId);
        if (snapshot == null || snapshot.receiverNo == null || snapshot.receiverNo.isBlank()) {
            return ReceiverBindQueryTask.Outcome.NOT_FOUND;
        }
        if (snapshot.applyId != null && !applyId.equals(snapshot.applyId)) {
            return ReceiverBindQueryTask.Outcome.NOT_FOUND;
        }
        if ("ACTIVE".equals(snapshot.status) || "REJECTED".equals(snapshot.status)) {
            return ReceiverBindQueryTask.Outcome.DONE;
        }
        if (!"BIND_APPLYING".equals(snapshot.status)) {
            return ReceiverBindQueryTask.Outcome.NOT_FOUND;
        }
        String orgCode = settings.orgCode();
        if (orgCode == null || orgCode.isBlank()) {
            return ReceiverBindQueryTask.Outcome.PENDING;
        }
        RelationApplyItem matched;
        try {
            matched = queryBindItem(orgCode, snapshot.receiverNo, applyId);
        } catch (RuntimeException transientErr) {
            return ReceiverBindQueryTask.Outcome.PENDING;
        }
        if (matched == null || !matched.auditStatus().isTerminal()) {
            return ReceiverBindQueryTask.Outcome.PENDING;
        }
        applyBindAuditOutcome(snapshot.id, applyId, snapshot.receiverNo,
                matched.auditStatus(), matched.remark(), "BIND_POLL",
                OperatorType.SYSTEM.name(), null);
        return ReceiverBindQueryTask.Outcome.DONE;
    }

    // ---- ReceiverQueryApi ------------------------------------------------------------------

    @Override
    public ReceiverAccountView getByMerchantId(String merchantIdRaw, QueryContext context) {
        requireAdminOrSystemQuery(context);
        long merchantId = parsePositiveId(merchantIdRaw, "merchantId");
        ReceiverAccountRow row = store.receiver.selectByMerchantId(merchantId);
        if (row == null) throw notFound("receiver account not found");
        return toAccountView(row);
    }

    @Override
    public ReceiverPage list(ListReceiversQuery query) {
        if (query == null) throw invalid("missing list query");
        requireAdminOrSystemQuery(query.context());
        Long merchantId = query.merchantId() == null || query.merchantId().isBlank()
                ? null : parsePositiveId(query.merchantId(), "merchantId");
        String status = query.status() == null || query.status().isBlank() ? null : query.status().trim();
        if (status != null && !VALID_STATUSES.contains(status)) {
            throw invalid("invalid receiver status filter");
        }
        int page = Math.max(1, query.page());
        int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
        int offset = (page - 1) * size;
        Map<String, Object> params = PaymentFoundationStore.values(
                "merchantId", merchantId, "status", status, "limit", size, "offset", offset);
        long total = store.receiver.countAccounts(params);
        List<ReceiverAccountSummary> items = store.receiver.listAccounts(params).stream()
                .map(ReceiverAccountService::toSummary)
                .toList();
        return new ReceiverPage(items, total, page, size);
    }

    @Override
    public MerchantReceiverStatusView getMerchantStatusReadOnly(String merchantIdRaw, QueryContext context) {
        if (context == null || (context.operatorType() != OperatorType.MERCHANT_STAFF
                && context.operatorType() != OperatorType.PLATFORM_OPERATOR
                && context.operatorType() != OperatorType.SYSTEM)) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "merchant receiver status query forbidden");
        }
        long merchantId = parsePositiveId(merchantIdRaw, "merchantId");
        ReceiverAccountRow row = store.receiver.selectByMerchantId(merchantId);
        if (row == null) {
            return new MerchantReceiverStatusView(
                    "UNCONFIGURED", statusLabel("UNCONFIGURED"), null, null, null, null, false, null, null);
        }
        return new MerchantReceiverStatusView(
                row.status,
                statusLabel(row.status),
                row.receiverNo,
                ReceiverSensitiveCipher.maskName(row.acctName),
                row.acctNoLast4,
                row.acctOpenBankName,
                "ACTIVE".equals(row.status),
                row.rejectReason,
                PaymentReceiverStore.offset(row.updatedAt));
    }

    // ---- Internal Helpers ------------------------------------------------------------------

    private ReceiverAccountView applyBindAuditOutcome(
            long accountId,
            String applyId,
            String receiverNo,
            BindAuditStatus auditStatus,
            String remark,
            String actionType,
            String operatorType,
            Long operatorId) {
        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            ReceiverAccountRow row = store.receiver.selectByIdForUpdate(accountId);
            if (row == null || !receiverNo.equals(row.receiverNo)) {
                throw unavailable("receiver account absent or receiverNo mismatch");
            }
            if (row.applyId != null && applyId != null && !row.applyId.equals(applyId)) {
                // Stale notification/poll for a superseded applyId.
                return toAccountView(row);
            }
            if ("ACTIVE".equals(row.status)) {
                if (auditStatus == BindAuditStatus.REJECTED) {
                    throw unavailable("conflicting REJECTED bind audit for already ACTIVE receiver");
                }
                return toAccountView(row);
            }
            if ("REJECTED".equals(row.status)) {
                if (auditStatus == BindAuditStatus.APPROVED) {
                    throw unavailable("conflicting APPROVED bind audit for already REJECTED receiver");
                }
                return toAccountView(row);
            }
            if (!"BIND_APPLYING".equals(row.status)) {
                return toAccountView(row);
            }
            String nextStatus = auditStatus == BindAuditStatus.APPROVED ? "ACTIVE" : "REJECTED";
            String rejectReason = auditStatus == BindAuditStatus.REJECTED ? normalizeRejectReason(remark) : null;
            String effectiveApplyId = row.applyId != null ? row.applyId : applyId;
            updateStateRow(row, nextStatus, row.receiverNo, effectiveApplyId, row.cooperationAssetId,
                    rejectReason, row.lastChannelOrderNo, PaymentReceiverStore.utc(now), now);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("applyId", effectiveApplyId);
            detail.put("receiverNo", receiverNo);
            detail.put("auditStatus", auditStatus.name());
            detail.put("newStatus", nextStatus);
            if (rejectReason != null) detail.put("rejectReason", rejectReason);
            insertAuditRow(row.merchantId, row.id, actionType, null, null,
                    operatorType, operatorId, normalizeReason(remark), auditDetail(detail), now);
            return toAccountView(store.receiver.selectById(row.id));
        });
    }

    private RelationApplyItem queryBindItem(String orgCode, String receiverNo, String applyId) {
        var page = channel.listRelationApply(new ListRelationApplyInput(
                nextOrderNo(), orgCode, settings.platformMerInnerNo(), settings.platformMerCupNo(),
                receiverNo, applyId, "ADD", null, 10, 1));
        for (RelationApplyItem item : page.result().list()) {
            if (!receiverNo.equals(item.receiverNo())) continue;
            if (applyId == null || applyId.equals(item.applyId())) {
                return item;
            }
        }
        return null;
    }

    private List<AttachItem> uploadAttachments(String orderNo, String orgCode,
            List<ReceiverAttachmentRef> refs) {
        List<AttachItem> uploaded = new ArrayList<>(refs.size());
        for (ReceiverAttachmentRef ref : refs) {
            AssetContent content = attachmentProvider.readAsset(ref.assetId());
            if (content == null) throw unavailable("private asset absent: " + ref.assetId());
            var result = channel.uploadFile(new UploadFileInput(
                    orderNo, orgCode, ref.attType(), content.extName(), content.bytes()));
            uploaded.add(new AttachItem(ref.attType(),
                    resolveFileName(content, ref.attType()), result.result().attFileId()));
        }
        return uploaded;
    }

    private void enqueueBindQueryTask(long merchantId, String applyId, OffsetDateTime now) {
        OffsetDateTime firstPollAt = now.plus(ReceiverBindQueryTask.FIRST_DELAY)
                .truncatedTo(ChronoUnit.MILLIS);
        tasks.enqueueAt(
                ReceiverBindQueryTask.taskKey(merchantId, applyId),
                ReceiverBindQueryTask.OWNER_MODULE,
                ReceiverBindQueryTask.TASK_TYPE,
                ReceiverBindQueryTask.BIZ_TYPE,
                merchantId,
                null,
                ReceiverBindQueryTask.payload(merchantId, applyId),
                ReceiverBindQueryTask.MAX_RETRY_COUNT,
                ReceiverBindQueryTask.RETRY_POLICY,
                firstPollAt);
    }

    private void revertStatusAfterPreSubmitFailure(long accountId, long expectedVersion,
            String restoreStatus, String rejectReason) {
        tx.executeWithoutResult(status -> {
            store.session();
            OffsetDateTime now = store.now();
            ReceiverAccountRow row = store.receiver.selectByIdForUpdate(accountId);
            if (row != null && row.version == expectedVersion) {
                updateStateRow(row, restoreStatus, row.receiverNo, row.applyId,
                        row.cooperationAssetId, rejectReason, row.lastChannelOrderNo,
                        row.lastSyncedAt, now);
            }
        });
    }

    private void recordChannelRejection(long accountId, long expectedVersion, String targetStatus,
            String rejectReason, String actionType, String operatorType, Long operatorId,
            String reason, Map<String, Object> detail) {
        tx.executeWithoutResult(status -> {
            store.session();
            OffsetDateTime now = store.now();
            ReceiverAccountRow row = store.receiver.selectByIdForUpdate(accountId);
            if (row != null && row.version == expectedVersion) {
                updateStateRow(row, targetStatus, row.receiverNo, row.applyId,
                        row.cooperationAssetId, rejectReason, row.lastChannelOrderNo,
                        PaymentReceiverStore.utc(now), now);
                Map<String, Object> copy = new LinkedHashMap<>(detail);
                copy.put("rejectReason", rejectReason);
                insertAuditRow(row.merchantId, row.id, actionType, null, null,
                        operatorType, operatorId, reason, auditDetail(copy), now);
            }
        });
    }

    private ReceiverAccountView checkIdempotentReplay(byte[] reqKey, long merchantId, String fingerprint) {
        ReceiverAuditRow existing = store.receiver.selectAuditByRequestKeyForUpdate(reqKey);
        if (existing == null) return null;
        if (existing.merchantId != merchantId || !fingerprint.equals(existing.requestFingerprint)) {
            throw idempotencyConflict("receiver write requestId conflict");
        }
        ReceiverAccountRow current = store.receiver.selectByMerchantId(merchantId);
        if (current == null) throw unavailable("idempotent receiver account missing");
        return toAccountView(current);
    }

    private void insertAccountRow(long accountId, ValidatedInput input, EncryptedFields enc,
            String attachmentsJson, String status, String orderNo, OffsetDateTime now) {
        try {
            store.receiver.insertAccount(PaymentFoundationStore.values(
                    "id", accountId,
                    "merchantId", input.merchantId(),
                    "receiverName", input.receiverName(),
                    "receiverType", input.receiverType(),
                    "acctName", input.acctName(),
                    "acctTypeCode", input.acctTypeCode(),
                    "acctCertificateType", input.acctCertificateType(),
                    "acctOpenBankCode", input.acctOpenBankCode(),
                    "acctOpenBankName", input.acctOpenBankName(),
                    "acctClearBankCode", input.acctClearBankCode(),
                    "licenseName", input.licenseName(),
                    "legalPersonName", input.legalPersonName(),
                    "legalPersonCertType", input.legalPersonCertType(),
                    "acctNoCipher", enc.acctNoCipher(),
                    "acctCertNoCipher", enc.acctCertNoCipher(),
                    "contactMobileCipher", enc.contactMobileCipher(),
                    "licenseNoCipher", enc.licenseNoCipher(),
                    "legalCertNoCipher", enc.legalCertNoCipher(),
                    "acctNoMasked", enc.acctNoMasked(),
                    "acctNoLast4", enc.acctNoLast4(),
                    "acctCertNoMasked", enc.acctCertNoMasked(),
                    "contactMobileMasked", enc.contactMobileMasked(),
                    "licenseNoMasked", enc.licenseNoMasked(),
                    "legalCertNoMasked", enc.legalCertNoMasked(),
                    "status", status,
                    "receiverNo", null,
                    "applyId", null,
                    "attachmentsJson", attachmentsJson,
                    "cooperationAssetId", null,
                    "rejectReason", null,
                    "lastChannelOrderNo", orderNo,
                    "lastSyncedAt", null,
                    "now", PaymentReceiverStore.utc(now)));
        } catch (DuplicateKeyException race) {
            throw conflict("concurrent receiver account creation");
        }
    }

    private void updateAccountRow(long id, long version, ValidatedInput input, EncryptedFields enc,
            String attachmentsJson, String status, String rejectReason, String orderNo,
            java.time.LocalDateTime lastSyncedAt, OffsetDateTime now) {
        int updated = store.receiver.updateAccount(PaymentFoundationStore.values(
                "id", id,
                "version", version,
                "receiverName", input.receiverName(),
                "receiverType", input.receiverType(),
                "acctName", input.acctName(),
                "acctTypeCode", input.acctTypeCode(),
                "acctCertificateType", input.acctCertificateType(),
                "acctOpenBankCode", input.acctOpenBankCode(),
                "acctOpenBankName", input.acctOpenBankName(),
                "acctClearBankCode", input.acctClearBankCode(),
                "licenseName", input.licenseName(),
                "legalPersonName", input.legalPersonName(),
                "legalPersonCertType", input.legalPersonCertType(),
                "acctNoCipher", enc.acctNoCipher(),
                "acctCertNoCipher", enc.acctCertNoCipher(),
                "contactMobileCipher", enc.contactMobileCipher(),
                "licenseNoCipher", enc.licenseNoCipher(),
                "legalCertNoCipher", enc.legalCertNoCipher(),
                "acctNoMasked", enc.acctNoMasked(),
                "acctNoLast4", enc.acctNoLast4(),
                "acctCertNoMasked", enc.acctCertNoMasked(),
                "contactMobileMasked", enc.contactMobileMasked(),
                "licenseNoMasked", enc.licenseNoMasked(),
                "legalCertNoMasked", enc.legalCertNoMasked(),
                "status", status,
                "attachmentsJson", attachmentsJson,
                "rejectReason", rejectReason,
                "lastChannelOrderNo", orderNo,
                "lastSyncedAt", lastSyncedAt,
                "now", PaymentReceiverStore.utc(now)));
        if (updated != 1) throw conflict("receiver account changed concurrently");
    }

    private void updateStateRow(ReceiverAccountRow row, String status, String receiverNo, String applyId,
            String cooperationAssetId, String rejectReason, String orderNo,
            java.time.LocalDateTime lastSyncedAt, OffsetDateTime now) {
        int updated = store.receiver.updateAccountState(PaymentFoundationStore.values(
                "id", row.id,
                "version", row.version,
                "status", status,
                "receiverNo", receiverNo,
                "applyId", applyId,
                "cooperationAssetId", cooperationAssetId,
                "rejectReason", rejectReason,
                "lastChannelOrderNo", orderNo,
                "lastSyncedAt", lastSyncedAt,
                "now", PaymentReceiverStore.utc(now)));
        if (updated != 1) throw conflict("receiver account state changed concurrently");
    }

    private void insertAuditRow(long merchantId, long accountId, String actionType, byte[] reqKey,
            String fingerprint, String operatorType, Long operatorId, String reason,
            String detailJson, OffsetDateTime now) {
        try {
            store.receiver.insertAudit(PaymentFoundationStore.values(
                    "id", nextId(),
                    "merchantId", merchantId,
                    "receiverAccountId", accountId,
                    "actionType", actionType,
                    "requestKey", reqKey,
                    "requestFingerprint", fingerprint,
                    "operatorType", operatorType,
                    "operatorId", operatorId,
                    "reason", reason,
                    "detailJson", detailJson,
                    "now", PaymentReceiverStore.utc(now)));
        } catch (DuplicateKeyException dup) {
            throw idempotencyConflict("duplicate receiver audit requestId");
        }
    }

    // ---- Validation, Encryption & View Mapping ---------------------------------------------

    private record ValidatedInput(
            long merchantId,
            String receiverName,
            String receiverType,
            String acctNo,
            String acctName,
            String acctTypeCode,
            String acctCertificateType,
            String acctCertificateNo,
            String acctOpenBankCode,
            String acctOpenBankName,
            String acctClearBankCode,
            String contactMobile,
            String licenseNo,
            String licenseName,
            String legalPersonName,
            String legalPersonCertType,
            String legalPersonCertNo,
            List<ReceiverAttachmentRef> attachments) {}

    private record EncryptedFields(
            String acctNoCipher,
            String acctCertNoCipher,
            String contactMobileCipher,
            String licenseNoCipher,
            String legalCertNoCipher,
            String acctNoMasked,
            String acctNoLast4,
            String acctCertNoMasked,
            String contactMobileMasked,
            String licenseNoMasked,
            String legalCertNoMasked) {}

    private ValidatedInput validateInput(UpsertReceiverCommand cmd, boolean requireSubmitAttachments) {
        long merchantId = parsePositiveId(cmd.merchantId(), "merchantId");
        String receiverName = requireText(cmd.receiverName(), 64, "receiverName");
        String receiverType = requireText(cmd.receiverType(), 16, "receiverType");
        if (!"PERSONAL".equals(receiverType) && !"ENTERPRISE".equals(receiverType)) {
            throw invalid("receiverType must be PERSONAL or ENTERPRISE");
        }
        String acctTypeCode = requireText(cmd.acctTypeCode(), 16, "acctTypeCode");
        if ("PERSONAL".equals(receiverType) && !"58".equals(acctTypeCode)) {
            throw invalid("PERSONAL receiver requires acctTypeCode=58");
        }
        if ("ENTERPRISE".equals(receiverType) && !"57".equals(acctTypeCode)) {
            throw invalid("ENTERPRISE receiver requires acctTypeCode=57");
        }
        String acctNo = requireText(cmd.acctNo(), 32, "acctNo");
        if (acctNo.length() < 4) throw invalid("acctNo must be at least 4 chars");
        String acctName = requireText(cmd.acctName(), 64, "acctName");
        String acctCertType = requireText(cmd.acctCertificateType(), 16, "acctCertificateType");
        if (!LakalaV2Protocol.VALID_CERT_TYPES.contains(acctCertType)) {
            throw invalid("invalid acctCertificateType");
        }
        String acctCertNo = requireText(cmd.acctCertificateNo(), 32, "acctCertificateNo");
        String acctOpenBankCode = requireText(cmd.acctOpenBankCode(), 32, "acctOpenBankCode");
        String acctOpenBankName = requireText(cmd.acctOpenBankName(), 64, "acctOpenBankName");
        String acctClearBankCode = requireText(cmd.acctClearBankCode(), 32, "acctClearBankCode");
        String contactMobile = requireText(cmd.contactMobile(), 16, "contactMobile");

        boolean enterprise = "ENTERPRISE".equals(receiverType);
        String licenseNo = enterprise
                ? requireText(cmd.licenseNo(), 32, "licenseNo")
                : normalizeOptionalText(cmd.licenseNo(), 32, "licenseNo");
        String licenseName = enterprise
                ? requireText(cmd.licenseName(), 128, "licenseName")
                : normalizeOptionalText(cmd.licenseName(), 128, "licenseName");
        String legalPersonName = enterprise
                ? requireText(cmd.legalPersonName(), 64, "legalPersonName")
                : normalizeOptionalText(cmd.legalPersonName(), 64, "legalPersonName");
        String legalCertType = enterprise
                ? requireText(cmd.legalPersonCertificateType(), 16, "legalPersonCertificateType")
                : normalizeOptionalText(cmd.legalPersonCertificateType(), 16, "legalPersonCertificateType");
        if (legalCertType != null && !LakalaV2Protocol.VALID_CERT_TYPES.contains(legalCertType)) {
            throw invalid("invalid legalPersonCertificateType");
        }
        String legalCertNo = enterprise
                ? requireText(cmd.legalPersonCertificateNo(), 32, "legalPersonCertificateNo")
                : normalizeOptionalText(cmd.legalPersonCertificateNo(), 32, "legalPersonCertificateNo");

        List<ReceiverAttachmentRef> normalizedAtts = new ArrayList<>();
        Set<String> presentTypes = new LinkedHashSet<>();
        for (ReceiverAttachmentRef ref : cmd.attachments()) {
            if (ref == null) throw invalid("null attachment reference");
            String attType = requireText(ref.attType(), 64, "attType");
            if (!LakalaV2Protocol.VALID_ATT_TYPES.contains(attType)) {
                throw invalid("unsupported attType: " + attType);
            }
            String assetId = requireText(ref.assetId(), 64, "assetId");
            normalizedAtts.add(new ReceiverAttachmentRef(attType, assetId));
            presentTypes.add(attType);
        }
        if (requireSubmitAttachments) {
            Set<String> required = enterprise ? ENTERPRISE_REQUIRED_ATTS : PERSONAL_REQUIRED_ATTS;
            if (!presentTypes.containsAll(required)) {
                throw invalid("missing required attachments for " + receiverType + ": " + required);
            }
        }
        return new ValidatedInput(merchantId, receiverName, receiverType, acctNo, acctName, acctTypeCode,
                acctCertType, acctCertNo, acctOpenBankCode, acctOpenBankName, acctClearBankCode,
                contactMobile, licenseNo, licenseName, legalPersonName, legalCertType, legalCertNo,
                List.copyOf(normalizedAtts));
    }

    private EncryptedFields encryptAndMask(ValidatedInput in) {
        return new EncryptedFields(
                cipher.encrypt(in.merchantId(), FIELD_ACCT_NO, in.acctNo()),
                cipher.encrypt(in.merchantId(), FIELD_ACCT_CERT_NO, in.acctCertificateNo()),
                cipher.encrypt(in.merchantId(), FIELD_CONTACT_MOBILE, in.contactMobile()),
                cipher.encrypt(in.merchantId(), FIELD_LICENSE_NO, in.licenseNo()),
                cipher.encrypt(in.merchantId(), FIELD_LEGAL_CERT_NO, in.legalPersonCertNo()),
                ReceiverSensitiveCipher.maskCard(in.acctNo()),
                ReceiverSensitiveCipher.last4(in.acctNo()),
                ReceiverSensitiveCipher.maskIdCard(in.acctCertificateNo()),
                ReceiverSensitiveCipher.maskPhone(in.contactMobile()),
                ReceiverSensitiveCipher.maskLicense(in.licenseNo()),
                ReceiverSensitiveCipher.maskIdCard(in.legalPersonCertNo()));
    }

    private static ReceiverAccountView toAccountView(ReceiverAccountRow r) {
        return new ReceiverAccountView(
                IDS.toApi(r.id),
                IDS.toApi(r.merchantId),
                r.status,
                r.receiverNo,
                r.applyId,
                r.settleType,
                r.receiverName,
                r.receiverType,
                r.acctName,
                r.acctNoMasked,
                r.acctNoLast4,
                r.acctTypeCode,
                r.acctCertificateType,
                r.acctCertNoMasked,
                r.acctOpenBankCode,
                r.acctOpenBankName,
                r.acctClearBankCode,
                r.contactMobileMasked,
                r.licenseNoMasked,
                r.licenseName,
                r.legalPersonName,
                r.legalPersonCertType,
                r.legalCertNoMasked,
                deserializeAttachments(r.attachmentsJson),
                r.cooperationAssetId,
                r.rejectReason,
                r.version,
                PaymentReceiverStore.offset(r.lastSyncedAt),
                PaymentReceiverStore.offset(r.createdAt),
                PaymentReceiverStore.offset(r.updatedAt));
    }

    private static ReceiverAccountSummary toSummary(ReceiverAccountRow r) {
        return new ReceiverAccountSummary(
                IDS.toApi(r.id),
                IDS.toApi(r.merchantId),
                r.status,
                r.receiverNo,
                r.applyId,
                r.receiverName,
                r.receiverType,
                r.acctName,
                r.acctNoMasked,
                r.acctNoLast4,
                r.acctOpenBankName,
                r.contactMobileMasked,
                r.rejectReason,
                PaymentReceiverStore.offset(r.updatedAt));
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "UNCONFIGURED" -> "未配置";
            case "DRAFT" -> "资料草稿";
            case "RECEIVER_APPLYING" -> "开通申请中";
            case "RECEIVER_CREATED" -> "待绑定分账关系";
            case "BIND_APPLYING" -> "绑定审核中";
            case "ACTIVE" -> "已生效";
            case "REJECTED" -> "审核驳回";
            case "CHANGE_APPLYING" -> "资料变更中";
            default -> status;
        };
    }

    private String nextOrderNo() {
        String ts = ORDER_NO_TIME.format(clock.instant().atOffset(ZoneOffset.UTC));
        int rand = RANDOM.nextInt(100_000_000);
        return ts + String.format("%08d", rand);
    }

    private String requireOrgCode() {
        if (settings.orgCode() == null || settings.orgCode().isBlank()) {
            throw unavailable("pet.payment.settlement.org-code is required for Lakala MMS calls");
        }
        return settings.orgCode().trim();
    }

    private String requireLedgerNotifyUrl() {
        if (settings.ledgerNotifyUrl() == null || settings.ledgerNotifyUrl().isBlank()) {
            throw unavailable("pet.payment.receiver.ledger-notify-url is required for Lakala 1290 bind");
        }
        return settings.ledgerNotifyUrl().trim();
    }

    private static String resolveFileName(AssetContent content, String fallbackStem) {
        String raw = content.fileName();
        String chosen = (raw == null || raw.isBlank()) ? fallbackStem + "." + content.extName() : raw.trim();
        return chosen.length() > 32 ? chosen.substring(0, 32) : chosen;
    }

    private static String serializeAttachments(List<ReceiverAttachmentRef> attachments) {
        try {
            return JSON.writeValueAsString(attachments);
        } catch (IOException failure) {
            throw unavailable("failed to serialize receiver attachment refs");
        }
    }

    private static List<ReceiverAttachmentRef> deserializeAttachments(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<ReceiverAttachmentRef> parsed = JSON.readValue(raw, ATTACH_LIST_TYPE);
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (IOException failure) {
            throw unavailable("corrupted receiver attachments_json");
        }
    }

    private static String auditDetail(Map<String, Object> detail) {
        try {
            return JSON.writeValueAsString(detail);
        } catch (IOException failure) {
            throw unavailable("failed to serialize receiver audit detail");
        }
    }

    private static String upsertFingerprint(String action, ValidatedInput in, boolean submitToChannel) {
        StringBuilder sb = new StringBuilder();
        sb.append(action).append('|').append(in.merchantId()).append('|').append(submitToChannel)
                .append('|').append(in.receiverName()).append('|').append(in.receiverType())
                .append('|').append(in.acctNo()).append('|').append(in.acctName())
                .append('|').append(in.acctTypeCode()).append('|').append(in.acctCertificateType())
                .append('|').append(in.acctCertificateNo()).append('|').append(in.acctOpenBankCode())
                .append('|').append(in.acctOpenBankName()).append('|').append(in.acctClearBankCode())
                .append('|').append(in.contactMobile())
                .append('|').append(nonNull(in.licenseNo())).append('|').append(nonNull(in.licenseName()))
                .append('|').append(nonNull(in.legalPersonName()))
                .append('|').append(nonNull(in.legalPersonCertType()))
                .append('|').append(nonNull(in.legalPersonCertNo()));
        for (ReceiverAttachmentRef ref : in.attachments()) {
            sb.append('|').append(ref.attType()).append('=').append(ref.assetId());
        }
        return sha256(sb.toString());
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static String formatRejectReason(String retCode, String retMsg) {
        String combined = (retMsg == null || retMsg.isBlank()) ? retCode : retCode + ": " + retMsg.trim();
        return normalizeRejectReason(combined);
    }

    private static String normalizeRejectReason(String raw) {
        if (raw == null || raw.isBlank()) return "CHANNEL_REJECTED";
        String trimmed = raw.trim();
        return trimmed.length() > 512 ? trimmed.substring(0, 512) : trimmed;
    }

    private long nextId() {
        long value = ids.nextId();
        if (value <= 0) throw unavailable("snowflake id generator returned invalid id");
        return value;
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw unavailable("receiver command must not run inside caller transaction");
        }
    }

    private static void requireAdminOrSystem(CommandContext ctx) {
        if (ctx.operatorType() != OperatorType.PLATFORM_OPERATOR && ctx.operatorType() != OperatorType.SYSTEM) {
            throw new ApiException(CommonApiCodes.FORBIDDEN,
                    "receiver command requires PLATFORM_OPERATOR or SYSTEM");
        }
    }

    private static void requireAdminOrSystemQuery(QueryContext ctx) {
        if (ctx == null || (ctx.operatorType() != OperatorType.PLATFORM_OPERATOR
                && ctx.operatorType() != OperatorType.SYSTEM)) {
            throw new ApiException(CommonApiCodes.FORBIDDEN,
                    "receiver query requires PLATFORM_OPERATOR or SYSTEM");
        }
    }

    private static String requireRequestId(CommandContext ctx) {
        String req = ctx.requestId();
        if (req == null || req.isBlank() || req.length() > 128) {
            throw invalid("requestId is required");
        }
        return req.trim();
    }

    private static byte[] requestKey(String action, CommandContext ctx, String requestId) {
        String op = ctx.operatorId() == null ? "SYSTEM" : ctx.operatorId();
        return (action + "/" + ctx.operatorType().name() + "/" + op + "/" + requestId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static long parsePositiveId(String raw, String field) {
        try {
            return IDS.fromApi(raw);
        } catch (IllegalArgumentException bad) {
            throw invalid("invalid " + field);
        }
    }

    private static Long parseNullableId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return parsePositiveId(raw, "operatorId");
    }

    private static String requireText(String raw, int max, String field) {
        if (raw == null || raw.isBlank()) throw invalid(field + " is required");
        String trimmed = raw.trim();
        if (trimmed.length() > max || trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("invalid " + field);
        }
        return trimmed;
    }

    private static String normalizeOptionalText(String raw, int max, String field) {
        if (raw == null || raw.isBlank()) return null;
        return requireText(raw, max, field);
    }

    private static String normalizeOptionalId(String raw, int max, String field) {
        if (raw == null || raw.isBlank()) return null;
        return requireText(raw, max, field);
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) return null;
        String trimmed = reason.trim();
        return trimmed.length() > 256 ? trimmed.substring(0, 256) : trimmed;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static ApiException invalid(String msg) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, msg);
    }

    private static ApiException conflict(String msg) {
        return new ApiException(CommonApiCodes.CONFLICT, msg);
    }

    private static ApiException idempotencyConflict(String msg) {
        return new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, msg);
    }

    private static ApiException notFound(String msg) {
        return new ApiException(CommonApiCodes.NOT_FOUND, msg);
    }

    private static ApiException unavailable(String msg) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, msg);
    }
}
