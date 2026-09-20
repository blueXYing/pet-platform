package com.petplatform.thirdparty.api.dto;

import com.petplatform.common.CommandContext;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;

/** Typed private-asset contracts. All HTTP-visible identifiers remain decimal strings. */
public final class PrivateAssetTypes {
  private PrivateAssetTypes() {}

  public enum PrivateAssetStatus {
    UPLOADING,
    SCANNING,
    READY,
    REJECTED,
    QUARANTINED,
    RETIRED
  }

  public enum ReadAuthorizationPhase {
    ISSUE,
    CONSUME
  }

  public record UploadPrivateAssetCommand(
      String ownerUserId,
      String purpose,
      String declaredMediaType,
      long declaredBytes,
      InputStream content,
      CommandContext context) {}

  public record UploadPrivateAssetResult(
      String assetId,
      boolean created,
      PrivateAssetStatus status,
      String objectSha256,
      String mediaType,
      long bytes) {}

  public record ResolveOwnedPrivateAssetsQuery(
      String ownerUserId, List<String> assetIds, String requiredPurpose) {}

  public record PrivateAssetFact(
      String assetId,
      String ownerUserId,
      String sourceSha256,
      String objectSha256,
      String objectVersionRef,
      String mediaType,
      long bytes,
      PrivateAssetStatus status,
      String factVersion) {}

  public record IssuePrivateAssetReadGrantCommand(
      String assetId,
      String applicationId,
      String revisionId,
      String purposeCode,
      String reason,
      String sessionId,
      long sessionGeneration,
      CommandContext context) {}

  public record IssuedPrivateAssetReadGrant(String token, OffsetDateTime expiresAt) {}

  public record ConsumePrivateAssetReadGrantCommand(
      String token, String sessionId, long sessionGeneration, CommandContext context) {}

  public record PrivateAssetContent(
      byte[] content, String mediaType, String objectSha256, long bytes) {
    public PrivateAssetContent {
      content = content == null ? null : content.clone();
    }

    @Override
    public byte[] content() {
      return content == null ? null : content.clone();
    }
  }

  public record ReadAuthorizationRequest(
      ReadAuthorizationPhase phase,
      String applicationId,
      String revisionId,
      String assetId,
      String operatorId,
      String sessionId,
      long sessionGeneration,
      String purposeCode) {}

  public record ReadAuthorizationProof(
      String materialId,
      String materialType,
      String ownerUserId,
      String assetId,
      String objectSha256,
      String revisionId,
      String authzVersion,
      String scopeVersion) {}
}
