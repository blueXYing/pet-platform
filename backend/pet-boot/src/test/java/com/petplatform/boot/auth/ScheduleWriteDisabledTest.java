package com.petplatform.boot.auth;

import com.petplatform.boot.adapter.web.merchant.MerchantScheduleController;
import com.petplatform.boot.config.ScheduleWriteConfiguration;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantCommandApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantQueryApiImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCH-004 (W2-SCHW-012): with pet.schedule.command.enabled / pet.schedule.command.http.enabled
 * explicitly false, neither the write assembly nor the merchant routes exist, so
 * /api/v1/merchant/stores/{storeId}/* availability-windows,
 * /api/v1/merchant/staff/{staffId}/availability-windows and
 * /api/v1/merchant/staff/{staffId}/service-capabilities stay unreachable. Ruling 35
 * (2026-10-16) flips the platform capability switches to default ON, so this fail-closed
 * check closes the switches explicitly instead of relying on the absent-property default.
 */
class ScheduleWriteDisabledTest {

    @Test
    void explicitOffKeepsTheScheduleWriteSliceUnassembled() {
        new ApplicationContextRunner()
                .withUserConfiguration(ScheduleWriteConfiguration.class)
                .withPropertyValues("pet.schedule.command.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(ScheduleMerchantCommandApiImpl.class)
                            .isEmpty());
                    assertTrue(context.getBeansOfType(ScheduleMerchantQueryApiImpl.class)
                            .isEmpty());
                });
        new ApplicationContextRunner()
                .withUserConfiguration(MerchantScheduleController.class)
                .withPropertyValues("pet.schedule.command.http.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(MerchantScheduleController.class)
                            .isEmpty());
                });
    }
}
