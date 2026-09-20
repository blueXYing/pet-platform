package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.command.MerchantApplicationCommandApi;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;
import com.petplatform.merchant.api.query.MerchantApplicationQueryApi;
import com.petplatform.merchant.biz.application.MerchantApplicationDependencies;
import com.petplatform.merchant.biz.application.MerchantApplicationService;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantApplicationStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/** Local MER implementation. HTTP exposure remains gated until every dependency is wired. */
public final class MerchantApplicationApiImpl
    implements MerchantApplicationCommandApi, MerchantApplicationQueryApi {
  private final MerchantApplicationService service;

  public MerchantApplicationApiImpl(DataSource dataSource, SnowflakeIdGenerator ids) {
    this(dataSource, ids, Clock.systemUTC(), MerchantApplicationDependencies.unavailable());
  }

  public MerchantApplicationApiImpl(
      DataSource dataSource,
      SnowflakeIdGenerator ids,
      Clock clock,
      MerchantApplicationDependencies dependencies) {
    this.service =
        new MerchantApplicationService(
            new MerchantApplicationStore(
                Objects.requireNonNull(dataSource), Objects.requireNonNull(ids)),
            Objects.requireNonNull(dependencies),
            Objects.requireNonNull(clock));
  }

  @Override
  public MerchantApplicationResult createDraft(CreateMerchantApplicationCommand c) {
    return service.create(c);
  }

  @Override
  public MerchantApplicationResult saveDraft(SaveMerchantApplicationDraftCommand c) {
    return service.save(c);
  }

  @Override
  public MerchantApplicationResult submit(SubmitMerchantApplicationCommand c) {
    return service.submit(c);
  }

  @Override
  public ReviewTaskResult claim(ClaimMerchantApplicationCommand c) {
    return service.claim(c);
  }

  @Override
  public ReviewTaskResult release(ReleaseMerchantApplicationCommand c) {
    return service.release(c);
  }

  @Override
  public MerchantApplicationResult recordManualVerification(VerifyMerchantSubjectCommand c) {
    return service.verify(c);
  }

  @Override
  public MerchantApplicationResult decide(DecideMerchantApplicationCommand c) {
    return service.decide(c);
  }

  @Override
  public MerchantApplicationResult getCurrent(CurrentMerchantApplicationQuery q) {
    return service.current(q);
  }

  @Override
  public MerchantApplicationPage listForReview(MerchantApplicationReviewListQuery q) {
    return service.list(q);
  }

  @Override
  public MerchantApplicationReviewDetail getForReview(MerchantApplicationReviewQuery q) {
    return service.review(q);
  }

  @Override
  public MerchantApplicationScopeFact getScope(MerchantApplicationScopeQuery q) {
    return service.scope(q);
  }

  @Override
  public MerchantApplicationEligibilityFact getEligibility(MerchantApplicationEligibilityQuery q) {
    return service.eligibility(q);
  }
}
