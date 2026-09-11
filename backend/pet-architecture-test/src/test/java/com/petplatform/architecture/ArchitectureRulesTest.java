package com.petplatform.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureRulesTest {

    private final com.tngtech.archunit.core.domain.JavaClasses classes =
            new ClassFileImporter().importPackages("com.petplatform");

    @Test
    void api_contracts_must_not_depend_on_spring_or_persistence_frameworks() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.mybatis..",
                        "jakarta.persistence.."
                );

        rule.check(classes);
    }

    @Test
    void domain_model_must_not_depend_on_infrastructure_or_spring() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..biz.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..biz.infrastructure..",
                        "org.springframework..",
                        "org.mybatis.."
                );

        rule.check(classes);
    }

    @Test
    void web_controllers_must_not_depend_on_persistence_details() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..adapter.web..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..infrastructure.persistence..",
                        "..repository..",
                        "..mapper.."
                );

        rule.check(classes);
    }
}
