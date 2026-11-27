# 商家营销工具改造执行包

## 文件

| 文件 | 用途 |
|---|---|
| `PLAN.md` | 背景、差距分析、后续阶段设计（分析版） |
| `HANDOFF.md` | **执行手册**：固定决策、Phase 1 规格、测试命令、报告模板 |
| `PROMPT-P1.txt` | 直接复制给执行智能体的 Phase 1 提示词 |
| `PROMPT-P2.txt` | 已迁移：见 `planning/progress/2026-11-18/svc-flash-price/PROMPT.txt` |
| `PROMPT-P3.txt` | Phase 3（商家券）提示词 |
| `PROMPT-P4.txt` | Phase 4a（裂变返券）提示词 |
| `PROMPT-P5.txt` | Phase 5（会员卡）提示词 |

配套决策/契约（已落库）：
- `docs/01-prd/41-商家营销工具与会员卡裁决补充-v1.0.md`
- `planning/ccr/CCR-MKT-TOOLS-001.md`
- `docs/04-api/76-Merchant-Marketing-Tools-Contract-v0.1.md`

## 工作区

默认交接 worktree：`/mnt/d/wt-marketing-tools`，分支：`codex/merchant-marketing-tools-20261117`。
每个提示词开头都要求智能体先核对 `git rev-parse --show-toplevel` 与 `git branch --show-current`；不一致必须停止报告，不能自行切换 worktree 或到 `/mnt/d/wt-*` 开工。若你要用其他 worktree/分支，直接改提示词开头两行即可。

## 怎么用

1. **现在只做 Phase 1**：把 `PROMPT-P1.txt` 的内容发给执行智能体。它会读 `HANDOFF.md` 并按 WP-00～WP-05 开工，不需要再问你。
2. Phase 1 完成并验收后，再按顺序发 `PROMPT-P2` → `PROMPT-P3` → `PROMPT-P4` → `PROMPT-P5`。
3. **不要一次发多个提示词**；每个 Phase 独立分支/PR，开关默认关闭。
4. Phase 2–P5 有客观门禁（支付中心、合规、前置契约）。门禁不满足时，智能体应停止并报告缺失项，而不是问你。

## 阶段

| 阶段 | 内容 | 外部前置 | 本阶段第一步创建 |
|---|---|---|---|
| P1 | 营销工具 hub + 节日营销 + 商家封面 | 已就绪 | 41/CCR/76 已存在；只需更新 71/63/12 |
| P2 | 限时秒杀 | 41 号补充 | CCR-SVC-FLASH-PRICE-001 + 77 号契约 + 38/11/74/75 修订 |
| P3 | 商家券 | 支付中心阶段 2 | CCR-MKT-MERCHANT-COUPON-001 + 79 号契约 + 61/75/12 修订 |
| P4a | 裂变返券 | P3 可用 | CCR-MKT-REFERRAL-001 + 79 号契约 |
| P5 | 会员卡（储值/次卡/年卡） | 法务/财务 + 支付中心阶段 2–6 + 订单支付方式契约 | CCR-MEMBER-CARD-001 + 80 号契约 + CCR-PAY-CENTER-002 |

编号说明——`docs/04-api` 的 78、80 已被支付结算契约占用（PR #235），P3 改用 79；P4/P5 原预留的 79/80 已不可用，开工时另择空号。

## 备注

- 会员卡/商家券/秒杀/裂变是 V1 范围变更，依据 41 号补充；不要按旧 38 号排除条款回退。
- 不要用 Docker；本地用既有 MySQL/Redis + `E2eCoreLoopServer`。
- 不要让执行智能体伪造统计、余额、优惠或名额；页面数字必须有接口事实。
