# MER-001 s5 — AUTH handoff

## Delivered boundary

Owned modules only: `pet-admin-api` and `pet-admin-biz`.

- Added `AdminAuthorizationQueryApi.check(AdminActionCheckQuery)` with the §5.2 fields.
- Added generic admin-owned `AdminResourceScope`; it has no merchant-api dependency.
- Added `EXECUTE` and `READ_RESULT` final-check phases.
- Added explicit `AdminCollectionActionCheckQuery` and source-compatible
  `AdminAuthorizationQueryApi.checkCollection(...)` for the list entry gate. It supports only
  `merchant.application.read` with `READ_RESULT`; it never fabricates a resource or authorizes a
  row.
- Added a production `AdminAuthorizationService` that reads the current MySQL session, account,
  global authz revision, action grants and account scope.
- Registered the implemented MER enforcement actions:
  `merchant.application.read`, `merchant.application.decide`, and
  `merchant.identity.reveal`.
- Added real-MySQL integration coverage for session revoke/expiry/generation change, action
  removal, city scope change, merchant scope denial and operator mismatch.

## Caller contract

After locking its idempotency binding, application and review task, MER constructs:

```java
new AdminActionCheckQuery(
    principal.sessionId(),
    principal.sessionGeneration(),
    principal.operatorId(),
    "merchant.application.decide",
    new AdminResourceScope(
        "MERCHANT_APPLICATION",
        applicationId,
        reservedMerchantId,
        submittedRevisionCityCode,
        lockedScopeVersion),
    "MERCHANT_APPLICATION_REVIEW",
    CheckPhase.EXECUTE);
```

The resource identifiers and city must come from the locked server-side application/submitted
revision. Request filters or body values are not authorization facts. Successful receipt replay
and private result reads use `READ_RESULT` and perform the same current action/scope checks.

Before a review list scans or counts rows, including an empty database, MER calls:

```java
authorization.checkCollection(new AdminCollectionActionCheckQuery(
    principal.sessionId(),
    principal.sessionGeneration(),
    principal.operatorId(),
    "merchant.application.read",
    "MERCHANT_APPLICATION_LIST",
    CheckPhase.READ_RESULT));
```

This checks current session/account/generation/action and validates that the persisted scope is
structurally readable. It does not test a city or merchant. CITY and MERCHANT operators therefore
pass the entry gate, while every returned row still requires its server-bound resource check. A
valid NONE scope also passes the entry gate and yields zero rows because all row checks deny.

An ordinary authorization rejection is returned as `allowed=false`. Missing/inconsistent AUTH
state or database failure throws `COMMON_DEPENDENCY_UNAVAILABLE`. MER should persist the returned
`authzVersion` on an accepted decision and map denied resource scope according to its HTTP
existence-hiding policy.

## Transaction semantics

For `EXECUTE`, `AdminAuthStore.currentAuthorization` uses `PROPAGATION_REQUIRED` and locking reads.
With the same DataSource it joins the MER write transaction and sees committed current rows even
when the surrounding transaction is repeatable-read; authorization locks remain through MER
commit. `READ_RESULT` uses an isolated read-committed read/write transaction because receipt reads
may originate in a read-only transaction and MySQL cannot perform the required locking reads
there. Supported RBAC mutations must continue to serialize through `admin_authz_revision` before
changing grants or scope.

## Exact remaining gaps

- Boot wiring is intentionally left to the integration owner; construct
  `AdminAuthorizationService` with the shared application `DataSource` and expose it as
  `AdminAuthorizationQueryApi`.
- The contract defines `reasonCode` but does not freeze its enum. Current implementation emits
  `ALLOWED`, `SESSION_NOT_FOUND`, `SESSION_REVOKED`, `SESSION_CLOSED`, `SESSION_EXPIRED`,
  `SESSION_GENERATION_CHANGED`, `OPERATOR_MISMATCH`, `ACTION_NOT_GRANTED`, and
  `RESOURCE_SCOPE_DENIED`. Callers should authorize only from `allowed`, not infer permission from
  reason text, until the Contract Owner freezes public reason values.
- AUTH currently has no production RBAC mutation command API. The strong-read protocol assumes a
  future mutation path locks and bumps `admin_authz_revision`; the integration tests mutate the
  approved Schema 26 tables directly and bump the revision explicitly.
- AUTH cannot independently verify an application's `scopeVersion`; it deliberately treats the
  whole `AdminResourceScope` as a server-bound caller fact. MER remains responsible for creating it
  from rows locked in the same transaction.
- Schema 26 currently constrains persisted `admin_account_scope.mode` to ALL/CITY/MERCHANT even
  though the public `AdminDataScope` also defines NONE. The collection implementation handles a
  valid future/approved NONE row without granting any resource, but persisting NONE requires a
  separately approved Schema 26 synchronization.
- No MFA or second-person approval was added, per the approved V1 decisions.

## Validation

Targeted MySQL test class:

```text
com.petplatform.admin.biz.auth.AdminAuthorizationCurrentReadTest
```

The targeted admin suite passed against isolated MySQL 8 and volatile Redis on 2026-09-17:
39 tests, 0 failures, 0 errors. This includes five
`AdminAuthorizationCurrentReadTest` cases and concurrent committed action/scope removal after an
outer repeatable-read snapshot was established. Log: `D:/Temp/auth-targeted-tests.txt`.
