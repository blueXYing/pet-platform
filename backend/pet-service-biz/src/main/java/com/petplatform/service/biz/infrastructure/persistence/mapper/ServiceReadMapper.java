package com.petplatform.service.biz.infrastructure.persistence.mapper;

import com.petplatform.service.biz.infrastructure.persistence.entity.CatalogCategoryReadEntity;
import com.petplatform.service.biz.infrastructure.persistence.entity.CatalogCategoryStoreCountEntity;
import com.petplatform.service.biz.infrastructure.persistence.entity.FeaturedStoreServiceRowEntity;
import com.petplatform.service.biz.infrastructure.persistence.entity.ServiceItemReadEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** service_item read statements. SQL is kept in ServiceReadMapper.xml. */
public interface ServiceReadMapper {

    ServiceItemReadEntity selectServiceById(@Param("serviceId") long serviceId);

    List<ServiceItemReadEntity> selectActiveStoreServices(
            @Param("storeId") long storeId, @Param("limit") int limit, @Param("offset") int offset);

    long countActiveStoreServices(@Param("storeId") long storeId);

    /**
     * HTTP10 §3.3.3 / contract 74 §3.3 C catalog search candidates: ACTIVE services, optionally
     * narrowed by the pre-escaped keyword (service or category name substring) and/or the exact
     * category id, combined with AND. Keywords keep the merchant/store/service order; a
     * category-only read orders by service id so paging is stable. Eligibility is not a SQL
     * concern; the caller evaluates the merchant facts per distinct pair.
     */
    List<ServiceItemReadEntity> selectActiveServiceCandidates(
            @Param("keyword") String keyword, @Param("categoryId") Long categoryId);

    /** Enabled dictionary rows ordered by the approved C sort, parent code resolved by self-join. */
    List<CatalogCategoryReadEntity> selectEnabledCategories();

    /** Exact categoryCode lookup; the caller splits unknown/disabled (400) from corrupt facts (503). */
    CatalogCategoryReadEntity selectCategoryByCode(@Param("categoryCode") String categoryCode);

    /**
     * One row per (enabled category, store) holding at least one ACTIVE service. A grouped read
     * feeds both counts without a per-category query (contract 74 §11 no-N+1 acceptance).
     */
    List<CatalogCategoryStoreCountEntity> countActiveServicesByCategoryStore();

    /** Distinct stores holding at least one ACTIVE service in the exact category, ascending. */
    List<Long> selectActiveCategoryStoreIds(@Param("categoryId") long categoryId);

    /**
     * Contract 74 §3.4 featured-card candidates (B-CARD-01): distinct stores holding at least one
     * ACTIVE service, ascending; a null categoryId removes the category filter. Store visibility
     * is a separate domain concern; this read only feeds the id intersection at composition.
     */
    List<Long> selectActiveServiceStoreIds(@Param("categoryId") Long categoryId);

    /**
     * Contract 74 §6.5 featured-card service enumeration (B-CARD-01): every ACTIVE service of the
     * requested stores in ONE window-partitioned query (ROW_NUMBER over store_id), never a query
     * per store. The window ranks by service id because pet_order sold counts belong to the order
     * domain and cannot appear in this module's SQL; the composing read applies the confirmed
     * sold-based top-2 after the order facts are merged in.
     */
    List<FeaturedStoreServiceRowEntity> selectFeaturedStoreServices(
            @Param("storeIds") List<Long> storeIds);
}
