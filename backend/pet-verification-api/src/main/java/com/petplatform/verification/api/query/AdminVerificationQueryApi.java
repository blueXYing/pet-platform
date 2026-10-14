package com.petplatform.verification.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only platform verification-record face for the single operator (contract 63, HTTP10
 * §5.1 verification-log read): list, detail with the attempt chain and risk attempts used for
 * dispute tracing (运营 PRD 6.3.5). The kernel re-proves the current ADMIN_WEB session, the
 * {@code order.verification-log.read} action and the resource scope on every call. Credential
 * secrets (code cipher, lookup key/hash) never cross this surface; attempts expose only the
 * result/fail-reason facts the dispute flow needs. No write path exists.
 */
public interface AdminVerificationQueryApi {

    /** One page of platform verification records, newest first (verified_at DESC, id DESC). */
    PageResult<AdminVerificationSummary> listVerifications(AdminVerificationListQuery query);

    /** One verification detail: record + attempt chain + risk attempts for that order. */
    AdminVerificationDetail getVerification(AdminVerificationQuery query);

    record AdminVerificationSummary(
            String verificationId, String verificationNo, String orderId, String storeId,
            String verifyMethod, OffsetDateTime verifiedAt) {}

    record AdminAttemptView(
            String attemptId, String result, String failReason, OffsetDateTime attemptedAt) {}

    record AdminRiskAttemptView(
            String riskAttemptId, String resultCode, boolean countsFailure, OffsetDateTime attemptedAt) {}

    record AdminVerificationDetail(
            String verificationId, String verificationNo, String orderId, String storeId,
            String verifyMethod, OffsetDateTime verifiedAt, String operatorStaffId,
            List<AdminAttemptView> attempts, List<AdminRiskAttemptView> riskAttempts) {}

    /** Trusted final-authorization input re-checked inside the verification kernel per call. */
    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    record AdminVerificationListQuery(
            String orderId, String storeId, String method, OffsetDateTime verifiedFrom,
            OffsetDateTime verifiedTo, int page, int pageSize, QueryContext context,
            AdminAuthorization authorization) {}

    record AdminVerificationQuery(String verificationId, QueryContext context, AdminAuthorization authorization) {}
}
