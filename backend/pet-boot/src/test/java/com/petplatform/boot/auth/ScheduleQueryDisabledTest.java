package com.petplatform.boot.auth;

import com.petplatform.boot.adapter.web.c.CScheduleController;
import com.petplatform.boot.config.ScheduleQueryConfiguration;
import com.petplatform.schedule.biz.apiimpl.ScheduleQueryApiImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SCH-001 (W2-SCH-007, ruling 2026-09-23): the availability slice stays fail-closed when the
 * switch is explicitly off. With pet.schedule.query.enabled=false neither the query assembly
 * nor the route controller exists, so GET /api/v1/c/services/{serviceId}/availability stays
 * unreachable (no mapping; the security chain answers denyAll). Ruling 35 (2026-10-16) flips
 * the platform capability switches to default ON, so this check closes the switch explicitly
 * instead of relying on the absent-property default.
 */
class ScheduleQueryDisabledTest {

    @Test
    void explicitOffKeepsTheScheduleQuerySliceUnassembled() {
        new ApplicationContextRunner()
                .withUserConfiguration(ScheduleQueryConfiguration.class)
                .withPropertyValues("pet.schedule.query.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(ScheduleQueryApiImpl.class).isEmpty());
                });
        new ApplicationContextRunner()
                .withUserConfiguration(CScheduleController.class)
                .withPropertyValues("pet.schedule.query.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(CScheduleController.class).isEmpty());
                });
    }
}
