package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.schedule.api.query.ScheduleUtilizationQueryApi;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleUtilizationStore;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Utilization facts impl (contract 70 §2 排期利用率, 2026-10-19 CCR expansion): double-key
 * scoped read aggregate over the store's own SCH windows and confirmed reservations. No OWNER
 * proof here — the consuming stats service re-proves the OWNER under its guard before this read.
 */
public final class ScheduleUtilizationQueryApiImpl implements ScheduleUtilizationQueryApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final ScheduleUtilizationStore store;

    public ScheduleUtilizationQueryApiImpl(DataSource source) {
        this.store = new ScheduleUtilizationStore(Objects.requireNonNull(source, "source is required"));
    }

    @Override
    public Utilization utilization(UtilizationQuery query) {
        if (query == null || query.merchantId() == null || query.storeId() == null
                || query.from() == null || query.to() == null || !query.from().isBefore(query.to())) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "utilization window is invalid");
        }
        long merchant = coordinate(query.merchantId());
        long storeKey = coordinate(query.storeId());
        return store.read(mapper -> new Utilization(
                mapper.availableMinutes(merchant, storeKey, query.from(), query.to()),
                mapper.bookedMinutes(merchant, storeKey, query.from(), query.to())));
    }

    private static long coordinate(String value) {
        try {
            return IDS.fromApi(value);
        } catch (RuntimeException malformed) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "商家坐标不合法");
        }
    }
}
