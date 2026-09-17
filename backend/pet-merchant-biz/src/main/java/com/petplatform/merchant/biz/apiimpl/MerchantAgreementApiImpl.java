package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.command.MerchantAgreementCommandApi;
import com.petplatform.merchant.api.command.MerchantAgreementConsentCommand;
import com.petplatform.merchant.api.dto.MerchantAgreementConsentDTO;
import com.petplatform.merchant.api.dto.MerchantAgreementDTO;
import com.petplatform.merchant.api.query.MerchantAgreementQuery;
import com.petplatform.merchant.api.query.MerchantAgreementQueryApi;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.MerchantAgreementService;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantAgreementStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/** Local domain implementation; no HTTP, publishing, boot registration or fabricated approval facts. */
public final class MerchantAgreementApiImpl
        implements MerchantAgreementQueryApi, MerchantAgreementCommandApi {
    private final MerchantAgreementService service;

    public MerchantAgreementApiImpl(DataSource dataSource, SnowflakeIdGenerator ids) {
        this(dataSource, ids, Clock.systemUTC(), merchantId -> {
            throw new ApiException(
                    CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant application review facts are not wired"
            );
        });
    }

    public MerchantAgreementApiImpl(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            Clock clock,
            ApplicationReviewFactsReader applicationFacts
    ) {
        this.service = new MerchantAgreementService(
                new MerchantAgreementStore(Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "PLAT-002 ID provider is required")),
                Objects.requireNonNull(applicationFacts, "applicationFacts is required"),
                Objects.requireNonNull(clock, "clock is required")
        );
    }

    @Override
    public MerchantAgreementDTO getAgreement(MerchantAgreementQuery query) {
        return service.getAgreement(query);
    }

    @Override
    public MerchantAgreementConsentDTO consent(MerchantAgreementConsentCommand command) {
        return service.consent(command).receipt();
    }

    /** Allows a future HTTP adapter to distinguish first creation (201) from replay/existing (200). */
    public ConsentOutcome consentOutcome(MerchantAgreementConsentCommand command) {
        MerchantAgreementService.ConsentResult result = service.consent(command);
        return new ConsentOutcome(result.receipt(), result.created());
    }

    public record ConsentOutcome(MerchantAgreementConsentDTO receipt, boolean created) {}
}
