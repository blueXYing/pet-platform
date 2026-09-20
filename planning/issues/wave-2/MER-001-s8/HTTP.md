# MER-001 S8 private-asset HTTP verification

## Implemented boundary

- `POST /api/v1/c/private-assets` resolves the current MINIAPP session, rejects caller-supplied
  ownership, accepts only the approved purpose, and passes a bounded stream to the real
  `PrivateAssetApiImpl`.
- The servlet retains at most one 10 MiB file in memory (`file-size-threshold` and `max-file-size`
  are both 10 MiB); the complete multipart request is capped at 11 MiB. Evidence is not written to
  the ordinary servlet temporary directory.
- Missing/generic MIME is inferred from the first eight server-read bytes. Explicit JPEG/PNG MIME
  must match magic bytes. The core still performs complete bounded decode, metadata removal,
  normalization, and scanning.
- A durably bound upload that reaches `REJECTED` or `QUARANTINED` maps to
  `422 PRIVATE_ASSET_REJECTED`; pre-binding syntax/media/size failures retain their separate
  400/413/415 semantics.
- Admin grant issue requires the current ADMIN_WEB session plus both
  `merchant.application.decide` and `merchant.identity.reveal`. The private-core callback locks the
  current submitted MER material/task and joins the same datasource transaction for current Admin
  session/action/scope reads.
- Grant consumption returns only watermarked PNG bytes. Token paths, Authorization, private object
  keys, and plaintext reasons are not logged by application code; Tomcat access logging is
  explicitly disabled.

## Real fixture coverage

`PrivateAssetUploadHttpMySqlTest` uses loopback Tomcat, real C authentication/session resolution,
`PrivateAssetApiImpl`, MySQL SQL13/SQL31, and the shared durable worker registration. Only the
external object store and malware scanner are controlled substitutes. It uploads a generated PNG,
checks READY and the strict five-field receipt, replays the same requestId as HTTP 200, verifies one
durable binding/task, resolves the persisted immutable fact for the owner, and proves another owner
cannot resolve the asset.

`PrivateAssetAuthorizationMySqlTest` uses real SQL26/SQL29 authorization and MER state. It covers
cross-city denial, revoked session, session-generation change, task release, submitted-revision
change, and concurrent attempts to mutate Admin authorization or the MER claimant while the private
transaction holds its locks.

The core MySQL suite owns grant issuance/consumption persistence tests, including concurrent
single-consumption and the rule that object/watermark failure after admission never revives a token.

## External acceptance still required

The real fixture does not claim production OSS or ClamAV reachability. Deployment must separately
verify the private bucket ACL/version behavior, ClamAV endpoint/database freshness, reverse-proxy
token-path redaction, and device-specific WeChat multipart behavior.
