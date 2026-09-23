package com.petplatform.service.api.query;

import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewDetail;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewDetailQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewListQuery;
import com.petplatform.service.api.dto.ServiceAdminTypes.ServiceReviewPage;

/**
 * Admin review reads for the service write slice (proposal SVCW-D7). Collection and detail reads
 * revalidate the live admin session/action through the authorization port; scope is not
 * resource-filtered in V1 (single-operator ruling) but action codes still apply.
 */
public interface ServiceReviewQueryApi {

    ServiceReviewPage listForReview(ServiceReviewListQuery query);

    ServiceReviewDetail getForReview(ServiceReviewDetailQuery query);
}
