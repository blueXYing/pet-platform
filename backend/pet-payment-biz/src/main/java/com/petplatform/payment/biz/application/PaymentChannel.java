package com.petplatform.payment.biz.application;

import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.CloseAcknowledgement;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.CloseInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.ExpectedPayment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryResult;

/** Verified external facts only. A transport failure is indeterminate, never a payment failure. */
public interface PaymentChannel {
    VerifiedPreorder submitPreorder(PreorderInput input, RequestNonce nonce);
    VerifiedQuery lookup(QueryInput input, ExpectedPayment expected, RequestNonce nonce);
    VerifiedClose requestClose(CloseInput input, RequestNonce nonce);

    record VerifiedPreorder(PreorderResult result, String responseSha256) {}
    record VerifiedQuery(QueryResult result, String responseSha256) {}
    record VerifiedClose(CloseAcknowledgement result, String responseSha256) {}
}
