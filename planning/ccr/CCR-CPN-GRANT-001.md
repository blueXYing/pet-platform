# CCR-CPN-GRANT-001 — 券发放渠道最小实现登记与待裁扩展项（65 号 §4.3 / 用户裁决 2026-10-16）

状态：已批最小实现落地 + 扩展项待裁；2026-10-16。
依据：用户裁决（2026-10-16）"做券发放渠道（⑤）——此前券只能数据库造数，运营台只能管模板，
'发券给用户'缺失导致券功能空转。这是券真正可用的前置"；资料优先级（SSOT > 最终 PRD > 技术基线
> Schema/API）；61 号 §1 V1 边界与 §3 冻结资格语义；[CCR-ADM-A005-OPS-001](CCR-ADM-A005-OPS-001.md)
第 2 项（发放渠道原登记为不做，本次按裁决部分放开）。
关联：`docs/04-api/65-Admin-Ops-Console-Contract-v0.1.md` §4.3、HTTP10 §5.13、61 号、
`docs/08-engineering/21-部署开关组合清单-v1.0.md`（`pet.coupon.grant.enabled` 行）。

## 1. 已落地（最小实现，用户裁决授权）

- **发放动作**：运营（PLATFORM_OPERATOR，ADMIN_WEB Bearer）按一个 ACTIVE 模板向一个指定用户
  发放一张 `coupon_instance`（status='AVAILABLE'，`original_expire_at=expire_at=模板
  valid_end_at`），单次终端 `X-Request-Id` 对应一张——批量发放不存在。
- **模板可用性守卫**：按 61 号 §3 冻结前置④的"模板可用"事实原样执行——模板 `status='ACTIVE'`
  且发放时刻 `now ∈ [valid_start_at, valid_end_at]`，任一不满足 409 `COMMON_CONFLICT`；模板
  不存在 404 防枚举。发放以行锁（`SELECT ... FOR UPDATE`）与守卫同事务，与模板 offline 并发安全。
- **幂等与审计**：23 号幂等契约，绑定在本模块 `command_idempotency`（14 号共享物理表），同 ID
  同参重放返回首次回执、异参 409 `IDEMPOTENCY_KEY_CONFLICT`；审计 = `coupon_ledger` ISSUE 行
  （`request_id='CPN:GRANT:{instanceId}'` uk，remark 记 `operator={operatorId};source={channel}`），
  61 号既有词表零新增。
- **模板计数**：`coupon_template.issued_count` 同事务 +1（既有的 `issued_count>0` 编辑守卫因此
  自然生效——已发放模板关键字段不可修改，PRD §6.4.1 既有规则）；`total_stock` V1 不扣减（见 §2）。
- **RBAC**：动作码 `coupon.instance.grant`（行 14 族的发放动作，点分词法），随
  `AdminPermissionEvaluator.DEPLOYED_ACTIONS` + 三处测试拷贝登记部署；数据范围要求 ALL
  （63/64/65 号先例，CITY/MERCHANT fail-closed 403）。
- **开关**：`pet.coupon.grant.enabled` 默认 **false**（21 号清单已登记）。开关关闭时
  POST `/api/v1/admin/coupon-instances/grant` 以 503 `COMMON_DEPENDENCY_UNAVAILABLE` 失败关闭
  （61 号"提供器未装配"语义）。**默认值翻转交由开关放开代理随 35 号开关裁决统一处理，本切片不自行翻转。**
- **C 端联动零改动**：发放后实例落入既有 `GET /api/v1/c/coupons?status=AVAILABLE` 桶
  （10 号 §3.15.1），立即可见；下单冻结走 61 号既有链路（发放的模板 ACTIVE 即满足冻结前置④）。
- **已核实零破坏**：61 号 §1 "无券模板管理/发放渠道"边界中"模板管理"半边已由 65 号 v0.1（#178）
  交付，本次放开"发放"半边；61 号的冻结/消费/释放/返券四链路代码与契约零改动。

## 2. 待裁扩展项（以下一律未实现，路由/字段/页面均不存在，待用户逐项裁决）

1. **单用户同模板持有上限**：SSOT/PRD 均未定义同一用户可持有同一模板多少张；当前不设限
  （重复发放每次生成新实例）。若设上限需 61 号冻结语义外的查询+守卫。
2. **发放频控**：单用户/单模板/全局时间窗限频，无授权口径，未实现。
3. **批量发放**（按用户清单/按标签圈选）：裁决明确"单次一张"最小实现，批量不在本切片；
  需发放批次实体与异步任务通道。
4. **`total_stock` 库存扣减**：模板库存列 V1 仍零管理（建模板时恒为 NULL=不限量）；发放不扣
  库存、不校验售罄。启用库存需 Schema 语义扩展 + 售罄守卫裁决。
5. **目标用户存在性校验**：券域不依赖 user 域（biz 不依赖 biz），发放不验证 userId 是否真实
  存在（仅 Snowflake 词法校验）；错误 ID 的券实例成为无主券。需用户域暴露存在性读契约后另裁。
6. **"领取后 N 天"有效期模式**：维持 CCR-ADM-A005-OPS-001 第 3 项登记（需 Schema 新列 +
  61 号实例到期语义扩展）；本切片发放的实例有效期一律继承模板固定窗口。
7. **发放后撤回/作废**：维持 CCR-ADM-A005-OPS-001 第 1 项登记（SSOT §9 无运营直改用户券状态
  授权）；发放的实例只能走既有冻结/核销/释放/过期生命周期。
8. **发放通知用户**：发放成功不产生任何站内信/微信通知（16 号消息目录无此类型，通知域契约不在本切片）。

## 3. 已部署动作码（本切片登记，DEPLOYED_ACTIONS + 测试拷贝同步）

`coupon.instance.grant`（用户裁决 2026-10-16；行 14 族的发放动作，提案矩阵无对应提案码，
按 AUTH 点分词法新造并登记；void/return 仍不登记）。
