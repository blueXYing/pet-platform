# CCR-W2-NOTIFICATION-001：站内通知读取子切片（NTF-001 + C-006）

状态：**APPROVED_FOR_SLICE（2026-09-22 用户按序授权实施）**。本 CCR 只冻结 HTTP10 §3.15 既有三条 C 端路径的线上细节；产品规则（站内不可关闭审核类通知、偏好不影响站内）沿用既有批准，无新规则。

Owner：NTF-001（后端读取）/ C-006（C 端消息页与跳转）。范围：V1 站内收件箱 `receiver_type=USER`。

## 1. 范围

纳入：`GET /api/v1/c/notifications`、`GET /api/v1/c/notifications/{notificationId}`、`POST /api/v1/c/notifications/{notificationId}/read`。
不纳入：通知偏好四条路径（HTTP10 §3.15 另列，待后续切片）、商家端/运营端消息路径（/merchant、/admin notifications 维持未实现）、外部微信推送、receiver_type=MERCHANT/OPS 投影。

## 2. GET /api/v1/c/notifications（列表）

- 鉴权：MINIAPP Bearer；receiver 固定解析为当前会话 userId（receiver_type=USER），任何客户端参数不得指定接收者。
- 参数：`page`（默认 1，1..10000）、`pageSize`（默认 20，1..50）；非法或多余参数 → 400。
- data：`{ items:[Notification], page, pageSize, total }`；排序 `created_at DESC, id DESC` 固定；total 为本人过滤后计数；越界页空 items。
- Notification 字段（camelCase）：`id`(String)、`category`(`SYSTEM`；值域 INTERACTION/SERVICE/SYSTEM 保留)、`messageType`(1..64)、`bizType`(null 或 1..32)、`bizId`(null 或 String)、`title`(1..128)、`content`(1..1000，列表返回全文，V1 不截断)、`readAt`(null 或偏移毫秒时间)、`createdAt`(偏移毫秒时间)。
- 错误：401 未登录。

## 3. GET /api/v1/c/notifications/{notificationId}（详情）

- 路径参数 String ID；格式非法 → 400；非本人或不存在的通知 → 404 `COMMON_NOT_FOUND`（防枚举，不区分"存在但非本人"）。
- data 字段与列表项一致。

## 4. POST /api/v1/c/notifications/{notificationId}/read（标记已读）

- 写操作：必须携带 `X-Request-Id`（23 号公共幂等契约的请求头要求）。
- 语义：CAS 置 `read_at`（仅当为 NULL）；重复标记（同或不同 requestId、已读状态）幂等返回当前状态 200；非本人/不存在 → 404；ID 非法 → 400。
- data：`{ id, readAt }`（readAt 为标记时间或既有时间）。
- **幂等实现说明**：本操作是状态收敛型写（无资源创建、无副作用外溢），按结果幂等实现，不建命令日志表——与 merchant 域"创建型命令必须 requestId 绑定回执"的语义不同，属 23 号契约允许的结果幂等类别。若后续通知写动作扩展出创建/外呼语义，须另行补命令幂等。

## 5. 消息类型登记与跳转白名单

| message_type | biz_type | 关联资源 | 跳转（客户端白名单） |
|---|---|---|---|
| `MERCHANT_APPLICATION_REVIEWED` | `MERCHANT_APPLICATION` | bizId=applicationId | `/consumer/pages/merchant-application/index` |

- 新消息类型经 CCR 增补登记；未登记类型仅展示详情，不出现跳转入口。
- 跳转只是页面导航：目标页（申请页）自行执行本人鉴权查询——**通知跳转不携带、不豁免任何授权**；消息体不包含任何 URL 字段。

## 6. 通用

- 三端点 `Cache-Control: no-store`；统一 SUCCESS 信封；HTTP/JSON ID 一律 String。
- 启用条件：`pet.auth.c.enabled=true`（消息随 C 端会话可用；审核通知写入由 merchant application 既有开关控制，读侧不依赖该开关）。

## 7. 验收对应

- 后端 `NotificationHttpTest`（真实 MySQL/Redis + 真实审批落库）：审批后列表可见 → 详情 → 标记已读 → 重复标记幂等 → 跨用户隔离（列表空/详情 404）→ 参数 400 族 → 未登录 401。
- 前端 live harness 扩展：审批链路后 list(1) → detail → read → replay。
- 用户模拟器走查：审批落库 → 消息可见 → 已读 → 跳转申请页 → 重新鉴权查询（申请页重读）。
