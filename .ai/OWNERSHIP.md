# 文件所有权与审核归属

2026-09-11人工指定真实GitHub审核人：PR及技术契约均为 **@blueXYing**；见 `.github/CODEOWNERS`。岗位名代表执行职责，不是虚构GitHub账号。CODEOWNERS不授予范围外写权限，也不证明远端强制规则已经启用。

| 文件/范围 | 唯一编辑者及Issue约束 |
|---|---|
| GOV-001的root/docs/.ai/.github/scripts治理文件 | Backend Core，本Issue专用worktree；不改已封板正文及Contract |
| WORK_STATE.md、planning任务台账 | 根Work调度任务；Worker只提交Issue报告，由Work更新状态 |
| backend/**骨架修复 | PLAT-001 / Backend Core；不得写交易业务 |
| backend架构测试、tools、CI | GOV-002后QA-001顺序交接；backend/pom.xml与PLAT-001共享时先登记唯一编辑者与提交 |
| frontend-miniapp根配置、锁文件、config/**、src/app*、src/shared/**、src/consumer/** | C-End / C-001；根通配仅根文件，不授权merchant业务 |
| frontend-miniapp/src/merchant/** | Merchant / M-001，等待GOV-001和C-001完成；共享修改交C-End |
| frontend-admin/** | Admin / A-001，React + TypeScript + Vite + React Router |
| e2e/**及测试入口 | QA，按Issue范围；Web测试不冒充微信运行时测试 |
| SSOT、AGENTS、Schema、API/OpenAPI、Event/Scheduler | 保护基线；必要变更走CCR与指定审核人批准 |

每次共享文件交接须登记：Issue、分支、提交、文件、释放状态。公共配置未释放不得并发编辑。当前GOV-001尚未完成；本文件不自行解除任何依赖。

交易模块由Transaction Backend按后续原Issue范围实现；本次仅允许只读审阅。身份契约见 `planning/ccr/CCR-ACR-001.md`，权限API见 `planning/ccr/CCR-PERM-001.md`。内部fixture不能被当作批准后的公共DTO/SDK。
