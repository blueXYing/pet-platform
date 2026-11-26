package com.petplatform.payment.api.command;

import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.dto.SettlementTypes.ResumeSettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.RetryFallbackCommand;
import com.petplatform.payment.api.dto.SettlementTypes.RetrySettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementConfigView;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementOrderView;
import com.petplatform.payment.api.dto.SettlementTypes.UpsertSettlementConfigCommand;

/** Contract 78 settlement admin commands: merchant T+d/fee-rate config, resume held, retry failed splits and fallbacks. */
public interface SettlementAdminApi {
    SettlementConfigView upsertConfig(UpsertSettlementConfigCommand command);
    SettlementConfigView getConfig(String merchantId, QueryContext context);
    SettlementOrderView resumeHeldOrder(ResumeSettlementCommand command);
    SettlementOrderView retryFailedOrder(RetrySettlementCommand command);
    SettlementOrderView retryFailedFallback(RetryFallbackCommand command);
}
