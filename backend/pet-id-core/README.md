# Hutool Snowflake component

This opt-in component implements the accepted PLAT-002 S2 adapter over SQL25. It is not a
Spring bean, boot migration, production host adapter, or general business idempotency service.
The original `pet-common` interfaces remain unchanged. See [HANDOFF](HANDOFF.md) for evidence.

## Explicit dependencies

Construct `JdbcSnowflakeNodeStore` with a dedicated infrastructure `DataSource`, then construct
`HutoolSnowflakeIdProvider(store, new SnowflakeProviderSettings(nodeId), verifier)`.
The verifier must be a trusted implementation of `PreviousJvmExitVerifier`, binding termination
to the exact previous incarnation/node/fence and host/process identity including process start.
Audited first initialization needs its own evidence. The overload without a verifier uses a
rejecting verifier, including for apparently virgin rows. No permissive production fallback exists.

SQL25 deliberately contains no initialized/enabled rows. The component neither creates the
table nor inserts/enables node slots. Production schema installation, initialization evidence,
node assignment, exit verification and durable database lineage are deployment gates.

The store uses short REQUIRES_NEW, READ_COMMITTED transactions and explicit UTC sessions.
`DataSourceTransactionManager` and `JdbcTemplate` use the same DataSource: the UTC SET,
row read, fresh time sample and update reuse the transaction-bound connection. A pool may
choose a different physical connection for the next transaction; that transaction sets UTC again.
Do not move SET outside the transaction or rely on a connection having been initialized by a
previous borrower. `PooledSnowflakeMySqlTest` exercises this with multiple real Hikari connections,
non-UTC sessions and different JVM/driver time zones, without forcing UTC through the JDBC URL.
It reads a prior-owner snapshot, performs host verification without a row lock, then locks and
rechecks that snapshot before obtaining database time in a new statement. H/fence/grant/lease
updates are atomic and checked against the prior values. CHECK constraints validate a row;
they do not prevent an administrator rolling back history. Never remove/decrease H or promote
a database missing acknowledged grants. Host verification, database calls and SDK calls run
only in the isolated single-flight action, not the caller/deadline controller.

## State and budget

Hutool core 5.8.47 exclusively owns generation. The private instance uses the original 2026
epoch, node low/high five bits, useSystemClock=false, timeOffset=0 and randomSequenceLimit=0.
No SDK instance or Future escapes to a business API; no custom sequence or bit-packing generator
is implemented. Read-only node/epoch checks are not another generation algorithm.

`SingleFlightLane` manually owns one SDK/DB worker and one deadline controller; the provider
owns one permission maintenance controller. These are daemon threads, with no worker replacement
and no SDK work queue. The single admitted operation is also the publication slot. An immutable
CAS cell commits model (including grant and lastPublished) and the single result together.
Callers contend within the same 1-second entry budget. Not admitted means no SDK task is queued.
An admitted timeout, action error, close, or unsafe permission is terminal. Late actions and
commit ACKs cannot publish or revive the model. An already won success remains the sole result
if close occurs before the caller resumes. Timer/control paths do no blocking I/O and take no
SDK, monitor, executor-cancellation or shutdown lock.

A confirmed future grant is a distinct WARMING case: no SDK call is made, the same grant is
retained, and this request reports not ready. Retries do not burn successive future windows.
Candidate IDs must match node/positive Long/strictly increasing published values, the confirmed
time range, and actual OS UTC before/after samples. Business Clock fixtures do not control
Hutool's private System.currentTimeMillis source. SDK errors or observed rollback fail closed.

One second is the caller/publication budget subject to the accepted scheduling-pause boundary,
not proof of stopping a blocked SDK or JDBC call. `close()` marks terminal and wakes controllers;
it does not interrupt/kill the SDK. A blocked daemon worker may remain until the host actually
terminates the JVM. An asynchronous diagnostic logs only the controlled failure category;
production alert routing is not implemented here. `isClosed`, `failureReason`, `currentGrant`,
`lastPublishedId` and `incarnation` are infrastructure diagnostics, not authentication/HTTP DTOs.
After terminal failure, use a new JVM and new grant only after trusted exit verification; never
reset/deserialize a generator in the same process. Lifecycle/VM memory cloning is unsupported.

The Spring transaction timeout and the single-flight publication deadline are separate protections.
`OPERATION_TIMEOUT` identifies the latter; it alone does not prove a Spring transaction timeout,
row lock, pool starvation or Docker pause. A test holds the acknowledgement after a real renewal
commit and verifies terminal closure and rejection of its late grant. This is controlled fault
injection, not reproduction of the spontaneous stall reported in PR36. See the
[2026-09-16 investigation](../../planning/progress/2026-09-16/ID_INTEGRATION_CLOSEOUT.md).

## Component tests

Use Java 21 and the existing isolated MySQL 8.4 service. The QA fixture accepts a loopback server
URL without an existing database name. Set `PLAT002_ID_MYSQL_URL`, `PLAT002_ID_MYSQL_USER`,
`PLAT002_ID_MYSQL_PASSWORD`. It creates a unique `plat002_id_test_*` database and may drop only
that database after its CREATE succeeded. SQL25 is read from `docs/03-database`; it is not copied
to boot's default Flyway location. For a full local reactor, point PLAT004_MYSQL_* at the same
isolated server too; its fixture uses a separate unique database prefix.

Run `mvn -B -f backend/pom.xml clean verify`, then the existing e2e Contract checks. Missing MySQL
must fail rather than skip. Process probes, blocking candidates, reflection injections and
exit-evidence fixtures belong only in src/test. State injection is not an OS clock change;
blocking test actions are not proof of real Hutool frozen-clock behavior. No production database,
OS clock, service or host-exit integration is configured by these tests.
