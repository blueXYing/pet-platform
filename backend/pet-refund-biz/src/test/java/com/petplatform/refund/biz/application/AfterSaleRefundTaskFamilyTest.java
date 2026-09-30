package com.petplatform.refund.biz.application;
import com.petplatform.common.ApiException;
import com.petplatform.refund.api.dto.RefundExecutionFact;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AfterSaleRefundTaskFamilyTest {
 private static RefundExecutionFact fact(String source){return new RefundExecutionFact("1","2","3","4","5","6","7","8","9",null,"trade",new BigDecimal("100.00"),new BigDecimal("1.00"),OffsetDateTime.parse("2026-09-30T00:00:00Z"),"CNY","CREATED",0,"10",OffsetDateTime.parse("2026-09-30T00:00:00Z"),source,null,"11","12","PARTIAL");}
 @Test void aftersaleRecoveryNeverSelectsLegacyTaskFamily(){assertEquals("AFTERSALE_REFUND_SUBMIT",RefundExecutionService.taskType(fact("AFTERSALE_DECISION"),false));assertEquals("AFTERSALE_REFUND_CHANNEL_QUERY",RefundExecutionService.taskType(fact("AFTERSALE_DECISION"),true));}
 @Test void unknownFamilyDoesNotFallBackToLatePayment(){assertThrows(ApiException.class,()->RefundExecutionService.taskType(fact("UNKNOWN"),false));}
}
