package com.petplatform.boot.auth;

import com.petplatform.boot.config.CAuthConfiguration;
import com.petplatform.boot.config.CAuthProperties;
import com.petplatform.user.biz.application.UserAuthService;
import com.petplatform.user.biz.infrastructure.provider.RedisMiniAuthVolatileStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The C-end auth slice is strictly opt-in: defaults are off, nothing assembles without explicit
 * enablement, and enabling without an authorized WechatSessionProvider fails startup loudly.
 */
class CAuthDisabledTest {

    @Test
    void defaultsKeepEverythingOffAndNoDependencyIsCreated() {
        CAuthProperties defaults = new CAuthProperties();
        assertFalse(defaults.isEnabled());
        assertEquals("auth001c:", defaults.getCachePrefix());
        assertEquals(600, defaults.getAttemptTtlSeconds());
        // 2026-11-18 用户裁决：小程序 access 会话默认 7 天（原 15 分钟导致频繁静默重登）。
        assertEquals(604_800, defaults.getAccessTtlSeconds());
        new ApplicationContextRunner()
                .withUserConfiguration(CAuthConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(UserAuthService.class).isEmpty());
                    assertTrue(context.getBeansOfType(RedisMiniAuthVolatileStore.class).isEmpty());
                });
    }

    @Test
    void enablingWithoutAnAuthorizedProviderFailsStartup() {
        // pet.auth.c.enabled=true but no WechatSessionProvider bean and no Redis configured:
        // the context must fail fast instead of serving half-assembled auth.
        new ApplicationContextRunner()
                .withUserConfiguration(CAuthConfiguration.class)
                .withPropertyValues("pet.auth.c.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
