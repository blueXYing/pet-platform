package com.petplatform.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/** Rules apply to all imported production classes; empty layer selectors cannot pass silently. */
final class ArchitectureRules {
    private ArchitectureRules() {}

    static String owner(JavaClass type) {
        String[] parts = type.getPackageName().split("\\.");
        if (parts.length >= 3 && parts[0].equals("com") && parts[1].equals("petplatform")) return parts[2];
        return "";
    }

    static boolean persistence(JavaClass type) {
        String p = type.getPackageName();
        String n = type.getSimpleName();
        return p.matches(".*\\.(persistence|repository|mapper|entity|dao)(\\..*)?")
                || n.matches(".*(Repository|Mapper|Entity|DO|Dao|DAO)")
                || type.getAnnotations().stream().anyMatch(a -> a.getRawType().getName().matches(
                        "(org.apache.ibatis.annotations.Mapper|com.baomidou.mybatisplus.annotation.TableName|jakarta.persistence.Entity)"));
    }

    static boolean framework(JavaClass type) {
        return type.getName().matches("(org.springframework|org.mybatis|org.apache.ibatis|com.baomidou.mybatisplus|jakarta.persistence|javax.persistence)\\..*");
    }

    static boolean layer(JavaClass type, String suffix) {
        return type.getPackageName().endsWith(suffix) || type.getPackageName().contains(suffix + ".");
    }

    static boolean controller(JavaClass type) {
        return type.getPackageName().contains(".adapter.web") || type.getSimpleName().endsWith("Controller")
                || type.getAnnotations().stream().anyMatch(a -> a.getRawType().getSimpleName().matches("(RestController|Controller)"));
    }

    interface Check { void check(JavaClass type, ConditionEvents events); }
    static ArchRule rule(String name, Check check) {
        return classes().should(new ArchCondition<JavaClass>(name) {
            @Override public void check(JavaClass type, ConditionEvents events) { check.check(type, events); }
        });
    }
    static void fail(ConditionEvents events, Object item, String message) {
        events.add(SimpleConditionEvent.violated(item, message));
    }

    static final ArchRule CROSS_PERSISTENCE = rule("ARCH-002: never access another module's persistence", (type, events) -> {
        for (var dependency : type.getDirectDependenciesFromSelf()) {
            var target = dependency.getTargetClass();
            if (!owner(target).isEmpty() && !owner(type).equals(owner(target)) && persistence(target))
                fail(events, dependency, "ARCH-002 " + dependency.getDescription());
        }
    });

    static final ArchRule API_PURITY = rule("ARCH-003: keep API free of implementation and persistence", (type, events) -> {
        if (!type.getPackageName().matches(".*\\.api(\\..*)?")) return;
        if (persistence(type) || type.getSimpleName().endsWith("Impl"))
            fail(events, type, "ARCH-003 implementation type in API: " + type.getName());
        for (var dependency : type.getDirectDependenciesFromSelf()) {
            var target = dependency.getTargetClass();
            if (framework(target) || persistence(target) || layer(target, ".biz"))
                fail(events, dependency, "ARCH-003 " + dependency.getDescription());
        }
    });

    static final ArchRule CONTROLLER_BOUNDARY = rule("ARCH-004: controllers must delegate persistence/state changes", (type, events) -> {
        if (!controller(type)) return;
        for (var dependency : type.getDirectDependenciesFromSelf()) {
            var target = dependency.getTargetClass();
            if (persistence(target) || layer(target, ".biz.domain")
                    || target.getName().matches("(org.apache.ibatis|org.mybatis|com.baomidou.mybatisplus|java.sql|javax.sql|jakarta.persistence|org.springframework.jdbc)\\..*"))
                fail(events, dependency, "ARCH-004 " + dependency.getDescription());
        }
    });

    static final ArchRule DOMAIN_BOUNDARY = rule("domain must not depend on infrastructure or frameworks", (type, events) -> {
        if (!layer(type, ".biz.domain")) return;
        for (var dependency : type.getDirectDependenciesFromSelf()) {
            var target = dependency.getTargetClass();
            if (framework(target) || layer(target, ".biz.infrastructure"))
                fail(events, dependency, "DOMAIN " + dependency.getDescription());
        }
    });
}
