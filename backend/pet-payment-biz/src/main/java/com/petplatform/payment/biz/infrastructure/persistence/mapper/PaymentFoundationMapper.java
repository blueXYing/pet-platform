package com.petplatform.payment.biz.infrastructure.persistence.mapper;

import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore.Dispatch;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore.ExposureDispatch;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore.ExposureReceipt;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore.IntentBinding;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore.Row;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** All PAYMENT foundation, dispatch and exposure SQL is in PaymentFoundationMapper.xml. */
public interface PaymentFoundationMapper {
    void setUtcTimeZone();
    void setLockWaitTimeout();
    LocalDateTime utcNow();
    Row selectByNo(@Param("value") long value);
    Row selectById(@Param("value") long value);
    Row selectByIdForUpdate(@Param("value") long value);
    Row selectByOrderForUpdate(@Param("value") long value);

    int insertIntent(Map<String, Object> p);
    IntentBinding selectIntentForUpdate(@Param("key") byte[] key);
    int bindIntent(@Param("paymentId") long paymentId, @Param("key") byte[] key);
    int insertPayment(Map<String, Object> p);
    int insertPreparedDispatch(@Param("paymentId") long paymentId);
    Long countSuccessProofs(Map<String, Object> p);

    Long countReceiptByDigest(@Param("paymentId") long paymentId, @Param("digest") String digest);
    int insertReceipt(Map<String, Object> p);
    int markReconciliation(Map<String, Object> p);
    int markPaid(Map<String, Object> p);
    int markObserved(Map<String, Object> p);

    Dispatch selectDispatchForUpdate(@Param("paymentId") long paymentId);
    int markMayHaveSent(Map<String, Object> p);
    int savePreorderSha(Map<String, Object> p);
    int saveExpiredParameters(Map<String, Object> p);
    int saveParameters(Map<String, Object> p);
    int markUnknown(@Param("paymentId") long paymentId);
    int fenceUnsent(@Param("paymentId") long paymentId);
    int fence(@Param("paymentId") long paymentId);
    int armClose(@Param("paymentId") long paymentId);
    int insertCloseReceipt(Map<String, Object> p);
    int ackClose(Map<String, Object> p);
    Long countAdverseReceipts(@Param("paymentId") long paymentId);
    Long countCloseReceipts(@Param("paymentId") long paymentId, @Param("sha") String sha);
    List<String> selectMatchingQueryForUpdate(@Param("paymentId") long paymentId,
            @Param("sha") String sha);
    int finalizeClose(Map<String, Object> p);

    Long selectPaymentIdByOrderForUpdate(@Param("orderId") long orderId);
    Long selectOrphanTransaction();
    Long selectTransactionForUpdate(@Param("paymentId") long paymentId);
    Long selectOrphanReceipt();
    Long selectOrphanDispatch();
    ExposureDispatch selectExposureDispatchForUpdate(@Param("paymentId") long paymentId);
    List<ExposureReceipt> selectExposureReceiptsForUpdate(@Param("paymentId") long paymentId);
}
