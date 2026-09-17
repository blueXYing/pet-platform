package com.petplatform.merchant.api.command;

import com.petplatform.common.CommandContext;

public record MerchantAgreementConsentCommand(
        String merchantId,
        String agreementVersion,
        String contentSha256,
        Boolean accepted,
        CommandContext context
) {}
