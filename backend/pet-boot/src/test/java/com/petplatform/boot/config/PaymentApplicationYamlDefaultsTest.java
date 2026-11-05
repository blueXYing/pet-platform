package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;

/** WP-B: application.yml maps pet.payment.* from the environment with every switch OFF and no secret literal. */
class PaymentApplicationYamlDefaultsTest {
    private static EnumerablePropertySource<?> yaml() throws Exception {
        List<?> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        return (EnumerablePropertySource<?>) sources.getFirst();
    }

    private static String raw(String key) throws Exception {
        Object value = yaml().getProperty(key);
        assertThat(value).as(key).isNotNull();
        return value.toString();
    }

    @Test void switchesDefaultToFalse() throws Exception {
        assertThat(raw("pet.payment.foundation.enabled")).isEqualTo("${PAYMENT_FOUNDATION_ENABLED:false}");
        assertThat(raw("pet.payment.dispatch.enabled")).isEqualTo("${PAYMENT_DISPATCH_ENABLED:false}");
    }

    @Test void everyCredentialIsAnEnvironmentPlaceholderWithEmptyDefault() throws Exception {
        for (String key : List.of(
                "pet.payment.dispatch.app-id", "pet.payment.dispatch.merchant-serial",
                "pet.payment.dispatch.merchant-private-key-path", "pet.payment.dispatch.platform-serial",
                "pet.payment.dispatch.platform-certificate-path", "pet.payment.dispatch.parameter-key-path",
                "pet.payment.dispatch.out-org-code", "pet.payment.dispatch.subject",
                "pet.payment.dispatch.request-ip", "pet.payment.dispatch.notify-url",
                "pet.payment.lakala.notification-certificate-path", "pet.payment.lakala.channel-time-zone",
                "pet.payment.lakala.platform.merchant-no", "pet.payment.lakala.platform.term-no",
                "pet.payment.lakala.platform.sub-app-id")) {
            assertThat(raw(key)).as(key).matches("\\$\\{[A-Z0-9_]+:}");
        }
    }

    @Test void environmentAndSettleTypeDefaults() throws Exception {
        assertThat(raw("pet.payment.dispatch.environment")).isEqualTo("${LAKALA_ENV:}");
        assertThat(raw("pet.payment.lakala.settle-type")).isEqualTo("${LAKALA_SETTLE_TYPE:0}");
    }

    @Test void mappedPlaceholdersMatchTheEnvTemplate() throws Exception {
        String template = java.nio.file.Files.readString(repositoryRoot().resolve("ops/payment-center.env.example"));
        for (String name : yaml().getPropertyNames()) {
            if (!name.startsWith("pet.payment.")) continue;
            var matcher = Pattern.compile("\\$\\{([A-Z0-9_]+):").matcher(raw(name));
            if (!matcher.find()) continue;
            String variable = matcher.group(1);
            if (variable.startsWith("PAYMENT_FOUNDATION") || variable.startsWith("PAYMENT_DISPATCH")) continue;
            assertThat(template).as(variable).containsPattern("(?m)^" + variable + "=");
        }
    }

    @Test void noSecretLiteralInTheFile() throws Exception {
        String text = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("BEGIN PRIVATE KEY").doesNotContain("BEGIN CERTIFICATE");
        // Every pet.payment.* value is a ${...} placeholder (no inline literal except the boolean/enum defaults inside it).
        for (String name : yaml().getPropertyNames()) {
            if (name.startsWith("pet.payment.")) assertThat(raw(name)).as(name).startsWith("${").endsWith("}");
        }
    }

    private static java.nio.file.Path repositoryRoot() {
        java.nio.file.Path p = java.nio.file.Path.of("").toAbsolutePath();
        while (p != null && !java.nio.file.Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
        return java.util.Objects.requireNonNull(p);
    }
}
