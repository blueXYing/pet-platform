# GitHub 第一次初始化（给不熟悉 Git 的 CTO）

## 原则
第一次只做“建立仓库 + main/develop + 推送”。不要手工做 rebase/force push。

## A. 本地初始化
在项目根目录打开 PowerShell。

优先让 Codex/Work 执行：
`scripts/Check-Environment.ps1`

然后执行：
`scripts/Init-LocalGit.ps1`

它会：
- 初始化 Git（如果尚未初始化）；
- 创建初始 Commit；
- 建立 develop 分支；
- 不会擅自配置 GitHub Remote。

## B. GitHub 网站
1. 登录 GitHub；
2. New repository；
3. Repository name：`pet-platform-v1`（名称可由你决定）；
4. 建议 Private；
5. 不要在 GitHub 端自动生成 README/.gitignore（本地已经有）；
6. 创建仓库；
7. 复制 GitHub 给你的 repository URL。

## C. 把 URL 交给 Codex
不要自己背 Git 命令。

对 Codex 说：

`这是我的 GitHub 仓库 URL：<粘贴URL>。请检查当前本地仓库状态，为它配置 origin，安全地推送 main 和 develop。禁止 force push。完成后告诉我 GitHub 上应看到什么。`

## D. GitHub 上最终至少应看到
- Code：main / develop；
- Issues；
- Pull requests；
- Actions；
- `.github/` 模板；
- docs/；
- backend/；
- frontend-miniapp（consumer / merchant / shared）及frontend-admin；
- AGENTS.md。

## E. 分支保护
等第一次推送完成后，让 Work 输出你当前 GitHub UI 对应的分支保护设置步骤。
初始至少要求：
- main 禁止普通 Worker 直接 push；
- develop 禁止普通 Worker 直接 push；
- PR 合并前 CI 必须通过；
- main 只接受 Release/RC。
