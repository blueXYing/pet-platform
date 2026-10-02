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
 * SCH-004 (W2-SCHW-012 default-off row): with pet.schedule.command.enabled=false neither the
 * write assembly nor the merchant routes exist, so /api/v1/merchant/stores/{storeId}/*
 * availability-windows, /api/v1/merchant/staff/{staffId}/availability-windows and
 * /api/v1/merchant/staff/{staffId}/service-capabilities stay unreachable until the switch is
 * explicitly enabled together with the reservation protection foundation.
 */
class ScheduleWriteDisabledTest {

    @Test
    void defaultsKeepTheScheduleWriteSliceUnassembled() {
        new ApplicationContextRunner()
                .withUserConfiguration(ScheduleWriteConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(ScheduleMerchantCommandApiImpl.class)
                            .isEmpty());
                    assertTrue(context.getBeansOfType(ScheduleMerchantQueryApiImpl.class)
                            .isEmpty());
                });
        new ApplicationContextRunner()
                .withUserConfiguration(MerchantScheduleController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(MerchantScheduleController.class)
                            .isEmpty());
                });
    }
}
