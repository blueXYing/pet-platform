package com.petplatform.user.api.command;

import com.petplatform.user.api.command.FavoriteCommands.Add;
import com.petplatform.user.api.command.FavoriteCommands.Cancel;

/**
 * Contract 58 favorite command surface. Every write is requestId-idempotent per supplement 23
 * §5: the same requestId with the same parameters replays the first receipt, and the same
 * requestId with different parameters is IDEMPOTENCY_KEY_CONFLICT.
 */
public interface FavoriteCommandApi {

    AddOutcome addFavorite(Add command);

    CancelOutcome cancelFavorite(Cancel command);

    /** created drives the 201-first / 200-replay-or-duplicate HTTP mapping. */
    record AddOutcome(FavoriteCommands.FavoriteReceipt receipt, boolean created,
            boolean replayed) {}

    /** Cancels never create: removed=false when the pair was not favorited at commit time. */
    record CancelOutcome(FavoriteCommands.CancelReceipt receipt, boolean replayed) {}
}
