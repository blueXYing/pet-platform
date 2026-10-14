# CCR-CPN-LIFECYCLE-RULES-001：优惠券生命周期规则冻结缺口登记（CPN-001/002 实现批）

状态：REGISTERED（2026-10-14，CPN-001/CPN-002 实现批提出）。依据根 AGENTS.md"SSOT 没写的规则走 CCR
记录，不自行发明"登记如下裁决缺口与本批采取的最小执行口径；权威契约见
[61 号](../../docs/04-api/61-Coupon-Lifecycle-Contract-v0.1.md)。若 Contract Owner 后续改判，按新裁决迁移。

## §1 rule_json 结构冻结（D1 收口）

CCR-C006 D1 遗留"rule_json 结构待 CPN-001 冻结"。SSOT/PRD 均未定义结构。本批按 CCR-C006 投影词表
冻结为四键：`amountOff`（必填，>0，两位小数字符串）、`thresholdAmount`（选填，null=无门槛）、
`scopeSummary`/`typeLabel`（展示文本）。V1 资格判定只用前两键；解析失败成员按缺失处理。

## §2 PRD"售后存在退款不返券"与 SSOT §9.1 的冲突

C 端 PRD §5.1.21："订单售后存在退款时，该订单使用的抵扣券不返还（已核销不退）"；SSOT §9.1（资料优先级
最高）："全额退款 + 原券仍处有效期 → 恢复可用"，§9.2 仅排除部分退款；事件目录 08 号 §8 消费者要求同
SSOT。**按资料优先级以 SSOT 执行**：返券判定只看 refundType（FULL 返 / PARTIAL 不返）与 LATE_PAYMENT_TIMEOUT
NOOP，不按退款来源（售后/商家同意/服务前/拒单）区分。本条为对 PRD 条款的显式覆盖登记，待 PRD 修订轮收口。

## §3 类目级适用范围（scope）V1 仅展示级

PRD §5.1.21"每张券设有适用范围…仅在该类目服务下单时可使用"。schema 无结构化 scope 键（rule_json 冻结
四键中 scopeSummary 仅为展示文本），且券模板管理不在 CPN-001/002 Issue Scope。本批 V1 不做类目拦截，
全部已存在券实例按全场通用处理；结构化 scope 键与模板管理 Issue 一并交付后启用。

## §4 折后应付必须大于零

PRD 支付页含"已优惠"语义但未定义 `amountOff >= 服务价` 行为；支付渠道链路（40/41 号）不可收 0 元单
（`paidAmount signum > 0` 硬校验）。本批口径：`amountOff < orderAmount` 作为冻结资格之一，违反即
409 `COUPON_NOT_AVAILABLE`（券不可用），不产生 0 元/负价订单，不引入部分抵扣/找零规则。

## §5 冻结有效期与槽位锁定一致

PRD："临时冻结…冻结有效期与槽位锁定一致（10 分钟）"。实现口径：`freeze_expire_at = 订单
payment_expire_at`（= SCH hold 截止值，实际由排期配置决定，当前 10 分钟）。超时关闭释放由 39 号关闭
事务内联完成，不新增独立过期 worker。

## §6 释放后的券立即恢复可用

SSOT §二十二"不抢回、不重新冻结已经释放的优惠券"针对迟到支付场景的补偿行为。本批明确正常支付超时
释放语义：FROZEN → AVAILABLE（未过期）或 EXPIRED（冻结期间过期，测试矩阵 CPN-003"按到期状态处理"），
不设冷却期，不改 `expire_at`（24 小时延长仅属退款返券规则）。
