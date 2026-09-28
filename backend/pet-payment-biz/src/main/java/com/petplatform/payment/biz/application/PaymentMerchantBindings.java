package com.petplatform.payment.biz.application;
/** Server-owned binding. Never accepted from a terminal request. Missing configuration fails closed. */
@FunctionalInterface public interface PaymentMerchantBindings {
    Binding require(String merchantId,String storeId);
    record Binding(String merchantNo,String termNo,String subAppId) {}
}
