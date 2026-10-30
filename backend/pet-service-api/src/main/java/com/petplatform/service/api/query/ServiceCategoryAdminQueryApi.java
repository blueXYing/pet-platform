package com.petplatform.service.api.query;

import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryAdminListQuery;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryAdminPage;

/** Admin dictionary read for the service category management page (contract 74 §4). */
public interface ServiceCategoryAdminQueryApi {

    ServiceCategoryAdminPage list(ServiceCategoryAdminListQuery query);
}
