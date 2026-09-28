# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 14.0
UPDATED_AT: 2026-09-28
CURRENT_PHASE: W3_INTEGRATION_REVIEW
CURRENT_STATUS: PAYMENT_FOUNDATION_INTERNAL_REVIEW
VERIFIED_BASELINE: develop 6938bd7b11dea4002ee9162505027689a4abdd6b（PR86、PR87 已按用户批准顺序合并；合并后 CI36369201751 成功）
NEXT_PHASE: 审阅支付基础；补实际发起/查单/关单与短期参数方案、迟到退款执行及其余消费者，再做真实渠道验收
NEXT_PHASE_APPROVED: YES（用户明确授权无正式拉卡拉参数继续推进内部实现；新 PR 合并及生产启用未授权）

## 当前事实

- PR86 原子创建和 PR87 选窗/到期保护已经合入；原工作目录用户改动保持。全部新开发位于独立 worktree。
- 新增内部 PaymentPreparationApi：真实 USER/ORDER 当前事实、二进制 requestKey 原目标绑定、每订单固定付款单与原付款截止；不冒充 createPayment 的微信参数成功响应。
- 拉卡拉协议适配为离线纯函数：请求/响应五行签验、异步通知三行签验、预下单/查询/关单构包和严格结果解析。无远程交易、内置商户或默认生产私钥。
- 已验签成功在同店 guard 下持久化 PAYMENT 真实付款事实与 Outbox；ORDER 消费再次核对权威事实，在同事务记录支付、确认预约和发布 OrderPaid，消费日志与业务同提交。
- 迟到付款保持 CANCELED/EXPIRED，记录实际付款并只发布一次迟到退款意图。未执行真实退款；自动接单、券与通知消费者尚待交付。
- 缺参数失败关闭，所有新增开关默认关闭；没有消费者发起支付或外部回调 HTTP。SQL40 仅隔离测试库执行，历史字段不自动回填。
- 作者协议/ORDER/SCH专项和独立主路径测试已有通过记录；最终全量门禁与负向回归以本轮 PR 验证说明为准。

## 下一步

1. 完成并审阅本轮支付基础，按用户批准再合并。
2. 按23号审查实际发起支付的短期微信参数、幂等重放和失效方案；接真实查单/关单协调，支付 UNKNOWN 继续保留占用。
3. 补迟到支付退款执行与三十分钟自动接单，接优惠券/备注审核及需要的事件消费者。
4. 补正式商户/终端/证书和小程序关联，核实渠道时间、金额语义后完成真实微信支付验收；不得将测试参数或本地签名用例当真实收款。

## 证据

- [40号契约](docs/04-api/40-Payment-Foundation-Contract-v0.1.md)
- [SQL40](docs/03-database/40-Payment-Foundation-Schema-v0.1.sql)
- [CCR实施回执](planning/ccr/CCR-W2-API-001/payment-foundation-implementation.md)
- [运行与验证说明](planning/progress/2026-09-28/payment-foundation/RUNBOOK.md)
- [上一状态](planning/history/WORK_STATE_BEFORE_20260928_PAYMENT_FOUNDATION.md)
