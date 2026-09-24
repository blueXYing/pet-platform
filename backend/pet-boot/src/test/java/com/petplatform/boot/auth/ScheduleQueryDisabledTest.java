package com.petplatform.boot.auth;

import com.petplatform.boot.adapter.web.c.CScheduleController;
import com.petplatform.boot.config.ScheduleQueryConfiguration;
import com.petplatform.schedule.biz.apiimpl.ScheduleQueryApiImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SCH-001 (W2-SCH-007, ruling 2026-09-23): the availability slice is strictly opt-in. With the
 * default pet.schedule.query.enabled=false neither the query assembly nor the route controller
 * exists, so GET /api/v1/c/services/{serviceId}/availability stays unreachable (no mapping; the
 * security chain answers denyAll) until the switch is explicitly enabled.
 */
class ScheduleQueryDisabledTest {

    @Test
    void defaultsKeepTheScheduleQuerySliceUnassembled() {
        new ApplicationContextRunner()
                .withUserConfiguration(ScheduleQueryConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(ScheduleQueryApiImpl.class).isEmpty());
                });
        new ApplicationContextRunner()
                .withUserConfiguration(CScheduleController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(CScheduleController.class).isEmpty());
                });
    }
}
