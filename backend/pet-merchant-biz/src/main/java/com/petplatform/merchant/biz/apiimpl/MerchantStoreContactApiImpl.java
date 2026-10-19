package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.merchant.api.dto.MerchantStoreContactDTO;
import com.petplatform.merchant.api.query.MerchantStoreContactQueryApi;
import com.petplatform.merchant.api.query.StoreIdQuery;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantReadStore;
import javax.sql.DataSource;

/**
 * Local implementation of the order-contact store phone read (HTTP10 §3.7 supplement,
 * 2026-10-19 ruling). Row existence is the only gate — deliberately not the catalog visibility
 * conjunction, because an existing paid order stays contactable even when the merchant later
 * stops accepting new orders (PRD 5.1.17: 待服务/售后阶段"仍可联系电话商家"). Masking reuses the
 * exact catalog rule (§3.3.2); a store row without a phone yields null fields, never a
 * fabricated contact.
 */
public final class MerchantStoreContactApiImpl implements MerchantStoreContactQueryApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final MerchantReadStore store;

    public MerchantStoreContactApiImpl(DataSource dataSource) {
        this.store = new MerchantReadStore(dataSource);
    }

    @Override
    public MerchantStoreContactDTO getStoreContact(StoreIdQuery query) {
        long storeId;
        try {
            storeId = Long.parseLong(requireShape(query.storeId()));
        } catch (NumberFormatException malformed) {
            throw new ApiException(CommonApiCodes.NOT_FOUND, "store resource not found");
        }
        if (storeId <= 0) throw new ApiException(CommonApiCodes.NOT_FOUND, "store resource not found");
        return store.read(mapper -> {
            var row = mapper.selectDisplayStore(storeId);
            // Positively missing store: NOT_FOUND; the caller degrades the contact section.
            if (row == null) throw new ApiException(CommonApiCodes.NOT_FOUND, "store resource not found");
            String phone = row.getPhone();
            return new MerchantStoreContactDTO(
                    IDS.toApi(storeId),
                    maskPhone(phone),
                    phone == null || phone.isBlank() ? null : phone);
        });
    }

    /** Same masking rule as the catalog projection (§3.3.2), verbatim. */
    private static String maskPhone(String phone) {
        if (phone == null) return null;
        if (phone.isBlank()) unavailable("phone is invalid");
        if (phone.length() <= 7) return "****";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }

    private static String requireShape(String storeId) {
        if (storeId == null || !storeId.matches("[1-9][0-9]{0,18}")) {
            throw new ApiException(CommonApiCodes.NOT_FOUND, "store resource not found");
        }
        return storeId;
    }
}
