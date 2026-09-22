# CCR-W2-API-001 服务域契约提案（草案 v0.2）

状态：**PROPOSAL_ACCEPTED（2026-09-22 人工批准 SVC-D1～D5，见[决定回执](service-domain-decisions.md)）**。规范版本：0.3，日期：2026-09-22。
提出方/唯一编辑者：Backend Core（SVC-001 规范阶段）。关联 Issue：SVC-001 / EPIC-04 / ST-SVC-01；消费者：C-003 / M-002 / ADM-001。批准人：人工 Contract Owner **blueXYing**。
基线：develop `bb6bb5c`；分支 `feat/svc001-service-domain-20260922`。本草案只做规范与依赖核验，不写实现代码、不改权威 06/07/10/11/12；获批后由 Owner 同步权威文档再派发实现。

## 修订记录

| 版本 | 变更 |
|---|---|
| 0.1（2026-09-22） | 初稿，SVC-D1～D4 |
| 0.3（2026-09-22，批准落档） | 人工批准 SVC-D1～D5；错误语义细化：确认不存在/无资格→隐藏或404，事实源故障/读取失败/状态未知→503，撤下 known=false 双标志（见回执第6条）；D5 允许本切片一并实现、商家模块独立提交 |
| 0.2（2026-09-22，人工审阅补正） | ① 撤回"资格接口可直接消费"结论：`checkOrderEligibility` 以 `owner_user_id` 匹配为前提（`MerchantQueryService.java:95`、`MerchantReadMapper.xml` `selectOwnedEligibilityBase`），消费者身份不可用，新增 **SVC-D5 消费者侧展示资格查询**（pet-merchant-api 第四查询，显式契约增补）；② 补齐"服务上架但商家/门店停用"的详情返回规则：与列表同语义，不可见即 404（SVC-D1b 可见性规则）；③ D1 增加"资格≠有空位≠下单成功"边界说明；④ 登记门店两条路由与服务写入方的后续承接；⑤ 删除 HTTP 详情响应的 bookability 子对象（可见性已蕴含基本资格，避免恒真字段） |

## 人工 CTO 一页阅读指南

**用通俗话说：小程序里"门店的服务列表"和"服务详情"两个页面要能跑，得先把"查一个服务、判断它现在能不能约"的接口字段和规则写成书面约定。数据库表 `service_item` 和内部接口名早就定了，但可预约资格 DTO 只起了名没定义内容，HTTP 响应示例也没写；另外发现现有"商家资格"接口只有商家本人能查，消费者查服务时不能借用，需要一个专门给消费者展示用的事实查询。本提案补齐这些，并把"没写清入口的功能"明确留给后续任务。**

| 分工 | 内容 |
|---|---|
| 我负责 | 逐操作字段/校验/错误/鉴权映射、聚合与失败关闭规则、消费者侧事实查询契约、示例与反例、Schema/Event/OpenAPI 影响与依赖核验 |
| 你负责 | 审核"建议决定"五项（D5 是新增契约增补）；权威契约同步与实现派发按流程另批 |
| 必读 | 本页"建议决定"；细节见下文逐操作定义 |
| 不变 | 产品规则（分钟排期/单次服务/金额 String/快照不受主数据后续变化影响）原样执行，不提前实现排期、库存锁、订单创建或支付 |

## 建议决定（需人工批准的五项）

1. **SVC-D1 可预约资格四条件合取、失败关闭**：`bookable=true` 当且仅当 `service.status=ACTIVE` ∧ 商家 `merchantEnabled` ∧ 门店 `storeEnabled` ∧ `acceptsNewOrders`（后三者已内含审核通过、签约完成等准入要求）。任何事实查不到或未知 → 整体不可用（503 或不可见），**绝不默认允许预约**。
   **边界（人工意见采纳，明文写入）**：此"可预约"仅指**基本资格合格**，不代表所选时间还有空位，更不代表订单预约成功；空位查询（HTTP10 §3.4）与下单（§3.5）由排期/订单域另行检查，本切片不实现不定义。
   **SVC-D1b 可见性规则（补正）**：C 端可见性 = 同一四条件合取，同事务判定。任一条件不满足：列表中不出现；详情返回 404 `SERVICE_NOT_FOUND`，与"服务不存在"同响应、不区分原因（防探测），与"只展示当前允许新预约的商家/门店/服务"（HTTP10 §3.3）一致，避免"列表隐藏了、详情链接还能打开"。HTTP 详情响应不再携带 bookability 子对象（可见即基本资格合格）；资格原因细分仅保留在内部 `checkBookable` 的 `reasonCodes`，供后续 ORD/SCH 使用。存量订单展示旧价格/旧资料走订单域订单快照，不经本接口。
2. **SVC-D2 本切片两条服务读路由**：`GET /api/v1/c/stores/{storeId}/services`（分页列表）与 `GET /api/v1/c/services/{serviceId}`（详情）。HTTP10 §3.3 另两条门店路由（`/c/stores`、`/c/stores/{storeId}`）**登记由 MER-001 后续门店读侧切片承接**（见 §7 承接表，CCR 索引商家/门店行同步登记），不挂空。**边界**：本切片交付的是接口能力，不等于小程序页面已做好（页面归 C-003）；完整"找店→进店→选服务"流程需门店路由与页面接齐后才算通。
3. **SVC-D3 快照为查询时值拷贝、金额 String**：内部 `ServiceSnapshotDTO` 沿用 API07 §5.1 已定义 11 字段（BigDecimal 金额）；HTTP 投影 `salePrice` 为十进制字符串两位小数（如 `"128.00"`，纯传输格式防精度损失，不影响用户所见价格）。每次查询重新读取并返回副本，之后主数据改价（如 128→158）不改变已取出副本，再查得新值。这是为订单保存当时资料做的准备；本切片只验证"取出的副本不跟着变"（W2-SVC-002），**不证明历史订单已正确保存并展示旧价格**（属 ORD008 后续完整验收）。
4. **SVC-D4 无写入方如实披露**：V1 尚未交付任何 `service_item` 写路径（商家端/运营端服务管理均未实现，入驻建档也不建服务）。本切片测试以 SQL 夹具直接播种，可证明"给定服务数据，查询与资格判断正确"，**不能证明"商家发布服务→消费者看到→成功预约"完整流程**；真实 E2E 在写入方交付前保留未完成，不得据此把完整业务验收标成完成。服务写入方承接登记见 §7。
5. **SVC-D5 消费者侧展示资格查询（新增契约增补，替代 v0.1"可直接消费"的错误结论）**：现有 `MerchantQueryApi.checkOrderEligibility` 是**商家所有者视角**——从 `QueryContext` 提取主体并以 `m.owner_user_id` 匹配（`MerchantQueryService.java:95`、`MerchantReadMapper.xml` `selectOwnedEligibilityBase`），消费者身份调用必然查不到，**不得借用、不得冒用商家身份绕过**。本提案在 pet-merchant-api 增补**第四个内部查询**（对 27 号三查询契约的显式增补，经本 CCR 批准）：

   ```java
   public interface MerchantDisplayEligibilityApi {
       MerchantDisplayEligibilityDTO checkDisplayEligibility(MerchantDisplayEligibilityQuery query);
   }
   public record MerchantDisplayEligibilityQuery(String merchantId, String storeId, QueryContext context) {}
   public record MerchantDisplayEligibilityDTO(
       String merchantId, String storeId,
       boolean merchantEnabled, boolean storeEnabled, boolean acceptsNewOrders) {}
   ```

   语义约定：**仅供 C 端展示聚合消费，只读布尔事实，不授予任何商家操作权限**（商家侧权威仍是既有三查询）；查询**不做所有者前提过滤**，`QueryContext` 仅承载 requestId/traceId 链路信息，**不得携带或冒充商家主体**（遵循 API07：调用方不能用 HTTP 自报 DTO 冒充 Principal）。错误语义（批准细化，撤下 v0.2 的 known 双标志）：**确认不存在**的商家/门店对 → NOT_FOUND（调用方隐藏/404）；**事实源故障、读取失败或状态未知** → 503 DEPENDENCY_UNAVAILABLE 失败关闭；两者不得混同。实现归 pet-merchant-api/merchant-biz（MER 域文件，按 WAVE_2_PLAN 唯一 Writer 规则以独立 commit 交付并在 PR 显式披露）；service-biz 仅经此 API 取事实，不跨模块读 Repository/Entity。

## 1. 范围与来源（依赖核验结论）

| 来源 | 核验结果 |
|---|---|
| Schema 06 §1 | `service_item` 已定（merchant_id/store_id/category_id/name/description/price DECIMAL(18,2)/duration_minutes/fulfillment_type/status DRAFT-ACTIVE-OFFLINE/version/时间戳；store/merchant+status 索引）；`service_category`、`staff_service_capability` 在库但后者归 SCH/人员域 |
| 内部 API07 §5.1 | `ServiceQueryApi.getServiceSnapshot/checkBookable` 接口已定；`ServiceSnapshotDTO` 11 字段已定；**`ServiceBookabilityDTO`、`ServiceSnapshotQuery`、`ServiceBookabilityQuery` 仅名称引用，形状未定义（本提案 §2/§3 补齐）**；FulfillmentType 仅 IN_STORE/PICKUP_DELIVERY；不含套餐/次卡/普通商品 |
| HTTP10 §3.3 | 四条 C 端只读路由已列；"只展示当前允许新预约的商家/门店/服务；存量订单读快照不依赖当前服务状态"；§3.3 无响应示例（本提案补） |
| HTTP10 §3.4/§3.5 | availability 与订单创建属 SCH/ORD，本切片不实现、不定义（D1 边界） |
| 错误码 12 号 §12 | MERCHANT/STORE/SERVICE NOT_FOUND、DISABLED、SERVICE_NOT_BOOKABLE、SERVICE_FULFILLMENT_NOT_SUPPORTED 既有，无需新增 |
| OpenAPI 11 号 | 四条 §3.3 路由均未收录；建议随权威同步增补本切片两条为 `ACCEPTED_CONTRACT_NOT_IMPLEMENTED`（MER-001 先例）；门店两条由承接切片收录 |
| MER-001 事实源 | ~~可直接消费~~（v0.1 有误，已撤回）：`checkOrderEligibility` 为所有者视角（代码依据同 SVC-D5），消费者不可用。**本提案改为增补 SVC-D5 展示资格查询后消费**；boot 接线沿用事务内事实读取先例 |
| 写入方 | 无（见 SVC-D4）；`pet-service-api`/`pet-service-biz` 现为空壳 package-info，本切片首次填充 |

## 2. DTO 与字段校验

内部（pet-service-api，形状获批后同步 07 号）：

```java
public record ServiceSnapshotQuery(String serviceId, QueryContext context) {}

public record ServiceBookabilityQuery(String serviceId, String storeId, QueryContext context) {}
```

`ServiceBookabilityDTO`（建议形状；仅供内部 ORD/SCH 消费，不直接出现在 HTTP 响应）：

```java
public record ServiceBookabilityDTO(
    String serviceId, String merchantId, String storeId,
    boolean bookable,
    java.util.List<String> reasonCodes   // 空=可预约；否则 ∈ {MERCHANT_DISABLED, STORE_DISABLED,
                                         //   MERCHANT_NOT_ACCEPTING_ORDERS, SERVICE_OFFLINE}
) {}
```

HTTP 投影（成功 200 `ApiResponse<...>`，信封与既有 C 端一致）：

服务详情 `ServiceDetailView`（可见性由 D1b 保证，不再携带 bookability）：

```json
{
  "serviceId": "20001", "merchantId": "957001", "storeId": "957002",
  "serviceName": "宠物美容-基础洗护", "categoryId": "957003", "categoryName": "美容",
  "salePrice": "128.00", "durationMinutes": 45,
  "fulfillmentType": "IN_STORE", "description": "含洗护、吹干、基础梳理"
}
```

门店服务列表项 `StoreServiceItemView`：详情字段去掉 `description`（列表不显全文）；不暴露 `version`；分页信封 `items/page/pageSize/total`，排序 `created_at DESC, id DESC`，`page` 1..10000、`pageSize` 1..50（与通知列表一致）。

校验（违规 `COMMON_INVALID_ARGUMENT`/400，details 指明字段）：

- `serviceId`/`storeId`：雪花 ID 字符串（公共 ID Codec）；路径参数非法即 400。
- `fulfillmentType`：仅 IN_STORE/PICKUP_DELIVERY；存储值非法时读侧失败关闭（503），不提前做订单侧校验。

## 3. 操作契约

会话：C 端登录态（既有三处登记：CBearerSessionFilter/CSessionSecurityConfiguration/CServiceExceptionHandler）；未登录 `COMMON_UNAUTHORIZED`/401。读操作无 requestId 幂等要求（23 号）。

| 操作 | 成功 | 关键错误 |
|---|---|---|
| GET `/api/v1/c/stores/{storeId}/services?page&pageSize` | 200 分页 `StoreServiceItemView[]`；仅返回 D1b 可见性合取通过的服务 | 400 参数；401 未登录 |
| GET `/api/v1/c/services/{serviceId}` | 200 `ServiceDetailView`（可见即资格合格，D1b） | 404 `SERVICE_NOT_FOUND`（不存在 / OFFLINE / DRAFT / 商家或门店停用或不接新单——一律同响应不区分原因）；400；401 |
| 内部 `getServiceSnapshot` | `ServiceSnapshotDTO`（11 字段原样） | 事实缺失抛 ApiException，由调用方决定 HTTP 映射 |
| 内部 `checkBookable` | `ServiceBookabilityDTO`（含 reasonCodes，供 ORD/SCH） | 事实源不可用 → 503 `COMMON_DEPENDENCY_UNAVAILABLE`（失败关闭） |
| 内部 `MerchantDisplayEligibilityApi.checkDisplayEligibility`（SVC-D5） | 七字段展示事实（含 known 标志） | 参数 400；读取异常 503；known=false 由调用方按不可见处理并留痕 |

列表归属语义：`storeId` 不存在或不可见 → 200 空列表（页面显示"暂无服务"，不暴露门店状态细节，不与"门店存在但无服务"区分探测）；详情不可见一律 404（D1b）。

## 4. 聚合规则与失败关闭

- 事实读取路径：服务自身事实读 `service_item`（本模块存储）；商家/门店事实**经 SVC-D5 `MerchantDisplayEligibilityApi`**，与其同事务（repeatable read）一致快照，沿用 MER-001 `MerchantEligibilityFactsReader` 事务内回调先例。
- `bookable = serviceActive ∧ merchantEnabled ∧ storeEnabled ∧ acceptsNewOrders`；`reasonCodes` 按"哪一条不满足"枚举输出（多项可并列）；可见性同式（D1b）。
- 任何事实源异常/未知（查询失败、字段缺失、状态值非法、known=false）→ C 端不可见或整体 503，绝不降级为可见/可预约。
- OFFLINE/DRAFT 服务对 C 端不可见；存量订单不受影响（HTTP10 §3.3 原文，属订单侧读快照）。

## 5. Schema / Event / OpenAPI 影响

- **Schema：无变化**（`service_item` 既有列满足；不建新表；无迁移）。
- **Event：无**（纯读切片，无 outbox 事件；后续写入方另行 CCR）。
- **内部契约：pet-merchant-api 增补第四查询**（SVC-D5，27 号增补披露）；pet-service-api 落地 §2 形状。
- **OpenAPI 11 号：获批后增补**本切片两条操作（`ACCEPTED_CONTRACT_NOT_IMPLEMENTED` 起步，实现 PR 合并后保持该状态直至 E2E 具备——MER-001 同款披露）；门店两条路由由 MER-001 承接切片收录。
- 07 号权威同步：`ServiceBookabilityDTO/ServiceSnapshotQuery/ServiceBookabilityQuery` 形状、§4 聚合规则、§5.1 增补展示资格查询引用；10 号权威同步：两条路由响应示例、错误与 D1b 可见性规则；12 号：无新增。

## 6. 测试映射（WAVE_2_TEST_ACCEPTANCE）

| 验收 | 本提案对应 |
|---|---|
| W2-SVC-001 资格聚合+反例 | §4 四条件合取；商家 DISABLED/门店 DISABLED/服务 OFFLINE/DRAFT 反例经 SQL 夹具；消费者上下文调用 SVC-D5 查询（反例：消费者调所有者视角接口必 404，证明未冒用） |
| W2-SVC-002 快照副本 | §2 SVC-D3：查询后 UPDATE 主数据（改价），已返回副本不变 |
| W2-SVC-003 字段/错误/页面查询 | §2/§3：ID/金额 String、404（含停用态详情同响应）/400/401/503、列表分页字段 |
| ARCH001~005 | 不跨模块 Repository；biz 不依赖 biz；模块边界保持 |
| ORD008 | 仅提交服务快照模块证据，不声明订单历史完成、不声明完整预约流程验收通过 |

## 7. 承接登记与风险

| 事项 | 承接 | 登记位置 |
|---|---|---|
| 门店两条 C 端读路由（`/c/stores`、`/c/stores/{storeId}`） | **MER-001 后续门店读侧切片**（门店域查询，含城市/列表页面字段，届时另走其域提案） | CCR-W2-API-001 索引"商家/门店"行 + READY_QUEUE 备注 |
| 服务写入方（商家端/运营端服务管理 CRUD，HTTP10 merchant/services 路由族） | **ADM-001 运营治理行既有"服务操作"范围**（M-002 工作台消费；具体切片届时立项） | CCR-W2-API-001 索引"运营治理"行 + READY_QUEUE 备注 |
| availability（§3.4）/订单创建（§3.5） | SCH/ORD 域 | 既有归属，不动 |

风险与未决：

- SVC-D5 是对已批三查询契约的增补，实现落在 MER 域文件（唯一 Writer 规则以独立 commit 披露）；若你倾向由 MER-001 名义先行交付该查询，本切片只消费，请在批准时说明。
- 写入方缺失（SVC-D4）→ 真实 E2E 保留未完成；夹具播种路径在测试文档显式标注。
- C-003 页面后续若需"按分类筛选/搜索"，新查询参数另走 CCR，不在本提案隐式扩大。
