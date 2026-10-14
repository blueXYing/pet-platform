package com.petplatform.user.api.dto;

import java.time.OffsetDateTime;

/**
 * Contract 58 list projection shared by favorites and footprints. The display columns are the
 * current C-catalog projection of the target (serviceName/categoryName/salePrice or
 * storeName/address); a target that is currently hidden or gone keeps its row with
 * targetAvailable=false and null display columns — cancel/remove still works on such rows.
 */
public record UserTargetDisplayDTO(
        String targetType,
        String targetId,
        boolean targetAvailable,
        String title,
        String categoryLabel,
        String salePrice,
        String storeId,
        String coverUrl) {

    /** SERVICE rows carry storeId so the client can route booking/store views; STORE rows do not. */
    public static UserTargetDisplayDTO unavailable(String targetType, String targetId) {
        return new UserTargetDisplayDTO(targetType, targetId, false, null, null, null, null, null);
    }
}
