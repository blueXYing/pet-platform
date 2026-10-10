# 商家退款处理 HTTP 面与 M 端页面接续方案

日期：2026-10-10。基线：origin/develop `0a4b6be`（工作树 codex/merchant-refund-20261010）。

状态：候选，随本切片实施；按 WORK_EXECUTION_PROTOCOL §4 属 Contract 增量（新增 57 号契约 + OpenAPI 同步），本文记录形成依据与裁决点，业务硬规则不新增、不自创。

## 1. 本次审阅的具体内容

49 号契约已交付普通退款申请内核（apply/decide/timeout/createApproved）并明确「HTTP/小程序不属本批」。本批把已批内核的商家决定面接成默认关闭的 HTTP 切片，并交付 M 端处理页面：

| 决定 | 推荐方案与影响 |
|---|---|
| M1：商家退款 HTTP 面 | `GET/POST /api/v1/merchant/refund-applications...` 四路由（列表/详情/approve/reject），全部薄边界：身份、参数、严格 JSON 与错误映射在 HTTP 层，准入/幂等/期限/来源仍在 49 号内核。新开关 `pet.refund.merchant.http.enabled` 默认 false。 |
| M2：商家读侧查询 | REFUND 域新增只读查询 API（本域 MyBatis XML，商家坐标作用域 + 门店 guard + OWNER 只读重验），不跨模块读 ORDER/PAYMENT 表；不开放买家明文说明。 |
| M3：M 端页面 | frontend-miniapp 商家子包新增退款待处理列表 + 详情（同意/拒绝，拒绝必填理由），沿 aftersale 页模式（OWNER 准入、FROZEN 只读、写日志幂等重放）。 |

不包括：超时任务（REFUND_MERCHANT_TIMEOUT 已存在，本批不动）、通知送达、部分退款、员工账号退款授权、生产启用与合并。

## 2. 已核实基线与来源

- 内核：`RefundApplicationCommandApi.decide`（APPROVE/REJECT；`MerchantOrderAuthorityApi.requireOwner` 在共享门店 guard 下重验；now>=deadline 返回 `REFUND_MERCHANT_DEADLINE_PASSED`；拒绝 reasonText 必填 1..500 码点）。错误码 `REFUND_MERCHANT_REASON_REQUIRED` 已登记 12 号 §6。
- SSOT §4.2/§4.3（商家只有同意全额/拒绝，超时系统自动全额）、§38（拒后可再申请，每轮重算 24h）、§39（共享门店锁内以数据库时间判断，到期交系统）。
- HTTP 基线草案：10 号 §4.4/§4.5/§4.6 + 11 号 OpenAPI `/merchant/refund-applications/{applicationId}/approve|reject`（草案级，未标实施）；列表/详情路由草案缺字段定义。
- 商家 HTTP 家族先例：MerchantOrderController（`pet.order.merchant.http.enabled`，严格 JSON、onlyParameters、success 信封、防枚举 403）、CRefundApplicationController（C 端退款申请 HTTP 面，409/503 映射先例）。
- 读侧先例：MerchantOrderQueryApi/Impl/Service（门店 guard + requireOwnerRead + 本域 MyBatis XML + created_at DESC,id DESC 固定排序）。
- 商家端 Figma（Usvn3d6UCVCAlDxou5KAK8）存在「首页-退款单 9:779/详情 9:891/退款成功 10:2010/订单-退款 40:1061 系列」frame，但语义与已批契约冲突（见 §7），视同设计缺稿。

## 3. 必须保留的不变规则

1. 商家只有同意全额/拒绝两种决定；无金额参数、无部分退款、无理由字典新枚举。
2. 决定只允许 now<merchantDeadline（数据库时间，门店锁内）；到期由系统接管，HTTP 面不得绕过或吞掉 `REFUND_MERCHANT_DEADLINE_PASSED`。
3. 拒绝必填 reasonText（1..500 码点、非全空白、过审核）；缺理由是 400 `REFUND_MERCHANT_REASON_REQUIRED`。
4. 全部写操作 23 号五元组幂等：decide scope=`REFUND_APPLICATION:id`；同 requestId 同参重放返回首回执并重验当前 OWNER 权限；同 key 异参 409。
5. 非 OWNER/跨店/未知坐标防枚举：同一 403（列表/详情读侧）；决定命令按内核既有语义（未知 applicationId 404 `REFUND_APPLICATION_NOT_FOUND`、他人申请 403）。
6. ID String（Snowflake 十进制）、金额 BigDecimal→JSON 两位小数字符串、时间 UTC 毫秒、no-store。
7. 不跨模块访问 Repository/Mapper/DO；biz 不依赖 biz；refund-biz 不新增对 merchant-biz 的依赖（OWNER 只读重验沿端口注入）。

## 4. M1：HTTP 面（57 号契约）

路由（全部 MERCHANT/MINIAPP，bearer 真实会话，开关 `pet.refund.merchant.http.enabled` 默认 false，开启前置 `pet.refund.application.enabled=true` 由装配校验失败关闭）：

- `GET /api/v1/merchant/refund-applications?merchantId&storeId&page&pageSize`：仅本店 `PENDING_MERCHANT`（10 号 §4.4「仅显示该商家有权限处理的 PENDING_MERCHANT 申请」）；page 1..10000、pageSize 1..100 默认 20；固定排序 created_at DESC, id DESC；PageResult 信封。
- `GET /api/v1/merchant/refund-applications/{applicationId}?merchantId&storeId`：单笔详情，本店任意状态（决定后可回看）；未知/跨店 403 防枚举（读侧与订单列表一致，不区分 404）。
- `POST .../{applicationId}/approve`：无业务体（允许空对象/无体；无部分金额参数，10 号 §4.5）。
- `POST .../{applicationId}/reject`：体严格 `{reasonText}`（10 号 §4.6）；缺/空白/超长/类型错 400（缺理由映射 `REFUND_MERCHANT_REASON_REQUIRED`，其余 `COMMON_INVALID_ARGUMENT`）。

expectedApplicationVersion：HTTP 面固定传 `"0"`。依据 49 号/内核不可变约束（PENDING_MERCHANT 行 version 恒 0；重放在版本检查前返回首回执），该取值与任意合法调用等价，不新增客户端字段。

回执：49 号七字段原样（orderId/applicationId/applicationStatus/applicationVersion/merchantDeadline/decidedAt/decisionId）；批准不表示退款成功（durable REFUND_APPLICATION_CREATE 任务建单）。信封沿商家家族 `{success,code,message,data,traceId}`。

错误映射（控制器本地，未识别一律 500 不泄内核）：400 INVALID_ARGUMENT/`REFUND_MERCHANT_REASON_REQUIRED`；401/403 COMMON_*；404 `REFUND_APPLICATION_NOT_FOUND`；409 `REFUND_APPLICATION_ALREADY_PROCESSED`/`REFUND_MERCHANT_DEADLINE_PASSED`/`IDEMPOTENCY_KEY_CONFLICT`/`COMMON_CONFLICT`/`ORDER_OPERATION_BUSY`/`REFUND_NOT_ELIGIBLE`/`REFUND_ORDER_ALREADY_EXISTS`/`REFUND_ALREADY_EXISTS`；503 COMMON_DEPENDENCY_UNAVAILABLE。

## 5. M2：读侧查询

- `pet-refund-api` 新增 `MerchantRefundApplicationQueryApi`：`listStoreApplications`（分页 Summary）与 `readStoreApplication`（单笔 Summary）。
- `pet-refund-biz`：application 服务 + 只读 QueryStore + 本域 `RefundApplicationQueryMapper.xml`（count/分页/单笔三条 SELECT，无锁读）；事务 REQUIRES_NEW READ_COMMITTED，先 `ScheduleCapacityGuardApi.acquire(store)` 再 OWNER 只读重验，后按 merchant_id+store_id 双键过滤——与订单列表同纪律。
- OWNER 只读重验沿端口（refund-biz 无 merchant-api 依赖）：`RefundApplicationPorts.OwnerReadAuthority`，boot 内接到 `MerchantOrderAuthorityApi.requireOwnerRead`（ACTIVE/OFFLINE/FROZEN 存量读侧语义：FROZEN 只读，决定命令仍走既有 requireOwner 失败关闭）。
- Summary 字段：applicationId/applicationNo/orderId/status/applicationVersion/reasonCode/refundAmount(BigDecimal)/createdAt/merchantDeadline/decidedAt/decisionId/refundOrderId。买家申请说明明文不回传商家（49 号密文留存，仅内核证明可解密）；不回传买家 userId/手机号等任何买家身份字段。

## 6. M3：M 端页面与准入目录

- `frontend-miniapp/src/merchant/pages/refund/{index,detail}` + `merchant/refund/{model,repository,controller}`：列表仅 PENDING_MERCHANT；详情含决定动作；拒绝理由必填（前端 1..500 码点校验，400 映射中文提示）；写沿 `merchant-refund:{merchantId}:{applicationId}:{approve|reject}` 槽位记 X-Request-Id，未确认提交原参数重放取回首回执；OWNER 准入 + FROZEN 只读沿 aftersale 模式。
- 共享层：consumer-api 路径门新增商家退款谓词（列表坐标严格等于工作台；详情/决定 target-free 但需完整工作台票根）；401/403/404 保留隐藏首回执日志（决定重放重验权限）。
- 准入动作目录补 `merchant.refund.read`（ACTIVE/OFFLINE/FROZEN）与 `merchant.refund.handle`（ACTIVE/OFFLINE，FROZEN 无）——10 号 §4.1 拟议动作码既有词汇，OFFLINE 可处理存量退款沿 49 号「OFFLINE 可处理存量订单」，FROZEN 只读沿同条款；同步 MerchantAdmissionHttpTest 精确断言。目录是工作台提示码（10 号明示非授权清单），真正授权仍是内核 requireOwner。

## 7. 设计稿裁决（登记表 §4 补记）

从本机 Figma 缓存（merchant-full.json，未耗 REST 配额）提取「首页-退款单 9:779/9:891/10:2010」「订单-退款 40:1061/40:1345/40:1296」全文：均为第三方平台聚合语义——底部 tab 导航（首页/订单/核销/消息/我的，M 端为工作台导航）、「申诉」入口（V1 无商家申诉）、「客户信息」姓名+脱敏手机号（商家读侧无该字段，45 号切片同裁决）、订单短码 `O8`、详情含「服务流程」四步（ORDER 域数据不在退款读侧）、标题混用「售后单」。与 49 号/57 号冲突，视同设计缺稿：页面沿 M 端现行规范（aftersale 页 measures/tokens）实现，原稿保留登记不删除，补稿后按 21 号验收补充还原。

## 8. 必须通过的验收

1. HTTP 决定四路由真实回环：同意回执 APPROVED + 决定行/事件/CREATE 任务齐备且不直接出款；拒绝回执 REJECTED、无 refund_order；同 X-Request-Id 重放首回执一致；同 key 异参 409；不同 key 二次决定 409 `REFUND_APPLICATION_ALREADY_PROCESSED`。
2. 列表/详情：仅本店 PENDING_MERCHANT；拒绝后再申请新轮出现新行新期限；详情字段与内核行一致；金额两位小数字符串。
3. 可见性：匿名 401；非 OWNER 会话列表/详情/决定 403；未知坐标 403（读侧防枚举）；未知 applicationId 决定 404。
4. 期限：时钟越过 merchantDeadline 后 approve/reject 409 `REFUND_MERCHANT_DEADLINE_PASSED` 且无决定行。
5. 拒绝理由：缺/空白 400 `REFUND_MERCHANT_REASON_REQUIRED`；>500 码点/未知字段/错类型 400；strict JSON（尾随/重复键/显式 null）400。
6. 开关默认关：无 controller bean、无路由，请求按既有安全链拒绝；开启而内核关时装配失败关闭。
7. 持久层检查（check-persistence-style.py）与架构测试通过；refund-biz 不新增跨模块依赖。
8. 前端：tsc/build/单测全绿（历史基线 502/502 以上）；页面 journal/准入/只读态单测覆盖核心分支。
9. 既有 C 端退款申请、商家订单、售后测试不回归。

## 9. 尚待审阅的边界

仅上述 M1/M2/M3 技术范围。不重问已批的 24 小时、全额、拒后再申请、防核销互斥等硬规则；不新增任何 SSOT 外产品规则；通知送达、员工授权、部分退款、超时任务实现仍归后续批次。
