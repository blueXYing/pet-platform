# NTF-002 微信外投 Adapter 与重试骨架（默认关闭）实施记录

日期：2026-10-06。基线：origin/develop `02abe89`（#104 合并，含 #100~#104）。分支 `codex/ntf002-wechat-adapter-20261006`，独立 worktree `wt-ntf002-adapter`；主桌面目录只读未动。并行 #105~#107 draft 未 base、未触碰。契约：[55 号内部契约](../../../../docs/04-api/55-Notification-Wechat-Delivery-Contract-v0.1.md)（本切片新建，52/53 已占用、54 留给并行 draft，内部 SPI 无新 HTTP 面）。

## 1. 依赖核实结论（动手前逐项验证）

| 依赖 | 现状 | 证据 |
|---|---|---|
| NTF-001 通知读取/落库侧 | **已在 develop** | `NotificationInboxApiImpl/Service/Store`（C 端列表/详情/已读，CCR-W2-NOTIFICATION-001）；强制站内通知消费者 `MerchantApplicationReviewedConsumer`（Event08）+ `ServiceReviewedConsumer`（Event08 九字段终版），均经 `JdbcOutboxConsumeGuard` 幂等消费、REQUIRES_NEW 通知事务 |
| 站内权威表 | **已在** | SQL06 §11 `notification`（mandatory_inbox 等），测试夹具 `MySqlNotificationTestDatabase` 直接执行权威 SQL06 |
| 外投状态表 | **已在，无需新增 DDL** | SQL06 §11 `notification_delivery`（channel INBOX/WECHAT_SUBSCRIBE/WECHAT_OA；status PENDING/SENT/FAILED/SKIPPED；`uk_notification_channel`；retry_count/last_error/sent_at 齐备）——本切片零 schema 变更，直接沿用 |
| PLAT-004 durable AsyncTask | **已在** | `pet-task-core`（SQL13 Worker/Lease/attempt；`JdbcAsyncTaskSubmitter` 同事务入队、`AsyncTaskWorker`、`TaskRegistration` 确定式 requestId）；producer/handler 先例：`OrderExpiryTaskSubmission`、`PrivateAssetReconcileTaskHandler`（task-core README 明确「重试耗尽 DEAD 由 repository 完成，对账/告警收口属业务侧」） |
| Scheduler §30 口径 | **已冻结** | 「外部通知失败≠站内通知失败≠交易失败；达上限 delivery=FAILED，不反向改变订单」；测试矩阵 MSG-003/TASK-007 同口径 |

结论：ISSUE_CATALOG 对 NTF-002 的依赖描述与 develop 现状一致，无契约缺口，无需 CCR。

## 2. 依据引用（原文位置）

- SSOT《01-SSOT-宠物平台V1.0-最终业务基线》§16.3「外部微信通知」：站内+微信侧外部通知；「站内消息 = 权威通知记录」「微信通知 = 外部提醒渠道」；具体公众号/订阅消息形态「后续结合实际微信账号资质和能力确定技术接入方式」；§21 待确认项 3 同。§16.4 强制通知规则：外部推送可关、站内业务通知不可关。
- 《09-Scheduler-Retry-Compensation-v0.5》§30「站内通知与外部通知重试」：`notification_delivery`；重试上限后 delivery=FAILED；§42 确立「禁止跨模块访问任务 Mapper 或独立提交任务，经 `JdbcAsyncTaskSubmitter` 同事务入队」先例。
- 《14-全链路测试矩阵-v0.1》MSG-003（P1）：外部微信失败→执行 delivery 重试→订单/退款状态不回滚；TASK-007（P1）：达重试上限→delivery=FAILED/告警、订单保持正确。

## 3. 实现内容（全部在 backend/pet-notification-* 与 pet-boot 装配）

### Delivery Adapter SPI（pet-notification-biz `delivery` / `delivery.spi`）
- `WechatDeliveryAdapter`：单次通道尝试端口，`deliver(request)→outcome`；实现不得为通道侧结果抛异常、不得无界阻塞、不落原始报文。
- `WechatDeliveryOutcome`（sealed）：`Sent(providerMessageId≤128)` / `RetryableFailure(≤64)` / `PermanentFailure(≤64)` / `Skipped(≤64)`。
- `UnconfiguredWechatDeliveryAdapter` 空壳：无 SDK/凭据/网络，每次调用终态 `Skipped("WECHAT_CHANNEL_UNCONFIGURED")`，不耗重试预算——即使开关打开、V1 生产路径也不发生任何真实外呼。
- `WechatDeliveryRequest`：全部字段取自权威站内行，严格构造校验；`dedupKey`=确定式 requestId（跨尝试稳定），供真实通道侧幂等。

### 投递任务 producer（durable AsyncTask 模式）
- `WechatDeliveryTaskProducer`：任务 `WECHAT_DELIVER`（owner/biz=NOTIFICATION，task_key `WECHAT_DELIVER:{notificationId}:{channel}`，max_retry 可配默认 5，retry_policy `WECHAT_DELIVER`），payload 严格三字段（notificationId/receiverUserId 十进制 String + channel）。
- 挂载：两个既有消费者的 `recordOnce` 增加可选 `afterInboxInsert` 事务钩子（null=零变化）；钩子在消费 guard 声明+站内行插入成功后、**同一通知事务内** INSERT `notification_delivery`(PENDING) + `JdbcAsyncTaskSubmitter.enqueue`——站内行、外投行、任务三者原子提交/原子回滚。
- `NotificationDeliveryStore/Mapper/XML`（PROPAGATION_REQUIRED：producer 加入调用方事务、handler 独立执行）；`markTerminal` 在 XML 侧白名单终态目标；站内表新增 `selectDeliverySource`（id+USER receiver 双绑定）。

### 投递任务 handler（幂等、退避、DEAD/挂账）
- `WechatDeliveryTaskHandler`：绑定核验（权威行缺失→`Dead("NOTIFICATION_MISSING")`；外投行缺失→`Dead("DELIVERY_ROW_MISSING")`；终态行→`Success("NOOP")` 永不复活）。
- 结果映射：Sent→CAS PENDING→SENT+Success；Skipped→SKIPPED+Success；Permanent→FAILED+Dead；Retryable 且预算未尽→行 PENDING、retry_count=attempt、`Retry(code, 10s/30s/2m/10m/30m 阶梯)`；**Retryable 处于最后预算次数→handler 主动置 FAILED 并返回 Dead**，保证任务 DEAD 与 delivery FAILED 同次尝试一致（TASK-007），不留「任务 DEAD、行仍 PENDING」漂移窗口。
- registration：严格解码+bizId 绑定校验；requestId `TASK:WECHAT_DELIVER:{notificationId}:{channel}:0` 与 attempt/worker/version 无关。
- 防御：adapter 抛 RuntimeException 兜底映射 `WECHAT_ADAPTER_UNEXPECTED` 可重试（行保持 PENDING、预算内最终 FAILED）。

### 装配（pet-boot，默认关闭）
- `NotificationDeliveryConfiguration`：`pet.notification.delivery.enabled=true` 且存在 SnowflakeIdGenerator bean 才装配 producer/registration/共享 worker（`@ConditionalOnMissingBean(AsyncTaskWorker.class)`，与私有材料运行时同款先例，复用同一 SQL13 worker 集合）；channel 默认 `WECHAT_SUBSCRIBE`，非法值拒绝启动。
- `EventOutboxConfiguration`：两个消费者 bean 增 `ObjectProvider<WechatDeliveryTaskProducer>` 注入——开关关→getIfAvailable()=null→消费侧行为与历史版本完全一致。
- **失败关闭**：默认无 producer/无任务/无 worker/无外呼；真实适配器后续以 `@ConditionalOnMissingBean(WechatDeliveryAdapter)` 绑定，V1 只存在空壳。

### 契约与文档
- `docs/04-api/55-Notification-Wechat-Delivery-Contract-v0.1.md`：内部 SPI/任务模型契约（无 HTTP 面；Schema 零新增，明确引用 SQL06 §11/SQL13 既有表与开关矩阵）。
- Schema：**零新增、零修改**——`notification_delivery` 已在权威 SQL06 §11，测试夹具加载权威脚本自动获得；无迁移文件、未运行任何生产迁移。

## 4. 测试（pet-notification-biz）

- `MySqlNotificationTestDatabase` 夹具追加执行权威 SQL13（async_task/attempt/reconciliation_issue），既有断言不受影响。
- `WechatDeliveryTaskRegistrationTest`（纯单测）：严格解码、bizId 绑定、非法字段/数字 ID 拒绝、requestId 跨 attempt/worker/version 确定且随 notification 变化。
- `WechatDeliveryTaskHandlerMySqlTest`（真实 MySQL，9 例）：Sent→SENT+provider_message_id+sent_at；预算内 Retryable→PENDING+retry_count 递进+阶梯步长；最后预算次→FAILED+Dead（并断言站内行与 async_task 零触碰=MSG-003/TASK-007）；Permanent→FAILED+Dead；空壳→SKIPPED+不耗预算；三种终态行永不复活且不调 adapter；权威行/外投行缺失→Dead 且不外呼；adapter 异常→防御性 Retryable、行 PENDING；dedupKey 跨尝试稳定。
- `WechatDeliveryFlowMySqlTest`（真实 MySQL，6 例）：商家/服务两消费者带 producer 原子落三表+重放幂等（守卫）+任务行字段断言+payload 绑定解码；**开关关闭显式零行为断言**（通知照常落库、notification_delivery=0、async_task=0、空壳不外呼）；外投入队失败→整通知事务回滚（consume_log/notification/async_task 全 0）后可重放收敛；产出的任务经 registration/requestId→handler→SKIPPED 终态、async_task 仍 READY（worker 职责边界）；fake adapter 全链路→SENT+NOOP 重放不再外呼。
- 外部交互全部走进程内 `ScriptedWechatDeliveryAdapter` 受控 fake；生产路径（空壳）零网络。

## 5. 门禁数字

- `mvn -pl pet-notification-biz test`（JDK 21.0.11、`-Dmaven.repo.local=D:\m2-sch004\repository`、`-Djava.io.tmpdir=D:\Temp\ntf002-tmp`、本机 MySQL 8.4.9@3306 经 `PLAT003_MYSQL_*` 注入、随机库用后 DROP）：**32/32 通过（BUILD SUCCESS）**——MerchantApplicationReviewed 6 + ServiceReviewed 8（既有消费者回归，旧构造器路径不变）+ 新增 Flow 6 + Handler 矩阵 9 + Registration 3。
- `mvn test-compile`（全 reactor 41 模块，含 pet-boot 装配类与其测试源）：BUILD SUCCESS。
- `mvn -pl pet-architecture-test test`：**31/31 通过**（ARCH-COVERAGE 全 41 模块导入；ARCH-001/002/003/004/005 与 domain 边界、源码负例夹具全绿）。
- `python backend/tools/check-module-deps.py`（ARCH-001：41 模块/17 biz POM）、`check-display-status.py`（ARCH-005）、`check-persistence-style.py`、`python -m unittest discover -s backend/tools`（负例夹具 18 项）：全部 PASS。

## 6. 验收边界（本切片不做）

- 不做真实微信订阅消息/公众号模板配置、access_token/凭据管理、频控与限流；不接微信 SDK；不发起任何真实外部调用。
- 不做 `notification_preference.external_push_enabled` 与外投的联动判定（SSOT §16.4 的用户关推送语义需产品口径后接入 SPI 前置检查）。
- 不做外投与已读联动；不做 `reconciliation_issue` 对账扫描/告警生产闭环（handler 异常路径预算耗尽时任务 DEAD、delivery 行可能停留 PENDING，属骨架登记边界，见契约 §6）。
- 未运行生产迁移（本切片亦无迁移可运行）；draft PR 不合并；主目录未触碰。

## 7. 遗留风险

- 至少一次投递：adapter 成功后行更新失败会重放并再次调用通道；真实通道上线时必须按 `dedupKey` 实现幂等（登记为启用前验收项）。
- worker 技术异常（HANDLER_EXCEPTION）预算耗尽的 DEAD 由 repository 记录，delivery 行停留 PENDING 的窗口依赖后续对账切片收口。
- 共享 worker `@ConditionalOnMissingBean` 协作：与私有材料运行时同时开启时只有一个 worker 进程，注册集合合并生效（与既有先例一致）。
- 消费者新增构造器为增量重载，旧签名保留；若后续并行分支也改这两个消费者需注意合并。
