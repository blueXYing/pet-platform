你正在接手“宠物平台 V1.0”AI 并发研发项目。

不要从零重新讨论研发流程，不要重新发明任务拆解方式。

请首先读取：
1. `00-START-HERE.md`
2. `WORK_EXECUTION_PROTOCOL.md`
3. `WORK_STATE.md`
4. `AGENTS.md`
5. `planning/READY_QUEUE_WAVE_1.md`
6. `planning/EPIC_CATALOG.md`
7. `planning/ISSUE_CATALOG.csv`
8. `planning/TRACEABILITY_MATRIX.md`
9. `planning/AUDIT_FINDINGS_PRELIMINARY.md`

随后读取 `docs/` 中的最终 SSOT、三份 PRD、技术基线与相关技术契约。

执行规则：
- 当前只执行 `WORK_STATE.md` 指定的阶段；
- 已封板产品规则不得重新讨论或反向修改；
- 发现真正冲突时列为 BLOCKER / 待产品裁决；
- 不允许因为 PRD 章节多就按章节顺序串行开发；
- 必须维持多 AI 并发：Backend Core、Transaction Backend、C端、商家端、运营端、QA；
- 一个 Codex Thread 对应一个短生命周期 Issue；
- 一个需要改代码的 Issue 对应独立 branch/worktree；
- Contract 缺失走 CCR；
- 第一批任务已预拆，除非发现阻断矛盾，否则不要重新拆第一批任务。

本次首先完成：
A. 校验文件/优先级/当前 Work State；
B. 检查第一批 Ready Queue 是否存在阻断冲突；
C. 如果没有阻断冲突，将状态更新为 EXECUTION_READY；
D. 输出“我现在需要人工 CTO 做的操作”，最多 5 项；
E. 不写业务代码。

完成后停止，等待人工批准启动 Wave 1。
