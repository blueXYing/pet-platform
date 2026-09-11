# 人类 CTO 操作手册

你不需要成为 Git 专家。你的职责主要是“批准、裁决、看门”。

## 第一次启动

1. 解压本目录。
2. 打开 ChatGPT Desktop 的 `宠物平台 V1.0` Project。
3. 进入 Work。
4. 授权 Work 访问本目录。
5. 粘贴 `01-WORK-FIRST-PROMPT.md`。
6. Work 完成 Bootstrap Validation 后，如果没有阻断项，你回复：
   `批准进入 EXECUTION_READY，启动 Wave 1。`

## 你每天主要做 4 件事

### 1. 看 Ready Queue
让 Work 告诉你今天可以同时启动哪些 Issue。

### 2. 处理 Blocker
只有以下问题需要你重点介入：
- 产品规则没有答案；
- AI 申请修改 SSOT；
- 跨模块 Contract 需要变更；
- 外部支付/微信/第三方资质；
- 是否允许 PR Merge。

### 3. 看 PR 摘要
不要求逐行读代码。至少检查：
- 是否只做当前 Issue；
- 是否修改了禁止目录；
- 产品规则是否一致；
- CI 是否全绿；
- AI Reviewer 是否存在 Blocking/High。

### 4. 批准 Merge
建议所有 Worker 只提 PR 到 `develop`。
`main` 只用于 Release Candidate / 正式版本。

## 你暂时不要自己使用的 Git 命令

除非明确知道后果，不要自行运行：
- `git reset --hard`
- `git clean -fd`
- `git push --force`
- 大规模 `rebase`

让 Codex/Integration Worker 处理。

## 你不会 Git 时怎么创建 Worktree

本包已有：
`scripts/New-TaskWorktree.ps1`

以后告诉 Codex：
`请为 Issue TX-005 使用 scripts/New-TaskWorktree.ps1 创建任务 worktree。`

不要求你手工敲复杂命令。
