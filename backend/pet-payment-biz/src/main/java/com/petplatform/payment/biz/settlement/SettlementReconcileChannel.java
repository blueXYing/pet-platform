package com.petplatform.payment.biz.settlement;

import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient.VerifiedDetailPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient.VerifiedResultListPage;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryDetailInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileProtocol.QueryResultListInput;

/** Outbound channel port for Lakala SACS reconciliation queries (1879 queryResultList & 1877 queryDetail). */
public interface SettlementReconcileChannel {
    VerifiedResultListPage queryResultList(QueryResultListInput input);
    VerifiedDetailPage queryDetail(QueryDetailInput input);
}
