package com.petplatform.service.api.query;

import com.petplatform.common.QueryContext;
import java.util.List;

/**
 * Contract 74 §3.1 C-end category catalog input (B-SVC-03): the adapter resolves the trusted open
 * city directory (an explicit cityCode or the whole current open set) and passes the resulting
 * closed list of city codes. The service domain never decides what "open" means; an empty list
 * matches no store (fail closed). The context is linkage-only and never an authority.
 */
public record CatalogServiceCategoryQuery(List<String> cityCodes, QueryContext context) {

    public CatalogServiceCategoryQuery {
        cityCodes = cityCodes == null ? List.of() : List.copyOf(cityCodes);
    }
}
