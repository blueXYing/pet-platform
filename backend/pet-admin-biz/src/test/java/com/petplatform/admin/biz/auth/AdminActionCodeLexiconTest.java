package com.petplatform.admin.biz.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;
import com.petplatform.admin.biz.domain.service.AdminPermissionEvaluator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FA-5 regression (2026-10-16): the AUTH action-code lexicon admits lowercase dotted segments
 * with hyphenated inner runs, so the DEPLOYED matrix spellings order.status-log.read and
 * order.verification-log.read (AUTH-001 rows 7/8, contract-63 read face) validate directly.
 * Before the widening, every authorization entry built from those codes threw
 * IllegalArgumentException and both trade read routes answered an unrecoverable 400 for any
 * operator, super admin included. The lexicon still refuses digit/uppercase/hyphen led segments,
 * empty or trailing segments, and stray separators.
 */
class AdminActionCodeLexiconTest {

  private static AdminActionCheckQuery check(String actionCode) {
    return new AdminActionCheckQuery(
        "9007199254740993", 7L, "9007199254740994", actionCode,
        new com.petplatform.admin.api.dto.AdminResourceScope(
            "ORDER", "9007199254740995", "9007199254740996", null, "3"),
        "ORDER_STATUS_LOG_READ",
        AdminActionCheckQuery.CheckPhase.READ_RESULT);
  }

  private static AdminCollectionActionCheckQuery collection(String actionCode) {
    return new AdminCollectionActionCheckQuery(
        "9007199254740993", 7L, "9007199254740994", actionCode,
        "ORDER_STATUS_LOG_READ",
        AdminActionCheckQuery.CheckPhase.READ_RESULT);
  }

  @Test void deployedHyphenatedTradeReadCodesValidate() {
    for (String deployed : List.of("order.status-log.read", "order.verification-log.read")) {
      assertDoesNotThrow(() -> check(deployed));
      assertDoesNotThrow(() -> collection(deployed));
      assertTrue(AdminPermissionEvaluator.DEPLOYED_ACTIONS.contains(deployed));
    }
  }

  @Test void dottedDisposalSpellingsKeepValidating() {
    for (String deployed : List.of(
        "refund.channel.query", "refund.retry", "order.abnormal.close", "merchant.disable",
        "service.force.offline", "coupon.instance.grant")) {
      assertDoesNotThrow(() -> check(deployed));
    }
  }

  @Test void hyphenPlacementStaysConstrained() {
    // Hyphens bind inside a segment: leading/trailing hyphens, double hyphens and hyphen-led
    // or digit-led segments stay invalid.
    for (String invalid : List.of(
        "order.-status.read", "order.status-.read", "order.sta--tus.read",
        "order..status-log", ".order.read", "order.read.", "Order.Status-Log.Read",
        "order.status log.read", "1order.status-log.read", "order", "order.",
        "order.status-log.read.extra-", "order.status-log..read")) {
      assertThrows(IllegalArgumentException.class, () -> check(invalid), invalid);
      assertThrows(IllegalArgumentException.class, () -> collection(invalid), invalid);
    }
  }

  @Test void multiHyphenSegmentsAndLongCodeBoundariesHold() {
    assertDoesNotThrow(() -> check("order.delivery-status-log.read"));
    assertThrows(IllegalArgumentException.class, () -> check("a".repeat(101) + ".read"));
    assertDoesNotThrow(() -> check("a." + "b".repeat(98)));
  }
}
