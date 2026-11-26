package com.petplatform.thirdparty.api;

import static com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.QueryContext;
import java.util.List;

/**
 * Internal owner for private merchant-application assets. No method exposes an object key or URL.
 */
public interface PrivateAssetApi {
  UploadPrivateAssetResult upload(UploadPrivateAssetCommand command);

  List<PrivateAssetFact> resolveOwned(ResolveOwnedPrivateAssetsQuery query);

  IssuedPrivateAssetReadGrant issueReadGrant(IssuePrivateAssetReadGrantCommand command);

  PrivateAssetContent consumeReadGrant(ConsumePrivateAssetReadGrantCommand command);

  /**
   * Internal system-to-system read of an immutable, scanned, un-watermarked {@code READY} private
   * asset for channel submission (e.g. Lakala 1084 split-receiver attachments). Never exposes raw
   * object keys or presigned URLs.
   */
  default PrivateAssetContent readReadyForSystem(String assetId, QueryContext context) {
    throw new ApiException(
        CommonApiCodes.DEPENDENCY_UNAVAILABLE, "system private asset read is unavailable");
  }
}
