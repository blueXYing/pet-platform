package com.petplatform.payment.api.query;
import com.petplatform.common.*;
import com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

/** Shared canonical binding; a successful check does not manufacture an authoritative provider. */
public final class RefundFundingEvidenceChecks {
 private RefundFundingEvidenceChecks() {}
 public static String hash(FundingCheck c) {
  if(c==null)throw unavailable();
  var parts=Arrays.asList(c.orderId(),c.paymentId(),c.paymentNo(),c.paymentSuccessEventId(),c.channelTradeNo(),c.userId(),c.merchantId(),c.storeId(),c.caseId(),c.decisionId(),c.commandId(),c.refundType(),money(c.requestedRefundAmount()),money(c.originalPaidAmount()),c.currency(),c.phase(),c.refundOrderId(),c.refundNo(),c.bindingVersion());
  var out=new StringBuilder("aftersale-funding-v1;");
  for(var p:parts)out.append(p==null?"-1:":p.getBytes(StandardCharsets.UTF_8).length+":"+p).append(';');
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw unavailable();}
 }
 public static void requireAllowed(FundingCheck c,FundingEvidence e,OffsetDateTime now) {
  try{
   var ids=new DecimalPublicIdCodec();for(var v:List.of(c.orderId(),c.paymentId(),c.paymentNo(),c.paymentSuccessEventId(),c.userId(),c.merchantId(),c.storeId(),c.caseId(),c.decisionId(),c.commandId()))ids.fromApi(v);
   amount(c.refundType(),c.requestedRefundAmount(),c.originalPaidAmount());
   if(c.channelTradeNo()==null||c.channelTradeNo().isBlank()||c.channelTradeNo().length()>128||c.channelTradeNo().codePoints().anyMatch(Character::isISOControl))throw unavailable();
   if(!"CNY".equals(c.currency())||!Set.of("DECISION_COMMIT","FIRST_SEND").contains(c.phase()))throw unavailable();
   if("FIRST_SEND".equals(c.phase())){ids.fromApi(c.refundOrderId());ids.fromApi(c.refundNo());if(!"0".equals(c.bindingVersion()))throw unavailable();}
   else if(c.refundOrderId()!=null||c.refundNo()!=null||c.bindingVersion()!=null)throw unavailable();
   if(e==null||!"ALLOWED".equals(e.eligibility())||!Set.of("UNSETTLED","SETTLED").contains(e.settlementState())
      ||!hash(c).equals(e.requestBindingSha256())||!c.currency().equals(e.currency())||e.authorizedRefundAmount()==null
      ||c.requestedRefundAmount().compareTo(e.authorizedRefundAmount())!=0)throw unavailable();
   for(var v:List.of(e.evidenceId(),e.authorityId(),e.authorityContractVersion(),e.sourceFactId(),e.sourceFactVersion(),e.authorityEvidenceRef(),e.policyVersion(),e.fencingReference()))
    if(v.isBlank()||v.length()>191||v.codePoints().anyMatch(Character::isISOControl))throw unavailable();
   for(var t:List.of(now,e.observedAt(),e.checkedAt(),e.validUntil()))PublicContractChecks.requireMillisecondPrecision(t);
   if(e.observedAt().isAfter(e.checkedAt())||e.checkedAt().isAfter(now)||!e.validUntil().isAfter(now))throw unavailable();
  }catch(RuntimeException ex){throw unavailable();}
 }
 public static void amount(String type,BigDecimal amount,BigDecimal paid){
  money(amount);money(paid);if(amount.signum()<=0||paid.signum()<=0||amount.compareTo(paid)>0
   ||"FULL".equals(type)&&amount.compareTo(paid)!=0||"PARTIAL".equals(type)&&amount.compareTo(paid)>=0||!Set.of("FULL","PARTIAL").contains(type))throw unavailable();
 }
 public static BigDecimal ratio(BigDecimal amount,BigDecimal paid){money(amount);money(paid);if(paid.signum()<=0)throw unavailable();return amount.divide(paid,6,RoundingMode.HALF_UP);}
 private static String money(BigDecimal v){if(v==null||v.scale()>2||v.precision()-v.scale()>16)throw unavailable();return v.setScale(2,RoundingMode.UNNECESSARY).toPlainString();}
 private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Authoritative refund funding evidence unavailable");}
}
