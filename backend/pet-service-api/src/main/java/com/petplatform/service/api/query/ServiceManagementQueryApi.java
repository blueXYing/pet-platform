package com.petplatform.service.api.query;

import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceCategoryPage;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceCategoryQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementDetailQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementItem;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementListQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceManagementPage;

/**
 * Merchant workbench reads for the service write slice (M-002): every status of the owner's own
 * store, paginated. Ownership is enforced by the merchant facts gate (404 anti-enumeration for
 * anyone without the owned-store fact); this never widens C-side visibility.
 */
public interface ServiceManagementQueryApi {

    ServiceManagementPage listManaged(ServiceManagementListQuery query);

    ServiceManagementItem getManaged(ServiceManagementDetailQuery query);

    ServiceCategoryPage listEnabledCategories(ServiceCategoryQuery query);
}
