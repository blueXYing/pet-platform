package com.petplatform.service.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceCategoryPage;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceCategoryQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementDetailQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementItem;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementListQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementPage;
import com.petplatform.service.api.query.ServiceManagementQueryApi;
import com.petplatform.service.biz.application.ServiceAdmissionGate;
import com.petplatform.service.biz.application.ServiceAdminQueryService;
import com.petplatform.service.biz.infrastructure.persistence.ServiceWriteStore;
import java.time.Clock;
import javax.sql.DataSource;

/** Local implementation of the merchant workbench reads (M-002). */
public final class ServiceManagementQueryApiImpl implements ServiceManagementQueryApi {
    private final ServiceAdminQueryService service;

    public ServiceManagementQueryApiImpl(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            MerchantAdmissionQueryApi admissions,
            Clock clock) {
        this.service =
                new ServiceAdminQueryService(
                        new ServiceWriteStore(dataSource, ids),
                        new ServiceAdmissionGate(admissions),
                        clock);
    }

    @Override
    public ServiceManagementPage listManaged(ServiceManagementListQuery query) {
        return service.listManaged(query);
    }

    @Override
    public ServiceManagementItem getManaged(ServiceManagementDetailQuery query) {
        return service.getManaged(query);
    }

    @Override
    public ServiceCategoryPage listEnabledCategories(ServiceCategoryQuery query) {
        return service.listEnabledCategories(query);
    }
}
