# NTF/C-006 交接：站内通知读取与受控跳转子切片

日期：2026-09-22。分支 `feat/ntf001-c006-messages-20260922`（基于 develop `12bdc38`）。契约：[CCR-W2-NOTIFICATION-001](../../../ccr/CCR-W2-NOTIFICATION-001.md)。

## 交付

- **后端（NTF-001）**：`NotificationInboxApiImpl`/`NotificationInboxService`/`NotificationInboxStore`（读侧 + CAS 已读，本人隔离 receiver=会话用户）；三端点 `CNotificationController`（列表/详情/已读）；`NotificationInboxConfiguration` 随 C 端会话装配；过滤器/安全/异常处理器三处登记。
- **前端（C-006）**：`NotificationRepository`（严格解码器+白名单路径校验）；`MessagesController`（列表/详情/已读状态机，打开即标记、幂等不重复标记）；消息中心页 `consumer/pages/messages`（无原稿，沿用申请页语言，不声称 VIS）；shell 增加"消息中心"入口；app.config/package-check 登记。
- **跳转白名单**：`MERCHANT_APPLICATION_REVIEWED`+`MERCHANT_APPLICATION` → 申请页；消息体不含 URL；目标页自行重鉴权（申请页本人查询）。

## 语义要点

- 已读为**结果幂等** CAS 写（无资源创建，不建命令日志表——CCR §4 论证）；重复标记返回同一时间戳。
- 接收者由会话解析，客户端参数不可指定；跨用户列表空、详情/已读 404（防枚举）。
- 列表排序 created_at DESC, id DESC；分页 page 1..10000 / pageSize 1..50。

## 测试证据

- `NotificationInboxHttpTest`（真实 MySQL/Redis + 真实审批落库经 outbox 投递）：可见→详情→已读→重复幂等同时间戳→跨用户隔离→参数 400 族→未登录 401。1/1 通过。
- 前端 138 项通过（新增 messages 5 项：列表/空/失败关闭/打开标记一次/已读不重复/白名单路由/释放后忽略迟到结果）；typecheck/build:weapp/check:package 全绿。
- live harness 扩展：真实链路 approval 通知 → list → detail → markRead → replay 同时间戳（随 Java 生命周期测试 CI 执行）。

## 边界

- 通知偏好四端点、商家端/运营端消息路径未实现；receiver MERCHANT/OPS 投影未做。
- 模拟器人工走查（审批落库→消息可见→已读→跳转→申请页重鉴权）待用户执行。
