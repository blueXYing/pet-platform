package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contract-79 §5 per-store merchant coupon list over the FULL security chain. #236 registered the
 * controller but missed both chain rules (the optional-session read and the GET whitelist), so
 * real traffic fell into anyRequest().denyAll(); the standalone controller test could not see it.
 */
class MerchantCouponListChainHttpTest {

    @Test
    void anonymousBrowsingAndCarriedSessionsBothReachTheList() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.coupon.merchant.enabled", true))) {
            // The HTTP-slice schema carries no marketing storage; the list reads coupon_campaign.
            f.ordinary.t.r.f.f.db.script("63-Marketing-Storage-v0.1.sql");
            String path = "/c/coupon-campaigns?storeId=" + AfterSaleHttpFixture.STORE;
            // Anonymous browse (STR-D8): 200, never the chain's 403.
            var anonymous = f.send("GET", path, (Object) null, (String) null);
            assertEquals(200, anonymous.status(), anonymous.toString());
            assertEquals(Boolean.TRUE, anonymous.envelope().get("success"));
            assertEquals("no-store", anonymous.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(((List<?>) anonymous.data().get("items")).isEmpty());
            // A carried MINIAPP session is attached and validated; an unusable bearer stays 401.
            var signedIn = f.send("GET", path, null, f.buyerToken);
            assertEquals(200, signedIn.status(), signedIn.toString());
            assertTrue(((List<?>) signedIn.data().get("items")).isEmpty());
            assertEquals(401, f.send("GET", path, null, "not-a-session").status());
            // The store is the judge of the read: an unknown store answers the kernel's 404.
            assertEquals(404, f.send("GET", "/c/coupon-campaigns?storeId=9007199254740993", (Object) null, (String) null).status());
        }
    }
}
