# Durable task consumer component

This module implements the PLAT-004 Worker/Lease component stage over the unmodified SQL13
`async_task` and `async_task_attempt` tables. Read [HANDOFF](HANDOFF.md) for acceptance boundaries.
It is deliberately not registered in Spring Boot: there is no production Snowflake provider,
business handler, producer API, reconciliation/alert adapter or default fake bean here.

## Wiring requirements

Construct `JdbcAsyncTaskRepository` with a dedicated infrastructure `DataSource` and the
PLAT-002 `SnowflakeIdGenerator`. Connections must reach the same authoritative MySQL primary.
The repository configures its sessions to UTC for DATETIME(3), uses short REQUIRES_NEW,
READ_COMMITTED transactions, and requires MySQL 8 SKIP LOCKED. The DataSource owns connection
pool/acquisition settings; the repository transaction budget is 10 seconds. Do not pass a
business connection with an active transaction to `runOne`/`pollBatch`: they reject ambient
transactions. Production ID ownership/fencing remains the provider's responsibility.

Construct `AsyncTaskWorker` with an explicit instance owner, injected `java.time.Clock`,
`TaskWorkerSettings`, configured `TaskRetryDelays`, and type-unique `TaskRegistration` entries.
Each entry adapts the existing `TaskHandler<T>` using a payload decoder and deterministic
requestId resolver based on the task's business generation. It must not derive requestId from
attemptNo, lease version or worker identity. There is no guessed payload/generation convention.
`expectedVersion` is passed through for the owning handler, not interpreted as generation.

`start()` enables scheduled polling; `runOne()`/`pollBatch()` support an external wakeup.
Batch size is a per-poll maximum: tasks are individually claimed just before execution, not
leased in bulk while waiting in an in-memory queue. Empty SKIP LOCKED results can be transient.
Default technical settings are 1s poll, 100 batch, 60s lease, 20s heartbeat. Retry schedules
are explicit configuration; Scheduler §10 provides the recommended delays, and channel
limits must be supplied by the channel owner. Handler `Retry.nextDelay` is authoritative for
that result. Exhausting `max_retry_count` sets DEAD; it does not implement the required
reconciliation/alert production closure.

## Failure and ownership semantics

- Claim updates task lease/version and inserts the next attempt in one transaction. Under
  the task row lock, max(attempt_no)+1 is independent of retry_count. Expired unfinished
  attempts close as RETRY / LEASE_EXPIRED; recovery itself does not count as a Handler retry.
- Success/Cancelled/Retry/Dead update both the fenced task and its exact attempt atomically.
  Missing attempt rolls back the task result. Task cancellation maps to attempt NOOP;
  Success("NOOP") retains SUCCEEDED with attempt NOOP. Other success codes map to SUCCESS.
- Heartbeat and completion check RUNNING, owner, claim version and nonexpired DB lease.
  They obtain the row lock before the statement evaluating NOW(3), so lock wait time cannot
  preserve an expired lease via MySQL's statement-start timestamp. Heartbeat retains the
  claim version; only a new claim increments the fencing token.
- The injected Clock supplies Handler context time only. DB NOW(3) decides claim, lease,
  audit timestamps and retry deadlines. Durations are positive whole milliseconds, bounded
  to 365 days; persisted timestamps remain milliseconds.
- Handler RuntimeException maps to a configured retry using technical code HANDLER_EXCEPTION,
  without persisting raw exception messages/payloads. Channel UNKNOWN must be handled by the
  business Handler's existing query/reconciliation path, never inferred as business FAILED.
- Error, configuration gaps, DB failures or shutdown leave durable ownership for expiry;
  no premature lease release. Nonfatal Error does not stop all scheduled polls. VM fatal errors
  close the worker and propagate. Missing handler/policy is a configuration failure, not DEAD.
- Heartbeat failure makes the local invocation unable to complete, even if a later operation
  might succeed. close cancels heartbeats and new dispatch; it does not interrupt business
  effects. If dispatch permission was granted before close, the handler may still execute.
  At-least-once side effects therefore require business idempotency/CAS/unique constraints.

## Real database verification

Use Java 21 and a **dedicated, disposable local MySQL 8 instance**. No database may be selected
in `PLAT004_MYSQL_URL`; the fixture creates a new random database, executes the actual SQL13
from the repository, and drops only a database it successfully created. It never uses H2,
Testcontainers skip flags or disabled tests. The isolated test account needs create/drop
database and `performance_schema.data_lock_waits`/`data_locks` read access for lock-wait proof.

Environment variables:

- `PLAT004_MYSQL_URL`: bare local server URL, defaults to `jdbc:mysql://127.0.0.1:33440/`.
- `PLAT004_MYSQL_USER` / `PLAT004_MYSQL_PASSWORD`: isolated test credentials supplied outside
  command arguments. Do not point these at existing business/production services.

Run from the repository root:

```text
mvn -B -f backend/pom.xml clean verify
python backend/tools/check-module-deps.py
python -m unittest discover -s backend/tools -p "test_*.py" -v
python backend/tools/check-display-status.py
python e2e/contract_smoke.py
python -m unittest discover -s e2e -p "test_*.py" -v
```

CI configures its own MySQL 8.4 service and runs the same mandatory Maven tests. A missing
database fails the build. The 22 component tests include actual separate JVM halt/restart,
24 competing workers, row-lock wait across lease expiry, atomic rollback, clock skew,
long-handler heartbeat, closing during preparation and execution, and retry recovery.
The test ID sequences are only in src/test and absent from the production jar.
