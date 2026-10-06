package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.petplatform.boot.adapter.web.merchant.MerchantScheduleController;
import java.lang.reflect.Field;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Contract 53 §3.1 pins the availability-windows status filter to OPEN/CLOSED/SOLD_OUT. The
 * shipped controller once validated OPEN/CLOSED only, so a status=SOLD_OUT filter answered 400
 * at the HTTP layer while the biz layer already accepted it (disclosed by the M-side schedule
 * page slice); this pin keeps the HTTP surface aligned with the contract.
 */
class MerchantScheduleWindowStatusContractTest {

    @Test
    @SuppressWarnings("unchecked")
    void windowStatusFilterMatchesContract53() throws Exception {
        Field field = MerchantScheduleController.class.getDeclaredField("WINDOW_STATUSES");
        field.setAccessible(true);
        assertEquals(Set.of("OPEN", "CLOSED", "SOLD_OUT"), (Set<String>) field.get(null));
    }
}
