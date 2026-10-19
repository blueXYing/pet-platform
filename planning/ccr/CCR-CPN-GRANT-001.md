# CCR-CPN-GRANT-001 — 券发放渠道最小实现登记与扩展项处置（65 号 §4.3 / 用户裁决 2026-10-16 + 2026-10-19）

状态：已批最小实现落地 + **强化批五项扩展已落地（用户裁决 2026-10-19 "继续扩展CCR"）** + 剩余项维持不做；2026-10-19 更新。
依据：用户裁决（2026-10-16）"做券发放渠道（⑤）——此前券只能数据库造数，运营台只能管模板，
'发券给用户'缺失导致券功能空转。这是券真正可用的前置"；用户裁决（2026-10-19）"继续扩展CCR"
——§2 原第 1–5 项按行业惯例最小实现落地，采用规则逐项登记于 §1.1 并全部披露；资料优先级
（SSOT > 最终 PRD > 技术基线 > Schema/API）；61 号 §1 V1 边界与 §3 冻结资格语义；
[CCR-ADM-A005-OPS-001](CCR-ADM-A005-OPS-001.md) 第 2 项（发放渠道原登记为不做，2026-10-16 按裁决部分放开）。
关联：`docs/04-api/65-Admin-Ops-Console-Contract-v0.1.md` §4.3（v0.4）、HTTP10 §5.13、
OpenAPI11（adminGrantCouponInstance）、错误码 12（§9 发放路由映射）、61 号、
`docs/08-engineering/21-部署开关组合清单-v1.0.md`（`pet.coupon.grant.enabled` 行 +
`pet.coupon.grant.max-per-template` 随行参数）。

## 1. 已落地（最小实现，用户裁决授权）

- **发放动作**：运营（PLATFORM_OPERATOR，ADMIN_WEB Bearer）按一个 ACTIVE 模板向一个指定用户
  发放一张 `coupon_instance`（status='AVAILABLE'，`original_expire_at=expire_at=模板
  valid_end_at`），单次终端 `X-Request-Id` 对应一张；批量形态见 §1.1 第 5 项。
- **模板可用性守卫**：按 61 号 §3 冻结前置④的"模板可用"事实原样执行——模板 `status='ACTIVE'`
  且发放时刻 `now ∈ [valid_start_at, valid_end_at]`，任一不满足 409 `COMMON_CONFLICT`；模板
  不存在 404 防枚举。发放以行锁（`SELECT ... FOR UPDATE`）与守卫同事务，与模板 offline 并发安全。
- **幂等与审计**：23 号幂等契约，绑定在本模块 `command_idempotency`（14 号共享物理表），同 ID
  同参重放返回首次回执、异参 409 `IDEMPOTENCY_KEY_CONFLICT`；审计 = `coupon_ledger` ISSUE 行
  （`request_id='CPN:GRANT:{instanceId}'` uk，remark 记 `operator={operatorId};source={channel}`），
  61 号既有词表零新增。
- **模板计数**：`coupon_template.issued_count` 同事务 +1（既有的 `issued_count>0` 编辑守卫因此
  自然生效——已发放模板关键字段不可修改，PRD §6.4.1 既有规则）。
- **RBAC**：动作码 `coupon.instance.grant`（行 14 族的发放动作，点分词法），随
  `AdminPermissionEvaluator.DEPLOYED_ACTIONS` + 三处测试拷贝登记部署；数据范围要求 ALL
  （63/64/65 号先例，CITY/MERCHANT fail-closed 403）。
- **开关**：`pet.coupon.grant.enabled` 默认 **false**（21 号清单已登记）。开关关闭时
  POST `/api/v1/admin/coupon-instances/grant` 以 503 `COMMON_DEPENDENCY_UNAVAILABLE` 失败关闭
  （61 号"提供器未装配"语义）。**默认值翻转交由开关放开代理随 35 号开关裁决统一处理，本切片不自行翻转。**
- **C 端联动零改动**：发放后实例落入既有 `GET /api/v1/c/coupons?status=AVAILABLE` 桶
  （10 号 §3.15.1），立即可见；下单冻结走 61 号既有链路（发放的模板 ACTIVE 即满足冻结前置④）。
- **已核实零破坏**：61 号 §1 "无券模板管理/发放渠道"边界中"模板管理"半边已由 65 号 v0.1（#178）
  交付，2026-10-16 放开"发放"半边；61 号的冻结/消费/释放/返券四链路代码与契约零改动
  （强化批亦零改动——冻结 CAS 天然拒绝新增语义，发放只新增 AVAILABLE 行）。

## 1.1 强化批五项扩展（用户裁决 2026-10-19 "继续扩展CCR"，逐项已落地 + 采用规则披露）

规则均为行业惯例最小实现；SSOT/PRD 未定义处按下述披露取值，未发明任何额外能力。

1. **单用户同模板持有上限——已落地**。采用规则：同用户同模板 `status IN ('AVAILABLE','FROZEN')`
   实例数达到上限后拒绝发放（409 `COMMON_CONFLICT`）；`USED`/`EXPIRED`/`VOID` 不占额（已消费
   或终态的券不回收额度）。上限可配 `pet.coupon.grant.max-per-template`，**默认 10**；配置非法
   （<1）启动失败（fail-fast）。守卫在模板行锁事务内执行（与并发发放对同模板天然串行，无竞态）。
2. **发放频控——已落地**。采用规则（按裁决原样）：**同操作者对同用户同模板 60 秒滑动窗内重复
   发放 409**；实现为 DB 计数——ISSUE 台账行 `remark` 的 `operator={operatorId};` 审计前缀 +
   join `coupon_instance`（模板/用户过滤 + `created_at > now-60s`），不引 Redis、不加新表；
   不同用户/不同操作者互不共享窗口；60 秒边界外（created_at ≤ now-60s）恢复可发。守卫同在
   模板行锁事务内执行。幂等重放不经过守卫（重放返回首次回执），频控不误伤重试。
3. **`total_stock` 库存扣减——已落地**。采用规则：模板 `total_stock` 非空时 `issued_count <
   total_stock` 才可发，售罄 409 `COMMON_CONFLICT`（回执文案"模板库存已用尽"）；`total_stock=NULL`
   恒为不限量（2026-10-16 最小语义保留，存量模板零影响）。扣减 = 既有 `issued_count+1` 语句
   条件化（`AND (total_stock IS NULL OR issued_count < total_stock)`），与实例/台账写入同事务
   同锁；行锁已串行化并发发放，语句条件为纵深防御。**无 Schema 变更**（列已存在，V1 建模板
   仍恒 NULL；运营侧无改库存入口，规则面零扩大）。
4. **目标用户存在性校验——已落地**。采用规则：内核在事务前经 boot 装配的 user 域只读存在性
   端口（新增 `pet-user-api` `UserActiveStatusApi`，`pet-user-biz` `UserActiveStatusApiImpl`
   单条主键只读查询；券域仍不依赖 user biz——模块本地端口 `AdminCouponUserFactsPort` + boot
   适配，沿 `AdminCouponAuthorizationPort` 先例）证实 `user_account` 行存在；**任意状态
   （ACTIVE/FROZEN/CANCELED）均算存在**——仅消灭"无主券"，不引入可用性政策（冻结用户禁发
   无授权口径，登记 §2 不做）；不存在 404 `COMMON_NOT_FOUND`（与模板不存在同码防枚举）；
   存在性读不可用 503 失败关闭。批量按用户逐个证实。
5. **批量发放——已落地**。采用规则：**同一路由同动作码**，body 以形态区分——单发
   `{templateId, userId}` 与批量 `{templateId, userIds}` 恰现其一（否则 400）；`userIds` 非空、
   ≤100（**上限 100，服务端 + 控制器双重校验**）、去重（重复 400，不静默去重）。**每用户独立
   幂等**：批量键在单发模板作用域键基础上插 `:u:{userId}` 段，每用户独立 `command_idempotency`
   绑定、同参重放 GRANTED 行回执稳定（instanceId/expireAt 不漂移）；**每用户独立守卫与独立
   事务**（服务内逐用户 `REQUIRES_NEW` 事务，外层仍单请求、一次授权）：单用户业务失败（404
   用户不存在 / 409 持有上限·频控·售罄·模板守卫）只落自身回执行（status='REJECTED' + code/
   message 透传既有码），**不回滚已成功用户**；授权失败/存在性读不可用/提交结果未知等基础
   设施失败终止整个命令（已成功用户保持，客户端同 X-Request-Id 重放按逐用户幂等续传）。
   回执按请求顺序逐用户一行（GRANTED 带 instanceId/expireAt/occurredAt，REJECTED 带 code/
   message），聚合 grantedCount/rejectedCount；批量回执的顶层 `occurredAt` 为本次应答组装时刻
   （幂等稳定域在逐用户明细）。**不引入发放批次实体/异步任务通道**（V1 最小同步语义，≤100
   同步逐用户提交可接受；批次实体留 §2）。

## 2. 待裁/明确不做（维持登记，本切片一律未实现）

1. **发放通知用户**：发放成功不产生任何站内信/微信通知（16 号消息目录无此类型，通知域契约
   不在本切片；强化批明确不做）。
2. **"领取后 N 天"有效期模式**：维持 CCR-ADM-A005-OPS-001 第 3 项登记（需 Schema 新列 +
   61 号实例到期语义扩展）；发放的实例有效期一律继承模板固定窗口。
3. **发放后撤回/作废补偿**：作废已由 65 号 v0.3 §4.4（[CCR-CPN-VOID-001](CCR-CPN-VOID-001.md)）
   单独裁决；本渠道"作废后是否补偿"维持用户未裁——最小实现=不补偿。
4. **券活动/批次（campaign 族）与导入路径**：维持不做；批量发放的"发放批次实体/按标签圈选/
   异步任务通道"亦不在本次（§1.1 第 5 项为同步逐用户最小语义）。
5. **冻结用户禁发/可用性政策**：存在性校验只消灭无主券；对 FROZEN/CANCELED 用户的发放限制
   无授权口径，未实现（§1.1 第 4 项披露）。

## 3. 已部署动作码（本切片登记，DEPLOYED_ACTIONS + 测试拷贝同步）

`coupon.instance.grant`（用户裁决 2026-10-16；行 14 族的发放动作，提案矩阵无对应提案码，
按 AUTH 点分词法新造并登记；void/return 仍不登记）。强化批零新增动作码——批量与单发同码
`coupon.instance.grant`、同 ALL 数据范围。
