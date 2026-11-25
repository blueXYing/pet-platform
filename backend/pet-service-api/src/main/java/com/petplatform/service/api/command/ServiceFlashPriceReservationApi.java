package com.petplatform.service.api.command;

import com.petplatform.common.CommandContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Contract 77 §6.1 internal quota reservation, called by the order creation kernel inside the
 * order's transaction after the schedule hold succeeds. Any failure throws and the whole order
 * creation rolls back (the reservation rows vanish with the transaction).
 */
public interface ServiceFlashPriceReservationApi {

    ReservationResult reserve(ReserveCommand command);

    /** expireAt is the schedule hold's expiry; released by FLASH_PRICE_RELEASE_EXPIRED later. */
    record ReserveCommand(
            CommandContext context,
            String flashPriceId,
            String orderId,
            String userId,
            OffsetDateTime expireAt) {}

    /** Authoritative price answer: price = flash price, listPrice = service current price. */
    record ReservationResult(
            String flashPriceId,
            BigDecimal price,
            BigDecimal listPrice,
            BigDecimal flashDiscount,
            String status) {}
}
