package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.merchant.api.dto.MerchantAdmissionDTO;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQuery;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.MerchantAdmissionService;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantAgreementStore;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantReadStore;
import com.petplatform.common.SnowflakeIdGenerator;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the owner-scoped admission surface (CCR-W2-ADMISSION-001). */
public final class MerchantAdmissionApiImpl implements MerchantAdmissionQueryApi {
  private final MerchantAdmissionService service;

  public MerchantAdmissionApiImpl(
      DataSource dataSource,
      SnowflakeIdGenerator ids,
      ApplicationReviewFactsReader applications,
      Clock clock
  ) {
    this.service = new MerchantAdmissionService(
        new MerchantReadStore(Objects.requireNonNull(dataSource, "dataSource is required")),
        Objects.requireNonNull(applications, "applications is required"),
        new MerchantAgreementStore(Objects.requireNonNull(dataSource, "dataSource is required"),
            Objects.requireNonNull(ids, "PLAT-002 ID provider is required")),
        Objects.requireNonNull(clock, "clock is required"));
  }

  @Override
  public MerchantMembershipPageDTO listMemberships(MerchantMembershipQuery query) {
    return service.listMemberships(query);
  }

  @Override
  public MerchantAdmissionDTO getAdmission(MerchantAdmissionQuery query) {
    return service.getAdmission(query);
  }
}
