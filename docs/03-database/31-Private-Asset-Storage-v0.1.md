# Private Asset Storage v0.1

`private_asset` owns the current immutable private-object fact. Upload first commits the asset,
the `(owner_user_id, request_id)` binding and a SQL13 `async_task`, then writes the deterministic
source key. A retry with the same canonical request hash reuses the asset and object keys; a changed
hash is an idempotency conflict. The task can recover a successful PUT whose acknowledgement was
lost by HEAD/GET of that key. Missing source bytes remain retryable and never allocate a new asset.
The shared leased `AsyncTaskWorker` runs the module's handler; there is no module-local queue.

Both source and normalized keys are under `merchant-materials/{owner-partition}/{asset-id}/`.
They contain no file name or identity value. `READY` requires the immutable final version, hashes,
decoded JPEG/PNG type and a size of 1..10 MiB. Scanner or decoder absence fails closed. There is no
automatic deletion until retention is approved.

`private_asset_read_grant` stores only a SHA-256 token digest and a keyed proof. The opaque token is
deterministically derived with a separate versioned external HMAC key, which permits an idempotent
issue replay without storing plaintext. A five-minute grant binds operator, session digest and
generation, application/revision/material, asset/hash, purpose, authorization version and scope
version. Consumption first locks, verifies and changes the grant to `CONSUMED` with a `STARTED`
audit in a short transaction. A second same-DataSource transaction invokes the current authorization
callback while holding the asset row through pinned read and watermark rendering. It appends SUCCESS
on commit; denial, rollback or provider failure is appended afterward as DENIED/FAILED while the
grant remains consumed. Raw object keys and URLs are never returned.

`private_asset_read_audit` is append-only issue/consume evidence. Consumption marks the grant before
rendering; success, denied, gone and failed outcomes commit with that mark. The service throws any
failure only after this transaction commits, so a storage/render failure cannot revive a token.
Reasons are purpose-protected before persistence; plaintext reasons and token values are excluded
from SQL, logs and errors.
