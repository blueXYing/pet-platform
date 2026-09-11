# PLAT-001 review evidence

Status: implementation committed; full ARCH-001–003 acceptance pending GOV-002 rule integration. Do not mark the Issue DONE from the baseline test result alone.

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
| ARCH-002 cross-module persistence | NOT COMPLETE: original test suite has no such rule; await GOV-002 | integration evidence to follow |
| ARCH-003 API framework boundary | Baseline positive rule passes; complete negative fixtures await GOV-002 | architecture-baseline.txt |

The preserved first build log, maven-verify-before-boot-fix.txt, documents the Web package empty-scan failure after only the root version fix. No failing check was skipped or weakened.

## PR description / impact

Issue: PLAT-001. Fix the Maven skeleton's missing internal dependency versions and preserve the boot library artifact for architecture tests. Java 21 compilation and baseline verification now pass; integration with GOV-002 is still required to accept ARCH-001–003 fully.

SSOT/API/DB/Event impact: none. No new business implementation, HTTP endpoints, DTOs, provider mocks, fixtures pretending to be real APIs, or transaction behavior. Existing String ID contexts/events remain unchanged; no amount calculation was introduced.

Concurrency risk: no runtime behavior change; only PLAT-001-owned POM files changed. Compatibility: dependency graph and pinned external versions unchanged; executable filename gains `-exec`. Rollback: revert the implementation commit, which also restores the original build defects. No database rollback is involved.

Known risks: skeleton success does not establish application startup, database/provider integration, transaction correctness, or future implementation coverage. Remote CI/review have not run. The connected GitHub branch listing showed main and chore/GOV-001-repository-baseline, but fetching backend/pom.xml from the latter returned 404; the complete local input commit is not proven remotely available. No remote branch/PR is claimed. Prepared PR awaits an available remote baseline and approved publishing path; any merge requires blueXYing human review.
