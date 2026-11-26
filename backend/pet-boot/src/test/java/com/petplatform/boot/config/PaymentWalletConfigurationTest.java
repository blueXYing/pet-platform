package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.api.command.WalletCommandApi;
import com.petplatform.payment.api.query.WalletQueryApi;
import com.petplatform.payment.biz.settlement.SettlementCompensationScanner;
import com.petplatform.payment.biz.settlement.SettlementReceiverLookup;
import com.petplatform.payment.biz.settlement.WalletChannel;
import com.petplatform.payment.biz.settlement.WalletReceiptWriter;
import com.petplatform.payment.biz.settlement.WalletService;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.bind.annotation.RestController;

/** Offline Contract 80 Stage 5 wallet configuration tests under unified pet.payment.enabled. */
class PaymentWalletConfigurationTest {

    private ApplicationContextRunner runner() {
        var lakala = new PaymentFoundationConfiguration.LakalaSettings(Map.of(), null, "Asia/Shanghai", null, "1");
        return new ApplicationContextRunner()
                .withUserConfiguration(PaymentWalletConfiguration.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(SettlementReceiverLookup.class, () -> merchantId -> Optional.empty())
                .withBean(WalletChannel.class, () -> mock(WalletChannel.class))
                .withBean(PaymentFoundationConfiguration.LakalaSettings.class, () -> lakala);
    }

    @Test
    void defaultsRegisterNoWalletRuntimeBeans() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(WalletCommandApi.class);
            assertThat(ctx).doesNotHaveBean(WalletQueryApi.class);
            assertThat(ctx).doesNotHaveBean(WalletService.class);
            assertThat(ctx).doesNotHaveBean(WalletReceiptWriter.class);
        });
    }

    @Test
    void paymentEnabledWiresStage5BeansWithoutSubSwitches() {
        runner().withPropertyValues(
                "pet.payment.enabled=true",
                "pet.payment.settlement.org-code=1950836",
                "pet.payment.wallet.withdraw-notify-url=https://api.example.com/api/v1/callbacks/lakala/withdrawals")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(WalletCommandApi.class);
                    assertThat(ctx).hasSingleBean(WalletQueryApi.class);
                    assertThat(ctx).hasSingleBean(WalletService.class);
                    assertThat(ctx).hasSingleBean(WalletReceiptWriter.class);
                    assertThat(ctx).hasSingleBean(SettlementCompensationScanner.CompensationHook.class);
                    assertThat(ctx.getBeanNamesForAnnotation(RestController.class)).isEmpty();
                });
    }
}
