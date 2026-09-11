# Wave 1 启动操作清单

前提：Work 已完成 W0_BOOTSTRAP_VALIDATION，并且你已回复：
`批准进入 EXECUTION_READY，启动 Wave 1。`

## 1. 第一批角色
- Backend Core
- C-End Frontend
- Merchant Frontend
- Admin Frontend
- QA

Transaction Backend 第一批复杂业务暂不抢跑，等公共/基础依赖形成后自动解锁。

## 2. Work 必须做
读取 `planning/READY_QUEUE_WAVE_1.md` 和 GitHub 当前状态，给出“今天真正启动的 Issue”。

## 3. 每个 Issue 的启动
让 Work/Codex 调用：
`scripts/New-TaskWorktree.ps1`

例如：
`请为 C-001 创建任务 worktree，并告诉我应该在 ChatGPT Desktop 新建哪个 Codex Thread、打开哪个目录。`

## 4. 新 Codex Thread
每个 Issue 都新开 Thread。
把 `.ai/templates/codex-task-start.md` 作为模板，并引用对应 Issue 文件。

## 5. 完成后
Codex：
- 测试；
- Commit；
- Push；
- PR。

QA/Reviewer：
- 检查 Acceptance Criteria；
- 检查 CI；
- 输出是否建议 Merge。

## 6. 你本人
只在以下节点介入：
- Work 请求批准 Wave；
- Blocker/产品裁决；
- CCR 涉及产品行为；
- PR 最终 Merge；
- Release。

## ACR-001同步后的启动约束

R1～R6已批准但Wave 1未获启动批准；必须检查WORK_STATE。C-001为统一Taro React小程序壳；M-001依赖C-001并仅写merchant工作区；A-001为React Web。六岗位职责保留，Transaction不提前实现被依赖阻断的业务。新任务必须引用20号基线、21号验收补充和ISSUE_EXECUTION_NOTES_ACR-001.md。
