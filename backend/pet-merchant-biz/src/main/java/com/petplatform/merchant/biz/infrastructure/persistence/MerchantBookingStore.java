package com.petplatform.merchant.biz.infrastructure.persistence;

import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantBookingMapper;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** The booking flow calls these locks inside the caller's guarded transaction. */
public final class MerchantBookingStore {
    public record StoreRow(long merchantId, String merchantName, String merchantStatus,
            String storeName, String address, String storeStatus) {}

    private final MerchantBookingMapper mapper;

    public MerchantBookingStore(DataSource source) {
        mapper = MerchantMybatis.joiningTemplate(source).getMapper(MerchantBookingMapper.class);
    }

    public List<StoreRow> lockStore(long storeId) {
        return mapper.lockStore(storeId).stream().map(MerchantBookingStore::storeRow).toList();
    }

    public List<Long> lockApplicationAnchors(long merchantId) {
        return mapper.lockApplicationAnchors(merchantId);
    }

    public void lockAgreementAcceptances(long merchantId) {
        mapper.lockAgreementAcceptances(merchantId);
    }

    private static StoreRow storeRow(Map<String, Object> row) {
        return new StoreRow(((Number) row.get("id")).longValue(),
                (String) row.get("merchant_name"), (String) row.get("merchant_status"),
                (String) row.get("store_name"), (String) row.get("address"),
                (String) row.get("store_status"));
    }
}
