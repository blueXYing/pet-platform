# SVC-001 交接：服务域读切片（列表/详情/资格/快照）

日期：2026-09-22。分支 `feat/svc001-service-domain-20260922`（基于 develop `bb6bb5c`）。契约：[CCR-W2-API-001 服务域提案 v0.3](../../../ccr/CCR-W2-API-001/service-domain-proposal.md) + [决定回执](../../../ccr/CCR-W2-API-001/service-domain-decisions.md)（SVC-D1～D5 人工批准）。

## 交付（本切片三个提交）

1. **docs(contract)**：07 号 §4.2（展示资格查询）与 §5.1.1（查询/资格/分页形状、接口四方法）；10 号 §3.3.1（路由契约+可见性规则+响应示例）；11 号两操作 `ACCEPTED_CONTRACT_NOT_IMPLEMENTED`；27 号 §4 第四查询行。
2. **feat(merchant)（独立提交、按批准披露）**：SVC-D5 `MerchantDisplayEligibilityApi`（pet-merchant-api 第四内部查询）+ biz 实现（同一资格策略/事实读取器、无所有者前提、加入调用方事务的单快照读取）+ boot 装配。
3. **feat(service)**：pet-service-api（快照/资格/分页 DTO 与查询、FulfillmentType）+ pet-service-biz（读库/映射/聚合服务）+ boot `CServiceController` 两条路由（默认关闭 `pet.service.query.enabled`）+ 安全三处登记。

## 语义要点（按批准细化）

- **错误两分，不得混同**：确认不存在或不具备资格 → 隐藏/404 `SERVICE_NOT_FOUND`（不区分原因，防探测）；事实源故障、读取失败或状态未知 → 503 `COMMON_DEPENDENCY_UNAVAILABLE` 失败关闭。撤回了 v0.2 的 known 双标志设计。
- **可见性=资格四条件合取**（服务 ACTIVE ∧ merchantEnabled ∧ storeEnabled ∧ acceptsNewOrders），同事务判定；列表与详情同语义，列表隐藏则详情 404。资格仅基本资格，不代表有空位或下单成功。
- **单快照**：签约事实读取要求活动事务，展示资格查询经 joining 模板加入服务域读事务（REQUIRES_NEW repeatable read）；`storePage` 整页一次资格判定（同店同对）。
- **快照值拷贝**（W2-SVC-002）：查询后改价不改变已返回副本；HTTP `salePrice` 两位小数字符串。
- 内部 `checkBookable` 保留 reasonCodes（MERCHANT_DISABLED/STORE_DISABLED/MERCHANT_NOT_ACCEPTING_ORDERS/SERVICE_OFFLINE，可并列）供 ORD/SCH；C 端响应不携带 bookability。

## 测试证据

- `ServiceQueryHttpTest`（真实 MySQL/Redis + 真实申请→审批→签署链 + **SQL 播种 service_item**，SVC-D4）：可见列表分页/字段/金额 String、详情快照、改价副本不变、OFFLINE/DRAFT/未知 ID 404、商家 OFFLINE/门店 FROZEN 隐藏且详情 404 且内部 reasonCodes 正确、商家状态损坏 503（与 404 不混同）、恢复后可见、400/401/参数拒绝、空门店空页、D5 反例（非所有者消费者调所有者视角接口 NOT_FOUND）。1/1 通过。
- 本地回归：pet-boot 全套 + pet-id-core（13+11+6）通过；ARCH 守卫随 CI。

## 边界与如实披露

- **无写入方**（SVC-D4）：V1 尚无 service_item 写路径，测试经 SQL 夹具播种；"商家发布服务→消费者看到→成功预约"完整流程未验收，不得据此声明完整业务验收通过。
- ORD008 本阶段仅提交服务快照模块证据（值拷贝），不声明完整订单历史。
- 门店两条读路由（`/c/stores`、`/c/stores/{storeId}`）由 MER-001 后续门店读侧切片承接；服务写入方登记于 ADM-001"服务操作"范围（M-002 消费）。
- `pet.service.query.enabled` 默认关闭；本地/生产启用需 C 会话与商家装配同时在场。
