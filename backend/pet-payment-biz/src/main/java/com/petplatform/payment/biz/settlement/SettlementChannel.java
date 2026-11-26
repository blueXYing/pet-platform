package com.petplatform.payment.biz.settlement;

import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedFallbackSubmit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedQueryAmt;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedSeparateSubmit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedSplitOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.ExpectedSplit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.FallbackInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryAmtInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryPlusInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SeparateInput;

/** Channel port for Lakala v3 SACS order split and fallback operations. Always called outside transactions. */
public interface SettlementChannel {
    VerifiedQueryAmt querySeparableAmount(QueryAmtInput input);
    VerifiedSeparateSubmit submitSeparate(SeparateInput input);
    VerifiedFallbackSubmit submitFallback(FallbackInput input);
    VerifiedSplitOutcome querySeparateOutcome(QueryPlusInput input, ExpectedSplit expected);
}
