package com.petplatform.boot.auth;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.boot.config.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class AdminAuthDisabledTest {
    @Test void authenticationAndItsMigrationAreOptInAndDoNotCreateDependenciesByDefault(){
        var defaults=new AdminAuthProperties();assertFalse(defaults.isEnabled());assertFalse(defaults.isMigrationEnabled());
        new ApplicationContextRunner().withUserConfiguration(AdminAuthConfiguration.class).run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(AdminAuthService.class).isEmpty());});
    }
    @Test void migrationSwitchAloneCannotEnableAuthentication(){
        new ApplicationContextRunner().withPropertyValues("pet.auth.admin.migration-enabled=true")
                .withUserConfiguration(AdminAuthConfiguration.class).run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(AdminAuthService.class).isEmpty());});
    }
    @Test void authDdlIsMirroredExactlyAndNotInDefaultMigrationScan()throws Exception{
        Path p=Path.of("").toAbsolutePath();while(p!=null&&!Files.isDirectory(p.resolve("docs/03-database")))p=p.getParent();assertNotNull(p);
        assertArrayEquals(Files.readAllBytes(p.resolve("docs/03-database/26-Admin-Auth-Schema-v0.1.sql")),Files.readAllBytes(p.resolve("backend/pet-boot/src/main/resources/db/admin-auth-migration/V26__admin_auth.sql")));
        try(var files=Files.walk(p.resolve("backend/pet-boot/src/main/resources/db/migration"))){assertTrue(files.noneMatch(f->f.getFileName().toString().contains("V26")));}
    }
}
