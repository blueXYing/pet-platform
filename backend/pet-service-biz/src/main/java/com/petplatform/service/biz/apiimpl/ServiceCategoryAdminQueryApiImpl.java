package com.petplatform.service.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryAdminListQuery;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryAdminPage;
import com.petplatform.service.api.query.ServiceCategoryAdminQueryApi;
import com.petplatform.service.biz.application.ServiceCategoryAdminQueryService;
import com.petplatform.service.biz.application.ServiceCategoryAuthorizationPort;
import com.petplatform.service.biz.infrastructure.persistence.ServiceWriteStore;
import javax.sql.DataSource;

/** Local implementation of the admin service category dictionary read (B-SVC-02). */
public final class ServiceCategoryAdminQueryApiImpl implements ServiceCategoryAdminQueryApi {
    private final ServiceCategoryAdminQueryService queries;

    public ServiceCategoryAdminQueryApiImpl(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            ServiceCategoryAuthorizationPort authorization) {
        this.queries =
                new ServiceCategoryAdminQueryService(
                        new ServiceWriteStore(dataSource, ids), authorization);
    }

    @Override
    public ServiceCategoryAdminPage list(ServiceCategoryAdminListQuery query) {
        return queries.list(query);
    }
}
