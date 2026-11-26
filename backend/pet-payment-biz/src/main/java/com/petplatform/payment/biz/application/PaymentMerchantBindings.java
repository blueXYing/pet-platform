package com.petplatform.payment.biz.application;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.util.Optional;
/** Server-owned binding. Never accepted from a terminal request. Missing configuration fails closed. */
@FunctionalInterface public interface PaymentMerchantBindings {
    Binding require(String merchantId,String storeId);
    /** The single platform collection merchant used by the business-agnostic payment center. Fails closed by default. */
    default Binding requirePlatform(){
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"platform payment merchant is not configured");
    }
    /** Optional per-merchant Lakala acquiring merchant number (Mode A, Ruling 40 §10). */
    default Optional<String> findAcquiringMerchantNo(long merchantId) {
        return Optional.empty();
    }
    record Binding(String merchantNo,String termNo,String subAppId) {}
}
