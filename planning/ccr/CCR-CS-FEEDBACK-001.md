# CCR-CS-FEEDBACK-001 — 反馈工单与人工客服承载最小实现登记（67 号 / 用户裁决 2026-10-19 "全做"）

状态：已批最小实现落地 + 扩展项待裁；2026-10-19。**v0.2 2026-10-19 用户裁决"继续扩展CCR"：§3.1/§3.2 已放开并落地（图片附件 + 运营处置面 + C 端回复可见），见文末 §5。**
依据：用户裁决（2026-10-19 收尾批次）"帮助与客服 2 帧（5.1.25）：……PRD 若定义为'联系方式展示+电话唤起'
则做实；若要人工客服系统则 V1 明确不做 IM，走最小：帮助中心静态内容页+客服联系方式；SSOT 缺口走 CCR"及
"意见反馈（5.1.28 投诉类）：C 端提交反馈工单+我的反馈列表；后端工单域（侦查放 pet-user 还是独立，按架构惯例）"；
SSOT §17（AI 客服不做/人工客服做）、§21 第 4 项"人工客服具体承载方式"（明确未决）；AGENTS 硬规则
"V1 无 AI 客服、无用户私信 IM、无直播"；C 端 PRD §5.1.25/§5.1.28。
关联：`docs/04-api/67-Feedback-Ticket-Contract-v0.1.md`、
`docs/03-database/67-Feedback-Ticket-Schema-v0.1.sql`、HTTP10 §3.18（本 PR 同步）、
`docs/08-engineering/21-部署开关组合清单-v1.0.md`（`pet.feedback.http.enabled` 行）、
[CCR-USR-DELETION-001](CCR-USR-DELETION-001.md)（同批次设置枢纽切片）。

## 1. 工单域归属（用户裁决交侦查定）

落 **pet-customer-service-api/biz**（技术基线模块清单既有客服域骨架，全 package-info 占位，本次首次落业务），
不落 pet-user——PRD 5.1.28 投诉类为独立工单域（运营权限矩阵已有 `complaint.*` 动作族占位），
与用户档案/认证职责正交；售后类（订单售后工单）继续归既有 aftersale 域（50/51 号），不并入。

## 2. 已落地（最小实现，用户裁决授权）

- **反馈工单 C 端三路由**（提交/我的列表/详情），字段口径=PRD 5.1.28 投诉类（四类型/联系方式选填/500 字）；
  状态词表冻结 PRD 五值，V1 恒 `PENDING`（无运营处置面，无迁移路由）。
- **幂等/审计**：23 号 §5 幂等（模块本地 `feedback_command_idempotency`，字段结构=SQL14 原样——
  沿"不从本模块访问他模块命令幂等表"既有裁决口径）；`feedback_ticket_event` append-only 审计
  （V1 仅 SUBMITTED 行）。
- **人工客服承载（5.1.25，SSOT §21 未决项的最小兜底）**：帮助与客服页 = 静态 FAQ 内容页（分组+展开，
  文案按平台已批规则改写，见 §4）+ 客服联系方式卡（热线号码展示 + `wx.makePhoneCall` 电话唤起）。
  **不建会话系统、无 IM、无 AI、无工单化人工咨询**——在线人工客服系统超出 V1（无 IM 硬规则），
  "承接订单/退款等咨询时自动带入上下文"（PRD 5.1.25）不在最小实现（无承载系统即无上下文注入）。
- **热线号码**：前端常量占位 `400-000-0000`（代码注释明示待运营配置）；V1 无运营配置读侧
  （系统设置运行时写无授权，65 号 §1 同口径），号码定稿后随配置读侧立项改挂。
- **内容存储口径（隐私红线披露）**：SSOT 无反馈内容脱敏条款（全文 0 命中）→ 最小口径=
  **原文存储 + owner-scoped 读 + 列表不回传 contact（仅详情返回本人自己的）**；无内容安全审核
  （评价域审核通道不外借）；无导出面。后续运营处置面/导出面立项前需先过隐私裁决。
- **频控**：单用户每分钟 5 单（模块内易失计数器，防滥用毛刺；非安全边界）。
- **开关**：`pet.feedback.http.enabled` 默认 **false**，硬前置 `pet.auth.c.enabled`（21 号清单登记）。

## 3. 待裁扩展项（以下一律未实现，待用户逐项裁决）

1. **反馈图片附件**（PRD 最多 6 张每张 5MB）：私有资产上传通道（31 号）为售后证据专用；反馈附件需
   扩展 31 号或新立通道，另立项。V1 文本工单。
2. **运营处置面**（assign/handle/decide 路由 + 状态迁移 + 处理结论/回复）：权限矩阵占位不动；
   运营台切片另立项。启用前 `PROCESSING/WAITING_SUPPLEMENT/RESOLVED/CLOSED` 四状态不可达。
3. **处理时效 24 小时提醒**（PRD"运营首次响应目标 24小时，超时系统提醒"）：无运营面无提醒通道，不做。
4. **内容安全审核**：提交即入库可见（仅本人）；若需机审另立通道。
5. **提交回执通知**：不产生站内信/订阅消息（16 号消息目录无此类型）。
6. **人工客服真实承载**（微信客服会话组件 / 第三方客服系统 / 工单化人工咨询 + 上下文带入）：
   SSOT §21 第 4 项待用户裁决后另立项。
7. **统一工单列表（投诉+售后）**：PRD 5.1.28 为商家/运营端需求，C 端不做混合列表（售后已有独立列表）。

## 4. 帮助与客服页文案改写（设计稿 → 平台已批规则）

| 设计稿原文 | 页面实际文案 | 依据 |
|---|---|---|
| 在线客服 / 7×24小时为您服务 | 客服热线（人工服务） | V1 无 AI/IM，人工客服=热线承载 |
| FAQ"如何取消订单？服务开始前2小时可免费取消，之后取消可能产生手续费" | 「订单暂不支持自行取消。未支付订单超时自动关闭；已支付订单可在服务开始前于订单详情申请退款，服务开始前退款自动全额原路退回。」 | V1 无 C 端普通取消入口（10号 §4.3）+ 服务前退款自动全额（AGENTS 硬规则） |
| FAQ 其余七问 | 按既有已批功能如实改写（评价=核销后 30 天、手机号=微信快捷验证换绑、积分=只赚不花、优惠券=下单抵扣、服务者=平台审核入驻、服务问题=联系客服/发起售后） | 各域已批契约 |

## 5. v0.2 扩展落地登记（2026-10-19 用户裁决"继续扩展CCR"；本 PR 同步）

逐项对应 §3 待裁扩展项，标记已落地口径：

1. **反馈图片附件（§3.1）——已落地**。31 号私有资产上传通道 purpose 枚举最小扩展：
   `FEEDBACK_ATTACHMENT` 加入 `UPLOAD_PURPOSES` 与 C 端 `POST /api/v1/c/private-assets` 用途集
   （同一不可变上传/扫描/收敛管线，无新错误码、无 31 号 Schema 变更）。工单创建体增选填
   `attachmentAssetIds`（0..6 个 Snowflake String，PRD 5.1.28"最多 6 张"上界；重复/词法 400），
   内核经 31 号 `resolveOwned` 端口重证=提交人所有+purpose+READY（不满足 400/404），同事务落
   `feedback_ticket_attachment` 绑定行（67 号 Schema v0.2 新表，ticket+asset uk）；附件列表属于
   创建幂等相等性事实。**披露**：①PRD"每张 5MB"未单独钳制，沿管线 10MiB 上界；②图片回显
   （owner/运营两侧）需签名 URL 通道，V1 不做、仅 id 级回显（CCR 待裁）；③31 号切片未装配时
   附件提交 503 失败关闭，纯文本工单不受影响（窄端口 `FeedbackAssetProofPort` boot 适配）。
2. **运营处置面（§3.2）——已落地（最小口径）**。路由：`GET /api/v1/admin/feedback-tickets`
   （page/pageSize/status?/type?；status 仅 `PENDING|PROCESSING|RESOLVED`——`WAITING_SUPPLEMENT/
   CLOSED` 维持不可达）+ `GET /{ticketId}`（**工单内容/联系方式运营侧展示原样**=用户裁决脱敏纪律；
   另含 owner userId 与附件 id 列表；未知 404 防枚举）+ `POST /{ticketId}/process`（PENDING→
   PROCESSING，无 body）+ `POST /{ticketId}/resolve`（PROCESSING→RESOLVED，body `{reply}`
   1..1000 码点）。守卫：真实 ADMIN_WEB 会话 + `pet.admin.ops.http.enabled` + 动作码快照校验 +
   模块本地授权端口（`FeedbackOpsAuthorizationPort`，boot 适配 admin 授权 owner，决断不可用
   503 fail-closed）当前读重验。写路由幂等：namespace `feedback.process`/`feedback.resolve`
   （scope OPS_ALL，模块本地绑定表），同参重放首回执/异参 409；乐观锁 CAS（非预期状态 409）；
   同事务 `feedback_ticket_event` 审计行（动作词表 v0.2：SUBMITTED/PROCESSING/RESOLVED，
   actor_type=OPERATOR）。回复落 `feedback_ticket.reply/reply_operator_id/replied_at`
   （Schema v0.2 增列），C 端详情 `replies[]` 办结后恰一条可见；`resolution` 字段位保留恒 null
   （回复即结论口径——披露）。**不做**：assign/分派/SLA/24 小时时效提醒（`complaint.assign`
   不登记——单运营无分派语义；提醒无通道）；CLOSED 关闭路由（RESOLVED 即 V1 终态）。
3. **C 端状态与回复可见（裁决第③项）——已落地**。详情投影增 `attachmentAssetIds[]`/`replies[]`，
   事件时间线含三个动作词；C 端"我的反馈"列表半屏详情渲染状态/附件数/时间线/回复
   （列表页缺稿口径沿现行家族语言，PR 披露）。
4. **处理时效 24 小时提醒（§3.3）/内容安全审核（§3.4）/提交回执通知（§3.5）——维持不做**。
5. **人工客服真实承载（§3.6）/统一工单列表（§3.7）——维持不做**。
6. **遗留风险**：①运营侧原文展示经用户裁决放行，若后续需运营侧脱敏需新裁决；②附件绑定为
   提交时点快照（资产后续 RETIRED 不回溯校验）；③RESOLVED 无运营侧撤回/改回复路由（错回复
   需 DB 运维，V1 接受）。
