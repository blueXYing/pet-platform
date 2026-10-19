package com.petplatform.customerservice.biz.application;

import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.PrivateAssetFact;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.ResolveOwnedPrivateAssetsQuery;
import java.util.List;

/**
 * Narrow module-local view of the thirdparty-owned private-asset read port (31号): the
 * feedback kernel only ever re-proves that submitted attachment ids are READY assets owned by
 * the submitter under the FEEDBACK_ATTACHMENT purpose — it never uploads, grants or consumes.
 * The boot adapter delegates to the real PrivateAssetApi and fails closed (503) when the
 * private-asset slice is not assembled; api-only, no biz dependency, no private Mapper access.
 */
public final class FeedbackAssetProofPort {

    private FeedbackAssetProofPort() {}

    @FunctionalInterface
    public interface Port {
        List<PrivateAssetFact> resolveOwned(ResolveOwnedPrivateAssetsQuery query);
    }
}
