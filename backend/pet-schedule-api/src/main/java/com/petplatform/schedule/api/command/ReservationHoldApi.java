package com.petplatform.schedule.api.command;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldCommand;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldResult;
/** Requires an existing guarded transaction and an ORDER binding before that transaction commits. */
public interface ReservationHoldApi { HoldResult hold(HoldCommand command); }
