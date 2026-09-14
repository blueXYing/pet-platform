# 宠物平台 V1.0 HTTP / OpenAPI Controller Contract v0.4

> 文档定位：定义 C 端小程序、商家端、运营端与第三方回调的 HTTP Controller 契约；内部模块之间仍优先使用 Java `*-api` Contract，不因为当前单体部署而通过 HTTP 绕一圈。  
> 优先级：产品 SSOT > 技术基线 > Internal API Contract > 本 HTTP Contract > Controller 实现。  
> 本文只冻结已经明确的产品规则；人工客服承载方式、退款拒绝原因具体字典、微信外部通知具体通道等未决项不擅自补充。

---

## 1. API 分区

```text
/api/v1/c/**              C 端宠物主
/api/v1/merchant/**       商家端
/api/v1/admin/**          平台运营端
/callbacks/lakala/**      拉卡拉回调
/internal/v1/**           未来微服务 Feign Adapter 使用，不对终端开放
```

当前 V1.0 模块化单体：

```text
Controller
   ↓
本模块 Application Service
   ↓
其他模块 *-api
```

禁止：

```text
Controller
→ 其他模块 Service
→ 其他模块 Repository
```

---

## 2. 协议标准

### 2.1 Content-Type

```http
Content-Type: application/json
Accept: application/json
```

文件上传使用：

```http
multipart/form-data
```

### 2.2 认证

终端 API：

```http
Authorization: Bearer <access-token>
```

Contract 只冻结 Bearer Access Token 语义；Token 最终采用 JWT 或 opaque token 属于认证模块内部实现，不影响业务 Controller。

### 2.3 写请求幂等

所有产生业务写入的终端请求必须：

```http
X-Request-Id: <UUID>
```

规则：

```text
相同 X-Request-Id + 相同业务参数
→ 当前权限校验通过后返回第一次成功业务回执

相同 X-Request-Id + 不同参数
→ 409 IDEMPOTENCY_KEY_CONFLICT
```

GET/纯查询不要求 `X-Request-Id`。

本节按已接受CCR-W2-IDEMP-001统一原“第一次处理结果/第一次成功结果”差异，完整规范见[23号补充§3～9](23-公共接口与幂等契约补充-v0.1.md)。终端header是完整8-4-4-4-12 UUID，原值保留，不trim/改大小写；内部确定性key的512 UTF-8字节规则不放宽终端UUID。scope=(稳定业务命令,可信主体类型/ID,授权业务范围)，不新增身份/门店header自证权限。

有效参数绑定后，未成功的同参请求可重新检查重试，失败不固定回执；异参仍409 IDEMPOTENCY_KEY_CONFLICT，不覆盖绑定。重复同参每次校验当前会话/动作权/资源范围/敏感用途并脱敏；无权不返回旧data，成功重放不重复执行已消耗的业务资格。首次创建201、幂等成功200；业务回执稳定，响应traceId使用本次链路，最新资源另查。

争锁忙默认建议有界等待2秒后409 COMMON_CONFLICT/data:null，明确原key稍后重试；异参冲突须处理，不能所有409自动重试。commit未知查主库原key，不能换UUID盲重做；依赖/旧版本读取不可用503 COMMON_DEPENDENCY_UNAVAILABLE。V1不自动删除去重事实，敏感载荷清理须保证重放边界。旧Schema兼容、真实权限和持久化未实现，文档更新不等生产端已执行这些行为。

仅原命令允许的可靠受理可作成功回执；createPayment仍必须提供wechatPayParameters，不能替“受理”/新增202或无限重放过期参数，完整分阶段映射仍按23号§7由原Owner审查。

### 2.4 Trace

请求可携带：

```http
X-Trace-Id
```

服务端不存在时生成，并通过响应返回。

### 2.5 ID

按23号补充§1：ASCII十进制正Long字符串1～9223372036854775807，无前导零、正负号、空白、指数；不先转JS Number。OpenAPI字符串词法与服务端Long上界检查共同约束；验证码/traceId不误作Snowflake。

所有 ID 在 JSON 中均为字符串：

```json
{
  "orderId": "2019267812367810561"
}
```

禁止：

```json
{
  "orderId": 2019267812367810561
}
```

### 2.6 时间

带偏移的ISO-8601，非零亚毫秒拒绝，不截断/舍入；具体业务分钟与截止规则不变。公共java.time.Clock及DB时间边界见23号补充§2。

统一 ISO-8601：

```json
"appointmentStart": "2026-09-10T14:30:00+08:00"
```

### 2.7 金额

HTTP JSON 金额统一使用十进制定点字符串，避免不同前端运行时浮点误差：

```json
{
  "payAmount": "128.00",
  "refundAmount": "64.00"
}
```

Java Controller DTO 转换为 `BigDecimal`。

按23号补充§2：输入可无小数或1～2位、无冗余前导零/空白/指数/正号，负零词法拒绝；最多16位整数，输出固定两位，不进行隐式舍入。公共MoneyCodec支持负值不改变现有订单/退款字段非负及业务资格；比例/积分不是Money规则。OpenAPI分别表达现有非负业务金额输入和两位输出，不添加业务DTO。

### 2.8 通用响应

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "OK",
  "data": {},
  "traceId": "01J..."
}
```

失败：

```json
{
  "success": false,
  "code": "REFUND_MERCHANT_REASON_REQUIRED",
  "message": "请填写拒绝原因",
  "data": null,
  "traceId": "01J..."
}
```

业务逻辑只能依赖 `code`，不得依赖中文 `message`。

### 2.9 分页

请求：

```text
page=1
pageSize=20
```

响应：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 0
}
```

默认 `pageSize=20`，最大 100。

---

# 3. C 端 API

## 3.1 登录与账号

### POST `/api/v1/c/auth/wechat-login`

用途：微信快捷登录/注册。

权限：匿名。

内部落点：

```text
user/auth application
```

产品约束：首次微信注册不自动生成随机密码。

### POST `/api/v1/c/auth/sms-login`

用途：手机号 + 验证码登录。

权限：匿名。

### POST `/api/v1/c/auth/password-login`

用途：手机号 + 密码登录。

权限：匿名。

规则：从未设置密码的账号不暴露“未设置密码”事实，对外统一账号/密码错误语义。

### POST `/api/v1/c/account/password/reset`

用途：验证码校验后设置/重置登录密码。

不设计支付密码相关 Endpoint。

---

## 3.2 宠物档案

```text
GET    /api/v1/c/pets
POST   /api/v1/c/pets
GET    /api/v1/c/pets/{petId}
PUT    /api/v1/c/pets/{petId}
DELETE /api/v1/c/pets/{petId}
```

删除宠物不得物理删除历史订单中的 `order_pet_snapshot`。

---

## 3.3 商家与服务

```text
GET /api/v1/c/stores
GET /api/v1/c/stores/{storeId}
GET /api/v1/c/stores/{storeId}/services
GET /api/v1/c/services/{serviceId}
```

只展示当前允许新预约的商家/门店/服务。

存量订单详情读取订单快照，不实时依赖当前服务是否已下线。

---

## 3.4 可预约时间查询

### GET `/api/v1/c/services/{serviceId}/availability`

Query：

```text
storeId
startDate
endDate
```

返回：

```json
{
  "items": [
    {
      "start": "2026-09-12T09:15:00+08:00",
      "end": "2026-09-12T10:45:00+08:00",
      "effectiveCapacity": 3,
      "occupiedCount": 1,
      "remainingCapacity": 2,
      "available": true
    }
  ]
}
```

内部调用：

```text
ScheduleQueryApi.queryAvailability
```

规则：

- 分钟级时间区间；
- 不暴露固定 60 分钟槽位概念；
- 有效容量由服务端计算；
- C 端不能自行用前端人数计算容量。

---

## 3.5 创建订单

### POST `/api/v1/c/orders`

Header：

```http
X-Request-Id: required
```

Request：

```json
{
  "storeId": "10001",
  "serviceId": "20001",
  "petId": "30001",
  "fulfillmentType": "IN_STORE",
  "appointmentStart": "2026-09-12T09:15:00+08:00",
  "appointmentEnd": "2026-09-12T10:45:00+08:00",
  "pickupStart": null,
  "returnStart": null,
  "couponInstanceId": "40001",
  "remark": "怕生，请提前沟通"
}
```

接送：

```json
{
  "fulfillmentType": "PICKUP_DELIVERY",
  "pickupStart": "2026-09-12T09:00:00+08:00",
  "returnStart": "2026-09-12T11:00:00+08:00"
}
```

强校验：

```text
returnStart >= pickupStart + 120 minutes
```

Response：

```json
{
  "orderId": "2019000000000000001",
  "orderNo": "2019000000000000002",
  "displayStatus": "PENDING_PAYMENT",
  "payAmount": "128.00",
  "paymentExpireAt": "2026-09-12T09:10:00+08:00"
}
```

主要内部调用：

```text
UserQueryApi.getPetSnapshot
MerchantQueryApi.checkOrderEligibility
ServiceQueryApi.getServiceSnapshot
ScheduleCommandApi.hold
CouponCommandApi.freeze（如使用优惠券）
OrderCommandApi.create
```

典型错误：

```text
MERCHANT_DISABLED
STORE_DISABLED
SERVICE_NOT_BOOKABLE
SCHEDULE_NOT_AVAILABLE
SCHEDULE_CAPACITY_EXCEEDED
SCHEDULE_PICKUP_RETURN_INTERVAL_INVALID
COUPON_NOT_AVAILABLE
IDEMPOTENCY_KEY_CONFLICT
```

---

## 3.6 发起支付

### POST `/api/v1/c/orders/{orderId}/payments`

Header：`X-Request-Id` 必填。

Request：

```json
{
  "channel": "WECHAT_MINI_PROGRAM"
}
```

C 端小程序 V1.0 不展示支付方式选择；该字段由客户端固定传输或后端按客户端类型确定。

Response：

```json
{
  "paymentId": "2019000000000000101",
  "paymentNo": "2019000000000000102",
  "channel": "LAKALA_WECHAT",
  "wechatPayParameters": {
    "timeStamp": "...",
    "nonceStr": "...",
    "package": "...",
    "signType": "...",
    "paySign": "..."
  }
}
```

内部调用：

```text
PaymentApi.createPayment
```

---

## 3.7 C 端订单查询

```text
GET /api/v1/c/orders
GET /api/v1/c/orders/{orderId}
```

Query 可按：

```text
displayStatus
page
pageSize
```

后端必须返回 `displayStatus`，前端禁止根据多个底层字段重新计算。

详情建议同时返回事实字段：

```json
{
  "orderId": "...",
  "displayStatus": "AFTERSALE",
  "orderStage": "PENDING_SERVICE",
  "paymentStatus": "PAID",
  "refundApplicationStatus": null,
  "refundStatus": null,
  "afterSaleStatus": "PROCESSING",
  "verificationStatus": "UNVERIFIED"
}
```

以支持 UI 按权限决定按钮，而不是推导业务真相。

---

## 3.8 改期

### POST `/api/v1/c/orders/{orderId}/reschedule`

Header：`X-Request-Id` 必填。

Request：

```json
{
  "appointmentStart": "2026-09-13T14:10:00+08:00",
  "appointmentEnd": "2026-09-13T15:40:00+08:00",
  "pickupStart": null,
  "returnStart": null
}
```

规则：

```text
仅预约开始前
同一商家
同一服务
每单最多 1 次
新预约成功后才释放旧预约
改期成功 → PENDING_CONFIRM
confirmDeadline 重新计算 30 分钟
```

错误：

```text
ORDER_RESCHEDULE_LIMIT_REACHED
ORDER_RESCHEDULE_AFTER_START
SCHEDULE_SWAP_FAILED
SCHEDULE_CAPACITY_EXCEEDED
```

---

## 3.9 申请退款

### POST `/api/v1/c/orders/{orderId}/refund-applications`

Header：`X-Request-Id` 必填。

Request：

```json
{
  "reasonCode": "USER_REQUEST",
  "reasonText": "临时无法到店"
}
```

说明：`reasonCode` 的具体产品字典尚未封板，HTTP Contract 只固定“code + 可选说明”的结构。

Response：服务开始前

```json
{
  "applicationId": "501",
  "applicationStatus": "AUTO_APPROVED",
  "route": "AUTO_FULL_BEFORE_SERVICE",
  "refundOrderId": "601",
  "displayStatus": "REFUNDING"
}
```

Response：服务开始后

```json
{
  "applicationId": "502",
  "applicationStatus": "PENDING_MERCHANT",
  "route": "MERCHANT_CONFIRM_AFTER_SERVICE",
  "merchantDeadline": "2026-09-11T11:30:00+08:00",
  "displayStatus": "REFUND_PENDING_CONFIRM"
}
```

规则：

- 服务开始前无需商家确认；
- 服务开始后商家 24 小时处理；
- 退款申请本身不禁止核销；
- `refund_order` 成功创建后才禁止核销；
- 申请阶段不释放预约资源；
- 渠道退款最终成功后才释放预约资源。

---

## 3.10 查看退款

```text
GET /api/v1/c/orders/{orderId}/refund-application
GET /api/v1/c/orders/{orderId}/refund
```

---

## 3.11 核销码

### GET `/api/v1/c/orders/{orderId}/verification-code`

权限：订单本人。

仅在服务端判断可展示时返回动态核销凭证。

Response：

```json
{
  "code": "******",
  "expiresAt": "2026-09-12T09:20:00+08:00",
  "refreshAfter": "2026-09-12T09:18:00+08:00",
  "verificationStatus": "UNVERIFIED"
}
```

若 `refund_order` 已创建，返回：

```text
VERIFICATION_BLOCKED_BY_REFUND
```

售后处理中但退款单尚未创建，不应仅因 `afterSaleStatus=PROCESSING` 拒绝核销码。

---

## 3.12 发起售后

### POST `/api/v1/c/orders/{orderId}/aftersales`

Header：`X-Request-Id` 必填。

Request：

```json
{
  "type": "SERVICE_DISPUTE",
  "description": "服务未按约完成",
  "requestedAmount": "128.00",
  "evidenceFileIds": ["file-1", "file-2"]
}
```

`type` 的具体用户侧分类字典后续可配置；不影响裁决状态机。

资格：

```text
已核销：
verifiedAt + 7天内

未核销：
预约开始已到 + 商家已拒绝退款
并在 appointmentStart + 7天内
```

Response：

```json
{
  "afterSaleId": "701",
  "status": "PENDING"
}
```

售后创建本身不禁止核销。

---

## 3.13 售后查询与补充证据

```text
GET  /api/v1/c/aftersales/{afterSaleId}
POST /api/v1/c/aftersales/{afterSaleId}/evidence
POST /api/v1/c/aftersales/{afterSaleId}/withdraw
```

不存在“售后复审/二次申诉” Endpoint。

---

## 3.14 评价

### GET `/api/v1/c/orders/{orderId}/review-eligibility`

Response：

```json
{
  "eligible": true,
  "scoreIncluded": true,
  "reviewDeadline": "2026-10-12T10:30:00+08:00"
}
```

### POST `/api/v1/c/orders/{orderId}/reviews`

Header：`X-Request-Id` 必填。

Request：

```json
{
  "storeScore": 5,
  "serviceScore": 5,
  "staffScore": 4,
  "content": "整体服务很好",
  "mediaFileIds": []
}
```

强规则：

```text
必须核销成功
评价截止 = verifiedAt + 30天

已核销后发生部分退款：
允许评价，但 scoreIncluded=false

未核销即部分退款：
不允许评价
```

错误：

```text
REVIEW_NOT_VERIFIED
REVIEW_WINDOW_EXPIRED
REVIEW_ALREADY_EXISTS
```

---

## 3.15 优惠券 / 积分 / 消息

```text
GET /api/v1/c/coupons
GET /api/v1/c/points/balance
GET /api/v1/c/points/ledger
GET /api/v1/c/notifications
GET /api/v1/c/notifications/{notificationId}
POST /api/v1/c/notifications/{notificationId}/read
GET /api/v1/c/notification-preferences
PUT /api/v1/c/notification-preferences
```

通知偏好只能影响：

```text
普通互动提醒
外部微信推送
```

不能关闭站内：

```text
ORDER
REFUND
VERIFICATION
AFTERSALE
AUDIT
```

V1.0 不提供积分抵现/积分商城 Endpoint。

---

# 4. 商家端 API

## 4.1 工作台与订单

```text
GET /api/v1/merchant/dashboard
GET /api/v1/merchant/orders
GET /api/v1/merchant/orders/{orderId}
```

商家列表中的状态同样使用服务端 `displayStatus`。

---

## 4.2 接单

### POST `/api/v1/merchant/orders/{orderId}/confirm`

Header：`X-Request-Id` 必填。

权限：订单所属门店具备订单处理权限的员工。

规则：

```text
仅 PENDING_CONFIRM
30分钟内未处理系统自动接单
```

返回最新订单状态。

---

## 4.3 拒单

### POST `/api/v1/merchant/orders/{orderId}/reject`

Request：

```json
{
  "reasonCode": "OTHER",
  "reasonText": "门店临时无法履约"
}
```

具体 `reasonCode` 字典不在此版本擅自冻结。

拒单成功后：

```text
创建全额退款链路
```

商家在订单确认后不再有普通拒单/取消接口。

---

## 4.4 退款待处理列表

```text
GET /api/v1/merchant/refund-applications
GET /api/v1/merchant/refund-applications/{applicationId}
```

仅显示该商家有权限处理的 `PENDING_MERCHANT` 申请。

---

## 4.5 商家同意退款

### POST `/api/v1/merchant/refund-applications/{applicationId}/approve`

Header：`X-Request-Id` 必填。

无部分金额参数。

商家普通退款能力：

```text
只能 FULL
```

成功后：

```text
创建 refund_order
→ 从创建成功时禁止核销
→ 渠道退款
```

---

## 4.6 商家拒绝退款

### POST `/api/v1/merchant/refund-applications/{applicationId}/reject`

Request：

```json
{
  "reasonText": "服务人员已按约到店等待，暂不同意退款"
}
```

`reasonText` 必填。

成功：

```text
未核销场景 → 恢复 PENDING_SERVICE
已核销场景 → 恢复 COMPLETED
```

买家仍可按产品规则进入售后。

错误：

```text
REFUND_MERCHANT_REASON_REQUIRED
REFUND_MERCHANT_DEADLINE_PASSED
REFUND_APPLICATION_ALREADY_PROCESSED
```

---

## 4.7 平台订单核销

### POST `/api/v1/merchant/orders/{orderId}/verification`

Header：`X-Request-Id` 必填。

Request：

```json
{
  "verificationCode": "123456"
}
```

服务端身份中获取：

```text
merchantId
storeId
staffId
```

禁止客户端自行声明其他门店 staffId 作为可信权限依据。

内部：

```text
VerificationApi.verify
→ OrderOperationGuardApi.acquire(VERIFY)
```

竞态：

```text
refund_order 先创建
→ 409 VERIFICATION_BLOCKED_BY_REFUND

核销先成功
→ 当前未履约售后 INVALIDATED
```

---

## 4.8 排期管理

```text
GET    /api/v1/merchant/stores/{storeId}/availability-windows
POST   /api/v1/merchant/stores/{storeId}/availability-windows
PUT    /api/v1/merchant/stores/{storeId}/availability-windows/{windowId}
DELETE /api/v1/merchant/stores/{storeId}/availability-windows/{windowId}
```

Request：

```json
{
  "serviceId": "20001",
  "startAt": "2026-09-12T09:15:00+08:00",
  "endAt": "2026-09-12T18:10:00+08:00",
  "configuredCapacity": 5
}
```

没有周循环模板字段：

```text
dayOfWeek
repeatWeekly
copyNextWeek
```

这些字段 V1.0 Contract 中禁止出现。

---

## 4.9 服务人员可用时间

```text
GET    /api/v1/merchant/staff/{staffId}/availability-windows
POST   /api/v1/merchant/staff/{staffId}/availability-windows
PUT    /api/v1/merchant/staff/{staffId}/availability-windows/{windowId}
DELETE /api/v1/merchant/staff/{staffId}/availability-windows/{windowId}
```

用户 C 端没有选择服务人员 Endpoint。

商家可在履约前/内部规则允许时指派人员：

### POST `/api/v1/merchant/orders/{orderId}/staff-assignment`

```json
{
  "staffId": "90001"
}
```

---

## 4.10 服务项目管理

```text
GET    /api/v1/merchant/services
POST   /api/v1/merchant/services
GET    /api/v1/merchant/services/{serviceId}
PUT    /api/v1/merchant/services/{serviceId}
POST   /api/v1/merchant/services/{serviceId}/online
POST   /api/v1/merchant/services/{serviceId}/offline
```

V1.0 服务创建模型只允许：

```text
single service
IN_STORE / PICKUP_DELIVERY
必须预约
```

不提供商品直售、套餐、次卡 API。

---

## 4.11 员工

```text
GET    /api/v1/merchant/staff
POST   /api/v1/merchant/staff
GET    /api/v1/merchant/staff/{staffId}
PUT    /api/v1/merchant/staff/{staffId}
POST   /api/v1/merchant/staff/{staffId}/enable
POST   /api/v1/merchant/staff/{staffId}/disable
```

---

## 4.12 售后商家侧

商家不是最终裁决方。

```text
GET  /api/v1/merchant/aftersales
GET  /api/v1/merchant/aftersales/{afterSaleId}
POST /api/v1/merchant/aftersales/{afterSaleId}/evidence
POST /api/v1/merchant/aftersales/{afterSaleId}/opinion
```

`opinion` 只提交意见，不产生最终退款结论。

禁止：

```text
POST /merchant/aftersales/{id}/final-decision
POST /merchant/aftersales/{id}/partial-refund
```

---

## 4.13 评价

```text
GET  /api/v1/merchant/reviews
GET  /api/v1/merchant/reviews/{reviewId}
POST /api/v1/merchant/reviews/{reviewId}/reply
POST /api/v1/merchant/reviews/{reviewId}/appeal
```

每条评价最多申诉一次。

评价申诉不等于售后复审。

---

## 4.14 第三方团购核销

### POST `/api/v1/merchant/third-party/group-buy/verify`

Header：`X-Request-Id` 必填。

Request：

```json
{
  "channel": "MEITUAN",
  "couponCode": "..."
}
```

内部：

```text
ThirdPartyGroupBuyVerificationApi.verify
```

该 API：

```text
不创建 pet_order
不创建 payment_order
不创建 refund_order
不计平台 GMV
```

---

## 4.15 商家消息

```text
GET  /api/v1/merchant/notifications
POST /api/v1/merchant/notifications/{notificationId}/read
```

---

# 5. 运营端 API

运营端统一要求：

```text
PLATFORM_OPERATOR 身份
+
RBAC 权限校验
+
操作审计
```

---

## 5.1 订单查询

```text
GET /api/v1/admin/orders
GET /api/v1/admin/orders/{orderId}
GET /api/v1/admin/orders/{orderId}/status-logs
```

运营不能通过通用更新接口任意改订单状态，因此不存在：

```text
PUT /api/v1/admin/orders/{id}
{ "status": "..." }
```

订单状态变化必须通过有业务语义的 Endpoint。

---

## 5.2 退款查询与异常处理

```text
GET  /api/v1/admin/refunds
GET  /api/v1/admin/refunds/{refundId}
POST /api/v1/admin/refunds/{refundId}/channel-query
POST /api/v1/admin/refunds/{refundId}/retry
```

`retry` 只重试渠道动作，不创建第二张业务退款单。

---

## 5.3 售后列表与详情

```text
GET /api/v1/admin/aftersales
GET /api/v1/admin/aftersales/{afterSaleId}
```

---

## 5.4 售后裁决

### POST `/api/v1/admin/aftersales/{afterSaleId}/decision`

Header：`X-Request-Id` 必填。

Request：全额退款

```json
{
  "decisionType": "FULL_REFUND",
  "refundAmount": "128.00",
  "reason": "经证据核验，支持买家退款"
}
```

部分退款：

```json
{
  "decisionType": "PARTIAL_REFUND",
  "refundAmount": "64.00",
  "reason": "部分服务已完成，裁决退还部分费用"
}
```

其他：

```json
{
  "decisionType": "REJECT",
  "refundAmount": null,
  "reason": "现有证据不足以支持退款"
}
```

合法枚举：

```text
FULL_REFUND
PARTIAL_REFUND
REJECT
RESERVICE
OTHER
```

规则：

- 运营首次裁决为最终裁决；
- 不提供复审 Endpoint；
- 退款金额不能超过实付；
- 退款型裁决只有在 `refund_order` 创建成功后才形成禁止核销边界；
- 若当前未履约售后已因核销先成功而 INVALIDATED，则拒绝退款型裁决执行。

---

## 5.5 运营异常关闭订单

### POST `/api/v1/admin/orders/{orderId}/abnormal-close`

适用：

```text
门店已下线
资质失效
商家失联
其他被授权的异常履约场景
```

Request：

```json
{
  "reasonCode": "MERCHANT_UNREACHABLE",
  "reason": "多次联系无响应，平台执行异常关闭并退款"
}
```

当前 Contract 固定能力语义：

```text
异常关闭 + 全额退款
```

这不是“正常运营代接单”。

操作必须：

```text
高权限
二次确认
完整审计
```

---

## 5.6 商家下线

### POST `/api/v1/admin/merchants/{merchantId}/disable`

Request：

```json
{
  "reason": "资质失效"
}
```

语义：

```text
立即停止新订单
存量订单继续履约
存量退款/售后继续处理
```

不能因为有存量订单直接拒绝“下线”动作。

---

## 5.7 评价治理

```text
GET  /api/v1/admin/reviews
GET  /api/v1/admin/review-appeals
GET  /api/v1/admin/review-appeals/{appealId}
POST /api/v1/admin/review-appeals/{appealId}/decision
```

这是评价治理，不是售后复审。

---

## 5.8 优惠券 / 积分 / 消息 / 审计

```text
GET/POST/PUT /api/v1/admin/coupon-templates...
GET          /api/v1/admin/points/ledger
GET          /api/v1/admin/notifications
POST         /api/v1/admin/announcements
GET          /api/v1/admin/audit-logs
```

V1.0 运营端没有积分抵扣配置、积分商城、直播配置 API。

---

# 6. 拉卡拉回调

## 6.1 支付回调

### POST `/callbacks/lakala/payments`

无需 Bearer Token。

必须：

```text
验签
渠道商户身份校验
渠道交易号幂等
原始回执必要字段脱敏留存
```

成功事实：

```text
payment 本地事务提交
+
PaymentSucceededEvent.v1 写 Outbox
```

重复回调返回渠道要求的幂等成功响应，不重复推进订单。

---

## 6.2 退款回调

### POST `/callbacks/lakala/refunds`

处理原则：

```text
渠道退款成功
→ refund 本地事务确认 SUCCESS
→ RefundSucceededEvent.v1
```

失败与未知必须区分：

```text
FAILED
UNKNOWN
```

`UNKNOWN` 进入主动查单，不创建新退款单。

---

# 7. 内部 Feign HTTP 预留规则

当前模块化单体**不要求模块间走 HTTP**。

未来拆服务时，Adapter 可映射为：

```text
GET  /internal/v1/orders/{orderId}
POST /internal/v1/orders/{orderId}/refund-eligibility
POST /internal/v1/orders/{orderId}/verification-eligibility
POST /internal/v1/orders/{orderId}/review-eligibility

POST /internal/v1/orders/{orderId}/operation-guards/acquire
POST /internal/v1/orders/{orderId}/operation-guards/{token}/commit
POST /internal/v1/orders/{orderId}/operation-guards/{token}/release
```

但调用业务代码仍依赖：

```java
OrderQueryApi
OrderCommandApi
OrderOperationGuardApi
```

而不是直接依赖 Feign Client。

内部 HTTP 必须增加：

```text
service-to-service authentication
trace propagation
requestId propagation
timeout
circuit breaker
```

这些属于未来微服务 Adapter 配置，不进入 Domain Contract。

---

# 8. Controller 到内部 API 映射

| Controller 动作 | 核心内部 API |
|---|---|
| C 创建订单 | UserQueryApi + MerchantQueryApi + ServiceQueryApi + ScheduleCommandApi + OrderCommandApi |
| C 发起支付 | PaymentApi |
| C 改期 | ScheduleCommandApi.swap + OrderCommandApi |
| C 退款申请 | RefundCommandApi.apply |
| C 发起售后 | AfterSaleCommandApi.create |
| C 评价 | OrderQueryApi.checkReviewEligibility + ReviewCommandApi.create |
| 商家接单 | OrderCommandApi.confirmOrder |
| 商家拒单 | OrderCommandApi.rejectOrder + RefundCommandApi |
| 商家同意退款 | RefundCommandApi.approveByMerchant |
| 商家拒绝退款 | RefundCommandApi.rejectByMerchant |
| 商家核销 | VerificationApi.verify |
| 商家第三方核销 | ThirdPartyGroupBuyVerificationApi.verify |
| 运营售后裁决 | AfterSaleCommandApi.decide |
| 运营退款查单/重试 | RefundCommandApi + PaymentRefundApi |
| 商家下线 | Merchant/Admin application + MerchantDisabledEvent |
| 通知查询 | NotificationQuery/Application |

---

# 9. 关键状态返回约束

所有订单列表/详情 Controller 都必须以 order 模块计算结果返回：

```text
PENDING_PAYMENT
PENDING_CONFIRM
PENDING_SERVICE
COMPLETED
CANCELED
REFUND_PENDING_CONFIRM
REFUNDING
REFUNDED
PARTIAL_REFUND
AFTERSALE
```

Controller / React / 小程序不得自己实现展示态优先级。

建议 Order Action DTO 同时返回：

```json
{
  "canPay": false,
  "canReschedule": false,
  "canApplyRefund": true,
  "canShowVerificationCode": true,
  "canReview": false,
  "canApplyAfterSale": true
}
```

`actions` 必须由服务端基于事实状态与当前身份计算，前端按钮显隐不等于后端权限校验；写接口仍需再次校验。

---

# 10. HTTP 状态码约定

```text
200  查询/幂等成功
201  新资源创建成功
400  参数校验失败
401  未认证
403  已认证但无权限
404  资源不存在
409  业务状态/并发/幂等冲突
422  业务参数语义不满足（可选；若团队希望简化可统一 400）
429  频控
500  未预期内部异常
503  渠道/依赖暂不可用
```

交易类“当前状态不允许”优先使用：

```text
409
```

渠道状态未知不是 500。

---

# 11. 安全约束

1. C 端所有 `userId` 从登录身份获取，不信任客户端传入的 ownerUserId。
2. 商家 `merchantId/storeId/staffId` 以身份与授权范围为准。
3. 运营端每个高风险写操作记录：
   - operatorId
   - requestId
   - traceId
   - before
   - after
   - reason
   - createdAt
4. 支付/退款渠道完整密钥、签名密钥、完整敏感回执不得返回终端。
5. 第三方团购完整券码不得进入普通业务日志。
6. 核销、退款、售后证据等敏感动作必须审计。

---

# 12. 不在本版本冻结的 HTTP 契约

以下只保留模块，不擅自确定具体 Endpoint：

```text
1. 人工客服具体会话 API
   原因：承载方式尚未决定

2. 微信公众号 / 订阅消息具体授权与模板管理 API
   原因：需结合实际账号能力

3. 黑名单完整管理 API
   原因：V1.0 仅预留

4. 分钟级排期“快速生成工具”
   原因：产品交互尚未决定

5. 退款拒绝原因具体字典值
   只固定 reasonCode + reasonText 协议
```

---

# 13. Controller 包建议

```text
pet-boot / adapter-web
├── c
│   ├── AuthController
│   ├── PetController
│   ├── StoreController
│   ├── OrderController
│   ├── RefundController
│   ├── AfterSaleController
│   ├── ReviewController
│   └── NotificationController
│
├── merchant
│   ├── MerchantOrderController
│   ├── MerchantRefundController
│   ├── MerchantVerificationController
│   ├── MerchantScheduleController
│   ├── MerchantServiceController
│   ├── MerchantStaffController
│   ├── MerchantAfterSaleController
│   └── MerchantThirdPartyController
│
├── admin
│   ├── AdminOrderController
│   ├── AdminRefundController
│   ├── AdminAfterSaleController
│   ├── AdminMerchantController
│   ├── AdminReviewController
│   └── AdminAuditController
│
└── callback
    └── LakalaCallbackController
```

Controller 只负责：

```text
身份
参数校验
HTTP DTO 转换
调用 Application Service
错误码 → HTTP 映射
响应封装
```

不得在 Controller 编写订单状态机。

---

# 14. 下一步

本 HTTP Contract 完成后，技术设计的下一步是：

```text
定时任务与补偿任务清单
+
失败重试策略
+
Outbox Publisher
+
渠道主动查单
+
30分钟自动接单
+
24小时退款超时
+
10分钟未支付关闭
+
预约临时锁释放
```

完成后再进入全链路测试矩阵。


---

# 15. 支付超时关闭后的迟到支付

该场景不提供恢复订单或预约重选终端 API。

服务端自动：

```text
PAYMENT_TIMEOUT 已关闭
↓
迟到支付成功
↓
FULL refund_order
↓
displayStatus = REFUNDING
↓
退款最终成功
↓
displayStatus = REFUNDED
```

C 端仍通过：

```text
GET /api/v1/c/orders/{orderId}
```

查看退款进度。

退款金额以渠道真实支付金额为准。支付超时关闭时已经释放的预约和优惠券不重新恢复。

## ACR-001会话契约状态

一个小程序的用户/商家工作区共用登录并切换上下文，具体DTO、准入与失效协议仍按planning/ccr/CCR-ACR-001.md建立并审批。本次只同步技术方向与React称谓，没有新增endpoint或修改现有请求/响应schema；内部fixture不能作为公共协议。

## 运营权限产品裁决同步（OD-W0-003）

SSOT §24及22号PRD补充已确定单运营、无内部双人审批、获权编辑直发、超管默认全已批准V1权限。原有高权限/二次确认/原因与审计语义保留；二次确认仅为同人确认，不要求另一个账号。具体角色权限DTO/管理API按CCR-PERM-001建立，本次不预设新endpoint或Schema。
