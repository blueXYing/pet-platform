package com.petplatform.user.api.command;

import com.petplatform.user.api.command.FootprintCommands.Delete;
import com.petplatform.user.api.command.FootprintCommands.Record;

/** Contract 58 footprint command surface (requestId-idempotent per supplement 23 §5). */
public interface FootprintCommandApi {

    RecordOutcome recordFootprint(Record command);

    DeleteOutcome deleteFootprint(Delete command);

    /** created=true only for the very first view of the target. */
    record RecordOutcome(FootprintCommands.RecordReceipt receipt, boolean created,
            boolean replayed) {}

    /** Deletes never create: deleted=false when the row was already gone. */
    record DeleteOutcome(FootprintCommands.DeleteReceipt receipt, boolean replayed) {}
}
