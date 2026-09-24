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

Bearer Access Token语义保留；已接受AUTH-001 D1选择服务端可撤销opaque会话，具体同步见本文AUTH-001附录。本次为未合并契约同步候选，不表示已实现认证。

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

字段与校验（CCR-W2-API-001用户域0.1已批）：

```text
PetView: petId/name/petType(DOG|CAT|OTHER)/breedName?/birthDate?(yyyy-MM-dd不得晚于当天)/
         sex(MALE|FEMALE|UNKNOWN)/weightKg?(两位小数0.01~999.99十进制String)/
         sterilizationStatus?(INTACT|NEUTERED|UNKNOWN)/vaccineStatus?(NONE|PARTIAL|COMPLETE|UNKNOWN)/
         healthNote?(≤1000)/avatarUrl?/isDefault/status(ACTIVE|DISABLED)
写操作：POST 201、PUT 200、DELETE 200软删除(status=DISABLED)；petType创建后不可改；
归属反例（不存在/他人/已删除）统一404 COMMON_NOT_FOUND防枚举；会话主体取登录态；
写操作X-Request-Id幂等按23号；冻结用户写操作403 USER_FROZEN；每用户至多一只默认宠物，
置默认与取消旧默认同事务，删除默认后无默认直至再设置。
```

## 3.2.1 用户资料（新增，CCR-W2-API-001用户域0.1已批）

```text
GET /api/v1/c/profile
PUT /api/v1/c/profile
```

```text
GET 200: {userId, nickname, avatarUrl, phoneMasked, passwordEnabled}
PUT 请求: {nickname?(1~64), avatarUrl?(≤512,https)}；幂等X-Request-Id；403 USER_FROZEN。
不包含手机号换绑/注销（AUTH后续）。
```

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

**匿名浏览语义（CCR-W2-API-001 门店读侧 STR-D8，2026-09-22 用户裁决，四条路由统一）**：按最终 C 端 PRD §5.1.13/14"所有用户浏览；已登录用户可进入预约"，上述四条 GET 路由**匿名可访问，Bearer 可选**——无 Authorization 头匿名放行；携带 Bearer 时校验，无效/过期仍 401 `COMMON_UNAUTHORIZED`（匿名放行不掩盖坏凭证）；有效会话正常解析主体。可选主体不改变可见性（可见性合取不含用户主体条件）。非 GET 方法与其余 C 端路由维持既有强制会话/denyAll。

## 3.3.1 门店服务列表与服务详情（新增，CCR-W2-API-001 服务域 v0.3 已批；补齐 PR#65 漏同步）

```text
GET /api/v1/c/stores/{storeId}/services?page&pageSize
GET /api/v1/c/services/{serviceId}
```

会话：匿名可访问、Bearer 可选（见上方“匿名浏览语义”，STR-D8 四条路由统一）；无效/过期凭证仍 401 `COMMON_UNAUTHORIZED`。读操作无 requestId 幂等要求（23 号）。

可见性（SVC-D1b）：C 端可见性 = `service.status=ACTIVE` ∧ 商家 `merchantEnabled` ∧ 门店 `storeEnabled` ∧ `acceptsNewOrders` 四条件合取，同事务判定。任一条件不满足：列表中不出现；详情返回 404 `SERVICE_NOT_FOUND`，与"服务不存在"同响应、不区分原因（防探测），与"只展示当前允许新预约的商家/门店/服务"（§3.3）一致，避免"列表隐藏了、详情链接还能打开"。HTTP 详情响应不携带 bookability 子对象（可见即基本资格合格）；资格原因细分仅保留在内部 `checkBookable` 的 `reasonCodes`，供 ORD/SCH 使用。此"可预约"仅指基本资格合格，不代表所选时间还有空位，更不代表订单预约成功（空位查询 §3.4 与下单 §3.5 由排期/订单域另行检查）。

| 操作 | 成功 | 关键错误 |
|---|---|---|
| GET `/api/v1/c/stores/{storeId}/services?page&pageSize` | 200 分页 `StoreServiceItemView[]`；仅返回 D1b 可见性合取通过的服务 | 400 参数；401 无效凭证（匿名可访问） |
| GET `/api/v1/c/services/{serviceId}` | 200 `ServiceDetailView`（可见即资格合格，D1b） | 404 `SERVICE_NOT_FOUND`（不存在 / OFFLINE / DRAFT / 商家或门店停用或不接新单——一律同响应不区分原因）；400；401 无效凭证 |

失败关闭：任何事实源异常/未知（查询失败、字段缺失、状态值非法）→ C 端不可见或整体 503 `COMMON_DEPENDENCY_UNAVAILABLE`，绝不降级为可见/可预约；确认不存在或不可见（404 `SERVICE_NOT_FOUND`）与事实源故障（503）不得混同。

列表归属语义：`storeId` 不存在或不可见 → 200 空列表（页面显示"暂无服务"，不暴露门店状态细节，不与"门店存在但无服务"区分探测）；详情不可见一律 404（D1b）。

服务详情 `ServiceDetailView` 示例：

```json
{
  "serviceId": "20001", "merchantId": "957001", "storeId": "957002",
  "serviceName": "宠物美容-基础洗护", "categoryId": "957003", "categoryName": "美容",
  "salePrice": "128.00", "durationMinutes": 45,
  "fulfillmentType": "IN_STORE", "description": "含洗护、吹干、基础梳理"
}
```

门店服务列表项 `StoreServiceItemView`：详情字段去掉 `description`（列表不显全文）；不暴露 `version`；分页信封 `items/page/pageSize/total`，排序 `created_at DESC, id DESC`，`page` 1..10000、`pageSize` 1..50（与通知列表一致）。

校验（违规 `COMMON_INVALID_ARGUMENT`/400，details 指明字段）：`serviceId`/`storeId` 为雪花 ID 字符串（公共 ID Codec），路径参数非法即 400；`fulfillmentType` 仅 IN_STORE/PICKUP_DELIVERY，存储值非法时读侧失败关闭（503）。

金额与快照（SVC-D3）：HTTP 投影 `salePrice` 为十进制字符串两位小数（如 `"128.00"`，纯传输格式防精度损失）；快照为查询时值拷贝，主数据后续修改不改变已返回副本；存量订单展示旧价格/旧资料走订单域订单快照，不经本接口。

#### 封面展示增补（CCR-W2-API-001 服务写入方 v0.2，2026-09-22 已批）

`GET /api/v1/c/services/{serviceId}` 成功 data（匿名可访问、Bearer 可选；可见=ACTIVE ∧ merchantEnabled ∧ storeEnabled ∧ acceptsNewOrders，不可见一律 404 不区分原因）：

```json
{
  "serviceId": "...", "merchantId": "...", "storeId": "...",
  "serviceName": "...", "categoryId": "...", "categoryName": "...",
  "salePrice": "128.00", "durationMinutes": 60, "fulfillmentType": "IN_STORE",
  "description": "...",
  "cover": {"coverAssetId": "...", "coverUrl": "https://...", "coverUrlExpiresAt": "2026-09-22T12:00:00Z"}
}
```

- `cover` 仅在服务可见且有封面绑定时返回（列表项同形状减 `description`）；无封面绑定为 `cover: null`；REVIEWING/REJECTED/OFFLINE/DRAFT 一律 404 不携带。
- `coverUrl` 为短时效签名 URL（复用 CCR-OSS-001 公开素材签名机制），客户端按 `coverUrlExpiresAt` 到期前刷新；签名端口不可用且有封面 → 503 失败关闭，不返回未签名 URL。
- 金额两位小数 String；`Cache-Control: no-store`。

## 3.3.2 门店浏览（CCR-W2-API-001 门店读侧 STR-D1～D8，2026-09-22 已批；装配开关 `pet.store.query.enabled` 默认关闭）

**GET `/api/v1/c/stores?city&page&pageSize`**（列表，匿名可 GET，见上）：

- Query 仅 `city?`、`page?`（1..10000，默认1）、`pageSize?`（1..50，默认20）；未知参数/非法值 400 `COMMON_INVALID_ARGUMENT`。
- `city` 语义：城市范围由服务端开放城市目录控制（`MerchantApplicationCityCatalog`，现仅成都）；显式传值必须词法合法（`[a-z][a-z0-9_-]{0,31}`）且属于目录，否则 400；**省略 = 隐式限定为当前全部开放城市集合**（不硬编码城市名）；目录空配置 503 失败关闭。
- 200 信封 `items/page/pageSize/total`；排序固定 `merchantId,storeId` 数值升序；不支持 sort/距离/评分排序与 keyword/categoryId 筛选（延后，未知参数 400）。
- 列表项九字段：`storeId、merchantId、storeName、merchantName、address、longitude?、latitude?、phoneMasked?、cityCode`；无 merchantStatus/storeStatus/version 恒真字段；电话仅掩码投影（完整号码与拨号能力不在本切片，待裁决）。

**GET `/api/v1/c/stores/{storeId}`**（详情，匿名可 GET）：字段集与列表项相同（详情无增量字段）；`storeId` 雪花 ID 十进制 String，非法 400。

- 可见性 = 三条件合取（`merchant.status==ACTIVE` ∧ `store.status==ACTIVE` ∧ 审核APPROVED+签约SIGNED，同 27号 §4 资格策略；**不含"服务 ACTIVE"**——有店无服务仍可见，店内空服务列表由 §3.3.1 路由返回空页）。
- 错误两分（不得混同）：确认不存在或任一条件不满足 → 详情 404 `STORE_NOT_FOUND` 不区分原因（防探测），列表隐藏；事实源故障、读取失败、状态未知、`merchant_profile_compat` 城市事实损坏（通过资格合取但缺行或 city_code 词法非法）→ 列表**整页** 503 `COMMON_DEPENDENCY_UNAVAILABLE`、详情 503，不降级空页；开放城市确证无可见门店 → 200 空页（与 503 严格区分）。
- 配套完整性程序（用户裁决要求）：存量 SQL 巡检+告警+修复 runbook 见 `planning/issues/wave-2/MER-001-store-read/INTEGRITY-RUNBOOK.md`。
- 与 `/c/stores/{storeId}/services` 的配合：同一不可见门店，本路由 404、店内服务列表 200 空页——各自已批语义并存；C 端页面以详情路由为门店可访问性判据。---

## 3.4 可预约时间查询（CCR-W2-API-001 排期域 SCH-001，2026-09-23 已批；装配开关 `pet.schedule.query.enabled` 默认关闭）

### GET `/api/v1/c/services/{serviceId}/availability`

Query：

```text
storeId      必填，雪花 ID String；与服务归属门店不一致 → 404 SERVICE_NOT_FOUND（防探测，与不可见同响应）
startDate    必填，yyyy-MM-DD（平台业务时区 Asia/Shanghai 解释为 [当日00:00, 次日00:00)）
endDate      必填，yyyy-MM-DD，endDate>=startDate，跨度 ≤31 天
```

未知 query 参数拒绝（400）。会话（SCH-D1）：**登录态**——本路由不属 STR-D8 匿名四路由，MINIAPP Bearer 强制（无 Authorization 401 `COMMON_UNAUTHORIZED`；携带无效/过期 401）。读操作无 requestId 幂等要求（23 号 §3）。

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
ScheduleQueryApi.queryAvailability   （07 号 §6.1/§6.1.1：可见性经 checkBookable，容量 min 公式，人员事实经 QualifiedStaffFactsPort）
```

规则：

- 分钟级时间区间；
- 不暴露固定 60 分钟槽位概念；
- 有效容量由服务端计算；
- C 端不能自行用前端人数计算容量。

细化（SCH-D1～D11 裁决，[决定回执](../../planning/ccr/CCR-W2-API-001/schedule-availability-decisions.md)）：

- **可见性先行（错误两分，不得混同）**：服务不存在/OFFLINE/DRAFT/REVIEWING/REJECTED/商家停用/门店停用/不接新单/storeId 不一致 → 404 `SERVICE_NOT_FOUND`（不区分原因，防探测，SVC-D1b 同语义）；服务或商家事实源故障/状态未知 → 503 `COMMON_DEPENDENCY_UNAVAILABLE`（失败关闭，不降级空页）。
- **容量失败关闭（SCH-D6/SCH2）**：容量=SSOT §12.2 `min(配置容量, 可用服务人员数)`；SCH-002已接通真实员工、具体服务能力及整个窗口排班覆盖（07号§4.4）。明确无合格人员=0/available=false；提供器缺席、读失败或未知状态仍503 `COMMON_DEPENDENCY_UNAVAILABLE`，不能伪造容量或默认可约。默认关闭不变，SQL播种事实仅用于测试。
- **窗口过滤与排序**：仅返回 `status='OPEN'` 窗口（CLOSED 不返回；临时停业=不开窗/关窗，无独立事实）；已结束窗口（end≤now）不返回；进行中窗口返回且 `available=false`；跨天窗口（寄养/过夜）与查询区间部分相交时**整体返回不切割**；items 按 `start` 升序；无可约窗口=200 空 `items`（与 404/503 严格三区分）。
- **响应投影**：items 元素仅 start/end/effectiveCapacity/occupiedCount/remainingCapacity/available 六字段（不暴露 windowId/version/status/配置容量/人员数细分；`available=false` 统一表达不可约/已占满，不引入状态枚举——商家端日历细分状态归写入方切片 SCH-004）；时间带偏移 ISO-8601 分钟精度、业务时区 +08:00 投影；不携带 fulfillmentType。
- **上门接送型（SCH-D4）**：窗口集不区分上门/送回候选（同一窗口集供 C 端分别选择）；120 分钟间隔与跨天送回约束不在查询侧（C 端置灰联动，服务端最终校验归 SCH-003 hold/订单侧）；`window_kind` Schema 增补由写入方（SCH-004）届时裁决，不得把同一组窗口宣称为已完成双时段排期。
- **提前预约窗口（SCH-D5）**：V1 不引入最短提前预约时长（PRD/SSOT 无条款）；唯一时间门禁=过去窗口过滤；最远可约由 31 天跨度上限间接约束。
- **边界**：本查询为展示投影，不构成预约授权租约；所选时段下单瞬间可能失效（"排期已变化，请重新选择"由 SCH-003/TX-001 承接）。
- **交付口径（SCH-D3/D6）**：写入方（SCH-004 后端/M-002 页面）交付前，"商家开窗→消费者查到→预约"真实链路未验收；本路由已接通SCH-002真实人员查询，但写入方及SCH-003占用权威链仍未齐备；展示可用性不等于预约成功或并发防超卖。

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

### 4.10.1 服务项目管理形态（CCR-W2-API-001 服务写入方 v0.2，2026-09-22 已批）

六路由细化 + 类目只读（MINIAPP Bearer；写请求 X-Request-Id UUID；首次创建 201、重放 200）：

| 方法/路径 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|
| GET `/api/v1/merchant/services` | query merchantId、storeId、status?、page(1..10000)/pageSize(1..100，默认20) | 本店全状态分页（含 latestRejection、submissionNo、submittedAt） | 400/401/404（无归属防枚举） |
| POST `/api/v1/merchant/services` | body 业务字段（草稿宽松）；X-Request-Id | 201 `{serviceId,merchantId,storeId,status:"DRAFT",version}` | 400/401/404/409（不可经营 SERVICE_STATE_NOT_ALLOWED）/409 异参重放 IDEMPOTENCY_KEY_CONFLICT |
| GET `/api/v1/merchant/services/{serviceId}` | query merchantId、storeId | 详情（含状态/最近驳回） | 404（非本店同响应） |
| PUT `/api/v1/merchant/services/{serviceId}` | body 业务字段 + expectedVersion；X-Request-Id | `{serviceId,status,version}` | 409 SERVICE_STATE_NOT_ALLOWED（ACTIVE/REVIEWING 不可编辑）/409 COMMON_CONFLICT（版本） |
| POST `/api/v1/merchant/services/{serviceId}/online` | body expectedVersion；X-Request-Id | `{serviceId,status:"REVIEWING",version}`（提交审核/重新提交，submitted_at/submission_no 递增） | 400（必填不齐含封面）/409（非 DRAFT/REJECTED/OFFLINE） |
| POST `/api/v1/merchant/services/{serviceId}/offline` | body expectedVersion；X-Request-Id | `{serviceId,status:"OFFLINE",version}` | 409（非 ACTIVE） |
| GET `/api/v1/merchant/service-categories` | 无参数 | `{items:[{categoryId,categoryName,sortNo}]}`（仅 ENABLED，sort_no 升序） | 401 |

业务字段（body，均可空存草稿）：`serviceName`(2-50)、`categoryId`（提交时须命中 ENABLED）、`fulfillmentType`(IN_STORE|PICKUP_DELIVERY)、`price`/`listPrice`（两位小数 String；price>0；listPrice≥price）、`durationMinutes`(1..10080)、`coverAssetId`（提交时必填且属本人 SERVICE_COVER 素材）、`applicablePetTypes`(DOG/CAT/EXOTIC/ALL 数组，ALL 互斥)、`staffRequirement`(≤200)、`verificationRequired`(默认 true)、`description`(≤1000)、`aftersaleNote`/`remark`(≤500)。PUT 为全量替换。

错误码适用面定稿（2026-09-22 与前端对齐）：

- 商家提交审核缺必填（含封面/ENABLED 类目等）→ 400 `COMMON_INVALID_ARGUMENT`（message 指明字段）；`SERVICE_REVIEW_REASON_REQUIRED` **仅**用于运营 REJECT 缺/短于 10 字意见，不覆盖商家提交场景。
- `online`/`offline`/`decision`/`force-offline` 的 `expectedVersion` 一律放 **body**（对齐 27 号写命令惯例，不放 query）。
- 商家工作台列表/详情字段定稿：`serviceId/merchantId/storeId/serviceName/categoryId/categoryName/status/price/listPrice/durationMinutes/fulfillmentType/coverAssetId/applicablePetTypes(数组)/staffRequirement/verificationRequired/description/aftersaleNote/remark/submissionNo/submittedAt/version/updatedAt/latestRejection{decisionId,submissionNo,decisionType,opinion,decidedAt}`；草案名 `latestDecision` 定稿为 `latestRejection`（仅承载最近一次 REJECT；APPROVE 不出现在工作台列表行）。
- C 端封面字段定稿：`cover.coverUrl`（可空 String，仅可见时返回，签名 URL）、`cover.coverAssetId`、`cover.coverUrlExpiresAt`。

状态机与门禁见 07 号 §5.2/§5.3（商家任何动作不产生 ACTIVE；商家/门店不可经营 409；无归属 404 防枚举）。

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

## 5.9 服务审核与治理（CCR-W2-API-001 服务写入方 v0.2，2026-09-22 已批）

```text
GET  /api/v1/admin/services
GET  /api/v1/admin/services/{serviceId}
POST /api/v1/admin/services/{serviceId}/decision
POST /api/v1/admin/services/{serviceId}/force-offline
```

真实运营会话（ADMIN_WEB Bearer）+ 动作码 `service.review.read` / `service.review.decide` / `service.force.offline`；决定与强制下架在事务内做数据库权威复核。运营不代商家新增/编辑/上下架服务（PRD 运营端 §3.3，无对应路由）。

> 动作码拼写更正（2026-09-22 实现阶段）：AUTH 域动作码词法为小写点分段（`AdminActionCheckQuery`：`[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+`），已批意图"forceOffline"按词法落地为 `service.force.offline`；语义与裁决不变，PR 披露。

| 方法/路径 | 动作码 | 请求 | 成功 data | 关键错误 |
|---|---|---|---|---|
| GET `/api/v1/admin/services` | service.review.read | query status?/categoryId?/merchantId?/page/pageSize | 审核分页（submittedAt、slaRemainingMinutes（按 24h SLA）、rejectCount、submissionNo） | 400/401/403 |
| GET `/api/v1/admin/services/{serviceId}` | service.review.read | — | 详情 + `decisions[]` 历史驳回记录（append-only） | 404 |
| POST `/api/v1/admin/services/{serviceId}/decision` | service.review.decide | body `{decisionType: APPROVE|REJECT, opinion?, expectedVersion}`；X-Request-Id | `{serviceId,status,version,decisionId}`（APPROVE→ACTIVE；REJECT→REJECTED） | 400 SERVICE_REVIEW_REASON_REQUIRED（REJECT 缺/短于10字意见）/409（非 REVIEWING 或版本失配） |
| POST `/api/v1/admin/services/{serviceId}/force-offline` | service.force.offline | body `{reason, expectedVersion}`；X-Request-Id | `{serviceId,status:"OFFLINE",version,actionId}`（落治理审计） | 400（reason 10-500）/409（非 ACTIVE） |

- 审核决定（APPROVE/REJECT）在决定事务内写 `ServiceReviewedEvent.v1` 到事务性 Outbox（Event08）；通知消费侧（MERCHANT 收件箱）由通知域切片承接，未接通前完整审核流程不标完成。
- 强制下架是否通知商家＝剩余问题（本轮不发事件）。

---

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


# AUTH-001 权威HTTP契约同步候选（2026-09-14）

状态：ACCEPTED_MAPPING / SYNC_CANDIDATE_NOT_IMPLEMENTED。本阶段尚未合并；不表示API上线、Schema已迁移或真实鉴权验收。映射来源为人工已接受的AUTH-001-draft-v2/head2963e391，审批回执e588369随PR14合入develop643f05cd。D1/D2及SSOT§25取消MFA不重复审批；存储新方案、Provider事实、冻结未明确写动作及内部未定字段未被同步批准。

以下复制已接受字段/行为作为HTTP同步候选；“提案/建议”指该被接受设计及其仍未落地的实现，不赋予生产调用能力。旧AUTH待规范说明与本候选冲突时，按本附录、23号公共约定及SSOT辨明当前状态。原4个登录/重置路由沿用，不重建并行入口；全量36 AUTH操作及OpenAPI operationId清单见[同步交接](../../planning/ccr/AUTH-001/contract-sync-handoff.md)。原16交易操作语义不变。

OpenAPI新增仅表达已接受外部schema；Web绑定cookie传输名__Host-pet-admin-attempt与Origin参数为既有绑定语义的具体线协议映射。refreshToken位于body，OAS3标准Security Scheme不能表达body凭据，因此其security=[]必须结合x-auth-mode=refresh-body、x-auth-credential=body.refreshToken以及必填AuthRefreshRequest理解为专用认证，绝非匿名放行。只有两端attempt创建是真匿名bootstrap。phone-binding两种凭据和body必须匹配且不能同时提交两种凭据。

所有新写操作X-Request-Id必填；两个attempt创建首次201，丢失首次秘密不能只凭UUID成功重放（409明确重新开始、无第二attempt副作用）。运营账号创建首次201、当前授权通过后的成功重放200。其他操作200与既定错误。错误data必须null；分页/敏感/范围约束不能只由schema代替服务端检查。

## 共享小程序与独立运营Web会话

## 2. 公共数据类型与响应

沿用HTTP10 ApiResponse：`{code,message,data,traceId}`；成功code=`SUCCESS`，错误data=null，traceId按本次请求生成/传递。所有ID为23号正Long十进制String；opaque token、nonce、code不是业务ID。日期为带偏移ISO-8601，输出固定三位毫秒；输入整数秒合法，非零亚毫秒拒绝。未知字段/重复JSON键/错误类型拒绝400；未注明可空的字段必填且非null；password/code/token不trim、不进入普通日志。分页page为正整数默认1，pageSize为1–100整数默认20，非法值400；Page响应items数组、page/pageSize整数、total非负整数（超过JS安全整数时须先升级契约，不截断），超末页空items但total不变；排序由各查询固定，不接SQL字段名。

| 类型 / 字段 | 约束与权威来源 |
|---|---|
| SessionGrant.accessToken | 256位以上随机opaque凭据，仅认证端通过HTTPS发给持有效认证尝试的客户端；示例只用不可用占位符 |
| tokenType | 固定Bearer |
| expiresAt | 服务端时钟计算；小程序建议15分钟，Web受30分钟无操作及账号session generation约束 |
| refreshToken / refreshExpiresAt | 仅小程序有；随机独立秘密，建议30天绝对有效期、不因刷新延长；Web无此字段 |
| sessionId / userId | String；会话记录及完成验证的用户账号ID；Web改为operatorId且不返回userId |
| audience | MINIAPP或ADMIN_WEB，来自服务端配置，不接受请求指定以升级 |
| CurrentSession | sessionId、userId或operatorId、audience、expiresAt；小程序另有phoneMasked(String)、merchantEntry(见准入)；Web另有idleExpiresAt |
| AuthAttempt | 创建响应含attemptId(String)、attemptToken(秘密)、expiresAt、nextStep；后续进度响应不再含attemptToken。建议尝试绝对有效10分钟；nextStep=PROVE_IDENTITY/VERIFY_PHONE/COMPLETED。Web只用PROVE_IDENTITY/COMPLETED；小程序VERIFY_PHONE仅原微信主登录手机号验证流程。不是业务Bearer，不返回商家授权 |
| phone | 依原PRD11位ASCII手机号，提案模式`^1[0-9]{10}$`；真实证明来自微信/SMS，不信任输入号码；不接受任意国际号码作为隐式扩展 |

所有敏感响应`Cache-Control: no-store`，禁止缓存access/refresh/attempt秘密到通用业务查询缓存、URL、遥测。Web access只存内存；同源Secure/HttpOnly/SameSite=Strict绑定cookie配合Origin校验保护普通登录交互，cookie本身不是业务Bearer，也不是用户额外认证因素。跨域部署须另行评审CORS/CSRF，不假设配置已存在。小程序使用平台受控本地存储，退出清理；两端退出不是注销账号。

## 3. 路径、请求与状态

下表请求body只列字段名，精确类型取本节及§2。除GET外要求23号完整UUID `X-Request-Id`。现有路径仅“已列用途”，响应仍是新提案。请求不得传operatorId/staffId作为授权。

| HTTP路径 | 已有/新增 | 请求 | 成功与认证要求 |
|---|---|---|---|
| POST /api/v1/c/auth/attempts | 新 | `{purpose}`；枚举WECHAT_LOGIN/SMS_LOGIN/PASSWORD_LOGIN/PASSWORD_RESET | 匿名创建仅限认证的尝试，201 AuthAttempt；限流，不产生用户/商家事实 |
| POST /api/v1/c/auth/wechat-login | 既有 | `{attemptId,wechatCode,phoneCode?}`；code为1–512字符opaque，空串拒绝 | `X-Auth-Attempt`秘密；服务端交换真实微信证明；缺手机号验证200 nextStep=VERIFY_PHONE且无SessionGrant，完整才200 SessionGrant |
| POST /api/v1/c/auth/sms-codes | 新 | `{attemptId,phone,purpose}`；purpose=LOGIN/RESET_PASSWORD | X-Auth-Attempt；200 receipt={challengeId,expiresAt,resendAfterAt}仅表示Provider确认接受发送，不返回验证码；未知发送状态503+原attempt原key查询，不自动重发 |
| POST /api/v1/c/auth/sms-login | 既有 | `{attemptId,challengeId,phone,code}`，code六位ASCII | X-Auth-Attempt，真实一次性SMS证明；200 SessionGrant；账号不存在时按首次验证码注册且不设密码 |
| POST /api/v1/c/auth/password-login | 既有 | `{attemptId,phone,password}`；password 8–64字符为本提案技术建议，不改变已存密码验证兼容策略 | X-Auth-Attempt；200 SessionGrant；无密码/错密/不存在统一401同文案，不泄露password_enabled |
| POST /api/v1/c/account/password/reset | 既有 | `{attemptId,challengeId,phone,code,newPassword}`；newPassword与提案密码8–64字符约束一致 | X-Auth-Attempt且attempt目的PASSWORD_RESET、challenge目的RESET_PASSWORD；200 `{updated:true}`，成功撤销该用户旧access/refresh；不自动登录，不产生平台支付密码 |
| POST /api/v1/c/account/phone-binding | 新 | `{phoneCode,attemptId?}`；phoneCode同§3 code约束 | MINIAPP Bearer或已验证微信的X-Auth-Attempt二选一；attempt链路attemptId必填，Bearer链路禁止attemptId；换绑200 `{phoneMasked}`；初次完整认证200 SessionGrant并将原attempt置COMPLETED |
| GET /api/v1/c/auth/session | 新 | 无body | MINIAPP Bearer，200 CurrentSession |
| POST /api/v1/c/auth/refresh | 新 | `{refreshToken}` | 专用刷新秘密，200 SessionGrant；不接受ADMIN_WEB凭据 |
| POST /api/v1/c/auth/logout | 新 | `{}` | 当前MINIAPP Bearer，200 `{loggedOut:true}`；同会话重复退出仍安全，不返回业务数据 |
| GET /api/v1/c/auth/attempts/{attemptId}/result | 新 | 无body；可选requestId查询参数限定已提交命令的UUID | X-Auth-Attempt；200 AttemptResult（下文）；未知/已过期401/503按§5，不能凭attemptId取令牌 |
| GET /api/v1/c/auth/attempts/{attemptId}/sms-intents/{requestId} | 新 | requestId是原发送命令UUID，不是此次GET新key；X-Auth-Attempt | 200 SmsIntentStatus，状态联合见下文；权限/依赖读取失败用401/503且data=null |

短信默认要求Provider确认接受发送后200，否则503并查原发送意图；不把受理当短信已送达或认证成功。投递成功也不等验证码验证成功。challengeId为String；建议SMS验证码6位/5分钟，重发间隔60秒，最多5次错误后该challenge失效；实际Provider上限若更严须按能力评审，不静默放宽。purpose必须和attempt创建目的相符，不能用LOGIN验证码重置密码。

AttemptResult必填attemptId、nextStep、expiresAt；可选commandResult仅当请求requestId属于该attempt已绑定命令且结果允许当前读取时返回，结构`{requestId,kind,data}`，kind=SESSION_GRANT/PASSWORD_RESET/SMS_ACCEPTED，data对应上表/下表成功data。没有指定requestId只返回流程进度，不能枚举所有敏感历史结果。未完成且无确定结果返回503；VERIFY_PHONE进度可200；SESSION_GRANT秘密恢复窗口关闭401，不再次签发；PASSWORD_RESET最小updated事实在attempt有效期内仍可读取。接收phoneCode是新的明确验证步骤，用新requestId，不修改原wechat-login同key已绑定的参数。Bearer换绑不属于attempt结果，按原phone-binding接口/原key/原参数及当前有效Bearer重放最小phoneMasked回执。

用途绑定矩阵：WECHAT_LOGIN仅wechat-login/初绑；SMS_LOGIN仅sms-codes(purpose=LOGIN)/sms-login；PASSWORD_LOGIN仅password-login；PASSWORD_RESET仅sms-codes(purpose=RESET_PASSWORD)/password-reset。Web attempt由其独立路径固定ADMIN_LOGIN，仅login/captcha。SMS challenge同时绑定attemptId、经过服务端规范化的同一phone、purpose及有效期，不能跨attempt/phone/purpose复用；不匹配统一验证失败。发送POST未定或Provider拒绝均503，但客户端从上述sms-intents读取明确机器状态，不解析中文决定重发。短信仅上述原主登录/重置用途，不提供Web登录后的追加校验或高权限升级用途。

SmsIntentStatus=`{requestId,status,nextAction,challengeId?,expiresAt?,resendAfterAt?}`。status=PENDING/UNKNOWN时nextAction=QUERY_SAME_REQUEST，后三字段禁止返回；ACCEPTED时nextAction=ENTER_CODE且后三字段必填String ID/时间（原有效期，不续期）；REJECTED时nextAction=START_NEW_ATTEMPT，后三字段禁止。REJECTED只表示Provider明确拒绝且无成功投递事实，不表示账号不存在；UNKNOWN不能变为REJECTED。200 UNKNOWN表示数据库已知意图未知，查询数据库失败则503/data=null。禁止客户端用新attempt绕过未决原发送意图：服务端按受保护phone/purpose及有效窗口关联未决发送并限流，未知须查证或人工处理，不自动二次发送。验证码已过期的ACCEPTED可读原expiresAt但不能继续验证，新的发送须明确新意图并受间隔约束。

phone-binding初次绑定与换绑必须分目的和已验证主体；Bearer换绑必须证明当前会话及新的微信手机号授权，若证明的微信身份与当前用户关联不一致拒绝；不允许以手机相同静默合并。号码被其他账号占用返回409 COMMON_CONFLICT、统一联系客服，data=null，不返回对方ID或信息。绑定/重置与邀请/注销业务没有跨模块大事务；这些下游副作用须原Owner设计事实交付。

## 4. 会话生命周期与Web

推荐opaque会话存服务端可撤销记录，token只存不可逆摘要。Redis可缓存但不能在权威状态不可查时放行。小程序access到期可用有效refresh；refresh每次轮换，旧refresh仅在同requestId/同参数/同设备绑定的60秒响应恢复窗口内返回同一当前grant；不同key再次使用已消费refresh拒绝并撤销该refresh family。窗口外旧key不能重新发新令牌，要求重新登录；不以全局requestId缓存永久明文令牌。并发刷新由客户端single-flight避免；重放检测细节、加密回执、秘密销毁与墓碑见§6。

本版“同设备绑定”不采用客户端deviceId：小程序以该refresh秘密所绑定的服务端session/family为认证边界；Web使用服务端随机绑定cookie，不承诺硬件设备身份。refresh恢复直接重试refresh接口，携原refreshToken和原X-Request-Id，不走AuthAttempt查询。按已批准B1，窗口由最终成功提交前数据库时间锚点加60秒固定；实际提交后可恢复时间可能不足60秒，读取不续期。这是找回登录结果的窗口，不是会话有效期。

轮换与logout在同一family/session记录原子CAS排序，并核账号generation及ACTIVE；密码重置/账号禁用递增账号generation并与该Owner的签发/轮换检查串行化。撤销先提交，后轮换不能生成可用token；轮换先提交，退出撤销该family新旧所有凭据。禁用/重置后旧generation签发必须失败。Web账号密码验证后的签发最终提交仍核当前账号状态/generation，防止在途签发复活已禁用账号；新会话签发及踢旧generation原子提交。事务/唯一键/跨记录锁顺序须后续Schema审查，不跨biz实施。

Web复用Bearer标准，但独立以下新增提案接口：

| HTTP路径 | body / 约束 | 响应 |
|---|---|---|
| POST /api/v1/admin/auth/attempts | `{}`；匿名限流，X-Request-Id | 201 AuthAttempt及受限绑定cookie |
| POST /api/v1/admin/auth/login | `{attemptId,account,password,captchaProof?}`；X-Auth-Attempt+绑定cookie/Origin；account=1–128字符的手机/邮箱/工号；password8–64，不归一密码；服务端按登记的账号别名规则查找 | 所有角色普通账号密码验证完成后200 Web SessionGrant；无角色分流的额外认证步骤 |
| GET /api/v1/admin/auth/attempts/{attemptId}/requirements | X-Auth-Attempt+绑定cookie/Origin | 200 `{requiredVerification}`，枚举NONE/CAPTCHA；仅登录反机器人条件，按同形失败策略计算，未知账号也有同类限流 |
| POST /api/v1/admin/auth/captcha/challenges | `{attemptId}`；X-Auth-Attempt+绑定cookie/Origin | 200 `{captchaId,imageDataUrl,expiresAt}`；captchaId String，imageDataUrl受限PNG data URL≤256KiB，120秒；实际生成器未装配503 |
| POST /api/v1/admin/auth/captcha/verify | `{attemptId,captchaId,answer}`；answer1–32字符，X-Auth-Attempt+绑定cookie/Origin | 200 `{captchaProof,expiresAt}`；proof为opaque120秒一次性、绑定attempt，供login条件必填字段，不是业务权限 |
| GET /api/v1/admin/auth/session | ADMIN_WEB Bearer | 200 CurrentSession及当前authzVersion(String版本标签，不是授权凭证) |
| GET /api/v1/admin/auth/attempts/{attemptId}/result | X-Auth-Attempt+绑定cookie/Origin；可选requestId同小程序 | 200 AttemptResult，仅本Web attempt，不接受小程序attempt |
| POST /api/v1/admin/auth/logout | `{}`；ADMIN_WEB Bearer | 200 `{loggedOut:true}` |
| POST /api/v1/admin/auth/activity | `{}`；ADMIN_WEB Bearer、X-Request-Id | 200 `{idleExpiresAt}`；只有前台真实交互才调用；后台轮询、自动刷新、旧key重放不能延长空闲 |

已有30分钟无操作由服务端lastInteractiveAt执行，前端计时仅提示。activity仅可在未过期时延后，重复key不能重复延后；查询轮询不重置。Web access有效至当前idleExpiresAt并以每次会话事实核验为准，activity只延长同一有效会话的服务器期限，业务Bearer不依JWT静态exp；响应expiresAt是当时期限快照，不是允许离线验证的永久声明。会话查询返回最新期限。页面刷新丢内存Bearer则重新登录，不从cookie生成业务权限。

账号禁用/密码重置立即使后续身份检查失效；权限变更刷新authzVersion并使旧授权缓存/在途结果失效。新增高权限角色或extraAction不触发额外认证因素；下一次动作按当前角色、动作、范围及业务资格重新检查。角色停用撤销对应授权，是否完全退出按账号身份仍有效与否区分，不因角色名称要求第二步登录。

建议Web密码连续5次失败需图形验证码，10次/15分钟暂锁15分钟；未知账号采用等形限流避免枚举，同时以IP/尝试防滥用。C密码路径本版仅429频控，不套用Web图形验证码要求；其失败阈值建议10次/15分钟锁15分钟。上述阈值已在D1方案范围接受，待权威Contract同步；图形验证码是登录反机器人措施，不作为已登录高权限动作的额外证明。原C端短信主登录/密码重置的Provider、验证码证明仍需实际接入，不能用固定验证码宣称成功。Web仍无自助找回，按原规则联系管理员处理账号密码；不配置额外因素、因素绑定或丢失恢复。

## 5. 错误、刷新和页面恢复

| 场景 | HTTP/code | 客户端处理 |
|---|---|---|
| 当前会话的受保护小程序请求401 | 401 COMMON_UNAUTHORIZED | 先暂停业务并失效旧epoch；若持refresh则仅保留该秘密single-flight刷新一次，失败才清该端全部凭据；无refresh直接清。旧session/epoch的401丢弃，不处理新会话 |
| 小程序refresh成功 | 200 | GET按新上下文重查；写仅以原key/原参数显式重试，不能未知结果自动换UUID；不根据401中文猜到期/撤销，刷新端核真实状态 |
| Web无效/过期/撤销或跨audience凭据；小程序refresh被拒 | 401 COMMON_UNAUTHORIZED | 清该端凭据/缓存/epoch；仅保留安全导航意图，重认证及重新准入后恢复；不清另一端 |
| 普通动作/范围被撤销 | 403 COMMON_FORBIDDEN | 不自动登出同微信用户；清相关授权缓存、废弃在途结果并刷新准入/权限；无权页面安全返回 |
| 他人资源/认证attempt枚举 | 404 COMMON_NOT_FOUND或不透露存在性的401 | 固定策略，message不披露对象存在；不返回旧data |
| 身份、权限或Provider事实查询失败 | 503 COMMON_DEPENDENCY_UNAVAILABLE | 关闭受保护路径，保留安全导航，显示重试；不是401、不是签约失败/成功 |
| 验证码/密码错误、无密码、不存在账号 | 401 COMMON_UNAUTHORIZED | 一致“账号或密码错误”或统一“验证失败”，不披露哪一项存在 |
| 非法参数/非法ID | 400 COMMON_INVALID_ARGUMENT | 修正输入，不自动重试 |
| 同key异参 / 并发忙 | 409 IDEMPOTENCY_KEY_CONFLICT / COMMON_CONFLICT | 前者不可自动重试；后者仅明确忙语义原key稍后重试 |
| 频控 | 429 COMMON_RATE_LIMITED | 到达服务端Retry-After再试，不绕过验证码/锁定 |

此表已逐字核对12号通用码登记，不新增全局错误码。错误message用于展示，不能让客户端解析中文决定授权；准入业务原因码通过成功的资格查询返回，详见准入文档。

## 6. 认证写入、秘密回执与公共幂等

认证前没有USER主体。推荐认证模块专属AuthAttempt幂等适配器，不假冒SYSTEM、不扩OperatorType，不直接接公共业务执行器。全部写接口保留UUID和同参/异参约束；认证尝试只承担受限证明交换。此为对23号“匿名映射由AUTH负责”的具体提案，须Contract Owner批准及后续逐命令实现设计，S1检查工具不提供该能力。

1. attempts创建先持久绑定requestId和用途，生成不可预测attemptId/attemptToken；限流依据不是业务权限。无业务账号写入；原请求重复不再新建attempt。首次响应秘密若丢失，原key只返回409重新开始提示，不能凭requestId重放秘密；用户显式新尝试可换key，旧尝试过期。不能通过此例外类推业务写入换key。
2. 后续调用须attemptId+attemptToken（Web另需cookie/Origin），服务端定位已验证身份；requestId只去重，不是秘密。微信/SMS一次性凭据在同attempt同key绑定后不可换参复用；Provider交换未知保留原意图查证，不能凭网络失败新建用户。没有上游查证能力的短期凭据失败应重新证明，持久业务唯一约束仍避免重复账号。
3. 完整身份验证成功后，账号创建/绑定、会话事实和最小完成回执在所属模块本地事务内一次提交；Phone唯一、微信(appId,openId)唯一均由DB兜底。若两个已存在账号证明相冲突拒绝，不自动合并。登录成功日志按原PRD故障降级告警，不能因日志不可用把身份猜成功。
4. 最小永久回执不含原密码、主登录/重置OTP、wechatCode、access/refresh秘密，仅保存结果标识与安全墓碑；短期秘密响应加密、最小读取权限，按B1最终成功提交前的数据库时间receipt_window_anchor_at固定secret_expires_at=anchor+60秒；提交确认后只发布剩余TTL，所有读取重核该截止，读取不续期，可能短于提交后完整60秒。SESSION_GRANT窗口内持原attempt秘密同key且新签发会话仍有效才可恢复；窗口外/撤销401，不再次签发。PASSWORD_RESET只允许仍有效受限attempt取得原key最小updated事实，不要求旧登录会话有效或一次性证明尚未消费；不返回新的登录能力，attempt到期后401。PHONE_BOUND换绑通过当前有效Bearer和原命令重放，不经attempt。新主认证须新证明。参数等值使用受保护摘要，不保存明文密码或易枚举OTP裸hash；采用服务端密钥HMAC及最小必要加密记录，密钥策略为实现门禁。
5. refresh、密码重置、手机号绑定均有自身稳定namespace和可信主体/attempt作用域；完成后重放仍核当前身份/敏感可读性。不能重放密码来获得新授权；重置成功不把重置码再次当有效一次性证明。

逐命令持久化、Provider未知恢复、短期加密/清载荷仍须后续设计和组件验证；这份协议提案没有实现公共幂等/S2，不能宣称登录重试已验收。

## 商家工作台选择与准入

## 1. 选择与授权分开

推荐不创建服务端“切换身份”写命令。C/M同一MINIAPP会话；客户端仅保存当前展示选择，每次进入/返回前台通过服务端准入查询确认。URL/query中的merchantId/storeId只是候选资源；服务端从已认证userId、merchant.owner_user_id及明确子账号绑定/授予解析关系，不能信任客户端staffId、角色或workspace。

建议如下新增查询；现HTTP工作台dashboard路径只提供业务看板，不复用其DTO充当身份授权：

| 路径 | 请求及约束 | 成功data |
|---|---|---|
| GET /api/v1/c/auth/merchant-memberships | MINIAPP Bearer；page/pageSize，固定merchantId、storeId按Long数值升序 | `{items:[Membership],page,pageSize,total}`；仅本人可选择范围；total也先过滤 |
| GET /api/v1/merchant/auth/admission | MINIAPP Bearer；merchantId、storeId必填String，禁止staffId/workspace参数 | Admission；每次进入重查，不能使用前端旧allowed作为缓存命中 |

Membership必填：merchantId、merchantName(1–128字符)、storeId、storeName(1–128)、membershipKind=OWNER/STAFF；staffId仅STAFF时服务端可返回用于显示历史操作主体，不能变成下次请求授权依据；OWNER不虚构一个staffId。无门店的入驻申请不放进门店列表，由CurrentSession.merchantEntry引导。主账号多门店与子账号多店只展示已有明确归属/授予；不是允许客户端创建跨商家关系。SQL现状仅merchant.owner_user_id唯一，子账号绑定持久化缺口单列，不能用电话匹配员工。

CurrentSession.merchantEntry提案：`{kind,applicationId?}`；kind=APPLY/APPLICATION_DRAFT/APPLICATION_PENDING/APPLICATION_REJECTED/SIGNING_REQUIRED/WORKSPACE_AVAILABLE。有任一当前可正常或受限进入的OWNER/STAFF关系时kind=WORKSPACE_AVAILABLE，不需要本人applicationId；点入仍选店重验，包含冻结/下线受限关系。否则按本人申请：无申请→APPLY且无ID；DRAFT→APPLICATION_DRAFT续填、REVIEWING→APPLICATION_PENDING、REJECTED→APPLICATION_REJECTED并必填本人applicationId；APPROVED而未满足正常资格SIGNING_REQUIRED并必填本人申请ID。若本人待审但已获他店STAFF身份，优先WORKSPACE_AVAILABLE，入驻进度仍由申请页面独立展示；不引用他人申请详情。当前Schema没有完整申请事实，依赖缺失/查询失败503，不能把null默认通过；真实Adapter未接入前不生成入口可用的公共Mock。

## 2. Admission字段和来源

| 字段 | 类型 / 空值规则 | 来源与含义 |
|---|---|---|
| merchantId / storeId | String，必填 | 已验证属于当前会话可访问关系的目标 |
| membershipKind | OWNER/STAFF | 真实主账号/子账号绑定；与服务人员岗位不同 |
| admission | ALLOWED/LIMITED/DENIED | 仅入口模式；不是对所有资源的授权。未决冻结写动作不转换为一个生产布尔值 |
| checkedAt | 带偏移毫秒时间，必填 | 本次权威事实查询完成时刻，不是可重复使用的通行证 |
| authzVersion | String 1–128，必填 | 身份/归属/授权变化的服务端版本标签；客户端只做失效识别 |
| facts.application | null或`{status}` | null=权威查询确认无申请；status=DRAFT/REVIEWING/APPROVED/REJECTED；新增英文wire码对应PRD四态 |
| facts.signing | `{status}` | status=NOT_SIGNED/SIGNING/SIGNED/FAILED/UNKNOWN；前四对应PRD，UNKNOWN是技术未知，不是新产品成功态 |
| facts.storeStatus | ACTIVE/FROZEN/OFFLINE | 门店权威状态；未知不映射ACTIVE |
| facts.merchantStatus | APPLYING/ACTIVE/OFFLINE/FROZEN/CANCELED | 商家权威状态，独立于门店；需按组合复核 |
| facts.staffEnabled | Boolean或null | STAFF必填Boolean，OWNER固定null表示不适用，null绝不表示已启用 |
| allowedActions | String[]，唯一排序 | 本次已确认范围内的动作码提示，见§3；空数组不证明正常业务权限；执行仍查资源资格 |
| reasonCodes | String[] | NONE不使用；允许时可空数组；拒绝/受限应给具体原因：NO_APPLICATION/APPLICATION_PENDING/APPLICATION_REJECTED/SIGNING_REQUIRED/SIGNING_FAILED/SIGNING_UNKNOWN/MERCHANT_OFFLINE/STORE_OFFLINE/MERCHANT_FROZEN/STORE_FROZEN/STAFF_DISABLED |
| nextSteps | Step[]，必填 | 每个Step仅`{type}`；type=APPLY/VIEW_APPLICATION/COMPLETE_SIGNING/CONTACT_SUPPORT/VIEW_EXISTING_ORDERS/VIEW_AFTERSALES/APPEAL；客户端将固定type映射白名单路由，不传任意重定向URL |

签约详情与外链单独由签约Owner定义：只有主账号获权时可见脱敏失败说明和经校验域名的短期签约入口；子账号不读取主账号结算/签约敏感详情，不直接透传Provider原包、证照或bank account。未冻结Provider前本包不新增签约启动/回调endpoint、provider enum或成功样例实际映射。

## 3. 准入矩阵与动作

判定先身份有效及归属，再子账号启用，再分别计算业务资格和例外，不能以商家正常新单资格提前返回整体禁入。无归属的资源403或防枚举404，查询不可用503且data=null；这是失败关闭，区别于成功查询返回DENIED/UNKNOWN状态。

| 已核事实 | 提案入口 | 已定允许范围 | 未定/拒绝范围 |
|---|---|---|---|
| 无申请、草稿、审核中、驳回 | DENIED | C端申请/进度引导，非商家正常工作台 | 正常经营动作不开放 |
| 已通过、未签/签约中/失败 | 无已证实例外则DENIED；例外非空则LIMITED | 主账号签约引导、子账号不敏感提示；已独立证实的存量履约/售后关系进入受限资源入口 | 正常经营不开放；不以缺签约事实抹掉已定责任 |
| 签约UNKNOWN | 无已证实例外则DENIED；例外非空则LIMITED | 可独立验证的既有存量例外由资源策略处理并返回相应allowedActions/nextSteps | 不默认成功，也不把未知当签约失败；事实查询整体失败503不伪造例外 |
| 已通过已签、商家门店ACTIVE且成员有效 | ALLOWED | 当前获授动作与范围 | 非本人店、未授动作、业务资格不符仍拒绝 |
| merchant/store OFFLINE且成员有效 | LIMITED | 历史读取、存量履约、退款/售后、处罚查看及已有申诉；每笔由订单/售后Owner核存量关系 | 新预约/新经营流量不得开放；不能代商家接单规则倒置到运营 |
| merchant/store FROZEN且成员有效 | LIMITED | 历史订单、待处理售后读取、处罚原因及申诉 | 冻结核销、退款处理、售后补证等写动作是B-FROZEN-WRITE；不得将SPEC_GAP当已批准的一律拒绝或允许 |
| 子账号停用/归属撤销 | DENIED | C端个人身份可仍有效；历史审计保留 | 子账号不能继续以历史staffId访问；存量责任由仍获权主账号/运营依法定产品动作处理，不给已撤账号保留写权 |
| 状态组合互相矛盾/来源不完整 | 不返回正常允许 | 明确错误/待核来源，已独立证实例外按资源契约 | 不按枚举优先级猜SIGNED或ACTIVE；具体矛盾BLOCKER |

建议工作台提示动作码（均拟议，非授权清单已批准）：`merchant.order.read`、`merchant.order.fulfill`、`merchant.refund.handle`、`merchant.aftersale.read`、`merchant.aftersale.respond`、`merchant.penalty.read`、`merchant.penalty.appeal`、`merchant.schedule.manage`、`merchant.service.manage`、`merchant.staff.manage`。`fulfill/handle/respond`仍是入口提示组，不直接作为每个命令的最终权限码；逐命令合同映射由业务Owner收窄，避免一枚入口权限授予全部行为。冻结已定读取与申诉可先规范，未定写入不生成可执行公共Mock。

商家主账号、门店店长、核销员、排期负责人来自PRD§5.2/5.6；服务人员记录不自动成为登录子账号。建议主账号管理所属店授权，店长仅获授门店管理；核销员仅获授订单核销与必要订单读取；排期负责人仅获授排期/人员时间配置，均不能看改主账号结算签约。精细授权初始化、子账号邀请/账号绑定需后续与MER-001逐字段交接，不能因这些角色名称直接放行业务API。

## 4. 切换、深链与在途

进入商家页面、重新show、用户/门店/账号变化时先失效旧epoch并清商家数据，展示checking。只在本次成功准入返回且epoch仍当前时显示内容。DENIED/503均不显示旧业务缓存；LIMITED只呈已确认入口。返回C端不登出，清商家敏感缓存；其他身份范围不作为缓存共享键。

缓存键至少包含audience/sessionId/userId/workspace/merchantId/storeId/本地epoch，业务查询再加资源/过滤；角色/范围/authzVersion变化失效该范围。scope标签由客户端用于隔离，不作服务端许可。请求发出时捕获epoch，成功和错误回包均比较，旧401/403不能清掉新的不同会话。页面卸载的旧清理回调也不得撤销新页面准入。

深链仅存白名单路由和String ID。未认证先登录，已认证仍调用准入和资源检查；不根据深链中staffId/workspace设权。重新授权后恢复原页仅在当前关系和资源仍有权时进行，不能恢复旧业务草稿中的密码、主登录/重置验证码等秘密，或自动执行原写动作。


## 运营角色、数据范围、管理与敏感规则

## 1. 角色和目录

| 稳定roleCode提案 | 已定名称 | 默认含义 |
|---|---|---|
| CONTENT_EDITOR | 运营编辑 | 平台内容获权编辑及直发；不因编辑有退款权 |
| REVIEWER | 审核员 | 已获权商家/用户内容业务审核、售后处理；无第二审核人 |
| OPERATIONS_ADMIN | 运营管理员 | 获权治理、配置、异常处置；不是超管别名 |
| FINANCE_READER | 财务只读 | 默认查看已批准财务事实；已有执行动作可显式授予 |
| AUDIT_READER | 审计只读 | 审计查询及获准导出，不存在日志修改/删除能力 |
| PLATFORM_SUPER_ADMIN | 平台超级管理员 | 默认全部已批准V1动作/全平台范围，仍核业务资格/脱敏/审计 |

角色code不可变，展示名可变；权限只取服务端已登记actionCode及真实授予。roleId为String主键，roleCode不能被普通角色编辑成超管；超级管理员身份由受控授予关系决定，不由名称或请求中的superuser=true决定。角色不可删除，仅停用。六角色是模板，不要求六人；不设计第二审批者、内部审批队列或本人复核隔离。

目录条目提案：`{actionCode,label,resourceType,riskLevel,requiresPurpose,requiresConfirmation,requiresSensitivePolicy,delegable,availability}`。actionCode小写ASCII字母/数字/点/连字符，1–100字符；label1–100字符、resourceType1–64稳定ASCII标识；riskLevel=READ/NORMAL/HIGH；requires*及delegable为Boolean；availability=PROPOSED_ONLY/CONTRACT_READY。delegable默认false，只有Contract Owner在已批准业务动作清单显式标true才可普通转授，管理/超管类固定false。本包所有新增码均PROPOSED_ONLY，只有对应业务Contract获批并同步后可注册到生产可授予目录。后台不接受客户端上传新权限码；未知码拒绝400，未知实际业务动作默认403。不能把未决资金码注册后靠按钮隐藏。

## 2. 原27行矩阵逐行映射

来源：运营PRD§4.2 word/document.xml body[49]。下列为**建议默认模板**；R=仅查看，D=该行已批准执行动作及必要查看，—=默认不授予。列序编/审/管/财/稽；超管单独按已批准目录闭包。▲→获权单人执行；○不推出导出/明文；●没有对应写业务时不创造写动作。权限码皆拟议，组内具体业务DTO仍归原Issue。

| # / 原行 | 建议动作码（同前缀按逗号展开） | 编/审/管/财/稽 | 边界 |
|---|---|---|---|
| 1 工作台看板 | dashboard.read | R/R/R/R/R | 管●也不产生看板写权限 |
| 2 商家入驻审核 | merchant.application.read, merchant.application.decide | —/D/D/R/R | 通过/驳回/补正按既有规则，签约不是审核自动成功 |
| 3 商家台账详情 | merchant.read | R/R/R/R/R | 详情●不包所有治理 |
| 4 商家冻结/下线/解冻 | merchant.penalty.read, merchant.freeze, merchant.disable, merchant.unfreeze | —/—/D/—/R | 经营治理不是资金冻结 |
| 5 服务审核/强制下架 | service.read, service.review, service.force-offline | R/D/D/—/R | 审核与下架分别校验 |
| 6 第三方渠道授权配置 | channel-authorization.read, channel-authorization.configure, channel-service-mapping.read, channel-service-mapping.configure | —/—/D/—/R | 凭证不得明文回传/记普通日志 |
| 7 订单总览详情 | order.read, order.status-log.read, order.verification-log.read, order.abnormal-close | R/R/D/R/R | 管的异常关闭来自HTTP§5.5，禁止正常代接单 |
| 8 退款处理重推 | refund.read, refund.channel-query, refund.retry | —/—/D/R/R | 财执行须显式授予；仅原退款重试，不手工改成功 |
| 9 账本补偿队列 | benefit-compensation.read, benefit-compensation.retry | —/—/D/R/R | 仅既有券返还/积分扣回补偿，retry等原Owner合同就绪 |
| 10 资金流水/分账/结算 | payment-ledger.read | —/—/R/R/R | 仅已定支付退款事实；监管冻结/分账/结算相关查询执行均OD001 |
| 11 对账差异 | payment-reconciliation.read, payment-reconciliation.channel-query | —/—/R/R/R | 人工修复无完整合同不注册；分账/结算/提现差异仍OD001 |
| 12 提现审核打款 | 无活动动作码 | —/—/—/待决/待决 | 整行OD001；不能由财●/超管解锁 |
| 13 券模板/活动 | coupon-template.read, coupon-template.manage, coupon-campaign.read, coupon-campaign.manage, coupon-campaign.start, coupon-campaign.stop | R/—/D/R/R | 状态与规则依业务Owner |
| 14 券实例作废返还 | coupon-instance.read, coupon-instance.void, coupon-instance.return | —/—/D/R/R | 不得违反全/部分退款返券规则 |
| 15 积分任务配置 | points-rule.read, points-rule.configure, points-task.read, points-task.configure | R/—/D/R/R | 无消费/任意改余额/历史流水动作 |
| 16 用户宠物档案 | customer.read, pet.read | R/R/R/R/R | 管●不凭空产生改档案权；原备注标签需原Owner独立映射 |
| 17 封禁/注销审核/申诉 | customer-account.read, customer-account.ban, customer-account.unban, account-closure.review, account-appeal.decide | —/D/D/—/R | 注销责任校验保留 |
| 18 售后投诉裁决 | aftersale.read, aftersale.assign, aftersale.handle, aftersale.decide, complaint.read, complaint.assign, complaint.handle, complaint.decide | —/D/D/R/R | 部分退款仅售后裁决；首次正式售后结论终局，无售后复审 |
| 19 评价/申诉 | review.read, review.review, review.offline, review-appeal.read, review-appeal.decide | R/D/D/—/R | 评价申诉不是售后复审 |
| 20 内容/举报 | community-content.read, community-content.review, community-content.offline, community-content.delete, report.read, report.handle | R/D/D/—/R | 业务审核单人，不能代改用户正文 |
| 21 百科/品种 | encyclopedia.read, encyclopedia.manage, encyclopedia.publish, breed.read, breed.manage | D/D/D/—/R | 获权编辑可直发 |
| 22 基础台账/字典 | dictionary.read, dictionary.manage, banner.read, banner.manage, banner.publish | D/—/D/—/R | 不更改历史快照 |
| 23 系统参数 | system-parameter.read, system-parameter.configure | —/—/D/—/R | 只限已批准可配置参数，不改封板硬规则 |
| 24 消息公告 | announcement.read, announcement.manage, announcement.publish, notification-template.read, notification-template.configure | D/—/D/—/R | 无内部审批，不创建私信IM |
| 25 统计导出 | statistics.read；每资源独立*.export候选 | R/R/D/R/R | `*.export`是文档占位表示，不是可授通配码；须逐个登记如order.export |
| 26 账号角色权限 | operator-account.read, operator-account.create, operator-account.update, operator-account.disable, operator-account.reset-password, role.read, role.configure, role.disable, grant.read, grant.configure | —/—/—/—/R | 原管●(超管)归超管；普通账号可显式获已有管理动作，但受§3防提权边界 |
| 27 审计查看 | audit-log.read, audit-log.export | —/—/R/R/D | 稽导出也需实际获权/用途/范围，不含改删 |

D不是无限“execute”。例如财务对refund.retry/benefit-compensation.retry可显式获权，但必须合同就绪且保留原交易资格；名称“只读”不得硬编码阻止已授权动作。导出、手机号敏感查找、证据/明文查看按独立码及条件再授予，不因行有R自动可用。

禁止登记：正常代商家接单、代用户支付、改渠道事实金额/手工标记到账或退款成功、代商家提现、手改订单完成/历史快照、代用户编辑社区正文、改用户评分、篡改审计、恢复失效旧售后、售后复审、积分消费、新内部审批。OD001待定行和未知动作对超管同样不开放。

## 3. 账号、角色与范围协议

推荐账号绑定多个角色并可有账号级extraActionCodes。有效动作=启用角色actionCodes并集∪该账号显式extraActionCodes，再与已批准CONTRACT_READY目录求交；账号禁用时无动作。extraActionCodes与角色授予分开持久化、审计与原子递增版本；移除某extraAction若仍由有效角色授予则仍有权，返回计算后真实权限，不能假称撤净。无deny优先级规则，不暗中实现覆盖禁用。这样可只给某个财务账号refund.retry，不改全体FINANCE_READER模板；角色停用不自动删除独立账号grant，界面需显示其来源。账号只有一个dataScope，应用于每个有效动作，避免跨角色scope交叉组合。按角色不同范围是未来变化，本版不支持。超管有效授予强制ALL，但仍有敏感规则。

Scope提案：`{mode,cityCodes,merchantIds}`。授予请求mode=ALL/CITY/MERCHANT；ALL两数组必须为空；CITY只接受非空cityCodes(唯一、最多100，来自服务端有效城市字典)，merchantIds为空；MERCHANT只接受非空merchantIds(唯一、最多1000且真实存在)，cityCodes为空。拒绝空范围隐式ALL；无有效授予的响应用mode=NONE且两数组为空；NONE不能用于授予请求。此wire枚举是提案。

资源所属城市/商家从资源Owner API及稳定归属事实取得；不是请求筛选器，不用用户住址/IP代替。跨城市历史订单按订单下单时所属门店/城市快照过滤；当前门店/商家按当前归属过滤；缺历史城市快照不能从当前地址猜，相关历史城市范围接口保留Schema/Owner门禁。跨域关联只取最小归属，不跨Repository。

列表/计数/看板/导出先做权限过滤再分页/total，不能查全平台后前端过滤。请求筛选条件与scope取交集，不扩大授权。单资源在范围外统一404 COMMON_NOT_FOUND避免枚举；没有该动作权403。全平台资源（系统参数、全局字典、账号角色管理）需要ALL范围；CITY/MERCHANT不能通过资源空merchantId绕过。这是本版推荐保守分配方案，可由Contract Owner审阅，不按角色名称自动放行。

以下全是**新增路径提案**，统一ADMIN_WEB Bearer、写X-Request-Id，管理接口核当前动作/范围/账号状态，用途/原因/同人确认按动作元数据；不追加认证因素。

| 路径 | 请求 | 成功data / 权限 |
|---|---|---|
| GET /api/v1/admin/auth/permissions | 无body | PermissionSnapshot；所有有效Web账号只查自己 |
| GET /api/v1/admin/permission-actions | page/pageSize、resourceType? | Page<ActionDefinition>；grant.read；只返回CONTRACT_READY目录，提案环境可显式查看PROPOSED_ONLY但禁止写入生产 |
| GET /api/v1/admin/operator-accounts | page/pageSize、status?、roleId? | Page<OperatorAccount>；operator-account.read、ALL；不返回密码或认证秘密 |
| POST /api/v1/admin/operator-accounts | CreateAccount | 201 OperatorAccount；operator-account.create |
| PUT /api/v1/admin/operator-accounts/{operatorId} | `{displayName,expectedVersion,reason}` | 200 OperatorAccount；operator-account.update |
| PUT /api/v1/admin/operator-accounts/{operatorId}/authorization | `{roleIds,extraActionCodes,dataScope,expectedVersion,reason,confirmed}` | 200 OperatorAccount；grant.configure；角色/额外动作/scope整体原子替换并递增authzVersion |
| POST /api/v1/admin/operator-accounts/{operatorId}/disable | `{expectedVersion,reason,confirmed}` | 200 OperatorAccount；operator-account.disable，撤销会话 |
| POST /api/v1/admin/operator-accounts/{operatorId}/enable | 同上 | 200 OperatorAccount；提案新增operator-account.enable，纳入账号管理行，启用不恢复旧token |
| POST /api/v1/admin/operator-accounts/{operatorId}/password-reset | `{newPassword,expectedVersion,reason,confirmed}` | 200 `{operatorId,updated:true}`；operator-account.reset-password，撤销旧会话；密码不回显 |
| GET /api/v1/admin/roles | page/pageSize、status? | Page<Role>；role.read |
| PUT /api/v1/admin/roles/{roleId} | `{displayName,actionCodes,expectedVersion,reason,confirmed}` | 200 Role；role.configure；只允许既有已批准动作；roleCode不能修改 |
| POST /api/v1/admin/roles/{roleId}/disable | `{expectedVersion,reason,confirmed}` | 200 Role；role.disable；所有相关账号授权版本更新 |
| POST /api/v1/admin/roles/{roleId}/enable | 同上 | 200 Role；提案新增role.enable；不恢复旧会话令牌 |

本版固定六角色，无任意新角色创建/删除endpoint；可配置动作和名称、启停。原PRD新建账号由超管单人执行，没有secondApprover。超级角色默认全批准目录不可编辑缩成普通集合；停用/移除须遵循恢复安全规则。

字段定义：

- CreateAccount=`{account,displayName,initialPassword,roleIds,extraActionCodes,dataScope,reason,confirmed}`。account登记一种别名，1–128字符，手机号/邮箱/工号校验；displayName1–64；initialPassword8–64且至少大小写/数字/特殊字符中三类（PRD）；roleIds非空唯一String数组最多6；extraActionCodes必填唯一排序数组可空/最多500，码约束同目录；reason1–500字符、不可全空白；confirmed必须true仅同人确认，不证明动作获权。账号启用及密码初始化按当前权限和状态策略执行，不配置额外认证因素。
- OperatorAccount=`{operatorId,accountMasked,displayName,roleIds,extraActionCodes,dataScope,status,lastLoginAt,version}`；status=ENABLED/DISABLED，lastLoginAt可null，version=非负十进制String；登录别名在普通列表脱敏，受控详情展示仍需用途和审计。roleCode仅Role中返回。
- Role=`{roleId,roleCode,displayName,status,actionCodes,version}`；status=ENABLED/DISABLED；actionCodes唯一排序。固定模板初始化映射由本节/§2给出建议，不允许客户端导入通配码。
- PermissionSnapshot=`{operatorId,authzVersion,checkedAt,roles:[{roleId,roleCode,displayName}],dataScope,actionCodes}`；只返回当前有效授予；没有业务资源资格，不能代替订单actions。
- expectedVersion非负十进制String；状态变更CAS不匹配409 COMMON_CONFLICT，当前key同参重放仍先核当前权限；不能用版本“最新”替代requestId。

本版账号管理是ALL资源，只有ALL范围管理者能调用；普通获权管理者仍只能授自己当前拥有且delegable=true的非管理动作及自身scope子集。创建/授权/启用/角色启用/密码重置等所有管理路径均核目标最终有效动作/范围，普通管理者不能接管、重置或启停超管/含非转授管理权的账号，也不能修改自身或反向提升自身权限。普通grant.configure可设置目标extraActionCodes；角色增删只有其整个有效动作集合均满足可转授子集才允许，不能借FINANCE角色名绕过动作与数据范围检查。角色模板修改、管理类动作授予及超管授予仅有效超管可操作，普通账号不得编辑全局角色actionCodes。所有路径同用防提权策略，不能借新建/启用绕过授权接口。超管可单人管理自身权限，但不得停用/移除最后一个可恢复的启用超管；这是防锁死技术建议，不要求第二账号。账号初始化/密码恢复在部署手册后续设计，不增加额外因素恢复或第二人要求。

## 4. 敏感信息、导出与审计

| 数据 | 原规则与提案检查 |
|---|---|
| 用户手机 | 默认掩码；另授customer.phone.reveal，限定售后/风控/司法协查用途及关联工单，逐次审计；不存在工单/用途不符拒绝 |
| 昵称/头像、真实姓名/地址联系人 | 昵称头像常规可展示但批量导出脱敏；真实姓名、收货人、地址手机按原PRD§6.6掩码及用途控制；禁止导出地址明文用于营销，不因export或超管绕过 |
| 证照/身份证 | 默认掩码水印；另授merchant.identity.reveal，按真实审核/签约核验场景最小访问；单人用途确认代替旧内部审批，不把原图URL常驻缓存 |
| 银行账号/法人/子商户 | 原字段场景约束及掩码；银行卡明文不展示；OD001事实未冻结不造结算信息 |
| 宠物健康/体重/疫苗 | 运营默认不可见；需用户授权且关联在途订单，仅最小必要字段，不能单凭pet.read或超管显示全部 |
| 第三方完整券码/授权凭证 | 任何角色不可查看完整券码，页面/导出仅脱敏；不记普通日志/埋点 |
| 支付渠道交易号 | 仅交易/对账动作获权者可明文复制，导出水印；不能顺带导出渠道凭证或未知资金状态 |
| 导出 | 默认脱敏；单独每资源export权限+当前scope+用途，范围≤31天，水印实际操作人/时间；默认24小时短期链接（原PRD）且下载再次核当前权限；单独reveal不自动改变导出脱敏策略 |

以上reveal/export码均是独立候选，不由基础read默认派生；API路径及下载授权详细Contract由相关数据Owner制定，AUTH只定义授权检查输入：可信主体、actionCode、resourceRef、purpose、caseId、用户授权/在途事实引用。客户端purpose/caseId只提供待核声明，不能自证敏感资格。

审计记录建议：auditId、operatorType/operatorId、actionCode、resourceType/resourceId、requestId、traceId、occurredAt、sourceIp、reason/purpose、脱敏before/after、result=ALLOWED/DENIED/FAILED、authzVersion、checkedAt。密钥/密码/OTP/token/完整券码/完整银行卡不进日志；拒绝也记录实际主体，匿名失败使用受限尝试引用而非伪造用户。原PRD至少3年留痕与不可删改保持，本包不重订法律期限。

高风险业务变更与必要审计意图须原模块可靠提交，不能权限通过后审计静默丢失。登录审计写失败按原PRD异步降级并告警，不影响已经真实验证的登录；两类故障语义分别验证。记录单个实际操作者，不设置secondApprover/reviewer必填。


## 执行授权与成功回执读取

执行过程：入口初检→按23号Admission绑定→获取本模块幂等/业务锁→**首个业务修改之前最终授权检查**→短本地事务业务与回执提交。最终检查通过身份/权限Owner强一致当前读，不继承调用者MySQL RR旧快照、不信任可过期允许缓存；跨模块不持Repository锁、不假设全局事务。依赖故障503且无业务修改。

一致性提案：每个Owner在自己的短只读事务内原子读取完整事实与单调版本；权限Owner把角色/账号grant/scope作为同一授权快照，不分别返回旧动作和新范围。跨Owner先收集全部快照，再按同样顺序强一致复核每个版本；全部相同才可使用，版本变化最多重取3次/总2秒，仍不稳定503。版本不得回退/重置/ABA，成员换店、停用、角色启停、scope/extra grant变化必须涵盖。相同两轮版本意味着存在两轮间一致观察区间，最终检查的逻辑时点在该区间；不声称多个Owner数据库事务同时提交。相关Owner未提供原子快照/版本契约则D2实现BLOCKED，不降级各自缓存拼接。副作用前若另有业务锁等待或重试仍重查。

承诺边界：撤权提交先于最终检查的权威读，则该检查拒绝；最终检查先通过、撤权后提交，则该次已经允许的短事务可以完成。不能宣称撤权一定先于后续业务commit生效。审计记录检查时刻及采用的版本。检查后发生长等待、业务锁失去、事务回滚重试、重新执行、异步代表用户发起新动作时必须重新检查，不复用旧allow；建议check完成至首写预算2秒，超限重新检查。该预算不制造跨模块锁或严格线性化保证。

已受理退款/可靠任务继续按原业务承诺执行，不能因原操作员撤权取消渠道退款；它们以既有SYSTEM逻辑身份和任务契约执行，不冒充已撤用户的新指令。UI在撤权后丢弃旧epoch结果并查询资源最新事实，不能承诺服务器已提交的动作被浏览器丢包撤销。

成功幂等重放、导出下载和敏感数据发送前独立READ_RESULT检查当前身份/动作/结果资源范围/用途及脱敏；401/403/防枚举404不返旧data，查询失败503。成功重放不再检查已消耗的一次性执行业务资格；历史actions剔除或标作历史，不可当当前授权。返回前检查同样是一次明确检查时点，网络传输期间撤权不能追回已发送字节，不承诺零时间窗口。

普通403只撤相关授权上下文；身份撤销401清该端会话。Web与小程序彼此不清令牌。权限读失败不能降级使用缓存旧allow。需要“撤权提交后任何在途均禁止commit”的更强保证时另列重大契约/一致性评审，不偷偷跨biz锁表。



内部新DTO/签名没有因HTTP同步自动冻结；严格按07号同步的语义边界与planning映射附录待审字段交接。

## 商家域已批准同步（2026-09-17）

员工六操作、协议读取与同意两操作、请求/响应/状态码/幂等/当前权限检查，以[27号商家域契约](27-Merchant-Domain-Contract-v0.1.md)为准，OpenAPI11已同步八操作。下线后存量履约/退款/售后继续；原“签约外链/Provider未定”描述不再适用于V1电子协议。首次签署不依赖ALLOWED工作台准入。成员绑定与员工在途守卫仍属未完成依赖，不能默认成功。

## 商家申请审核HTTP同步候选（2026-09-17）

[30号补充](30-Merchant-Application-Contract-v0.1.md)与OpenAPI11新增本人申请4操作、运营审核6操作，全部标CONTRACT_SYNC_CANDIDATE_NOT_IMPLEMENTED。申请人仅看本人；运营仅看已提交版本并先过滤scope再分页。人工核验/释放/决定必须当前领取人，不能构造内部双人审批。

applicationId等主键使用Snowflake String；applicationNo按原PRD为SQ+YYYYMMDD+8位随机码，首次提交生成、重提不变，不套PublicId数字词法。revisionNo为正序号String。默认详情仅脱敏，不接受GET reveal参数，不在URL放敏感用途；原件读取依赖独立私有资产授权契约。无新的运行时路由或Provider就绪声明。

## 2026-09-24 已批准：SCH-003 / ORDER 预约保护的 HTTP 同步边界

[36号联合契约](36-Reservation-Order-Protection-Contract-v0.1.md)的 ROC-1～6 已批，当前状态仍为 `ACCEPTED_CONTRACT_NOT_IMPLEMENTED`。现有 §3.4 六字段可约 `items` 不返回原 `windowId/kind`，§3.5 创建与改期请求未携接送两个所选窗 ID 或到店 GENERAL 原窗 ID；不能以旧响应猜 ID、宣称接送双 claim 或跨服务容量写已可用。后续实现切片须同步 07/10/11 的选窗字段、请求/错误与客户端合同，并在服务端锁内完成 36 号复核；本次追加不创建 HTTP 路由、不更改当前响应或 `x-contract-status`，相关入口未实现时失败关闭。
