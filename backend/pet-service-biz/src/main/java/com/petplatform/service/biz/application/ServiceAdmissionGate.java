package com.petplatform.service.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantAdmissionDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQuery;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import java.util.Objects;

/**
 * Owner admission gate shared by the service write commands and workbench reads (SVCW-D5). Built
 * on the already-approved owner-scoped admission facts (CCR-W2-ADMISSION-001): NOT_FOUND without
 * the owned-store fact (anti-enumeration, indistinguishable from a missing resource), 503 on
 * unreadable/damaged facts, 409 SERVICE_STATE_NOT_ALLOWED when the facts are clear but the
 * merchant/store is not operable (DENIED/LIMITED).
 */
public final class ServiceAdmissionGate {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final MerchantAdmissionQueryApi admissions;

    public ServiceAdmissionGate(MerchantAdmissionQueryApi admissions) {
        this.admissions = Objects.requireNonNull(admissions, "admissions is required");
    }

    public void requireOperable(QueryContext context, long merchantId, long storeId) {
        if (context == null || context.operatorId() == null || context.operatorId().isBlank()
                || context.operatorType() != OperatorType.USER) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "merchant session is required");
        }
        MerchantAdmissionDTO admission;
        try {
            admission =
                    admissions.getAdmission(
                            new MerchantAdmissionQuery(
                                    IDS.toApi(merchantId),
                                    IDS.toApi(storeId),
                                    new QueryContext(
                                            context.traceId(),
                                            OperatorType.USER,
                                            context.operatorId())));
        } catch (ApiException known) {
            throw known; // NOT_FOUND / 503 pass through, never widened
        } catch (RuntimeException unreadable) {
            unavailable("merchant admission facts are unavailable");
            return;
        }
        requireAllowed(admission);
    }

    private static void requireAllowed(MerchantAdmissionDTO admission) {
        if (admission == null
                || admission.admission() == null
                || admission.checkedAt() == null
                || admission.authzVersion() == null) {
            unavailable("admission facts are damaged");
        }
        if (!"OWNER".equals(admission.membershipKind())) notFound();
        if (!"ALLOWED".equals(admission.admission())) stateNotAllowed();
    }

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "service resource not found");
    }

    private static void stateNotAllowed() {
        throw new ApiException(
                com.petplatform.service.api.error.ServiceWriteApiCodes.SERVICE_STATE_NOT_ALLOWED,
                "当前商家/门店状态不允许该操作");
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
