package com.petplatform.marketing.biz.application;

/**
 * Service-category dictionary facts for campaign {@code serviceCategoryCodes} validation
 * (contract 71 v0.3 §2.1/§4.1, CCR-SVC-CAT-001 §6.4). Boot adapts the service owner's
 * catalog-category read face; the marketing kernel never reads {@code service_category}
 * directly and never crosses into another biz module's internals. A missing/unavailable
 * adapter fails closed (503), never silently accepts.
 */
public interface MarketingServiceCategoryPort {

    /**
     * True only when {@code categoryCode} is a lexically valid, currently ENABLED service
     * dictionary category. A disabled or unknown code answers false; read failures throw.
     * Supply/visibility facts are deliberately not part of the answer: a campaign may target
     * an enabled category that has no visible supply yet.
     */
    boolean usable(String categoryCode);
}
