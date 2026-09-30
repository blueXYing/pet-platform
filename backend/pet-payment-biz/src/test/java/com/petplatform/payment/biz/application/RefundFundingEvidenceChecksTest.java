package com.petplatform.payment.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi.*;
import com.petplatform.payment.api.query.RefundFundingEvidenceChecks;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RefundFundingEvidenceChecksTest {
 private static final OffsetDateTime NOW=OffsetDateTime.parse("2026-09-30T00:00:00Z");
 private static FundingCheck check(String phase,String type,String amount){return new FundingCheck("1","2","3","4","original-trade","5","6","7","8","9","10",type,new BigDecimal(amount),new BigDecimal("100.00"),"CNY",phase,"FIRST_SEND".equals(phase)?"11":null,"FIRST_SEND".equals(phase)?"12":null,"FIRST_SEND".equals(phase)?"0":null);}
 private static FundingEvidence evidence(FundingCheck c,String state,String eligibility,OffsetDateTime until){return new FundingEvidence("TEST-ONLY:proof-1","TEST-ONLY:authority","test-contract-1","test-ledger-1","3","TEST-ONLY:audit-1",RefundFundingEvidenceChecks.hash(c),state,eligibility,"test-policy-1",c.requestedRefundAmount(),"CNY",NOW.minusSeconds(1),NOW,until,"TEST-ONLY:fence-1");}
 @Test void absenceUnknownAndExpiredAuthorityCannotAuthorize(){
  var c=check("DECISION_COMMIT","FULL","100.00");assertThrows(ApiException.class,()->RefundFundingEvidenceChecks.requireAllowed(c,null,NOW));
  assertThrows(ApiException.class,()->RefundFundingEvidenceChecks.requireAllowed(c,evidence(c,"UNKNOWN","ALLOWED",NOW.plusSeconds(30)),NOW));
  assertThrows(ApiException.class,()->RefundFundingEvidenceChecks.requireAllowed(c,evidence(c,"UNSETTLED","UNKNOWN",NOW.plusSeconds(30)),NOW));
  assertThrows(ApiException.class,()->RefundFundingEvidenceChecks.requireAllowed(c,evidence(c,"SETTLED","ALLOWED",NOW),NOW));
 }
 @Test void authoritativeDecisionAndFirstSendAreDistinctBindings(){
  var decision=check("DECISION_COMMIT","PARTIAL","40.25");var send=check("FIRST_SEND","PARTIAL","40.25");
  assertDoesNotThrow(()->RefundFundingEvidenceChecks.requireAllowed(decision,evidence(decision,"SETTLED","ALLOWED",NOW.plusSeconds(30)),NOW));
  assertNotEquals(RefundFundingEvidenceChecks.hash(decision),RefundFundingEvidenceChecks.hash(send));
  assertThrows(ApiException.class,()->RefundFundingEvidenceChecks.requireAllowed(send,evidence(decision,"SETTLED","ALLOWED",NOW.plusSeconds(30)),NOW));
  assertDoesNotThrow(()->RefundFundingEvidenceChecks.requireAllowed(send,evidence(send,"SETTLED","ALLOWED",NOW.plusSeconds(30)),NOW));
 }
 @Test void changedAmountCannotReuseEvidenceAndPartialBoundsAreStrict(){
  var c=check("DECISION_COMMIT","PARTIAL","1.00");var e=evidence(c,"UNSETTLED","ALLOWED",NOW.plusSeconds(30));
  assertThrows(ApiException.class,()->RefundFundingEvidenceChecks.requireAllowed(check("DECISION_COMMIT","PARTIAL","2.00"),e,NOW));
  for(String value:new String[]{"0","-1","100","100.01","0.001"})assertThrows(ApiException.class,()->RefundFundingEvidenceChecks.amount("PARTIAL",new BigDecimal(value),new BigDecimal("100.00")),value);
  assertThrows(ApiException.class,()->RefundFundingEvidenceChecks.amount("FULL",new BigDecimal("99.99"),new BigDecimal("100.00")));
 }
 @Test void ratioIsSixDecimalsButNeverDefinesFullOrPartial(){
  var max=new BigDecimal("9999999999999999.99");
  assertEquals(new BigDecimal("0.333333"),RefundFundingEvidenceChecks.ratio(new BigDecimal("1.00"),new BigDecimal("3.00")));
  assertEquals(new BigDecimal("0.000000"),RefundFundingEvidenceChecks.ratio(new BigDecimal("0.01"),max));
  assertEquals(new BigDecimal("1.000000"),RefundFundingEvidenceChecks.ratio(max.subtract(new BigDecimal("0.01")),max));
  assertDoesNotThrow(()->RefundFundingEvidenceChecks.amount("PARTIAL",max.subtract(new BigDecimal("0.01")),max));
  assertThrows(ApiException.class,()->RefundFundingEvidenceChecks.amount("FULL",max.subtract(new BigDecimal("0.01")),max));
 }
 @Test void amountCanonicalizationPreservesSemanticEquality(){
  assertEquals(RefundFundingEvidenceChecks.hash(check("DECISION_COMMIT","PARTIAL","1")),RefundFundingEvidenceChecks.hash(check("DECISION_COMMIT","PARTIAL","1.00")));
  assertNotEquals(RefundFundingEvidenceChecks.hash(check("DECISION_COMMIT","FULL","100.00")),RefundFundingEvidenceChecks.hash(check("DECISION_COMMIT","PARTIAL","100.00")));
 }
}
