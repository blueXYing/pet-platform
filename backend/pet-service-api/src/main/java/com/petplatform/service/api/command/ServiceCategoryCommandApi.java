package com.petplatform.service.api.command;

import com.petplatform.service.api.dto.ServiceCategoryTypes.CreateServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceCategoryTypes.DisableServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceCategoryTypes.EnableServiceCategoryCommand;
import com.petplatform.service.api.dto.ServiceCategoryTypes.ServiceCategoryResult;
import com.petplatform.service.api.dto.ServiceCategoryTypes.UpdateServiceCategoryCommand;

/**
 * Admin service category dictionary commands (contract 74 §4): create, partial update and the
 * two status transitions. The requestId idempotency scope is {@code admin.service.category};
 * identical replays return the first receipt and different parameters conflict (409). Every
 * persisted write appends one {@code service_category_audit} row in the same transaction.
 */
public interface ServiceCategoryCommandApi {

    ServiceCategoryResult create(CreateServiceCategoryCommand command);

    ServiceCategoryResult update(UpdateServiceCategoryCommand command);

    ServiceCategoryResult enable(EnableServiceCategoryCommand command);

    ServiceCategoryResult disable(DisableServiceCategoryCommand command);
}
