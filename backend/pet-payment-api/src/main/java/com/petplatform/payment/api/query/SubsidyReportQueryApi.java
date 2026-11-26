package com.petplatform.payment.api.query;

import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportQuery;
import com.petplatform.payment.api.dto.ReconcileTypes.SubsidyReportResponse;

/**
 * Contract 80 §4.5 / Ruling 40 §3.2 / Ruling 22 strictly read-only platform subsidy payable report API.
 * Offline settlement happens outside the platform; no mark-paid or mark-settled action is permitted.
 */
public interface SubsidyReportQueryApi {
    SubsidyReportResponse querySubsidyReport(SubsidyReportQuery query);
}
