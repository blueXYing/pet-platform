package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantAgreementDocumentEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantCommandBindingEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantOwnerEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Agreement and merchant-owned idempotency SQL lives in MerchantAgreementMapper.xml. */
public interface MerchantAgreementMapper {
    void setTimeZoneUtc();

    void setLockWaitTimeout2Seconds();

    MerchantOwnerEntity selectOwnedMerchant(
            @Param("merchantId") long merchantId,
            @Param("ownerUserId") long ownerUserId
    );

    MerchantOwnerEntity selectOwnedMerchantForUpdate(
            @Param("merchantId") long merchantId,
            @Param("ownerUserId") long ownerUserId
    );

    List<MerchantAgreementDocumentEntity> selectAcceptedAgreements(@Param("merchantId") long merchantId);

    int countAcceptances(@Param("merchantId") long merchantId);

    List<MerchantAgreementDocumentEntity> selectAcceptedAgreementsForUpdate(@Param("merchantId") long merchantId);

    MerchantAgreementDocumentEntity selectCurrentAgreement();

    MerchantAgreementDocumentEntity selectCurrentAgreementForUpdate();

    /** 2026-11-20 用户裁决：运营端协议管理——版本历史/发布新版本/切换当前指针。 */
    List<MerchantAgreementDocumentEntity> selectAllVersions();

    int insertVersion(
            @Param("id") long id,
            @Param("agreementVersion") String agreementVersion,
            @Param("content") String content,
            @Param("contentSha256") String contentSha256,
            @Param("publishedByOperatorId") long publishedByOperatorId,
            @Param("now") LocalDateTime now);

    int upsertCurrentPointer(
            @Param("agreementVersionId") long agreementVersionId,
            @Param("now") LocalDateTime now);

    int insertAcceptance(
            @Param("id") long id,
            @Param("merchantId") long merchantId,
            @Param("agreementVersionId") long agreementVersionId,
            @Param("acceptedByUserId") long acceptedByUserId,
            @Param("acceptedAt") LocalDateTime acceptedAt,
            @Param("contentSha256") String contentSha256
    );

    int insertBinding(
            @Param("id") long id,
            @Param("requestKey") byte[] requestKey,
            @Param("canonicalVersion") String canonicalVersion,
            @Param("paramsSha256") String paramsSha256,
            @Param("paramsCanonical") byte[] paramsCanonical,
            @Param("traceId") String traceId
    );

    MerchantCommandBindingEntity selectBindingForUpdate(@Param("requestKey") byte[] requestKey);

    int markBindingSucceeded(
            @Param("requestKey") byte[] requestKey,
            @Param("receiptJson") String receiptJson
    );
}
