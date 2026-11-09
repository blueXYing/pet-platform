package com.petplatform.order.api.query;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;
/** ORDER snapshots never grant a later write capability. */
public interface OrderAfterSaleFactsApi {
 Location locate(String orderId, QueryContext context);
 Fact requireCurrentEligible(String orderId,String storeId,QueryContext context,DataSource transactionSource);
 Current current(String orderId,String storeId,QueryContext context,DataSource transactionSource);
 /**
  * CCR-M-AFTERSALE-VIEW-001 (approved 2026-11-06): read-only display labels for the merchant
  * aftersale read side — the immutable order-time service name and pet name/type/breed of 1..50
  * distinct orders of ONE store, read in the caller's READ_COMMITTED transaction under its
  * already-held store guard (same discipline as {@link #current}). Breed level only: no
  * sex/weight/health column, never a phone, user id or write capability. Orders of another store
  * are absent from the answer; a missing snapshot reads null (callers degrade, never fabricate).
  */
 default List<DisplayLabel> displayLabels(List<String> orderIds,String storeId,QueryContext context,DataSource transactionSource){
  throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"ORDER display labels unavailable");
 }
 record DisplayLabel(String orderId,String serviceName,String petName,String petType,String petBreed) {}
 record Location(String orderId,String userId,String merchantId,String storeId,String reservationId) {}
 record NormalPaymentOrigin(String paymentId,String paymentNo,String paymentSuccessEventId,String channelTradeNo,BigDecimal paidAmount,OffsetDateTime paidAt,String currency) {}
 record Fact(Location location,String orderVersion,OffsetDateTime appointmentStart,String verificationStatus,String verificationId,OffsetDateTime verifiedAt,NormalPaymentOrigin payment,String currentCaseId,String aftersaleStatus,String currentApplicationId,String currentApplicationStatus,String cityCode,String scopeVersion) {}
 record Current(String orderId,String storeId,String caseId,String status,String orderVersion) {}
}
