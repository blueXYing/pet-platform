package com.petplatform.payment.api.command;

import com.petplatform.payment.api.dto.OnboardingTypes.AdoptContractCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.DiscardContractCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingAssetView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingSensitiveView;
import com.petplatform.payment.api.dto.OnboardingTypes.OnboardingView;
import com.petplatform.payment.api.dto.OnboardingTypes.RevealOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.RetryOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SaveOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SubmitOnboardingCommand;
import com.petplatform.payment.api.dto.OnboardingTypes.SyncOnboardingCommand;
import com.petplatform.common.CommandContext;
import java.io.InputStream;

/**
 * Contract 81 §4 operator commands for merchant acquiring onboarding (Ruling 44). Merchants have no
 * write API in this phase; every command requires {@code X-Request-Id} idempotency and writes an
 * immutable audit row.
 */
public interface MerchantOnboardingApi {
    OnboardingView saveProfile(SaveOnboardingCommand command);
    OnboardingView submit(SubmitOnboardingCommand command);
    OnboardingView sync(SyncOnboardingCommand command);
    OnboardingView retry(RetryOnboardingCommand command);
    OnboardingView adoptContract(AdoptContractCommand command);
    OnboardingView discardContract(DiscardContractCommand command);
    OnboardingSensitiveView reveal(RevealOnboardingCommand command);

    /** Stores one operator-uploaded attachment for the merchant and returns its private asset id. */
    OnboardingAssetView uploadAttachment(CommandContext context, String merchantId, String declaredMediaType,
            long declaredBytes, InputStream content);
}
