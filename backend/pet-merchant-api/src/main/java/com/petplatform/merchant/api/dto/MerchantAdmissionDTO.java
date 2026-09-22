package com.petplatform.merchant.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Entry-mode admission facts (HTTP10 admission table; CCR-W2-ADMISSION-001 §3). Entry hint
 * only: every business command still performs its own authorization and eligibility checks.
 */
public record MerchantAdmissionDTO(
        String merchantId,
        String storeId,
        String membershipKind,
        String admission,
        OffsetDateTime checkedAt,
        String authzVersion,
        ApplicationFact application,
        SigningFact signing,
        String storeStatus,
        String merchantStatus,
        Boolean staffEnabled,
        List<String> allowedActions,
        List<String> reasonCodes,
        List<AdmissionStep> nextSteps) {

    public record ApplicationFact(String status) {}

    public record SigningFact(String status) {}

    public record AdmissionStep(String type) {}
}
