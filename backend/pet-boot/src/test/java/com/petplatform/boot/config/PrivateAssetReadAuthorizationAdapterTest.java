package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.petplatform.admin.api.dto.*;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;
import com.petplatform.merchant.api.query.MerchantApplicationQueryApi;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class PrivateAssetReadAuthorizationAdapterTest {
  private final MerchantApplicationQueryApi merchants = mock(MerchantApplicationQueryApi.class);
  private final AdminAuthorizationQueryApi authorization = mock(AdminAuthorizationQueryApi.class);
  private final PrivateAssetReadAuthorizationAdapter adapter =
      new PrivateAssetReadAuthorizationAdapter(merchants, authorization);

  @Test
  void proofUsesLockedMerchantScopeClaimantAndBothCurrentPermissions() {
    when(merchants.provePrivateMaterialAccess(any())).thenReturn(fact());
    when(authorization.check(any()))
        .thenReturn(
            new AdminActionDecision(
                true, OffsetDateTime.parse("2026-09-20T01:00:00Z"), "8:12", "ALLOWED"));
    ReadAuthorizationProof proof = adapter.authorize(request());
    assertEquals("401", proof.materialId());
    assertEquals("501", proof.ownerUserId());
    assertEquals("a".repeat(64), proof.objectSha256());
    var checks = org.mockito.ArgumentCaptor.forClass(AdminActionCheckQuery.class);
    verify(authorization, times(2)).check(checks.capture());
    assertEquals(
        java.util.Set.of("merchant.application.decide", "merchant.identity.reveal"),
        checks.getAllValues().stream()
            .map(AdminActionCheckQuery::actionCode)
            .collect(java.util.stream.Collectors.toSet()));
    for (AdminActionCheckQuery check : checks.getAllValues()) {
      assertEquals("901", check.sessionId());
      assertEquals(3, check.sessionGeneration());
      assertEquals("701", check.operatorId());
      assertEquals("101", check.resource().resourceId());
      assertEquals("201", check.resource().merchantId());
      assertEquals("110100", check.resource().cityCode());
      assertEquals("7:2", check.resource().scopeVersion());
      assertEquals(AdminActionCheckQuery.CheckPhase.EXECUTE, check.phase());
    }
  }

  @Test
  void currentCrossScopeOrRevokedSessionDenialFailsClosed() {
    when(merchants.provePrivateMaterialAccess(any())).thenReturn(fact());
    when(authorization.check(any()))
        .thenReturn(
            new AdminActionDecision(
                false,
                OffsetDateTime.parse("2026-09-20T01:00:00Z"),
                "8:12",
                "RESOURCE_SCOPE_DENIED"));
    assertEquals(
        "COMMON_FORBIDDEN",
        assertThrows(ApiException.class, () -> adapter.authorize(request())).code());

    reset(authorization);
    when(authorization.check(any()))
        .thenReturn(
            new AdminActionDecision(
                false, OffsetDateTime.parse("2026-09-20T01:00:01Z"), "8:13", "SESSION_REVOKED"));
    assertEquals(
        "COMMON_FORBIDDEN",
        assertThrows(ApiException.class, () -> adapter.authorize(request())).code());
  }

  private static ReadAuthorizationRequest request() {
    return new ReadAuthorizationRequest(
        ReadAuthorizationPhase.CONSUME, "101", "301", "801", "701", "901", 3, "APPLICATION_REVIEW");
  }

  private static MerchantPrivateMaterialAccessFact fact() {
    return new MerchantPrivateMaterialAccessFact(
        "101",
        "201",
        "110100",
        "501",
        "7:2",
        "401",
        "801",
        "a".repeat(64),
        "ID_CARD_FRONT",
        "image/png",
        1024,
        "301",
        "CLAIMED",
        "701",
        "2");
  }
}
