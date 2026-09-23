package com.petplatform.service.api.command;

import com.petplatform.service.api.dto.ServiceWriteTypes.CreateServiceItemCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.DecideServiceReviewCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.ForceOfflineServiceCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.ServiceItemResult;
import com.petplatform.service.api.dto.ServiceWriteTypes.SubmitServiceItemCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.TakeServiceOfflineCommand;
import com.petplatform.service.api.dto.ServiceWriteTypes.UpdateServiceItemCommand;

/**
 * CCR-W2-API-001 service write commands (approved 2026-09-22). Command namespaces join the
 * supplement-23 idempotency scope: merchant.service.create / merchant.service.update /
 * merchant.service.submit / merchant.service.offline / admin.service.review.decide /
 * admin.service.forceOffline. State machine: DRAFT -> REVIEWING -> ACTIVE/REJECTED;
 * ACTIVE -> OFFLINE (merchant offline or admin force-offline); OFFLINE/REJECTED -> REVIEWING
 * (resubmission always re-reviews). Merchants never set ACTIVE directly.
 */
public interface ServiceCommandApi {

    ServiceItemResult createDraft(CreateServiceItemCommand command);

    ServiceItemResult update(UpdateServiceItemCommand command);

    ServiceItemResult submitForReview(SubmitServiceItemCommand command);

    ServiceItemResult takeOffline(TakeServiceOfflineCommand command);

    ServiceItemResult decideReview(DecideServiceReviewCommand command);

    ServiceItemResult forceOffline(ForceOfflineServiceCommand command);
}
