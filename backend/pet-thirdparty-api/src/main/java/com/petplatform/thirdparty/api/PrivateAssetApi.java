package com.petplatform.thirdparty.api;

import static com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;

import java.util.List;

/**
 * Internal owner for private merchant-application assets. No method exposes an object key or URL.
 */
public interface PrivateAssetApi {
  UploadPrivateAssetResult upload(UploadPrivateAssetCommand command);

  List<PrivateAssetFact> resolveOwned(ResolveOwnedPrivateAssetsQuery query);

  IssuedPrivateAssetReadGrant issueReadGrant(IssuePrivateAssetReadGrantCommand command);

  PrivateAssetContent consumeReadGrant(ConsumePrivateAssetReadGrantCommand command);
}
