package com.petplatform.coupon.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.dto.CouponInstanceDTO;
import com.petplatform.coupon.api.query.CouponQueryApi.MyCouponListQuery;
import com.petplatform.coupon.api.query.CouponQueryApi.MyCouponQuery;
import com.petplatform.coupon.biz.apiimpl.CouponQueryApiImpl;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CCR-C006-COUPON-POINTS-READ-001 P1 acceptance against isolated real MySQL (schema 06 section
 * 8): three display buckets with FROZEN/RISK_FROZEN never surfacing (D2), stable expire_at+id
 * pagination, the D1 defensive rule_json projection (missing/wrong-typed members read as null),
 * and the uniform anti-enumeration 404 for foreign/absent/malformed ids.
 */
class CouponQueryMySqlTest {

    private static final long OWNER = 9_400_000_000_000_201L;
    private static final long OTHER = 9_400_000_000_000_202L;
    private static final long TEMPLATE_FULL = 9_400_000_000_000_101L;
    private static final long TEMPLATE_EMPTY = 9_400_000_000_000_102L;
    private static final long TEMPLATE_WRONG_TYPES = 9_400_000_000_000_103L;

    // Instance ids: assigned so that expire_at ASC, id ASC ordering is observable per bucket.
    private static final long AVAILABLE_1 = 9_400_000_000_000_301L;
    private static final long AVAILABLE_2 = 9_400_000_000_000_302L;
    private static final long AVAILABLE_3 = 9_400_000_000_000_303L;
    private static final long USED_1 = 9_400_000_000_000_311L;
    private static final long EXPIRED_1 = 9_400_000_000_000_321L;
    private static final long FROZEN_1 = 9_400_000_000_000_331L;
    private static final long RISK_FROZEN_1 = 9_400_000_000_000_341L;
    private static final long OTHER_AVAILABLE = 9_400_000_000_000_351L;

    private CouponQueryMySqlTestDatabase database;
    private CouponQueryApiImpl api;

    @BeforeEach
    void start() throws Exception {
        database = new CouponQueryMySqlTestDatabase();
        seed(database);
        api = new CouponQueryApiImpl(database.dataSource());
    }

    @AfterEach
    void stop() {
        if (database != null) {
            database.close();
        }
    }

    private static void seed(CouponQueryMySqlTestDatabase db) {
        var jdbc = db.jdbc();
        jdbc.update("INSERT INTO coupon_template(id,name,status,total_stock,issued_count,"
                        + "valid_start_at,valid_end_at,rule_json,version,created_at,updated_at)"
                        + " VALUES(?,?,'ACTIVE',100,0,'2026-01-01 00:00:00.000','2026-12-31 23:59:59.000',?," +
                        "0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                TEMPLATE_FULL, "新客满减券",
                "{\"amountOff\":\"20\",\"thresholdAmount\":\"100.00\",\"scopeSummary\":\"全场通用\",\"typeLabel\":\"满减券\"}");
        jdbc.update("INSERT INTO coupon_template(id,name,status,total_stock,issued_count,"
                        + "valid_start_at,valid_end_at,rule_json,version,created_at,updated_at)"
                        + " VALUES(?,?,'ACTIVE',100,0,'2026-01-01 00:00:00.000','2026-12-31 23:59:59.000',?," +
                        "0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                TEMPLATE_EMPTY, "无规则券", "{}");
        jdbc.update("INSERT INTO coupon_template(id,name,status,total_stock,issued_count,"
                        + "valid_start_at,valid_end_at,rule_json,version,created_at,updated_at)"
                        + " VALUES(?,?,'ACTIVE',100,0,'2026-01-01 00:00:00.000','2026-12-31 23:59:59.000',?," +
                        "0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                TEMPLATE_WRONG_TYPES, "脏规则券",
                "{\"amountOff\":\"abc\",\"thresholdAmount\":null,\"scopeSummary\":123,\"typeLabel\":\"\"}");

        instance(jdbc, AVAILABLE_1, TEMPLATE_FULL, OWNER, "AVAILABLE", null,
                "2026-11-01 00:00:00.000", "2026-11-01 00:00:00.000");
        instance(jdbc, AVAILABLE_2, TEMPLATE_EMPTY, OWNER, "AVAILABLE", null,
                "2026-10-15 00:00:00.000", "2026-10-15 00:00:00.000");
        // Same expire_at as AVAILABLE_2: the id ASC tie-break must order AVAILABLE_2 first.
        instance(jdbc, AVAILABLE_3, TEMPLATE_WRONG_TYPES, OWNER, "AVAILABLE", null,
                "2026-10-15 00:00:00.000", "2026-10-15 00:00:00.000");
        instance(jdbc, USED_1, TEMPLATE_FULL, OWNER, "USED",
                "2026-10-06 02:30:00.000", "2026-11-01 00:00:00.000", "2026-11-01 00:00:00.000");
        instance(jdbc, EXPIRED_1, TEMPLATE_FULL, OWNER, "EXPIRED", null,
                "2026-09-01 00:00:00.000", "2026-09-01 00:00:00.000");
        instance(jdbc, FROZEN_1, TEMPLATE_FULL, OWNER, "FROZEN", null,
                "2026-11-01 00:00:00.000", "2026-11-01 00:00:00.000");
        instance(jdbc, RISK_FROZEN_1, TEMPLATE_FULL, OWNER, "RISK_FROZEN", null,
                "2026-11-01 00:00:00.000", "2026-11-01 00:00:00.000");
        instance(jdbc, OTHER_AVAILABLE, TEMPLATE_FULL, OTHER, "AVAILABLE", null,
                "2026-11-01 00:00:00.000", "2026-11-01 00:00:00.000");
    }

    private static void instance(org.springframework.jdbc.core.JdbcTemplate jdbc, long id,
            long template, long user, String status, String usedAt, String expireAt,
            String originalExpireAt) {
        jdbc.update("INSERT INTO coupon_instance(id,coupon_template_id,user_id,status,order_id,"
                        + "frozen_at,freeze_expire_at,used_at,original_expire_at,expire_at,version,"
                        + "created_at,updated_at) VALUES(?,?,?,?,NULL,NULL,NULL,?,?,?,0,"
                        + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                id, template, user, status, usedAt, originalExpireAt, expireAt);
    }

    private static QueryContext asOwner() {
        return new QueryContext("test", OperatorType.USER, Long.toString(OWNER));
    }

    private static QueryContext asOther() {
        return new QueryContext("test", OperatorType.USER, Long.toString(OTHER));
    }

    private static List<String> ids(PageResult<CouponInstanceDTO> page) {
        return page.items().stream().map(CouponInstanceDTO::couponId).toList();
    }

    @Test
    void availableBucketReturnsOnlyOwnAvailableInstances() {
        PageResult<CouponInstanceDTO> page =
                api.listMyCoupons(new MyCouponListQuery("AVAILABLE", 1, 20, asOwner()));
        assertEquals(3, page.total());
        assertEquals(List.of("9400000000000302", "9400000000000303", "9400000000000301"), ids(page));
        for (CouponInstanceDTO item : page.items()) {
            assertNotEquals("FROZEN", item.status());
            assertNotEquals("RISK_FROZEN", item.status());
        }
    }

    @Test
    void usedAndExpiredBucketsSeparateAndFrozenNeverSurface() {
        PageResult<CouponInstanceDTO> used =
                api.listMyCoupons(new MyCouponListQuery("USED", 1, 20, asOwner()));
        assertEquals(1, used.total());
        assertEquals("9400000000000311", used.items().get(0).couponId());
        assertNotNull(used.items().get(0).usedAt());
        assertEquals("2026-10-06T02:30Z", used.items().get(0).usedAt().toString());

        PageResult<CouponInstanceDTO> expired =
                api.listMyCoupons(new MyCouponListQuery("EXPIRED", 1, 20, asOwner()));
        assertEquals(1, expired.total());
        assertEquals("9400000000000321", expired.items().get(0).couponId());
        assertNull(expired.items().get(0).usedAt());

        // D2: neither frozen state leaks through any selectable bucket, including empty ones.
        for (String bucket : new String[] {"AVAILABLE", "USED", "EXPIRED"}) {
            for (long subject : new long[] {OWNER, OTHER}) {
                PageResult<CouponInstanceDTO> page = api.listMyCoupons(new MyCouponListQuery(
                        bucket, 1, 50, new QueryContext("t", OperatorType.USER, Long.toString(subject))));
                for (CouponInstanceDTO item : page.items()) {
                    assertNotEquals("FROZEN", item.status(), bucket);
                    assertNotEquals("RISK_FROZEN", item.status(), bucket);
                }
            }
        }
    }

    @Test
    void paginationIsStableAcrossPagesWithExpireThenIdOrdering() {
        PageResult<CouponInstanceDTO> first =
                api.listMyCoupons(new MyCouponListQuery("AVAILABLE", 1, 2, asOwner()));
        PageResult<CouponInstanceDTO> second =
                api.listMyCoupons(new MyCouponListQuery("AVAILABLE", 2, 2, asOwner()));
        assertEquals(3, first.total());
        assertEquals(2, first.items().size());
        assertEquals(1, second.items().size());
        List<String> whole = new java.util.ArrayList<>(ids(first));
        whole.addAll(ids(second));
        assertEquals(List.of("9400000000000302", "9400000000000303", "9400000000000301"), whole);
        assertEquals(2, second.page());
    }

    @Test
    void ruleProjectionIsDefensiveAboutTheUnfrozenRuleJson() {
        // D1: full rule renders two-decimal amounts and bounded labels.
        CouponInstanceDTO full = api.getMyCoupon(new MyCouponQuery("9400000000000301", asOwner()));
        assertEquals("新客满减券", full.name());
        assertEquals("20.00", full.amountOff());
        assertEquals("100.00", full.thresholdAmount());
        assertEquals("全场通用", full.scopeSummary());
        assertEquals("满减券", full.typeLabel());
        assertEquals("2026-11-01", full.validTo().toString());

        // D1: an empty rule document must not fail the read; all four projections are null.
        CouponInstanceDTO empty = api.getMyCoupon(new MyCouponQuery("9400000000000302", asOwner()));
        assertEquals("无规则券", empty.name());
        assertNull(empty.amountOff());
        assertNull(empty.thresholdAmount());
        assertNull(empty.scopeSummary());
        assertNull(empty.typeLabel());

        // D1: wrong-typed members also read as null rather than failing or coercing.
        CouponInstanceDTO dirty = api.getMyCoupon(new MyCouponQuery("9400000000000303", asOwner()));
        assertNull(dirty.amountOff());
        assertNull(dirty.thresholdAmount());
        assertNull(dirty.scopeSummary());
        assertNull(dirty.typeLabel());
    }

    @Test
    void detailOfForeignOrAbsentCouponIsUniformNotFound() {
        // Foreign coupon: other user's AVAILABLE instance is invisible to the owner.
        ApiException foreign = assertThrows(ApiException.class,
                () -> api.getMyCoupon(new MyCouponQuery("9400000000000351", asOwner())));
        assertEquals(CommonApiCodes.NOT_FOUND, foreign.code());

        // Absent coupon: same code, so the two cases are indistinguishable (anti-enumeration).
        ApiException absent = assertThrows(ApiException.class,
                () -> api.getMyCoupon(new MyCouponQuery("9400000000000999", asOwner())));
        assertEquals(CommonApiCodes.NOT_FOUND, absent.code());
        assertEquals(foreign.code(), absent.code());

        // Frozen own coupon: no C-side route may confirm its existence either.
        ApiException frozen = assertThrows(ApiException.class,
                () -> api.getMyCoupon(new MyCouponQuery("9400000000000331", asOwner())));
        assertEquals(CommonApiCodes.NOT_FOUND, frozen.code());

        // Malformed ids are the same 404, never a 500.
        for (String malformed : new String[] {"abc", "0", "-1", null}) {
            ApiException invalid = assertThrows(ApiException.class,
                    () -> api.getMyCoupon(new MyCouponQuery(malformed, asOwner())));
            assertEquals(CommonApiCodes.NOT_FOUND, invalid.code());
        }

        // Owner still reads their own.
        assertEquals("9400000000000301",
                api.getMyCoupon(new MyCouponQuery("9400000000000301", asOwner())).couponId());
    }

    @Test
    void foreignSubjectSeesOnlyOwnRows() {
        PageResult<CouponInstanceDTO> page =
                api.listMyCoupons(new MyCouponListQuery("AVAILABLE", 1, 20, asOther()));
        assertEquals(1, page.total());
        assertEquals("9400000000000351", page.items().get(0).couponId());
    }

    @Test
    void invalidStatusBucketIsRejected() {
        for (String bucket : new String[] {"FROZEN", "RISK_FROZEN", "", "available", "x"}) {
            ApiException invalid = assertThrows(ApiException.class,
                    () -> api.listMyCoupons(new MyCouponListQuery(bucket, 1, 20, asOwner())));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, invalid.code());
        }
    }
}
