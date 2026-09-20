package com.petplatform.boot.config;

import static com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;

import com.petplatform.admin.api.dto.*;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;
import com.petplatform.merchant.api.query.MerchantApplicationQueryApi;
import com.petplatform.thirdparty.api.PrivateAssetReadAuthorizer;
import java.util.Objects;
import java.util.function.Supplier;

/** Current resource, claimant and RBAC proof used inside the private-asset transaction. */
public final class PrivateAssetReadAuthorizationAdapter implements PrivateAssetReadAuthorizer {
  private final Supplier<MerchantApplicationQueryApi> merchants;
  private final AdminAuthorizationQueryApi authorization;

  public PrivateAssetReadAuthorizationAdapter(
      MerchantApplicationQueryApi merchants, AdminAuthorizationQueryApi authorization) {
    this(() -> Objects.requireNonNull(merchants), authorization);
  }

  public PrivateAssetReadAuthorizationAdapter(
      Supplier<MerchantApplicationQueryApi> merchants, AdminAuthorizationQueryApi authorization) {
    this.merchants = Objects.requireNonNull(merchants);
    this.authorization = Objects.requireNonNull(authorization);
  }

  @Override
  public ReadAuthorizationProof authorize(ReadAuthorizationRequest request) {
    if (request == null) throw forbidden();
    try {
      MerchantPrivateMaterialAccessFact fact =
          Objects.requireNonNull(merchants.get(), "merchant query API unavailable")
              .provePrivateMaterialAccess(
                  new MerchantPrivateMaterialAccessQuery(
                      request.applicationId(),
                      request.revisionId(),
                      request.assetId(),
                      request.operatorId()));
      AdminResourceScope resource =
          new AdminResourceScope(
              "MERCHANT_APPLICATION",
              fact.applicationId(),
              fact.merchantId(),
              fact.cityCode(),
              fact.scopeVersion());
      // EXECUTE is intentional for both issue and consume: the admin owner then joins the
      // private-asset transaction instead of opening a REQUIRES_NEW read that releases its locks.
      AdminActionDecision decide = check(request, resource, "merchant.application.decide");
      AdminActionDecision reveal = check(request, resource, "merchant.identity.reveal");
      if (!decide.allowed() || !reveal.allowed()) throw forbidden();
      if (!decide.authzVersion().equals(reveal.authzVersion()))
        throw new ApiException(
            CommonApiCodes.CONFLICT,
            "authorization changed while checking private material access");
      return new ReadAuthorizationProof(
          fact.materialId(),
          fact.materialType(),
          fact.ownerUserId(),
          fact.privateAssetId(),
          fact.materialSha256(),
          fact.submittedRevisionId(),
          decide.authzVersion(),
          fact.scopeVersion());
    } catch (ApiException known) {
      throw known;
    } catch (RuntimeException failure) {
      throw new ApiException(
          CommonApiCodes.DEPENDENCY_UNAVAILABLE, "private material authorization is unavailable");
    }
  }

  private AdminActionDecision check(
      ReadAuthorizationRequest request, AdminResourceScope resource, String action) {
    return authorization.check(
        new AdminActionCheckQuery(
            request.sessionId(),
            request.sessionGeneration(),
            request.operatorId(),
            action,
            resource,
            request.purposeCode(),
            AdminActionCheckQuery.CheckPhase.EXECUTE));
  }

  private static ApiException forbidden() {
    return new ApiException(CommonApiCodes.FORBIDDEN, "private material access is forbidden");
  }
}
