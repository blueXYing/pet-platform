# W0 Bootstrap Validation — 2026-09-11

历史记录说明：本文件保存首次W0审计时点结论。随后ACR-001已获批准并同步React/统一小程序及任务依赖，现行基线、队列与暂停状态以WORK_STATE和ACR-001_SYNC_REPORT.md为准，不再按下方旧Vue说明实施。

## 结论与阶段

本次仅执行 W0。未发现阻断现有 Wave 1 七个工程基础 Issue 的产品级冲突；CURRENT_STATUS 已登记 EXECUTION_READY。CURRENT_PHASE 保留 W0_BOOTSTRAP_VALIDATION，NEXT_PHASE_APPROVED=NO；待人工批准后才进入 W1 并派发任务，不启动 W2。

这不是业务 DoD、全量契约一致性或构建通过声明。没有写业务代码，没有创建 Wave 1 Codex Tasks/branch/worktree，没有 Git 初始化、提交、推送、PR 或 Merge。

## 文件与优先级

- 指定的 9 份交接文件全部存在并读取；docs/ 共 19 份资料：SSOT 1、最终 PRD 3、架构 2、Schema 2、API 4、Event 1、Scheduler 1、Test 3、工程规范 2。
- 优先级沿用 Work Protocol：SSOT > 最终 PRD > 技术基线 > Schema > API/Event/Scheduler > Test > 工程协作规范。低层文档局部优先级说明不削弱最终 PRD 的位置。
- 三份 DOCX 的 word/document.xml 均成功解析，提取含表格的全部正文段落，均无 w:ins/w:del 修订节点。C/商家/运营分别为 2975/2122/3281 段。审计采用章节扫描及 Wave 1、封板规则、Preliminary Audit 相关段落核验，不声称全部字段表已逐行精审。
- 修改前核对 MANIFEST.csv 的 417 个条目：缺失 0；docs/ 中 19 份文件哈希全部匹配。唯一既有不匹配是 BOOTSTRAP_STATS.json；未覆盖该差异或改写原始清单。实际 Issue 63、Story 42、Wave 1 Issue 7，与统计中的对应数量一致。
- 原始 SSOT、PRD、技术契约及实现文件保持不变；本次修改仅为状态和 planning 审计/执行约束文档。

## Wave 1 检查

| 检查 | 结果 / 处理 |
|---|---|
| 产品范围 | 7 Issue 均为目录、骨架、工程壳或测试门禁，不实现未封板业务 |
| 任务拆分 | 保留 GOV-001、GOV-002、PLAT-001、C-001、M-001、A-001、QA-001 的 ID/Owner/依赖/范围 |
| AC / Forbidden | 原 Issue AC 通用化；在 READY_QUEUE_WAVE_1.md 补充标题范围内可验收结果及统一禁止边界，不新造需求 |
| 测试 | 全部有测试要求；ARCH-001~005 可定位至全链路矩阵第154~158行；前端要求 typecheck/smoke |
| 追踪 | Catalog 可对应 Epic/Story；已在 TRACEABILITY_MATRIX.md 补入七个基础 Issue 的来源和测试关联 |
| 依赖 | GOV-001 先形成基线；不能把 READY 理解成它已完成。三端可在明确基线/Mock后独立推进 |
| 文件冲突 | QA 两任务串行；PLAT 的宽 backend/** 范围与 QA 架构目录按唯一编辑者协调；三端目录独立 |
| 六角色 | 原 Queue 文案写6线程但只列5实现岗；现已披露实际分配，Transaction 参与现有 Issue 契约/Mock只读审阅，交易业务继续按依赖解锁。没有声称存在第六个独立实现 Issue |

六角色协作不要求为了凑数提前实现交易业务。若后续人工要求 Wave 1 必须同时有六个独立实现 Issue，则现有队列不能满足，需人工明确调整；本次不造第八张任务。

## 已知差异与适用边界

1. 运营 PRD §6.6.2 仍列旧八态，商家 PRD §5.6 概述仍提“C端预约选人”。采用更高优先级 SSOT：十种 DisplayOrderStatus 由 order 计算，用户不可选人。不把残留文案反向修改为新规则，也未修改 PRD 原件。后续文档同步由治理流程处理。
2. Preliminary Audit A-001：运营 PRD §6.2.9 引用仓库未提供的《资金管理1.0需求规格说明书 V1.4》，涉及7天冻结、分账、提现、保证金。登记 OD-W0-001；资金实现不得从售后7天窗口自行推导。
3. Preliminary Audit A-002：三端 PRD 有审核后支付服务商签约流程；具体签约接口/状态映射未冻结。登记 OD-W0-002；不自行选择 Provider。
4. Preliminary Audit A-003：人工客服做、承载未定，沿用原 Open Decision；保持模块边界，不实现未知聊天协议。
5. 运营 PRD §4.1/4.2 的角色标签、财务只读与操作权限存在歧义。登记 OD-W0-003；A-001 只做通用权限壳，真实 RBAC 不能用 Mock 自行封板。
6. Outbox 恢复租约字段、迟到支付来源、订单关闭原因/确认轮次的存储映射进入 CCR-W0-001~003。阻断相关后续实现，不阻断当前仅验证已有契约的 Smoke 或工程壳。
7. C端/商家端目标包含微信小程序，运营为PC Web；frontend-c README 已指定 Vue3/TypeScript/Vite/Pinia/Router/Axios。本次不选额外框架；工程壳通过不等于小程序发布能力完成，后续适配须明确验收目标。

PRD 证据定位：C §5.1.23/5.1.25；商家 §5.2/5.6/6.6/7；运营 §4.1/4.2/5.3/6.2.9/6.6.2。临时提取文本用于核验，最终权威来源仍是 docs/01-prd/ 原件。

## 实际验证及限制

- 文件存在性、清单哈希核验已完成，结果如上。
- 执行已有 backend/tools/check-module-deps.py，退出成功：无 biz→biz Maven 依赖。该脚本不覆盖所有架构约束，不能代替 ARCH-001~005 全部测试。
- OpenAPI 静态检查：YAML 可解析，15 paths / 16 operations，内部 $ref 均可解析，operationId 无重复，现有写操作均声明 RequestId。这不是完整 OpenAPI 规范验证、服务端行为验证或 E2E。
- 当前 git status 报 not a git repository；没有 Git 上游、分支保护或远程 CI 成功证据。CODEOWNERS 仍是待真实身份模板。
- java -version 与 mvn -version 均确认当前使用 Java 17.0.0.1，Maven 3.9.12。Java 21 为执行前置；本次未运行 Maven verify、前端测试或 CI，未声明它们通过。
- Git 初始化及 worktree 脚本的原生命令退出码处理需在 GOV-001 范围内检查；本轮没有运行这些有副作用的脚本。

## 我现在需要人工 CTO 做的操作

1. 确认启动时回复：`批准进入 EXECUTION_READY，启动 Wave 1。` 本次到此停止。
2. 提供或指定 GitHub 仓库及实际 Review/Contract Owner 身份；后续由执行者完成 Git、分支保护和 worktree 配置，无需 CTO 手工逐条执行。
3. 在相关业务 Issue 启动前处理 OPEN_DECISIONS.md 中资金基线、签约接入和生产权限矩阵的裁决；这些不作为本批工程壳启动的附加门槛。

Java 21 环境准备、文档细化、CCR 技术评审及文件所有权协调由执行团队按既有 Issue/职责处理，不转嫁为 CTO 手工开发工作。
