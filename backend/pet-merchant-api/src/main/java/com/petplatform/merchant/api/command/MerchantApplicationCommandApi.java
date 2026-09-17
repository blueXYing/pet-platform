package com.petplatform.merchant.api.command;

import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;

public interface MerchantApplicationCommandApi {
  MerchantApplicationResult createDraft(CreateMerchantApplicationCommand command);

  MerchantApplicationResult saveDraft(SaveMerchantApplicationDraftCommand command);

  MerchantApplicationResult submit(SubmitMerchantApplicationCommand command);

  ReviewTaskResult claim(ClaimMerchantApplicationCommand command);

  ReviewTaskResult release(ReleaseMerchantApplicationCommand command);

  MerchantApplicationResult recordManualVerification(VerifyMerchantSubjectCommand command);

  MerchantApplicationResult decide(DecideMerchantApplicationCommand command);
}
