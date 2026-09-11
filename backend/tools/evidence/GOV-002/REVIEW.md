# GOV-002 review and evidence

Issue: GOV-002 / QA. Local independent worktree: `C:/Users/Administrator/.codex/worktrees/6571/宠物平台V1.0`; branch `codex/gov-002`.

Input: GOV-001 import `e8654c30b4dfe2cfc9e3c050110322d2a18bb6a1`, unlocked by approved Wave 1 EX-W1-001 (latest root dispatch log read-only). The root Work ledger was not edited.

Adopted PLAT-001 implementation `6cfefa8799a6c1bcba8c48a35940215e1a6c4f7d` through a local cherry-pick `09e9453` before verification. Only that dependency owns the root/backend boot POM changes; GOV-002 did not edit them. GOV-002's own commit changes only `.github`, `backend/pet-architecture-test`, and `backend/tools`. No Contract/Schema/Event or product change; no CCR required.

## Actual validation

- Java: Eclipse Temurin 21.0.11; Maven 3.9.12; Python 3. Runtime selection was process-local, not a system setting change.
- `mvn -B -f backend/pom.xml clean verify`: full 41-project reactor, 22 JUnit executions including 15 synthetic Java fixture cases and 7 production/source gates. See `maven-verify.txt` for the actual final outcome.
- Production scan: 39 modules, 294 imported classes including package-info; **50 non-package-info classes**, zero real controllers and zero real domain classes. Markers are actual compiled module classes, not fixture stand-ins. These counts do not claim future business coverage.
- Python gate suite: 13 tests, with subcases for forbidden dependency scopes/profiles and status derivation patterns. Source fixtures also inject violations into a disposable copy of actual backend/frontend paths; no real business source or other worktree is mutated.
- No-source/missing-module/missing-app-script and unavailable capability checks are negative cases; they must fail. Manual unavailable runtime gates are not green placeholders.
- First local run detected an exact domain-package matching omission in the new rule; `maven-first-attempt.txt` records its fixture failure. The rule was corrected to cover the root package and children; no test was skipped to pass.
- Baseline PR #1 run [34558509473](https://github.com/blueXYing/pet-platform/actions/runs/34558509473), backend job 103136206100: actual connected GitHub logs show missing internal dependency versions and ProjectBuildingException before compile/tests. `baseline-ci-failure.txt` preserves the error-only extract. The adopted PLAT fix resolves this model error locally; remote GOV-002 CI must be checked independently.
- PLAT-001 independently ran the real Maven Enforcer refund-biz→order-biz mutation at its fixed evidence commit `f33d7c241887ae37a84ab6cbe6101df20c2249ee` (`backend/evidence/PLAT-001/arch-001-negative.txt`). This is explicitly attributed dependency evidence, not a GOV-002 execution claim.

## Delivery / risks

Remote delivery overlays only Issue changes and the disclosed PLAT POM dependency onto GOV-001 remote base `5fb3950d3ac214dab2db5cc90deaf35a09ca7967`, tree `62a18b43ef818fe9c353b6977ef63a65e0b08e55`; it must not delete GOV-001 governance additions. The final handoff supplies actual local/remote commit and draft PR IDs after creation. Merge requires blueXYing human review; both technical and PR reviewer roles remain blueXYing.

Frontend manifests are not in this Issue's local input: both runtime/visual paths remain NOT_EXECUTED. Confirmed C/A script names and versions are wired without modifying their source trees; their separate tests are not this Issue's results. Lint is not configured by those owners. Source/bytecode gates have the documented static-analysis limits in `backend/tools/README.md`. There is no real API, state-machine implementation, database/provider integration, payment, RBAC, device or visual acceptance here.

Rollback: revert the GOV-002 commit to remove its gate changes. PLAT's POM fix is a separately owned dependency; reverting it restores the original model failure. No runtime data migration or concurrency behavior changes. QA-001 is not started by this delivery and requires root Work's sequential ownership handoff.
