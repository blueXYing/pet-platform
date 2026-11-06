package com.petplatform.order.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantOrderQueryApi.StoreOrderListQuery;
import com.petplatform.order.api.query.MerchantOrderQueryApi.Summary;
import com.petplatform.order.biz.infrastructure.persistence.OrderQueryStore;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderQueryMerchantReadMapper;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.OrderServiceSnapshotName;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.OrderPetSnapshotName;
import com.petplatform.order.biz.infrastructure.persistence.entity.OrderQueryViewEntity;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.dto.UserBasicDTO;
import com.petplatform.user.api.query.UserQueryApi;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Merchant order list read model (HTTP contract 10 §4.1 supplement, the slice after contract
 * 45's manual decisions). Read-only: every query is scoped to the OWNER merchant coordinate
 * (merchantId+storeId) re-proven per call inside the store guard transaction via
 * {@link MerchantOrderAuthorityApi#requireOwnerRead} (ACTIVE/OFFLINE/FROZEN existing-order
 * family, same as the contract-51 store read), then filtered by both keys so a foreign,
 * absent or non-owned store answers the same COMMON_FORBIDDEN (anti-enumeration) and
 * cross-store rows never appear. The fixed sort is created_at DESC then id DESC; the optional
 * displayStatus filter reuses the same {@link OrderDisplayStatus} truth table the C-end §3.7
 * list filters with. No page derivation beyond the wire bounds lives here.
 */
public final class MerchantOrderQueryService {

    /** OpenAPI 11 Page/PageSize parameter bounds (contract 10 §4.1 supplement, same as §3.7). */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final OrderQueryStore store;
    private final ScheduleCapacityGuardApi guard;
    private final MerchantOrderAuthorityApi authority;
    private final UserQueryApi users;

    public MerchantOrderQueryService(OrderQueryStore store, ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi authority, UserQueryApi users) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.authority = Objects.requireNonNull(authority, "authority is required");
        this.users = Objects.requireNonNull(users, "user basics facts are required");
    }

    public PageResult<Summary> listStoreOrders(StoreOrderListQuery query) {
        Objects.requireNonNull(query, "query is required");
        if (query.context() == null || query.context().operatorType() != OperatorType.USER
                || query.context().operatorId() == null) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "query subject is required");
        }
        long merchant = coordinate(query.merchantId());
        long storeKey = coordinate(query.storeId());
        String filterStatus = query.displayStatus();
        if (filterStatus != null && !OrderDisplayStatus.VALUES.contains(filterStatus)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "displayStatus is invalid");
        }
        int page = query.page() < 1 ? 1 : query.page();
        int pageSize = query.pageSize() <= 0 ? DEFAULT_PAGE_SIZE
                : Math.min(query.pageSize(), MAX_PAGE_SIZE);
        return store.read((mapper, merchantReads) -> {
            // Same store guard + OWNER re-proof discipline as the contract-45 commands: the
            // coordinate is validated under the stable SCH row lock on every read.
            guard.acquire(List.of(query.storeId()),
                    new QueryContext(query.context().traceId(), OperatorType.SYSTEM, null));
            authority.requireOwnerRead(query.merchantId(), query.storeId(), query.context());
            long total = mapper.countForStore(merchant, storeKey, filterStatus);
            List<Summary> items;
            if (total == 0 || pageSize == 0) {
                items = List.of();
            } else {
                List<OrderQueryViewEntity> rows = mapper.selectForStorePage(merchant, storeKey,
                        filterStatus, pageSize, (long) (page - 1) * pageSize);
                items = project(merchantReads, rows, merchant, storeKey);
            }
            return new PageResult<>(items, total, page, pageSize);
        });
    }

    private static long coordinate(String value) {
        try {
            return IDS.fromApi(value);
        } catch (RuntimeException malformed) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "商家坐标不合法");
        }
    }

    /**
     * Wire-neutral merchant summary plus the CCR-M-ORDER-CARD-001 card facts: nickname/avatar
     * via the user domain's masked batch read (batch cap 1..200, absent ids simply missing so
     * the fields read null), service/pet names via this store's own immutable creation
     * snapshots (still double-key scoped; a snapshot-less order reads null, never fabricated).
     */
    private List<Summary> project(OrderQueryMerchantReadMapper merchantReads,
            List<OrderQueryViewEntity> rows, long merchant, long storeKey) {
        if (rows.isEmpty()) return List.of();
        Map<Long, UserBasicDTO> basics = loadBasics(rows);
        List<Long> orderIds = rows.stream().map(OrderQueryViewEntity::getId).toList();
        Map<Long, String> serviceNames = new LinkedHashMap<>();
        for (OrderServiceSnapshotName name : merchantReads.pageServiceSnapshotNames(
                merchant, storeKey, orderIds)) {
            serviceNames.put(name.getOrderId(), name.getServiceName());
        }
        Map<Long, OrderPetSnapshotName> pets = new LinkedHashMap<>();
        for (OrderPetSnapshotName pet : merchantReads.pagePetSnapshotNames(
                merchant, storeKey, orderIds)) {
            pets.put(pet.getOrderId(), pet);
        }
        List<Summary> items = new ArrayList<>(rows.size());
        for (OrderQueryViewEntity row : rows) {
            UserBasicDTO basic = basics.get(row.getUserId());
            OrderPetSnapshotName pet = pets.get(row.getId());
            items.add(new Summary(IDS.toApi(row.getId()), IDS.toApi(row.getOrderNo()),
                    displayStatus(row), money(row.getPayAmount()),
                    instant(row.getAppointmentStartAt()), instant(row.getAppointmentEndAt()),
                    instant(row.getPaidAt()),
                    basic == null ? null : basic.nickname(),
                    basic == null ? null : externalAvatar(basic.avatarUrl()),
                    serviceNames.get(row.getId()),
                    pet == null ? null : pet.getPetName(),
                    pet == null ? null : pet.getPetType(),
                    pet == null ? null : pet.getBreedName()));
        }
        return List.copyOf(items);
    }

    /**
     * Only https external links (WeChat avatar CDN, ≤512 chars) are projected to the merchant
     * face; internal {@code asset:} forms stay inside the user domain. Mirrors the review-side
     * hardening agreed for CCR-M-REVIEW-VIEW-001 so both faces share one discipline.
     */
    private static String externalAvatar(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.length() > 512 || !avatarUrl.startsWith("https://")) {
            return null;
        }
        return avatarUrl;
    }

    /** Contract-69 loadBasics discipline, keyed by the page's own user ids. */
    private Map<Long, UserBasicDTO> loadBasics(List<OrderQueryViewEntity> rows) {
        List<Long> userIds = rows.stream().map(OrderQueryViewEntity::getUserId).distinct().toList();
        Map<Long, UserBasicDTO> basics = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        for (Long userId : userIds) {
            ids.add(IDS.toApi(userId));
            if (ids.size() == 200) {
                consume(basics, ids);
                ids = new ArrayList<>();
            }
        }
        consume(basics, ids);
        return basics;
    }

    private void consume(Map<Long, UserBasicDTO> basics, List<String> ids) {
        if (ids.isEmpty()) return;
        List<UserBasicDTO> batch;
        try {
            batch = users.listUsers(new UserQueryApi.UserBasicsQuery(List.copyOf(ids)));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "客户档案事实不可用");
        }
        if (batch == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "客户档案事实不可用");
        }
        for (UserBasicDTO basic : batch) {
            basics.put(Long.parseLong(basic.userId()), basic);
        }
    }

    private static String displayStatus(OrderQueryViewEntity row) {
        try {
            return OrderDisplayStatus.compute(new OrderDisplayStatus.Facts(row.getOrderStage(),
                    row.getRefundOrderId(), row.getRefundedAmount(), row.getPayAmount(),
                    row.getRefundApplicationStatus(), row.getAftersaleStatus()));
        } catch (RuntimeException unreadable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "订单状态投影不可用");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private static java.time.OffsetDateTime instant(java.time.LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
