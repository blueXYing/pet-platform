# 反馈工单（投诉类）HTTP 契约 v0.1（67 号）

状态：IMPLEMENTED_INTERNAL_DEFAULT_OFF，最终以实现 PR 的 CI 为准。v0.1 2026-10-19。
来源：C 端 PRD `docs/01-prd/02-PRD-C端用户-V1.0-最终基线.docx` §5.1.28 售后与投诉之**投诉类（平台意见反馈）**（四类型/联系方式选填/500 字/提交生成投诉工单/查看运营回复和处理结论）；SSOT §17（AI 客服不做、人工客服做、承载方式未决）；[10 号](10-HTTP-API-Contract-v0.4.md) 权限矩阵既有 `complaint.read/assign/handle/decide` 运营占位（10 号，运营面无路由）；[23 号](23-公共接口与幂等契约补充-v0.1.md) §5。设计稿：帧 `130:17185`（意见反馈）、`130:16491`/`130:16704`（帮助与客服及展开态，登记表 §3）。
最小实现口径按 [CCR-CS-FEEDBACK-001](../../planning/ccr/CCR-CS-FEEDBACK-001.md) 冻结（文本工单无附件、运营处置面不在本切片、人工客服承载=帮助中心静态内容+客服联系方式电话唤起）。

本契约为客服/工单域（pet-customer-service-api/biz 骨架模块首次落业务）C 端反馈工单面的唯一契约文档（67 号预分配位）。**售后类（订单售后工单）不在本契约**——售后已由 50/51 号既有售后域承载，PRD 5.1.28 的统一工单列表为商家/运营端需求，C 端不做混合列表。

## 1. 范围与产品边界

1. **提交反馈工单**（PRD 5.1.28 投诉类核心功能）：已登录用户提交 {类型(必填四值), 联系方式(选填), 内容(必填 ≤500 字)} → 生成 `PENDING`（待处理）工单。
2. **我的反馈列表 / 详情**：本人工单分页列表与单条详情；详情含"运营回复和处理结论"字段位（V1 恒为空数组/空值——运营处置面不在本切片，PR 披露）。

**不在本切片（CCR-CS-FEEDBACK-001 登记，一行不做）**：反馈图片附件（PRD 最多 6 张每张 5MB——私有资产上传通道为售后证据专用契约 31 号，反馈附件需扩展该契约或新立通道，另立项）；运营处置面（assign/handle/decide 路由，运营台切片；权限矩阵占位不动）；工单状态流转（V1 提交后恒 `PENDING`，词表冻结五值待运营面启用）；补充举证（待补充态无触发方）；内容安全审核（评价域审核通道不外借，工单进入运营处理流程由人工看单，PRD"反馈内容审核：提交后进入运营处理流程"）；IM/在线会话（V1 无 IM 硬规则）。

## 2. 鉴权、开关与边界

- 装配开关 `pet.feedback.http.enabled`（默认 **false**），硬前置 `pet.auth.c.enabled=true`（双条件装配；21 号清单同步登记）。全部路由真实 MINIAPP Bearer 会话，无匿名路由。
- `Cache-Control: no-store` 全路由；信封 `{code,message,data,traceId}`；未知 query 参数整单 400（列表仅 page/pageSize）；工单号全部 Snowflake 十进制 String；时间 ISO-8601 UTC 毫秒。
- 写路由（POST）终结性 `X-Request-Id`（23 号 §5，namespace `feedback.create`，scope USER_SELF）：幂等绑定在本模块本地 `feedback_command_idempotency`（23 号同构物理表，字段结构同 SQL14——骨架模块不共享 user 模块命令表，遵守"不从本模块访问他模块 CommandIdempotencyStore"既有裁决口径）；同 ID 异参 409 `IDEMPOTENCY_KEY_CONFLICT`，同 ID 同参重放返回首次回执（首执 201 / 重放 200）。
- 展示态零推导（ARCH-005）；内容/联系方式仅本人可读（owner-scoped），不做跨人投影。
- 无跨域依赖 by design：反馈工单不关联订单（投诉类无订单强关联，PRD 5.1.28 仅售后类关联订单）；biz 不依赖 biz。SQL 只进本模块 Mapper XML（`FeedbackTicketMybatis` 命名空间），过 `backend/tools/check-persistence-style.py`。
- 审计：`feedback_ticket_event` 追加式事件表，同事务写 `SUBMITTED` 行（actor=本人）；本行级审计即工单操作史，运营面启用后沿此表追加动作行。
- 无事件 by design：工单提交无跨域消费方（无通知——16 号消息目录无此类型，CCR 登记待运营面立项时增补）；无 Outbox/AsyncTask。PR 披露。

## 3. 路由

| 方法/路径 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|
| POST `/api/v1/c/feedback-tickets` | 终结性 X-Request-Id；严格 JSON `{type, contact?, content}` | `{ticketId, type, status: "PENDING", createdAt}`（首执 201 / 同参重放 200 同回执） | 400/401/403(USER_FROZEN)/409/503 |
| GET `/api/v1/c/feedback-tickets?page&pageSize` | page 1..10000 默认 1；pageSize 1..50 默认 20 | PageResult：`{items: [FeedbackTicketSummary], page, pageSize, total}` | 400/401/503 |
| GET `/api/v1/c/feedback-tickets/{ticketId}` | 路径参数 String ID | `FeedbackTicketDetail`（summary 全字段 + `contact` + `events[]` + `replies: []`） | 400/401/404/503 |

### 3.1 字段口径（冻结）

- `type` 四值枚举（PRD 5.1.28 原文四类型，不新增不改名）：`FEATURE_SUGGESTION`（功能建议）/ `BUG_REPORT`（问题反馈）/ `SERVICE_COMPLAINT`（服务投诉）/ `OTHER`（其他）。未知值 400 `FEEDBACK_TYPE_INVALID`。
- `contact`：选填，1..64 码点字符串（提示语"手机号或微信号，方便我们联系您"由前端渲染；服务端不做格式校验——PRD 未限定格式，电话/微信号/其他联系方式均可）；缺省存 null；**仅详情视图返回，列表不返回**（最小暴露面）。
- `content`：必填，1..500 码点（PRD"500字以内"）；前后端同限。
- `FeedbackTicketSummary`：`{ticketId, type, status, contentPreview, createdAt}`；`contentPreview` ≤ 50 码点截断（服务端截断，UTF-8 码点安全）；固定排序 `created_at DESC, id DESC`。
- `FeedbackTicketDetail`：summary 全字段 + `content`（全文）+ `contact`（null 或原文）+ `events[]`（`{action, createdAt}` 词表冻结 `SUBMITTED`，V1 仅此一值）+ `replies: []`（运营回复字段位恒空数组）+ `resolution: null`（处理结论字段位恒 null）。
- 状态词表（PRD 5.1.28 统一工单状态机，冻结登记）：`PENDING`（待处理）/ `PROCESSING`（处理中）/ `WAITING_SUPPLEMENT`（待补充）/ `RESOLVED`（已处理）/ `CLOSED`（已关闭）。V1 只落 `PENDING`，无任何迁移路由。

### 3.2 防枚举与写守卫

- 详情：非本人或不存在的工单统一 404 `FEEDBACK_TICKET_NOT_FOUND`（不区分存在但非本人）；ticketId 非 Snowflake 词法 400。
- 写前置 `user_account.status=ACTIVE`（403 `USER_FROZEN`）；写路径读 user 状态沿 C 端既有 owner 守卫口径——本模块不依赖 user-biz：经 pet-boot 装配层注入 user 域既有 `user_status` 只读事实（见 §5 装配），同 `BookingUserFactsApi.checkActor` 先例的模块本地端口方向（user-api 已有只读端口可复用时不新立）。
- 频控：单用户每分钟提交 ≤ 5 单（`COMMON_RATE_LIMITED` 429，模块内易失计数器，易失性披露——重启清零不构成安全边界，仅防滥用毛刺）。

## 4. 错误码（12 号登记）

| Code | 含义 | HTTP |
|---|---|---:|
| FEEDBACK_TICKET_NOT_FOUND | 反馈工单不存在/非本人（防枚举统一语义） | 404 |
| FEEDBACK_TYPE_INVALID | 反馈类型不在四值词表 | 400 |

（频控复用既有 `COMMON_RATE_LIMITED` 429——实现期拼写更正，原提案 `FEEDBACK_RATE_LIMITED` 不登记，PR 披露。）

（其余复用既有码：COMMON_INVALID_ARGUMENT / COMMON_UNAUTHORIZED / USER_FROZEN / IDEMPOTENCY_KEY_CONFLICT / COMMON_DEPENDENCY_UNAVAILABLE / COMMON_RATE_LIMITED。）

## 5. 装配与模块边界

- pet-customer-service-biz 骨架模块首次落业务：`FeedbackTicketService`（application）+ `FeedbackTicketStore`/Mapper XML（infrastructure）+ `FeedbackCommandIdempotencyStore`（23 号同构）。pet-boot 新增 `FeedbackConfiguration`（双开关装配）与 `CFeedbackController`。
- 写守卫的用户状态事实：优先复用 pet-user-api 既有只读端口；若既有端口语义不匹配（需"仅状态、无会话"纯事实），在 pet-user-api 增补只读状态端口 `UserActiveStatusApi`（user-biz 实现，纯事实读、无授权语义，沿 OrderFirstPaidFactsApi 先例）——实现期择一并 PR 披露，两向均为 api 依赖非 biz 依赖。
- 内容脱敏存储：SSOT 无反馈内容脱敏条款（全文 0 命中），最小口径=原文存储 + owner-scoped 读 + 列表不回传联系方式；若后续运营面/导出面立项，需先过隐私口径裁决。CCR-CS-FEEDBACK-001 §2 登记。

## 6. 设计偏差登记（帧 `130:17185` / `130:16491` / `130:16704`，登记表同步）

| 帧元素 | 处理 | 依据 |
|---|---|---|
| 意见反馈：上传图片位 | V1 不做（帧稿未见上传控件，PRD 有 6 张附件——按 PRD 从宽应为有；通道缺失，CCR 另立项） | 帧与 PRD 冲突处按最小实现 + CCR |
| 反馈类型四胶囊/联系方式选填/500 字/提交 | 1:1 还原 | PRD 5.1.28 |
| 帮助与客服：在线客服"7×24"文案 | 改写为人工客服口径（V1 无 AI 客服；承载=客服联系方式展示+电话唤起） | SSOT §17 + AGENTS 硬规则 |
| FAQ 答案（取消订单"服务开始前2小时免费取消"等） | 按平台已批规则改写（V1 无 C 端普通取消入口；服务前退款自动全额） | 10 号 §4.3 + 退款硬规则 |
| 帮助与客服底部 tab | 不还原 | 同 66 号 §6 tab 口径 |
| 联系客服按钮 | `wx.makePhoneCall` 唤起客服热线（热线号常量，CCR 登记待运营配置） | 最小承载 CCR-CS-FEEDBACK-001 |

## 7. 测试口径

- customer-service 域内核真实 MySQL 回环（隔离库）：提交 happy path（工单行 + SUBMITTED 审计行）、四类型词表、content 边界（1/500/501）、contact 边界、幂等同参 200 回执/异参 409、列表分页与排序、contentPreview 截断、详情 owner 防枚举 404、FROZEN 403、频控 429。
- 装配：开关默认关；缺 auth.c 启动失败。
- 前端：意见反馈提交页/我的反馈列表页/帮助与客服页 smoke + typecheck + build；package-check 同步。
