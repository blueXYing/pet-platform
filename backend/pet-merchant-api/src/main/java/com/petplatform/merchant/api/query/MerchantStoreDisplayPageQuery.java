package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;
import java.util.List;
import java.util.Set;

/**
 * STR-D6 display page input. {@code cityCodes} is the closed set of server-controlled open cities
 * resolved by the boot adapter (biz never decides what "open" means); an empty list matches no
 * store. {@code keyword} is the optional C-side search term (HTTP10 §3.3.2 search supplement,
 * 2026-10-19 ruling): already trimmed and length-bounded by the adapter, matched as a
 * case-insensitive substring against storeName and merchantName only — no service/百科 scope, no
 * ranking. {@code null} means no keyword filter. {@code storeIds} is the contract-74 §3.2
 * category filter: {@code null} means no category filter, an empty set means the resolved category
 * has no stores (empty page), and a non-null set keeps only those stores. The context is
 * linkage-only and never an authority.
 */
public record MerchantStoreDisplayPageQuery(
        List<String> cityCodes, String keyword, int page, int pageSize, QueryContext context,
        boolean promotedOnly, Set<Long> storeIds) {

    public MerchantStoreDisplayPageQuery {
        cityCodes = cityCodes == null ? List.of() : List.copyOf(cityCodes);
        storeIds = storeIds == null ? null : Set.copyOf(storeIds);
    }

    /** Backward-compatible form without the contract-74 category filter. */
    public MerchantStoreDisplayPageQuery(
            List<String> cityCodes, String keyword, int page, int pageSize, QueryContext context,
            boolean promotedOnly) {
        this(cityCodes, keyword, page, pageSize, context, promotedOnly, null);
    }
}
