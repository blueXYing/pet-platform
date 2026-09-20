package com.petplatform.merchant.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.event.api.IntegrationEventPublisher;

/** Production-safe defaults: every unresolved private/external fact fails closed. */
public record MerchantApplicationDependencies(
    PrivateAssetQueryPort privateAssets,
    ApplicationValidationPorts.OpenCityReader cities,
    ApplicationValidationPorts.MapValidationPort maps,
    ApplicationValidationPorts.ProtectedValuePort protectedValues,
    SubjectCredentialPort credentials,
    ApplicationFinalAuthorizationPort authorization,
    IntegrationEventPublisher events) {
  public static MerchantApplicationDependencies unavailable() {
    return new MerchantApplicationDependencies(
        (owner, ids) -> fail("private asset provider is unavailable"),
        city -> fail("open city provider is unavailable"),
        (city, address, longitude, latitude) -> fail("map validation provider is unavailable"),
        (purpose, value) -> fail("protected value provider is unavailable"),
        (type, subject, identifier, basis) ->
            fail("credential protection/HMAC provider is unavailable"),
        check -> fail("final admin authorization provider is unavailable"),
        event -> fail("transactional outbox publisher is unavailable"));
  }

  private static <T> T fail(String message) {
    throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
  }
}
