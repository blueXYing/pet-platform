# S8 private-asset core

Implementation owner: `pet-thirdparty-api`, `pet-thirdparty-biz`, SQL/Storage 31. The boot HTTP,
merchant authorization adapter and image/scanner/watermark providers are composed by the root task.

## Contracts and boundaries

- `PrivateAssetApi` is the only domain-facing owner API. IDs are decimal strings and it never
  exposes an object key, public URL or raw unwatermarked download.
- Upload accepts a bounded `InputStream`, declared JPEG/PNG metadata and `CommandContext`. The owner
  must be the current USER operator. No original filename enters the contract.
- `PrivateAssetReadAuthorizer` is a callback implemented at the composition root. Issue calls it in
  the grant transaction. Consume first commits single-use admission, then calls it in the protected
  read transaction. It returns current owner/material/hash, submitted revision, authz version and
  scope version; the core compares all persisted facts.
- Storage, malware scan, complete image decode/normalization, dynamic watermark, grant key and reason
  protection are mandatory constructor dependencies. Missing or unavailable providers fail closed.

## Durable upload

The first request commits `private_asset`, its owner/request binding and a SQL13 `async_task` before
the first object PUT. Asset and source/final keys are deterministic from that persisted ID. The
source stream is consumed with a hard 10 MiB bound and SHA-256 is part of the canonical request hash.
Same-request replay therefore reuses the same ID and key; changed bytes or metadata conflict.

`JdbcAsyncTaskSubmitter` joins that transaction on the same DataSource. The shared leased
`AsyncTaskWorker` runs `PrivateAssetReconcileTaskHandler`; no module-local queue exists.

`S3PrivateObjectStore` validates that bucket ACL has no public/authenticated-user grants, uses
conditional immutable PUT, pins reads by version ID or ETag, and verifies SHA metadata. A lost PUT
acknowledgement converges through HEAD. The durable worker recovers expired claims, scans source and
normalized bytes, fully decodes and strips image metadata, enforces the final 10 MiB limit, and then
atomically records READY. Invalid images become REJECTED; malicious images become QUARANTINED;
provider/storage failures remain retryable. No retention deletion is scheduled.

With bucket versioning enabled, `version:{id}` pins the exact immutable version. With versioning
disabled, `etag:{etag}` plus conditional GET is a fail-closed integrity reference: an out-of-band
overwrite makes reads fail instead of serving changed bytes, but durable preservation still depends
on deployment policy granting this service exclusive object writes and denying ACL writes. The
adapter validates bucket ACL at startup; RAM-policy minimality and later out-of-band ACL changes are
deployment controls and are not proven by the four adapter unit tests.

## One-time read grant

Issue authenticates/authorizes before reading an idempotency binding. A separate versioned HMAC key
derives a deterministic opaque token from grant ID, operator, session digest and request ID. SQL
stores SHA-256(token), a keyed proof and key version, never plaintext. Same-request replay can
rederive the original token while it remains ISSUED. Key gaps/version mismatches fail closed.

Consume locks the digest row, verifies token proof, operator, SHA-256 session binding, generation and
database-clock five-minute expiry, then commits `CONSUMED` with a STARTED audit. A second transaction
locks the asset, invokes current authorization, compares owner/material/revision, object hash and
authorization/scope versions, reads the pinned object, renders the watermark and records SUCCESS.
Denial or provider/transaction failure is appended as DENIED/FAILED after rollback; it never revives
the token. A consumed or expired token returns the stable gone contract. A current authorization
revocation returns forbidden and records DENIED. Terminal HTTP adds `no-store`, `private`, `nosniff`
and pragma headers.

## Validation

Six `PrivateAssetCoreMySqlTest` cases passed against real MySQL 8 / SQL13 / SQL31. They cover lost
PUT acknowledgement recovery through the real shared `AsyncTaskWorker.runOne`, stable asset/task/key
and stored source version; grant replay token equality and same-key/different-parameter conflict;
database expiry response; concurrent single consumption; storage failure after admission remaining
CONSUMED with FAILED audit; a participating same-DataSource authorization rollback remaining
CONSUMED with DENIED audit; ambient transaction rejection; undecodable-image REJECTED state; owner
query all-or-nothing rejection for cross-owner, unknown and duplicate IDs; final-object rather than
source hash projection; and same-upload-request changed-byte conflict preserving the original asset.

The core MySQL tests use bounded fake object/scanner/normalizer/watermark ports. Separately, four
`S3PrivateObjectStoreTest` cases passed for the AWS/OSS request adapter and seven image/scanner tests
passed. These are mocked/provider-level tests, not a live OSS mutation test. Live ClamAV behavior,
RAM-policy minimality, real bucket versioning, process-crash timing between each read phase, and
long-running provider timeout behavior remain integration/deployment verification.
