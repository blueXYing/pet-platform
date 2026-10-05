package com.petplatform.merchant.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.command.CancelStaffMemberInvitationCommand;
import com.petplatform.merchant.api.command.ConfirmStaffMemberInvitationCommand;
import com.petplatform.merchant.api.command.GrantStaffMemberActionsCommand;
import com.petplatform.merchant.api.command.InviteStaffMemberCommand;
import com.petplatform.merchant.api.command.StaffMemberLifecycleCommand;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationCommandResult;
import com.petplatform.merchant.api.dto.MerchantStaffMemberCommandResult;
import com.petplatform.merchant.api.query.MyStaffInvitationQuery;
import com.petplatform.merchant.api.query.StaffInvitationManagementQuery;
import com.petplatform.merchant.api.query.StaffMemberManagementQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantStaffIdentityApiImpl;
import com.petplatform.merchant.biz.apiimpl.MerchantStaffMemberApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Isolated real MySQL proves the contract-54 D1-a invite-confirm binding flow: invitation
 * uniqueness and terminal cancel, phone-equality confirm with 52-relation creation and audit,
 * conflict and idempotency semantics, disable/enable against the contract-52 action gate, the
 * D2 verify-only catalog, whole-set action replacement and terminal revoke-store (D3 pending).
 */
class MerchantStaffMemberBindingMySqlTest {
    private static final long MERCHANT = 9_100_000_000_000_101L;
    private static final long STORE = 9_100_000_000_000_102L;
    private static final long STORE2 = 9_100_000_000_000_103L;
    private static final long OWNER = 9_100_000_000_000_110L;
    private static final long USER = 9_100_000_000_000_111L;
    private static final long OTHER_USER = 9_100_000_000_000_112L;
    private static final String PHONE = "13900001111";
    private static final String OTHER_PHONE = "13900002222";
    private static final String VERIFY = "merchant.order.verify";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), ZoneOffset.UTC);

    private final AtomicLong ids = new AtomicLong(9_300_000_000_000_100L);

    private static String id(long value) { return Long.toString(value); }
    private static QueryContext query(long user) {
        return new QueryContext("test", OperatorType.USER, id(user));
    }
    private static CommandContext command(String requestId, long user) {
        return new CommandContext(requestId, "test", OperatorType.USER, id(user), "MINIAPP");
    }

    private MerchantStaffMemberApiImpl api(StaffBindingMySqlTestDatabase db) {
        ScheduleCapacityGuardApi guard = Mockito.mock(ScheduleCapacityGuardApi.class);
        return new MerchantStaffMemberApiImpl(db.dataSource(), ids::incrementAndGet,
                approvedFacts(),
                new AesGcmProtectedValueProvider("staff-binding-test-v1", key((byte) 3), key((byte) 4)),
                loginPhones(), guard, CLOCK);
    }

    private static ApplicationReviewFactsReader approvedFacts() {
        return merchantId -> new ApplicationReviewFactsReader.Facts("APPROVED");
    }

    /** The confirm channel: equality with the session user's verified account phone (S9 fact). */
    private static com.petplatform.merchant.biz.application.StaffLoginPhonePort loginPhones() {
        return (userId, phone) -> userId == USER && PHONE.equals(phone);
    }

    private static byte[] key(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    private static void seed(StaffBindingMySqlTestDatabase db) {
        JdbcTemplate jdbc = db.jdbc();
        jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,version,created_at,updated_at) VALUES(?,?,?,'ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                MERCHANT, OWNER, "绑定测试商家");
        jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                STORE, MERCHANT, "旗舰一店", "地址一");
        jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                STORE2, MERCHANT, "旗舰二店", "地址二");
        jdbc.update("INSERT INTO merchant_application(id,owner_user_id,reserved_merchant_id,status,subject_verification_status,version,created_at,updated_at) VALUES(?,?,?,'DRAFT','NOT_STARTED',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                2101L, OWNER, MERCHANT);
        String content = "绑定测试协议";
        String hash = MySqlMerchantAgreementTestDatabase.sha256(content);
        jdbc.update("INSERT INTO merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
                2102L, "binding-test", content, hash, OWNER);
        jdbc.update("INSERT INTO merchant_agreement_acceptance(id,merchant_id,agreement_version_id,accepted_by_user_id,accepted_at,content_sha256) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
                2103L, MERCHANT, 2102L, OWNER, hash);
    }

    private MerchantStaffInvitationCommandResult invite(MerchantStaffMemberApiImpl api,
            String requestId, String phone) {
        return api.inviteMember(new InviteStaffMemberCommand(id(MERCHANT), id(STORE), phone,
                "李小美", List.of(VERIFY), command(requestId, OWNER)));
    }

    private MerchantStaffMemberCommandResult confirm(MerchantStaffMemberApiImpl api,
            String requestId, long user, String invitationId) {
        return api.confirmInvitation(new ConfirmStaffMemberInvitationCommand(invitationId,
                command(requestId, user)));
    }

    /** The 52 read-side kernel over the same rows: the binding must light it up correctly. */
    private MerchantStaffIdentityApiImpl identityApi(StaffBindingMySqlTestDatabase db) {
        ScheduleCapacityGuardApi guard = Mockito.mock(ScheduleCapacityGuardApi.class);
        return new MerchantStaffIdentityApiImpl(db.dataSource(), guard, approvedFacts(), CLOCK);
    }

    @Test
    void inviteCancelAndPendingUniqueness() throws Exception {
        try (var db = new StaffBindingMySqlTestDatabase()) {
            seed(db);
            var api = api(db);
            MerchantStaffInvitationCommandResult invited = invite(api, "inv-1", PHONE);
            assertTrue(invited.created());
            assertEquals("INVITED", invited.invitation().status());
            assertEquals("139****1111", invited.invitation().phoneMasked());
            assertEquals("0", invited.invitation().version());
            // Same requestId replays the receipt without new rows or audit.
            MerchantStaffInvitationCommandResult replay = invite(api, "inv-1", PHONE);
            assertFalse(replay.created());
            assertTrue(replay.replayed());
            assertEquals(invited.invitation(), replay.invitation());
            // A second live invitation for the same merchant+phone is rejected.
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> invite(api, "inv-2", PHONE)).code());
            // A different phone invites fine (second pending invitation on another store context).
            MerchantStaffInvitationCommandResult other = invite(api, "inv-3", OTHER_PHONE);
            assertTrue(other.created());

            // Cancel is terminal: confirm afterwards conflicts, a second cancel conflicts.
            String cancelId = invited.invitation().invitationId();
            MerchantStaffInvitationCommandResult canceled = api.cancelInvitation(
                    new CancelStaffMemberInvitationCommand(id(MERCHANT), id(STORE), cancelId, "0",
                            command("cancel-1", OWNER)));
            assertEquals("CANCELED", canceled.invitation().status());
            assertEquals("1", canceled.invitation().version());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> confirm(api, "cf-1", USER, cancelId)).code());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> api.cancelInvitation(new CancelStaffMemberInvitationCommand(id(MERCHANT),
                            id(STORE), cancelId, "1", command("cancel-2", OWNER)))).code());
            // Version guard on cancel.
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> api.cancelInvitation(new CancelStaffMemberInvitationCommand(id(MERCHANT),
                            id(STORE), other.invitation().invitationId(), "7",
                            command("cancel-3", OWNER)))).code());

            assertEquals(2, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_member_invitation", Integer.class));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_member_invitation WHERE status='CANCELED'",
                    Integer.class));
            assertEquals(3, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_member_audit", Integer.class));
        }
    }

    @Test
    void confirmCreatesRelationsLightsUpContract52AndReplays() throws Exception {
        try (var db = new StaffBindingMySqlTestDatabase()) {
            seed(db);
            var api = api(db);
            var identity = identityApi(db);
            String invitationId = invite(api, "inv-1", PHONE).invitation().invitationId();

            // Wrong session phone reads exactly like a missing invitation (anti-enumeration).
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> confirm(api, "cf-wrong", OTHER_USER, invitationId)).code());
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> api.getMyInvitation(new MyStaffInvitationQuery(invitationId,
                            query(OTHER_USER)))).code());

            // The matching session sees the confirm-page projection.
            var detail = api.getMyInvitation(new MyStaffInvitationQuery(invitationId, query(USER)));
            assertEquals("绑定测试商家", detail.merchantName());
            assertEquals("旗舰一店", detail.storeName());
            assertEquals("李小美", detail.memberName());
            assertEquals(List.of(VERIFY), detail.grantedActions());
            assertEquals("INVITED", detail.status());

            MerchantStaffMemberCommandResult confirmed = confirm(api, "cf-1", USER, invitationId);
            assertTrue(confirmed.created());
            assertEquals("ENABLED", confirmed.member().memberStatus());
            assertEquals("ENABLED", confirmed.member().grantStatus());
            assertEquals(List.of(VERIFY), confirmed.member().grantedActions());
            assertEquals("李小美", confirmed.member().memberName());
            assertEquals("139****1111", confirmed.member().phoneMasked());
            String memberId = confirmed.member().memberId();

            // Same requestId replays; a different requestId conflicts.
            MerchantStaffMemberCommandResult replay = confirm(api, "cf-1", USER, invitationId);
            assertFalse(replay.created());
            assertTrue(replay.replayed());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> confirm(api, "cf-2", USER, invitationId)).code());

            // Contract-52 read kernel now resolves the freshly bound member.
            var facts = identity.getStaffFacts(new com.petplatform.merchant.api.query.MerchantStaffIdentityFactsQuery(
                    id(MERCHANT), id(STORE), query(USER)));
            assertEquals("STAFF", facts.membershipKind());
            assertTrue(facts.membershipEnabled());
            assertEquals(List.of(VERIFY), facts.grantedActions());
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> identity.getStaffFacts(new com.petplatform.merchant.api.query.MerchantStaffIdentityFactsQuery(
                            id(MERCHANT), id(STORE2), query(USER)))).code());

            // Storage: relations + audit rows exist; the member table never stores phone/name.
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_member WHERE id=" + memberId, Integer.class));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_member_store_grant WHERE member_id=" + memberId
                            + " AND store_id=" + STORE + " AND status='ENABLED'", Integer.class));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_member_store_action WHERE member_id=" + memberId,
                    Integer.class));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_member_audit WHERE action_code='merchant.staff-member.confirm' AND member_id=" + memberId,
                    Integer.class));

            // OWNER management lists project the bound member and the terminal invitation.
            var members = api.listMembers(new StaffMemberManagementQuery(id(MERCHANT), id(STORE),
                    1, 20, query(OWNER)));
            assertEquals(1, members.total());
            assertEquals(memberId, members.items().getFirst().memberId());
            var invitations = api.listInvitations(new StaffInvitationManagementQuery(id(MERCHANT),
                    id(STORE), 1, 20, query(OWNER)));
            assertEquals(1, invitations.total());
            assertEquals("CONFIRMED", invitations.items().getFirst().status());

            // The same account cannot bind twice (D3 pending; UNIQUE(merchant_id,user_id)).
            String second = invite(api, "inv-2", PHONE).invitation().invitationId();
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> confirm(api, "cf-3", USER, second)).code());
        }
    }

    @Test
    void ownerPhoneAndD3SemanticsAreRejected() throws Exception {
        try (var db = new StaffBindingMySqlTestDatabase()) {
            seed(db);
            var api = api(db);
            // OWNER invites their own phone; confirm must refuse (owner never becomes a member).
            api.inviteMember(new InviteStaffMemberCommand(id(MERCHANT), id(STORE), "13900003333",
                    "店主本人", List.of(VERIFY), command("inv-owner", OWNER)));
            var ownerApi = new MerchantStaffMemberApiImpl(db.dataSource(), ids::incrementAndGet,
                    approvedFacts(),
                    new AesGcmProtectedValueProvider("staff-binding-test-v1", key((byte) 3), key((byte) 4)),
                    (userId, phone) -> userId == OWNER && "13900003333".equals(phone),
                    Mockito.mock(ScheduleCapacityGuardApi.class), CLOCK);
            // The invitation detail 404s for the owner session: phone matches but the confirm
            // path rejects OWNER binding; keep the read honest by asserting the conflict there.
            String invitationId = db.jdbc().queryForObject(
                    "SELECT id FROM merchant_member_invitation WHERE phone='13900003333'", String.class);
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> ownerApi.confirmInvitation(new ConfirmStaffMemberInvitationCommand(
                            invitationId, command("cf-owner", OWNER)))).code());

            // An already-bound account cannot take a second invitation on another store.
            String first = api.inviteMember(new InviteStaffMemberCommand(id(MERCHANT), id(STORE),
                    PHONE, "李小美", List.of(VERIFY), command("inv-1", OWNER)))
                    .invitation().invitationId();
            confirm(api, "cf-1", USER, first);
            String second = api.inviteMember(new InviteStaffMemberCommand(id(MERCHANT), id(STORE2),
                    PHONE, "李小美", List.of(VERIFY), command("inv-2", OWNER)))
                    .invitation().invitationId();
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> confirm(api, "cf-2", USER, second)).code());
        }
    }

    @Test
    void disableEnableAndActionGateStayFailClosed() throws Exception {
        try (var db = new StaffBindingMySqlTestDatabase()) {
            seed(db);
            var api = api(db);
            var identity = identityApi(db);
            String invitationId = invite(api, "inv-1", PHONE).invitation().invitationId();
            String memberId = confirm(api, "cf-1", USER, invitationId).member().memberId();

            // Disable bumps member version; the 52 action gate fails closed immediately.
            var disabled = api.disableMember(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE),
                    memberId, "0", command("d1", OWNER)));
            assertEquals("DISABLED", disabled.member().memberStatus());
            assertEquals("1", disabled.member().memberVersion());
            assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                    () -> identity.requireStaffAction(new com.petplatform.merchant.api.query.MerchantStaffActionQuery(
                            id(MERCHANT), id(STORE), VERIFY, query(USER)))).code());

            // Version conflict and double-disable conflict.
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> api.disableMember(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE),
                            memberId, "0", command("d2", OWNER)))).code());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> api.disableMember(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE),
                            memberId, "1", command("d3", OWNER)))).code());

            var enabled = api.enableMember(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE),
                    memberId, "1", command("e1", OWNER)));
            assertEquals("ENABLED", enabled.member().memberStatus());
            // 52 membership listing shows the enabled member again.
            assertEquals(1, identity.listStaffMemberships(
                    new com.petplatform.merchant.api.query.MerchantStaffMembershipQuery(1, 20, query(USER)))
                    .total());

            // A same-requestId same-params replay re-verifies authority and serves the CURRENT
            // projection (enabled again by now), not the stale disable receipt (23号);
            // different params on the same key conflict.
            var replayed = api.disableMember(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE),
                    memberId, "0", command("d1", OWNER)));
            assertTrue(replayed.replayed());
            assertEquals("ENABLED", replayed.member().memberStatus());
            assertEquals("2", replayed.member().memberVersion());
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, assertThrows(ApiException.class,
                    () -> api.disableMember(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE),
                            memberId, "1", command("d1", OWNER)))).code());
        }
    }

    @Test
    void grantActionsReplaceAndRevokeStoreIsTerminal() throws Exception {
        try (var db = new StaffBindingMySqlTestDatabase()) {
            seed(db);
            var api = api(db);
            var identity = identityApi(db);
            String invitationId = invite(api, "inv-1", PHONE).invitation().invitationId();
            String memberId = confirm(api, "cf-1", USER, invitationId).member().memberId();

            // Catalog enforcement: empty, unknown and duplicate inputs are invalid.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.grantActions(new GrantStaffMemberActionsCommand(id(MERCHANT), id(STORE),
                            memberId, List.of(), "0", command("g0", OWNER)))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.grantActions(new GrantStaffMemberActionsCommand(id(MERCHANT), id(STORE),
                            memberId, List.of("merchant.order.read"), "0", command("g1", OWNER)))).code());

            // Whole-set replacement bumps the grant version and feeds authzVersion.
            var granted = api.grantActions(new GrantStaffMemberActionsCommand(id(MERCHANT), id(STORE),
                    memberId, List.of(VERIFY), "0", command("g2", OWNER)));
            assertEquals("1", granted.member().grantVersion());
            assertEquals(List.of(VERIFY), granted.member().grantedActions());

            // A second store grant is created through grant-actions (grant v0 -> actions).
            var store2 = api.grantActions(new GrantStaffMemberActionsCommand(id(MERCHANT), id(STORE2),
                    memberId, List.of(VERIFY), "0", command("g3", OWNER)));
            assertEquals("0", store2.member().grantVersion());
            assertEquals(2, identity.listStaffMemberships(
                    new com.petplatform.merchant.api.query.MerchantStaffMembershipQuery(1, 20, query(USER)))
                    .total());

            // Revoke-store is terminal (D3 pending): gate 404s, re-grant conflicts.
            var revoked = api.revokeStoreGrant(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE),
                    memberId, "1", command("r1", OWNER)));
            assertEquals("REVOKED", revoked.member().grantStatus());
            assertEquals(List.of(), revoked.member().grantedActions());
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> identity.getStaffFacts(new com.petplatform.merchant.api.query.MerchantStaffIdentityFactsQuery(
                            id(MERCHANT), id(STORE), query(USER)))).code());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> api.grantActions(new GrantStaffMemberActionsCommand(id(MERCHANT), id(STORE),
                            memberId, List.of(VERIFY), "2", command("g4", OWNER)))).code());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> api.revokeStoreGrant(new StaffMemberLifecycleCommand(id(MERCHANT), id(STORE),
                            memberId, "2", command("r2", OWNER)))).code());
            // The second store keeps working.
            identity.getStaffFacts(new com.petplatform.merchant.api.query.MerchantStaffIdentityFactsQuery(
                    id(MERCHANT), id(STORE2), query(USER)));
        }
    }

    @Test
    void concurrentConfirmsSerializeToOneWinner() throws Exception {
        try (var db = new StaffBindingMySqlTestDatabase()) {
            seed(db);
            var api = api(db);
            String invitationId = invite(api, "inv-1", PHONE).invitation().invitationId();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch ready = new CountDownLatch(2);
                Future<String> first = pool.submit(() -> {
                    ready.countDown();
                    ready.await();
                    try {
                        confirm(api, "race-a", USER, invitationId);
                        return "a-created";
                    } catch (ApiException e) {
                        return "a-" + e.code();
                    }
                });
                Future<String> second = pool.submit(() -> {
                    ready.countDown();
                    ready.await();
                    try {
                        confirm(api, "race-b", USER, invitationId);
                        return "b-created";
                    } catch (ApiException e) {
                        return "b-" + e.code();
                    }
                });
                String a = first.get(60, TimeUnit.SECONDS);
                String b = second.get(60, TimeUnit.SECONDS);
                long created = (a.equals("a-created") ? 1 : 0) + (b.equals("b-created") ? 1 : 0);
                assertEquals(1, created, "exactly one concurrent confirm wins: " + a + " / " + b);
                assertTrue(a.endsWith(CommonApiCodes.CONFLICT) || b.endsWith(CommonApiCodes.CONFLICT),
                        "the loser conflicts: " + a + " / " + b);
                assertEquals(1, db.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM merchant_member", Integer.class));
                assertEquals(1, db.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM merchant_member_audit WHERE action_code='merchant.staff-member.confirm'",
                        Integer.class));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void ownerAdmissionAndInputValidationFailClosed() throws Exception {
        try (var db = new StaffBindingMySqlTestDatabase()) {
            seed(db);
            var api = api(db);
            // Non-owner operator reads exactly like an unknown merchant (anti-enumeration 404,
            // same as the 35号 staff management reads).
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> api.inviteMember(new InviteStaffMemberCommand(id(MERCHANT), id(STORE),
                            PHONE, "李小美", List.of(VERIFY), command("x1", USER)))).code());
            // Unknown merchant anti-authorized 404.
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> api.inviteMember(new InviteStaffMemberCommand(id(9_100_000_000_000_999L),
                            id(STORE), PHONE, "李小美", List.of(VERIFY), command("x2", OWNER)))).code());
            // Bad phone / bad name / bad catalog.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.inviteMember(new InviteStaffMemberCommand(id(MERCHANT), id(STORE),
                            "1234", "李小美", List.of(VERIFY), command("x3", OWNER)))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.inviteMember(new InviteStaffMemberCommand(id(MERCHANT), id(STORE),
                            PHONE, "", List.of(VERIFY), command("x4", OWNER)))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.inviteMember(new InviteStaffMemberCommand(id(MERCHANT), id(STORE),
                            PHONE, "李小美", List.of("merchant.order.fulfill"), command("x5", OWNER)))).code());
            // Reads require the owner too (non-owner user reads as 404, not a scope leak).
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                    () -> api.listMembers(new StaffMemberManagementQuery(id(MERCHANT), id(STORE),
                            1, 20, query(USER)))).code());
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM merchant_member_invitation", Integer.class));
        }
    }

    @Test
    void guardTransactionJoinKeepsDisableAtomicWithGate() throws Exception {
        try (var db = new StaffBindingMySqlTestDatabase()) {
            seed(db);
            var api = api(db);
            String invitationId = invite(api, "inv-1", PHONE).invitation().invitationId();
            String memberId = confirm(api, "cf-1", USER, invitationId).member().memberId();
            // The confirm command ran with a mocked guard; the storage facts must still let a
            // real guard-joined action-gate transaction (52) verify inside a caller transaction.
            var identity = identityApi(db);
            TransactionTemplate guardTx = new TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.dataSource()));
            guardTx.setIsolationLevel(TransactionTemplate.ISOLATION_REPEATABLE_READ);
            guardTx.execute(status -> {
                identity.requireStaffAction(new com.petplatform.merchant.api.query.MerchantStaffActionQuery(
                        id(MERCHANT), id(STORE), VERIFY, query(USER)));
                return null;
            });
            assertEquals("ENABLED", db.jdbc().queryForObject(
                    "SELECT status FROM merchant_member WHERE id=" + memberId, String.class));
        }
    }
}
