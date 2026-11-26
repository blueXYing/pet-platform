package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.payment.api.command.ReceiverAccountApi;
import com.petplatform.payment.api.query.ReceiverQueryApi;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient;
import com.petplatform.payment.biz.settlement.DbSettlementReceiverLookup;
import com.petplatform.payment.biz.settlement.ReceiverAccountService;
import com.petplatform.payment.biz.settlement.ReceiverAttachmentProvider;
import com.petplatform.payment.biz.settlement.ReceiverChannel;
import com.petplatform.payment.biz.settlement.ReceiverSensitiveCipher;
import com.petplatform.payment.biz.settlement.SettlementChannel;
import com.petplatform.payment.biz.settlement.SettlementCompensationScanner;
import com.petplatform.payment.biz.settlement.SettlementReceiverLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.bind.annotation.RestController;

/** Offline Contract 80 §7 Stage 4 configuration matrix and @Primary lookup tests. No MySQL required. */
class PaymentReceiverConfigurationTest {

    @Test
    void guardCheckEnforces32ByteAesKeyFileWhenPaymentEnabled(@TempDir Path dir) throws Exception {
        Path validKey = writeKey(dir.resolve("valid.key"), 32);
        Path shortKey = writeKey(dir.resolve("short.key"), 16);

        // Payment disabled passes even without key file.
        assertThatCode(() -> PaymentReceiverConfiguration.check(
                false, new PaymentReceiverConfiguration.ReceiverSettings(null, null)))
                .doesNotThrowAnyException();

        // Payment enabled with valid 32-byte key passes.
        assertThatCode(() -> PaymentReceiverConfiguration.check(
                true, new PaymentReceiverConfiguration.ReceiverSettings(validKey.toString(), null)))
                .doesNotThrowAnyException();

        // Payment enabled with missing or invalid key file fails closed.
        assertThatThrownBy(() -> PaymentReceiverConfiguration.check(
                true, new PaymentReceiverConfiguration.ReceiverSettings("", null)))
                .hasMessage(PaymentReceiverConfiguration.INVALID_AES_KEY);
        assertThatThrownBy(() -> PaymentReceiverConfiguration.check(
                true, new PaymentReceiverConfiguration.ReceiverSettings(shortKey.toString(), null)))
                .hasMessage(PaymentReceiverConfiguration.INVALID_AES_KEY);
        assertThatThrownBy(() -> PaymentReceiverConfiguration.check(
                true, new PaymentReceiverConfiguration.ReceiverSettings(dir.resolve("absent.key").toString(), null)))
                .hasMessage(PaymentReceiverConfiguration.INVALID_AES_KEY);
    }

    @Test
    void defaultsRegisterNoReceiverRuntimeBeans() {
        runner(PaymentReceiverConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(ReceiverAccountApi.class);
            assertThat(ctx).doesNotHaveBean(ReceiverQueryApi.class);
            assertThat(ctx).doesNotHaveBean(ReceiverSensitiveCipher.class);
            assertThat(ctx).doesNotHaveBean(DbSettlementReceiverLookup.class);
            assertThat(ctx).doesNotHaveBean(SettlementCompensationScanner.CompensationHook.class);
        });
    }

    @Test
    void enabledWithoutValidAesKeyFailsStartup() {
        runner(PaymentReceiverConfiguration.class)
                .withPropertyValues("pet.payment.enabled=true")
                .run(ctx -> assertThat(ctx).getFailure()
                        .hasStackTraceContaining(PaymentReceiverConfiguration.INVALID_AES_KEY));
    }

    @Test
    void enabledSupersedesFailClosedSettlementReceiverLookupRegardlessOfConfigOrder(@TempDir Path dir) throws Exception {
        Path validKey = writeKey(dir.resolve("valid.key"), 32);

        // Order 1: Settlement config first, Receiver config second -> @Primary DbSettlementReceiverLookup wins.
        runner(PaymentSettlementConfiguration.class, PaymentReceiverConfiguration.class)
                .withPropertyValues(
                        "pet.payment.enabled=true",
                        "pet.payment.settlement.org-code=200669",
                        "pet.payment.receiver.aes-key-path=" + validKey,
                        "pet.payment.receiver.ledger-notify-url=https://notify.invalid/api/v1/callbacks/lakala/ledger")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(ReceiverAccountService.class);
                    assertThat(ctx).hasSingleBean(ReceiverAccountApi.class);
                    assertThat(ctx).hasSingleBean(ReceiverQueryApi.class);
                    assertThat(ctx).hasSingleBean(ReceiverSensitiveCipher.class);
                    assertThat(ctx).hasSingleBean(ReceiverAttachmentProvider.class);
                    assertThat(ctx.getBean(SettlementReceiverLookup.class))
                            .isInstanceOf(DbSettlementReceiverLookup.class);
                    assertThat(ctx).hasSingleBean(SettlementCompensationScanner.CompensationHook.class);
                    assertThat(ctx.getBeanNamesForAnnotation(RestController.class)).isEmpty();
                });

        // Order 2: Receiver config first, Settlement config second -> DbSettlementReceiverLookup still wins.
        runner(PaymentReceiverConfiguration.class, PaymentSettlementConfiguration.class)
                .withPropertyValues(
                        "pet.payment.enabled=true",
                        "pet.payment.settlement.org-code=200669",
                        "pet.payment.receiver.aes-key-path=" + validKey,
                        "pet.payment.receiver.ledger-notify-url=https://notify.invalid/api/v1/callbacks/lakala/ledger")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(SettlementReceiverLookup.class))
                            .isInstanceOf(DbSettlementReceiverLookup.class);
                    assertThat(ctx).hasSingleBean(SettlementCompensationScanner.CompensationHook.class);
                    assertThat(ctx.getBeanNamesForAnnotation(RestController.class)).isEmpty();
                });
    }

    @Test
    void schemaDocAndFlywayV40AreByteForByteIdentical() throws Exception {
        Path root = repoRoot();
        byte[] docSql = Files.readAllBytes(root.resolve("docs/03-database/80-Payment-Receiver-Schema-v0.1.sql"));
        byte[] flywaySql = Files.readAllBytes(
                root.resolve("backend/pet-boot/src/main/resources/db/settlement-migration/V40__payment_settlement_receiver.sql"));
        assertThat(flywaySql).isEqualTo(docSql);
    }

    private ApplicationContextRunner runner(Class<?>... configs) {
        var dispatch = new PaymentDispatchConfiguration.DispatchSettings(
                LakalaHttpClient.Environment.SIT, "app", "serial", "unused", "serial", "unused",
                "unused", "ORG", "subject", "127.0.0.1", "https://notify.invalid/pay", false);
        var lakala = new PaymentFoundationConfiguration.LakalaSettings(Map.of(), null, "Asia/Shanghai", null, "1");
        return new ApplicationContextRunner()
                .withUserConfiguration(configs)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(IntegrationEventPublisher.class, () -> event -> {})
                .withBean(JdbcOutboxConsumeGuard.class, () -> mock(JdbcOutboxConsumeGuard.class))
                .withBean(PaymentMerchantBindings.class, PaymentReceiverConfigurationTest::platform)
                .withBean(SettlementChannel.class, () -> mock(SettlementChannel.class))
                .withBean(ReceiverChannel.class, () -> mock(ReceiverChannel.class))
                .withBean(PaymentDispatchConfiguration.DispatchSettings.class, () -> dispatch)
                .withBean(PaymentFoundationConfiguration.LakalaSettings.class, () -> lakala);
    }

    private static PaymentMerchantBindings platform() {
        return new PaymentMerchantBindings() {
            @Override
            public Binding require(String merchantId, String storeId) {
                throw new AssertionError();
            }

            @Override
            public Binding requirePlatform() {
                return new Binding("82229005943096D", "TERM", "wxApp");
            }
        };
    }

    private static Path writeKey(Path file, int byteLength) throws Exception {
        byte[] key = new byte[byteLength];
        new SecureRandom().nextBytes(key);
        Files.writeString(file, Base64.getEncoder().encodeToString(key), StandardCharsets.US_ASCII);
        return file;
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
        return java.util.Objects.requireNonNull(p);
    }
}
