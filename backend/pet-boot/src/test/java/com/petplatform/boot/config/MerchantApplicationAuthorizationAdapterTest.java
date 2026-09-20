package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.api.dto.AdminActionDecision;
import com.petplatform.common.ApiException;
import com.petplatform.merchant.biz.application.ApplicationFinalAuthorizationPort;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class MerchantApplicationAuthorizationAdapterTest {
  @Test
  void carriesLockedResourceAndSessionReferenceAndPreservesCurrentDenial() {
    var at = OffsetDateTime.parse("2026-09-17T11:00:00Z");
    var adapter =
        new MerchantApplicationAuthorizationAdapter(
            query -> {
              assertEquals("101", query.sessionId());
              assertEquals(8, query.sessionGeneration());
              assertEquals("201", query.operatorId());
              assertEquals("301", query.resource().resourceId());
              assertEquals("401", query.resource().merchantId());
              assertEquals("open-city-a", query.resource().cityCode());
              assertEquals("7", query.resource().scopeVersion());
              assertEquals("EXECUTE", query.phase().name());
              return new AdminActionDecision(false, at, "1:4", "RESOURCE_SCOPE_DENIED");
            });
    var result = adapter.check(check());
    assertFalse(result.allowed());
    assertEquals("RESOURCE_SCOPE_DENIED", result.reasonCode());
    assertEquals(at, result.checkedAt());
  }

  @Test
  void dependencyErrorsRemainClosedWithoutOriginalErrorOrSecret() {
    var adapter =
        new MerchantApplicationAuthorizationAdapter(
            query -> {
              throw new IllegalStateException("SQL-SECRET-MUST-NOT-ESCAPE");
            });
    var error = assertThrows(ApiException.class, () -> adapter.check(check()));
    assertFalse(error.getMessage().contains("SQL-SECRET"));
    assertNull(error.getCause());
  }

  @Test
  void collectionCannotUseResourceOnlyAuthorizationAsAnAllowFallback() {
    var adapter =
        new MerchantApplicationAuthorizationAdapter(
            query -> new AdminActionDecision(true, OffsetDateTime.now(), "1:1", "ALLOWED"));
    assertThrows(
        ApiException.class,
        () ->
            adapter.checkCollection(
                new ApplicationFinalAuthorizationPort.CollectionCheck(
                    "101",
                    8,
                    "201",
                    "merchant.application.read",
                    "APPLICATION_READ",
                    ApplicationFinalAuthorizationPort.Phase.READ_RESULT)));
  }

  private ApplicationFinalAuthorizationPort.Check check() {
    return new ApplicationFinalAuthorizationPort.Check(
        "101",
        8,
        "201",
        "merchant.application.decide",
        new ApplicationFinalAuthorizationPort.Resource(
            "MERCHANT_APPLICATION", "301", "401", "open-city-a", "7"),
        "APPLICATION_REVIEW",
        ApplicationFinalAuthorizationPort.Phase.EXECUTE);
  }
}
