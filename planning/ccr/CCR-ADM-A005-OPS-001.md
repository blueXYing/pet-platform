# CCR-ADM-A005-OPS-001 — 运营治理台二期未授权动作登记（A-005 / 65 号契约）

状态：登记在案（本切片未实现，待用户裁决）；2026-10-16。
依据：AGENTS.md 资料优先级（SSOT > 最终 PRD > 技术基线 > Schema/API）；63 号 v0.2 处置动作先例
（33 号裁决：处置写动作需用户裁决授权，治理写动作无等效授权不实现）；任务书指令"SSOT 没授权的
运营动作（如直接改用户券状态）走 CCR 登记"。
关联：`docs/04-api/65-Admin-Ops-Console-Contract-v0.1.md` §6、HTTP10 §5.13、权限提案矩阵
（HTTP10 §运营角色行 13/14/15/21/23/24/26/27）。

## 登记范围（以下动作本切片一律未实现，路由/动作码/页面均不存在）

1. **券实例写动作**（矩阵行 14 的 `coupon-instance.void` / `coupon-instance.return`，点分拼写
   `coupon.instance.void` / `coupon.instance.return` 未登记部署）：单张作废、批量作废、风控冻结、
   解冻、手动返还。理由：SSOT §9 只封板退款返券自动化规则（全额返/部分不返），未授权运营直接
   改用户券状态；PRD §7.3 运营分支要求"作废必须通知用户/风控核查通过解冻"，通知域与风控事实
   契约均不在仓。影响：券实例台账页只读（65 号 §4.2），页面无任何实例动作按钮（测试钉
   `frontend-admin/tests/ops.spec.ts` 券实例只读用例）。
   **【2026-10-16 更新】单张作废已按用户裁决"治理写动作最小授权"（36 号）放开为 65 号 §4.4
   最小实现（动作码 `coupon.instance.void` 已登记部署，开关 `pet.coupon.void.enabled` 默认关，
   仅 AVAILABLE 可作废、不通知、无补偿），见 [CCR-CPN-VOID-001](CCR-CPN-VOID-001.md) §1；
   批量作废/风控冻结/解冻/手动返还维持本项登记不做。**
2. **券活动/批次/发放渠道**（矩阵行 13 的 campaign 族）：61 号 V1 边界明确"无券模板管理/发放渠道
   （券实例仅运营侧数据，导入路径 V1 不做）"；本切片交付模板管理面后，`total_stock/issued_count`
   仍无发放入口，编辑守卫以 `issued_count>0` 为"已被引用"事实。
   **【2026-10-16 更新】发放渠道已按用户裁决以最小实现放开（单用户单次按 ACTIVE 模板发一张，
   POST /api/v1/admin/coupon-instances/grant，动作码 coupon.instance.grant，开关
   `pet.coupon.grant.enabled` 默认关），见 [CCR-CPN-GRANT-001](CCR-CPN-GRANT-001.md) §1；
   批量发放/单用户持有上限/频控/库存扣减/用户存在性校验仍登记待裁（同文件 §2）。券活动/批次
   （campaign 族）与"导入路径"维持不做。**
3. **券模板"领取后 N 天"有效期模式**：需 Schema 新列 + 61 号实例到期语义扩展；V1 仅固定日期模式。
4. **百科分类/品种新增与删除、导出**：~~分类枚举五值 PRD 不可自定义新增（62 号同口径）；品种新增
   需运营侧封面图上传通道（V1 私有素材通道仅售后证据，无运营素材面）；导出不在 V1 已批准范围
   （63 号 v0.2 同口径）~~【**2026-10-19 用户裁决"继续扩展CCR"放开并已落地（65 号 §3 v0.4 百科节）**：
   分类/品种新增走与编辑同款守卫（14 号幂等绑定 + 授权端口重验 + uk 防重），字典枚举开放
   （词法 2..16，born ENABLED/OFFLINE，无删除路由，封面仍 URL 维护）；CSV 导出为只读字典数据、
   内核有界 ≤1MiB（413 `ENCYCLOPEDIA_EXPORT_TOO_LARGE` 新码）；**品种删除仍不做**】。
5. **资金台账专项面**（矩阵行 10 `payment-ledger.read` 未登记部署）：PRD §6.11.1 明示"详见《资金
   管理 V1.4》"，该文件不在仓、字段级口径缺失；既有资金事实读侧由 63 号（退款执行事实）与订单
   金额投影覆盖，不重复发明。
6. **统一审计日志面**（HTTP10 §5.8 草案 `GET /admin/audit-logs`；矩阵行 27 `audit-log.read` 未登记
   部署）：审计行分散于 63 号 v0.2 三张处置审计表 + AUTH 文件 sink，统一存储/口径未决。
7. **后台账号/角色管理写面**（矩阵行 26 全族 `operator-account.*`/`role.*`/`grant.*` 未登记部署）：
   AUTH owner 专项契约；V1 单运营种子账号（26 号 Schema），无账号管理 HTTP 面。
8. **消息公告/通知模板面**（矩阵行 24 `announcement.*`/`notification-template.*` 未登记部署）：
   通知域公告广播无事件契约与消费方。
9. **系统参数运行时写配置**（矩阵行 23 `system-parameter.configure` 未登记部署）：V1 配置仅部署期
   env（21 号清单）；`GET /admin/settings/runtime` 只读输出该清单登记开关及实时状态，不新造配置
   机制。已批准的运行时可配置参数（积分任务量，58 号）走既有 `points.task.config.write` 面。

## 已部署动作码（65 号切片登记，DEPLOYED_ACTIONS + 测试拷贝同步）

`encyclopedia.read` / `encyclopedia.manage` / `encyclopedia.publish` / `breed.read` / `breed.manage`
（矩阵行 21 原拼写即合规）；`coupon.template.read` / `coupon.template.manage` / `coupon.instance.read`
（行 13/14 提案 `coupon-template.*`/`coupon-instance.*` 连字符拼写的点分落地，与
`service.force.offline`/`refund.channel.query` 同款实现期拼写更正，PR 披露）；`points.ledger.read`
（行 15 族积分监管读侧）；`system.parameter.read`（行 23 提案 `system-parameter.read` 点分落地）。
**【2026-10-16 增补】`coupon.instance.grant`（发放渠道最小实现，用户裁决，见
[CCR-CPN-GRANT-001](CCR-CPN-GRANT-001.md) §3）；`coupon.instance.void`（作废渠道最小实现，
用户裁决"治理写动作最小授权"36 号，见 [CCR-CPN-VOID-001](CCR-CPN-VOID-001.md) §3）。**

## 裁决请求（如需推进，请逐项批准）

以上 1~9 项各自需要：①用户裁决授权该动作；②对应业务契约（通知/风控/资金/审计存储口径）补齐；
③动作码按矩阵行登记部署。在裁决前，本平台的运营杠杆为：券模板停用（不再发放新资格）、券发放
（最小实现已放开，CCR-CPN-GRANT-001）、券作废（最小实现已放开，36 号裁决，
CCR-CPN-VOID-001——仅 AVAILABLE 单张、不通知、无补偿）、百科上下线（C 端即时可见性）、
售后裁决（51 号）、63 号 v0.2 处置动作（33 号裁决）及商家治理页停用入口（36 号裁决，
复用 63 号路由）。
