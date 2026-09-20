package com.petplatform.merchant.biz.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.petplatform.common.*;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;
import com.petplatform.merchant.biz.application.ApplicationFinalAuthorizationPort.*;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts.ProtectedValue;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantApplicationStore;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.*;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantApplicationMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transactional MER-001 lifecycle. External private facts are supplied only by fail-closed ports.
 */
public final class MerchantApplicationService {
  private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
  private static final Set<String> TYPES =
      Set.of(
          "PET_LIFE_STORE",
          "PET_HOSPITAL",
          "PET_GROOMING",
          "PET_BOARDING",
          "PET_TRAINING",
          "OTHER");
  private static final Set<String> STATUSES = Set.of("DRAFT", "REVIEWING", "APPROVED", "REJECTED");
  private static final Pattern PHONE = Pattern.compile("1[0-9]{10}"),
      SHA = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern CONTACT_NAME = Pattern.compile("[\\p{IsHan}·]{2,20}");
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final DateTimeFormatter EVENT_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX");
  private final MerchantApplicationStore store;
  private final MerchantApplicationDependencies deps;
  private final Clock clock;

  public MerchantApplicationService(
      MerchantApplicationStore store, MerchantApplicationDependencies deps, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.deps = Objects.requireNonNull(deps);
    this.clock = Objects.requireNonNull(clock);
  }

  public MerchantApplicationResult create(CreateMerchantApplicationCommand c) {
    return createOutcome(c).receipt();
  }

  public ApplicationCommandOutcome createOutcome(CreateMerchantApplicationCommand c) {
    CommandContext ctx = user(c == null ? null : c.context());
    PreparedDraft draft = prepare(c.draft(), principal(ctx), false);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("command", "create-draft");
    p.put("draft", draft.canonical());
    Execution<MerchantApplicationResult> outcome =
        commandOutcome(
            "merchant.application.create-draft",
            ctx,
            "OWNER:" + ctx.operatorId(),
            p,
            m -> {
              MerchantApplicationEntity existing = m.selectByOwner(principal(ctx));
              if (existing != null) conflict("the owner already has an application");
              long id = store.nextId(), merchantId = store.nextId();
              LocalDateTime now = now();
              try {
                one(m.insertApplication(id, principal(ctx), merchantId, now));
              } catch (DuplicateKeyException duplicateOwner) {
                conflict("the owner already has an application");
              }
              if (draft.present()) {
                long revisionId = store.nextId();
                insertRevision(m, id, revisionId, 1, draft, principal(ctx), now);
                one(m.pointCurrentRevision(id, revisionId, 0, now));
                insertMaterials(m, id, revisionId, draft, principal(ctx), now);
                one(
                    m.insertAudit(
                        store.nextId(),
                        id,
                        revisionId,
                        "USER",
                        principal(ctx),
                        "DRAFT_SAVE",
                        "DRAFT",
                        "DRAFT",
                        bytes(ctx.requestId()),
                        ctx.traceId(),
                        null,
                        now));
              }
              return result(m, required(m.selectById(id)), true);
            },
            MerchantApplicationResult.class,
            null);
    return new ApplicationCommandOutcome(outcome.value(), outcome.created());
  }

  public MerchantApplicationResult save(SaveMerchantApplicationDraftCommand c) {
    if (c == null) invalid("command is required");
    CommandContext ctx = user(c.context());
    long id = id(c.applicationId(), "applicationId");
    // Reject another owner's application before resolving any submitted private asset metadata.
    // The execution transaction repeats ownership under its row lock before writing.
    store.read(mapper -> owned(mapper.selectById(id), principal(ctx)));
    PreparedDraft draft = prepare(c.draft(), principal(ctx), false);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("applicationId", c.applicationId());
    p.put("expectedVersion", c.expectedVersion());
    p.put("draft", draft.canonical());
    return command(
        "merchant.application.save-draft",
        ctx,
        "APPLICATION:" + c.applicationId(),
        p,
        m -> {
          MerchantApplicationEntity a = owned(m.selectByIdForUpdate(id), principal(ctx));
          if (!Set.of("DRAFT", "REJECTED").contains(a.getStatus()))
            conflict("application cannot be edited in its current status");
          if (a.getVersion() != c.expectedVersion()) conflict("application version changed");
          MerchantApplicationRevisionEntity prior =
              a.getCurrentRevisionId() == null
                  ? null
                  : m.selectRevision(id, a.getCurrentRevisionId());
          int no = prior == null ? 1 : prior.getRevisionNo() + 1;
          long revisionId = store.nextId();
          LocalDateTime now = now();
          insertRevision(m, id, revisionId, no, draft, principal(ctx), now);
          insertMaterials(m, id, revisionId, draft, principal(ctx), now);
          one(m.pointCurrentRevision(id, revisionId, c.expectedVersion(), now));
          one(
              m.insertAudit(
                  store.nextId(),
                  id,
                  revisionId,
                  "USER",
                  principal(ctx),
                  "DRAFT_SAVE",
                  a.getStatus(),
                  a.getStatus(),
                  bytes(ctx.requestId()),
                  ctx.traceId(),
                  null,
                  now));
          return result(m, required(m.selectById(id)), true);
        },
        MerchantApplicationResult.class,
        null);
  }

  public MerchantApplicationResult submit(SubmitMerchantApplicationCommand c) {
    if (c == null) invalid("command is required");
    CommandContext ctx = user(c.context());
    long id = id(c.applicationId(), "applicationId"), revisionId = id(c.revisionId(), "revisionId");
    Map<String, Object> p =
        Map.of(
            "applicationId",
            c.applicationId(),
            "expectedVersion",
            c.expectedVersion(),
            "revisionId",
            c.revisionId());
    return command(
        "merchant.application.submit",
        ctx,
        "APPLICATION:" + c.applicationId(),
        p,
        m -> {
          MerchantApplicationEntity a = owned(m.selectByIdForUpdate(id), principal(ctx));
          if (a.getVersion() != c.expectedVersion()
              || !Objects.equals(a.getCurrentRevisionId(), revisionId))
            conflict("application version or revision changed");
          if (!Set.of("DRAFT", "REJECTED").contains(a.getStatus()))
            conflict("application cannot be submitted");
          MerchantApplicationRevisionEntity r = requiredRevision(m.selectRevision(id, revisionId));
          List<MerchantMaterialEntity> materials = m.selectRevisionMaterials(id, revisionId);
          validateSubmission(r, materials, principal(ctx));
          LocalDate businessDate = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
          for (MerchantEvidenceEntity known : m.selectKnownEvidenceForRevision(id, revisionId)) {
            if (known.getValidFrom() != null && known.getValidFrom().isAfter(businessDate)
                || known.getValidTo() != null && known.getValidTo().isBefore(businessDate)) {
              conflict("a known credential validity period does not include the business date");
            }
          }
          String policy = m.selectPolicyVersionForUpdate();
          if (policy == null || !policy.equals(deps.credentials().availablePolicyVersion()))
            unavailable("credential HMAC policy is unavailable or mismatched");
          long taskId = store.nextId();
          int submissionNo = a.getApplicationNo() == null ? 1 : nextSubmission(m, a);
          LocalDateTime now = now();
          one(m.insertTask(taskId, id, revisionId, submissionNo, now));
          submitWithUniqueApplicationNo(m, a, id, revisionId, taskId, c.expectedVersion(), now);
          one(
              m.insertAudit(
                  store.nextId(),
                  id,
                  revisionId,
                  "USER",
                  principal(ctx),
                  "SUBMIT",
                  a.getStatus(),
                  "REVIEWING",
                  bytes(ctx.requestId()),
                  ctx.traceId(),
                  null,
                  now));
          return result(m, required(m.selectById(id)), true);
        },
        MerchantApplicationResult.class,
        null);
  }

  public ReviewTaskResult claim(ClaimMerchantApplicationCommand c) {
    if (c == null) invalid("command is required");
    CommandContext ctx = operator(c.context());
    long id = id(c.applicationId(), "applicationId");
    Map<String, Object> p =
        Map.of("applicationId", c.applicationId(), "expectedTaskVersion", c.expectedTaskVersion());
    return command(
        "merchant.application.claim",
        ctx,
        "APPLICATION:" + c.applicationId(),
        p,
        m -> {
          MerchantApplicationEntity a = required(m.selectByIdForUpdate(id));
          authorize(
              c.authorization(),
              ctx,
              a,
              m,
              "merchant.application.decide",
              "APPLICATION_REVIEW",
              Phase.EXECUTE);
          a = reviewing(a);
          MerchantReviewTaskEntity t = task(m, a, true);
          if (t.getVersion() != c.expectedTaskVersion() || !"AVAILABLE".equals(t.getStatus()))
            conflict("review task is not available at the expected version");
          LocalDateTime now = now();
          one(m.claimTask(id, t.getId(), c.expectedTaskVersion(), principal(ctx), now));
          one(
              m.insertAudit(
                  store.nextId(),
                  id,
                  a.getSubmittedRevisionId(),
                  "PLATFORM_OPERATOR",
                  principal(ctx),
                  "CLAIM",
                  "REVIEWING",
                  "REVIEWING",
                  bytes(ctx.requestId()),
                  ctx.traceId(),
                  null,
                  now));
          return taskView(requiredTask(m.selectTask(id, t.getId())));
        },
        ReviewTaskResult.class,
        (m, b) ->
            authorize(
                c.authorization(),
                ctx,
                required(m.selectById(id)),
                m,
                "merchant.application.decide",
                "APPLICATION_REVIEW",
                Phase.READ_RESULT));
  }

  public ReviewTaskResult release(ReleaseMerchantApplicationCommand c) {
    if (c == null) invalid("command is required");
    CommandContext ctx = operator(c.context());
    long id = id(c.applicationId(), "applicationId");
    Map<String, Object> p =
        Map.of("applicationId", c.applicationId(), "expectedTaskVersion", c.expectedTaskVersion());
    return command(
        "merchant.application.release",
        ctx,
        "APPLICATION:" + c.applicationId(),
        p,
        m -> {
          MerchantApplicationEntity a = required(m.selectByIdForUpdate(id));
          authorize(
              c.authorization(),
              ctx,
              a,
              m,
              "merchant.application.decide",
              "APPLICATION_REVIEW",
              Phase.EXECUTE);
          a = reviewing(a);
          MerchantReviewTaskEntity t = task(m, a, true);
          currentClaimant(t, ctx, c.expectedTaskVersion());
          LocalDateTime now = now();
          one(m.releaseTask(id, t.getId(), c.expectedTaskVersion(), principal(ctx), now));
          one(
              m.insertAudit(
                  store.nextId(),
                  id,
                  a.getSubmittedRevisionId(),
                  "PLATFORM_OPERATOR",
                  principal(ctx),
                  "RELEASE",
                  "REVIEWING",
                  "REVIEWING",
                  bytes(ctx.requestId()),
                  ctx.traceId(),
                  null,
                  now));
          return taskView(requiredTask(m.selectTask(id, t.getId())));
        },
        ReviewTaskResult.class,
        (m, b) ->
            authorize(
                c.authorization(),
                ctx,
                required(m.selectById(id)),
                m,
                "merchant.application.decide",
                "APPLICATION_REVIEW",
                Phase.READ_RESULT));
  }

  public MerchantApplicationResult verify(VerifyMerchantSubjectCommand c) {
    if (c == null) invalid("command is required");
    CommandContext ctx = operator(c.context());
    long id = id(c.applicationId(), "applicationId"),
        revisionId = id(c.submissionRevisionId(), "submissionRevisionId");
    if (!Boolean.TRUE.equals(c.confirmed())) invalid("confirmed must be true");
    if (c.reason() == null || c.reason().isBlank() || c.reason().length() > 500)
      invalid("reason is required and must be at most 500 characters");
    List<PreparedEvidence> evidence = prepareEvidence(c.evidenceItems());
    ProtectedValue reasonToken =
        deps.protectedValues()
            .protect("merchant-application-verification-reason-idempotency", c.reason());
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("applicationId", c.applicationId());
    p.put("revisionId", c.submissionRevisionId());
    p.put("expectedVersion", c.expectedVersion());
    p.put("expectedTaskVersion", c.expectedTaskVersion());
    p.put("reasonToken", hex(reasonToken.equalityToken()));
    p.put("evidence", evidence.stream().map(PreparedEvidence::canonical).toList());
    return command(
        "merchant.application.manual-verify",
        ctx,
        "APPLICATION:" + c.applicationId(),
        p,
        m -> {
          MerchantApplicationEntity a = required(m.selectByIdForUpdate(id));
          authorize(
              c.authorization(),
              ctx,
              a,
              m,
              "merchant.application.decide",
              "APPLICATION_REVIEW",
              Phase.EXECUTE);
          authorize(
              c.authorization(),
              ctx,
              a,
              m,
              "merchant.identity.reveal",
              "IDENTITY_VERIFICATION",
              Phase.EXECUTE);
          a = reviewing(a);
          if (a.getVersion() != c.expectedVersion()
              || !Objects.equals(a.getSubmittedRevisionId(), revisionId))
            conflict("application version or submitted revision changed");
          MerchantReviewTaskEntity t = task(m, a, true);
          currentClaimant(t, ctx, c.expectedTaskVersion());
          String policy = m.selectPolicyVersionForUpdate();
          if (policy == null) unavailable("subject lookup policy is unavailable");
          List<MerchantMaterialEntity> materials = m.selectRevisionMaterials(id, revisionId);
          Map<String, MerchantMaterialEntity> byType = new HashMap<>();
          materials.forEach(x -> byType.put(x.getMaterialType(), x));
          LocalDateTime now = now();
          List<MerchantClaimEntity> old = m.selectActiveClaimsForUpdate(id);
          Map<String, MerchantClaimEntity> active = new HashMap<>();
          old.forEach(x -> active.put(x.getClaimType(), x));
          List<Long> replacedClaimIds = new ArrayList<>();
          for (PreparedEvidence e : evidence) {
            if (!policy.equals(e.keyVersion()) || e.policySlot() != 1)
              unavailable("credential HMAC policy does not match the persisted policy");
            MerchantMaterialEntity mat = byType.get(e.materialType());
            if (mat == null) conflict("evidence material is not in the submitted revision");
            if (!e.materialId().equals(IDS.toApi(mat.getId()))
                || !e.materialSha256().equals(mat.getSha256()))
              conflict("evidence material reference does not match the submitted revision");
            long evidenceId = store.nextId();
            one(
                m.insertEvidence(
                    evidenceId,
                    id,
                    revisionId,
                    mat.getId(),
                    mat.getMaterialType(),
                    mat.getSha256(),
                    e.credentialType(),
                    e.subjectProtected(),
                    e.identifierProtected(),
                    e.digest(),
                    e.policySlot(),
                    e.keyVersion(),
                    e.validityKind(),
                    e.validFrom(),
                    e.validTo(),
                    e.validityBasisProtected(),
                    principal(ctx),
                    c.reason(),
                    now));
            if ("INDUSTRY_LICENSE".equals(e.credentialType())) continue;
            MerchantClaimEntity existing = active.get(e.credentialType());
            if (existing != null
                && Arrays.equals(existing.getLookupDigest(), e.digest())
                && existing.getLookupKeyVersion().equals(e.keyVersion())) continue;
            long claimId = store.nextId();
            try {
              one(
                  m.insertClaim(
                      claimId,
                      e.credentialType(),
                      e.digest(),
                      e.policySlot(),
                      e.keyVersion(),
                      id,
                      revisionId,
                      evidenceId,
                      now));
            } catch (DuplicateKeyException duplicateSubject) {
              conflict("the verified subject is already occupied");
            }
            if (existing != null) replacedClaimIds.add(existing.getId());
            MerchantClaimEntity made = new MerchantClaimEntity();
            made.setId(claimId);
            made.setClaimType(e.credentialType());
            made.setLookupDigest(e.digest());
            made.setLookupPolicySlot(e.policySlot());
            made.setLookupKeyVersion(e.keyVersion());
            made.setEvidenceId(evidenceId);
            made.setStatus("ACTIVE");
            active.put(e.credentialType(), made);
          }
          MerchantClaimEntity credit = active.get("CREDIT_CODE"),
              identity = active.get("IDENTITY_NUMBER");
          if (credit == null || identity == null)
            conflict("both business and identity credentials must be verified");
          one(m.markVerified(id, credit.getId(), identity.getId(), c.expectedVersion(), now));
          // The application FK still points to ACTIVE old claims until the pointer swap above.
          // Acquire both new claims first; swap pointers, then release old claims in this
          // transaction.
          for (Long replacedClaimId : replacedClaimIds) one(m.releaseClaim(replacedClaimId, now));
          one(
              m.insertAudit(
                  store.nextId(),
                  id,
                  revisionId,
                  "PLATFORM_OPERATOR",
                  principal(ctx),
                  "MANUAL_VERIFY",
                  "REVIEWING",
                  "REVIEWING",
                  bytes(ctx.requestId()),
                  ctx.traceId(),
                  null,
                  now));
          return resultAtRevision(m, required(m.selectById(id)), revisionId);
        },
        MerchantApplicationResult.class,
        (m, b) -> {
          MerchantApplicationEntity a = required(m.selectById(id));
          authorize(
              c.authorization(),
              ctx,
              a,
              m,
              "merchant.application.decide",
              "APPLICATION_REVIEW",
              Phase.READ_RESULT);
          authorize(
              c.authorization(),
              ctx,
              a,
              m,
              "merchant.identity.reveal",
              "IDENTITY_VERIFICATION",
              Phase.READ_RESULT);
        });
  }

  public MerchantApplicationResult decide(DecideMerchantApplicationCommand c) {
    if (c == null) invalid("command is required");
    CommandContext ctx = operator(c.context());
    long id = id(c.applicationId(), "applicationId"),
        revisionId = id(c.submissionRevisionId(), "submissionRevisionId");
    if (!Boolean.TRUE.equals(c.confirmed())) invalid("confirmed must be true");
    if (!Set.of("APPROVE", "REJECT", "REQUEST_CORRECTION").contains(c.decisionType()))
      invalid("decisionType is invalid");
    if (!"APPROVE".equals(c.decisionType())
        && (c.opinion() == null || c.opinion().trim().length() < 10 || c.opinion().length() > 500))
      invalid("opinion must contain 10 to 500 characters");
    if (c.opinion() != null && c.opinion().length() > 500
        || c.internalNote() != null && c.internalNote().length() > 500)
      invalid("decision text exceeds 500 characters");
    ProtectedValue internalNote =
        c.internalNote() == null
            ? null
            : deps.protectedValues()
                .protect("merchant-application-internal-note-idempotency", c.internalNote());
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("applicationId", c.applicationId());
    p.put("decisionType", c.decisionType());
    p.put("revisionId", c.submissionRevisionId());
    p.put("expectedVersion", c.expectedVersion());
    p.put("expectedTaskVersion", c.expectedTaskVersion());
    p.put("opinion", c.opinion());
    p.put("internalNoteToken", internalNote == null ? null : hex(internalNote.equalityToken()));
    return command(
        "merchant.application.decide",
        ctx,
        "APPLICATION:" + c.applicationId(),
        p,
        m -> {
          MerchantApplicationEntity a = required(m.selectByIdForUpdate(id));
          Decision auth =
              authorize(
                  c.authorization(),
                  ctx,
                  a,
                  m,
                  "merchant.application.decide",
                  "APPLICATION_REVIEW",
                  Phase.EXECUTE);
          a = reviewing(a);
          if (a.getVersion() != c.expectedVersion()
              || !Objects.equals(a.getSubmittedRevisionId(), revisionId))
            conflict("application version or submitted revision changed");
          MerchantReviewTaskEntity t = task(m, a, true);
          currentClaimant(t, ctx, c.expectedTaskVersion());
          MerchantApplicationRevisionEntity r = requiredRevision(m.selectRevision(id, revisionId));
          MerchantEvidenceEntity creditEvidence = new MerchantEvidenceEntity(),
              identityEvidence = new MerchantEvidenceEntity();
          MerchantClaimEntity creditClaim = new MerchantClaimEntity(),
              identityClaim = new MerchantClaimEntity();
          if ("APPROVE".equals(c.decisionType())) {
            if (!"VERIFIED".equals(a.getSubjectVerificationStatus()))
              conflict("subject verification is incomplete");
            List<MerchantEvidenceEntity> es = m.selectVerifiedEvidenceForUpdate(id, revisionId);
            creditEvidence = uniqueEvidence(es, "CREDIT_CODE");
            identityEvidence = uniqueEvidence(es, "IDENTITY_NUMBER");
            List<MerchantClaimEntity> claims = m.selectActiveClaimsForUpdate(id);
            creditClaim = uniqueClaim(claims, "CREDIT_CODE");
            identityClaim = uniqueClaim(claims, "IDENTITY_NUMBER");
            proof(creditEvidence, creditClaim);
            proof(identityEvidence, identityClaim);
            LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
            valid(creditEvidence, today);
            valid(identityEvidence, today);
            if ("PET_HOSPITAL".equals(r.getMerchantTypeCode())) {
              MerchantEvidenceEntity industryEvidence = uniqueEvidence(es, "INDUSTRY_LICENSE");
              valid(industryEvidence, today);
            }
            validateSubmission(r, m.selectRevisionMaterials(id, revisionId), a.getOwnerUserId());
            try {
              if (!deps.credentials()
                  .subjectMatches(creditEvidence.getSubjectNameProtected(), r.getMerchantName()))
                conflict("verified license subject does not match merchantName");
            } catch (UnsupportedOperationException missing) {
              unavailable("protected subject comparison is unavailable");
            }
            if (!Objects.equals(a.getCurrentCreditClaimId(), creditClaim.getId())
                || !Objects.equals(a.getCurrentIdentityClaimId(), identityClaim.getId()))
              unavailable("application claim projection is inconsistent");
          }
          LocalDateTime now = now();
          long decisionId = store.nextId(), auditId = store.nextId();
          String target = "APPROVE".equals(c.decisionType()) ? "APPROVED" : "REJECTED";
          one(
              m.insertDecision(
                  decisionId,
                  id,
                  revisionId,
                  t.getId(),
                  c.decisionType(),
                  trim(c.opinion()),
                  trim(c.internalNote()),
                  principal(ctx),
                  auth.authzVersion(),
                  Long.toString(a.getVersion()),
                  bytes(ctx.requestId()),
                  ctx.traceId(),
                  creditEvidence,
                  identityEvidence,
                  creditClaim,
                  identityClaim,
                  now));
          one(
              m.insertAudit(
                  auditId,
                  id,
                  revisionId,
                  "PLATFORM_OPERATOR",
                  principal(ctx),
                  "DECISION",
                  "REVIEWING",
                  target,
                  bytes(ctx.requestId()),
                  ctx.traceId(),
                  decisionId,
                  now));
          if ("APPROVE".equals(c.decisionType())) {
            one(
                m.insertMerchant(
                    a.getReservedMerchantId(), a.getOwnerUserId(), r.getMerchantName(), now));
            one(
                m.insertStore(
                    store.nextId(),
                    a.getReservedMerchantId(),
                    r.getMerchantName(),
                    r.getAddress(),
                    r.getLongitude(),
                    r.getLatitude(),
                    now));
            one(
                m.insertProfile(
                    a.getReservedMerchantId(),
                    id,
                    revisionId,
                    r.getMerchantTypeCode(),
                    r.getCityCode(),
                    now));
          }
          one(m.closeTask(id, t.getId(), c.expectedTaskVersion(), principal(ctx), now));
          one(
              m.finalizeDecision(
                  id, decisionId, c.decisionType(), auditId, target, c.expectedVersion(), now));
          long eventId = store.nextId();
          deps.events()
              .publish(
                  new IntegrationEvent<>(
                      Long.toString(eventId),
                      "MerchantApplicationReviewedEvent.v1",
                      1,
                      utc(now),
                      "MERCHANT_APPLICATION",
                      IDS.toApi(id),
                      ctx.traceId(),
                      eventPayload(a, r, decisionId, c.decisionType(), target, c.opinion(), now)));
          return resultAtRevision(m, required(m.selectById(id)), revisionId);
        },
        MerchantApplicationResult.class,
        (m, b) ->
            authorize(
                c.authorization(),
                ctx,
                required(m.selectById(id)),
                m,
                "merchant.application.decide",
                "APPLICATION_REVIEW",
                Phase.READ_RESULT));
  }

  public MerchantApplicationResult current(CurrentMerchantApplicationQuery q) {
    long owner = queryUser(q == null ? null : q.context());
    return store.read(
        m -> {
          MerchantApplicationEntity a = m.selectByOwner(owner);
          if (a == null) notFound();
          return result(m, a, true);
        });
  }

  public MerchantApplicationReviewDetail review(MerchantApplicationReviewQuery q) {
    if (q == null) invalid("query is required");
    QueryContext ctx = queryOperator(q.context());
    long id = id(q.applicationId(), "applicationId");
    return store.execute(
        m -> {
          MerchantApplicationEntity a = required(m.selectById(id));
          authorize(
              q.authorization(),
              ctx,
              a,
              m,
              "merchant.application.read",
              "APPLICATION_READ",
              Phase.READ_RESULT);
          if ("DRAFT".equals(a.getStatus())) notFound();
          return new MerchantApplicationReviewDetail(
              resultAtRevision(m, a, a.getSubmittedRevisionId()), taskView(task(m, a, false)));
        });
  }

  public OwnerApplicationDetail currentDetail(CurrentMerchantApplicationQuery query) {
    long owner = queryUser(query == null ? null : query.context());
    return store.read(
        mapper -> {
          MerchantApplicationEntity application = owned(mapper.selectByOwner(owner), owner);
          MerchantApplicationResult base = result(mapper, application, true);
          MerchantApplicationRevisionEntity revision =
              requiredRevision(
                  mapper.selectRevision(application.getId(), application.getCurrentRevisionId()));
          RevisionView view = base.currentRevision();
          DraftRevisionInput draft =
              new DraftRevisionInput(
                  view.merchantName(),
                  view.contactName(),
                  revealOwnerField(
                      "merchant-application-contact-phone", revision.getContactPhoneProtected()),
                  revealOwnerField(
                      "merchant-application-contact-email", revision.getEmailProtected()),
                  view.merchantTypeCode(),
                  view.cityCode(),
                  view.address(),
                  view.longitude(),
                  view.latitude(),
                  view.introduction(),
                  view.storePhotoAssetIds(),
                  view.businessLicenseAssetId(),
                  view.idCardFrontAssetId(),
                  view.idCardBackAssetId(),
                  view.industryLicenseAssetId());
          return new OwnerApplicationDetail(
              base.applicationId(),
              base.applicationNo(),
              base.reservedMerchantId(),
              base.status(),
              base.version(),
              view.revisionId(),
              new OwnerRevisionView(view.revisionId(), view.revisionNo(), draft, view.createdAt()),
              base.submittedAt(),
              base.reviewedAt(),
              base.latestDecision(),
              base.subjectVerificationStatus());
        });
  }

  private String revealOwnerField(String purpose, byte[] value) {
    if (value == null) return null;
    try {
      String revealed = deps.protectedValues().reveal(purpose, value);
      if (revealed == null) unavailable("owner field protection is unavailable");
      return revealed;
    } catch (RuntimeException failure) {
      unavailable("owner field protection is unavailable");
      return null;
    }
  }

  public MerchantApplicationPage list(MerchantApplicationReviewListQuery q) {
    if (q == null || q.page() < 1 || q.pageSize() < 1 || q.pageSize() > 100)
      invalid("page or pageSize is invalid");
    QueryContext ctx = queryOperator(q.context());
    return store.read(
        m -> {
          Decision entryAuthorization =
              authorizeCollection(
                  q.authorization(),
                  ctx,
                  "merchant.application.read",
                  "APPLICATION_LIST",
                  Phase.READ_RESULT);
          final int batchSize = 200;
          int candidateOffset = 0;
          long authorizedTotal = 0;
          long wantedFrom = (long) (q.page() - 1) * q.pageSize();
          long wantedTo = wantedFrom + q.pageSize();
          List<MerchantApplicationSummary> page = new ArrayList<>(q.pageSize());
          while (true) {
            List<Map<String, Object>> rows =
                m.listForReview(
                    blank(q.status()),
                    blank(q.merchantTypeCode()),
                    blank(q.cityCode()),
                    q.submittedFrom() == null
                        ? null
                        : q.submittedFrom().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                    q.submittedTo() == null
                        ? null
                        : q.submittedTo().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                    blank(q.keyword()),
                    candidateOffset,
                    batchSize);
            for (Map<String, Object> row : rows) {
              long id = ((Number) row.get("applicationId")).longValue();
              MerchantApplicationEntity a = required(m.selectById(id));
              try {
                authorize(
                    q.authorization(),
                    ctx,
                    a,
                    m,
                    "merchant.application.read",
                    "APPLICATION_READ",
                    Phase.READ_RESULT);
              } catch (ApiException denied) {
                if (CommonApiCodes.NOT_FOUND.equals(denied.code())) continue;
                throw denied;
              }
              if (authorizedTotal >= wantedFrom && authorizedTotal < wantedTo) {
                page.add(summary(row));
              }
              authorizedTotal++;
            }
            if (rows.size() < batchSize) break;
            if (candidateOffset > Integer.MAX_VALUE - batchSize) {
              unavailable("review list exceeds the supported scan boundary");
            }
            candidateOffset += batchSize;
          }
          Decision finalAuthorization =
              authorizeCollection(
                  q.authorization(),
                  ctx,
                  "merchant.application.read",
                  "APPLICATION_LIST",
                  Phase.READ_RESULT);
          if (!entryAuthorization.authzVersion().equals(finalAuthorization.authzVersion()))
            conflict("review permissions changed while reading the list; reload the list");
          return new MerchantApplicationPage(
              q.page(), q.pageSize(), authorizedTotal, List.copyOf(page));
        });
  }

  public MerchantApplicationScopeFact scope(MerchantApplicationScopeQuery q) {
    if (q == null) invalid("query is required");
    long id = id(q.applicationId(), "applicationId");
    return store.read(
        m -> {
          MerchantApplicationEntity a = required(m.selectById(id));
          if (a.getSubmittedRevisionId() == null) notFound();
          MerchantApplicationRevisionEntity r =
              requiredRevision(m.selectRevision(id, a.getSubmittedRevisionId()));
          return new MerchantApplicationScopeFact(
              IDS.toApi(id),
              IDS.toApi(a.getReservedMerchantId()),
              r.getCityCode(),
              IDS.toApi(a.getOwnerUserId()),
              IDS.toApi(a.getSubmittedRevisionId()),
              Long.toString(a.getVersion()));
        });
  }

  /**
   * Revalidates the exact submitted material and current claimant under the transaction opened by
   * the private-asset owner. Keeping the application and review-task rows locked until that owner
   * commits prevents a release, reassignment, or resubmission racing grant issue/consumption.
   */
  public MerchantPrivateMaterialAccessFact provePrivateMaterialAccess(
      MerchantPrivateMaterialAccessQuery q) {
    if (q == null) invalid("query is required");
    long applicationId = id(q.applicationId(), "applicationId");
    long revisionId = id(q.submittedRevisionId(), "submittedRevisionId");
    long privateAssetId = id(q.privateAssetId(), "privateAssetId");
    long operatorId = id(q.operatorId(), "operatorId");
    return store.joinCurrentTransaction(
        m -> {
          MerchantApplicationEntity a = required(m.selectByIdForUpdate(applicationId));
          strict(a);
          if (a.getSubmittedRevisionId() == null
              || a.getSubmittedRevisionId() != revisionId
              || a.getCurrentReviewTaskId() == null
              || !"REVIEWING".equals(a.getStatus())) {
            conflict("submitted application facts changed; reload before reading the material");
          }
          MerchantReviewTaskEntity task =
              m.selectTaskForUpdate(applicationId, a.getCurrentReviewTaskId());
          if (task == null
              || !"CLAIMED".equals(task.getStatus())
              || task.getSubmittedRevisionId() == null
              || task.getSubmittedRevisionId() != revisionId
              || task.getClaimedByOperatorId() == null
              || task.getClaimedByOperatorId() != operatorId) {
            throw new ApiException(
                CommonApiCodes.FORBIDDEN, "the review task is not claimed by this operator");
          }
          MerchantApplicationRevisionEntity revision =
              requiredRevision(m.selectRevision(applicationId, revisionId));
          MerchantMaterialEntity material =
              m.selectRevisionMaterialByAsset(applicationId, revisionId, privateAssetId);
          if (material == null) notFound();
          if (material.getId() == null
              || material.getId() <= 0
              || material.getPrivateAssetId() == null
              || material.getPrivateAssetId() != privateAssetId
              || material.getSha256() == null
              || !SHA.matcher(material.getSha256()).matches()
              || material.getMaterialType() == null
              || !Set.of(
                      "STORE_PHOTO",
                      "BUSINESS_LICENSE",
                      "ID_CARD_FRONT",
                      "ID_CARD_BACK",
                      "INDUSTRY_LICENSE")
                  .contains(material.getMaterialType())
              || material.getMediaType() == null
              || !Set.of("image/jpeg", "image/png").contains(material.getMediaType())
              || material.getBytes() == null
              || material.getBytes() < 1
              || material.getBytes() > 10 * 1024 * 1024) {
            unavailable("submitted material facts are damaged");
          }
          return new MerchantPrivateMaterialAccessFact(
              IDS.toApi(applicationId),
              IDS.toApi(a.getReservedMerchantId()),
              revision.getCityCode(),
              IDS.toApi(a.getOwnerUserId()),
              a.getVersion() + ":" + task.getVersion(),
              IDS.toApi(material.getId()),
              IDS.toApi(material.getPrivateAssetId()),
              material.getSha256(),
              material.getMaterialType(),
              material.getMediaType(),
              material.getBytes(),
              IDS.toApi(revisionId),
              task.getStatus(),
              IDS.toApi(task.getClaimedByOperatorId()),
              Long.toString(task.getVersion()));
        });
  }

  public MerchantApplicationEligibilityFact eligibility(MerchantApplicationEligibilityQuery q) {
    if (q == null) invalid("query is required");
    long merchant = id(q.merchantId(), "merchantId");
    return store.read(
        m -> {
          MerchantApplicationEntity a = m.selectEligibilityByMerchant(merchant);
          if (a == null) unavailable("application eligibility facts are missing");
          strict(a);
          if ("APPROVED".equals(a.getStatus()) && !Boolean.TRUE.equals(a.getChainValid()))
            unavailable("approved application joined chain is damaged");
          return new MerchantApplicationEligibilityFact(
              IDS.toApi(a.getId()),
              IDS.toApi(merchant),
              a.getStatus(),
              a.getVersion(),
              a.getCurrentDecisionId() == null ? null : IDS.toApi(a.getCurrentDecisionId()),
              utc(a.getReviewedAt()));
        });
  }

  private <T> T command(
      String namespace,
      CommandContext ctx,
      String authority,
      Map<String, Object> params,
      Function<MerchantApplicationMapper, T> action,
      Class<T> type,
      ReplayGuard replay) {
    return commandOutcome(namespace, ctx, authority, params, action, type, replay).value();
  }

  private record Execution<T>(T value, boolean created) {}

  private <T> Execution<T> commandOutcome(
      String namespace,
      CommandContext ctx,
      String authority,
      Map<String, Object> params,
      Function<MerchantApplicationMapper, T> action,
      Class<T> type,
      ReplayGuard replay) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "application command admission must not run inside an existing transaction");
    }
    MerchantCanonicalParams.Canonical canonical = MerchantCanonicalParams.of(params);
    byte[] key =
        MerchantRequestKey.encode(
            namespace, ctx.operatorType().name(), ctx.operatorId(), authority, ctx.requestId());
    admitWithRecovery(key, canonical, ctx.traceId());
    try {
      return store.execute(
          m -> {
            MerchantApplicationStore.Binding b = MerchantApplicationStore.require(m, key);
            MerchantApplicationStore.same(b, canonical);
            if ("SUCCEEDED".equals(b.status())) {
              if (replay != null) replay.check(m, b);
              return new Execution<>(read(b.receiptJson(), type), false);
            }
            T result = action.apply(m);
            String json = write(result);
            one(m.markBindingSucceeded(key, json));
            return new Execution<>(result, true);
          });
    } catch (MerchantApplicationStore.CommitUnknown first) {
      try {
        return store.execute(
            m -> {
              MerchantApplicationStore.Binding b = MerchantApplicationStore.require(m, key);
              MerchantApplicationStore.same(b, canonical);
              if ("SUCCEEDED".equals(b.status())) {
                if (replay != null) replay.check(m, b);
                return new Execution<>(read(b.receiptJson(), type), false);
              }
              T result = action.apply(m);
              one(m.markBindingSucceeded(key, write(result)));
              return new Execution<>(result, true);
            });
      } catch (MerchantApplicationStore.CommitUnknown second) {
        unavailable("application commit result remains unknown");
        return null;
      }
    }
  }

  private void admitWithRecovery(
      byte[] key, MerchantCanonicalParams.Canonical canonical, String traceId) {
    try {
      store.admit(key, canonical, traceId);
    } catch (MerchantApplicationStore.CommitUnknown firstUnknown) {
      try {
        store.admit(key, canonical, traceId);
      } catch (MerchantApplicationStore.CommitUnknown secondUnknown) {
        unavailable("idempotency admission result remains unknown");
      }
    }
  }

  @FunctionalInterface
  private interface ReplayGuard {
    void check(MerchantApplicationMapper m, MerchantApplicationStore.Binding b);
  }

  private PreparedDraft prepare(DraftRevisionInput d, long owner, boolean complete) {
    if (d == null) {
      d =
          new DraftRevisionInput(
              null, null, null, null, null, null, null, null, null, null, List.of(), null, null,
              null, null);
    }
    String phone = clean(d.contactPhone()), email = clean(d.email());
    String merchantName = clean(d.merchantName());
    String contactName = clean(d.contactName());
    String merchantType = clean(d.merchantTypeCode());
    String city = clean(d.cityCode());
    String address = clean(d.address());
    String introduction = clean(d.introduction());
    if (merchantName != null && merchantName.length() > 50)
      invalid("merchantName exceeds 50 characters");
    if (contactName != null && contactName.length() > 20)
      invalid("contactName exceeds 20 characters");
    if (phone != null && phone.length() > 11) invalid("contactPhone exceeds 11 characters");
    if (email != null && email.length() > 254) invalid("email exceeds 254 characters");
    if (merchantType != null && !TYPES.contains(merchantType))
      invalid("merchantTypeCode is invalid");
    if (city != null && city.length() > 32) invalid("cityCode is too long");
    if (address != null && address.length() > 255) invalid("address is too long");
    if (introduction != null && introduction.length() > 500) invalid("introduction is too long");
    if (d.longitude() != null
        && (d.longitude().scale() > 7
            || d.longitude().compareTo(new java.math.BigDecimal("-180")) < 0
            || d.longitude().compareTo(new java.math.BigDecimal("180")) > 0))
      invalid("longitude is out of range");
    if (d.latitude() != null
        && (d.latitude().scale() > 7
            || d.latitude().compareTo(new java.math.BigDecimal("-90")) < 0
            || d.latitude().compareTo(new java.math.BigDecimal("90")) > 0))
      invalid("latitude is out of range");
    ProtectedValue pp =
        phone == null ? null : protectedValue("merchant-application-contact-phone", phone);
    ProtectedValue ep =
        email == null ? null : protectedValue("merchant-application-contact-email", email);
    List<AssetSlot> slots = assetSlots(d);
    List<Long> ids = slots.stream().map(AssetSlot::assetId).distinct().toList();
    if (ids.size() != slots.size()) invalid("private asset ids must be distinct");
    Map<Long, PrivateAssetQueryPort.PrivateAssetRef> refs = new HashMap<>();
    if (!ids.isEmpty()) {
      List<PrivateAssetQueryPort.PrivateAssetRef> resolved =
          deps.privateAssets().resolveOwned(owner, ids);
      if (resolved == null) unavailable("private asset facts are unavailable");
      for (var r : resolved) refs.put(r.assetId(), r);
      for (Long id : ids) {
        var r = refs.get(id);
        if (r == null
            || r.ownerUserId() != owner
            || !"READY".equals(r.status())
            || !Set.of("image/jpeg", "image/png").contains(r.mediaType())
            || r.bytes() < 1
            || r.bytes() > 10_485_760
            || r.sha256() == null
            || !SHA.matcher(r.sha256()).matches())
          conflict("a private asset is unavailable or invalid");
      }
    }
    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("merchantName", merchantName);
    canonical.put("contactName", contactName);
    canonical.put("contactPhoneToken", pp == null ? null : hex(pp.equalityToken()));
    canonical.put("emailToken", ep == null ? null : hex(ep.equalityToken()));
    canonical.put("merchantTypeCode", merchantType);
    canonical.put("cityCode", city);
    canonical.put("address", address);
    canonical.put("longitude", d.longitude());
    canonical.put("latitude", d.latitude());
    canonical.put("introduction", introduction);
    canonical.put(
        "assets", slots.stream().map(MerchantApplicationService::assetCanonical).toList());
    return new PreparedDraft(
        true,
        merchantName,
        contactName,
        pp == null ? null : pp.ciphertext(),
        ep == null ? null : ep.ciphertext(),
        merchantType,
        city,
        address,
        d.longitude(),
        d.latitude(),
        introduction,
        slots,
        refs,
        canonical);
  }

  private List<PreparedEvidence> prepareEvidence(List<ManualEvidenceItem> items) {
    if (items == null || items.isEmpty()) invalid("evidenceItems are required");
    List<PreparedEvidence> out = new ArrayList<>();
    Set<String> credentials = new HashSet<>();
    for (ManualEvidenceItem i : items) {
      if (i == null
          || !Set.of("BUSINESS_LICENSE", "ID_CARD_BACK", "INDUSTRY_LICENSE")
              .contains(i.materialType())) invalid("manual evidence materialType is invalid");
      String type =
          switch (i.materialType()) {
            case "BUSINESS_LICENSE" -> "CREDIT_CODE";
            case "ID_CARD_BACK" -> "IDENTITY_NUMBER";
            default -> "INDUSTRY_LICENSE";
          };
      if (!credentials.add(type)) invalid("duplicate credential evidence");
      if (i.subjectName() == null
          || i.subjectName().isBlank()
          || i.identifier() == null
          || i.identifier().isBlank()) invalid("verified subject and identifier are required");
      if (!Set.of("DATED", "LONG_TERM").contains(i.validityKind())
          || i.validFrom() == null
          || ("DATED".equals(i.validityKind())
              && (i.validTo() == null || i.validFrom().isAfter(i.validTo())))
          || ("LONG_TERM".equals(i.validityKind())
              && (i.validTo() != null || i.validityBasis() == null || i.validityBasis().isBlank())))
        invalid("verified credential validity is invalid");
      if (i.materialId() == null || i.materialSha256() == null)
        invalid("manual evidence material reference is required");
      id(i.materialId(), "materialId");
      if (!SHA.matcher(i.materialSha256()).matches()) invalid("invalid material hash");
      SubjectCredentialPort.ProtectedCredential p =
          deps.credentials().protect(type, i.subjectName(), i.identifier(), i.validityBasis());
      ProtectedValue subjectToken =
          deps.protectedValues()
              .protect("merchant-application-evidence-subject-idempotency", i.subjectName());
      ProtectedValue basisToken =
          i.validityBasis() == null
              ? null
              : deps.protectedValues()
                  .protect("merchant-application-evidence-validity-idempotency", i.validityBasis());
      if (p == null
          || p.lookupDigest() == null
          || p.lookupDigest().length != 32
          || p.policySlot() != 1
          || p.keyVersion() == null
          || p.keyVersion().isBlank()) unavailable("credential protection result is invalid");
      out.add(
          new PreparedEvidence(
              i.materialType(),
              type,
              i.subjectName().trim(),
              p.subjectNameProtected(),
              p.identifierProtected(),
              p.lookupDigest(),
              p.policySlot(),
              p.keyVersion(),
              i.validityKind(),
              i.validFrom(),
              i.validTo(),
              p.validityBasisProtected(),
              hex(subjectToken.equalityToken()),
              basisToken == null ? null : hex(basisToken.equalityToken()),
              i.materialId(),
              i.materialSha256()));
    }
    if (!credentials.containsAll(Set.of("CREDIT_CODE", "IDENTITY_NUMBER")))
      invalid("business and identity evidence are both required");
    return List.copyOf(out);
  }

  private void validateSubmission(
      MerchantApplicationRevisionEntity r, List<MerchantMaterialEntity> ms, long owner) {
    String contactPhone;
    String email = null;
    try {
      contactPhone =
          deps.protectedValues()
              .reveal("merchant-application-contact-phone", r.getContactPhoneProtected());
      if (r.getEmailProtected() != null) {
        email =
            deps.protectedValues()
                .reveal("merchant-application-contact-email", r.getEmailProtected());
      }
    } catch (UnsupportedOperationException unavailableReveal) {
      unavailable("protected contact validation is unavailable");
      return;
    }
    if (r.getMerchantName() == null
        || r.getMerchantName().length() < 2
        || r.getMerchantName().length() > 50
        || r.getContactName() == null
        || r.getContactName().length() < 2
        || r.getContactName().length() > 20
        || !CONTACT_NAME.matcher(r.getContactName()).matches()
        || contactPhone == null
        || !PHONE.matcher(contactPhone).matches()
        || (email != null && !EMAIL.matcher(email).matches())
        || !TYPES.contains(r.getMerchantTypeCode())
        || r.getCityCode() == null
        || r.getAddress() == null
        || r.getLongitude() == null
        || r.getLatitude() == null
        || r.getIntroduction() != null && r.getIntroduction().length() > 500)
      invalid("draft is incomplete or invalid for submission");
    if (!deps.cities().isOpen(r.getCityCode())) conflict("city is not open");
    if (!deps.maps()
        .isReasonable(r.getCityCode(), r.getAddress(), r.getLongitude(), r.getLatitude()))
      conflict("application location is not reasonable");
    long photos = ms.stream().filter(x -> "STORE_PHOTO".equals(x.getMaterialType())).count();
    if (photos < 1
        || photos > 6
        || count(ms, "BUSINESS_LICENSE") != 1
        || count(ms, "ID_CARD_FRONT") != 1
        || count(ms, "ID_CARD_BACK") != 1
        || ("PET_HOSPITAL".equals(r.getMerchantTypeCode()) && count(ms, "INDUSTRY_LICENSE") != 1))
      invalid("required private materials are incomplete");
    List<Long> assetIds = ms.stream().map(MerchantMaterialEntity::getPrivateAssetId).toList();
    Map<Long, PrivateAssetQueryPort.PrivateAssetRef> current = new HashMap<>();
    for (var ref : deps.privateAssets().resolveOwned(owner, assetIds))
      current.put(ref.assetId(), ref);
    for (var material : ms) {
      var ref = current.get(material.getPrivateAssetId());
      if (ref == null
          || ref.ownerUserId() != owner
          || !"READY".equals(ref.status())
          || !Objects.equals(ref.sha256(), material.getSha256())
          || !Objects.equals(ref.mediaType(), material.getMediaType())
          || ref.bytes() != material.getBytes())
        conflict("private material changed or is unavailable");
    }
  }

  private Decision authorize(
      AdminAuthorizationReference ref,
      CommandContext ctx,
      MerchantApplicationEntity a,
      MerchantApplicationMapper m,
      String action,
      String purpose,
      Phase phase) {
    return authorize0(ref, ctx.operatorId(), a, m, action, purpose, phase);
  }

  private Decision authorize(
      AdminAuthorizationReference ref,
      QueryContext ctx,
      MerchantApplicationEntity a,
      MerchantApplicationMapper m,
      String action,
      String purpose,
      Phase phase) {
    return authorize0(ref, ctx.operatorId(), a, m, action, purpose, phase);
  }

  private Decision authorizeCollection(
      AdminAuthorizationReference ref,
      QueryContext ctx,
      String action,
      String purpose,
      Phase phase) {
    if (ref == null
        || ref.sessionId() == null
        || ref.sessionId().isBlank()
        || ref.sessionGeneration() < 0)
      throw new ApiException(
          CommonApiCodes.UNAUTHORIZED, "admin authorization reference is required");
    Decision d =
        deps.authorization()
            .checkCollection(
                new CollectionCheck(
                    ref.sessionId(),
                    ref.sessionGeneration(),
                    ctx.operatorId(),
                    action,
                    purpose,
                    phase));
    if (d == null
        || d.checkedAt() == null
        || d.authzVersion() == null
        || d.authzVersion().isBlank())
      unavailable("collection authorization decision is unavailable");
    if (!d.allowed()) authorizationDenied(d.reasonCode(), false);
    return d;
  }

  private Decision authorize0(
      AdminAuthorizationReference ref,
      String operatorId,
      MerchantApplicationEntity a,
      MerchantApplicationMapper m,
      String action,
      String purpose,
      Phase phase) {
    if (ref == null
        || ref.sessionId() == null
        || ref.sessionId().isBlank()
        || ref.sessionGeneration() < 0)
      throw new ApiException(
          CommonApiCodes.UNAUTHORIZED, "admin authorization reference is required");
    if (a.getSubmittedRevisionId() == null) notFound();
    MerchantApplicationRevisionEntity r =
        requiredRevision(m.selectRevision(a.getId(), a.getSubmittedRevisionId()));
    Decision d =
        deps.authorization()
            .check(
                new Check(
                    ref.sessionId(),
                    ref.sessionGeneration(),
                    operatorId,
                    action,
                    new Resource(
                        "MERCHANT_APPLICATION",
                        IDS.toApi(a.getId()),
                        IDS.toApi(a.getReservedMerchantId()),
                        r.getCityCode(),
                        Long.toString(a.getVersion())),
                    purpose,
                    phase));
    if (d == null
        || d.checkedAt() == null
        || d.authzVersion() == null
        || d.authzVersion().isBlank()) unavailable("authorization decision is invalid");
    if (!d.allowed()) authorizationDenied(d.reasonCode(), true);
    return d;
  }

  private static void authorizationDenied(String reasonCode, boolean resourceRead) {
    if (reasonCode == null || reasonCode.isBlank()) {
      unavailable("authorization denial reason is missing");
    }
    if (resourceRead && "RESOURCE_SCOPE_DENIED".equals(reasonCode)) notFound();
    if ("ACTION_NOT_GRANTED".equals(reasonCode)) {
      throw new ApiException(CommonApiCodes.FORBIDDEN, "admin action is not authorized");
    }
    if (Set.of(
            "SESSION_NOT_FOUND",
            "SESSION_REVOKED",
            "SESSION_CLOSED",
            "SESSION_EXPIRED",
            "SESSION_GENERATION_CHANGED",
            "OPERATOR_MISMATCH")
        .contains(reasonCode)) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "admin session is no longer valid");
    }
    unavailable("authorization decision reason is unsupported");
  }

  private void insertRevision(
      MerchantApplicationMapper m,
      long app,
      long revision,
      int no,
      PreparedDraft d,
      long owner,
      LocalDateTime now) {
    one(
        m.insertRevision(
            revision,
            app,
            no,
            d.merchantName(),
            d.contactName(),
            d.phoneProtected(),
            d.emailProtected(),
            d.type(),
            d.city(),
            d.address(),
            d.longitude(),
            d.latitude(),
            d.introduction(),
            MerchantCanonicalParams.of(d.canonical()).sha256(),
            owner,
            now));
  }

  private void insertMaterials(
      MerchantApplicationMapper m,
      long app,
      long revision,
      PreparedDraft d,
      long owner,
      LocalDateTime now) {
    for (AssetSlot s : d.slots()) {
      var ref = d.refs().get(s.assetId());
      MerchantMaterialEntity existing = m.selectMaterialByAsset(app, s.assetId());
      long material;
      if (existing == null) {
        material = store.nextId();
        one(
            m.insertMaterial(
                material,
                app,
                s.type(),
                s.assetId(),
                ref.sha256(),
                ref.mediaType(),
                ref.bytes(),
                owner,
                now));
      } else {
        if (!Objects.equals(existing.getMaterialType(), s.type())
            || !Objects.equals(existing.getSha256(), ref.sha256())
            || !Objects.equals(existing.getMediaType(), ref.mediaType())
            || existing.getBytes() != ref.bytes()) {
          conflict("a reused private asset no longer matches its immutable material fact");
        }
        material = existing.getId();
      }
      one(m.linkRevisionMaterial(app, revision, material, s.type(), s.position()));
    }
  }

  private MerchantApplicationResult result(
      MerchantApplicationMapper m, MerchantApplicationEntity a, boolean materials) {
    strict(a);
    MerchantApplicationRevisionEntity r =
        a.getCurrentRevisionId() == null
            ? null
            : m.selectRevision(a.getId(), a.getCurrentRevisionId());
    RevisionView rv = null;
    if (r != null) {
      List<MerchantMaterialEntity> ms =
          materials ? m.selectRevisionMaterials(a.getId(), r.getId()) : List.of();
      rv = revision(r, ms, false);
    }
    MerchantDecisionEntity d =
        a.getCurrentDecisionId() == null
            ? null
            : m.selectDecision(a.getId(), a.getCurrentDecisionId());
    DecisionView dv =
        d == null
            ? null
            : new DecisionView(
                d.getDecisionType(),
                d.getOpinion(),
                utc(d.getDecidedAt()),
                IDS.toApi(d.getId()),
                IDS.toApi(d.getSubmittedRevisionId()));
    return new MerchantApplicationResult(
        IDS.toApi(a.getId()),
        a.getApplicationNo(),
        IDS.toApi(a.getReservedMerchantId()),
        a.getStatus(),
        a.getVersion(),
        rv,
        utc(a.getSubmittedAt()),
        utc(a.getReviewedAt()),
        dv,
        a.getSubjectVerificationStatus());
  }

  private MerchantApplicationResult resultAtRevision(
      MerchantApplicationMapper m, MerchantApplicationEntity a, long revisionId) {
    strict(a);
    MerchantApplicationRevisionEntity r = requiredRevision(m.selectRevision(a.getId(), revisionId));
    RevisionView rv = revision(r, m.selectRevisionMaterials(a.getId(), revisionId), true);
    MerchantDecisionEntity d =
        a.getCurrentDecisionId() == null
            ? null
            : m.selectDecision(a.getId(), a.getCurrentDecisionId());
    DecisionView dv =
        d == null
            ? null
            : new DecisionView(
                d.getDecisionType(),
                d.getOpinion(),
                utc(d.getDecidedAt()),
                IDS.toApi(d.getId()),
                IDS.toApi(d.getSubmittedRevisionId()));
    return new MerchantApplicationResult(
        IDS.toApi(a.getId()),
        a.getApplicationNo(),
        IDS.toApi(a.getReservedMerchantId()),
        a.getStatus(),
        a.getVersion(),
        rv,
        utc(a.getSubmittedAt()),
        utc(a.getReviewedAt()),
        dv,
        a.getSubjectVerificationStatus());
  }

  private static RevisionView revision(
      MerchantApplicationRevisionEntity r,
      List<MerchantMaterialEntity> ms,
      boolean maskContactName) {
    List<String> photos =
        ms.stream()
            .filter(x -> "STORE_PHOTO".equals(x.getMaterialType()))
            .sorted(Comparator.comparing(MerchantMaterialEntity::getPosition))
            .map(x -> IDS.toApi(x.getPrivateAssetId()))
            .toList();
    return new RevisionView(
        IDS.toApi(r.getId()),
        Integer.toString(r.getRevisionNo()),
        r.getMerchantName(),
        maskContactName && r.getContactName() != null ? "**" : r.getContactName(),
        r.getContactPhoneProtected() == null ? null : "***********",
        r.getEmailProtected() == null ? null : "***",
        r.getMerchantTypeCode(),
        r.getCityCode(),
        r.getAddress(),
        decimal(r.getLongitude()),
        decimal(r.getLatitude()),
        r.getIntroduction(),
        photos,
        asset(ms, "BUSINESS_LICENSE"),
        asset(ms, "ID_CARD_FRONT"),
        asset(ms, "ID_CARD_BACK"),
        asset(ms, "INDUSTRY_LICENSE"),
        utc(r.getCreatedAt()));
  }

  private static java.math.BigDecimal decimal(java.math.BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros();
  }

  private static String asset(List<MerchantMaterialEntity> ms, String type) {
    return ms.stream()
        .filter(x -> type.equals(x.getMaterialType()))
        .findFirst()
        .map(x -> IDS.toApi(x.getPrivateAssetId()))
        .orElse(null);
  }

  private static MerchantApplicationSummary summary(Map<String, Object> r) {
    return new MerchantApplicationSummary(
        s(r, "applicationId"),
        s(r, "applicationNo"),
        s(r, "reservedMerchantId"),
        s(r, "status"),
        ((Number) r.get("version")).longValue(),
        s(r, "merchantName"),
        s(r, "merchantTypeCode"),
        s(r, "cityCode"),
        utc((LocalDateTime) r.get("submittedAt")),
        s(r, "taskStatus"),
        s(r, "submittedRevisionId"),
        s(r, "subjectVerificationStatus"));
  }

  private static String s(Map<String, Object> r, String key) {
    Object v = r.get(key);
    return v == null ? null : v.toString();
  }

  private static ReviewTaskResult taskView(MerchantReviewTaskEntity t) {
    return new ReviewTaskResult(
        IDS.toApi(t.getApplicationId()),
        IDS.toApi(t.getId()),
        t.getStatus(),
        t.getVersion(),
        t.getClaimedByOperatorId() == null ? null : IDS.toApi(t.getClaimedByOperatorId()),
        utc(t.getClaimedAt()),
        IDS.toApi(t.getSubmittedRevisionId()));
  }

  private static void strict(MerchantApplicationEntity a) {
    if (a.getId() == null
        || a.getId() <= 0
        || a.getReservedMerchantId() == null
        || a.getReservedMerchantId() <= 0
        || a.getOwnerUserId() == null
        || a.getOwnerUserId() <= 0
        || a.getVersion() == null
        || a.getVersion() < 0
        || !STATUSES.contains(a.getStatus())) unavailable("application facts are damaged");
    if ("APPROVED".equals(a.getStatus())
        && (a.getCurrentDecisionId() == null
            || !"APPROVE".equals(a.getCurrentDecisionType())
            || a.getReviewAuditId() == null
            || a.getReviewedAt() == null
            || a.getSubmittedRevisionId() == null
            || !Objects.equals(a.getCurrentRevisionId(), a.getSubmittedRevisionId())
            || !"VERIFIED".equals(a.getSubjectVerificationStatus())
            || a.getCurrentCreditClaimId() == null
            || a.getCurrentIdentityClaimId() == null))
      unavailable("approved application chain is damaged");
  }

  private static MerchantApplicationEntity reviewing(MerchantApplicationEntity a) {
    a = required(a);
    if (!"REVIEWING".equals(a.getStatus())) conflict("application is not under review");
    return a;
  }

  private static MerchantReviewTaskEntity task(
      MerchantApplicationMapper m, MerchantApplicationEntity a, boolean lock) {
    if (a.getCurrentReviewTaskId() == null) unavailable("current review task is missing");
    MerchantReviewTaskEntity t =
        lock
            ? m.selectTaskForUpdate(a.getId(), a.getCurrentReviewTaskId())
            : m.selectTask(a.getId(), a.getCurrentReviewTaskId());
    return requiredTask(t);
  }

  private static void currentClaimant(
      MerchantReviewTaskEntity t, CommandContext ctx, long version) {
    if (t.getVersion() != version
        || !"CLAIMED".equals(t.getStatus())
        || !Objects.equals(t.getClaimedByOperatorId(), principal(ctx)))
      conflict("review task is not claimed by the current operator at the expected version");
  }

  private static MerchantEvidenceEntity uniqueEvidence(
      List<MerchantEvidenceEntity> es, String type) {
    List<MerchantEvidenceEntity> matched =
        es.stream().filter(x -> type.equals(x.getCredentialType())).toList();
    if (matched.size() != 1)
      conflict("submitted revision requires exactly one verified " + type + " evidence");
    return matched.getFirst();
  }

  private static MerchantClaimEntity uniqueClaim(List<MerchantClaimEntity> cs, String type) {
    List<MerchantClaimEntity> matched =
        cs.stream().filter(x -> type.equals(x.getClaimType())).toList();
    if (matched.size() != 1) unavailable("active subject claim chain is damaged");
    return matched.getFirst();
  }

  private static void proof(MerchantEvidenceEntity e, MerchantClaimEntity c) {
    if (!Arrays.equals(e.getIdentifierLookupDigest(), c.getLookupDigest())
        || !Objects.equals(e.getLookupPolicySlot(), c.getLookupPolicySlot())
        || !Objects.equals(e.getLookupKeyVersion(), c.getLookupKeyVersion()))
      unavailable("credential evidence and active claim do not match");
  }

  private static void valid(MerchantEvidenceEntity e, LocalDate today) {
    if (e.getValidFrom() == null
        || e.getValidFrom().isAfter(today)
        || e.getValidTo() != null && e.getValidTo().isBefore(today))
      conflict("verified credential is not currently valid");
  }

  private Map<String, Object> eventPayload(
      MerchantApplicationEntity a,
      MerchantApplicationRevisionEntity r,
      long decision,
      String decisionType,
      String status,
      String opinion,
      LocalDateTime at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("applicationId", IDS.toApi(a.getId()));
    p.put("applicationNo", a.getApplicationNo());
    p.put("ownerUserId", IDS.toApi(a.getOwnerUserId()));
    p.put("reservedMerchantId", IDS.toApi(a.getReservedMerchantId()));
    p.put("submittedRevisionId", IDS.toApi(r.getId()));
    p.put("reviewDecisionId", IDS.toApi(decision));
    p.put("decisionType", decisionType);
    p.put("applicationStatus", status);
    p.put("applicantVisibleOpinion", trim(opinion));
    p.put("decidedAt", EVENT_TIME.format(utc(at)));
    return p;
  }

  private int nextSubmission(MerchantApplicationMapper m, MerchantApplicationEntity a) {
    MerchantReviewTaskEntity prior = m.selectTask(a.getId(), a.getCurrentReviewTaskId());
    if (prior == null || prior.getSubmissionNo() == null)
      unavailable("previous review task is damaged");
    return prior.getSubmissionNo() + 1;
  }

  private void submitWithUniqueApplicationNo(
      MerchantApplicationMapper m,
      MerchantApplicationEntity application,
      long applicationId,
      long revisionId,
      long taskId,
      long expectedVersion,
      LocalDateTime now) {
    if (application.getApplicationNo() != null) {
      one(
          m.submit(
              applicationId,
              revisionId,
              taskId,
              application.getApplicationNo(),
              expectedVersion,
              "SUBJECT_VERIFICATION_PENDING",
              now));
      return;
    }
    LocalDate businessDate = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
    for (int attempt = 0; attempt < 4; attempt++) {
      try {
        one(
            m.submit(
                applicationId,
                revisionId,
                taskId,
                applicationNo(businessDate),
                expectedVersion,
                "SUBJECT_VERIFICATION_PENDING",
                now));
        return;
      } catch (DuplicateKeyException collision) {
        // The application number is human-readable random data; retry this statement only.
      }
    }
    unavailable("application number allocation is temporarily unavailable");
  }

  private static List<AssetSlot> assetSlots(DraftRevisionInput d) {
    List<AssetSlot> r = new ArrayList<>();
    List<String> photos = d.storePhotoAssetIds() == null ? List.of() : d.storePhotoAssetIds();
    if (photos.size() > 6) invalid("storePhotoAssetIds must contain at most 6 assets");
    for (int i = 0; i < photos.size(); i++)
      r.add(new AssetSlot("STORE_PHOTO", i + 1, id(photos.get(i), "storePhotoAssetIds")));
    add(r, "BUSINESS_LICENSE", d.businessLicenseAssetId());
    add(r, "ID_CARD_FRONT", d.idCardFrontAssetId());
    add(r, "ID_CARD_BACK", d.idCardBackAssetId());
    add(r, "INDUSTRY_LICENSE", d.industryLicenseAssetId());
    return List.copyOf(r);
  }

  private static void add(List<AssetSlot> l, String type, String raw) {
    if (clean(raw) != null) l.add(new AssetSlot(type, 1, id(raw, type)));
  }

  private static Map<String, Object> assetCanonical(AssetSlot slot) {
    Map<String, Object> value = new TreeMap<>();
    value.put("id", slot.assetId());
    value.put("position", slot.position());
    value.put("type", slot.type());
    return value;
  }

  private static long count(List<MerchantMaterialEntity> m, String type) {
    return m.stream().filter(x -> type.equals(x.getMaterialType())).count();
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  private static String applicationNo(LocalDate date) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    StringBuilder b = new StringBuilder("SQ").append(date.toString().replace("-", ""));
    for (int i = 0; i < 8; i++) b.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
    return b.toString();
  }

  private static CommandContext user(CommandContext c) {
    c = command(c);
    if (c.operatorType() != OperatorType.USER)
      throw new ApiException(
          CommonApiCodes.FORBIDDEN, "application command requires the owner user");
    return c;
  }

  private static CommandContext operator(CommandContext c) {
    c = command(c);
    if (c.operatorType() != OperatorType.PLATFORM_OPERATOR)
      throw new ApiException(
          CommonApiCodes.FORBIDDEN, "review command requires a platform operator");
    return c;
  }

  private static CommandContext command(CommandContext c) {
    if (c == null || c.operatorId() == null || c.operatorId().isBlank() || c.operatorType() == null)
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated context is required");
    try {
      PublicContractChecks.requireCommandRequestId(c);
      principal(c);
    } catch (IllegalArgumentException e) {
      invalid("command context is invalid");
    }
    return c;
  }

  private static long queryUser(QueryContext c) {
    if (c == null || c.operatorType() != OperatorType.USER)
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "owner query context is required");
    return principal(c.operatorId());
  }

  private static QueryContext queryOperator(QueryContext c) {
    if (c == null || c.operatorType() != OperatorType.PLATFORM_OPERATOR || c.operatorId() == null)
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "operator query context is required");
    principal(c.operatorId());
    return c;
  }

  private static long principal(CommandContext c) {
    return principal(c.operatorId());
  }

  private static long principal(String s) {
    try {
      return IDS.fromApi(s);
    } catch (Exception e) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated principal is invalid");
    }
  }

  private static long id(String s, String field) {
    try {
      return IDS.fromApi(s);
    } catch (Exception e) {
      invalid(field + " is invalid");
      return 0;
    }
  }

  private static MerchantApplicationEntity owned(MerchantApplicationEntity a, long owner) {
    a = required(a);
    if (a.getOwnerUserId() != owner) notFound();
    return a;
  }

  private static MerchantApplicationEntity required(MerchantApplicationEntity a) {
    if (a == null) notFound();
    strict(a);
    return a;
  }

  private static MerchantApplicationRevisionEntity requiredRevision(
      MerchantApplicationRevisionEntity r) {
    if (r == null || r.getId() == null || r.getRevisionNo() == null)
      unavailable("application revision is missing or damaged");
    return r;
  }

  private static MerchantReviewTaskEntity requiredTask(MerchantReviewTaskEntity t) {
    if (t == null
        || t.getId() == null
        || t.getVersion() == null
        || !Set.of("AVAILABLE", "CLAIMED", "CLOSED").contains(t.getStatus()))
      unavailable("review task is missing or damaged");
    return t;
  }

  private static void one(int n) {
    if (n != 1) conflict("resource version or state changed");
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static String clean(String s) {
    return s == null || s.trim().isEmpty() ? null : s.trim();
  }

  private static String trim(String s) {
    return clean(s);
  }

  private static String hex(byte[] b) {
    if (b == null || b.length < 16) unavailable("protected equality token is invalid");
    return MerchantCanonicalParams.sha256Hex(b);
  }

  private ProtectedValue protectedValue(String purpose, String plaintext) {
    ProtectedValue value = deps.protectedValues().protect(purpose, plaintext);
    if (value == null
        || value.ciphertext() == null
        || value.ciphertext().length < 1
        || value.ciphertext().length > 512
        || value.equalityToken() == null
        || value.equalityToken().length < 16) {
      unavailable("protected value result is invalid");
    }
    return value;
  }

  private static OffsetDateTime utc(LocalDateTime t) {
    return t == null ? null : t.atOffset(ZoneOffset.UTC);
  }

  private static String write(Object o) {
    try {
      return JSON.writeValueAsString(o);
    } catch (JsonProcessingException e) {
      unavailable("receipt serialization failed");
      return null;
    }
  }

  private static <T> T read(String j, Class<T> t) {
    try {
      return JSON.readValue(j, t);
    } catch (JsonProcessingException e) {
      unavailable("stored receipt is unreadable");
      return null;
    }
  }

  private static void invalid(String m) {
    throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, m);
  }

  private static void conflict(String m) {
    throw new ApiException(CommonApiCodes.CONFLICT, m);
  }

  private static void notFound() {
    throw new ApiException(CommonApiCodes.NOT_FOUND, "merchant application not found");
  }

  private static void unavailable(String m) {
    throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, m);
  }

  private record AssetSlot(String type, int position, long assetId) {}

  private record PreparedDraft(
      boolean present,
      String merchantName,
      String contactName,
      byte[] phoneProtected,
      byte[] emailProtected,
      String type,
      String city,
      String address,
      java.math.BigDecimal longitude,
      java.math.BigDecimal latitude,
      String introduction,
      List<AssetSlot> slots,
      Map<Long, PrivateAssetQueryPort.PrivateAssetRef> refs,
      Map<String, Object> canonical) {
    static PreparedDraft empty() {
      return new PreparedDraft(
          true, null, null, null, null, null, null, null, null, null, null, List.of(), Map.of(),
          Map.of());
    }
  }

  private record PreparedEvidence(
      String materialType,
      String credentialType,
      String subjectName,
      byte[] subjectProtected,
      byte[] identifierProtected,
      byte[] digest,
      int policySlot,
      String keyVersion,
      String validityKind,
      LocalDate validFrom,
      LocalDate validTo,
      byte[] validityBasisProtected,
      String subjectToken,
      String validityBasisToken,
      String materialId,
      String materialSha256) {
    Map<String, Object> canonical() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("materialType", materialType);
      value.put("credentialType", credentialType);
      value.put("digest", Base64.getEncoder().encodeToString(digest));
      value.put("policySlot", policySlot);
      value.put("keyVersion", keyVersion);
      value.put("validityKind", validityKind);
      value.put("validFrom", validFrom.toString());
      value.put("validTo", validTo == null ? null : validTo.toString());
      value.put("subjectToken", subjectToken);
      value.put("validityBasisToken", validityBasisToken);
      value.put("materialId", materialId);
      value.put("materialSha256", materialSha256);
      return value;
    }
  }
}
