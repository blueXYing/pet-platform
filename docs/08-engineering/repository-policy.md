# Git基线与交付规则

真实仓库为 https://github.com/blueXYing/pet-platform ，审核人为 @blueXYing。2026-09-11远端只有main初始README提交 `8c1aea572333fe64eb56d8373e5f3f20d085dea8`，尚无develop。不得把配置的默认分支等同于完整工程基线。

本次通过已连接GitHub只读API取得README blob、tree和签名commit内容，在本地还原并校验全部Git对象SHA。未转抄认证信息。初始远端README保留在祖先提交；430份本地用户文件以原字节导入后建立GOV-001 worktree，导入前后SHA-256校验留档。根目录保持本地导入分支，不移动或清理用户资料。

正常流程：Issue READY与依赖/文件所有权确认 → 从获准基线建立独立分支/worktree → 实现 → 对应测试 → commit → draft PR → CI/Review → 人工批准merge develop → 按批准release main。启动Wave不授予merge/release权限。

本次尚无develop，草稿PR以现有main作为差异审阅目标，**不代表请求或授权发布main**。人工另行建立/选择develop并批准集成后，再重定向PR；Worker不创建或推送受保护分支，不自动merge。

基线操作必须先检查远端实际分支和历史，禁止在未知上游时用Init-LocalGit.ps1盲建无共同祖先的仓库。现有脚本保留为历史模板，本次不调用其初始化或删除动作。

现有New-TaskWorktree.ps1会切换当前分支并pull，禁止在根调度目录自动运行该旧流程。使用以下不修改当前checkout的明确命令（基线须由Work确认，本Issue未完成时不得启动依赖）：

```powershell
git show-ref --verify refs/remotes/origin/develop
git worktree add ../wt-ISSUE-ID -b feat/ISSUE-ID-slug origin/develop
git worktree list
```

认证不可用时保留本地提交、bundle与报告，不索取token、不把连接认证复制给Git、不宣称推送或PR成功。通过连接工具发布时核验远端commit/tree与本地对应；所有更新只触及Issue分支，不force覆盖他人提交。

分支保护建议由人工核验落实：main/develop禁止直接推送及force/delete，要求PR、CODEOWNERS审核、相关测试/CI通过。当前仅登记策略，未声称已在GitHub生效。产品权限的单运营裁决不取消工程合并批准。

DoD遵循AGENTS及Issue：AC完成、相关测试和架构检查通过、契约变化披露、PR可Review、遗留风险说明。失败时保持未完成，不能以草稿PR或静态检查替代完整测试。需要修复其他Issue负责的门禁时交Work处理依赖，不自行扩大Scope。

回滚：本次只在独立分支追加提交，可由人工关闭PR或后续revert治理提交；保留导入基线及原资料。worktree仅在验收/交接后、确认无未提交文件且路径已解析核验时移除，不递归删除用户目录。
