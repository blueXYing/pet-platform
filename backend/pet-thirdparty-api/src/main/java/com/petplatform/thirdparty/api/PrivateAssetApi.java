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

  /** Purpose of operator-uploaded merchant acquiring onboarding attachments (CCR-PAY-ONBOARD-001). */
  String MERCHANT_ONBOARDING_ATTACHMENT = "MERCHANT_ONBOARDING_ATTACHMENT";

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

  /**
   * Operator-uploaded attachment for merchant acquiring onboarding (CCR-PAY-ONBOARD-001, contract 81
   * §4.12). {@code purpose} must be {@code MERCHANT_ONBOARDING_ATTACHMENT}, the command context must be
   * a {@code PLATFORM_OPERATOR}, and the asset belongs to the merchant's owner user. Rides the same
   * immutable upload/scan/normalize pipeline; consumer uploads never accept this purpose.
   */
  default UploadPrivateAssetResult uploadOperatorAttachment(UploadPrivateAssetCommand command) {
    throw new ApiException(
        CommonApiCodes.DEPENDENCY_UNAVAILABLE, "operator private asset upload is unavailable");
  }
}
