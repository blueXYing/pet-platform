package com.petplatform.payment.biz.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.api.dto.ReceiverTypes.BindReceiverCommand;
import com.petplatform.payment.api.dto.ReceiverTypes.ListReceiversQuery;
import com.petplatform.payment.api.dto.ReceiverTypes.MerchantReceiverStatusView;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverAccountView;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverAttachmentRef;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverPage;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverSensitiveView;
import com.petplatform.payment.api.dto.ReceiverTypes.RevealReceiverCommand;
import com.petplatform.payment.api.dto.ReceiverTypes.UpsertReceiverCommand;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedApplyBind;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedReceiverAction;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedReceiverDetail;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedRelationApplyPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient.VerifiedUploadFile;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyBindInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyBindResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ApplyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.BindAuditStatus;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ListRelationApplyInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ModifyReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.QueryReceiverInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ReceiverActionResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.ReceiverDetailResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.RelationApplyItem;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.RelationApplyPageResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.UploadFileInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2Protocol.UploadFileResult;
import com.petplatform.payment.biz.settlement.ReceiverAccountService.BindNotificationVerifier;
import com.petplatform.payment.biz.settlement.ReceiverAttachmentProvider.AssetContent;
import com.petplatform.payment.biz.settlement.SettlementReceiverLookup.ActiveReceiver;
import com.petplatform.payment.biz.task.ReceiverBindQueryTask;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Real MySQL 8 integration tests for Contract 80 / V40 Stage 4 split-receiver onboarding:
 * draft -> 1084+1287 sync create -> AES-256-GCM DB ciphertext verification (zero plaintext) ->
 * masked admin & read-only merchant views -> audited reveal -> 1084+1288 modify ->
 * 1084+1290 async bind & durable RECEIVER_BIND_QUERY task -> 1872 poll x 1290 callback race ->
 * ACTIVE -> DbSettlementReceiverLookup -> rejected & re-bind flow -> requestId idempotency & conflict.
 */
class PaymentReceiverMySqlTest {
    private static final String PLATFORM_MCH = "82229005943096D";
    private static final String ORG_CODE = "200669";
    private static final String NOTIFY_URL = "https://notify.invalid/api/v1/callbacks/lakala/ledger";
    private static final String SHA = "a".repeat(64);
    private static final String RAW_CARD = "6222021234567894837";
    private static final String RAW_CARD_NEW = "6228489999888871234";
    private static final String RAW_ID_NO = "310101199001011234";
    private static final String RAW_MOBILE = "13812345678";
    private static final String RAW_LICENSE = "91310000MA1FL0001X";
    private static final String RAW_LEGAL_CERT = "320501198505056789";

    private static KeyPair notifyKeys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        notifyKeys = gen.generateKeyPair();
    }

    @Test
    void fullLifecycleEncryptMaskAuditRevealModifyBindRaceLookupAndIdempotency() throws Exception {
        try (Db db = Db.open()) {
            var channel = new FakeReceiverChannel();
            var service = db.service(channel);
            var lookup = new DbSettlementReceiverLookup(db.ds);
            var adminQ = new QueryContext("t1", OperatorType.PLATFORM_OPERATOR, "1001");
            var merchantQ = new QueryContext("t2", OperatorType.MERCHANT_STAFF, "2001");

            // 1. Unconfigured merchant: read-only status is UNCONFIGURED, lookup fails closed.
            MerchantReceiverStatusView unconfigured = service.getMerchantStatusReadOnly("5001", merchantQ);
            assertEquals("UNCONFIGURED", unconfigured.status());
            assertFalse(unconfigured.active());
            assertTrue(lookup.findActiveByMerchantId(5001L).isEmpty());

            // 2. Save DRAFT (submitToChannel=false) — zero Lakala calls.
            var draftCmd = personalCmd("req-draft-1", "5001", false, RAW_CARD, "中国工商银行北京分行");
            ReceiverAccountView draft = service.upsertOrCreate(draftCmd);
            assertEquals("DRAFT", draft.status());
            assertNull(draft.receiverNo());
            assertEquals("01", draft.settleType());
            assertEquals("6222****4837", draft.acctNoMasked());
            assertEquals("4837", draft.acctNoLast4());
            assertEquals("310****1234", draft.acctCertificateNoMasked());
            assertEquals("138****5678", draft.contactMobileMasked());
            assertEquals(0, channel.uploadCalls.size());
            assertEquals(0, channel.createCalls.get());

            // Idempotent replay of DRAFT save with same requestId -> no duplicate audit row.
            ReceiverAccountView draftReplay = service.upsertOrCreate(draftCmd);
            assertEquals(draft.receiverAccountId(), draftReplay.receiverAccountId());
            assertEquals(1, db.count("SELECT COUNT(*) FROM settlement_receiver_audit WHERE merchant_id=5001"));

            // Same requestId + different args -> IDEMPOTENCY_KEY_CONFLICT.
            ApiException draftConflict = assertThrows(ApiException.class, () -> service.upsertOrCreate(
                    personalCmd("req-draft-1", "5001", false, RAW_CARD_NEW, "中国工商银行北京分行")));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, draftConflict.code());

            // 3. Submit 1084 + 1287 sync create.
            var createCmd = personalCmd("req-create-1", "5001", true, RAW_CARD, "中国工商银行北京分行");
            ReceiverAccountView created = service.upsertOrCreate(createCmd);
            assertEquals("RECEIVER_CREATED", created.status());
            assertEquals("SR202611250001", created.receiverNo());
            assertEquals("01", created.settleType());
            assertEquals(3, channel.uploadCalls.size(), "personal create uploads 3 attachments via 1084");
            assertEquals(1, channel.createCalls.get());
            String createOrderNo = channel.lastCreateInput.get().orderNo();
            assertTrue(channel.uploadCalls.stream().allMatch(u -> createOrderNo.equals(u.orderNo())),
                    "1084 uploads must share the exact 1287 orderNo within 24h window");
            assertEquals(3, channel.lastCreateInput.get().attachList().size());

            // Idempotent replay of create -> does NOT call Lakala again!
            ReceiverAccountView createReplay = service.upsertOrCreate(createCmd);
            assertEquals("SR202611250001", createReplay.receiverNo());
            assertEquals(1, channel.createCalls.get());
            assertEquals(3, channel.uploadCalls.size());

            // Same requestId + different args on create -> IDEMPOTENCY_KEY_CONFLICT.
            ApiException createConflict = assertThrows(ApiException.class, () -> service.upsertOrCreate(
                    personalCmd("req-create-1", "5001", true, RAW_CARD_NEW, "中国工商银行北京分行")));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, createConflict.code());

            // 4. RAW DB INSPECTION: verify ciphertext columns NEVER contain plaintext AND attachments_json
            //    NEVER persists Lakala transient attFileId ("G1/M00/...").
            try (Connection c = db.ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT acct_no_cipher, acct_cert_no_cipher, contact_mobile_cipher, "
                                 + "attachments_json, settle_type FROM settlement_receiver_account WHERE merchant_id=5001");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String acctCipher = rs.getString(1);
                String certCipher = rs.getString(2);
                String phoneCipher = rs.getString(3);
                String attJson = rs.getString(4);
                String settleType = rs.getString(5);
                assertTrue(acctCipher.startsWith("v1:"));
                assertFalse(acctCipher.contains(RAW_CARD));
                assertFalse(acctCipher.contains("4837"));
                assertFalse(certCipher.contains(RAW_ID_NO));
                assertFalse(phoneCipher.contains(RAW_MOBILE));
                assertEquals("01", settleType);
                assertTrue(attJson.contains("asset-id-front"));
                assertFalse(attJson.contains("G1/M00/"), "must NEVER persist Lakala 24h attFileId");
            }
            // Audit detail_json must NEVER contain plaintext card/ID/phone.
            try (Connection c = db.ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT detail_json FROM settlement_receiver_audit WHERE merchant_id=5001");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String detail = rs.getString(1);
                    if (detail != null) {
                        assertFalse(detail.contains(RAW_CARD));
                        assertFalse(detail.contains(RAW_ID_NO));
                        assertFalse(detail.contains(RAW_MOBILE));
                        assertFalse(detail.contains("G1/M00/"));
                    }
                }
            }

            // Lookup is STILL empty until status is ACTIVE!
            assertTrue(lookup.findActiveByMerchantId(5001L).isEmpty());

            // 5. Masked reads (admin detail + admin list + merchant read-only).
            ReceiverAccountView fetched = service.getByMerchantId("5001", adminQ);
            assertEquals("6222****4837", fetched.acctNoMasked());
            ReceiverPage page = service.list(new ListReceiversQuery(adminQ, null, "RECEIVER_CREATED", 1, 20));
            assertEquals(1, page.total());
            assertEquals("6222****4837", page.items().get(0).acctNoMasked());

            MerchantReceiverStatusView mView = service.getMerchantStatusReadOnly("5001", merchantQ);
            assertEquals("RECEIVER_CREATED", mView.status());
            assertEquals("*三", mView.acctNameMasked());
            assertEquals("4837", mView.acctNoLast4());
            assertFalse(mView.active());

            // 6. Audited reveal: rejects SYSTEM/MERCHANT_STAFF & blank reason; succeeds for PLATFORM_OPERATOR.
            ApiException sysReveal = assertThrows(ApiException.class, () -> service.revealSensitive(
                    new RevealReceiverCommand(
                            new CommandContext("r1", "t", OperatorType.SYSTEM, null, "SYS"), "5001", "check")));
            assertEquals(CommonApiCodes.FORBIDDEN, sysReveal.code());

            ApiException blankReason = assertThrows(ApiException.class, () -> service.revealSensitive(
                    new RevealReceiverCommand(
                            new CommandContext("r2", "t", OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN"),
                            "5001", "   ")));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, blankReason.code());

            ReceiverSensitiveView revealed = service.revealSensitive(new RevealReceiverCommand(
                    new CommandContext("r3", "t", OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN"),
                    "5001", "核对商户银行卡信息"));
            assertEquals(RAW_CARD, revealed.acctNo());
            assertEquals(RAW_ID_NO, revealed.acctCertificateNo());
            assertEquals(RAW_MOBILE, revealed.contactMobile());
            assertEquals(1, db.count(
                    "SELECT COUNT(*) FROM settlement_receiver_audit WHERE merchant_id=5001 AND action_type='REVEAL' "
                            + "AND reason='核对商户银行卡信息'"));

            // 7. 1084 + 1288 sync modify (change bank card & bank name).
            var modCmd = personalCmd("req-mod-1", "5001", true, RAW_CARD_NEW, "中国农业银行上海分行");
            ReceiverAccountView modified = service.modify(modCmd);
            assertEquals("RECEIVER_CREATED", modified.status());
            assertEquals("6228****1234", modified.acctNoMasked());
            assertEquals("1234", modified.acctNoLast4());
            assertEquals("中国农业银行上海分行", modified.acctOpenBankName());
            assertEquals(1, channel.modifyCalls.get());
            // Replay modify with same requestId -> idempotent, does NOT call Lakala again.
            assertEquals("1234", service.modify(modCmd).acctNoLast4());
            assertEquals(1, channel.modifyCalls.get());
            // Conflict on modify requestId with different args.
            ApiException modConflict = assertThrows(ApiException.class, () -> service.modify(
                    personalCmd("req-mod-1", "5001", true, RAW_CARD, "中国农业银行上海分行")));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, modConflict.code());

            // 8. 1084 + 1290 async bind -> BIND_APPLYING + atomic RECEIVER_BIND_QUERY task enqueue.
            var bindCmd = new BindReceiverCommand(
                    new CommandContext("req-bind-1", "t", OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN"),
                    "5001", "asset-coop-1", "发起分账绑定");
            ReceiverAccountView binding = service.bind(bindCmd);
            assertEquals("BIND_APPLYING", binding.status());
            assertEquals("548099616395186176", binding.applyId());
            assertEquals("asset-coop-1", binding.cooperationAssetId());
            assertEquals(1, channel.bindCalls.get());
            assertEquals(1, db.count(
                    "SELECT COUNT(*) FROM async_task WHERE task_key='RECEIVER_BIND_QUERY:5001:548099616395186176' "
                            + "AND task_type='RECEIVER_BIND_QUERY' AND retry_policy='RECEIVER_BIND_QUERY'"));
            // Replay bind with same requestId -> idempotent, zero extra 1290 calls.
            assertEquals("BIND_APPLYING", service.bind(bindCmd).status());
            assertEquals(1, channel.bindCalls.get());
            // Conflict on bind requestId with different asset.
            ApiException bindConflict = assertThrows(ApiException.class, () -> service.bind(
                    new BindReceiverCommand(
                            new CommandContext("req-bind-1", "t", OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN"),
                            "5001", "asset-coop-2", "发起分账绑定")));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, bindConflict.code());

            // 9. 1872 poll while auditStatus=0 (PENDING) keeps BIND_APPLYING.
            channel.pollStatus.set(BindAuditStatus.PENDING);
            assertEquals(ReceiverBindQueryTask.Outcome.PENDING,
                    service.pollBindStatus(5001L, "548099616395186176"));
            assertEquals("BIND_APPLYING", service.getByMerchantId("5001", adminQ).status());
            assertTrue(lookup.findActiveByMerchantId(5001L).isEmpty());

            // Bad 1009 signature on 1290 callback rejected before any DB write!
            byte[] approvedNotifyBody = ("{\"respData\":{\"optType\":\"ADD\",\"applyId\":548099616395186176,"
                    + "\"merCupNo\":\"" + PLATFORM_MCH + "\",\"receiverNo\":\"SR202611250001\","
                    + "\"auditStatus\":\"1\",\"auditStatusText\":\"审核通过\",\"remark\":\"通过\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            ApiException badSig = assertThrows(ApiException.class, () -> service.receiveBindNotification(
                    Map.of("Authorization", "LKLAPI-SHA256withRSA timestamp=\"1\",nonce_str=\"AbCd12345678\",signature=\"AAAA\""),
                    approvedNotifyBody));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, badSig.code());
            assertEquals("BIND_APPLYING", service.getByMerchantId("5001", adminQ).status());

            // 10. CONCURRENT RACE: 1872 poll (auditStatus=1) x signed 1290 callback (auditStatus=1).
            channel.pollStatus.set(BindAuditStatus.APPROVED);
            Map<String, String> validNotifyHeaders = signNotification(approvedNotifyBody);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                Future<ReceiverBindQueryTask.Outcome> pollFuture = pool.submit(() -> {
                    start.await();
                    return service.pollBindStatus(5001L, "548099616395186176");
                });
                Future<ReceiverAccountView> notifyFuture = pool.submit(() -> {
                    start.await();
                    return service.receiveBindNotification(validNotifyHeaders, approvedNotifyBody);
                });
                start.countDown();
                assertEquals(ReceiverBindQueryTask.Outcome.DONE, pollFuture.get());
                assertEquals("ACTIVE", notifyFuture.get().status());
            } finally {
                pool.shutdownNow();
            }

            // Exactly ONE terminal bind audit row was written during the race!
            assertEquals(1, db.count(
                    "SELECT COUNT(*) FROM settlement_receiver_audit WHERE merchant_id=5001 "
                            + "AND action_type IN ('BIND_NOTIFY','BIND_POLL')"));

            // 11. DbSettlementReceiverLookup NOW returns ActiveReceiver(5001, "SR202611250001")!
            Optional<ActiveReceiver> active = lookup.findActiveByMerchantId(5001L);
            assertTrue(active.isPresent());
            assertEquals(new ActiveReceiver(5001L, "SR202611250001"), active.get());

            // Merchant read-only view reflects ACTIVE & last4 of modified card.
            MerchantReceiverStatusView activeMerchantView = service.getMerchantStatusReadOnly("5001", merchantQ);
            assertTrue(activeMerchantView.active());
            assertEquals("ACTIVE", activeMerchantView.status());
            assertEquals("已生效", activeMerchantView.statusLabel());
            assertEquals("1234", activeMerchantView.acctNoLast4());

            // Replaying same 1290 approved notification is idempotent; conflicting REJECTED callback fails closed.
            assertEquals("ACTIVE", service.receiveBindNotification(validNotifyHeaders, approvedNotifyBody).status());
            byte[] conflictRejectBody = ("{\"respData\":{\"optType\":\"ADD\",\"applyId\":548099616395186176,"
                    + "\"receiverNo\":\"SR202611250001\",\"auditStatus\":\"2\",\"remark\":\"驳回\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            ApiException conflictCb = assertThrows(ApiException.class, () -> service.receiveBindNotification(
                    signNotification(conflictRejectBody), conflictRejectBody));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, conflictCb.code());
        }
    }

    @Test
    void enterpriseReceiverRejectedBindFlowAndRebindToActive() throws Exception {
        try (Db db = Db.open()) {
            var channel = new FakeReceiverChannel();
            channel.nextReceiverNo.set("SR202611250099");
            channel.nextApplyId.set("548099616395189001");
            var service = db.service(channel);
            var lookup = new DbSettlementReceiverLookup(db.ds);
            var adminQ = new QueryContext("t1", OperatorType.PLATFORM_OPERATOR, "1001");

            // Create ENTERPRISE (acctTypeCode="57") receiver with license + legal person info.
            var entCmd = new UpsertReceiverCommand(
                    new CommandContext("req-ent-create", "t", OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN"),
                    "6002", true, "萌宠企业旗舰店", "ENTERPRISE",
                    RAW_CARD, "上海萌宠科技有限公司", "57", "17", RAW_ID_NO,
                    "102100099996", "中国工商银行上海分行", "102100099996", RAW_MOBILE,
                    RAW_LICENSE, "上海萌宠科技有限公司", "李四", "17", RAW_LEGAL_CERT,
                    List.of(
                            new ReceiverAttachmentRef("FR_ID_CARD_FRONT", "asset-fr-front"),
                            new ReceiverAttachmentRef("FR_ID_CARD_BEHIND", "asset-fr-behind"),
                            new ReceiverAttachmentRef("BANK_CARD", "asset-bank"),
                            new ReceiverAttachmentRef("BUSINESS_LICENCE", "asset-biz-lic")),
                    "企业商户开通");
            ReceiverAccountView created = service.upsertOrCreate(entCmd);
            assertEquals("RECEIVER_CREATED", created.status());
            assertEquals("9131****001X", created.licenseNoMasked());
            assertEquals("320****6789", created.legalPersonCertificateNoMasked());

            // Verify raw DB does NOT store plaintext license or legal person cert no.
            try (Connection c = db.ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT license_no_cipher, legal_cert_no_cipher FROM settlement_receiver_account WHERE merchant_id=6002");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertFalse(rs.getString(1).contains(RAW_LICENSE));
                assertFalse(rs.getString(2).contains(RAW_LEGAL_CERT));
            }
            ReceiverSensitiveView revealed = service.revealSensitive(new RevealReceiverCommand(
                    new CommandContext("req-ent-rev", "t", OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN"),
                    "6002", "审核执照"));
            assertEquals(RAW_LICENSE, revealed.licenseNo());
            assertEquals(RAW_LEGAL_CERT, revealed.legalPersonCertificateNo());

            // First bind -> REJECTED by 1872 poll with rejectReason.
            service.bind(new BindReceiverCommand(
                    new CommandContext("req-ent-bind-1", "t", OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN"),
                    "6002", "asset-coop-bad", "首次绑定"));
            channel.pollStatus.set(BindAuditStatus.REJECTED);
            channel.pollRemark.set("合作协议公章不清晰");
            assertEquals(ReceiverBindQueryTask.Outcome.DONE,
                    service.pollBindStatus(6002L, "548099616395189001"));

            ReceiverAccountView rejected = service.getByMerchantId("6002", adminQ);
            assertEquals("REJECTED", rejected.status());
            assertEquals("合作协议公章不清晰", rejected.rejectReason());
            assertTrue(lookup.findActiveByMerchantId(6002L).isEmpty());

            // Re-bind with corrected cooperation asset -> new applyId -> syncFromChannel advances to ACTIVE.
            channel.nextApplyId.set("548099616395189002");
            ReceiverAccountView rebinding = service.bind(new BindReceiverCommand(
                    new CommandContext("req-ent-bind-2", "t", OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN"),
                    "6002", "asset-coop-good", "重新提交清晰协议"));
            assertEquals("BIND_APPLYING", rebinding.status());
            assertEquals("548099616395189002", rebinding.applyId());
            assertNull(rebinding.rejectReason());

            channel.pollStatus.set(BindAuditStatus.APPROVED);
            channel.pollRemark.set("通过");
            ReceiverAccountView synced = service.syncFromChannel("6002",
                    new CommandContext("req-ent-sync", "t", OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN"));
            assertEquals("ACTIVE", synced.status());
            assertEquals(Optional.of(new ActiveReceiver(6002L, "SR202611250099")),
                    lookup.findActiveByMerchantId(6002L));
        }
    }

    private static UpsertReceiverCommand personalCmd(String reqId, String merchantId, boolean submit,
            String acctNo, String bankName) {
        return new UpsertReceiverCommand(
                new CommandContext(reqId, "t", OperatorType.PLATFORM_OPERATOR, "1001", "ADMIN"),
                merchantId, submit, "萌宠个人店", "PERSONAL",
                acctNo, "张三", "58", "17", RAW_ID_NO,
                "102100099996", bankName, "102100099996", RAW_MOBILE,
                null, null, null, null, null,
                List.of(
                        new ReceiverAttachmentRef("ID_CARD_FRONT", "asset-id-front"),
                        new ReceiverAttachmentRef("ID_CARD_BEHIND", "asset-id-behind"),
                        new ReceiverAttachmentRef("BANK_CARD", "asset-bank-card")),
                "运营代填开通");
    }

    private static Map<String, String> signNotification(byte[] body) throws Exception {
        String ts = "1764039600";
        String nonce = "AbCd12345678";
        byte[] prefix = (ts + "\n" + nonce + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] msg = new byte[prefix.length + body.length + 1];
        System.arraycopy(prefix, 0, msg, 0, prefix.length);
        System.arraycopy(body, 0, msg, prefix.length, body.length);
        msg[msg.length - 1] = '\n';
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(notifyKeys.getPrivate());
        sig.update(msg);
        String auth = "LKLAPI-SHA256withRSA timestamp=\"" + ts + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + Base64.getEncoder().encodeToString(sig.sign()) + "\"";
        return Map.of("Authorization", auth);
    }

    private static final class FakeReceiverChannel implements ReceiverChannel {
        final List<UploadFileInput> uploadCalls = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger createCalls = new AtomicInteger();
        final AtomicInteger modifyCalls = new AtomicInteger();
        final AtomicInteger bindCalls = new AtomicInteger();
        final AtomicReference<ApplyReceiverInput> lastCreateInput = new AtomicReference<>();
        final AtomicReference<String> nextReceiverNo = new AtomicReference<>("SR202611250001");
        final AtomicReference<String> nextApplyId = new AtomicReference<>("548099616395186176");
        final AtomicReference<BindAuditStatus> pollStatus = new AtomicReference<>(BindAuditStatus.PENDING);
        final AtomicReference<String> pollRemark = new AtomicReference<>("审核中");

        @Override
        public VerifiedUploadFile uploadFile(UploadFileInput input) {
            uploadCalls.add(input);
            return new VerifiedUploadFile(new UploadFileResult(
                    input.orgCode(), input.orderNo(),
                    "G1/M00/06/64/" + input.attType() + "." + input.attExtName(),
                    input.attType()), SHA);
        }

        @Override
        public VerifiedReceiverAction applyLedgerReceiver(ApplyReceiverInput input) {
            createCalls.incrementAndGet();
            lastCreateInput.set(input);
            return new VerifiedReceiverAction(new ReceiverActionResult(
                    input.orderNo(), input.orgCode(), input.orgCode(), "测试机构", nextReceiverNo.get()), SHA);
        }

        @Override
        public VerifiedReceiverAction modifyLedgerReceiver(ModifyReceiverInput input) {
            modifyCalls.incrementAndGet();
            return new VerifiedReceiverAction(new ReceiverActionResult(
                    input.orderNo(), input.orgCode(), input.orgCode(), "测试机构", input.receiverNo()), SHA);
        }

        @Override
        public VerifiedReceiverDetail queryReceiverDetail(QueryReceiverInput input) {
            return new VerifiedReceiverDetail(new ReceiverDetailResult(
                    input.receiverNo(), "萌宠店", "138****5678", null, null, null, null, null,
                    "6222****4837", "张三", "58", "17", null, "102100099996", "工商银行",
                    "102100099996", "OPEN"), SHA);
        }

        @Override
        public VerifiedApplyBind applyBind(ApplyBindInput input) {
            bindCalls.incrementAndGet();
            return new VerifiedApplyBind(new ApplyBindResult(
                    input.orderNo(), input.orgCode(), nextApplyId.get()), SHA);
        }

        @Override
        public VerifiedRelationApplyPage listRelationApply(ListRelationApplyInput input) {
            String applyId = input.applyId() != null ? input.applyId() : nextApplyId.get();
            var item = new RelationApplyItem(
                    applyId, "ADD", ORG_CODE, "测试机构", null, PLATFORM_MCH,
                    input.receiverNo(), "萌宠店", pollStatus.get(), pollRemark.get(), "2026-11-25 12:00:00");
            return new VerifiedRelationApplyPage(
                    new RelationApplyPageResult(1, 1, 10, 1, List.of(item)), SHA);
        }
    }

    private static final class Db implements AutoCloseable {
        final MysqlDataSource admin;
        final MysqlDataSource ds;
        final String dbName;
        final AtomicLong seq = new AtomicLong(100_000L);
        final SnowflakeIdGenerator ids = seq::incrementAndGet;
        final ReceiverSensitiveCipher cipher;

        private Db(MysqlDataSource admin, MysqlDataSource ds, String dbName) {
            this.admin = admin;
            this.ds = ds;
            this.dbName = dbName;
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            this.cipher = new ReceiverSensitiveCipher(new SecretKeySpec(key, "AES"));
        }

        static Db open() throws Exception {
            String url = System.getenv("PAYMENT_CENTER_MYSQL_URL");
            if (url == null || url.isBlank()) url = System.getenv("PAYMENT_REFUND_MYSQL_URL");
            Assumptions.assumeTrue(url != null && !url.isBlank(), "MySQL URL env not set");
            String user = env("PAYMENT_CENTER_MYSQL_USER", env("PAYMENT_REFUND_MYSQL_USER", "root"));
            String pass = env("PAYMENT_CENTER_MYSQL_PASSWORD", env("PAYMENT_REFUND_MYSQL_PASSWORD", "root"));
            String normalized = url.endsWith("/") ? url : url + "/";
            String dbName = "payrecv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

            MysqlDataSource admin = new MysqlDataSource();
            admin.setURL(normalized + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            admin.setUser(user);
            admin.setPassword(pass);
            try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE `" + dbName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            MysqlDataSource ds = new MysqlDataSource();
            ds.setURL(normalized + dbName + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            ds.setUser(user);
            ds.setPassword(pass);

            Path root = repoRoot();
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                for (String script : List.of(
                        "docs/03-database/13-Async-Infra-Schema-v0.1.sql",
                        "docs/03-database/80-Payment-Receiver-Schema-v0.1.sql")) {
                    ScriptUtils.executeSqlScript(c, new EncodedResource(
                            new FileSystemResource(root.resolve(script)), StandardCharsets.UTF_8));
                }
                // SQL39 column required by JdbcAsyncTaskSubmitter.enqueueAt.
                s.execute("ALTER TABLE async_task ADD COLUMN submitted_execute_at DATETIME(3) NULL");
            }
            return new Db(admin, ds, dbName);
        }

        ReceiverAccountService service(ReceiverChannel channel) {
            ReceiverAttachmentProvider assets = assetId -> new AssetContent(
                    assetId, assetId.contains("coop") ? "pdf" : "png",
                    assetId + (assetId.contains("coop") ? ".pdf" : ".png"),
                    ("bytes-" + assetId).getBytes(StandardCharsets.UTF_8));
            return new ReceiverAccountService(
                    ds, ids, cipher, assets, channel,
                    new JdbcAsyncTaskSubmitter(ds, ids),
                    BindNotificationVerifier.fromPublicKey(notifyKeys.getPublic()),
                    new ReceiverAccountService.Settings(ORG_CODE, PLATFORM_MCH, null, NOTIFY_URL),
                    Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC));
        }

        int count(String sql) throws Exception {
            try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                return rs.getInt(1);
            }
        }

        @Override
        public void close() throws Exception {
            try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS `" + dbName + "`");
            }
        }

        private static String env(String key, String fallback) {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? fallback : v;
        }

        private static Path repoRoot() {
            Path p = Path.of("").toAbsolutePath();
            while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
            return java.util.Objects.requireNonNull(p);
        }
    }
}
