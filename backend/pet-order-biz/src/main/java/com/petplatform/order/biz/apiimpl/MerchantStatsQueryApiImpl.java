package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantStatsQueryApi;
import com.petplatform.order.api.query.MerchantStatsQueryApi.ServiceOrdersPage;
import com.petplatform.order.api.query.MerchantStatsQueryApi.ServiceRow;
import com.petplatform.order.api.query.MerchantStatsQueryApi.StatsQuery;
import com.petplatform.order.api.query.MerchantStatsQueryApi.TrendBucket;
import com.petplatform.order.api.query.MerchantStatsQueryApi.UtilizationView;
import com.petplatform.order.api.query.MerchantStatsQueryApi.Overview;
import com.petplatform.order.api.query.StoreReviewScoreFactsApi;
import com.petplatform.order.biz.application.MerchantStatsQueryService;
import com.petplatform.order.biz.infrastructure.persistence.OrderQueryStore;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.query.ScheduleUtilizationQueryApi;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the business stats read face (contract 70). The review score port and
 * the schedule utilization port are optional at assembly time: absent renders reviewAvg /
 * utilizationRate null ("--"), present-but-failing is 503 inside the service. Read-only; guard
 * + OWNER re-proof share the aggregate transaction.
 */
public final class MerchantStatsQueryApiImpl implements MerchantStatsQueryApi {
    private final MerchantStatsQueryService service;

    public MerchantStatsQueryApiImpl(DataSource dataSource, ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi authority, StoreReviewScoreFactsApi reviewScores,
            ScheduleUtilizationQueryApi scheduleUtilization, Clock clock) {
        this.service = new MerchantStatsQueryService(
                new OrderQueryStore(Objects.requireNonNull(dataSource, "dataSource is required")),
                Objects.requireNonNull(guard, "guard is required"),
                Objects.requireNonNull(authority, "authority is required"),
                reviewScores, scheduleUtilization, clock == null ? Clock.systemUTC() : clock);
    }

    @Override
    public Overview overview(StatsQuery query) {
        return service.overview(query);
    }

    @Override
    public List<ServiceRow> serviceTop(StatsQuery query) {
        return service.serviceTop(query);
    }

    @Override
    public List<TrendBucket> revenueTrend(StatsQuery query) {
        return service.revenueTrend(query);
    }

    @Override
    public ServiceOrdersPage serviceOrders(StatsQuery query, String serviceId, Integer page,
            Integer pageSize) {
        return service.serviceOrders(query, serviceId, page, pageSize);
    }

    @Override
    public UtilizationView scheduleUtilization(StatsQuery query) {
        return service.scheduleUtilization(query);
    }

    /** Boot-side adapter seam kept next to the port's only consumer. */
    public static StoreReviewScoreFactsApi unavailableScores() {
        return query -> {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "评分事实不可用");
        };
    }
}
