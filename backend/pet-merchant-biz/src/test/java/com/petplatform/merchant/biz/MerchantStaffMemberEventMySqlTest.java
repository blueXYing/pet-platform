package com.petplatform.merchant.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.merchant.api.command.CancelStaffMemberInvitationCommand;
import com.petplatform.merchant.api.command.ConfirmStaffMemberInvitationCommand;
import com.petplatform.merchant.api.command.GrantStaffMemberActionsCommand;
import com.petplatform.merchant.api.command.InviteStaffMemberCommand;
import com.petplatform.merchant.api.command.StaffMemberLifecycleCommand;
import com.petplatform.merchant.biz.apiimpl.MerchantStaffMemberApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Contract 54 NTF slice producer acceptance over isolated real MySQL: the lifecycle outbox events
 * are appended inside the binding command transaction (rollback leaves nothing, a 23号 replay never
 * emits a second event), the payload carries only the pre-masked phone and pinned fields, and a
 * missing publisher (pet.outbox.enabled=false default) keeps the commands byte-identical with zero
 * events. Consumer-side semantics live in pet-notification-biz tests.
 */
class MerchantStaffMemberEventMySqlTest {
    private static final long MERCHANT = 9_100_000_000_000_201L;
    private static final long STORE = 9_100_000_000_000_202L;
    private static final long STORE2 = 9_100_000_000_000_203L;
    private static final long OWNER = 9_100_000_000_000_210L;
    private static final long USER = 9_100_000_000_000_211L;
    private static final String PHONE = "13900004444";
    private static final String OTHER_PHONE = "13900005555";
    private static final String VERIFY = "merchant.order.verify";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T09:00:00Z"), ZoneOffset.UTC);

    private final AtomicLong ids = new AtomicLong(9_300_000_000_000_300L);

    private static String id(long value) { return Long.toString(value); }
    private static CommandContext command(String requestId, long user) {
        return new CommandContext(requestId, "test", OperatorType.USER, id(user), "MINIAPP");
    }

    private MerchantStaffMemberApiImpl api(StaffBindingMySqlTestDatabase db,
            TransactionalOutboxPublisher publisher) {
        ScheduleCapacityGuardApi guard = Mockito.mock(ScheduleCapacityGuardApi.class);
        return new MerchantStaffMemberApiImpl(db.dataSource(), ids::incrementAndGet,
                (ApplicationReviewFactsReader) merchantId ->
                        new ApplicationReviewFactsReader.Facts("APPROVED"),
                new AesGcmProtectedValueProvider("staff-event-test-v1", key((byte) 5), key((byte) 6)),
                (userId, phone) -> userId == USER && PHONE.equals(phone), guard, CLOCK, publisher);
    }

    private static byte[] key(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    private static void seed(StaffBindingMySqlTestDatabase db) {
        var jdbc = db.jdbc();
        jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,version,created_at,updated_at) VALUES(?,?,?,'ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                MERCHANT, OWNER, "事件测试商家");
        jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                STORE, MERCHANT, "事件门店", "地址");
        jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                STORE2, MERCHANT, "事件门店二", "地址二");
        jdbc.update("INSERT INTO merchant_application(id,owner_user_id,reserved_merchant_id,status,subject_verification_status,version,created_at,updated_at) VALUES(?,?,?,'DRAFT','NOT_STARTED',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                2201L, OWNER, MERCHANT);
        String content = "事件测试协议";
        String hash = MySqlMerchantAgreementTestDatabase.sha256(content);
        jdbc.update("INSERT INTO merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
                2202L, "staff-event-test", content, hash, OWNER);
        jdbc.update("INSERT INTO merchant_agreement_acceptance(id,merchant_id,agreement_version_id,accepted_by_user_id,accepted_at,content_sha256) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
                2203L, MERCHANT, 2202L, OWNER, hash);
    }

    private String invite(MerchantStaffMemberApiImpl api, String requestId, String phone) {
        return api.inviteMember(new InviteStaffMemberCommand(id(MERCHANT), id(STORE), phone,
                "王小明", List.of(VERIFY), command(requestId, OWNER)))
                .invitation().invitationId();
    }

    private String cancel(MerchantStaffMemberApiImpl api, String requestId, String invitationId,
            String version) {
        return api.cancelInvitation(new CancelStaffMemberInvitationCommand(id(MERCHANT), id(STORE),
                invitationId, version, command(requestId, OWNER)))
                .invitation().invitationId();
    }

    private int outboxCount(StaffBindingMySqlTestDatabase db, String eventType) {
        return db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM integration_event_outbox WHERE event_type=?",
                Integer.class, eventType);
    }

    private Map<String, Object> payloadOf(StaffBindingMySqlTestDatabase db, String eventType,
            long aggregateId, String changeType) throws Exception {
        String json = db.jdbc().queryForObject(
                "SELECT CAST(payload AS CHAR) FROM integration_event_outbox WHERE event_type=?"
                        + " AND aggregate_id=? AND payload->>'$.changeType'=?",
                String.class, eventType, aggregateId, changeType);
        return new ObjectMapper().readValue(json, Map.class);
    }

    /** Grant events share the member aggregate, so the store distinguishes the payloads. */
    private Map<String, Object> grantPayloadOf(StaffBindingMySqlTestDatabase db, long aggregateId,
            String changeType, long storeId) throws Exception {
        String json = db.jdbc().queryForObject(
                "SELECT CAST(payload AS CHAR) FROM integration_event_outbox"
                        + " WHERE event_type='MerchantStaffGrantLifecycleEvent.v1'"
                        + " AND aggregate_id=? AND payload->>'$.changeType'=? AND payload->>'$.storeId'=?",
                String.class, aggregateId, changeType, Long.toUnsignedString(storeId));
        return new ObjectMapper().readValue(json, Map.class);
    }

    @Test
    void invitationLifecycleEventsCommitWithTheCommandAndReplayNeverDuplicates() throws Exception {
        try (var db = new StaffBindingMySqlTestDatabase()) {
            seed(db);
            var publisher = new TransactionalOutboxPublisher(db.dataSource(),
                    ids::incrementAndGet, new ObjectMapper());
            var api = api(db, publisher);
            String invitationId = invite(api, "inv-1", PHONE);
            assertEquals(1, outboxCount(db, "MerchantStaffInvitationLifecycleEvent.v1"));
            Map<String, Object> payload = payloadOf(db,
                    "MerchantStaffInvitationLifecycleEvent.v1", Long.parseLong(invitationId),
                    "INVITED");
            assertEquals(id(OWNER), payload.get("ownerUserId"));
            assertEquals("王小明", payload.get("memberName"));
            assertEquals("139****4444", payload.get("phoneMasked"));
            assertEquals(invitationId, payload.get("invitationId"));
            assertEquals("2026-10-06T09:00:00.000Z", payload.get("occurredAt"));
            String json = db.jdbc().queryForObject(
                    "SELECT CAST(payload AS CHAR) FROM integration_event_outbox WHERE event_type='MerchantStaffInvitationLifecycleEvent.v1'",
                    String.class);
            assertFalse(json.contains(PHONE)); // plaintext phone never leaves the module
            // 23号 replay: the receipt is served from the idempotency binding, no second event.
            invite(api, "inv-1", PHONE);
            assertEquals(1, outboxCount(db, "MerchantStaffInvitationLifecycleEvent.v1"));

            // Canceled: terminal cancel appends its own event with the same reachability shape.
            cancel(api, "cancel-1", invitationId, "0");
            assertEquals(2, outboxCount(db, "MerchantStaffInvitationLifecycleEvent.v1"));
            Map<String, Object> canceled = payloadOf(db,
                    "MerchantStaffInvitationLifecycleEvent.v1", Long.parseLong(invitationId),
                    "CANCELED");
            assertEquals(id(OWNER), canceled.get("ownerUserId"));
            assertNull(canceled.get("confirmedUserId"));
            assertNull(canceled.get("memberId"));
        }
    }

    @Test
    void confirmEmitsOneInvitationEventCarryingBothReceivers() throws Exception {
        try (var db = new StaffBindingMySqlTestDatabase()) {
            seed(db);
            var publisher = new TransactionalOutboxPublisher(db.dataSource(),
                    ids::incrementAndGet, new ObjectMapper());
            var api = api(db, publisher);
            String invitationId = invite(api, "inv-1", PHONE);
            var confirmed = api.confirmInvitation(new ConfirmStaffMemberInvitationCommand(
                    invitationId, command("cf-1", USER)));
            assertTrue(confirmed.created());
            Map<String, Object> payload = payloadOf(db,
                    "MerchantStaffInvitationLifecycleEvent.v1", Long.parseLong(invitationId),
                    "CONFIRMED");
            assertEquals(id(USER), payload.get("confirmedUserId"));
            assertEquals(confirmed.member().memberId(), payload.get("memberId"));
            assertEquals(id(OWNER), payload.get("ownerUserId"));
            // Replay serves the stored receipt, not a second event.
            api.confirmInvitation(new ConfirmStaffMemberInvitationCommand(invitationId,
                    command("cf-1", USER)));
            assertEquals(2, outboxCount(db, "MerchantStaffInvitationLifecycleEvent.v1"));
        }
    }

    @Test
    void memberDisableEnableEmitMemberEventsAndConflictsLeaveNothing() throws Exception {
        try (var db = new StaffBindingMySqlTestDatabase()) {
            seed(db);
            var publisher = new TransactionalOutboxPublisher(db.dataSource(),
                    ids::incrementAndGet, new ObjectMapper());
            var api = api(db, publisher);
            String invitationId = invite(api, "inv-1", PHONE);
            String memberId = api.confirmInvitation(new ConfirmStaffMemberInvitationCommand(
                    invitationId, command("cf-1", USER))).member().memberId();
            assertEquals(0, outboxCount(db, "MerchantStaffMemberLifecycleEvent.v1"));
            api.disableMember(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE), memberId,
                    "0", command("dis-1", OWNER)));
            assertEquals(1, outboxCount(db, "MerchantStaffMemberLifecycleEvent.v1"));
            Map<String, Object> payload = payloadOf(db, "MerchantStaffMemberLifecycleEvent.v1",
                    Long.parseLong(memberId), "DISABLED");
            assertEquals("DISABLED", payload.get("changeType"));
            assertEquals(id(USER), payload.get("memberUserId"));
            assertEquals(memberId, payload.get("memberId"));
            // A conflicting second disable rolls back before any event is appended.
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> api.disableMember(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE),
                            memberId, "0", command("dis-2", OWNER)))).code());
            assertEquals(1, outboxCount(db, "MerchantStaffMemberLifecycleEvent.v1"));
            api.enableMember(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE), memberId,
                    "1", command("en-1", OWNER)));
            assertEquals(2, outboxCount(db, "MerchantStaffMemberLifecycleEvent.v1"));
        }
    }

    @Test
    void grantReplaceAndRevokeStoreEmitGrantEventsWithActionSummary() throws Exception {
        try (var db = new StaffBindingMySqlTestDatabase()) {
            seed(db);
            var publisher = new TransactionalOutboxPublisher(db.dataSource(),
                    ids::incrementAndGet, new ObjectMapper());
            var api = api(db, publisher);
            String invitationId = invite(api, "inv-1", PHONE);
            long memberId = Long.parseLong(api.confirmInvitation(new ConfirmStaffMemberInvitationCommand(
                    invitationId, command("cf-1", USER))).member().memberId());
            assertEquals(0, outboxCount(db, "MerchantStaffGrantLifecycleEvent.v1"));
            // Whole-set replacement on the confirmed store grant rides the same transaction.
            api.grantActions(new GrantStaffMemberActionsCommand(id(MERCHANT), id(STORE),
                    Long.toUnsignedString(memberId), List.of(VERIFY), "0", command("g-1", OWNER)));
            assertEquals(1, outboxCount(db, "MerchantStaffGrantLifecycleEvent.v1"));
            Map<String, Object> replaced = grantPayloadOf(db, memberId, "GRANTED", STORE);
            assertEquals(List.of(VERIFY), replaced.get("actions"));
            assertEquals(id(USER), replaced.get("memberUserId"));
            assertEquals(id(STORE), replaced.get("storeId"));
            assertEquals("GRANTED", replaced.get("changeType"));
            // 23号 replay serves the stored receipt, never a second event.
            api.grantActions(new GrantStaffMemberActionsCommand(id(MERCHANT), id(STORE),
                    Long.toUnsignedString(memberId), List.of(VERIFY), "0", command("g-1", OWNER)));
            assertEquals(1, outboxCount(db, "MerchantStaffGrantLifecycleEvent.v1"));
            // First store grant (multi-store member, grant v0 path) emits its own GRANTED event.
            api.grantActions(new GrantStaffMemberActionsCommand(id(MERCHANT), id(STORE2),
                    Long.toUnsignedString(memberId), List.of(VERIFY), "0", command("g-2", OWNER)));
            assertEquals(2, outboxCount(db, "MerchantStaffGrantLifecycleEvent.v1"));
            assertEquals(List.of(VERIFY), grantPayloadOf(db, memberId, "GRANTED", STORE2).get("actions"));
            // Store-wide revoke is terminal and carries the withdrawn action set.
            api.revokeStoreGrant(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE),
                    Long.toUnsignedString(memberId), "1", command("r-1", OWNER)));
            assertEquals(3, outboxCount(db, "MerchantStaffGrantLifecycleEvent.v1"));
            Map<String, Object> revoked = grantPayloadOf(db, memberId, "REVOKED", STORE);
            assertEquals(List.of(VERIFY), revoked.get("actions"));
            assertEquals(id(USER), revoked.get("memberUserId"));
            String json = db.jdbc().queryForObject(
                    "SELECT CAST(payload AS CHAR) FROM integration_event_outbox"
                            + " WHERE event_type='MerchantStaffGrantLifecycleEvent.v1'"
                            + " AND payload->>'$.changeType'='REVOKED'",
                    String.class);
            assertFalse(json.contains(PHONE)); // grant payloads never carry name or phone
            assertFalse(json.contains("王小明"));
            // Conflicts after the terminal revoke roll back before any event is appended.
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> api.grantActions(new GrantStaffMemberActionsCommand(id(MERCHANT), id(STORE),
                            Long.toUnsignedString(memberId), List.of(VERIFY), "2",
                            command("g-3", OWNER)))).code());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> api.revokeStoreGrant(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE),
                            Long.toUnsignedString(memberId), "2", command("r-2", OWNER)))).code());
            assertEquals(3, outboxCount(db, "MerchantStaffGrantLifecycleEvent.v1"));
        }
    }

    @Test
    void missingPublisherKeepsContract54BehaviorWithZeroEvents() throws Exception {
        try (var db = new StaffBindingMySqlTestDatabase()) {
            seed(db);
            var api = api(db, null);
            String invitationId = invite(api, "inv-1", PHONE);
            String memberId = api.confirmInvitation(new ConfirmStaffMemberInvitationCommand(
                    invitationId, command("cf-1", USER))).member().memberId();
            api.disableMember(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE), memberId,
                    "0", command("dis-1", OWNER)));
            cancel(api, "cancel-1", invite(api, "inv-2", OTHER_PHONE), "0");
            api.grantActions(new GrantStaffMemberActionsCommand(id(MERCHANT), id(STORE2),
                    memberId, List.of(VERIFY), "0", command("g-1", OWNER)));
            api.revokeStoreGrant(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE),
                    memberId, "0", command("r-1", OWNER)));
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM integration_event_outbox", Integer.class));
            assertEquals(2, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_member_invitation", Integer.class));
            assertEquals("DISABLED", db.jdbc().queryForObject(
                    "SELECT status FROM merchant_member WHERE id=" + memberId, String.class));
        }
    }
}
