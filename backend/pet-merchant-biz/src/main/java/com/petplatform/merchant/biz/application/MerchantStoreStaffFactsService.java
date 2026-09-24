package com.petplatform.merchant.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.merchant.api.dto.StoreStaffFactsDTO;
import com.petplatform.merchant.api.query.StoreStaffFactsQuery;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantReadStore;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffFactsEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreStaffFactsEntity;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** MER-owned source of active, service-enabled staff IDs for a store. */
public final class MerchantStoreStaffFactsService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final MerchantReadStore store;

    public MerchantStoreStaffFactsService(MerchantReadStore store) {
        this.store = Objects.requireNonNull(store, "store is required");
    }

    public StoreStaffFactsDTO list(StoreStaffFactsQuery query) {
        if (query == null) invalid("query is required");
        if (query.context() == null) invalid("query context is required");
        long storeId = targetId(query.storeId());
        return store.read(mapper -> {
            MerchantStoreStaffFactsEntity shop = mapper.selectStoreStaffFacts(storeId);
            if (shop == null) throw new ApiException(CommonApiCodes.NOT_FOUND, "merchant store not found");
            if (shop.getStoreId() == null || shop.getStoreId() != storeId
                    || shop.getMerchantId() == null || shop.getMerchantId() <= 0) {
                unavailable("store staff ownership facts are invalid");
            }
            List<MerchantStaffFactsEntity> rows = mapper.selectStoreStaffRows(storeId);
            if (rows == null) unavailable("store staff facts are missing");
            TreeSet<Long> active = new TreeSet<>();
            for (MerchantStaffFactsEntity row : rows) {
                if (row == null || row.getStaffId() == null || row.getStaffId() <= 0
                        || row.getStoreId() == null || row.getStoreId() != storeId
                        || row.getMerchantId() == null || !row.getMerchantId().equals(shop.getMerchantId())) {
                    unavailable("store staff relationship is invalid");
                }
                String status = row.getEmploymentStatus();
                if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
                    unavailable("unknown staff employment status");
                }
                Integer enabled = row.getServiceEnabled();
                if (enabled == null || (enabled != 0 && enabled != 1)) {
                    unavailable("invalid staff service enabled fact");
                }
                if ("ACTIVE".equals(status) && enabled == 1) active.add(row.getStaffId());
            }
            return new StoreStaffFactsDTO(
                    IDS.toApi(storeId), active.stream().map(IDS::toApi).toList());
        });
    }

    private static long targetId(String value) {
        try {
            long id = IDS.fromApi(value);
            if (id <= 0) invalid("storeId is invalid");
            return id;
        } catch (IllegalArgumentException malformed) {
            invalid("storeId is invalid");
            return 0;
        }
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
