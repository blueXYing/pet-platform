package com.petplatform.thirdparty.biz.infrastructure.persistence.mapper;

import com.petplatform.thirdparty.biz.infrastructure.persistence.entity.*;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface PrivateAssetMapper {
  void setSessionTimeZoneUtc();

  int insertAsset(
      @Param("id") long id,
      @Param("ownerUserId") long ownerUserId,
      @Param("purpose") String purpose,
      @Param("sourceKey") String sourceKey,
      @Param("objectKey") String objectKey,
      @Param("sourceSha256") String sourceSha256);

  int insertUploadBinding(
      @Param("id") long id,
      @Param("ownerUserId") long ownerUserId,
      @Param("requestId") String requestId,
      @Param("requestHash") byte[] requestHash,
      @Param("assetId") long assetId);

  PrivateAssetUploadBindingEntity selectUploadBindingForUpdate(
      @Param("ownerUserId") long ownerUserId, @Param("requestId") String requestId);

  PrivateAssetEntity selectAsset(@Param("assetId") long assetId);

  PrivateAssetEntity selectAssetForUpdate(@Param("assetId") long assetId);

  List<PrivateAssetEntity> selectOwned(
      @Param("ownerUserId") long ownerUserId,
      @Param("purpose") String purpose,
      @Param("assetIds") List<Long> assetIds);

  int updateSourceStored(@Param("assetId") long assetId, @Param("versionRef") String versionRef);

  int updateScanning(@Param("assetId") long assetId);

  int updateRejected(
      @Param("assetId") long assetId,
      @Param("status") String status,
      @Param("providerVersion") String providerVersion,
      @Param("resultCode") String resultCode);

  int updateReady(
      @Param("assetId") long assetId,
      @Param("versionRef") String versionRef,
      @Param("objectSha256") String objectSha256,
      @Param("mediaType") String mediaType,
      @Param("bytes") long bytes,
      @Param("providerVersion") String providerVersion,
      @Param("resultCode") String resultCode);

  PrivateAssetGrantEntity selectGrantByRequestForUpdate(
      @Param("operatorId") String operatorId, @Param("requestId") String requestId);

  PrivateAssetGrantEntity selectGrantByDigestForUpdate(@Param("tokenDigest") byte[] tokenDigest);

  int insertGrant(
      @Param("id") long id,
      @Param("tokenDigest") byte[] tokenDigest,
      @Param("tokenProof") byte[] tokenProof,
      @Param("keyVersion") String keyVersion,
      @Param("assetId") long assetId,
      @Param("applicationId") long applicationId,
      @Param("revisionId") long revisionId,
      @Param("materialId") long materialId,
      @Param("materialType") String materialType,
      @Param("objectSha256") String objectSha256,
      @Param("operatorId") String operatorId,
      @Param("sessionDigest") byte[] sessionDigest,
      @Param("sessionGeneration") long sessionGeneration,
      @Param("purposeCode") String purposeCode,
      @Param("reasonProtected") byte[] reasonProtected,
      @Param("requestId") String requestId,
      @Param("requestHash") byte[] requestHash,
      @Param("authzVersion") String authzVersion,
      @Param("scopeVersion") String scopeVersion);

  int consumeGrant(@Param("id") long id);

  int expireGrant(@Param("id") long id);

  int insertAudit(
      @Param("id") long id,
      @Param("grantId") long grantId,
      @Param("action") String action,
      @Param("result") String result,
      @Param("operatorId") String operatorId,
      @Param("applicationId") long applicationId,
      @Param("revisionId") long revisionId,
      @Param("assetId") long assetId,
      @Param("purposeCode") String purposeCode,
      @Param("requestId") String requestId,
      @Param("authzVersion") String authzVersion,
      @Param("scopeVersion") String scopeVersion);
}
