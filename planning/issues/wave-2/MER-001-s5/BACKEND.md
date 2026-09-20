# MER-001 application runtime backend handoff

## Delivered

- Added the merchant application command/query contracts and a transactional MyBatis runtime for create, save, submit, claim, release, manual verification, review decision, owner query, and operator query.
- Every write uses durable `requestId` admission and result binding. Admission and execution commit-unknown cases recover a stable result or fail closed without losing the binding.
- Drafts are immutable revisions. Submit validates the complete submission and current private asset facts, fixes the credential HMAC policy version, and creates the review task and audit facts atomically.
- Review commands lock current application/task facts, perform final server-side authorization, enforce the current claimant, and preserve submitted revision pointers. Authorization scope denials map to not-found; action denials to forbidden; invalid sessions to unauthorized; malformed or unavailable authorization facts fail as a dependency error.
- Manual verification stores protected evidence and subject claims. Approval revalidates the submitted revision, private asset owner/hash/status, city/map facts, evidence/claim chain, policy version, validity dates, and subject name. Approval atomically creates the ACTIVE merchant/store/profile, final decision/audit facts, and the transactional outbox event. It does not create an agreement acceptance or mark an agreement signed.
- Published `MerchantApplicationReviewedEvent.v1` with aggregate type `MERCHANT_APPLICATION` and the approved ten-field payload. The event uses the shared `IntegrationEventPublisher`; merchant code does not write platform outbox tables directly.
- Added a strict persistent `ApplicationReviewFactsReader` for agreement admission. It requires the complete approved decision/audit/task/revision/evidence/claim/merchant/profile/store provenance chain. Merchant/store operating status is intentionally separate from the immutable historical approval fact.
- Protected contact values, credentials, identifiers, notes, and review reasons are not stored in receipt canonical parameters as plaintext. Nested receipt parameters are canonicalized deterministically.
- Added real MySQL workflow, safety, and idempotency tests using the approved SQL schema. The tests cover approval and agreement admission through the persistent facts reader, correction/resubmission, revoked authorization rollback, durable replay/conflict, admission/execution acknowledgement loss, competing/current claimants, duplicate subject-claim rollback, known credential expiry after time advance, and HMAC-policy drift.

## Required runtime providers

Production wiring must supply all of these ports before enabling `pet.merchant.application.enabled`:

- private asset ownership, digest, media type, size, and status reader;
- open-city reader and map validation provider;
- protected-value encryption, reveal, and equality-token provider;
- credential normalization/protection/comparison provider with an available fixed HMAC policy version;
- current admin authorization provider for collection entry and per-application final checks;
- transactional integration event publisher.

Unavailable defaults fail closed. There are no production success fixtures. An unavailable OCR provider leaves a valid submission in `REVIEWING` with `SUBJECT_VERIFICATION_PENDING`; verified evidence can only be established through the manual verification command in this delivery.

## Integration notes and remaining gates

- HTTP mapping remains explicitly gated by boot configuration. Owner and operator wire models must be mapped to the approved OpenAPI shapes; the internal records are not asserted to be wire-identical.
- Operator list access is checked once at collection level and again for every row. Rows are scanned in bounded batches so page size does not cause an unbounded materialization. Exact filtered totals still require O(N) per-row authorization calls. Before high-volume rollout, add an authoritative admin-scope SQL predicate or bulk authorization contract while preserving exact totals and row checks.
- No production schema migration was added. The runtime targets the approved MER-001 SQL contract; deployment must apply that schema through the infrastructure-owned migration path.
- Automated OCR, private upload/read-grant issuance, and concrete city/map/key-directory providers are external dependencies and remain unavailable until their owning modules supply real adapters.

## Verification

Targeted command (Java 21, MySQL `127.0.0.1:33452`):

```powershell
D:\apache-maven-3.9.12\bin\mvn.cmd -pl pet-merchant-biz -am `
  -Dtest=MerchantApplicationRuntimeMySqlTest,MerchantApplicationIdempotencyMySqlTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The final combined real-MySQL run passed 18/18: three runtime workflows, three acknowledgement/idempotency cases, five merchant safety cases, and seven admin collection-authorization cases. Maven reported `BUILD SUCCESS`; the integrating task retained the output at `D:/Temp/mer001-s5-combined.txt`.
