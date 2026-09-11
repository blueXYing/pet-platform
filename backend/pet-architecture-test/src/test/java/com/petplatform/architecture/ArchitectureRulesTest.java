package com.petplatform.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureRulesTest {
    private static JavaClasses classes;

    @BeforeAll
    static void importAllProductionModules() throws Exception {
        Path backend = Path.of("..").toAbsolutePath().normalize();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var modules = factory.newDocumentBuilder().parse(backend.resolve("pom.xml").toFile())
                .getElementsByTagName("module");
        List<Path> outputs = new ArrayList<>();
        for (int i = 0; i < modules.getLength(); i++) {
            String module = modules.item(i).getTextContent().trim();
            if (module.equals("pet-architecture-test")) continue;
            Path output = backend.resolve(module).resolve("target/classes");
            assertTrue(Files.isDirectory(output), "ARCH-COVERAGE missing compiled module: " + module);
            var imported = new ClassFileImporter().importPath(output);
            assertTrue(imported.stream().anyMatch(c -> !c.getSimpleName().equals("package-info")),
                    "ARCH-COVERAGE no production classes in " + module);
            outputs.add(output);
            System.out.printf("ARCH-COVERAGE %s: %d production classes%n", module, imported.size());
        }
        assertTrue(outputs.size() >= 39, "ARCH-COVERAGE expected all 39 baseline production modules");
        classes = new ClassFileImporter().importPaths(outputs);
        assertFalse(classes.stream().anyMatch(c -> c.getName().contains("architecture.fixtures")),
                "Test fixtures must never enter production scan");
        System.out.printf("ARCH-COVERAGE total: %d modules / %d classes%n", outputs.size(), classes.size());
        System.out.printf("ARCH-COVERAGE meaningful classes: %d; controllers: %d; domain classes: %d%n",
                classes.stream().filter(c -> !c.getSimpleName().equals("package-info")).count(),
                classes.stream().filter(c -> !c.getSimpleName().equals("package-info") && ArchitectureRules.controller(c)).count(),
                classes.stream().filter(c -> !c.getSimpleName().equals("package-info") && ArchitectureRules.layer(c, ".biz.domain")).count());
    }

    private void sourceGate(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("python"));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).directory(Path.of("../..").toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        System.out.print(output);
        assertEquals(0, process.waitFor(), output);
    }

    @Test void arch001_maven_dependency_gate() throws Exception { sourceGate("backend/tools/check-module-deps.py"); }
    @Test void arch005_display_status_owner_gate() throws Exception { sourceGate("backend/tools/check-display-status.py"); }
    @Test void source_gate_negative_fixtures() throws Exception {
        sourceGate("-m", "unittest", "discover", "-s", "backend/tools", "-p", "test_*.py", "-v");
    }

    @Test void arch002_cross_module_persistence() { ArchitectureRules.CROSS_PERSISTENCE.check(classes); }
    @Test void arch003_api_purity() { ArchitectureRules.API_PURITY.check(classes); }
    @Test void arch004_controller_boundary() { ArchitectureRules.CONTROLLER_BOUNDARY.check(classes); }
    @Test void domain_boundary() { ArchitectureRules.DOMAIN_BOUNDARY.check(classes); }
}
