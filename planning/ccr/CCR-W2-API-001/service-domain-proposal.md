# CCR-W2-API-001 服务域契约提案（草案）

状态：**PROPOSAL_DRAFT（待人工批准）**。规范版本：0.1，日期：2026-09-22。
提出方/唯一编辑者：Backend Core（SVC-001 规范阶段）。关联 Issue：SVC-001 / EPIC-04 / ST-SVC-01；消费者：C-003 / M-002 / ADM-001。批准人：人工 Contract Owner **blueXYing**。
基线：develop `bb6bb5c`；分支 `feat/svc001-service-domain-20260922`。本草案只做规范与依赖核验，不写实现代码、不改权威 06/07/10/11/12；获批后由 Owner 同步权威文档再派发实现。

## 人工 CTO 一页阅读指南

**用通俗话说：小程序里"门店的服务列表"和"服务详情"两个页面要能跑，得先把"查一个服务、判断它现在能不能约"的接口字段和规则写清楚。数据库表 `service_item` 和内部接口名早就定了，但 `ServiceBookabilityDTO`（可预约资格）只起了个名字没定义内容，HTTP 响应示例也没写。本提案把这些补齐，另有两个范围问题需要你拍板。**

| 分工 | 内容 |
|---|---|
| 我负责 | 逐操作字段/校验/错误/鉴权映射、聚合与失败关闭规则、示例与反例、Schema/Event/OpenAPI 影响与依赖核验 |
| 你负责 | 审核"建议决定"四项（其中 D2 是范围取舍）；权威契约同步与实现派发按流程另批 |
| 必读 | 本页"建议决定"；细节见下文逐操作定义 |
| 不变 | 产品规则（分钟排期/单次服务/金额 String/快照不受主数据后续变化影响）原样执行，不提前实现排期、库存锁、订单创建或支付 |

## 建议决定（需人工批准的四项）

1. **SVC-D1 可预约资格四条件合取、失败关闭**：`ServiceBookabilityDTO.bookable=true` 当且仅当 `service.status=ACTIVE` ∧ 商家事实 `merchantEnabled` ∧ 门店事实 `storeEnabled` ∧ `acceptsNewOrders`。商家/门店事实只经 `MerchantQueryApi.checkOrderEligibility`（已批三查询契约，27号）获取，事务内一致读取；事实源不可用或返回未知一律 `COMMON_DEPENDENCY_UNAVAILABLE`/503，**不自行放行**。不可预约原因以 `reasonCodes` 枚举返回（复用 12 号 §12 既有语义，不新增错误码）。
2. **SVC-D2 本切片 HTTP 面收窄为两条服务读路由**：`GET /api/v1/c/stores/{storeId}/services`（分页列表）与 `GET /api/v1/c/services/{serviceId}`（详情+资格）。HTTP10 §3.3 的另两条门店路由（`/c/stores`、`/c/stores/{storeId}`）是门店域读侧，建议归 MER/C003 后续切片，不在 SVC-001 实现。若你要求四条全做请明确（门店列表还需城市/搜索等页面字段，范围将扩大）。
3. **SVC-D3 快照为查询时值拷贝、金额 String**：内部 `ServiceSnapshotDTO` 沿用 API07 §5.1 已定义 11 字段（BigDecimal 金额）；HTTP 投影 `salePrice` 为十进制字符串两位小数（技术基线：金额 String）。每次查询重新读取并返回副本，后续修改 `service_item` 主数据不改变已返回副本；ORD008 在本阶段只提交该快照模块证据，不声明完整订单历史通过。
4. **SVC-D4 无写入方如实披露**：V1 尚未交付任何 `service_item` 写路径（商家端/运营端服务管理均未实现，商家入驻建档也不建服务）。本切片测试以 SQL 夹具直接播种；真实 E2E 在写入方交付前保留未完成，不伪装通过。

## 1. 范围与来源（依赖核验结论）

| 来源 | 核验结果 |
|---|---|
| Schema 06 §1 | `service_item` 已定（merchant_id/store_id/category_id/name/description/price DECIMAL(18,2)/duration_minutes/fulfillment_type/status DRAFT-ACTIVE-OFFLINE/version/时间戳；store/merchant+status 索引）；`service_category`、`staff_service_capability` 在库但后者归 SCH/人员域 |
| 内部 API07 §5.1 | `ServiceQueryApi.getServiceSnapshot/checkBookable` 接口已定；`ServiceSnapshotDTO` 11 字段已定；**`ServiceBookabilityDTO`、`ServiceSnapshotQuery`、`ServiceBookabilityQuery` 仅名称引用，形状未定义（本提案 §2/§3 补齐）**；FulfillmentType 仅 IN_STORE/PICKUP_DELIVERY；不含套餐/次卡/普通商品 |
| HTTP10 §3.3 | 四条 C 端只读路由已列；"只展示当前允许新预约的商家/门店/服务；存量订单读快照不依赖当前服务状态"；§3.3 无响应示例（本提案补） |
| HTTP10 §3.4/§3.5 | availability 与订单创建属 SCH/ORD，本切片不实现、不定义 |
| 错误码 12 号 §12 | MERCHANT/STORE/SERVICE NOT_FOUND、DISABLED、SERVICE_NOT_BOOKABLE、SERVICE_FULFILLMENT_NOT_SUPPORTED 既有，无需新增 |
| OpenAPI 11 号 | 四条 §3.3 路由均未收录；建议随权威同步增补本切片两条为 `ACCEPTED_CONTRACT_NOT_IMPLEMENTED`（MER-001 先例） |
| MER-001 事实源 | `MerchantQueryApiImpl.checkOrderEligibility(merchantId,storeId)` → 五字段投影（merchantEnabled/storeEnabled/acceptsNewOrders…），boot 已经 `MerchantAgreementEligibilityFactsAdapter` 接线；`getStore` 可取门店归属校验。**SVC-001 只消费该 API，不跨模块读 Repository/Entity（AC3）** |
| 写入方 | 无（见 SVC-D4）；`pet-service-api`/`pet-service-biz` 现为空壳 package-info，本切片首次填充 |

## 2. DTO 与字段校验

内部（pet-service-api，形状获批后同步 07 号）：

```java
public record ServiceSnapshotQuery(String serviceId, QueryContext context) {}

public record ServiceBookabilityQuery(String serviceId, String storeId, QueryContext context) {}
```

`ServiceBookabilityDTO`（建议形状）：

```java
public record ServiceBookabilityDTO(
    String serviceId, String merchantId, String storeId,
    boolean bookable,
    java.util.List<String> reasonCodes   // 空=可预约；否则 ∈ {MERCHANT_DISABLED, STORE_DISABLED,
                                         //   MERCHANT_NOT_ACCEPTING_ORDERS, SERVICE_OFFLINE}
) {}
```

HTTP 投影（成功 200 `ApiResponse<...>`，信封与既有 C 端一致）：

服务详情+资格 `ServiceDetailView`：

```json
{
  "serviceId": "20001", "merchantId": "957001", "storeId": "957002",
  "serviceName": "宠物美容-基础洗护", "categoryId": "957003", "categoryName": "美容",
  "salePrice": "128.00", "durationMinutes": 45,
  "fulfillmentType": "IN_STORE", "description": "含洗护、吹干、基础梳理",
  "bookability": { "bookable": true, "reasonCodes": [] }
}
```

门店服务列表项 `StoreServiceItemView`：详情字段去掉 `description`（列表不显全文），加 `version` 不暴露；分页信封 `items/page/pageSize/total`，排序 `created_at DESC, id DESC`，`page` 1..10000、`pageSize` 1..50（与通知列表一致）。

校验（违规 `COMMON_INVALID_ARGUMENT`/400，details 指明字段）：

- `serviceId`/`storeId`：雪花 ID 字符串（公共 ID Codec）；路径参数非法即 400。
- `fulfillmentType`：仅 IN_STORE/PICKUP_DELIVERY；`SERVICE_FULFILLMENT_NOT_SUPPORTED` 本切片仅在详情投影出现非法存储值时由读侧失败关闭（503），不提前做订单侧校验。

## 3. 操作契约

会话：C 端登录态（既有三处登记：CBearerSessionFilter/CSessionSecurityConfiguration/CServiceExceptionHandler）；未登录 `COMMON_UNAUTHORIZED`/401。读操作无 requestId 幂等要求（23 号）。

| 操作 | 成功 | 关键错误 |
|---|---|---|
| GET `/api/v1/c/stores/{storeId}/services?page&pageSize` | 200 分页 `StoreServiceItemView[]`；仅返回该门店 `status=ACTIVE` 且其商家/门店允许新预约的服务；门店不存在或非本店归属 → 空列表或 404（见下） | 400 参数；401 未登录 |
| GET `/api/v1/c/services/{serviceId}` | 200 `ServiceDetailView` | 404 `SERVICE_NOT_FOUND`（不存在/OFFLINE 或 DRAFT 不展示，同 404 防探测）；400；401 |
| 内部 `getServiceSnapshot` | `ServiceSnapshotDTO`（11 字段原样） | 事实缺失抛 ApiException，由调用方决定 HTTP 映射 |
| 内部 `checkBookable` | `ServiceBookabilityDTO` | 事实源不可用 → 503 `COMMON_DEPENDENCY_UNAVAILABLE`（失败关闭） |

列表归属语义建议：`storeId` 不存在或门店 DISABLED → 200 空列表（页面显示"暂无服务"，不暴露门店状态细节）；不返回 404，避免与"门店存在但无服务"区分探测。此点如需改为 404 请在批准时说明。

## 4. 聚合规则与失败关闭

- 单一事实读取路径：服务自身事实读 `service_item`（本模块存储）；商家/门店事实调 `MerchantQueryApi.checkOrderEligibility`，与其同事务（repeatable read）一致快照，沿用 MER-001 `MerchantEligibilityFactsReader` 事务内回调先例。
- `bookable = serviceActive ∧ merchantEnabled ∧ storeEnabled ∧ acceptsNewOrders`；`reasonCodes` 按"哪一条不满足"枚举输出（多项可并列）。
- 任何事实源异常/未知（查询失败、字段缺失、状态值非法）→ 整体 503，绝不降级为可预约。
- OFFLINE/DRAFT 服务对 C 端不可见（详情 404、列表不出现）；存量订单不受影响（HTTP10 §3.3 原文，属订单侧读快照）。

## 5. Schema / Event / OpenAPI 影响

- **Schema：无变化**（`service_item` 既有列满足；不建新表；无迁移）。
- **Event：无**（纯读切片，无 outbox 事件；后续写入方另行 CCR）。
- **OpenAPI 11 号：获批后增补**本切片两条操作（`ACCEPTED_CONTRACT_NOT_IMPLEMENTED` 起步，实现 PR 合并后保持该状态直至 E2E 具备——MER-001 同款披露）；门店两条路由不收录、留待其归属切片。
- 07 号权威同步：`ServiceBookabilityDTO/ServiceSnapshotQuery/ServiceBookabilityQuery` 形状与 §5.1 聚合规则；10 号权威同步：两条路由响应示例与错误；12 号：无新增。

## 6. 测试映射（WAVE_2_TEST_ACCEPTANCE）

| 验收 | 本提案对应 |
|---|---|
| W2-SVC-001 资格聚合+反例 | §4 四条件合取；DISABLED/OFFLINE 反例经 SQL 夹具 |
| W2-SVC-002 快照副本 | §2 SVC-D3：查询后 UPDATE 主数据，已返回副本不变 |
| W2-SVC-003 字段/错误/页面查询 | §2/§3：ID/金额 String、404/400/401/503、列表分页字段 |
| ARCH001~005 | 不跨模块 Repository；biz 不依赖 biz；模块边界保持 |
| ORD008 | 仅提交服务快照模块证据，不声明订单历史完成 |

## 7. 未决与风险

- 范围：门店两条路由归属（SVC-D2）待批；`staff_service_capability`、`service_category` 写侧与运营 CRUD 均不在本切片。
- 写入方缺失（SVC-D4）→ 真实 E2E 保留未完成；夹具播种路径需在测试文档显式标注。
- C-003 页面后续若需"按分类筛选/搜索"，分页之外的新查询参数另走 CCR，不在本提案隐式扩大。
- availability（§3.4）与订单创建（§3.5）对快照的消费时机属 SCH/ORD 切片，本提案不定义其校验顺序。
