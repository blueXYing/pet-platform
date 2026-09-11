package com.petplatform.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** These are intentionally invalid synthetic classes, never real APIs or production sources. */
class ArchitectureRulesFixtureTest {
    @TempDir Path temp;

    void verify(ArchRule rule, boolean rejected, String diagnostic, Map<String, String> sources) throws Exception {
        var args = new ArrayList<String>();
        args.addAll(java.util.List.of("--release", "21", "-d", temp.toString()));
        for (var source : sources.entrySet()) {
            Path file = temp.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)),
                "Fixture must compile before testing the architecture rule");
        var imported = new ClassFileImporter().importPath(temp);
        assertFalse(imported.isEmpty(), "Empty fixture is not evidence");
        var result = rule.evaluate(imported);
        assertEquals(rejected, result.hasViolation(), result.getFailureReport().toString());
        if (rejected) assertTrue(result.getFailureReport().toString().contains(diagnostic),
                result.getFailureReport().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OrderRepository", "OrderMapper", "OrderDO", "OrderEntity"})
    void arch002_rejects_cross_biz_persistence_even_outside_conventional_packages(String type) throws Exception {
        verify(ArchitectureRules.CROSS_PERSISTENCE, true, "ARCH-002", Map.of(
            "com.petplatform.order.biz.hidden." + type,
            "package com.petplatform.order.biz.hidden; public class " + type + " {}",
            "com.petplatform.refund.biz.RefundService",
            "package com.petplatform.refund.biz; public class RefundService { com.petplatform.order.biz.hidden." + type + " dependency; }"));
    }

    @Test void arch002_rejects_persistence_package_without_suffix() throws Exception {
        verify(ArchitectureRules.CROSS_PERSISTENCE, true, "ARCH-002", Map.of(
            "com.petplatform.order.biz.infrastructure.persistence.StoredOrder",
            "package com.petplatform.order.biz.infrastructure.persistence; public class StoredOrder {}",
            "com.petplatform.admin.biz.Report",
            "package com.petplatform.admin.biz; public class Report { com.petplatform.order.biz.infrastructure.persistence.StoredOrder order; }"));
    }

    @Test void arch002_allows_local_persistence_and_cross_module_api() throws Exception {
        verify(ArchitectureRules.CROSS_PERSISTENCE, false, "", Map.of(
            "com.petplatform.order.biz.OrderRepository", "package com.petplatform.order.biz; public class OrderRepository {}",
            "com.petplatform.order.biz.OrderService", "package com.petplatform.order.biz; public class OrderService { OrderRepository local; }",
            "com.petplatform.order.api.OrderApi", "package com.petplatform.order.api; public interface OrderApi {}",
            "com.petplatform.refund.biz.RefundService", "package com.petplatform.refund.biz; public class RefundService { com.petplatform.order.api.OrderApi api; }"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"org.apache.ibatis.annotations.Mapper", "com.baomidou.mybatisplus.annotation.TableName", "jakarta.persistence.Entity"})
    void arch003_rejects_real_framework_annotation_names(String annotation) throws Exception {
        int dot = annotation.lastIndexOf('.');
        verify(ArchitectureRules.API_PURITY, true, "ARCH-003", Map.of(
            annotation, "package " + annotation.substring(0, dot) + "; public @interface " + annotation.substring(dot + 1) + " {}",
            "com.petplatform.order.api.BadContract", "package com.petplatform.order.api; @" + annotation + " public class BadContract {}"));
    }

    @Test void arch003_rejects_api_implementation() throws Exception {
        verify(ArchitectureRules.API_PURITY, true, "ARCH-003", Map.of(
            "com.petplatform.order.api.OrderServiceImpl", "package com.petplatform.order.api; public class OrderServiceImpl {}"));
    }

    @Test void arch003_allows_pure_contract() throws Exception {
        verify(ArchitectureRules.API_PURITY, false, "", Map.of(
            "com.petplatform.order.api.OrderDTO", "package com.petplatform.order.api; public record OrderDTO(String id) {}"));
    }

    @Test void arch004_rejects_direct_repository_update() throws Exception {
        verify(ArchitectureRules.CONTROLLER_BOUNDARY, true, "ARCH-004", Map.of(
            "com.petplatform.order.biz.OrderRepository", "package com.petplatform.order.biz; public class OrderRepository { public void updateStatus() {} }",
            "com.petplatform.order.biz.adapter.web.OrderController", "package com.petplatform.order.biz.adapter.web; public class OrderController { void update(com.petplatform.order.biz.OrderRepository r) { r.updateStatus(); } }"));
    }

    @Test void arch004_recognizes_annotation_outside_web_package() throws Exception {
        verify(ArchitectureRules.CONTROLLER_BOUNDARY, true, "ARCH-004", Map.of(
            "org.springframework.web.bind.annotation.RestController", "package org.springframework.web.bind.annotation; public @interface RestController {}",
            "com.petplatform.order.biz.OrderMapper", "package com.petplatform.order.biz; public interface OrderMapper {}",
            "com.petplatform.order.biz.Endpoint", "package com.petplatform.order.biz; @org.springframework.web.bind.annotation.RestController public class Endpoint { OrderMapper mapper; }"));
    }

    @Test void arch004_allows_delegation_to_application() throws Exception {
        verify(ArchitectureRules.CONTROLLER_BOUNDARY, false, "", Map.of(
            "com.petplatform.order.biz.application.OrderService", "package com.petplatform.order.biz.application; public class OrderService { public void confirm() {} }",
            "com.petplatform.order.biz.adapter.web.OrderController", "package com.petplatform.order.biz.adapter.web; public class OrderController { void confirm(com.petplatform.order.biz.application.OrderService service) { service.confirm(); } }"));
    }

    @Test void domain_rejects_infrastructure() throws Exception {
        verify(ArchitectureRules.DOMAIN_BOUNDARY, true, "DOMAIN", Map.of(
            "com.petplatform.order.biz.infrastructure.Storage", "package com.petplatform.order.biz.infrastructure; public class Storage {}",
            "com.petplatform.order.biz.domain.Order", "package com.petplatform.order.biz.domain; public class Order { com.petplatform.order.biz.infrastructure.Storage storage; }"));
    }
}
