package com.petplatform.service.api.command;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.CancelCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.CreateFlashPriceCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPriceItem;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.FlashPricePage;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.SubmitCommand;
import com.petplatform.service.api.dto.ServiceFlashPriceTypes.UpdateFlashPriceCommand;

/**
 * Contract 77 §3 merchant flash-price face: list/create/edit/submit/cancel. Merchants never
 * publish (no approve route here — the admin API owns review); writes are requestId-idempotent
 * with optimistic versions. Owner admission (store OWNER) is enforced inside the service module.
 */
public interface ServiceFlashPriceMerchantApi {

    /** Contract 77 §3.1: created_at DESC, id DESC; optional status filter. */
    FlashPricePage list(String serviceId, String status, int page, int pageSize,
            QueryContext context);

    /** Contract 77 §3.2: lands DRAFT with the server-computed flashPrice preview. */
    FlashPriceItem create(CreateFlashPriceCommand command);

    /** Contract 77 §3.3: DRAFT/REJECTED only, full revalidation. */
    FlashPriceItem update(UpdateFlashPriceCommand command);

    /** Contract 77 §3.4: DRAFT/REJECTED -> PENDING_REVIEW. */
    FlashPriceItem submit(SubmitCommand command);

    /** Contract 77 §3.5: DRAFT/REJECTED/PENDING_REVIEW -> CANCELED. */
    FlashPriceItem cancel(CancelCommand command);
}
