package com.petplatform.order.api.query;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
/** ORDER snapshots never grant a later write capability. */
public interface OrderAfterSaleFactsApi {
 Location locate(String orderId, QueryContext context);
 Fact requireCurrentEligible(String orderId,String storeId,QueryContext context,DataSource transactionSource);
 Current current(String orderId,String storeId,QueryContext context,DataSource transactionSource);
 record Location(String orderId,String userId,String merchantId,String storeId,String reservationId) {}
 record NormalPaymentOrigin(String paymentId,String paymentNo,String paymentSuccessEventId,String channelTradeNo,BigDecimal paidAmount,OffsetDateTime paidAt,String currency) {}
 record Fact(Location location,String orderVersion,OffsetDateTime appointmentStart,String verificationStatus,String verificationId,OffsetDateTime verifiedAt,NormalPaymentOrigin payment,String currentCaseId,String aftersaleStatus,String currentApplicationId,String currentApplicationStatus,String cityCode,String scopeVersion) {}
 record Current(String orderId,String storeId,String caseId,String status,String orderVersion) {}
}
