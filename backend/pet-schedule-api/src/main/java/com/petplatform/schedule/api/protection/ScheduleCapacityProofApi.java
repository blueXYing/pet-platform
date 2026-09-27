package com.petplatform.schedule.api.protection;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.CapacityProofQuery;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.CapacityProofResult;
/** Checks one prospective reservation under an already-held guard; does not reserve anything. */
public interface ScheduleCapacityProofApi {
    CapacityProofResult checkNewReservation(CapacityProofQuery query);
}
