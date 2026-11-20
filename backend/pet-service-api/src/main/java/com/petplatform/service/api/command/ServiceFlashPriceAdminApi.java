package com.petplatform.service.api.command;

import com.petplatform.common.QueryContext;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.AdminReviewCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPriceItem;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPricePage;

/**
 * Contract 77 §4 operations review face: list plus approve/reject/pause/end. Operators never
 * change prices (a bad price is rejected for the merchant to fix); every action is append-only
 * audited inside the service module.
 */
public interface ServiceFlashPriceAdminApi {

    /** Contract 77 §4.1: updated_at DESC, id DESC, decorated with service/store names. */
    FlashPricePage list(String status, int page, int pageSize, QueryContext context);

    /** PENDING_REVIEW -> SCHEDULED/ACTIVE by startAt, or PAUSED -> resume; revalidates price. */
    FlashPriceItem approve(AdminReviewCommand command);

    /** PENDING_REVIEW -> REJECTED (reason 1..500). */
    FlashPriceItem reject(AdminReviewCommand command);

    /** SCHEDULED/ACTIVE -> PAUSED (reserve refuses while paused). */
    FlashPriceItem pause(AdminReviewCommand command);

    /** SCHEDULED/ACTIVE/PAUSED -> ENDED (kept reservations release at their own expire_at). */
    FlashPriceItem end(AdminReviewCommand command);
}
