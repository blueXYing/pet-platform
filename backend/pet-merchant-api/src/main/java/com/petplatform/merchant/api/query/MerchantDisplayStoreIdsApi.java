package com.petplatform.merchant.api.query;

import java.util.List;

/**
 * Contract 74 §3.1 display-scope store-id read (B-SVC-03 supplement to SVC-D5). Returns the
 * decimal store ids of stores that pass the approved three-condition display conjunction and whose
 * compat city belongs to the requested open-city set, ascending by numeric store id. It exists so
 * cross-domain category availability can be composed without per-category or per-store N+1 reads;
 * it grants no merchant-operation authority and returns no profile fields.
 *
 * <p>Error contract (same split as SVC-D5, never conflated): read failures, facts-source failures,
 * unknown status values and profile-compat integrity violations throw DEPENDENCY_UNAVAILABLE (the
 * caller maps it to 503). A city set without matching stores is a normal empty list.
 */
public interface MerchantDisplayStoreIdsApi {

    List<String> listDisplayStoreIds(MerchantDisplayStoreIdsQuery query);
}
