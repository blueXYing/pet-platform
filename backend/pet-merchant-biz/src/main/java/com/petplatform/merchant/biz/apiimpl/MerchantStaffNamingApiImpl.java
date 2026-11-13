package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.MerchantStaffNamingApi;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantStaffNamingStore;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * CCR-M-VERIFY-RECORD-001 naming face: batch staff display names strictly scoped to the
 * caller's store. The caller (the VER read kernel) has already passed its OWNER store guard;
 * this implementation does not repeat the guard but never reads beyond {@code storeId}, so a
 * cross-store or absent staff id simply misses. A blank {@code staffIds} collection answers an
 * empty map without touching the table.
 */
public final class MerchantStaffNamingApiImpl implements MerchantStaffNamingApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final MerchantStaffNamingStore store;

    public MerchantStaffNamingApiImpl(DataSource source) {
        this.store = new MerchantStaffNamingStore(Objects.requireNonNull(source, "source is required"));
    }

    @Override
    public Map<String, String> staffNames(String storeId, Collection<String> staffIds, QueryContext context) {
        if (context == null) throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "query context required");
        long storeKey = parse(storeId, "storeId");
        if (staffIds == null || staffIds.isEmpty()) return Map.of();
        List<Long> keys = staffIds.stream().distinct()
                .map(id -> parse(id, "staffId"))
                .toList();
        Map<String, String> names = new LinkedHashMap<>();
        for (var row : store.read(mapper -> mapper.selectStaffNames(storeKey, keys))) {
            names.put(IDS.toApi(row.getStaffId()), row.getStaffName());
        }
        return names;
    }

    private static long parse(String value, String field) {
        try {
            return IDS.fromApi(value);
        } catch (RuntimeException malformed) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
    }
}
