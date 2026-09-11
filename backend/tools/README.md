# GOV-002 architecture gates

Source: AGENTS; SSOT; backend technical baseline v0.6 §§1/4/5; Internal API v0.6 §1.10; HTTP API v0.4 order display contract; testing matrix ARCH-001–005; frontend baseline v0.7 and acceptance supplement 21. No product, Schema, API or Event contract is changed.

Run from the repository root with Java 21, Maven >=3.9 and Python 3 on PATH:

```text
python backend/tools/check-module-deps.py
python backend/tools/check-display-status.py
python -m unittest discover -s backend/tools -p 'test_*.py' -v
mvn -B -f backend/pom.xml clean verify
```

Maven's architecture tests also execute the two source gates and Python fixture suite. Do not run the architecture module alone against stale outputs: run the whole reactor with `clean verify`. Its production import reads **each declared module's target/classes**, not just the architecture-test classpath. Missing outputs, modules without a non-package-info class, fewer than the baseline 39 production modules, an empty reactor, or unlisted on-disk modules fail. Test classes and synthetic violations never enter the production import.

| Test | Enforcement | Positive / negative evidence |
|---|---|---|
| ARCH-001 | Existing Maven Enforcer plus complete reactor POM inventory; biz dependency edges, including test/profile/aliased group edges, are forbidden | API dependencies allowed; refund→order biz fixtures rejected; empty/unlisted reactors rejected |
| ARCH-002 | ArchUnit inspects all direct bytecode dependencies; domain ownership comes from com.petplatform.<domain>; persistence recognized by package, Repository/Mapper/DO/Entity/DAO suffix and persistence annotations | Local persistence and cross-module APIs allowed; ordinary refund/admin classes referencing order persistence rejected |
| ARCH-003 | API forbids Spring, MyBatis core/org.mybatis, MyBatis-Plus, JPA, biz and persistence/implementation types | Pure DTO allowed; Mapper/TableName/Entity annotation fixtures and API implementation rejected |
| ARCH-004 | Web package, Controller class name or controller annotation cannot depend on persistence, SQL access or domain internals | Application delegation allowed; repository update and annotated endpoint mapper access rejected |
| ARCH-005 | Java main sources and both frontend src trees reject raw fact/display derivation combinations, named calculators and enum-constant selection outside pet-order-biz | Order calculation, pass-through, labels, filter comparisons and static snapshots allowed; Java/TS conditional/switch/lookup/nested-fact copies rejected; actual path injection into all three source roots verified |

The domain→infrastructure/framework rule is retained and tested separately. A fixture must first compile successfully and then fail for the specific rule; compilation failure is not a passing negative test.

ARCH-005 is a conservative source review gate, not general-purpose interprocedural dataflow analysis. Obfuscated/renamed or indirect computations, reflection and SQL built through unrecognized APIs still require code review. A flagged legitimate mixed-purpose file should separate display rendering from raw facts and delegate calculation to order; do not add blanket suppressions. This gate does not prove the future order priority algorithm's business correctness. That belongs to its business Issue and contract tests. The skeleton has no real controllers/domain implementation yet; the report explicitly lists zero counts and independently exercises those boundaries with fixtures.

## Frontend capability boundary

Confirmed C-001/A-001 handoff: Node 22.23.1, npm 10.9.8 and owner-provided package-lock.json. The CI does not edit either application.

| Entry | Actual commands after npm ci | Does not establish |
|---|---|---|
| web-build | typecheck, build, check:boundaries | Playwright execution, real Java RBAC/API or visual acceptance |
| miniapp-build | typecheck, test, build:weapp, check:package | WeChat developer tools/device execution, real payment/permissions or visual acceptance |

`test` in the miniapp is the owner's Node fixture suite. `check:package` counts package bytes; it is not platform acceptance. Neither owner supplied a lint script; no lint result is claimed and no `--if-present` silently omits required commands.

The default CI shows missing application manifests as skipped build jobs with NOT_IMPLEMENTED in its inventory summary. An existing app with missing lockfile, source files or required scripts fails. A green backend job/inventory is not full frontend acceptance. Manual `Explicit frontend capability gate` requests fail if the requested capability is unavailable; web-runtime, wechat-runtime and visual are intentionally NOT_EXECUTED until QA-001/later Issues supply and verify real entrypoints. This Issue does not implement QA-001, business E2E, database smoke or provider tests.

CI runs on PRs targeting main/develop or the approved GOV-001 stacking branch. No workflow merges or pushes protected branches. Reviewers must not use a skipped/absent capability as a release approval.
