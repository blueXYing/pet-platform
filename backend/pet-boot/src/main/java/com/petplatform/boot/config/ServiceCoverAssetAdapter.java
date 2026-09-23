package com.petplatform.boot.config;

import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverAssetPort;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.PrivateAssetFact;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.ResolveOwnedPrivateAssetsQuery;
import java.util.List;
import java.util.Objects;

/**
 * Cover material ownership facts for service submissions (SVCW-D4 v0.2). Bridges to the private
 * asset owner with the SERVICE_COVER purpose; the SERVICE_COVER upload type itself is the MER
 * domain writer's deliverable (31号 amendment, role B) — until it lands, resolveOwned simply
 * reports no SERVICE_COVER asset and submission fails closed with a validation error.
 */
public final class ServiceCoverAssetAdapter implements ServiceCoverAssetPort {
    static final String SERVICE_COVER_PURPOSE = "SERVICE_COVER";
    private final PrivateAssetApi assets;

    public ServiceCoverAssetAdapter(PrivateAssetApi assets) {
        this.assets = Objects.requireNonNull(assets, "assets is required");
    }

    @Override
    public List<CoverAssetFact> resolveOwned(String ownerUserId, List<String> assetIds) {
        if (ownerUserId == null || assetIds == null || assetIds.isEmpty()) return List.of();
        List<PrivateAssetFact> facts =
                assets.resolveOwned(
                        new ResolveOwnedPrivateAssetsQuery(ownerUserId, assetIds,
                                SERVICE_COVER_PURPOSE));
        if (facts == null) return List.of();
        return facts.stream()
                .map(f -> new CoverAssetFact(
                        f.assetId(), f.ownerUserId(),
                        f.status() == null ? null : f.status().name(),
                        f.mediaType(), f.bytes()))
                .toList();
    }
}
