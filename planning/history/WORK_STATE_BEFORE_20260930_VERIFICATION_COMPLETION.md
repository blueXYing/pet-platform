# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 26.0
UPDATED_AT: 2026-09-29
CURRENT_PHASE: W2_WAVE_IN_PROGRESS
CURRENT_STATUS: VER_CREDENTIAL_V1_V2__LOCAL_VERIFIED_READY_FOR_PR_REVIEW
VERIFIED_BASELINE: develop 324cba1243183a4cfc9cfef46373121848bdd6a8（PR93已合并；合并CI36539276218六项通过，112份后端报告698测试、零失败/错误/跳过）
NEXT_PHASE: 提交默认关闭的凭证内核PR供审阅，检查远端全量CI；不自动合并
NEXT_PHASE_APPROVED: 用户2026-09-29明确“批准 V1、V2”，正式契约/Schema同步、实现、测试及提交PR已授权；不合并、不生产迁移或启用。

## 当前事实

- [PR93](https://github.com/blueXYing/pet-platform/pull/93)已合入develop，旧待审状态保留在[历史记录](planning/history/WORK_STATE_BEFORE_20260929_VERIFICATION.md)。
- 合并CI六项成功，已下载解析112份Surefire XML、698测试零失败/错误/跳过；见[CI](planning/progress/2026-09-29/verification-foundation/pr93-merge-ci.json)和[汇总/哈希](planning/progress/2026-09-29/verification-foundation/pr93-merge-backend-summary.json)。这是改期基线证明，不是新核销业务验收。
- 在干净、无在途任务的既有隔离worktree创建`codex/verification-foundation-20260929`，基于PR93合并版本；原用户目录未改动。
- [凭证基础V1/V2方案](planning/ccr/CCR-W2-API-001/verification-credential-proposal.md)已明确批准，[准备记录](planning/progress/2026-09-29/verification-foundation/PREPARATION.md)保留审批前历史。正式来源为SSOT §36、47号Contract/Schema。
- 已落地真实动态码、只读视图、幂等刷新、风险锁/Outbox及改期同事务fence；本地73项相关Java测试零失败/错误/跳过，契约118项、源码架构18项及静态门禁通过。见[实现/验收](planning/progress/2026-09-29/verification-foundation/IMPLEMENTATION.md)和[实际报告摘要](planning/progress/2026-09-29/verification-foundation/local-targeted-tests.json)。ORDER提供当前资格及改期提交证明；VER不越Owner读写。
- 既定硬规则不变；第三次失败锁15分钟、跨换码/改期保持锁和读写分离按批准执行。商家核验权限仍必需可信Provider，缺失启动失败，QA适配器不算真实成员链交付。

## 下一步

1. 本地MySQL并发/故障及既有改期回归已通过；无需重复申请V1/V2批准。
2. 默认关闭，提交PR审阅并检查当前提交CI；不自动合并、生产迁移或启用。
3. 后续补商家核销身份、完整OrderOperationGuard/markVerified及未履约售后失效，再接公开版本读侧、HTTP/小程序；不把主账号userId伪装为staffId。

## 验收与限制

本轮本地相关测试已通过，远端全量结果以PR当前提交CI为准。完整VER-001/VER-002/ORD-003保持未完成；运行默认关闭。未执行生产迁移、正式支付退款渠道或外部通知；不以既有698项报告冒充新功能验收。
