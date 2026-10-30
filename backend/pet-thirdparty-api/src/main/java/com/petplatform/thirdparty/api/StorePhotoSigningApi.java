package com.petplatform.thirdparty.api;

import com.petplatform.common.QueryContext;
import java.time.Instant;

/**
 * Contract 74 §3.4 store-avatar display signing (B-CARD-02, the ServiceCoverSigningApi pattern for
 * the same 31号 private-asset pipeline): a presigned HTTPS GET for one READY STORE_PHOTO asset.
 * Internal only — the composing card read must have already proven the store's C visibility; this
 * face re-checks the asset's current facts under its own short lock before local presigning.
 * Application/identity materials and covers can never be signed here.
 */
public interface StorePhotoSigningApi {

    SignedStorePhoto signStorePhoto(String assetId, QueryContext context);

    /** Object storage pointers and credentials never leave the owning module. */
    record SignedStorePhoto(String assetId, String signedUrl, Instant expiresAt) {}
}
