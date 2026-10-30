package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;
import java.util.List;

/**
 * Contract 74 §3.1 category-availability input (B-SVC-03): the caller passes the closed set of
 * trusted open-city codes resolved by the boot adapter; an empty list matches no store (fail
 * closed). The context is linkage-only and never an authority.
 */
public record MerchantDisplayStoreIdsQuery(List<String> cityCodes, QueryContext context) {

    public MerchantDisplayStoreIdsQuery {
        cityCodes = cityCodes == null ? List.of() : List.copyOf(cityCodes);
    }
}
