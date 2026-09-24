package com.petplatform.merchant.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.command.*;
import com.petplatform.merchant.api.dto.*;
import com.petplatform.merchant.api.query.*;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.MerchantStaffService;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantStaffStore;
import java.time.Clock;
import javax.sql.DataSource;

public final class MerchantStaffApiImpl implements MerchantStaffManagementQueryApi, MerchantStaffCommandApi {
    private final MerchantStaffService service;
    public MerchantStaffApiImpl(DataSource source, SnowflakeIdGenerator ids,
                                ApplicationReviewFactsReader applicationFacts,
                                ApplicationValidationPorts.ProtectedValuePort protection, Clock clock) {
        this.service = new MerchantStaffService(new MerchantStaffStore(source, ids), applicationFacts,
                protection, clock);
    }
    @Override public MerchantStaffPageDTO listStaff(MerchantStaffListQuery query) { return service.listStaff(query); }
    @Override public MerchantStaffDTO getStaff(MerchantStaffQuery query) { return service.getStaff(query); }
    @Override public MerchantStaffCommandResult createStaff(CreateMerchantStaffCommand command) { return service.createStaff(command); }
    @Override public MerchantStaffCommandResult updateStaff(UpdateMerchantStaffCommand command) { return service.updateStaff(command); }
    @Override public MerchantStaffCommandResult enableStaff(EnableMerchantStaffCommand command) { return service.enableStaff(command); }
}
