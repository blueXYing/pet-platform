package com.petplatform.merchant.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.command.MerchantAgreementConsentCommand;
import com.petplatform.merchant.api.dto.MerchantAgreementConsentDTO;
import com.petplatform.merchant.api.dto.MerchantAgreementDTO;
import com.petplatform.merchant.api.query.MerchantAgreementQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantAgreementApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

/** S3 agreement read/consent/idempotency tests over real isolated MySQL. */
class MerchantAgreementApiMySqlTest {
    private static final long MERCHANT_ID = 9_007_199_254_740_993L;
    private static final long OWNER_ID = 7_000_000_001L;
    private static final long OTHER_USER_ID = 7_000_000_002L;
    private static final long STAFF_USER_ID = 7_000_000_003L;
    private static final long AGREEMENT_V1_ID = 9_100_000_000_000_001L;
    private static final long AGREEMENT_V2_ID = 9_100_000_000_000_002L;
    private static final String AGREEMENT_V1 = "merchant-v1";
    private static final String AGREEMENT_V2 = "merchant-v2";
    private static final String CONTENT_V1 = "商家版用户协议 v1\n服务与退款条款。";
    private static final String CONTENT_V2 = "商家版用户协议 v2\n更新后的服务与退款条款。";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-17T08:00:00Z"), ZoneOffset.UTC);

    private final AtomicLong ids = new AtomicLong(9_200_000_000_000_000L);

    private static QueryContext ownerQuery(long userId) {
        return new QueryContext("trace-" + userId, OperatorType.USER, Long.toString(userId));
    }

    private static CommandContext command(String requestId, long userId) {
        return new CommandContext(requestId, "trace-" + requestId, OperatorType.USER,
                Long.toString(userId), "MERCHANT_APP");
    }

    private MerchantAgreementApiImpl api(MySqlMerchantAgreementTestDatabase db,
                                         ApplicationReviewFactsReader facts) {
        return api(db.dataSource(), ids::incrementAndGet, facts);
    }

    private static MerchantAgreementApiImpl api(DataSource dataSource,
                                                SnowflakeIdGenerator generator,
                                                ApplicationReviewFactsReader facts) {
        return new MerchantAgreementApiImpl(dataSource, generator, CLOCK, facts);
    }

    private static MerchantAgreementConsentCommand consent(String requestId, long userId,
                                                            String version, String hash,
                                                            Boolean accepted) {
        return new MerchantAgreementConsentCommand(Long.toString(MERCHANT_ID), version, hash,
                accepted, command(requestId, userId));
    }

    private static ApiException failure(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    @Test
    void ownerCanReadAndConsentWithoutPriorWorkspaceAllowedAdmission() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            AtomicReference<String> application = new AtomicReference<>("APPROVED");
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts(application.get()));

            MerchantAgreementDTO unsigned = api.getAgreement(new MerchantAgreementQuery(
                    Long.toString(MERCHANT_ID), ownerQuery(OWNER_ID)));
            assertEquals(Long.toString(MERCHANT_ID), unsigned.merchantId());
            assertEquals(AGREEMENT_V1, unsigned.agreementVersion());
            assertEquals(CONTENT_V1, unsigned.content());
            assertEquals(hash, unsigned.contentSha256());
            assertEquals("NOT_SIGNED", unsigned.signingStatus());
            assertNull(unsigned.acceptedVersion());
            assertNull(unsigned.acceptedAt());

            MerchantAgreementConsentDTO receipt = api.consent(consent(
                    UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, hash, true));
            assertEquals(Long.toString(MERCHANT_ID), receipt.merchantId());
            assertEquals(AGREEMENT_V1, receipt.agreementVersion());
            assertEquals("SIGNED", receipt.signingStatus());
            assertEquals(Instant.parse("2026-09-17T08:00:00Z"), receipt.acceptedAt().toInstant());
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance WHERE merchant_id=?",
                    Integer.class, MERCHANT_ID));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_command_idempotency WHERE status='SUCCEEDED'",
                    Integer.class));
            assertEquals("APPROVED", application.get(), "reader is a test-only admission-fact substitute");
        }
    }

    @Test
    void applyingMerchantFirstConsentFailsClosedUntilApplicationStatusIsActivated() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "APPLYING");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            String requestId = UUID.randomUUID().toString();
            MerchantAgreementConsentCommand command = consent(
                    requestId, OWNER_ID, AGREEMENT_V1, hash, true);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.consent(command)).code());
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_command_idempotency WHERE status='RESERVED'", Integer.class));
            db.jdbc().update("UPDATE merchant SET status='ACTIVE' WHERE id=?", MERCHANT_ID);
            assertEquals("SIGNED", api.consent(command).signingStatus());
        }
    }

    @Test
    void nonOwnerAndStaffCannotReadOrSignOwnerAgreement() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));

            assertEquals(CommonApiCodes.NOT_FOUND, failure(() -> api.getAgreement(new MerchantAgreementQuery(
                    Long.toString(MERCHANT_ID), ownerQuery(OTHER_USER_ID)))).code());
            assertEquals(CommonApiCodes.FORBIDDEN, failure(() -> api.getAgreement(new MerchantAgreementQuery(
                    Long.toString(MERCHANT_ID), new QueryContext("staff", OperatorType.MERCHANT_STAFF,
                            Long.toString(STAFF_USER_ID))))).code());
            assertEquals(CommonApiCodes.NOT_FOUND, failure(() -> api.consent(consent(
                    UUID.randomUUID().toString(), OTHER_USER_ID, AGREEMENT_V1, hash, true))).code());
            assertEquals(CommonApiCodes.FORBIDDEN, failure(() -> api.consent(new MerchantAgreementConsentCommand(
                    Long.toString(MERCHANT_ID), AGREEMENT_V1, hash, true,
                    new CommandContext(UUID.randomUUID().toString(), "staff", OperatorType.MERCHANT_STAFF,
                            Long.toString(STAFF_USER_ID), "MERCHANT_APP")))) .code());
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
        }
    }

    @Test
    void contentVersionAndAcceptedValidationFailWithoutWrites() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            String req = UUID.randomUUID().toString();

            assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure(() -> api.consent(consent(
                    req, OWNER_ID, "", hash, true))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure(() -> api.consent(consent(
                    UUID.randomUUID().toString(), OWNER_ID, "商家-v1", hash, true))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure(() -> api.consent(consent(
                    UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, "not-a-hash", true))).code());
            assertEquals(CommonApiCodes.CONFLICT, failure(() -> api.consent(consent(
                    UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, "0".repeat(64), true))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure(() -> api.consent(consent(
                    UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, hash, false))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure(() -> api.consent(consent(
                    UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, hash, null))).code());
            assertEquals(CommonApiCodes.CONFLICT, failure(() -> api.consent(consent(
                    UUID.randomUUID().toString(), OWNER_ID, "merchant-unknown", hash, true))).code());
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
        }
    }

    @Test
    void signedOldVersionRemainsReadableAfterCurrentPointerChanges() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hashV1 = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            String hashV2 = db.seedAgreementVersion(AGREEMENT_V2_ID, AGREEMENT_V2, CONTENT_V2, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            MerchantAgreementConsentDTO signed = api.consent(consent(
                    UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, hashV1, true));
            db.moveCurrent(AGREEMENT_V2_ID, 1);

            MerchantAgreementDTO read = api.getAgreement(new MerchantAgreementQuery(
                    Long.toString(MERCHANT_ID), ownerQuery(OWNER_ID)));
            assertEquals(AGREEMENT_V1, read.agreementVersion());
            assertEquals(CONTENT_V1, read.content());
            assertEquals(hashV1, read.contentSha256());
            assertEquals(AGREEMENT_V1, read.acceptedVersion());
            assertEquals(signed.acceptedAt().toInstant(), read.acceptedAt().toInstant());
            assertEquals(signed, api.consent(consent(
                    UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, hashV1, true)));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance WHERE merchant_id=?",
                    Integer.class, MERCHANT_ID));
        }
    }

    @Test
    void firstConsentOfOldVersionAfterPublishSwitchIsRejected() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hashV1 = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            String hashV2 = db.seedAgreementVersion(AGREEMENT_V2_ID, AGREEMENT_V2, CONTENT_V2, OWNER_ID);
            db.seedCurrent(AGREEMENT_V2_ID, 1);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));

            assertEquals(CommonApiCodes.CONFLICT, failure(() -> api.consent(consent(
                    UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, hashV1, true))).code());
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
            assertEquals(hashV2, db.jdbc().queryForObject(
                    "SELECT content_sha256 FROM merchant_agreement_version WHERE id=?",
                    String.class, AGREEMENT_V2_ID));
        }
    }

    @Test
    void sameRequestIdReplaysStableReceiptAndDifferentParamsConflict() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            String requestId = UUID.randomUUID().toString();
            MerchantAgreementConsentCommand first = consent(requestId, OWNER_ID, AGREEMENT_V1, hash, true);
            MerchantAgreementConsentDTO receipt = api.consent(first);
            MerchantAgreementConsentDTO replay = api.consent(first);
            assertEquals(receipt, replay);
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, failure(() -> api.consent(
                    consent(requestId, OWNER_ID, AGREEMENT_V1, "f".repeat(64), true))).code());
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_command_idempotency WHERE status='SUCCEEDED'",
                    Integer.class));
        }
    }

    @Test
    void successfulReplayDoesNotRequestAnotherIdFromAFailedProvider() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            AtomicInteger calls = new AtomicInteger();
            SnowflakeIdGenerator ids = () -> {
                int call = calls.incrementAndGet();
                if (call > 2) throw new IllegalStateException("ID provider intentionally unavailable");
                return 9_300_000_000_000_000L + call;
            };
            var api = api(db.dataSource(), ids,
                    id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            String requestId = UUID.randomUUID().toString();
            MerchantAgreementConsentDTO first = api.consent(consent(
                    requestId, OWNER_ID, AGREEMENT_V1, hash, true));
            assertEquals(first, api.consent(consent(requestId, OWNER_ID, AGREEMENT_V1, hash, true)));
            assertEquals(2, calls.get(), "replay reads the stored receipt before requesting another ID");
        }
    }

    @Test
    void corruptedBindingDigestCanonicalVersionOrReceiptFailsClosed() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            String requestId = UUID.randomUUID().toString();
            MerchantAgreementConsentCommand command = consent(requestId, OWNER_ID, AGREEMENT_V1, hash, true);
            api.consent(command);
            byte[] key = db.jdbc().queryForObject(
                    "SELECT request_key FROM merchant_command_idempotency", byte[].class);

            db.jdbc().update("UPDATE merchant_command_idempotency SET canonical_version='corrupt' WHERE request_key=?", key);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.consent(command)).code());
        }
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            String requestId = UUID.randomUUID().toString();
            MerchantAgreementConsentCommand command = consent(requestId, OWNER_ID, AGREEMENT_V1, hash, true);
            api.consent(command);
            byte[] key = db.jdbc().queryForObject(
                    "SELECT request_key FROM merchant_command_idempotency", byte[].class);
            db.jdbc().update("UPDATE merchant_command_idempotency SET params_sha256=? WHERE request_key=?",
                    "f".repeat(64), key);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.consent(command)).code());
        }
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            String requestId = UUID.randomUUID().toString();
            MerchantAgreementConsentCommand command = consent(requestId, OWNER_ID, AGREEMENT_V1, hash, true);
            api.consent(command);
            byte[] key = db.jdbc().queryForObject(
                    "SELECT request_key FROM merchant_command_idempotency", byte[].class);
            db.jdbc().update("UPDATE merchant_command_idempotency SET receipt_json=? WHERE request_key=?",
                    "{\"merchantId\":\"" + MERCHANT_ID + "\",\"agreementVersion\":\"merchant-v9\","
                            + "\"acceptedAt\":\"2026-09-17T08:00:00Z\",\"signingStatus\":\"SIGNED\"}", key);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.consent(command)).code());
        }
    }

    @Test
    void applicationReaderErrorsAreDependencyFailuresWithoutSourceLeakage() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            for (String code : new String[] {CommonApiCodes.INVALID_ARGUMENT, CommonApiCodes.NOT_FOUND}) {
                var api = api(db, id -> {
                    throw new ApiException(code, "secret application source detail");
                });
                ApiException error = failure(() -> api.consent(consent(
                        UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, hash, true)));
                assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, error.code());
                assertFalse(error.getMessage().contains("secret"));
            }
            var unknown = api(db, id -> new ApplicationReviewFactsReader.Facts("UNKNOWN"));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> unknown.consent(consent(
                    UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, hash, true))).code());
        }
    }

    @Test
    void markSucceededFailureRollsBackAcceptanceButRetainsReservedBinding() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            String requestId = UUID.randomUUID().toString();
            MerchantAgreementConsentCommand command = consent(requestId, OWNER_ID, AGREEMENT_V1, hash, true);
            db.jdbc().execute("""
                    CREATE TRIGGER mer_qa_fail_receipt BEFORE UPDATE ON merchant_command_idempotency
                    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='qa mark receipt failure'
                    """);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.consent(command)).code());
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_command_idempotency WHERE status='RESERVED'", Integer.class));
            db.jdbc().execute("DROP TRIGGER mer_qa_fail_receipt");
            assertEquals("SIGNED", api.consent(command).signingStatus());
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
        }
    }

    @Test
    void commitAcknowledgementLossRecoversTheOriginalReceiptWithoutDuplicateAcceptance() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            CommitAckLossDataSource source = new CommitAckLossDataSource(db.dataSource(), 3);
            var api = api(source, new AtomicLong(9_400_000_000_000_000L)::incrementAndGet,
                    id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            MerchantAgreementConsentDTO receipt = api.consent(consent(
                    UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, hash, true));
            assertEquals("SIGNED", receipt.signingStatus());
            assertTrue(source.commitAcknowledgementWasLost());
            assertEquals(3, source.lostCommitAt(), "commit #3 is execution after read and admission");
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_command_idempotency WHERE status='SUCCEEDED'",
                    Integer.class));
        }
    }

    @Test
    void consentRejectsAnOuterTransactionBeforeAnyBindingOrAcceptance() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            TransactionTemplate outer = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
            assertThrows(IllegalStateException.class, () -> outer.execute(status -> {
                api.consent(consent(UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, hash, true));
                return null;
            }));
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_command_idempotency", Integer.class));
        }
    }

    @Test
    void exactRequestKeyBytesAndNewKeySameVersionDoNotCreateSecondAcceptance() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            String upper = "ABCDEFAB-ABCD-4ABC-8ABC-ABCDEFABCDEF";
            String lower = upper.toLowerCase(java.util.Locale.ROOT);
            MerchantAgreementConsentDTO first = api.consent(consent(upper, OWNER_ID, AGREEMENT_V1, hash, true));
            MerchantAgreementConsentDTO second = api.consent(consent(lower, OWNER_ID, AGREEMENT_V1, hash, true));
            assertEquals(first, second);
            String longRequestId = "x".repeat(512);
            assertEquals(first, api.consent(new MerchantAgreementConsentCommand(
                    Long.toString(MERCHANT_ID), AGREEMENT_V1, hash, true,
                    commandWithTrace(longRequestId, "trace-long", OWNER_ID))));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
            assertEquals(3, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_command_idempotency WHERE status='SUCCEEDED'",
                    Integer.class));
        }
    }

    @Test
    void failedAdmissionBindingRetainsConflictAndNewKeyCanRetry() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            AtomicReference<String> application = new AtomicReference<>("REVIEWING");
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts(application.get()));
            String requestId = UUID.randomUUID().toString();
            assertEquals(CommonApiCodes.CONFLICT, failure(() -> api.consent(
                    consent(requestId, OWNER_ID, AGREEMENT_V1, hash, true))).code());
            application.set("APPROVED");
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, failure(() -> api.consent(
                    consent(requestId, OWNER_ID, AGREEMENT_V1, "f".repeat(64), true))).code());
            MerchantAgreementConsentDTO retry = api.consent(consent(
                    UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, hash, true));
            assertEquals("SIGNED", retry.signingStatus());
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_command_idempotency WHERE status='SUCCEEDED'",
                    Integer.class));
        }
    }

    @Test
    void concurrentDifferentKeysProduceOneAcceptanceAndStableTime() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<MerchantAgreementConsentDTO> first = pool.submit(() -> {
                start.await();
                return api.consent(consent(UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, hash, true));
            });
            Future<MerchantAgreementConsentDTO> second = pool.submit(() -> {
                start.await();
                return api.consent(consent(UUID.randomUUID().toString(), OWNER_ID, AGREEMENT_V1, hash, true));
            });
            start.countDown();
            MerchantAgreementConsentDTO one = await(first);
            MerchantAgreementConsentDTO two = await(second);
            pool.shutdownNow();
            assertEquals(one, two);
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
            assertEquals(2, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_command_idempotency", Integer.class));
        }
    }

    @Test
    void concurrentSameRequestIdRechecksSucceededBindingAndReadsApprovalOnce() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            AtomicInteger approvals = new AtomicInteger();
            var api = api(db, id -> {
                approvals.incrementAndGet();
                return new ApplicationReviewFactsReader.Facts("APPROVED");
            });
            String requestId = UUID.randomUUID().toString();
            MerchantAgreementConsentCommand command = consent(requestId, OWNER_ID, AGREEMENT_V1, hash, true);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<MerchantAgreementConsentDTO> first = pool.submit(() -> {
                start.await();
                return api.consent(command);
            });
            Future<MerchantAgreementConsentDTO> second = pool.submit(() -> {
                start.await();
                return api.consent(command);
            });
            start.countDown();
            MerchantAgreementConsentDTO one = await(first);
            MerchantAgreementConsentDTO two = await(second);
            pool.shutdownNow();
            assertEquals(one, two);
            assertEquals(1, approvals.get(), "only the RESERVED owner reads approval facts");
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_command_idempotency", Integer.class));
        }
    }

    @Test
    void damagedCurrentOrAcceptanceFactsFailClosed() throws Exception {
        try (var db = new MySqlMerchantAgreementTestDatabase()) {
            db.seedMerchant(MERCHANT_ID, OWNER_ID, "ACTIVE");
            String hash = db.seedAgreementVersion(AGREEMENT_V1_ID, AGREEMENT_V1, CONTENT_V1, OWNER_ID);
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            var api = api(db, id -> new ApplicationReviewFactsReader.Facts("APPROVED"));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                    db.seedAcceptance(9_100_000_000_000_003L, MERCHANT_ID, 9_100_000_000_000_099L,
                            OWNER_ID, java.time.LocalDateTime.of(2026, 9, 17, 8, 0), hash));
            db.jdbc().update("DELETE FROM merchant_agreement_current WHERE agreement_key='MERCHANT'");
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.getAgreement(
                    new MerchantAgreementQuery(Long.toString(MERCHANT_ID), ownerQuery(OWNER_ID)))).code());
            db.seedCurrent(AGREEMENT_V1_ID, 0);
            db.jdbc().update("UPDATE merchant_agreement_version SET content='changed' WHERE id=?", AGREEMENT_V1_ID);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.getAgreement(
                    new MerchantAgreementQuery(Long.toString(MERCHANT_ID), ownerQuery(OWNER_ID)))).code());
            db.jdbc().update("UPDATE merchant_agreement_version SET content=? WHERE id=?", CONTENT_V1, AGREEMENT_V1_ID);
            db.seedAcceptance(AGREEMENT_V2_ID, MERCHANT_ID, AGREEMENT_V1_ID, OWNER_ID,
                    java.time.LocalDateTime.of(2026, 9, 17, 8, 0), hash);
            db.jdbc().update("UPDATE merchant_agreement_version SET content='corrupt' WHERE id=?", AGREEMENT_V1_ID);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure(() -> api.getAgreement(
                    new MerchantAgreementQuery(Long.toString(MERCHANT_ID), ownerQuery(OWNER_ID)))).code());
        }
    }

    private static MerchantAgreementConsentDTO await(Future<MerchantAgreementConsentDTO> future)
            throws InterruptedException, ExecutionException {
        return future.get();
    }

    private static CommandContext commandWithTrace(String requestId, String traceId, long userId) {
        return new CommandContext(requestId, traceId, OperatorType.USER,
                Long.toString(userId), "MERCHANT_APP");
    }

    /** Real MySQL commit succeeds; only the caller's acknowledgement is lost once. */
    private static final class CommitAckLossDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final int loseCommitNumber;
        private final AtomicInteger commits = new AtomicInteger();
        private volatile boolean lost;
        private volatile int lostAt;

        CommitAckLossDataSource(DataSource delegate, int loseCommitNumber) {
            this.delegate = delegate;
            this.loseCommitNumber = loseCommitNumber;
        }

        boolean commitAcknowledgementWasLost() { return lost; }
        int lostCommitAt() { return lostAt; }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return wrap(delegate.getConnection(user, password));
        }

        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(connection, args);
                            if ("commit".equals(method.getName())
                                    && commits.incrementAndGet() == loseCommitNumber && !lost) {
                                lost = true;
                                lostAt = commits.get();
                                throw new SQLException("TEST: commit succeeded but acknowledgement was lost", "08006");
                            }
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
