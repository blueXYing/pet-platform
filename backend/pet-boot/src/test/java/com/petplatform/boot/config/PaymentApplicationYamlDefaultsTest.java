package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;

/** Verifies the 5-layer YAML config: base defaults, env profiles, and secrets-only template. */
class PaymentApplicationYamlDefaultsTest {
    private static EnumerablePropertySource<?> yaml(String file) throws Exception {
        List<?> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        return (EnumerablePropertySource<?>) sources.getFirst();
    }

    private static String raw(EnumerablePropertySource<?> source, String key) {
        Object value = source.getProperty(key);
        assertThat(value).as(key).isNotNull();
        return value.toString();
    }

    @Test void baseSwitchesDefaultToFalseAndProfilesEnableFullPlatform() throws Exception {
        var base = yaml("application.yml");
        assertThat(raw(base, "pet.payment.enabled")).isEqualTo("false");
        assertThat(raw(base, "pet.payment.center.demo.enabled")).isEqualTo("false");
        assertThat(base.getProperty("spring.profiles.active")).isNull();

        for (String profile : List.of("application-dev.yml", "application-test.yml", "application-prod.yml")) {
            var p = yaml(profile);
            assertThat(raw(p, "pet.auth.admin.enabled")).as(profile).isEqualTo("true");
            assertThat(raw(p, "pet.auth.c.enabled")).as(profile).isEqualTo("true");
            assertThat(raw(p, "pet.private-assets.enabled")).as(profile).isEqualTo("true");
            assertThat(raw(p, "pet.payment.enabled")).as(profile).isEqualTo("true");
        }
        assertThat(raw(yaml("application-prod.yml"), "pet.payment.dispatch.environment")).isEqualTo("PRODUCTION");
        assertThat(raw(yaml("application-prod.yml"), "pet.payment.center.demo.enabled")).isEqualTo("false");
    }

    @Test void everyCredentialIsAnEnvironmentPlaceholderWithEmptyDefault() throws Exception {
        var base = yaml("application.yml");
        for (String key : List.of(
                "pet.payment.dispatch.app-id", "pet.payment.dispatch.merchant-serial",
                "pet.payment.dispatch.merchant-private-key-path", "pet.payment.dispatch.platform-serial",
                "pet.payment.dispatch.platform-certificate-path", "pet.payment.dispatch.parameter-key-path",
                "pet.payment.dispatch.out-org-code", "pet.payment.dispatch.subject",
                "pet.payment.dispatch.request-ip", "pet.payment.dispatch.notify-url",
                "pet.payment.lakala.notification-certificate-path",
                "pet.payment.lakala.platform.merchant-no", "pet.payment.lakala.platform.term-no")) {
            assertThat(raw(base, key)).as(key).matches("\\$\\{[A-Z0-9_]+:}");
        }
        assertThat(raw(base, "pet.payment.lakala.platform.sub-app-id"))
                .isEqualTo("${WECHAT_MINI_APP_ID:${AUTH_WECHAT_APP_ID:}}");
    }

    @Test void mappedSecretPlaceholdersMatchTheUnifiedEnvTemplate() throws Exception {
        var base = yaml("application.yml");
        String template = java.nio.file.Files.readString(repositoryRoot().resolve("ops/backend-secrets.env.example"));
        for (String name : base.getPropertyNames()) {
            if (!name.startsWith("pet.payment.")) continue;
            var matcher = Pattern.compile("\\$\\{([A-Z0-9_]+):").matcher(raw(base, name));
            if (!matcher.find()) continue;
            String variable = matcher.group(1);
            if (variable.equals("LAKALA_ENV") || variable.equals("LAKALA_CHANNEL_TIMEZONE")) continue;
            assertThat(template).as(variable).containsPattern("(?m)^" + variable + "=");
        }
    }

    @Test void noSecretLiteralInAnyYamlFile() throws Exception {
        for (String file : List.of(
                "application.yml", "application-dev.yml", "application-test.yml", "application-prod.yml")) {
            String text = new String(new ClassPathResource(file).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(text).as(file).doesNotContain("BEGIN PRIVATE KEY").doesNotContain("BEGIN CERTIFICATE");
        }
    }

    private static java.nio.file.Path repositoryRoot() {
        java.nio.file.Path p = java.nio.file.Path.of("").toAbsolutePath();
        while (p != null && !java.nio.file.Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
        return java.util.Objects.requireNonNull(p);
    }
}
