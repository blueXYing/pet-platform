package com.petplatform.coupon.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.command.AdminCouponCampaignApi;
import com.petplatform.coupon.api.command.CouponClaimApi;
import com.petplatform.coupon.api.command.MerchantCouponAdminApi;
import com.petplatform.coupon.api.command.MerchantCouponAdminApi.AdminAuthorization;
import com.petplatform.coupon.api.command.MerchantCouponApi;
import com.petplatform.coupon.api.error.MerchantCouponApiCodes;
import com.petplatform.coupon.biz.apiimpl.CouponCampaignApiImpl;
import com.petplatform.coupon.biz.apiimpl.CouponCommandApiImpl;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Check;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.CollectionCheck;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort.Decision;
import com.petplatform.coupon.biz.application.MerchantCouponAdminService;
import com.petplatform.coupon.biz.application.MerchantCouponService;
import com.petplatform.coupon.biz.event.MerchantCouponPaidConsumer;
import com.petplatform.coupon.biz.event.RefundSucceededCouponConsumer;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.merchant.api.dto.MerchantAdmissionDTO;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;
import com.petplatform.merchant.api.dto.MerchantOrderEligibilityDTO;
import com.petplatform.merchant.api.dto.MerchantStaffDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayPageDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQuery;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.merchant.api.query.MerchantOrderEligibilityQuery;
import com.petplatform.merchant.api.query.MerchantQueryApi;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.merchant.api.query.MerchantStoreDisplayPageQuery;
import com.petplatform.merchant.api.query.MerchantStoreDisplayQuery;
import com.petplatform.merchant.api.query.MerchantStaffQuery;
import com.petplatform.merchant.api.query.StoreIdQuery;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract 79 / CCR-MKT-MERCHANT-COUPON-001 acceptance against isolated real MySQL: the
 * merchant campaign state machine with its dedicated template, per-merchant name uniqueness
 * that terminal campaigns release, per-user limits, budget exhaustion, the separate budget
 * ledger (RESERVE / USE / UNUSE, each at most once), the freeze gate that keeps a merchant
 * coupon on its own merchant's orders, and the untouched behavior of platform campaigns.
 */
class MerchantCouponMySqlTest {

    private static final long USER = 9_420_000_000_000_201L;
    private static final long MERCHANT_A = 9_420_000_000_000_101L;
    private static final long STORE_A = 9_420_000_000_000_901L;
    private static final long MERCHANT_B = 9_420_000_000_000_102L;
    private static final long STORE_B = 9_420_000_000_000_902L;
    private static final OffsetDateTime START = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END = OffsetDateTime.of(2099, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final AtomicLong NUMBERS = new AtomicLong(51_000_000L);
    private static final SnowflakeIdGenerator IDS = NUMBERS::incrementAndGet;

    private final AdminCouponAuthorizationPort.CouponPort authz = new AdminCouponAuthorizationPort.CouponPort() {
        @Override
        public Decision check(Check check) {
            return allowed();
        }

        @Override
        public Decision checkCollection(CollectionCheck check) {
            return allowed();
        }
    };
    private MerchantCouponMySqlTestDatabase db;
    private MerchantCouponService merchant;
    private MerchantCouponAdminService admin;
    private CouponCampaignApiImpl campaigns;
    private CouponCommandApiImpl lifecycle;
    private MerchantCouponPaidConsumer paid;
    private RefundSucceededCouponConsumer refund;
    private JdbcOutboxConsumeGuard claims;

    private static Decision allowed() {
        return new Decision(true, OffsetDateTime.now(ZoneOffset.UTC), "v1", null);
    }

    @BeforeEach
    void start() throws Exception {
        db = new MerchantCouponMySqlTestDatabase();
        merchant = new MerchantCouponService(store(), new com.petplatform.coupon.biz.application
                .MerchantCouponAdmissionGate(admissions("OWNER")), java.time.Clock.systemUTC());
        admin = new MerchantCouponAdminService(store(), authz, merchants, java.time.Clock.systemUTC());
        campaigns = new CouponCampaignApiImpl(db.dataSource(), IDS, authz, (userId, trace) -> true,
                displays, java.time.Clock.systemUTC());
        var guard = new ScheduleCapacityGuardApi() {
            @Override public void acquire(List<String> storeIds, QueryContext context) {}
            @Override public void requireHeld(String storeId, javax.sql.DataSource callerSource) {}
        };
        lifecycle = new CouponCommandApiImpl(db.dataSource(), guard, IDS);
        claims = new JdbcOutboxConsumeGuard(db.dataSource(), IDS);
        paid = new MerchantCouponPaidConsumer(db.dataSource(), IDS,
                (name, event) -> claims.tryClaim(name, event), java.time.Clock.systemUTC());
        refund = new RefundSucceededCouponConsumer(db.dataSource(), IDS, claims);
    }

    @AfterEach
    void stop() {
        if (db != null) db.close();
    }

    // ------------------------------------------------------------------ state machine

    @Test
    void createSubmitsAndApprovesWithADedicatedTemplate() {
        MerchantCouponApi.Receipt created = create(MERCHANT_A, STORE_A, "新客立减", "10.00", null, 1, 5, "50.00");
        long id = Long.parseLong(created.campaignId());
        assertEquals("DRAFT", created.status());
        assertEquals(1L, created.version());
        assertEquals(1, count("SELECT COUNT(*) FROM coupon_template WHERE id=(SELECT template_id FROM"
                + " coupon_campaign WHERE id=?) AND status='DRAFT' AND owner_type='MERCHANT'"
                + " AND funding_type='MERCHANT' AND owner_merchant_id=?", id, MERCHANT_A));
        assertEquals(1, count("SELECT COUNT(*) FROM merchant_coupon_budget WHERE campaign_id=? AND"
                + " budget_limit=50.00 AND issued_amount=0.00 AND used_amount=0.00", id));

        MerchantCouponApi.Receipt submitted = merchant.submit(status(id, MERCHANT_A, STORE_A, 1));
        assertEquals("PENDING_REVIEW", submitted.status());
        assertEquals(2L, submitted.version());
        // Submitted campaigns are locked: no edit, no second submit.
        assertEquals(MerchantCouponApiCodes.STATE_NOT_ALLOWED, assertThrows(ApiException.class,
                () -> merchant.submit(status(id, MERCHANT_A, STORE_A, 2))).code());

        MerchantCouponAdminApi.Receipt approved = admin.approve(review(id, 2, null));
        assertEquals("ACTIVE", approved.status());
        assertEquals(3L, approved.version());
        assertEquals("ACTIVE", db.jdbc().queryForObject("SELECT status FROM coupon_template WHERE"
                + " id=(SELECT template_id FROM coupon_campaign WHERE id=?)", String.class, id));
        assertEquals(List.of("APPROVE", "SUBMIT", "CREATE"), db.jdbc().queryForList(
                "SELECT action_type FROM coupon_campaign_audit WHERE campaign_id=? ORDER BY id DESC",
                String.class, id));
        // Operators cannot approve twice and merchants never reach publish routes.
        assertEquals(MerchantCouponApiCodes.STATE_NOT_ALLOWED, assertThrows(ApiException.class,
                () -> admin.approve(review(id, 3, null))).code());
    }

    @Test
    void rejectRequiresAReasonAndTheMerchantCanResubmit() {
        long id = createId(MERCHANT_A, STORE_A, "驳回流转", "8.00", null, 1, 5, "40.00");
        merchant.submit(status(id, MERCHANT_A, STORE_A, 1));
        assertEquals(MerchantCouponApiCodes.INVALID, assertThrows(ApiException.class,
                () -> admin.reject(review(id, 2, null))).code());
        MerchantCouponAdminApi.Receipt rejected = admin.reject(review(id, 2, "优惠力度过大"));
        assertEquals("REJECTED", rejected.status());
        assertEquals("优惠力度过大", merchant.get(get(id, MERCHANT_A, STORE_A)).rejectReason());
        // Rejected campaigns are editable again, then resubmitted.
        long next = rejected.version();
        next = merchant.update(new MerchantCouponApi.UpdateCommand(Long.toString(id),
                Long.toString(MERCHANT_A), Long.toString(STORE_A), userCmd(), next,
                input("驳回流转", "5.00", null, 1, 5, "25.00"))).version();
        assertEquals("PENDING_REVIEW", merchant.submit(status(id, MERCHANT_A, STORE_A, next)).status());
    }

    @Test
    void pauseAndEndAreOperatorOnly() {
        long id = live(MERCHANT_A, STORE_A, "暂停结束", "10.00", null, 1, 5, "50.00");
        assertEquals("PAUSED", admin.pause(review(id, 3, null)).status());
        assertEquals("ENDED", admin.end(review(id, 4, null)).status());
        assertEquals(MerchantCouponApiCodes.STATE_NOT_ALLOWED, assertThrows(ApiException.class,
                () -> admin.pause(review(id, 5, null))).code());
    }

    @Test
    void staleVersionsAndForeignMerchantsAreRejected() {
        long id = createId(MERCHANT_A, STORE_A, "版本校验", "10.00", null, 1, 5, "50.00");
        assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                () -> merchant.submit(status(id, MERCHANT_A, STORE_A, 9))).code());
        // Another merchant sees neither the row nor a way to act on it (anti-enumeration 404).
        assertEquals(MerchantCouponApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                () -> merchant.get(get(id, MERCHANT_B, STORE_B))).code());
        assertEquals(MerchantCouponApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                () -> merchant.submit(status(id, MERCHANT_B, STORE_B, 1))).code());
        // Staff (non-owner) admission reads as not found as well.
        MerchantCouponService staff = new MerchantCouponService(store(),
                new com.petplatform.coupon.biz.application.MerchantCouponAdmissionGate(
                        admissions("STAFF")), java.time.Clock.systemUTC());
        assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                () -> staff.get(get(id, MERCHANT_A, STORE_A))).code());
    }

    // ------------------------------------------------------------------ naming and idempotency

    @Test
    void namesAreUniquePerMerchantUntilTheCampaignEnds() {
        create(MERCHANT_A, STORE_A, "同名活动", "10.00", null, 1, 5, "50.00");
        assertEquals(MerchantCouponApiCodes.NAME_DUPLICATE, assertThrows(ApiException.class,
                () -> create(MERCHANT_A, STORE_A, "同名活动", "10.00", null, 1, 5, "50.00")).code());
        // Another merchant may use the same name, and a cancelled campaign frees it.
        MerchantCouponApi.Receipt other = create(MERCHANT_B, STORE_B, "同名活动", "10.00", null, 1, 5, "50.00");
        assertEquals("CANCELED", merchant.cancel(new MerchantCouponApi.StatusCommand(
                other.campaignId(), Long.toString(MERCHANT_B), Long.toString(STORE_B), userCmd(), 1)).status());
        assertEquals("DRAFT", create(MERCHANT_B, STORE_B, "同名活动", "10.00", null, 1, 5, "50.00").status());
    }

    @Test
    void createIsIdempotentPerRequestId() {
        CommandContext once = userCmd();
        MerchantCouponApi.Receipt first = merchant.create(new MerchantCouponApi.CreateCommand(
                Long.toString(MERCHANT_A), Long.toString(STORE_A), once,
                input("幂等创建", "10.00", null, 1, 5, "50.00")));
        MerchantCouponApi.Receipt replay = merchant.create(new MerchantCouponApi.CreateCommand(
                Long.toString(MERCHANT_A), Long.toString(STORE_A), once,
                input("幂等创建", "10.00", null, 1, 5, "50.00")));
        assertEquals(first, replay);
        assertEquals(1, count("SELECT COUNT(*) FROM coupon_campaign WHERE name='幂等创建'"));
        // Same request id with a different payload is a conflict, never a second campaign.
        assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, assertThrows(ApiException.class,
                () -> merchant.create(new MerchantCouponApi.CreateCommand(Long.toString(MERCHANT_A),
                        Long.toString(STORE_A), once, input("幂等创建", "12.00", null, 1, 5, "50.00")))).code());
    }

    // ------------------------------------------------------------------ claims, limits, budget

    @Test
    void claimsHonourPerUserLimitAndBudget() {
        long single = live(MERCHANT_A, STORE_A, "每人一张", "10.00", null, 1, 5, "50.00");
        claim(single, USER, "c1");
        assertEquals("COUPON_CLAIMED", assertThrows(ApiException.class, () -> claim(single, USER, "c2")).code());

        long twice = live(MERCHANT_A, STORE_A, "每人两张", "10.00", null, 2, 5, "50.00");
        claim(twice, USER, "d1");
        claim(twice, USER, "d2");
        assertEquals("COUPON_CLAIM_LIMIT_REACHED",
                assertThrows(ApiException.class, () -> claim(twice, USER, "d3")).code());
        assertEquals(List.of(1, 2), db.jdbc().queryForList("SELECT claim_no FROM coupon_claim WHERE"
                + " campaign_id=? ORDER BY claim_no", Integer.class, twice));

        // Budget 25.00 with a face value of 10.00 stops the third claim (30.00 would exceed it).
        long capped = live(MERCHANT_A, STORE_A, "预算封顶", "10.00", null, 5, 10, "25.00");
        claim(capped, USER, "e1");
        claim(capped, USER, "e2");
        assertEquals("COUPON_CAMPAIGN_SOLD_OUT",
                assertThrows(ApiException.class, () -> claim(capped, USER, "e3")).code());
        assertEquals(new BigDecimal("20.00"), db.jdbc().queryForObject(
                "SELECT issued_amount FROM merchant_coupon_budget WHERE campaign_id=?",
                BigDecimal.class, capped));
        assertEquals(2, count("SELECT COUNT(*) FROM merchant_coupon_budget_ledger WHERE"
                + " campaign_id=? AND entry_type='RESERVE' AND amount=10.00", capped));
    }

    @Test
    void platformCampaignsKeepOneClaimPerUserAndStayOutOfTheMerchantLists() {
        long template = NUMBERS.incrementAndGet();
        long platform = NUMBERS.incrementAndGet();
        db.jdbc().update("INSERT INTO coupon_template(id,name,status,total_stock,issued_count,"
                + "valid_start_at,valid_end_at,rule_json,version,created_at,updated_at) VALUES(?,?,'ACTIVE',"
                + "NULL,0,'2020-01-01 00:00:00','2099-12-31 00:00:00',?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                template, "平台券", "{\"amountOff\":\"5.00\",\"typeLabel\":\"无门槛券\"}");
        db.jdbc().update("INSERT INTO coupon_campaign(id,name,template_id,audience_type,per_user_limit,"
                + "total_limit,budget_limit,issued_count,issued_amount,start_at,end_at,claim_method,status,"
                + "version,created_by_id,created_at,updated_at) VALUES(?,'平台活动',?,'ALL',1,100,500.00,0,0.00,"
                + "'2020-01-01 00:00:00','2099-12-31 00:00:00','ACTIVE_CLAIM','ACTIVE',1,1,UTC_TIMESTAMP(3),"
                + "UTC_TIMESTAMP(3))", platform, template);
        claim(platform, USER, "p1");
        assertEquals("COUPON_CLAIMED", assertThrows(ApiException.class,
                () -> claim(platform, USER, "p2")).code());

        live(MERCHANT_A, STORE_A, "商家活动", "10.00", null, 1, 5, "50.00");
        // The contract-72 operator list still answers platform campaigns only.
        assertEquals(1L, campaigns.listCampaigns(new AdminCouponCampaignApi.ListQuery(null, 1, 20,
                new AdminCouponCampaignApi.AdminAuthorization("s", 1, "1"), new QueryContext(null,
                        OperatorType.PLATFORM_OPERATOR, "1"))).total());
        assertEquals("PLATFORM", campaigns.getCampaign(Long.toString(platform), null,
                new QueryContext(null, null, null)).fundingType());
    }

    // ------------------------------------------------------------------ budget ledger

    @Test
    void budgetLedgerBooksUseAndUnuseExactlyOnce() {
        long campaign = live(MERCHANT_A, STORE_A, "账本流转", "10.00", null, 1, 5, "50.00");
        CouponClaimApi.ClaimReceipt receipt = claim(campaign, USER, "b1");
        long coupon = Long.parseLong(receipt.couponInstanceId());
        long orderId = NUMBERS.incrementAndGet();
        freeze(coupon, orderId, MERCHANT_A, STORE_A);
        lifecycle.consume(new com.petplatform.coupon.api.command.CouponCommandApi.CouponConsumeCommand(
                userCmd(), Long.toString(orderId), Long.toString(STORE_A), instant()));

        paid.consume(paidEvent(orderId, coupon, NUMBERS.incrementAndGet()));
        paid.consume(paidEvent(orderId, coupon, NUMBERS.incrementAndGet())); // replay: no second entry
        assertEquals(new BigDecimal("10.00"), budget(campaign, "used_amount"));
        assertEquals(new BigDecimal("10.00"), budget(campaign, "issued_amount"));
        assertEquals(1, count("SELECT COUNT(*) FROM merchant_coupon_budget_ledger WHERE"
                + " campaign_id=? AND entry_type='USE' AND order_id=? AND request_id=?", campaign, orderId,
                "MCPB:USE:" + orderId));

        // An order without a coupon leaves the ledger untouched (RESERVE + USE so far).
        long noCouponOrder = NUMBERS.incrementAndGet();
        paid.consume(new DispatchedEvent(Long.toString(NUMBERS.incrementAndGet()), "OrderPaidEvent.v1", 1,
                OffsetDateTime.now(ZoneOffset.UTC), "ORDER", noCouponOrder, null,
                "{\"orderId\":\"" + noCouponOrder + "\",\"reservationId\":\"" + NUMBERS.incrementAndGet()
                        + "\",\"couponInstanceId\":null,\"paidAt\":\"2026-01-01T00:00:00.000Z\","
                        + "\"confirmDeadline\":\"2026-01-01T00:10:00.000Z\"}"));
        assertEquals(2, count("SELECT COUNT(*) FROM merchant_coupon_budget_ledger WHERE campaign_id=?", campaign));

        // Full refund restores the coupon and reverses the merchant spend; replays are no-ops.
        long refundId = NUMBERS.incrementAndGet();
        refund.consume(refundEvent(refundId, orderId, "FULL"));
        refund.consume(refundEvent(refundId, orderId, "FULL"));
        assertEquals(0, new BigDecimal("0.00").compareTo(budget(campaign, "used_amount")));
        assertEquals("AVAILABLE", db.jdbc().queryForObject(
                "SELECT status FROM coupon_instance WHERE id=?", String.class, coupon));
        assertEquals(1, count("SELECT COUNT(*) FROM merchant_coupon_budget_ledger WHERE"
                + " campaign_id=? AND entry_type='UNUSE' AND request_id=?", campaign, "MCPB:UNUSE:" + refundId));
    }

    // ------------------------------------------------------------------ freeze gate

    @Test
    void merchantCouponsOnlyFreezeForTheirOwnMerchant() {
        long campaign = live(MERCHANT_A, STORE_A, "范围闸门", "10.00", null, 1, 5, "50.00");
        long coupon = Long.parseLong(claim(campaign, USER, "g1").couponInstanceId());
        long foreignOrder = NUMBERS.incrementAndGet();
        ApiException mismatch = assertThrows(ApiException.class,
                () -> freeze(coupon, foreignOrder, MERCHANT_B, STORE_B));
        assertEquals("COUPON_SCOPE_MISMATCH", mismatch.code());
        assertEquals("AVAILABLE", db.jdbc().queryForObject(
                "SELECT status FROM coupon_instance WHERE id=?", String.class, coupon));
        long ownOrder = NUMBERS.incrementAndGet();
        var frozen = freeze(coupon, ownOrder, MERCHANT_A, STORE_A);
        assertEquals("MERCHANT", frozen.fundingType());
        assertEquals(new BigDecimal("10.00"), frozen.discountAmount());
    }

    @Test
    void platformCouponsFreezeUnchangedAndReportPlatformFunding() {
        long template = NUMBERS.incrementAndGet();
        db.jdbc().update("INSERT INTO coupon_template(id,name,status,total_stock,issued_count,"
                + "valid_start_at,valid_end_at,rule_json,version,created_at,updated_at) VALUES(?,?,'ACTIVE',"
                + "NULL,0,'2020-01-01 00:00:00','2099-12-31 00:00:00',?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                template, "平台券", "{\"amountOff\":\"5.00\",\"typeLabel\":\"无门槛券\"}");
        long coupon = NUMBERS.incrementAndGet();
        db.jdbc().update("INSERT INTO coupon_instance(id,coupon_template_id,user_id,status,order_id,"
                + "frozen_at,freeze_expire_at,used_at,original_expire_at,expire_at,version,created_at,"
                + "updated_at) VALUES(?,?,?,'AVAILABLE',NULL,NULL,NULL,NULL,'2099-12-31 00:00:00',"
                + "'2099-12-31 00:00:00',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", coupon, template, USER);
        long order = NUMBERS.incrementAndGet();
        var frozen = freeze(coupon, order, MERCHANT_B, STORE_B); // any merchant: no scope gate
        assertEquals("PLATFORM", frozen.fundingType());
        assertEquals(new BigDecimal("5.00"), frozen.discountAmount());
    }

    // ------------------------------------------------------------------ reads

    @Test
    void overviewAndOperatorListReadRealCounters() {
        live(MERCHANT_A, STORE_A, "统计活动", "10.00", null, 2, 5, "50.00");
        long pending = createId(MERCHANT_A, STORE_A, "待审活动", "10.00", null, 1, 5, "50.00");
        merchant.submit(status(pending, MERCHANT_A, STORE_A, 1));
        create(MERCHANT_B, STORE_B, "别家活动", "10.00", null, 1, 5, "50.00");
        MerchantCouponApi.Overview overview = merchant.overview(new MerchantCouponApi.ScopeQuery(
                Long.toString(MERCHANT_A), Long.toString(STORE_A), userQuery()));
        assertEquals(2, overview.totalCampaignCount());
        assertEquals(1, overview.activeCampaignCount());
        assertEquals(1, overview.pendingReviewCount());
        assertEquals(0, overview.monthIssuedCount());
        assertEquals("0.0000", overview.redemptionRate().toPlainString());

        MerchantCouponAdminApi.Page review = admin.list(new MerchantCouponAdminApi.ListQuery(null, null, 1,
                20, new AdminAuthorization("s", 1, "1"), new QueryContext(null, OperatorType.PLATFORM_OPERATOR, "1")));
        assertEquals(3L, review.total());
        MerchantCouponAdminApi.Item item = review.items().stream()
                .filter(i -> i.merchantId().equals(Long.toString(MERCHANT_B))).findFirst().orElseThrow();
        assertEquals("商家甲", item.merchantName());
        assertEquals("门店甲", item.storeName());
        assertEquals("NO_THRESHOLD", item.couponType());
    }

    // ------------------------------------------------------------------ helpers

    private static MerchantAdmissionQueryApi admissions(String membership) {
        return new MerchantAdmissionQueryApi() {
            @Override
            public MerchantMembershipPageDTO listMemberships(MerchantMembershipQuery query) {
                throw new UnsupportedOperationException("not used by the merchant coupon face");
            }

            @Override
            public MerchantAdmissionDTO getAdmission(MerchantAdmissionQuery query) {
                return new MerchantAdmissionDTO(query.merchantId(), query.storeId(), membership,
                        "ALLOWED", OffsetDateTime.now(ZoneOffset.UTC), "v1", null, null, "ACTIVE",
                        "ACTIVE", true, List.of(), List.of(), List.of());
            }
        };
    }

    private static final MerchantQueryApi merchants = new MerchantQueryApi() {
        @Override
        public MerchantStoreDTO getStore(StoreIdQuery query) {
            return new MerchantStoreDTO(Long.toString(MERCHANT_A), query.storeId(), "商家甲", "门店甲",
                    null, null, null, null, null, null, null);
        }

        @Override
        public MerchantOrderEligibilityDTO checkOrderEligibility(MerchantOrderEligibilityQuery query) {
            throw new UnsupportedOperationException("not used by the merchant coupon face");
        }

        @Override
        public MerchantStaffDTO getStaff(MerchantStaffQuery query) {
            throw new UnsupportedOperationException("not used by the merchant coupon face");
        }
    };

    // The C faces read the store through the public display projection, never the owner-scoped
    // MerchantQueryApi above (a shopper is not the store owner).
    private static final MerchantStoreDisplayApi displays = new MerchantStoreDisplayApi() {
        @Override
        public MerchantStoreDisplayDTO getDisplayStore(MerchantStoreDisplayQuery query) {
            return new MerchantStoreDisplayDTO(Long.toString(MERCHANT_A), query.storeId(), "商家甲", "门店甲",
                    null, null, null, null, null, null, null, null);
        }

        @Override
        public MerchantStoreDisplayPageDTO pageDisplayStores(MerchantStoreDisplayPageQuery query) {
            throw new UnsupportedOperationException("not used by the merchant coupon face");
        }
    };

    private com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore store() {
        return new com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore(db.dataSource(), IDS);
    }

    private static MerchantCouponApi.Input input(String name, String amountOff, String threshold,
            int perUser, int total, String budget) {
        return new MerchantCouponApi.Input(name, new BigDecimal(amountOff),
                threshold == null ? null : new BigDecimal(threshold), total, perUser,
                new BigDecimal(budget), START, END, "测试活动");
    }

    private static CommandContext userCmd() {
        return new CommandContext("req:" + NUMBERS.incrementAndGet(), null, OperatorType.USER,
                Long.toString(USER), "test");
    }

    private static QueryContext userQuery() {
        return new QueryContext(null, OperatorType.USER, Long.toString(USER));
    }

    private static MerchantCouponApi.StatusCommand status(long id, long merchant, long store, long version) {
        return new MerchantCouponApi.StatusCommand(Long.toString(id), Long.toString(merchant),
                Long.toString(store), userCmd(), version);
    }

    private static MerchantCouponApi.GetQuery get(long id, long merchant, long store) {
        return new MerchantCouponApi.GetQuery(Long.toString(id), Long.toString(merchant),
                Long.toString(store), userQuery());
    }

    private static MerchantCouponAdminApi.ReviewCommand review(long id, long version, String reason) {
        return new MerchantCouponAdminApi.ReviewCommand(Long.toString(id), new CommandContext(
                "rev:" + NUMBERS.incrementAndGet(), null, OperatorType.PLATFORM_OPERATOR, "9001", "test"),
                new AdminAuthorization("s", 1, "9001"), version, reason);
    }

    private MerchantCouponApi.Receipt create(long merchantId, long storeId, String name, String amountOff,
            String threshold, int perUser, int total, String budget) {
        return merchant.create(new MerchantCouponApi.CreateCommand(Long.toString(merchantId),
                Long.toString(storeId), userCmd(), input(name, amountOff, threshold, perUser, total, budget)));
    }

    private long createId(long merchantId, long storeId, String name, String amountOff, String threshold,
            int perUser, int total, String budget) {
        return Long.parseLong(create(merchantId, storeId, name, amountOff, threshold, perUser, total, budget)
                .campaignId());
    }

    /** Created, submitted and approved: the campaign is ACTIVE with a live template. */
    private long live(long merchantId, long storeId, String name, String amountOff, String threshold,
            int perUser, int total, String budget) {
        long id = createId(merchantId, storeId, name, amountOff, threshold, perUser, total, budget);
        merchant.submit(status(id, merchantId, storeId, 1));
        assertEquals("ACTIVE", admin.approve(review(id, 2, null)).status());
        return id;
    }

    private CouponClaimApi.ClaimReceipt claim(long campaign, long user, String key) {
        return campaigns.claim(new CouponClaimApi.ClaimCommand(Long.toString(campaign),
                Long.toString(user), key + ":" + NUMBERS.incrementAndGet(), null));
    }

    private com.petplatform.coupon.api.command.CouponCommandApi.CouponFreezeResult freeze(long coupon,
            long order, long merchant, long store) {
        return lifecycle.freeze(new com.petplatform.coupon.api.command.CouponCommandApi.CouponFreezeCommand(
                userCmd(), Long.toString(order), Long.toString(coupon), Long.toString(USER),
                Long.toString(store), new BigDecimal("100.00"), instant().plusMinutes(10),
                Long.toString(merchant)));
    }

    /** Millisecond precision on purpose: the lifecycle commands reject finer instants. */
    private static OffsetDateTime instant() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    private static DispatchedEvent paidEvent(long orderId, long coupon, long eventId) {
        return new DispatchedEvent(Long.toString(eventId), "OrderPaidEvent.v1", 1,
                OffsetDateTime.now(ZoneOffset.UTC), "ORDER", orderId, null,
                "{\"orderId\":\"" + orderId + "\",\"reservationId\":\"" + NUMBERS.incrementAndGet()
                        + "\",\"couponInstanceId\":\"" + coupon + "\",\"paidAt\":\"2026-01-01T00:00:00.000Z\","
                        + "\"confirmDeadline\":\"2026-01-01T00:10:00.000Z\"}");
    }

    private static DispatchedEvent refundEvent(long refundId, long orderId, String type) {
        return new DispatchedEvent(Long.toString(refundId), "RefundSucceededEvent.v1", 1,
                OffsetDateTime.now(ZoneOffset.UTC), "REFUND", refundId, null,
                "{\"refundOrderId\":\"" + refundId + "\",\"refundNo\":\"1\",\"orderId\":\"" + orderId
                        + "\",\"refundType\":\"" + type + "\",\"refundSource\":\"MERCHANT_APPROVED\","
                        + "\"refundAmount\":90.00,\"originalPaidAmount\":90.00,"
                        + "\"channelRefundNo\":\"CH1\",\"succeededAt\":\"2026-01-02T00:00:00.000Z\"}");
    }

    private BigDecimal budget(long campaign, String column) {
        return db.jdbc().queryForObject("SELECT " + column + " FROM merchant_coupon_budget WHERE campaign_id=?",
                BigDecimal.class, campaign);
    }

    private int count(String sql, Object... args) {
        return db.jdbc().queryForObject(sql, Integer.class, args);
    }
}
