# Ready Queue — Wave 1收尾

2026-09-14：七项已按原Scope完成并合入develop；本表不是下一波派发授权。W3审查结论见WAVE_1_CLOSEOUT_REVIEW.md，状态文档同步待人工合并。

| Issue | Role | 任务 | 原依赖 | 当前状态 |
|---|---|---|---|---|
| GOV-001 | Backend Core | 初始化统一仓库目录与最终 docs 基线 | 无 | DONE |
| GOV-002 | QA | 启用架构边界检查与基础 CI Gate | GOV-001 | DONE |
| PLAT-001 | Backend Core | 验证并修复 Maven 后端骨架可构建 | GOV-001 | DONE |
| C-001 | C-End Frontend | 统一微信小程序壳、用户路由、状态与平台Mock | GOV-001 | DONE |
| M-001 | Merchant Frontend | 同一小程序商家工作区、分包路由与Mock | GOV-001,C-001 | DONE |
| A-001 | Admin Frontend | React运营网页壳、路由、权限与Mock | GOV-001 | DONE |
| QA-001 | QA | 测试框架、ArchUnit、Contract Smoke、CI首轮 | GOV-001 | DONE |

## 依赖、所有权与验收归属

GOV-001完整架构验收与PLAT/GOV-002实现存在的启动循环已按EX-W1-001以固定资料输入独立推进并经PR6组合验证解决；不得沿用旧候选READY解释为仍待实施。

C-End独占共享壳；M-001仅写merchant目录，必要五个公共文件由同Issue C-End子代理单独提交，见M-001 REVIEW和执行日志。QA的GOV-002→QA-001按顺序交接CI所有权。Transaction在已批准范围只读审阅，不提前实现交易。

各Issue正文保留原Source/AC/Test；DONE仅限工程壳。34小程序fixture测试和DevTools实际行为不替代真实权限、签约、业务或视觉验收；后续业务保持Contract/产品门禁。下波仅在W4细化与授权，不按PRD章节串行重拆。
