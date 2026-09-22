package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;
import java.util.List;

/**
 * STR-D6 display page input. {@code cityCodes} is the closed set of server-controlled open cities
 * resolved by the boot adapter (biz never decides what "open" means); an empty list matches no
 * store. The context is linkage-only and never an authority.
 */
public record MerchantStoreDisplayPageQuery(
        List<String> cityCodes, int page, int pageSize, QueryContext context) {

    public MerchantStoreDisplayPageQuery {
        cityCodes = cityCodes == null ? List.of() : List.copyOf(cityCodes);
    }
}
