package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
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
import com.petplatform.thirdparty.api.PrivateAssetApi;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.PrivateAssetContent;
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

    @Test
    void wiresPrivateAssetAdapterWhenPrivateAssetApiPresentAndFailsClosedWhenAbsent(@TempDir Path dir)
            throws Exception {
        Path validKey = writeKey(dir.resolve("valid.key"), 32);

        // 1. Without PrivateAssetApi -> fail-closed provider throws 503 DEPENDENCY_UNAVAILABLE.
        runner(PaymentReceiverConfiguration.class)
                .withPropertyValues(
                        "pet.payment.enabled=true",
                        "pet.payment.receiver.aes-key-path=" + validKey)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ReceiverAttachmentProvider provider = ctx.getBean(ReceiverAttachmentProvider.class);
                    assertThat(provider).isNotInstanceOf(PrivateAssetReceiverAttachmentAdapter.class);
                    assertThatThrownBy(() -> provider.readAsset("10001"))
                            .isInstanceOf(ApiException.class)
                            .extracting(e -> ((ApiException) e).code())
                            .isEqualTo(CommonApiCodes.DEPENDENCY_UNAVAILABLE);
                });

        // 2. With PrivateAssetApi -> wires PrivateAssetReceiverAttachmentAdapter & maps jpg/png/pdf.
        byte[] jpgBytes = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01};
        byte[] pngBytes = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47};
        byte[] pdfBytes = "%PDF-1.4".getBytes(StandardCharsets.US_ASCII);
        byte[] hugeBytes = new byte[5 * 1024 * 1024 + 1];
        PrivateAssetApi fakeAssets = mock(PrivateAssetApi.class);
        org.mockito.Mockito.when(fakeAssets.readReadyForSystem(
                        org.mockito.ArgumentMatchers.eq("10001"),
                        org.mockito.ArgumentMatchers.argThat(q -> q != null && q.operatorType() == OperatorType.SYSTEM)))
                .thenReturn(new PrivateAssetContent(jpgBytes, "image/jpeg", "a".repeat(64), jpgBytes.length));
        org.mockito.Mockito.when(fakeAssets.readReadyForSystem(
                        org.mockito.ArgumentMatchers.eq("10002"),
                        org.mockito.ArgumentMatchers.any(QueryContext.class)))
                .thenReturn(new PrivateAssetContent(pngBytes, "image/png", "b".repeat(64), pngBytes.length));
        org.mockito.Mockito.when(fakeAssets.readReadyForSystem(
                        org.mockito.ArgumentMatchers.eq("10003"),
                        org.mockito.ArgumentMatchers.any(QueryContext.class)))
                .thenReturn(new PrivateAssetContent(pdfBytes, "application/pdf", "c".repeat(64), pdfBytes.length));
        org.mockito.Mockito.when(fakeAssets.readReadyForSystem(
                        org.mockito.ArgumentMatchers.eq("10004"),
                        org.mockito.ArgumentMatchers.any(QueryContext.class)))
                .thenReturn(new PrivateAssetContent(hugeBytes, "image/jpeg", "d".repeat(64), hugeBytes.length));
        org.mockito.Mockito.when(fakeAssets.readReadyForSystem(
                        org.mockito.ArgumentMatchers.eq("10005"),
                        org.mockito.ArgumentMatchers.any(QueryContext.class)))
                .thenReturn(new PrivateAssetContent(jpgBytes, "image/webp", "e".repeat(64), jpgBytes.length));

        runner(PaymentReceiverConfiguration.class)
                .withBean(PrivateAssetApi.class, () -> fakeAssets)
                .withPropertyValues(
                        "pet.payment.enabled=true",
                        "pet.payment.receiver.aes-key-path=" + validKey)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ReceiverAttachmentProvider provider = ctx.getBean(ReceiverAttachmentProvider.class);
                    assertThat(provider).isInstanceOf(PrivateAssetReceiverAttachmentAdapter.class);

                    var jpg = provider.readAsset("10001");
                    assertThat(jpg.extName()).isEqualTo("jpg");
                    assertThat(jpg.fileName()).isEqualTo("asset-10001.jpg");
                    assertThat(jpg.bytes()).isEqualTo(jpgBytes);

                    var png = provider.readAsset("10002");
                    assertThat(png.extName()).isEqualTo("png");
                    assertThat(png.bytes()).isEqualTo(pngBytes);

                    var pdf = provider.readAsset("10003");
                    assertThat(pdf.extName()).isEqualTo("pdf");
                    assertThat(pdf.bytes()).isEqualTo(pdfBytes);

                    // Exceeds 5MB Lakala limit -> 400 INVALID_ARGUMENT
                    assertThatThrownBy(() -> provider.readAsset("10004"))
                            .isInstanceOf(ApiException.class)
                            .extracting(e -> ((ApiException) e).code())
                            .isEqualTo(CommonApiCodes.INVALID_ARGUMENT);

                    // Unsupported MIME type -> 400 INVALID_ARGUMENT
                    assertThatThrownBy(() -> provider.readAsset("10005"))
                            .isInstanceOf(ApiException.class)
                            .extracting(e -> ((ApiException) e).code())
                            .isEqualTo(CommonApiCodes.INVALID_ARGUMENT);
                });
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
