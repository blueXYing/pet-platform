package com.petplatform.service.api.query;

import com.petplatform.service.api.dto.ServiceBookabilityDTO;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.dto.ServiceSnapshotPageDTO;

/**
 * API07 5.1/5.1.1 (CCR-W2-API-001 service domain, approved 2026-09-22). Error split per the
 * approval: positively confirmed missing or ineligible -> NOT_FOUND (C side hides with 404);
 * facts-source failure, read failure or unknown status -> DEPENDENCY_UNAVAILABLE (C side 503);
 * the two are never conflated.
 */
public interface ServiceQueryApi {

    /** Internal projection: any stored row by id; a value copy independent of later changes. */
    ServiceSnapshotDTO getServiceSnapshot(ServiceSnapshotQuery query);

    /** Internal aggregation: four-condition conjunction with reasonCodes for ORD/SCH. */
    ServiceBookabilityDTO checkBookable(ServiceBookabilityQuery query);

    /** C catalog page: only ACTIVE services of a visible store; hidden stores yield empty pages. */
    ServiceSnapshotPageDTO getStoreServiceSnapshots(StoreServiceSnapshotQuery query);

    /** C visibility rule (HTTP10 3.3.1): NOT_FOUND unless the four-condition conjunction holds. */
    ServiceSnapshotDTO getVisibleService(ServiceSnapshotQuery query);
}
