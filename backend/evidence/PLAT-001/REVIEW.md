# PLAT-001 review evidence

Status: implementation committed and local ARCH-001–003 integration verification PASS. Draft PR #2 is available for review. Remote CI and human review remain separate gates; no merge or Issue DONE is claimed.

## Scope and provenance

- Input: `e8654c30b4dfe2cfc9e3c050110322d2a18bb6a1`, unlocked by approved Wave 1 exception EX-W1-001.
- Implementation: `6cfefa8799a6c1bcba8c48a35940215e1a6c4f7d`.
- Branch: `codex/plat-001-backend-core`.
- Worktree: `C:/Users/Administrator/.codex/worktrees/285a/宠物平台V1.0`.
- Changed modules: root parent POM and pet-boot POM only. QA-owned architecture tests/tools, root Work logs, product rules and protected contracts were not edited.

## Changes

The parent now manages all 40 internal module versions with `${project.version}`. Previously Maven rejected versionless internal dependencies before compiling the reactor. Dependency management does not add dependency edges; the existing domain module dependency matrix remains unchanged.

pet-boot now attaches its Spring Boot executable archive with classifier `exec`, preserving the ordinary main JAR for downstream dependencies. Previously repackage replaced the main artifact, and the architecture test could not import boot Web package classes from its ordinary classpath. The distributable executable is now `pet-boot-0.1.0-SNAPSHOT-exec.jar`; the unclassified JAR is the library artifact.

## Reproduction and results

Use task-process `JAVA_HOME=D:/soft/Android Studio/jbr` and prepend its `bin` to this process's PATH. No machine/user environment was changed.

| Check | Result | Evidence |
|---|---|---|
| Runtime | OpenJDK 21.0.5, Maven 3.9.12 | environment.txt |
| `mvn -B -ntp -f backend/pom.xml clean verify` | PASS: all 41 reactor projects | maven-verify.txt |
| Baseline ArchUnit | 3 tests, 0 failures/errors/skips | architecture-baseline.txt |
| ARCH-001 static dependency check | PASS | arch-001-static.txt |
| ARCH-001 Enforcer mutation | PASS: deliberately adding refund-biz → order-biz causes exit 1 at ban-cross-biz-dependencies; original POM restored byte-for-byte | arch-001-negative.txt |
| Ordinary / executable boot archive shape | PASS: regular class entries / BOOT-INF main class respectively | boot-library-jar.txt, boot-executable-jar.txt |
| ARCH-002 cross-module persistence | PASS after adopting fixed GOV-002 rules, including cross-module Repository/Mapper/DO/Entity negative fixtures | integration-maven-verify.txt, integration-fixtures.txt |
| ARCH-003 API framework boundary | PASS after adopting fixed GOV-002 rules and API purity negative fixtures | integration-production-gates.txt, integration-fixtures.txt |

The preserved first build log, maven-verify-before-boot-fix.txt, documents the Web package empty-scan failure after only the root version fix. No failing check was skipped or weakened.

## PR description / impact

Issue: PLAT-001. Fix the Maven skeleton's missing internal dependency versions and preserve the boot library artifact for architecture tests. Java 21 compilation, baseline verification and independent integration with GOV-002 now pass.

SSOT/API/DB/Event impact: none. No new business implementation, HTTP endpoints, DTOs, provider mocks, fixtures pretending to be real APIs, or transaction behavior. Existing String ID contexts/events remain unchanged; no amount calculation was introduced.

Concurrency risk: no runtime behavior change; only PLAT-001-owned POM files changed. Compatibility: dependency graph and pinned external versions unchanged; executable filename gains `-exec`. Rollback: revert the implementation commit, which also restores the original build defects. No database rollback is involved.

Known risks: skeleton success does not establish application startup, database/provider integration, transaction correctness, or future implementation coverage. Remote CI/review have not been accepted by this local evidence. Any merge requires blueXYing human review.

## Independent integration on 2026-09-11

GOV-002's fixed commit `79180f30c39dcd8f5d2d2a0e4f24746da9c39f1a` was adopted without edits as local cherry-pick `1db73ba2c79402d6a1f23a53bee7ea7d2c451b72`, on top of PLAT implementation and evidence. It contains 18 QA-owned files and is explicitly a dependency, not PLAT implementation. The local branch includes that integration dependency; the remote PLAT PR intentionally does not upload these QA-owned files.

On that integrated tree, process-local OpenJDK 21.0.5 / Maven 3.9.12 ran `mvn -B -ntp -f backend/pom.xml clean verify`: all 41 projects SUCCESS, exit 0, 22 JUnit tests (15 independent Java fixture tests + 7 production/source gates), zero failures/errors/skips, and 13 embedded Python tests PASS. Evidence: integration-maven-verify.txt, integration-production-gates.txt and integration-fixtures.txt.

Production import asserted all 39 modules and counted 294 classes including package-info, 50 non-package-info classes, zero real controllers, and zero real domain implementation classes. Synthetic negative fixtures are separate from this production scan. This establishes the skeleton and gate behavior, not transaction feature correctness. ARCH-001–005 ran through Maven; ARCH-001–003 satisfy this Issue's local architecture requirements.

## Remote delivery

The initial remote baseline 404 was resolved by GOV-001 publishing `5fb3950d3ac214dab2db5cc90deaf35a09ca7967`. PLAT branch `codex/plat-001-backend-core` was created from that fixed base and only its own POM/evidence files overlaid. Initial remote head `7b66fb641c0cc3956f9ceaf6e6d790278102da46` was compared to base: 11 files changed, no deletions, behind 0; GOV-001 governance additions preserved. Later commits update only PLAT evidence.

Draft PR: https://github.com/blueXYing/pet-platform/pull/2 . Base: `chore/GOV-001-repository-baseline`, not main/develop. Connector-created remote commit IDs differ from local commit IDs; the two POM changes correspond to local implementation `6cfefa8799a6c1bcba8c48a35940215e1a6c4f7d`. The integration result requires the separately owned fixed GOV-002 rules, and must not be misrepresented as execution of those rules by the standalone remote PLAT tree.
