# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 0.7
UPDATED_AT: 2026-09-11

CURRENT_PHASE: W2_WAVE_IN_PROGRESS
CURRENT_STATUS: WAVE_IN_PROGRESS
NEXT_PHASE: W3_INTEGRATION_REVIEW
NEXT_PHASE_APPROVED: NO

## 最新启动批准（2026-09-11）

人工已明确：批准进入 EXECUTION_READY，启动 Wave 1。此前暂停及未批准记载均为历史，已由本次批准覆盖。W0校验及W1 EXECUTION_READY已通过，现已进入W2。GOV-001任务已创建，任务ID：01a08e74-4198-7131-9ebb-82b529caa841；其他 Issue 按 Ready Queue 依赖与文件所有权解锁。WAVE_1_START_APPROVED: YES。顶层NEXT_PHASE_APPROVED指下一阶段W3，不代表撤销Wave启动批准。启动批准不包含合并到 develop、发布到 main、修改产品规则或重大 Contract 变更。

## 仓库与审核人登记（启动前历史快照）

- 人工指定仓库：https://github.com/blueXYing/pet-platform 。
- PR审核人：@blueXYing；技术契约审核人：@blueXYing。
- 已通过GitHub连接只读核验：仓库存在、可访问、为私有仓库，默认分支配置为main；连接返回pull/push/admin权限。此结果不代表本机Git认证或分支保护已验证。
- 本地CODEOWNERS已填写指定审核人，尚未上传或在远端生效；本地目录仍未初始化Git，未设置remote、创建分支/worktree、提交或推送。
- 提供仓库与审核人不构成Wave 1启动批准；暂停状态和NEXT_PHASE_APPROVED: NO保持不变。

## 历史暂停指令（已被最新启动批准覆盖）

- 人工明确暂停 Wave 1；本状态优先于下方历史 W0 校验结果及原 READY 标记。
- 人工于2026-09-11明确“R1～R6 按建议确认”；本次完成文档与任务同步，不启动 Codex 开发任务或子代理，不创建 branch/worktree，不写业务代码。
- 人工已明确方向：前端 React；用户和商家共用一个微信小程序并切换身份；运营端 React 网页；用户端不做网页，App 后续再做。
- 人工已明确还原要求：V1.0 范围内按 Figma 原稿一比一还原，使用原始切图，不作为单纯风格参考；范围外入口删减导致的布局变化先确认。
- 已批准方案见planning/ARCHITECTURE_CHANGE_REVIEW_ACR-001.md，同步记录见planning/ACR-001_SYNC_REPORT.md；现行技术为后端v0.6 + 前端v0.7（20号文档），前端测试执行21号补充。
- 原63个Issue/7个Wave1任务不增不减；C-001负责统一小程序壳，M-001新增C-001依赖并保持BLOCKED。共享文件由C-End唯一编辑，两个业务岗位继续保留。
- CCR-ACR-001已登记，具体会话API/DTO仍待规范审批；不将R4方向批准误作完整接口批准。原技术CCR与资金/签约产品未决项保持有效；生产权限已按下方最新裁决关闭产品未决。
- 退出暂停剩余条件：人工另行批准启动Wave 1；具体Issue仍须通过依赖、Contract、测试及文件所有权门禁。本次ACR确认不构成Wave启动批准。
- 同步复核已通过：63个Issue/42个Story不变，任务元数据一致、依赖无环；361个受保护原文件哈希未变。未运行构建/业务测试，结果见ACR-001_SYNC_REPORT.md。

## 历史 W0 校验结果（2026-09-11，已被上方暂停指令覆盖）

- 本次人工指令授权将校验结果登记为 EXECUTION_READY；尚未授权启动 Wave 1。
- CURRENT_PHASE 保留 W0，遵守下方人工批准退出条件；不得将状态登记理解为 W2 已启动。
- 9 份交接文件及 docs/ 中 19 份最终资料均存在并已读取核验；三份 Word PRD 已提取正文核对。
- 资料优先级：SSOT > 三端最终 PRD > 技术基线 > Schema > API/Event/Scheduler > Test > 工程协作规范。
- 未发现阻断 Wave 1 工程基础范围的产品冲突；7 个既有 Issue、Owner、依赖和业务范围不变。
- 具体启动约束、验收补充、验证证据见 planning/BOOTSTRAP_VALIDATION_2026-09-11.md 及 planning/READY_QUEUE_WAVE_1.md。
- 后续产品未决项已进入 planning/OPEN_DECISIONS.md；技术契约缺口已进入 planning/CCR_W0_REGISTER.md，相关后续阻断见 planning/BLOCKED_QUEUE.md。
- 当前目录尚无 Git 仓库；当前 Maven 使用 Java 17，须在批准后落实 Git/worktree 基线与 Java 21，不能据此声称构建已通过。
- 本次未写业务代码，未创建 Wave 1 Tasks/branch/worktree，未提交、推送或启动 Wave 1。

批准后由 Work 先落实 GOV-001 基线，再按 Ready Queue 解锁独立 Issue；一个 Issue 一个短生命周期 Codex Thread，改代码必须独立 branch/worktree。

## 已完成前置成果

- 三端最终 PRD：存在
- 最终业务 SSOT：存在
- 后端技术基线 v0.6 + 前端技术基线 v0.7：存在（ACR-001已批准）
- Core DB Schema：存在
- Internal API Contract v0.6：存在
- HTTP API / OpenAPI：存在
- Integration Events v0.6：存在
- Scheduler/Retry/Compensation v0.5：存在
- 全链路测试矩阵：存在
- 并发/故障矩阵：存在
- Maven 后端骨架：已放入 `backend/`
- AI 6 Role：已初始化
- Epic/Story/Issue Catalog：已初始化
- 第一批 Ready Queue：已初始化

## W0 允许动作

- 读取并验证现有基线；
- 检查 Ready Queue 是否与 SSOT 冲突；
- 将真正阻断项写入 `planning/BLOCKED_QUEUE.md`；
- 更新本状态文件。

## W0 禁止动作

- 重新从三份 PRD 开始设计产品；
- 重新裁决已封板规则；
- 一次性大规模创建全部 GitHub Issue；
- 写业务实现代码。

## W0 退出条件

1. 最终资料读取成功；
2. Ready Queue 与 SSOT 无已知 Blocking 冲突；
3. Preliminary Audit 中的非阻断风险已登记；
4. 人工 CTO 回复批准。

## CTO 批准语句

`批准进入 EXECUTION_READY，启动 Wave 1。`

## 最新产品裁决：运营权限（2026-09-11）

OD-W0-003已RESOLVED。P1=A；P2=原PRD已有财务按通用RBAC配置、不扩功能；P3/P4=单运营无双人复核及内部审批；P5=获权运营编辑直接发布；P6=B超管默认全已批准V1业务权限/全平台范围。已同步SSOT §24及22号PRD补充，原Word留档；具体权限API技术映射按CCR-PERM-001由AUTH-001建立。其他业务规则不变，NEXT_PHASE_APPROVED仍为NO，当前仍暂停等待Wave批准。

本次文档校验：63个Issue/7个Wave1任务保持，任务测试元数据一致，PERM-001～006均有定义；349个后端/Schema/Event/Scheduler/OpenAPI及Word原件文件哈希未变。仅同步产品裁决与技术/任务引用，没有运行构建或业务测试、没有启动开发。


