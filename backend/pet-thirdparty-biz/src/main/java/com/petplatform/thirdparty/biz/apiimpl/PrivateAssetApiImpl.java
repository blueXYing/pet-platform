package com.petplatform.thirdparty.biz.apiimpl;

import static com.petplatform.thirdparty.api.PrivateAssetApiCodes.*;
import static com.petplatform.thirdparty.api.dto.PrivateAssetTypes.*;

import com.petplatform.common.*;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import com.petplatform.thirdparty.api.PrivateAssetReadAuthorizer;
import com.petplatform.thirdparty.biz.application.port.*;
import com.petplatform.thirdparty.biz.infrastructure.persistence.PrivateAssetRepository;
import com.petplatform.thirdparty.biz.infrastructure.persistence.entity.*;
import com.petplatform.thirdparty.biz.infrastructure.persistence.mapper.PrivateAssetMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** CCR-MER-PRIVATE-001 implementation. Raw object locations never leave this module. */
public final class PrivateAssetApiImpl implements PrivateAssetApi {
  public static final String MERCHANT_APPLICATION_MATERIAL = "MERCHANT_APPLICATION_MATERIAL";
  /**
   * CCR-W2-API-001 store read (user-ruled SERVICE_COVER assignment): service cover images share
   * the whole immutable upload/scan/ownership pipeline; the purpose only scopes resolution and
   * review visibility, never the safety chain.
   */
  public static final String SERVICE_COVER = "SERVICE_COVER";
  private static final java.util.Set<String> UPLOAD_PURPOSES =
      java.util.Set.of(MERCHANT_APPLICATION_MATERIAL, SERVICE_COVER);
  static final int MAX_BYTES = 10 * 1024 * 1024;
  private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

  private final PrivateAssetRepository repository;
  private final JdbcAsyncTaskSubmitter tasks;
  private final SnowflakeIdGenerator ids;
  private final PrivateObjectStore objects;
  private final PrivateAssetScanner scanner;
  private final PrivateAssetImageNormalizer normalizer;
  private final PrivateAssetWatermarkRenderer watermarks;
  private final PrivateAssetReadAuthorizer authorizer;
  private final PrivateAssetGrantKeyProvider grantKeys;
  private final PrivateAssetReasonProtector reasons;
  private final Clock clock;

  public PrivateAssetApiImpl(
      DataSource dataSource,
      SnowflakeIdGenerator ids,
      PrivateObjectStore objects,
      PrivateAssetScanner scanner,
      PrivateAssetImageNormalizer normalizer,
      PrivateAssetWatermarkRenderer watermarks,
      PrivateAssetReadAuthorizer authorizer,
      PrivateAssetGrantKeyProvider grantKeys,
      PrivateAssetReasonProtector reasons,
      Clock clock) {
    this.repository = new PrivateAssetRepository(dataSource);
    this.tasks = new JdbcAsyncTaskSubmitter(dataSource, ids);
    this.ids = Objects.requireNonNull(ids);
    this.objects = Objects.requireNonNull(objects);
    this.scanner = Objects.requireNonNull(scanner);
    this.normalizer = Objects.requireNonNull(normalizer);
    this.watermarks = Objects.requireNonNull(watermarks);
    this.authorizer = Objects.requireNonNull(authorizer);
    this.grantKeys = Objects.requireNonNull(grantKeys);
    this.reasons = Objects.requireNonNull(reasons);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public UploadPrivateAssetResult upload(UploadPrivateAssetCommand command) {
    UploadInput input = validateUpload(command);
    byte[] source = readBounded(command.content(), command.declaredBytes());
    String sourceHash = sha256Hex(source);
    byte[] requestHash =
        digestFields(
            input.ownerId(),
            input.purpose(),
            input.mediaType(),
            command.declaredBytes(),
            sourceHash);

    UploadBinding binding;
    try {
      binding =
          repository.transaction(
              mapper ->
                  bindUpload(
                      mapper, command.context().requestId(), input, sourceHash, requestHash));
    } catch (DuplicateKeyException raced) {
      binding =
          repository.transaction(
              mapper -> {
                PrivateAssetUploadBindingEntity existing =
                    mapper.selectUploadBindingForUpdate(
                        input.ownerId(), command.context().requestId());
                if (existing == null) throw raced;
                if (!MessageDigest.isEqual(requestHash, existing.getRequestHash()))
                  throw conflict();
                return new UploadBinding(existing.getAssetId(), false);
              });
    }
    long assetId = binding.assetId();
    PrivateAssetEntity current = requireAsset(assetId);
    if (terminal(current)) return uploadResultOrFailure(current, binding.created());

    try {
      PrivateObjectStore.StoredObject stored =
          objects.putIfAbsent(current.getSourceObjectKey(), source, input.mediaType(), sourceHash);
      requireStored(stored, sourceHash, source.length);
      repository.transaction(
          mapper -> {
            mapper.updateSourceStored(assetId, stored.versionRef());
            return null;
          });
      ReconcileResult result = reconcileAsset(assetId);
      if (result == ReconcileResult.RETRY) {
        throw new ApiException(ASSET_NOT_READY, "私有材料正在安全处理，请使用相同 requestId 重试");
      }
    } catch (ApiException failure) {
      throw failure;
    } catch (IllegalArgumentException invalidImage) {
      finishRejected(assetId, "REJECTED", "IMAGE_VALIDATION", "VALIDATOR");
      throw rejected();
    } catch (RuntimeException unavailable) {
      throw new ApiException(ASSET_NOT_READY, "私有材料正在安全处理，请使用相同 requestId 重试");
    }
    return uploadResultOrFailure(requireAsset(assetId), binding.created());
  }

  @Override
  public List<PrivateAssetFact> resolveOwned(ResolveOwnedPrivateAssetsQuery query) {
    if (query == null || !UPLOAD_PURPOSES.contains(query.requiredPurpose())) {
      throw invalid("私有材料查询参数无效");
    }
    long owner = apiId(query.ownerUserId());
    if (query.assetIds() == null || query.assetIds().isEmpty()) return List.of();
    LinkedHashSet<Long> requested = new LinkedHashSet<>();
    for (String id : query.assetIds()) {
      if (!requested.add(apiId(id))) throw invalid("私有材料编号重复");
    }
    List<PrivateAssetEntity> rows =
        repository.read(
            mapper -> mapper.selectOwned(owner, query.requiredPurpose(), List.copyOf(requested)));
    if (rows.size() != requested.size()) {
      throw new ApiException(CommonApiCodes.NOT_FOUND, "私有材料不存在或不可访问");
    }
    Map<Long, PrivateAssetEntity> byId = new HashMap<>();
    rows.forEach(row -> byId.put(row.getId(), row));
    List<PrivateAssetFact> result = new ArrayList<>(requested.size());
    for (long id : requested) result.add(fact(byId.get(id)));
    return List.copyOf(result);
  }

  @Override
  public IssuedPrivateAssetReadGrant issueReadGrant(IssuePrivateAssetReadGrantCommand command) {
    IssueInput input = validateIssue(command);
    return repository.transaction(
        mapper -> {
          PrivateAssetEntity asset = mapper.selectAssetForUpdate(input.assetId());
          requireReady(asset);
          ReadAuthorizationProof proof =
              authorize(
                  ReadAuthorizationPhase.ISSUE,
                  command.sessionId(),
                  command.sessionGeneration(),
                  command.applicationId(),
                  command.revisionId(),
                  command.assetId(),
                  command.context().operatorId(),
                  command.purposeCode());
          validateProof(proof, asset, input.revisionId());
          byte[] sessionDigest = sha256(command.sessionId().getBytes(StandardCharsets.UTF_8));
          byte[] requestHash =
              digestFields(
                  input.assetId(),
                  input.applicationId(),
                  input.revisionId(),
                  command.purposeCode(),
                  command.reason(),
                  command.context().operatorId(),
                  sessionDigest,
                  command.sessionGeneration(),
                  proof.materialId(),
                  proof.materialType(),
                  proof.ownerUserId(),
                  proof.objectSha256(),
                  proof.authzVersion(),
                  proof.scopeVersion());

          PrivateAssetGrantEntity existing =
              mapper.selectGrantByRequestForUpdate(
                  command.context().operatorId(), command.context().requestId());
          if (existing != null) {
            if (!MessageDigest.isEqual(requestHash, existing.getRequestHash())) throw conflict();
            return replayGrant(mapper, existing);
          }

          long grantId = nextId();
          PrivateAssetGrantKeyProvider.KeyMaterial key = requireKey();
          String token =
              deriveToken(
                  key,
                  grantId,
                  command.context().operatorId(),
                  sessionDigest,
                  command.context().requestId());
          byte[] tokenDigest = sha256(token.getBytes(StandardCharsets.US_ASCII));
          byte[] tokenProof = hmac(key, bytes("proof"), tokenDigest);
          byte[] reasonProtected;
          try {
            reasonProtected = reasons.protect("private-asset-read-reason", command.reason());
          } catch (RuntimeException unavailable) {
            throw dependency();
          }
          if (reasonProtected == null
              || reasonProtected.length == 0
              || reasonProtected.length > 2048) {
            throw dependency();
          }
          mapper.insertGrant(
              grantId,
              tokenDigest,
              tokenProof,
              key.keyVersion(),
              input.assetId(),
              input.applicationId(),
              input.revisionId(),
              apiId(proof.materialId()),
              proof.materialType(),
              asset.getObjectSha256(),
              command.context().operatorId(),
              sessionDigest,
              command.sessionGeneration(),
              command.purposeCode(),
              reasonProtected,
              command.context().requestId(),
              requestHash,
              proof.authzVersion(),
              proof.scopeVersion());
          mapper.insertAudit(
              nextId(),
              grantId,
              "ISSUE",
              "SUCCESS",
              command.context().operatorId(),
              input.applicationId(),
              input.revisionId(),
              input.assetId(),
              command.purposeCode(),
              command.context().requestId(),
              proof.authzVersion(),
              proof.scopeVersion());
          PrivateAssetGrantEntity persisted =
              mapper.selectGrantByRequestForUpdate(
                  command.context().operatorId(), command.context().requestId());
          if (persisted == null) throw new IllegalStateException("Inserted grant missing");
          return new IssuedPrivateAssetReadGrant(
              token, persisted.getExpiresAt().atOffset(ZoneOffset.UTC));
        });
  }

  @Override
  public PrivateAssetContent consumeReadGrant(ConsumePrivateAssetReadGrantCommand command) {
    validateConsume(command);
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw dependency();
    }
    ConsumeAdmission admission =
        repository.transaction(
            mapper -> {
              byte[] presentedDigest = sha256(command.token().getBytes(StandardCharsets.US_ASCII));
              PrivateAssetGrantEntity grant = mapper.selectGrantByDigestForUpdate(presentedDigest);
              if (grant == null) throw gone();
              ApiException preliminary =
                  validatePresentedGrant(mapper, grant, command, presentedDigest);
              if (preliminary != null) {
                String result =
                    CommonApiCodes.FORBIDDEN.equals(preliminary.code()) ? "DENIED" : "GONE";
                insertConsumeAudit(
                    mapper,
                    grant,
                    command,
                    result,
                    grant.getAuthzVersion(),
                    grant.getScopeVersion());
                return new ConsumeAdmission(grant, preliminary);
              }
              if (mapper.consumeGrant(grant.getId()) != 1)
                return new ConsumeAdmission(grant, gone());
              insertConsumeAudit(
                  mapper,
                  grant,
                  command,
                  "STARTED",
                  grant.getAuthzVersion(),
                  grant.getScopeVersion());
              return new ConsumeAdmission(grant, null);
            });
    if (admission.failure() != null) throw admission.failure();
    PrivateAssetGrantEntity grant = admission.grant();
    try {
      return repository.transaction(
          mapper -> {
            PrivateAssetEntity asset = mapper.selectAssetForUpdate(grant.getAssetId());
            requireReady(asset);
            ReadAuthorizationProof proof =
                authorize(
                    ReadAuthorizationPhase.CONSUME,
                    command.sessionId(),
                    command.sessionGeneration(),
                    IDS.toApi(grant.getApplicationId()),
                    IDS.toApi(grant.getRevisionId()),
                    IDS.toApi(grant.getAssetId()),
                    command.context().operatorId(),
                    grant.getPurposeCode());
            validateProof(proof, asset, grant.getRevisionId());
            if (apiId(proof.materialId()) != grant.getMaterialId()
                || !proof.materialType().equals(grant.getMaterialType())
                || !proof.objectSha256().equals(grant.getObjectSha256())
                || !proof.authzVersion().equals(grant.getAuthzVersion())
                || !proof.scopeVersion().equals(grant.getScopeVersion())) {
              throw new ApiException(CommonApiCodes.FORBIDDEN, "读取授权已失效");
            }

            PrivateObjectStore.StoredContent raw =
                objects.get(asset.getObjectKey(), asset.getObjectVersionRef());
            if (raw == null
                || !asset.getObjectSha256().equals(raw.sha256())
                || !asset.getObjectSha256().equals(sha256Hex(raw.content()))) throw dependency();
            OffsetDateTime renderedAt = OffsetDateTime.now(clock);
            PrivateAssetWatermarkRenderer.RenderedImage rendered =
                watermarks.render(
                    raw.content(),
                    raw.mediaType(),
                    new PrivateAssetWatermarkRenderer.Watermark(
                        command.context().operatorId(),
                        IDS.toApi(grant.getApplicationId()),
                        renderedAt));
            validateRendered(rendered);
            insertConsumeAudit(
                mapper, grant, command, "SUCCESS", proof.authzVersion(), proof.scopeVersion());
            return new PrivateAssetContent(
                rendered.content(),
                rendered.mediaType(),
                asset.getObjectSha256(),
                rendered.content().length);
          });
    } catch (ApiException failure) {
      appendConsumeFailureAudit(
          grant, command, CommonApiCodes.FORBIDDEN.equals(failure.code()) ? "DENIED" : "FAILED");
      throw failure;
    } catch (RuntimeException failure) {
      appendConsumeFailureAudit(grant, command, "FAILED");
      throw dependency();
    }
  }

  private void appendConsumeFailureAudit(
      PrivateAssetGrantEntity grant, ConsumePrivateAssetReadGrantCommand command, String result) {
    repository.transaction(
        mapper -> {
          insertConsumeAudit(
              mapper, grant, command, result, grant.getAuthzVersion(), grant.getScopeVersion());
          return null;
        });
  }

  private void insertConsumeAudit(
      PrivateAssetMapper mapper,
      PrivateAssetGrantEntity grant,
      ConsumePrivateAssetReadGrantCommand command,
      String result,
      String authzVersion,
      String scopeVersion) {
    mapper.insertAudit(
        nextId(),
        grant.getId(),
        "CONSUME",
        result,
        command.context().operatorId(),
        grant.getApplicationId(),
        grant.getRevisionId(),
        grant.getAssetId(),
        grant.getPurposeCode(),
        command.context().requestId(),
        authzVersion,
        scopeVersion);
  }

  /** Targeted, idempotent SQL13 task effect. The shared worker owns leases and retry state. */
  public ReconcileResult reconcileAsset(long assetId) {
    if (assetId <= 0) throw new IllegalArgumentException("assetId must be positive");
    try {
      PrivateAssetEntity asset = requireAsset(assetId);
      if (terminal(asset)) return ReconcileResult.COMPLETE;
      PrivateObjectStore.StoredObject sourceHead =
          objects
              .head(asset.getSourceObjectKey())
              .orElseThrow(() -> new IllegalStateException("SOURCE_OBJECT_PENDING"));
      requireStored(sourceHead, asset.getSourceSha256(), sourceHead.bytes());
      repository.transaction(
          mapper -> {
            mapper.updateSourceStored(asset.getId(), sourceHead.versionRef());
            return null;
          });
      PrivateObjectStore.StoredContent source =
          objects.get(asset.getSourceObjectKey(), sourceHead.versionRef());
      if (source == null
          || !asset.getSourceSha256().equals(source.sha256())
          || !asset.getSourceSha256().equals(sha256Hex(source.content()))) {
        throw new IllegalStateException("SOURCE_OBJECT_MISMATCH");
      }
      repository.transaction(
          mapper -> {
            mapper.updateScanning(asset.getId());
            return null;
          });
      PrivateAssetScanner.ScanResult sourceScan = requireScan(scanner.scan(source.content()));
      if (!sourceScan.clean()) {
        finishRejected(
            asset.getId(), "QUARANTINED", sourceScan.resultCode(), sourceScan.providerVersion());
        return ReconcileResult.COMPLETE;
      }
      PrivateAssetImageNormalizer.NormalizedImage normalized =
          normalizer.normalize(source.content(), source.mediaType());
      validateNormalized(normalized);
      PrivateAssetScanner.ScanResult finalScan = requireScan(scanner.scan(normalized.content()));
      if (!finalScan.clean()) {
        finishRejected(
            asset.getId(), "QUARANTINED", finalScan.resultCode(), finalScan.providerVersion());
        return ReconcileResult.COMPLETE;
      }
      String finalHash = sha256Hex(normalized.content());
      PrivateObjectStore.StoredObject stored =
          objects.putIfAbsent(
              asset.getObjectKey(), normalized.content(), normalized.mediaType(), finalHash);
      requireStored(stored, finalHash, normalized.content().length);
      repository.transaction(
          mapper -> {
            PrivateAssetEntity locked = mapper.selectAssetForUpdate(asset.getId());
            if (!terminal(locked)) {
              mapper.updateReady(
                  asset.getId(),
                  stored.versionRef(),
                  finalHash,
                  normalized.mediaType(),
                  normalized.content().length,
                  finalScan.providerVersion(),
                  finalScan.resultCode());
            }
            return null;
          });
      return ReconcileResult.COMPLETE;
    } catch (IllegalArgumentException invalid) {
      finishRejected(assetId, "REJECTED", "IMAGE_VALIDATION", "VALIDATOR");
      return ReconcileResult.COMPLETE;
    } catch (RuntimeException unavailable) {
      return ReconcileResult.RETRY;
    }
  }

  private UploadBinding bindUpload(
      PrivateAssetMapper mapper,
      String requestId,
      UploadInput input,
      String sourceHash,
      byte[] requestHash) {
    PrivateAssetUploadBindingEntity existing =
        mapper.selectUploadBindingForUpdate(input.ownerId(), requestId);
    if (existing != null) {
      if (!MessageDigest.isEqual(requestHash, existing.getRequestHash())) throw conflict();
      return new UploadBinding(existing.getAssetId(), false);
    }
    long assetId = nextId();
    String partition =
        sha256Hex(Long.toString(input.ownerId()).getBytes(StandardCharsets.US_ASCII))
            .substring(0, 16);
    String base = "merchant-materials/" + partition + "/" + assetId + "/";
    mapper.insertAsset(
        assetId,
        input.ownerId(),
        input.purpose(),
        base + "source-v1",
        base + "normalized-v1",
        sourceHash);
    mapper.insertUploadBinding(nextId(), input.ownerId(), requestId, requestHash, assetId);
    tasks.enqueue(
        "PRIVATE_ASSET_RECONCILE:" + assetId,
        "THIRDPARTY",
        PrivateAssetReconcileTaskHandler.TASK_TYPE,
        "PRIVATE_ASSET",
        assetId,
        0L,
        "{\"assetId\":\"" + assetId + "\"}",
        20,
        "FAST_INTERNAL");
    return new UploadBinding(assetId, true);
  }

  private IssuedPrivateAssetReadGrant replayGrant(
      PrivateAssetMapper mapper, PrivateAssetGrantEntity grant) {
    if (!"ISSUED".equals(grant.getStatus()) || grant.isExpired()) {
      mapper.expireGrant(grant.getId());
      throw gone();
    }
    PrivateAssetGrantKeyProvider.KeyMaterial key = requireKey();
    if (!key.keyVersion().equals(grant.getTokenKeyVersion())) throw dependency();
    String token =
        deriveToken(
            key,
            grant.getId(),
            grant.getOperatorId(),
            grant.getSessionIdDigest(),
            grant.getRequestId());
    verifyTokenMaterial(grant, token, key);
    return new IssuedPrivateAssetReadGrant(token, grant.getExpiresAt().atOffset(ZoneOffset.UTC));
  }

  private ApiException validatePresentedGrant(
      PrivateAssetMapper mapper,
      PrivateAssetGrantEntity grant,
      ConsumePrivateAssetReadGrantCommand command,
      byte[] digest) {
    if (!"ISSUED".equals(grant.getStatus()) || grant.isExpired()) {
      mapper.expireGrant(grant.getId());
      return gone();
    }
    if (!grant.getOperatorId().equals(command.context().operatorId())
        || grant.getSessionGeneration() != command.sessionGeneration()
        || !MessageDigest.isEqual(
            grant.getSessionIdDigest(),
            sha256(command.sessionId().getBytes(StandardCharsets.UTF_8)))) {
      return new ApiException(CommonApiCodes.FORBIDDEN, "读取授权不匹配");
    }
    PrivateAssetGrantKeyProvider.KeyMaterial key = requireKey();
    if (!key.keyVersion().equals(grant.getTokenKeyVersion())) throw dependency();
    verifyTokenMaterial(grant, command.token(), key);
    if (!MessageDigest.isEqual(digest, grant.getTokenDigest())) return gone();
    return null;
  }

  private void verifyTokenMaterial(
      PrivateAssetGrantEntity grant, String token, PrivateAssetGrantKeyProvider.KeyMaterial key) {
    String expected =
        deriveToken(
            key,
            grant.getId(),
            grant.getOperatorId(),
            grant.getSessionIdDigest(),
            grant.getRequestId());
    byte[] supplied = token.getBytes(StandardCharsets.US_ASCII);
    byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
    byte[] proof = hmac(key, bytes("proof"), sha256(supplied));
    if (!MessageDigest.isEqual(supplied, expectedBytes)
        || !MessageDigest.isEqual(proof, grant.getTokenProof())) throw gone();
  }

  private ReadAuthorizationProof authorize(
      ReadAuthorizationPhase phase,
      String sessionId,
      long generation,
      String applicationId,
      String revisionId,
      String assetId,
      String operatorId,
      String purposeCode) {
    try {
      ReadAuthorizationProof proof =
          authorizer.authorize(
              new ReadAuthorizationRequest(
                  phase,
                  applicationId,
                  revisionId,
                  assetId,
                  operatorId,
                  sessionId,
                  generation,
                  purposeCode));
      return Objects.requireNonNull(proof, "authorization proof");
    } catch (ApiException denied) {
      throw denied;
    } catch (RuntimeException unavailable) {
      throw dependency();
    }
  }

  private static void validateProof(
      ReadAuthorizationProof proof, PrivateAssetEntity asset, long revisionId) {
    requireText(proof.materialId(), 32);
    requireText(proof.materialType(), 64);
    requireText(proof.authzVersion(), 128);
    requireText(proof.scopeVersion(), 128);
    if (apiId(proof.ownerUserId()) != asset.getOwnerUserId()
        || apiId(proof.assetId()) != asset.getId()
        || apiId(proof.revisionId()) != revisionId
        || !asset.getObjectSha256().equals(proof.objectSha256())) {
      throw new ApiException(CommonApiCodes.FORBIDDEN, "私有材料授权事实不匹配");
    }
  }

  private UploadInput validateUpload(UploadPrivateAssetCommand command) {
    if (command == null || command.context() == null || command.content() == null)
      throw invalid("上传参数无效");
    PublicContractChecks.requireTerminalRequestId(command.context().requestId());
    long owner = apiId(command.ownerUserId());
    if (command.context().operatorType() != OperatorType.USER
        || !command.ownerUserId().equals(command.context().operatorId())) throw forbidden();
    if (!UPLOAD_PURPOSES.contains(command.purpose())) throw invalid("材料用途无效");
    String media = requireMediaType(command.declaredMediaType());
    if (command.declaredBytes() < 1 || command.declaredBytes() > MAX_BYTES) throw invalid("文件大小无效");
    return new UploadInput(owner, command.purpose(), media);
  }

  private IssueInput validateIssue(IssuePrivateAssetReadGrantCommand command) {
    if (command == null || command.context() == null) throw invalid("授权参数无效");
    PublicContractChecks.requireTerminalRequestId(command.context().requestId());
    if (command.context().operatorType() != OperatorType.PLATFORM_OPERATOR) throw forbidden();
    requireText(command.context().operatorId(), 64);
    requireText(command.sessionId(), 512);
    requireText(command.purposeCode(), 64);
    requireText(command.reason(), 1000);
    if (command.sessionGeneration() < 0) throw invalid("会话版本无效");
    return new IssueInput(
        apiId(command.assetId()), apiId(command.applicationId()), apiId(command.revisionId()));
  }

  private ConsumeInput validateConsume(ConsumePrivateAssetReadGrantCommand command) {
    if (command == null || command.context() == null) throw invalid("读取参数无效");
    PublicContractChecks.requireRequestId(command.context().requestId());
    if (command.context().operatorType() != OperatorType.PLATFORM_OPERATOR) throw forbidden();
    requireText(command.context().operatorId(), 64);
    requireText(command.sessionId(), 512);
    requireText(command.token(), 512);
    if (command.sessionGeneration() < 0) throw invalid("会话版本无效");
    return new ConsumeInput();
  }

  private static byte[] readBounded(java.io.InputStream input, long declaredBytes) {
    try {
      ByteArrayOutputStream out =
          new ByteArrayOutputStream((int) Math.min(declaredBytes, 64 * 1024));
      byte[] buffer = new byte[8192];
      int total = 0;
      for (int read; (read = input.read(buffer)) != -1; ) {
        total = Math.addExact(total, read);
        if (total > MAX_BYTES) throw invalid("文件超过 10 MiB");
        out.write(buffer, 0, read);
      }
      if (total != declaredBytes || total == 0) throw invalid("文件长度与声明不一致");
      return out.toByteArray();
    } catch (IOException failure) {
      throw invalid("无法读取上传文件");
    }
  }

  private PrivateAssetEntity requireAsset(long id) {
    PrivateAssetEntity asset = repository.read(mapper -> mapper.selectAsset(id));
    if (asset == null) throw new ApiException(CommonApiCodes.NOT_FOUND, "私有材料不存在");
    return asset;
  }

  private static PrivateAssetFact fact(PrivateAssetEntity row) {
    return new PrivateAssetFact(
        IDS.toApi(row.getId()),
        IDS.toApi(row.getOwnerUserId()),
        row.getSourceSha256(),
        row.getObjectSha256(),
        row.getObjectVersionRef(),
        row.getMediaType(),
        row.getBytes() == null ? 0 : row.getBytes(),
        PrivateAssetStatus.valueOf(row.getStatus()),
        Long.toString(row.getVersion()));
  }

  private static UploadPrivateAssetResult uploadResultOrFailure(
      PrivateAssetEntity asset, boolean created) {
    if ("REJECTED".equals(asset.getStatus()) || "QUARANTINED".equals(asset.getStatus())) {
      throw rejected();
    }
    if (!"READY".equals(asset.getStatus())) throw dependency();
    return new UploadPrivateAssetResult(
        IDS.toApi(asset.getId()),
        created,
        PrivateAssetStatus.valueOf(asset.getStatus()),
        asset.getObjectSha256(),
        asset.getMediaType(),
        asset.getBytes() == null ? 0 : asset.getBytes());
  }

  private static boolean terminal(PrivateAssetEntity asset) {
    return Set.of("READY", "REJECTED", "QUARANTINED", "RETIRED").contains(asset.getStatus());
  }

  private static void requireReady(PrivateAssetEntity asset) {
    if (asset == null) throw new ApiException(CommonApiCodes.NOT_FOUND, "私有材料不存在");
    if (!"READY".equals(asset.getStatus()) || asset.getObjectVersionRef() == null) {
      throw new ApiException(ASSET_NOT_READY, "私有材料尚未就绪");
    }
  }

  private void finishRejected(long assetId, String status, String code, String provider) {
    repository.transaction(
        mapper -> {
          PrivateAssetEntity locked = mapper.selectAssetForUpdate(assetId);
          if (locked != null && !terminal(locked))
            mapper.updateRejected(assetId, status, provider, code);
          return null;
        });
  }

  private static PrivateAssetScanner.ScanResult requireScan(PrivateAssetScanner.ScanResult result) {
    if (result == null) throw new IllegalStateException("Scanner returned no result");
    requireText(result.providerVersion(), 128);
    requireText(result.resultCode(), 64);
    return result;
  }

  private static void validateNormalized(PrivateAssetImageNormalizer.NormalizedImage value) {
    if (value == null
        || value.content() == null
        || value.content().length < 1
        || value.content().length > MAX_BYTES
        || value.width() < 1
        || value.height() < 1) {
      throw new IllegalArgumentException("Invalid normalized image");
    }
    requireMediaType(value.mediaType());
  }

  private static void validateRendered(PrivateAssetWatermarkRenderer.RenderedImage value) {
    if (value == null || value.content() == null || value.content().length == 0) throw dependency();
    requireMediaType(value.mediaType());
  }

  private static void requireStored(
      PrivateObjectStore.StoredObject stored, String hash, long bytes) {
    if (stored == null
        || !hash.equals(stored.sha256())
        || stored.bytes() != bytes
        || stored.versionRef() == null
        || stored.versionRef().isBlank()) {
      throw new IllegalStateException("Immutable object fact mismatch");
    }
  }

  private PrivateAssetGrantKeyProvider.KeyMaterial requireKey() {
    PrivateAssetGrantKeyProvider.KeyMaterial key;
    try {
      key = grantKeys.current();
    } catch (RuntimeException failure) {
      throw dependency();
    }
    if (key == null || key.hmacKey() == null) throw dependency();
    requireText(key.keyVersion(), 64);
    return key;
  }

  private static String deriveToken(
      PrivateAssetGrantKeyProvider.KeyMaterial key,
      long grantId,
      String operatorId,
      byte[] sessionDigest,
      String requestId) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            hmac(
                key,
                bytes("private-asset-read-token-v1"),
                bytes(grantId),
                bytes(operatorId),
                sessionDigest,
                bytes(requestId)));
  }

  private static byte[] hmac(PrivateAssetGrantKeyProvider.KeyMaterial key, byte[]... fields) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key.hmacKey());
      for (byte[] field : fields) {
        mac.update(bytes(field.length));
        mac.update(field);
      }
      return mac.doFinal();
    } catch (Exception failure) {
      throw dependency();
    }
  }

  private static byte[] digestFields(Object... fields) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Object field : fields) {
        byte[] value =
            field instanceof byte[] b ? b : String.valueOf(field).getBytes(StandardCharsets.UTF_8);
        digest.update(bytes(value.length));
        digest.update(value);
      }
      return digest.digest();
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static String sha256Hex(byte[] value) {
    return HexFormat.of().formatHex(sha256(value));
  }

  private static byte[] bytes(Object value) {
    return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] bytes(int value) {
    return new byte[] {
      (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
    };
  }

  private long nextId() {
    long id = ids.nextId();
    if (id <= 0) throw new IllegalStateException("Invalid Snowflake ID");
    return id;
  }

  private static long apiId(String value) {
    try {
      return IDS.fromApi(value);
    } catch (IllegalArgumentException invalid) {
      throw invalid("编号无效");
    }
  }

  private static String requireMediaType(String value) {
    if (!"image/jpeg".equals(value) && !"image/png".equals(value)) throw invalid("图片类型无效");
    return value;
  }

  private static String requireText(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.codePoints().anyMatch(Character::isISOControl)) throw invalid("参数无效");
    return value;
  }

  private static ApiException invalid(String message) {
    return new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
  }

  private static ApiException forbidden() {
    return new ApiException(CommonApiCodes.FORBIDDEN, "无权访问私有材料");
  }

  private static ApiException conflict() {
    return new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "相同 requestId 对应不同参数");
  }

  private static ApiException dependency() {
    return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "私有材料服务暂不可用");
  }

  private static ApiException gone() {
    return new ApiException(GRANT_GONE, "读取授权已失效");
  }

  private static ApiException rejected() {
    return new ApiException(ASSET_REJECTED, "上传文件未通过安全检查");
  }

  private record UploadInput(long ownerId, String purpose, String mediaType) {}

  private record UploadBinding(long assetId, boolean created) {}

  private record IssueInput(long assetId, long applicationId, long revisionId) {}

  private record ConsumeInput() {}

  private record ConsumeAdmission(PrivateAssetGrantEntity grant, ApiException failure) {}

  public enum ReconcileResult {
    COMPLETE,
    RETRY
  }
}
