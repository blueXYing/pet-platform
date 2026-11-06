package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.petplatform.boot.config.PaymentFoundationConfiguration.LakalaSettings;
import com.petplatform.boot.config.PaymentFoundationConfiguration.MerchantSettings;
import com.petplatform.boot.config.PaymentFoundationConfiguration.PlatformMerchant;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.order.api.query.OrderPaymentFactsApi;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.query.BookingUserFactsApi;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** WP-B: platform merchant bindings and settle_type wiring. No MySQL, no network. */
class PaymentFoundationConfigurationTest {
    private static final String M = "MERCHANT-PLACEHOLDER", T = "TERM-PLACEHOLDER", A = "wx-placeholder";
    private final PaymentFoundationConfiguration configuration = new PaymentFoundationConfiguration();

    private static LakalaSettings settings(Map<String, MerchantSettings> stores, PlatformMerchant platform,
            String settleType) {
        return new LakalaSettings(stores, null, "Asia/Shanghai", platform, settleType);
    }

    private static LakalaSettings bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("pet.payment.lakala", Bindable.of(LakalaSettings.class)).get();
    }

    @Test void platformModeReturnsTheSameBindingForRequireAndRequirePlatform() {
        var bindings = configuration.paymentMerchantBindings(
                settings(Map.of(), new PlatformMerchant(M, T, A), "1"));
        var expected = new PaymentMerchantBindings.Binding(M, T, A);
        assertThat(bindings.requirePlatform()).isEqualTo(expected);
        assertThat(bindings.require("17", "23")).isEqualTo(expected);
        assertThat(bindings.require("18", "99")).isEqualTo(bindings.requirePlatform());
    }

    @Test void platformModeRejectsMissingIdentifiers() {
        var bindings = configuration.paymentMerchantBindings(
                settings(Map.of(), new PlatformMerchant(M, T, A), "1"));
        assertThatThrownBy(() -> bindings.require(null, "23")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> bindings.require("17", " ")).isInstanceOf(ApiException.class);
    }

    @Test void storesAndPlatformTogetherFailStartup() {
        var stores = Map.of("23", new MerchantSettings("17", "M1", "T1", "wx1"));
        assertThatThrownBy(() -> configuration.paymentMerchantBindings(
                settings(stores, new PlatformMerchant(M, T, A), "1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ambiguous");
    }

    @Test void partialPlatformConfigurationFailsStartup() {
        assertThatThrownBy(() -> configuration.paymentMerchantBindings(
                settings(Map.of(), new PlatformMerchant(M, "", A), "1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
        assertThatThrownBy(() -> configuration.paymentMerchantBindings(
                settings(Map.of(), new PlatformMerchant(null, T, null), "1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void blankPlatformValuesMeanNotConfigured() {
        var stores = Map.of("23", new MerchantSettings("17", "M1", "T1", "wx1"));
        var bindings = configuration.paymentMerchantBindings(
                settings(stores, new PlatformMerchant("", "  ", null), "1"));
        assertThat(bindings.require("17", "23")).isEqualTo(new PaymentMerchantBindings.Binding("M1", "T1", "wx1"));
    }

    @Test void storesModeFailsClosedForPlatformAndForUnknownStores() {
        var stores = Map.of("23", new MerchantSettings("17", "M1", "T1", "wx1"));
        var bindings = configuration.paymentMerchantBindings(settings(stores, null, "0"));
        assertThatThrownBy(bindings::requirePlatform).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(CommonApiCodes.DEPENDENCY_UNAVAILABLE));
        assertThatThrownBy(() -> bindings.require("17", "24")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> bindings.require("18", "23")).isInstanceOf(ApiException.class);
    }

    @Test void nothingConfiguredFailsClosedForEveryBindingRequest() {
        var bindings = configuration.paymentMerchantBindings(settings(Map.of(), null, "0"));
        assertThatThrownBy(() -> bindings.require("17", "23")).isInstanceOf(ApiException.class);
        assertThatThrownBy(bindings::requirePlatform).isInstanceOf(ApiException.class);
    }

    @Test void interfaceDefaultRequirePlatformFailsClosedForLegacyLambdas() {
        PaymentMerchantBindings legacy = (merchantId, storeId) -> new PaymentMerchantBindings.Binding("M", "T", "A");
        assertThatThrownBy(legacy::requirePlatform).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(CommonApiCodes.DEPENDENCY_UNAVAILABLE));
    }

    @Test void legacyThreeArgumentConstructorKeepsOldBehaviour() {
        var legacy = new LakalaSettings(Map.of(), null, "Asia/Shanghai");
        assertThat(legacy.platform()).isNull();
        assertThat(legacy.settleType()).isEqualTo("0");
        assertThat(legacy.channelTimeZone()).isEqualTo("Asia/Shanghai");
    }

    @Test void nullOrEmptySettleTypeDefaultsToOrdinarySettlement() {
        assertThat(settings(null, null, null).settleType()).isEqualTo("0");
        assertThat(settings(null, null, "").settleType()).isEqualTo("0");
    }

    @Test void settleTypeOutsideZeroOrOneFailsPreparationWiring() {
        for (String bad : new String[] {"2", " 1", "01", "x"}) {
            assertThatThrownBy(() -> preparation(settings(Map.of(), null, bad)))
                    .as("settle-type %s", bad).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("settle-type");
        }
    }

    @Test void validSettleTypesWirePreparation() {
        assertThat(preparation(settings(Map.of(), null, "0"))).isNotNull();
        assertThat(preparation(settings(Map.of(), null, "1"))).isNotNull();
    }

    private Object preparation(LakalaSettings settings) {
        return configuration.paymentPreparationApi(mock(DataSource.class), () -> 1L,
                mock(ScheduleCapacityGuardApi.class), mock(OrderPaymentFactsApi.class),
                mock(BookingUserFactsApi.class), (merchantId, storeId) -> null, settings);
    }

    @Test void propertiesBindToThePlatformAndSettleTypeFields() {
        var bound = bind(Map.of(
                "pet.payment.lakala.settle-type", "1",
                "pet.payment.lakala.channel-time-zone", "Asia/Shanghai",
                "pet.payment.lakala.platform.merchant-no", M,
                "pet.payment.lakala.platform.term-no", T,
                "pet.payment.lakala.platform.sub-app-id", A));
        assertThat(bound.settleType()).isEqualTo("1");
        assertThat(bound.platform()).isEqualTo(new PlatformMerchant(M, T, A));
        assertThat(bound.stores()).isEmpty();
    }

    @Test void emptyEnvironmentStylePropertiesBindToUnconfiguredPlatformAndOrdinarySettlement() {
        var bound = bind(Map.of(
                "pet.payment.lakala.settle-type", "",
                "pet.payment.lakala.platform.merchant-no", "",
                "pet.payment.lakala.platform.term-no", "",
                "pet.payment.lakala.platform.sub-app-id", ""));
        assertThat(bound.settleType()).isEqualTo("0");
        // Empty platform values must never put the bindings into platform mode.
        var bindings = configuration.paymentMerchantBindings(bound);
        assertThatThrownBy(bindings::requirePlatform).isInstanceOf(ApiException.class);
    }

    /** Review S2: LAKALA_ENV has no yml default; an empty value binds to null and dispatch fails closed. */
    @Test void emptyDispatchEnvironmentBindsToNullAndChannelFailsClosed() {
        var dispatch = new Binder(new MapConfigurationPropertySource(Map.of(
                "pet.payment.dispatch.environment", "", "pet.payment.dispatch.app-id", "")))
                .bind("pet.payment.dispatch", Bindable.of(PaymentDispatchConfiguration.DispatchSettings.class)).get();
        assertThat(dispatch.environment()).isNull();
        assertThatThrownBy(() -> new PaymentDispatchConfiguration().paymentChannel(dispatch))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Payment dispatch credentials or launch configuration unavailable");
    }

    @Test void storesStillBindFromProperties() {
        var bound = bind(Map.of(
                "pet.payment.lakala.stores.23.merchant-id", "17",
                "pet.payment.lakala.stores.23.merchant-no", "M1",
                "pet.payment.lakala.stores.23.term-no", "T1",
                "pet.payment.lakala.stores.23.sub-app-id", "wx1"));
        assertThat(bound.stores().get("23")).isEqualTo(new MerchantSettings("17", "M1", "T1", "wx1"));
        assertThat(bound.platform()).isNull();
        assertThat(bound.settleType()).isEqualTo("0");
    }
}
