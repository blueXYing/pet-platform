# K1 / K2 implementation plan

**Goal:** Implement the approved OWNER-only internal verification completion, with atomic ORDER completion and current unfulfilled AFTERSALE invalidation.
**Architecture:** One writable READ_COMMITTED transaction and shared store guard; module-owned MyBatis SQL; public APIs and reciprocal durable commit proofs. Independent encrypted five-part command admission persists across rollback. ORDER publishes OrderVerifiedEvent.v2 once.
**Tech stack:** Java 21, Spring transactions, MyBatis XML, MySQL 8.4, JUnit.
**Spec:** planning/ccr/CCR-W2-API-001/verification-completion-proposal.md, approved by the user's latest “批准”.
**Global constraints:** Default off, no HTTP, no staff grants, no generic refund creation, no production migration/enablement; new PR for review only. PR94 merge was separately approved and completed at eb083cb.

## Steps

- [x] K1: Add a configuration test proving real OWNER authority composes without a QA authority. Observe failure. Wire existing current-session plus MER requireOwner. Reject staff context and revoked identities. Verify targeted configuration tests.
- [x] Contract48: Record approval; add identity/command/consumption columns and ORDER/AFS proof storage. Preserve v1; document v2 and internal commands. Migration fails closed on unmapped historical identities.
- [x] K2 RED: Add real-MySQL completion acceptance tests for success, duplicate/replay, risk, aftersale and rollback; observe missing completion behavior.
- [x] K2 GREEN: Add VerificationCompletionApi.verify(Command), internal shared credential validation, OrderVerificationCommitApi acquire/markVerified/proof and AfterSaleVerificationApi invalidateCurrent/proof. Tokens bind current transaction/DataSource, order/store/actor/command/version. ORDER/AFS/VER each read only their own persistence.
- [ ] Verify: real current-session OWNER test, concurrency, lost commit acknowledgement, failed durable writes, reschedule/confirmation/pickup, refund presence and existing refund regression. Run architecture and persistence checks; full project CI on the final code head.
- [ ] Review: one fresh-context whole-branch review, fix substantive findings with regression tests. Update evidence and WORK_STATE, commit, push feature branch, create/attach PR against develop.

## Review focus

Recheck proof forgery and reuse across transaction/instance, identity and replay authority, current-aftersale mismatch/missing rows/history, credential/version/risk integrity, every transaction side effect and rollback, five-part idempotency versus global requestId, default-off composition, confidential code in logs/receipts/events, and unimplemented generic refund/HTTP boundaries. Do not infer full aftersale end-to-end or QA-004 coverage from fixture rows.

## Execution ledger

- M94: merged PR94 at eb083cbbb1ae6195db97e438c182c4ed817594e2; rebased preparation commit onto develop.
- Ruling: proceed under the user's explicit approval of the proposal and its implementation/test sequence; no duplicate approval gate. Cost if misunderstood: a review-only feature branch; no next PR merge or production action.
- Ruling: retain this compact native ledger in the plan rather than introducing a second workspace helper on Windows; git history preserves decisions. Cost: no helper-generated per-task summaries.
- K1: missing real OWNER adapter observed RED, 6/6 GREEN after wiring; expanded config 8/8 GREEN.
- K2: missing completion observed assertion RED; first real MySQL tests 8/8 GREEN; expanded 15/15 GREEN, then 3/3 additional migration/concurrency/transaction boundaries GREEN. Separate runs cover 18 distinct methods.
- Source gates 18/18, contract regressions 118/118; no new HTTP or production actions. Full CI and final branch review remain pending.
- ArchUnit 22/22 GREEN. Malformed store ID test RED (DEPENDENCY instead of INVALID_ARGUMENT), validation fix GREEN; completion coverage now 19 distinct methods. Existing lifecycle/refund regressions will run in complete CI.
- Final review: fresh-context read-only reviewer inspected eb083cb..029f787, verified 11 raw-log hashes, Critical 0 / Important 0 / behavioral Minor 0. Reviewer boundaries and executor rulings are recorded exhaustively in planning/progress/2026-09-30/verification-completion/REVIEW.md.
- PR95 created and attached; final documentation/LF-only commit does not change production behavior. Final-head CI and actual downloaded report totals will be recorded in the PR description, avoiding a self-referential evidence commit loop. No new PR merge, production DDL or enablement.
