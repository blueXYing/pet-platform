package com.petplatform.payment.biz.application;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
/** Production adapter must verify the original bytes before returning any parsed result. */
@FunctionalInterface public interface PaymentReceiptVerifier {
    VerifiedNotice verify(Map<String,String> headers,byte[] rawBody,ExpectedPayment expected);
    record ExpectedPayment(String merchantNo,String paymentNo,BigDecimal expectedAmount) {}
    record VerifiedNotice(String merchantNo,String paymentNo,String channelTradeNo,String status,
            BigDecimal totalAmount,BigDecimal paidAmount,LocalDateTime channelTradeTime,String accountType,
            String logNo,LocalDate logDate) {
        public VerifiedNotice(String merchantNo,String paymentNo,String channelTradeNo,String status,
                BigDecimal totalAmount,BigDecimal paidAmount,LocalDateTime channelTradeTime,String accountType) {
            this(merchantNo,paymentNo,channelTradeNo,status,totalAmount,paidAmount,channelTradeTime,accountType,null,null);
        }
    }
}
