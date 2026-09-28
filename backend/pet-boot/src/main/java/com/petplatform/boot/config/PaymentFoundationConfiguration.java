package com.petplatform.boot.config;
import com.petplatform.common.*;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.order.api.query.OrderPaymentFactsApi;
import com.petplatform.order.biz.apiimpl.*;
import com.petplatform.payment.api.command.PaymentPreparationApi;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.payment.biz.apiimpl.*;
import com.petplatform.payment.biz.application.*;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol;
import com.petplatform.schedule.api.command.*;
import com.petplatform.schedule.api.protection.*;
import com.petplatform.schedule.biz.apiimpl.ReservationConfirmApiImpl;
import com.petplatform.user.api.query.BookingUserFactsApi;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;

/** Offline/internal payment foundation. No payment-initiation HTTP or callback HTTP is registered. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pet.payment.foundation",name="enabled",havingValue="true")
@EnableConfigurationProperties(PaymentFoundationConfiguration.LakalaSettings.class)
public class PaymentFoundationConfiguration {
    @ConfigurationProperties(prefix="pet.payment.lakala")
    public record LakalaSettings(Map<String,MerchantSettings> stores,String notificationCertificatePath,String channelTimeZone){
        public LakalaSettings{stores=stores==null?Map.of():Map.copyOf(stores);}
    }
    public record MerchantSettings(String merchantId,String merchantNo,String termNo,String subAppId){}
    @Bean OrderPaymentFactsApi orderPaymentFactsApi(DataSource source,ScheduleCapacityGuardApi guard){
        return new OrderPaymentFactsApiImpl(source,guard);
    }
    @Bean @ConditionalOnMissingBean(PaymentMerchantBindings.class)
    PaymentMerchantBindings paymentMerchantBindings(LakalaSettings settings){
        return (merchantId,storeId)->{
            MerchantSettings value=settings.stores().get(storeId);
            if(value==null||!merchantId.equals(value.merchantId()))throw unavailable();
            return new PaymentMerchantBindings.Binding(value.merchantNo(),value.termNo(),value.subAppId());
        };
    }
    @Bean @ConditionalOnMissingBean(PaymentReceiptVerifier.class)
    PaymentReceiptVerifier paymentReceiptVerifier(LakalaSettings settings){
        String path=settings.notificationCertificatePath();
        if(path==null||path.isBlank())return (headers,body,expected)->{throw unavailable();};
        final PublicKey key;
        try(var input=Files.newInputStream(Path.of(path))){
            key=CertificateFactory.getInstance("X.509").generateCertificate(input).getPublicKey();
        }catch(Exception failed){throw new IllegalStateException("Configured payment notification certificate is unavailable");}
        return (headers,body,expected)->{
            var notice=LakalaProtocol.verifyNotification(headers,body,key,new LakalaProtocol.ExpectedPayment(
                    expected.merchantNo(),expected.paymentNo(),expected.expectedAmount().movePointRight(2).longValueExact()));
            return new PaymentReceiptVerifier.VerifiedNotice(notice.merchantNo(),notice.outTradeNo(),notice.channelTradeNo(),
                    notice.tradeStatus().name(),BigDecimal.valueOf(notice.totalAmountCents(),2),
                    notice.payerAmountCents()==null?null:BigDecimal.valueOf(notice.payerAmountCents(),2),notice.channelTradeTime(),notice.accountType());
        };
    }
    @Bean PaymentPreparationApi paymentPreparationApi(DataSource source,SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard,OrderPaymentFactsApi orders,BookingUserFactsApi users,PaymentMerchantBindings bindings){
        return new PaymentPreparationApiImpl(source,ids,guard,orders,users,bindings);
    }
    @Bean PaymentNotificationService paymentNotificationService(DataSource source,SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard,PaymentReceiptVerifier verifier,IntegrationEventPublisher publisher,LakalaSettings settings){
        String zone=settings.channelTimeZone();
        return new PaymentNotificationService(source,ids,guard,verifier,publisher,
                zone==null||zone.isBlank()?null:java.time.ZoneId.of(zone));
    }
    @Bean PaymentSuccessFactsApi paymentSuccessFactsApi(DataSource source,ScheduleCapacityGuardApi guard){
        return new PaymentSuccessFactsApiImpl(source,guard);
    }
    @Bean ReservationConfirmApi reservationConfirmApi(DataSource source,SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard,ScheduleProtectionFactsApi facts,OrderPaymentFactsApi orders){
        return new ReservationConfirmApiImpl(source,ids,guard,facts,orders);
    }
    @Bean OrderPaymentResultApiImpl orderPaymentResultConsumer(DataSource source,SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard,PaymentSuccessFactsApi payments,ReservationConfirmApi reservations,
            ReservationExpiryApi expirations,IntegrationEventPublisher publisher,
            @org.springframework.beans.factory.annotation.Value("${pet.order.auto-confirm.enabled:false}") boolean autoConfirmTasksEnabled){
        return new OrderPaymentResultApiImpl(source,ids,guard,payments,reservations,expirations,publisher,autoConfirmTasksEnabled);
    }
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"payment channel configuration unavailable");}
}
