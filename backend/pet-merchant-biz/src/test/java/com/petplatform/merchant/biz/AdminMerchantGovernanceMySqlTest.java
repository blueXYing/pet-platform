package com.petplatform.merchant.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminAuthorization;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminMerchantDetailQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminMerchantListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffInvitationListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffListQuery;
import com.petplatform.merchant.api.query.AdminMerchantGovernanceQueryApi.AdminStaffMemberListQuery;
import com.petplatform.merchant.biz.apiimpl.AdminMerchantGovernanceQueryApiImpl;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Check;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.CollectionCheck;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Decision;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.MerchantGovernanceAuthorizationPort;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Phase;
import com.petplatform.merchant.biz.application.AdminGovernanceAuthorizationPort.Resource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Isolated real MySQL proves the contract-64 merchant governance read face: the ledger list
 * with filters/masking/ordering, the detail projection (stores + application + signing),
 * staff/member/invitation read-only views with pre-masked phones, anti-enumeration 404s,
 * fail-closed authorization and the query vocabulary gates. Read-only: no governance write
 * path exists to prove.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminMerchantGovernanceMySqlTest {
    private static final long M1 = 9_100_000_000_000_101L;
    private static final long M2 = 9_100_000_000_000_102L;
    private static final long S1 = 9_100_000_000_000_201L;
    private static final long S2 = 9_100_000_000_000_202L;
    private static final long ST1 = 9_100_000_000_000_301L;
    private static final long ST2 = 9_100_000_000_000_302L;
    private static final long INV1 = 9_100_000_000_000_401L;
    private static final long INV2 = 9_100_000_000_000_402L;
    private static final long INV3 = 9_100_000_000_000_403L;
    private static final long INV4 = 9_100_000_000_000_404L;
    private static final long MEM1 = 9_100_000_000_000_501L;
    private static final long MEM2 = 9_100_000_000_000_502L;
    private static final long OWNER1 = 9_100_000_000_000_610L;
    private static final long OWNER2 = 9_100_000_000_000_621L;
    private static final long USER1 = 9_100_000_000_000_611L;
    private static final long USER2 = 9_100_000_000_000_612L;
    private static final String OPERATOR = "9100000000000999";

    private AdminMerchantGovernanceMySqlTestDatabase db;
    private AdminMerchantGovernanceQueryApi api;
    private JdbcTemplate jdbc;
    private final AtomicReference<String> lastCollectionAction = new AtomicReference<>();
    private final AtomicReference<String> lastResourceAction = new AtomicReference<>();

    @BeforeAll
    void open() throws Exception {
        db = new AdminMerchantGovernanceMySqlTestDatabase();
        jdbc = db.jdbc();
        seed();
        api = new AdminMerchantGovernanceQueryApiImpl(db.dataSource(), port(true, "ALLOWED"));
    }

    @AfterAll
    void close() throws Exception {
        if (db != null) db.close();
    }

    private void seed() {
        jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,provider_merchant_no,version,created_at,updated_at) VALUES(?,?,?,'ACTIVE','PROVIDER12345678',7,'2026-10-01 08:00:00.000','2026-10-02 08:00:00.000')",
                M1, OWNER1, "治理测试商家一");
        jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,version,created_at,updated_at) VALUES(?,?,?,'FROZEN',3,'2026-10-05 08:00:00.000','2026-10-06 08:00:00.000')",
                M2, OWNER2, "治理测试商家二");
        jdbc.update("INSERT INTO merchant_profile_compat(merchant_id,application_id,source_revision_id,merchant_type_code,city_code,source_kind,version,created_at,updated_at) VALUES(?,NULL,NULL,'PET_LIFE_STORE','CITY_A','LEGACY',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                M1);
        jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',1,'2026-10-01 08:00:00.000','2026-10-01 08:00:00.000')",
                S1, M1, "旗舰一店", "治理路1号");
        jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at) VALUES(?,?,?,?,'OFFLINE',1,'2026-10-01 08:01:00.000','2026-10-03 08:00:00.000')",
                S2, M1, "旗舰二店", "治理路2号");
        // DRAFT application for M1 (projection keeps only id/status facts).
        jdbc.update("INSERT INTO merchant_application(id,owner_user_id,reserved_merchant_id,status,subject_verification_status,version,created_at,updated_at) VALUES(2101,?,?, 'DRAFT','NOT_STARTED',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                OWNER1, M1);
        // REVIEWING application for M2. The application↔revision foreign keys are circular,
        // so the seed inserts the application with a null revision pointer, adds the
        // submitted revision, then flips the pointer into the CHECK-valid REVIEWING shape.
        // The state-shape CHECK is validated per statement, so the seed starts from the
        // valid DRAFT shape, adds the revision row (FK), then flips every REVIEWING field in
        // one UPDATE that lands on the valid REVIEWING shape.
        jdbc.update("INSERT INTO merchant_application(id,owner_user_id,reserved_merchant_id,status,subject_verification_status,version,created_at,updated_at) VALUES(2102,?,?,'DRAFT','NOT_STARTED',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                OWNER2, M2);
        jdbc.update("INSERT INTO merchant_application_revision(id,application_id,revision_no,merchant_name,created_by_user_id,created_at) VALUES(2102,2102,1,'治理测试商家二',?,'2026-10-09 08:00:00.000')",
                OWNER2);
        jdbc.update("INSERT INTO merchant_application_review_task(id,application_id,submitted_revision_id,submission_no,status,claimed_by_operator_id,claimed_at,closed_at,version,updated_at) VALUES(4102,2102,2102,1,'CLAIMED',901,'2026-10-10 10:00:00.000',NULL,1,'2026-10-10 10:00:00.000')");
        jdbc.update("UPDATE merchant_application SET status='REVIEWING', application_no='SQ20261015AAAAAAAA', submitted_revision_id=2102, current_revision_id=2102, current_review_task_id=4102, subject_verification_status='VERIFIED', version=2, submitted_at='2026-10-10 09:00:00.000' WHERE id=2102");
        // Signing: one published current version, accepted by M1 only.
        String content = "治理测试协议内容";
        jdbc.update("INSERT INTO merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id) VALUES(301,'merchant-agreement-v1',?,?,'2026-09-30 08:00:00.000',901)",
                content, sha());
        jdbc.update("INSERT INTO merchant_agreement_current(agreement_key,agreement_version_id,updated_at) VALUES('MERCHANT',301,UTC_TIMESTAMP(3))");
        jdbc.update("INSERT INTO merchant_agreement_acceptance(id,merchant_id,agreement_version_id,accepted_by_user_id,accepted_at,content_sha256) VALUES(311,?,301,?, '2026-10-02 08:00:00.000',?)",
                M1, OWNER1, sha());
        // Staff profiles (35号 vocabulary).
        jdbc.update("INSERT INTO merchant_staff(id,merchant_id,store_id,staff_name,phone,employment_status,service_enabled,version,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',1,1,'2026-10-02 08:00:00.000','2026-10-02 08:00:00.000')",
                ST1, M1, S1, "员工甲", "13900001111");
        jdbc.update("INSERT INTO merchant_staff(id,merchant_id,store_id,staff_name,phone,employment_status,service_enabled,version,created_at,updated_at) VALUES(?,?,?,?,NULL,'INACTIVE',0,2,'2026-10-02 08:01:00.000','2026-10-03 08:00:00.000')",
                ST2, M1, S2, "员工乙");
        // Members + grants + actions + CONFIRMED invitations (name/phone source).
        jdbc.update("INSERT INTO merchant_member_invitation(id,merchant_id,store_id,phone,member_name,status,invited_by,confirmed_by,member_id,version,pending_marker,created_at,updated_at) VALUES(?,?,?,?,?,'CONFIRMED',?,?,?,2,?,'2026-10-04 08:00:00.000','2026-10-04 09:00:00.000')",
                INV1, M1, S1, "13600001111", "成员甲", OWNER1, USER1, MEM1, Long.toString(INV1));
        jdbc.update("INSERT INTO merchant_member_invitation(id,merchant_id,store_id,phone,member_name,status,invited_by,confirmed_by,member_id,version,pending_marker,created_at,updated_at) VALUES(?,?,?,?,?,'CONFIRMED',?,?,?,2,?,'2026-10-04 10:00:00.000','2026-10-04 11:00:00.000')",
                INV2, M1, S1, "13600002222", "成员乙", OWNER1, USER2, MEM2, Long.toString(INV2));
        jdbc.update("INSERT INTO merchant_member_invitation(id,merchant_id,store_id,phone,member_name,status,invited_by,version,pending_marker,created_at,updated_at) VALUES(?,?,?,?,?,'INVITED',?,0,'PENDING','2026-10-12 08:00:00.000','2026-10-12 08:00:00.000')",
                INV3, M1, S1, "13600003333", "成员丙", OWNER1);
        jdbc.update("INSERT INTO merchant_member_invitation(id,merchant_id,store_id,phone,member_name,status,invited_by,version,pending_marker,created_at,updated_at) VALUES(?,?,?,?,?,'CANCELED',?,1,?,'2026-10-06 08:00:00.000','2026-10-07 08:00:00.000')",
                INV4, M1, S2, "13600004444", "成员丁", OWNER1, Long.toString(INV4));
        jdbc.update("INSERT INTO merchant_member_invitation_action(id,invitation_id,action_code,created_at) VALUES(421,?,'merchant.order.verify',UTC_TIMESTAMP(3))",
                INV3);
        jdbc.update("INSERT INTO merchant_member(id,merchant_id,user_id,status,version,created_at,updated_at) VALUES(?,?,?,'ENABLED',1,'2026-10-04 09:00:00.000','2026-10-04 09:00:00.000')",
                MEM1, M1, USER1);
        jdbc.update("INSERT INTO merchant_member(id,merchant_id,user_id,status,version,created_at,updated_at) VALUES(?,?,?,'DISABLED',3,'2026-10-04 11:00:00.000','2026-10-08 08:00:00.000')",
                MEM2, M1, USER2);
        jdbc.update("INSERT INTO merchant_member_store_grant(id,member_id,store_id,status,version,created_at,updated_at) VALUES(?,?,?,'ENABLED',3,'2026-10-04 09:00:00.000','2026-10-09 08:00:00.000')",
                521L, MEM1, S1);
        jdbc.update("INSERT INTO merchant_member_store_grant(id,member_id,store_id,status,version,created_at,updated_at) VALUES(?,?,?,'REVOKED',4,'2026-10-04 11:00:00.000','2026-10-08 08:00:00.000')",
                522L, MEM2, S1);
        jdbc.update("INSERT INTO merchant_member_store_action(id,member_id,store_id,action_code,created_at,updated_at) VALUES(531,?,?,'merchant.order.verify',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                MEM1, S1);
    }

    private String sha() {
        try {
            return AdminMerchantGovernanceMySqlTestDatabase.sha256("治理测试协议内容");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ ports

    private MerchantGovernanceAuthorizationPort port(boolean allowed, String reasonCode) {
        return new MerchantGovernanceAuthorizationPort() {
            @Override
            public Decision check(Check checkQuery) {
                lastResourceAction.set(checkQuery.actionCode());
                return decision(allowed, reasonCode);
            }

            @Override
            public Decision checkCollection(CollectionCheck collectionQuery) {
                lastCollectionAction.set(collectionQuery.actionCode());
                return decision(allowed, reasonCode);
            }
        };
    }

    /** Null decision on every check: the authorization owner is unavailable. */
    private MerchantGovernanceAuthorizationPort unavailablePort() {
        return new MerchantGovernanceAuthorizationPort() {
            @Override
            public Decision check(Check checkQuery) {
                return null;
            }

            @Override
            public Decision checkCollection(CollectionCheck collectionCheck) {
                return null;
            }
        };
    }

    private static Decision decision(boolean allowed, String reasonCode) {
        return new Decision(allowed, Instant.parse("2026-10-15T08:00:00Z").atOffset(ZoneOffset.UTC),
                "epoch:1", reasonCode);
    }

    private AdminMerchantGovernanceQueryApi api(boolean allowed, String reasonCode) {
        return new AdminMerchantGovernanceQueryApiImpl(db.dataSource(), port(allowed, reasonCode));
    }

    private static QueryContext query() {
        return new QueryContext("trace", OperatorType.PLATFORM_OPERATOR, OPERATOR);
    }

    private static AdminAuthorization authorization() {
        return new AdminAuthorization("9100000000000888", 4L);
    }

    // ------------------------------------------------------------------ ledger

    @Test
    void ledgerListsAllMerchantsNewestFirstWithAggregatesAndMasking() {
        PageResult<AdminMerchantGovernanceQueryApi.AdminMerchantLedgerSummary> page =
                api.listMerchants(new AdminMerchantListQuery(null, null, null, null, null, null,
                        null, 1, 20, query(), authorization()));
        assertEquals("merchant.read", lastCollectionAction.get());
        assertEquals(2, page.total());
        AdminMerchantGovernanceQueryApi.AdminMerchantLedgerSummary first = page.items().get(0);
        assertEquals(Long.toString(M2), first.merchantId());
        assertEquals("FROZEN", first.status());
        assertEquals("REVIEWING", first.applicationStatus());
        assertEquals("NOT_SIGNED", first.signingStatus());
        assertNull(first.merchantTypeCode());
        AdminMerchantGovernanceQueryApi.AdminMerchantLedgerSummary second = page.items().get(1);
        assertEquals(Long.toString(M1), second.merchantId());
        assertEquals("PET_LIFE_STORE", second.merchantTypeCode());
        assertEquals("CITY_A", second.cityCode());
        assertEquals(2, second.storeCount());
        assertEquals(1, second.activeStoreCount());
        assertEquals("DRAFT", second.applicationStatus());
        assertEquals("SIGNED", second.signingStatus());
        assertEquals("****5678", second.providerMerchantNoMasked());
    }

    @Test
    void ledgerFiltersByStatusStoreStatusCityTypeKeywordAndRange() {
        assertEquals(1, api.listMerchants(new AdminMerchantListQuery("FROZEN", null, null, null,
                null, null, null, 1, 20, query(), authorization())).total());
        assertEquals(1, api.listMerchants(new AdminMerchantListQuery(null, "OFFLINE", null, null,
                null, null, null, 1, 20, query(), authorization())).total());
        assertEquals(Long.toString(M1), api.listMerchants(new AdminMerchantListQuery(null, null,
                "CITY_A", null, null, null, null, 1, 20, query(), authorization())).items().get(0).merchantId());
        assertEquals(Long.toString(M1), api.listMerchants(new AdminMerchantListQuery(null, null,
                null, "PET_LIFE_STORE", null, null, null, 1, 20, query(), authorization())).items().get(0).merchantId());
        // keyword matches the merchant name and owned store names alike.
        assertEquals(1, api.listMerchants(new AdminMerchantListQuery(null, null, null, null,
                "旗舰二店", null, null, 1, 20, query(), authorization())).total());
        assertEquals(1, api.listMerchants(new AdminMerchantListQuery(null, null, null, null, null,
                OffsetDateTime.parse("2026-10-03T00:00:00.000Z"),
                OffsetDateTime.parse("2026-10-06T00:00:00.000Z"), 1, 20, query(), authorization())).total());
        assertEquals(0, api.listMerchants(new AdminMerchantListQuery("CANCELED", null, null, null,
                null, null, null, 1, 20, query(), authorization())).total());
    }

    @Test
    void ledgerDetailCarriesStoresApplicationAndSigningFacts() {
        var detail = api.getMerchant(new AdminMerchantDetailQuery(Long.toString(M1), query(),
                authorization()));
        assertEquals("merchant.read", lastResourceAction.get());
        assertEquals("治理测试商家一", detail.merchantName());
        assertEquals(2, detail.stores().size());
        assertEquals(Long.toString(S1), detail.stores().get(0).storeId());
        assertEquals("旗舰一店", detail.stores().get(0).storeName());
        assertEquals("治理路1号", detail.stores().get(0).address());
        assertEquals(Long.toString(2101), detail.application().applicationId());
        assertEquals("DRAFT", detail.application().status());
        assertNull(detail.application().applicationNo());
        assertEquals("SIGNED", detail.signing().signingStatus());
        assertEquals("merchant-agreement-v1", detail.signing().agreementVersion());
        assertEquals("2026-10-02T08:00:00Z", detail.signing().acceptedAt().toInstant().toString());
        var frozen = api.getMerchant(new AdminMerchantDetailQuery(Long.toString(M2), query(),
                authorization()));
        assertEquals("REVIEWING", frozen.application().status());
        assertEquals("SQ20261015AAAAAAAA", frozen.application().applicationNo());
        assertEquals("2026-10-10T09:00:00Z", frozen.application().submittedAt().toInstant().toString());
        assertEquals("NOT_SIGNED", frozen.signing().signingStatus());
        assertNull(frozen.signing().acceptedAt());
    }

    @Test
    void unknownMerchantReadsAsNotFoundForAntiEnumeration() {
        ApiException failure = assertThrows(ApiException.class,
                () -> api.getMerchant(new AdminMerchantDetailQuery("910000000000999", query(),
                        authorization())));
        assertEquals(CommonApiCodes.NOT_FOUND, failure.code());
        assertEquals(CommonApiCodes.NOT_FOUND,
                assertThrows(ApiException.class, () -> api.listStaff(new AdminStaffListQuery(
                        "910000000000999", null, null, null, 1, 20, query(), authorization()))).code());
    }

    // ------------------------------------------------------------------ staff

    @Test
    void staffListOrdersByIdAscMasksPhoneAndFilters() {
        var page = api.listStaff(new AdminStaffListQuery(Long.toString(M1), null, null, null, 1,
                20, query(), authorization()));
        assertEquals("merchant.staff.read", lastResourceAction.get());
        assertEquals(2, page.total());
        var first = page.items().get(0);
        assertEquals(Long.toString(ST1), first.staffId());
        assertEquals("139****1111", first.phoneMasked());
        assertEquals("ACTIVE", first.employmentStatus());
        assertTrue(first.serviceEnabled());
        assertEquals("1", first.version());
        assertEquals(1, api.listStaff(new AdminStaffListQuery(Long.toString(M1), Long.toString(S2),
                null, null, 1, 20, query(), authorization())).total());
        assertEquals(1, api.listStaff(new AdminStaffListQuery(Long.toString(M1), null, "INACTIVE",
                null, 1, 20, query(), authorization())).total());
        var disabled = api.listStaff(new AdminStaffListQuery(Long.toString(M1), null, null, false,
                1, 20, query(), authorization())).items().get(0);
        assertNull(disabled.phoneMasked());
        assertFalse(disabled.serviceEnabled());
    }

    // ------------------------------------------------------------------ members & invitations

    @Test
    void memberListSurfacesBindingStateActionsAndTerminalRevoke() {
        var page = api.listStaffMembers(new AdminStaffMemberListQuery(Long.toString(M1), null,
                null, 1, 20, query(), authorization()));
        assertEquals("merchant.staff.read", lastResourceAction.get());
        assertEquals(2, page.total());
        var enabled = page.items().get(0);
        assertEquals(Long.toString(MEM1), enabled.memberId());
        assertEquals("ENABLED", enabled.status());
        assertEquals("成员甲", enabled.memberName());
        assertEquals("136****1111", enabled.phoneMasked());
        assertEquals(List.of("merchant.order.verify"), enabled.grantedActions());
        assertFalse(enabled.grantRevoked());
        assertEquals("3", enabled.grantVersion());
        var revoked = page.items().get(1);
        assertEquals("DISABLED", revoked.status());
        assertTrue(revoked.grantRevoked());
        assertEquals(List.of(), revoked.grantedActions());
        assertEquals(1, api.listStaffMembers(new AdminStaffMemberListQuery(Long.toString(M1),
                null, "DISABLED", 1, 20, query(), authorization())).total());
        assertEquals(2, api.listStaffMembers(new AdminStaffMemberListQuery(Long.toString(M1),
                Long.toString(S1), null, 1, 20, query(), authorization())).total());
    }

    @Test
    void invitationListOrdersIdDescCarriesActionsAndStateMachine() {
        var page = api.listStaffInvitations(new AdminStaffInvitationListQuery(Long.toString(M1),
                null, null, 1, 20, query(), authorization()));
        assertEquals("merchant.staff.read", lastResourceAction.get());
        assertEquals(4, page.total());
        // id DESC (54号 §7): 404 CANCELED, 403 INVITED, 402/401 CONFIRMED.
        assertEquals(Long.toString(INV4), page.items().get(0).invitationId());
        assertEquals("CANCELED", page.items().get(0).status());
        var invited = page.items().get(1);
        assertEquals(Long.toString(INV3), invited.invitationId());
        assertEquals("INVITED", invited.status());
        assertEquals(List.of("merchant.order.verify"), invited.grantedActions());
        assertEquals("136****3333", invited.phoneMasked());
        assertEquals(Long.toString(OWNER1), invited.invitedBy());
        assertEquals("CONFIRMED", page.items().get(3).status());
        assertEquals(1, api.listStaffInvitations(new AdminStaffInvitationListQuery(
                Long.toString(M1), Long.toString(S2), null, 1, 20, query(), authorization())).total());
        assertEquals(1, api.listStaffInvitations(new AdminStaffInvitationListQuery(
                Long.toString(M1), null, "CANCELED", 1, 20, query(), authorization())).total());
    }

    // ------------------------------------------------------------------ fail-closed gates

    @Test
    void vocabularyAndRangeValidationRejectInvalidInput() {
        assertInvalid(new AdminMerchantListQuery("BOGUS", null, null, null, null, null, null, 1,
                20, query(), authorization()));
        assertInvalid(new AdminMerchantListQuery(null, "PAUSED", null, null, null, null, null, 1,
                20, query(), authorization()));
        // single-sided created range and over-long range both reject
        assertInvalid(new AdminMerchantListQuery(null, null, null, null, null,
                OffsetDateTime.parse("2026-10-01T00:00:00.000Z"), null, 1, 20, query(), authorization()));
        assertInvalid(new AdminMerchantListQuery(null, null, null, null, "a", null, null, 1, 20,
                query(), authorization()));
        assertInvalid(new AdminStaffMemberListQuery(Long.toString(M1), null, "PAUSED", 1, 20,
                query(), authorization()));
        assertInvalid(new AdminStaffInvitationListQuery(Long.toString(M1), null, "DELETED", 1, 20,
                query(), authorization()));
        assertInvalid(new AdminStaffListQuery(Long.toString(M1), null, "RETired", null, 1, 20,
                query(), authorization()));
    }

    private void assertInvalid(AdminMerchantGovernanceQueryApi.AdminMerchantListQuery query) {
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> api.listMerchants(query)).code());
    }

    private void assertInvalid(AdminStaffListQuery query) {
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> api.listStaff(query)).code());
    }

    private void assertInvalid(AdminStaffMemberListQuery query) {
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> api.listStaffMembers(query)).code());
    }

    private void assertInvalid(AdminStaffInvitationListQuery query) {
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> api.listStaffInvitations(query)).code());
    }

    @Test
    void authorizationFailuresStayClosed() {
        // Collection deny (reason does not matter at list level) is 403.
        assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                () -> api(false, "ACTION_NOT_GRANTED").listMerchants(new AdminMerchantListQuery(
                        null, null, null, null, null, null, null, 1, 20, query(), authorization())))
                .code());
        // Scope-denied single resource reads as 404 (anti-enumeration), never 403.
        assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                () -> api(false, "RESOURCE_SCOPE_DENIED").getMerchant(
                        new AdminMerchantDetailQuery(Long.toString(M1), query(), authorization())))
                .code());
        // A present but denied decision stays 403 even without a reason code.
        assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                () -> api(false, null).listStaff(new AdminStaffListQuery(Long.toString(M1), null,
                        null, null, 1, 20, query(), authorization()))).code());
        // Null/unavailable authorization decisions never read (503 fail-closed).
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                () -> new AdminMerchantGovernanceQueryApiImpl(db.dataSource(), unavailablePort())
                        .listStaff(new AdminStaffListQuery(Long.toString(M1), null, null, null, 1,
                                20, query(), authorization()))).code());
        // Non-operator contexts are rejected before any authorization work.
        assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                () -> api.listMerchants(new AdminMerchantListQuery(null, null, null, null, null,
                        null, null, 1, 20, new QueryContext("trace", OperatorType.USER, "1"),
                        authorization()))).code());
        // Authorization references without a session are unauthorized.
        assertEquals(CommonApiCodes.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> api.listMerchants(new AdminMerchantListQuery(null, null, null, null, null,
                        null, null, 1, 20, query(), new AdminAuthorization("", 1)))).code());
    }
}
