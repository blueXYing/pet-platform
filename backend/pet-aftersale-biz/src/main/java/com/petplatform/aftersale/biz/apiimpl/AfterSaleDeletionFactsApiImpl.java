package com.petplatform.aftersale.biz.apiimpl;

import com.petplatform.aftersale.api.query.AfterSaleDeletionFactsApi;
import com.petplatform.aftersale.biz.infrastructure.persistence.AfterSaleDeletionFactsStore;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the aftersale-owned deletion-blocking fact port (contract 66 /
 * CCR-USR-DELETION-001). Read-only, subject-free; malformed subjects read as "no open case"
 * — the consumer fail-closes on unavailability, never on an empty fact.
 */
public final class AfterSaleDeletionFactsApiImpl implements AfterSaleDeletionFactsApi {
    private final AfterSaleDeletionFactsStore store;

    public AfterSaleDeletionFactsApiImpl(DataSource dataSource) {
        this.store = new AfterSaleDeletionFactsStore(Objects.requireNonNull(dataSource, "dataSource is required"));
    }

    @Override
    public boolean userHasOpenCase(UserDeletionFactQuery query) {
        long userId = parse(query.userId());
        if (userId <= 0) return false;
        return store.read(mapper -> mapper.countOpenForUser(userId)) > 0;
    }

    @Override
    public boolean storesHaveOpenCase(StoreDeletionFactQuery query) {
        List<Long> storeIds = query.storeIds().stream().map(AfterSaleDeletionFactsApiImpl::parse)
                .filter(id -> id > 0).distinct().toList();
        if (storeIds.isEmpty()) return false;
        return store.read(mapper -> mapper.countOpenForStores(storeIds)) > 0;
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
