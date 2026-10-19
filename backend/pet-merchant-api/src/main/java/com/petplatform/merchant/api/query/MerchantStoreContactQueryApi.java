package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.MerchantStoreContactDTO;

/**
 * Order-contact store phone facts (HTTP10 §3.7 supplement, 2026-10-19 ruling "联系电话唤起拨号").
 *
 * <p>Read-only facts for the order domain's composition needs: the C buyer with an existing
 * order must be able to reach the merchant (PRD 5.1.17), so the order detail adapter needs the
 * store contact phone — masked for display plus the raw number for system dialing. This is a
 * deliberately narrow read: it grants no merchant-operation authority and exposes nothing beyond
 * the store's own business contact line. Visibility is NOT the catalog three-condition
 * conjunction on purpose: a paid order stays contactable even if the merchant later stops
 * accepting new orders or the store goes offline; only row existence matters.
 *
 * <p>Error contract (same split as the other merchant reads, never conflated): a positively
 * confirmed missing store throws NOT_FOUND (the caller degrades the contact section to null);
 * read failures throw DEPENDENCY_UNAVAILABLE (the caller also degrades — the order facts stay
 * authoritative and must never fail because merchant facts fail).
 */
public interface MerchantStoreContactQueryApi {

    MerchantStoreContactDTO getStoreContact(StoreIdQuery query);
}
