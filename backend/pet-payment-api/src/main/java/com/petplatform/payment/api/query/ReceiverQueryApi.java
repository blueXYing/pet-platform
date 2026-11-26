package com.petplatform.payment.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.ReceiverTypes.ListReceiversQuery;
import com.petplatform.payment.api.dto.ReceiverTypes.MerchantReceiverStatusView;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverAccountView;
import com.petplatform.payment.api.dto.ReceiverTypes.ReceiverPage;

/** Read-only query API for split-receiver accounts (Contract 80 §4.2 & §5). */
public interface ReceiverQueryApi {
    ReceiverAccountView getByMerchantId(String merchantId, QueryContext context);
    ReceiverPage list(ListReceiversQuery query);
    MerchantReceiverStatusView getMerchantStatusReadOnly(String merchantId, QueryContext context);
}
