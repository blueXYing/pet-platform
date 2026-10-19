package com.petplatform.order.biz.apiimpl;

import com.petplatform.order.api.query.OrderDeletionFactsApi;
import com.petplatform.order.biz.infrastructure.persistence.OrderDeletionFactsStore;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the order-owned deletion-blocking fact port (contract 66 /
 * CCR-USR-DELETION-001). Read-only, subject-free; malformed subjects read as "no blocking
 * orders" exactly like an empty history — the consumer fail-closes on unavailability, never
 * on an empty fact.
 */
public final class OrderDeletionFactsApiImpl implements OrderDeletionFactsApi {
    private final OrderDeletionFactsStore store;

    public OrderDeletionFactsApiImpl(DataSource dataSource) {
        this.store = new OrderDeletionFactsStore(Objects.requireNonNull(dataSource, "dataSource is required"));
    }

    @Override
    public boolean userHasBlockingOrder(UserDeletionFactQuery query) {
        long userId = parse(query.userId());
        if (userId <= 0) return false;
        return store.read(mapper -> mapper.countBlockingForUser(userId)) > 0;
    }

    @Override
    public boolean storesHaveBlockingOrder(StoreDeletionFactQuery query) {
        List<Long> storeIds = query.storeIds().stream().map(OrderDeletionFactsApiImpl::parse)
                .filter(id -> id > 0).distinct().toList();
        if (storeIds.isEmpty()) return false;
        return store.read(mapper -> mapper.countBlockingForStores(storeIds)) > 0;
    }

    private static long parse(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return -1;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }
}
