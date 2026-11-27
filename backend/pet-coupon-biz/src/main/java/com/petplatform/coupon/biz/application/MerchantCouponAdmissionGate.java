package com.petplatform.coupon.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.error.MerchantCouponApiCodes;
import com.petplatform.merchant.api.dto.MerchantAdmissionDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQuery;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import java.util.Objects;

/**
 * Owner admission of the merchant coupon face (contract 79 section 3), built on the approved
 * owner-scoped admission facts the service domain already uses: without the owned-store fact the
 * caller gets 404 (anti-enumeration, indistinguishable from a missing resource), unreadable facts
 * are 503, and a clear but non-operable merchant/store is 409.
 */
public final class MerchantCouponAdmissionGate {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final MerchantAdmissionQueryApi admissions;

    public MerchantCouponAdmissionGate(MerchantAdmissionQueryApi admissions) {
        this.admissions = Objects.requireNonNull(admissions, "admissions is required");
    }

    public void requireOperable(QueryContext context, long merchantId, long storeId) {
        if (context == null || context.operatorId() == null || context.operatorId().isBlank()
                || context.operatorType() != OperatorType.USER) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "merchant session is required");
        }
        MerchantAdmissionDTO admission;
        try {
            admission = admissions.getAdmission(new MerchantAdmissionQuery(
                    IDS.toApi(merchantId), IDS.toApi(storeId),
                    new QueryContext(context.traceId(), OperatorType.USER, context.operatorId())));
        } catch (ApiException known) {
            throw known; // NOT_FOUND / 503 pass through, never widened
        } catch (RuntimeException unreadable) {
            unavailable("merchant admission facts are unavailable");
            return;
        }
        if (admission == null || admission.admission() == null || admission.checkedAt() == null
                || admission.authzVersion() == null) {
            unavailable("admission facts are damaged");
        }
        if (!"OWNER".equals(admission.membershipKind())) {
            throw new ApiException(CommonApiCodes.NOT_FOUND, "商家券活动不存在");
        }
        if (!"ALLOWED".equals(admission.admission())) {
            throw new ApiException(MerchantCouponApiCodes.STATE_NOT_ALLOWED, "当前商家/门店状态不允许该操作");
        }
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
