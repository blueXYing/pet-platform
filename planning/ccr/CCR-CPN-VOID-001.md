# CCR-CPN-VOID-001 — 券作废渠道最小实现登记与待裁扩展项（65 号 §4.4 / 用户裁决 2026-10-16 最小授权 36 号）

状态：已批最小实现落地 + 待裁项登记；2026-10-16。
依据：用户裁决 2026-10-16 ⑥"治理写动作最小授权"= 两项（商家停用治理面入口、券作废），员工处置等其他写动作未授权不做；登记文件 [36 号](../docs/01-prd/36-治理写动作最小授权裁决补充-v1.0.md)；资料优先级（SSOT > 最终 PRD > 技术基线 > Schema/API）；61 号实例生命周期；[CCR-ADM-A005-OPS-001](CCR-ADM-A005-OPS-001.md) 第 1 项（单张作废原登记为不做，本次按裁决放开，批量/风控冻结/解冻/返还维持不做）。
关联：`docs/04-api/65-Admin-Ops-Console-Contract-v0.1.md` §4.4、HTTP10 §5.13、61 号、`docs/03-database/06-核心数据库Schema-v0.1.sql`（status/action 词表注记）、`docs/08-engineering/21-部署开关组合清单-v1.0.md`（`pet.coupon.void.enabled` 行）。

## 1. 已落地（最小实现，用户裁决授权）

- **作废动作**：运营（PLATFORM_OPERATOR，ADMIN_WEB Bearer）作废指定**单张** `status='AVAILABLE'`
  的 `coupon_instance`，落点新终态 `status='VOID'`；单次终端 `X-Request-Id` 对应一张——批量作废不存在。
- **状态守卫**：`USED`（已核销）/`FROZEN`（被在途订单冻结）/`RISK_FROZEN`/`EXPIRED` 一律
  409 `COMMON_CONFLICT`——FROZEN 被订单锁着的券不许作废，防破坏在途单；实例不存在 404 防枚举。
  守卫 = 行锁（`SELECT ... FOR UPDATE`）读状态 + CAS 写（`WHERE status='AVAILABLE'`）同事务，
  与 38 号下单冻结（持店 guard 后 CAS 同一状态位）并发安全。
- **幂等与审计**：23 号幂等契约，绑定本模块 `command_idempotency`（14 号共享物理表），同 ID 同参
  重放返回首次回执、异参 409 `IDEMPOTENCY_KEY_CONFLICT`；审计 = `coupon_ledger` VOID 行
  （`request_id='CPN:VOID:{instanceId}'` uk——一实例至多一 VOID 行由终态结构性保证；remark 记
  `operator={operatorId};source={channel};reason={reason}`，reason 必填 10..200 码点，分号/换行消毒）。
  词表注记：`coupon_instance.status` 增 `VOID`、`coupon_ledger.action` 增 `VOID`（无 DDL，注记披露）。
- **RBAC**：动作码 `coupon.instance.void`（行 14 提案 `coupon-instance.void` 的点分落地），随
  `AdminPermissionEvaluator.DEPLOYED_ACTIONS` + 三处测试拷贝登记部署；数据范围要求 ALL
  （63/64/65 号先例，CITY/MERCHANT fail-closed 403）。
- **开关**：`pet.coupon.void.enabled` 默认 **false**（21 号清单已登记）。开关关闭时
  POST `/api/v1/admin/coupon-instances/{instanceId}/void` 以 503 `COMMON_DEPENDENCY_UNAVAILABLE`
  失败关闭（61 号"提供器未装配"语义）。**默认值翻转交由开关放开代理随 35 号开关裁决统一处理，
  本切片不自行翻转。**
- **C 端联动零改动**：C 端读侧三桶 AVAILABLE/USED/EXPIRED（10 号 §3.15.1 + 61 号 §7 D2）不含
  VOID——作废即时从列表消失、详情同形 404；冻结 CAS 天然拒绝 VOID 行；返券恢复只找 USED 行；
  释放只走 FROZEN；VOID 行不参与任何自动化链路。`coupon_template.issued_count` 只增不减（发放
  事实历史，作废不改发放流水）。
- **事件族零新增**：同步内核写，不经 Outbox/AsyncTask（65 号 v0.2 披露 7 同口径）。

## 2. 待裁项（以下一律未实现，待用户逐项裁决）

1. **作废后是否补偿**（本 CCR 挂起的主待裁项）：用户已持有的券被作废后要不要赔（补发/换券/积分
   折抵）。SSOT 没写，36 号裁决未涉及；**最小实现 = 不补偿**（作废即终态，无任何自动/手动补偿
   路径）。如需补偿须先裁决补偿形态与资金/券来源。
2. **作废通知用户**：PRD §7.3 运营分支要求"作废需通知用户"；16 号消息目录无此类型、通知域契约
   不在仓——本次作废不发任何通知，通知能力待通知域切片另裁。
3. **批量作废**（按模板/按用户圈选）：36 号明确仅单张；批量需批次实体与异步任务通道，未授权不做。
4. **风控冻结/解冻**（`RISK_FROZEN` 落点与解除）：PRD §7.3 需风控核查事实契约，不在仓，维持不做。
5. **手动返还**（矩阵行 14 return）：维持 [CCR-ADM-A005-OPS-001](CCR-ADM-A005-OPS-001.md) 第 1 项登记不做。

## 3. 已部署动作码（本切片登记，DEPLOYED_ACTIONS + 测试拷贝同步）

`coupon.instance.void`（用户裁决 2026-10-16 36 号；行 14 提案 `coupon-instance.void` 点分落地；
return 仍不登记）。
