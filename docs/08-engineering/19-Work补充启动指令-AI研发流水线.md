# ChatGPT Work 补充启动指令：建立 AI Engineering Operating System

在完成项目资料一致性审计后，请读取：

`18-AI并行研发流水线规范-v0.1.md`

把它作为工程协作输入草案进行审查。

注意：
- 它不是产品 SSOT；
- 不允许用该文档修改任何产品规则；
- 允许对 Git / Issue / PR / Worktree / Codex / CI 协作机制做技术优化。

请最终形成并落到仓库：

1. `AGENTS.md`
2. `.ai/roles/backend-core.md`
3. `.ai/roles/transaction-backend.md`
4. `.ai/roles/c-frontend.md`
5. `.ai/roles/merchant-frontend.md`
6. `.ai/roles/admin-frontend.md`
7. `.ai/roles/qa.md`
8. GitHub Issue Templates
9. Pull Request Template
10. CODEOWNERS
11. Contract Change Request Template
12. Definition of Done
13. Branch / Worktree Policy
14. CI / Merge Gate
15. Release Process
16. AI Worker Ready Queue / Blocked Queue 工作机制

最终输出：
- AI研发组织图；
- Issue状态机；
- Worktree生命周期；
- 每个Role允许/禁止修改范围；
- 日常“领取任务 → Codex → PR → CI → Merge”完整流程；
- 哪些规则应进入仓库，哪些只留在项目管理文档。
