package com.petplatform.merchant.api.dto;
import java.util.List;
public final class MerchantCurrentStaffTypes {
    private MerchantCurrentStaffTypes() {}
    public record CurrentStaffFact(String staffId, String merchantId, String storeId,
            String employmentStatus, boolean serviceEnabled, String version) {}
    public record CurrentStoreStaffFacts(String merchantId, String storeId, boolean complete,
            List<CurrentStaffFact> items) {
        public CurrentStoreStaffFacts { items=List.copyOf(items); }
    }
}
