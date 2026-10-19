package com.petplatform.order.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantCustomerQueryApi;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.CustomerDetail;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.CustomerGetQuery;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.CustomerListQuery;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.CustomerRow;
import com.petplatform.order.api.query.MerchantCustomerQueryApi.PetView;
import com.petplatform.order.biz.infrastructure.persistence.OrderQueryStore;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.CustomerAggregate;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.CustomerPet;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.dto.UserBasicDTO;
import com.petplatform.user.api.query.UserQueryApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Derived customer read model (contract 69). Every row is a user_id grouping of the store's
 * own orders; the OWNER coordinate is re-proven inside the guard transaction exactly like the
 * §4.1 list, then the aggregation runs over the double key so cross-store rows never exist.
 * Nickname/phoneMasked come from the user domain's batched masked basics read (contract 69
 * §2: the raw credential never crosses domains); pets reduce the store's own
 * order_pet_snapshot rows to name + type/breed level. Read-only: this service has no write
 * path, no export, and no plaintext phone surface.
 */
public final class MerchantCustomerQueryService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    /** Nickname search scans at most this many store aggregates (contract 69 §2 cap). */
    static final int SEARCH_SCAN_CAP = 1_000;
    static final int DETAIL_PET_CAP = 20;

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final OrderQueryStore store;
    private final ScheduleCapacityGuardApi guard;
    private final MerchantOrderAuthorityApi authority;
    private final UserQueryApi users;

    public MerchantCustomerQueryService(OrderQueryStore store, ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi authority, UserQueryApi users) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.authority = Objects.requireNonNull(authority, "authority is required");
        this.users = Objects.requireNonNull(users, "user basics facts are required");
    }

    public PageResult<CustomerRow> listCustomers(CustomerListQuery query) {
        Objects.requireNonNull(query, "query is required");
        long merchant = coordinate(query.merchantId());
        long storeKey = coordinate(query.storeId());
        String nickname = normalizedNickname(query.nickname());
        String suffix = normalizedSuffix(query.orderNoSuffix());
        if (nickname != null && suffix != null) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "昵称与订单号尾号搜索不可同时使用");
        }
        int page = query.page() < 1 ? 1 : query.page();
        int pageSize = query.pageSize() <= 0 ? DEFAULT_PAGE_SIZE
                : Math.min(query.pageSize(), MAX_PAGE_SIZE);
        return store.read((queries, mapper) -> {
            proveOwnership(query.merchantId(), query.storeId(), query.context());
            if (suffix != null) {
                // 订单号尾号搜索 (2026-10-19 expansion): the SQL matches only this store's own
                // order tails; the aggregate projection and ranking stay identical.
                long total = mapper.countStoreCustomersByOrderSuffix(merchant, storeKey, suffix);
                List<CustomerRow> items = total == 0 ? List.of()
                        : project(mapper.pageStoreCustomersByOrderSuffix(merchant, storeKey,
                                suffix, pageSize, (long) (page - 1) * pageSize));
                return new PageResult<>(items, total, page, pageSize);
            }
            if (nickname == null) {
                long total = mapper.countStoreCustomers(merchant, storeKey);
                List<CustomerRow> items = total == 0 ? List.of()
                        : project(mapper.pageStoreCustomers(merchant, storeKey, pageSize,
                                (long) (page - 1) * pageSize));
                return new PageResult<>(items, total, page, pageSize);
            }
            // Bounded scan for the contains filter (contract 69 §2): the aggregate SQL has no
            // nickname column by design — the user domain owns that fact.
            List<CustomerAggregate> scan = mapper.scanStoreCustomers(merchant,
                    storeKey, SEARCH_SCAN_CAP);
            List<CustomerAggregate> matched = new ArrayList<>();
            Map<Long, UserBasicDTO> basics = loadBasics(scan);
            for (CustomerAggregate row : scan) {
                UserBasicDTO basic = basics.get(row.getUserId());
                if (basic != null && basic.nickname() != null && basic.nickname().contains(nickname)) {
                    matched.add(row);
                }
            }
            int from = (int) Math.min(matched.size(), (long) (page - 1) * pageSize);
            int to = Math.min(matched.size(), from + pageSize);
            List<CustomerRow> items = project(matched.subList(from, to));
            return new PageResult<>(items, matched.size(), page, pageSize);
        });
    }

    public CustomerDetail getCustomer(CustomerGetQuery query) {
        Objects.requireNonNull(query, "query is required");
        long merchant = coordinate(query.merchantId());
        long storeKey = coordinate(query.storeId());
        long userId = coordinate(query.userId());
        return store.read((queries, mapper) -> {
            proveOwnership(query.merchantId(), query.storeId(), query.context());
            CustomerAggregate aggregate = mapper.storeCustomerAggregate(
                    merchant, storeKey, userId);
            if (aggregate == null) notFound();
            CustomerRow row = project(List.of(aggregate)).getFirst();
            List<PetView> pets = mapper.storeCustomerPets(merchant, storeKey, userId,
                    DETAIL_PET_CAP).stream()
                    .map(pet -> new PetView(pet.getPetName(), pet.getPetType(), pet.getBreedName()))
                    .toList();
            var noteRow = mapper.storeCustomerNote(storeKey, userId);
            CustomerDetail.NoteView note = noteRow == null || noteRow.getNote() == null ? null
                    : new CustomerDetail.NoteView(noteRow.getNote(),
                            Long.toString(noteRow.getVersion()),
                            noteRow.getUpdatedAt().atOffset(ZoneOffset.UTC));
            return new CustomerDetail(row, pets, note);
        });
    }

    private void proveOwnership(String merchantId, String storeId, QueryContext context) {
        if (context == null || context.operatorType() != OperatorType.USER
                || context.operatorId() == null) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "query subject is required");
        }
        guard.acquire(List.of(storeId), new QueryContext(context.traceId(), OperatorType.SYSTEM, null));
        authority.requireOwnerRead(merchantId, storeId, context);
    }

    private Map<Long, UserBasicDTO> loadBasics(List<CustomerAggregate> rows) {
        Map<Long, UserBasicDTO> basics = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        for (CustomerAggregate row : rows) {
            ids.add(IDS.toApi(row.getUserId()));
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

    private List<CustomerRow> project(List<CustomerAggregate> rows) {
        if (rows.isEmpty()) return List.of();
        Map<Long, UserBasicDTO> basics = loadBasics(rows);
        List<CustomerRow> items = new ArrayList<>(rows.size());
        for (CustomerAggregate row : rows) {
            UserBasicDTO basic = basics.get(row.getUserId());
            if (basic == null) {
                // The order exists but the account is gone/canceled: keep the derived row with
                // the least disclosure (no nickname, no phone), counts stay factual.
                basic = new UserBasicDTO(IDS.toApi(row.getUserId()), null, null, null);
            }
            BigDecimal spend = money(row.getTotalSpend());
            BigDecimal avg = row.getOrderCount() == null || row.getOrderCount() == 0 ? null
                    : spend.divide(BigDecimal.valueOf(row.getOrderCount()), 2, RoundingMode.DOWN);
            items.add(new CustomerRow(IDS.toApi(row.getUserId()), basic.nickname(), basic.mobileMasked(),
                    row.getOrderCount() == null ? 0 : row.getOrderCount(), spend, avg,
                    row.getLastVisitAt() == null ? null : row.getLastVisitAt().atOffset(ZoneOffset.UTC),
                    row.getTopPetName() == null ? null
                            : new PetView(row.getTopPetName(), row.getTopPetType(), row.getTopBreedName())));
        }
        return List.copyOf(items);
    }

    private static String normalizedNickname(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) > 64) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "搜索词过长");
        }
        return value;
    }

    /** 2..10 digits; leading zeros are part of the tail (RIGHT tail match in SQL). */
    static String normalizedSuffix(String value) {
        if (value == null || value.isEmpty()) return null;
        if (!value.matches("[0-9]{2,10}")) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "订单号尾号须为 2-10 位数字");
        }
        return value;
    }

    private static long coordinate(String value) {
        try {
            return IDS.fromApi(value);
        } catch (RuntimeException malformed) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "商家坐标不合法");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.DOWN);
    }

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "客户不存在");
    }
}
