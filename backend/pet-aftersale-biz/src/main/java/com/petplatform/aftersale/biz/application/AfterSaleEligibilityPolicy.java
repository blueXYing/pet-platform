package com.petplatform.aftersale.biz.application;

import com.petplatform.aftersale.api.query.AfterSaleCaseFactsApi.CaseFact;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi.Eligibility;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi;
import com.petplatform.refund.api.query.RefundApplicationHistoryFactsApi;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** AFS admission policy only; ORDER remains the sole owner of order display status. */
final class AfterSaleEligibilityPolicy {
    private AfterSaleEligibilityPolicy() {}

    static Eligibility evaluate(OrderAfterSaleFactsApi.Fact fact,
            RefundApplicationHistoryFactsApi.Fact history, CaseFact current, OffsetDateTime at) {
        String stage;
        OffsetDateTime anchor;
        if ("VERIFIED".equals(fact.verificationStatus())) {
            stage = "VERIFIED";
            anchor = fact.verifiedAt();
            if (anchor == null || fact.verificationId() == null) throw unavailable();
        } else if ("UNVERIFIED".equals(fact.verificationStatus())) {
            stage = "UNVERIFIED_POST_START";
            anchor = fact.appointmentStart();
            if (anchor == null) throw unavailable();
        } else {
            throw unavailable();
        }
        String denied = history.refundExists() ? "REFUND_ORDER_ALREADY_EXISTS"
                : history.activeApplication() != null ? "AFTERSALE_REFUND_APPLICATION_ACTIVE"
                : current.active() ? "AFTERSALE_ALREADY_ACTIVE" : null;
        if (denied == null && (at.isBefore(anchor) || at.isAfter(anchor.plusDays(7))))
            denied = "AFTERSALE_NOT_ELIGIBLE";
        if (denied == null && "UNVERIFIED_POST_START".equals(stage) && history.latestRejected() == null)
            denied = "AFTERSALE_NOT_ELIGIBLE";
        return new Eligibility(denied == null, stage,
                anchor.plusDays(7).withOffsetSameInstant(ZoneOffset.UTC).toString(), denied,
                current.active() ? current.afterSaleId() : null);
    }

    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "Aftersale eligibility source unavailable");
    }
}
