package com.petplatform.merchant.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantAgreementStore;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** Combines real immutable agreement acceptance with the still-injected application review fact. */
public final class MerchantAgreementEligibilityFactsAdapter implements MerchantEligibilityFactsReader {
    private final MerchantAgreementStore agreements;
    private final ApplicationReviewFactsReader applications;

    public MerchantAgreementEligibilityFactsAdapter(
            DataSource dataSource,
            ApplicationReviewFactsReader applications
    ) {
        this.agreements = new MerchantAgreementStore(Objects.requireNonNull(dataSource, "dataSource is required"));
        this.applications = Objects.requireNonNull(applications, "applications is required");
    }

    @Override
    public Facts read(long merchantId, long storeId) {
        ApplicationReviewFactsReader.Facts application;
        try {
            application = applications.read(merchantId);
        } catch (RuntimeException failure) {
            throw unavailable("application review facts are unavailable");
        }
        if (application == null || application.applicationStatus() == null) {
            throw unavailable("application review facts are missing");
        }
        String signing = agreements.joinCurrentTransaction(mapper -> {
            List<com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantAgreementDocumentEntity>
                    accepted = MerchantAgreementService.acceptedAgreements(mapper, merchantId, false);
            if (accepted.isEmpty()) return "NOT_SIGNED";
            MerchantAgreementService.validateDocument(accepted.getFirst(), true);
            return "SIGNED";
        });
        return new Facts(application.applicationStatus(), signing);
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
