# SVC-STORES-QA 验收清单（门店读侧 + 服务写入→消费者可见闭环）

日期：2026-09-22。Owner：角色D（QA/集成）。分支 `codex/qa-svc-stores-20260922`（基于 develop `52a1c45`，含 PR#65 服务读切片）。

本清单只定义验收项与记录结果，**不表示任何未列证据的项已通过**。状态取值：`PASS / FAIL / NOT_EXECUTED / BLOCKED`，每格必须附证据或阻断原因。

依据：SSOT、PRD（商家端/运营端最终基线）、HTTP10 §3.3、OpenAPI11（`/c/stores/{storeId}/services`、`/c/services/{serviceId}` 两操作已冻结）、12号错误注册表、23号公共契约、CCR-W2-API-001 服务域提案 v0.3 + 决定回执（SVC-D1～D5）、WAVE_2_TEST_ACCEPTANCE、14/15/16/21号测试文档。

## 0. 分层定义（严格分层，绝不混报）

| 层 | 含义 | 本轮状态 |
|---|---|---|
| L1 模块测试 | 模块内/单测试类，真实 MySQL/Redis，夹具播种（SVC-D4 SQL 播种 service_item） | 可执行（读侧已有） |
| L2 Mock联调 | 前端/消费方以契约 schema 约束的 Mock 数据联调 | 未开始 |
| L3 真实API | 起本地服务器 + curl/客户端打真实路由（正例+反例） | 未开始（环境被占用，本轮按指令跳过起服，见 §4 环境记录） |
| L4 完整业务流程 | 预约、订单、支付、排期、核销等端到端 | **本轮范围外，任何情况下不得标记通过** |

**红线**：预约/订单/支付/排期/核销不在本轮范围（SVC-D4 无服务写入方，ORD/SCH/PAY 域另行验收）；任何层的结果不得冒充更高层。L1 通过只证明"给定服务/门店数据，查询与可见性判定正确"，不证明"商家发布服务→消费者看到→成功预约"完整流程（HANDOFF 既定边界）。

## 1. 特性(a)：门店读侧 `/c/stores`、`/c/stores/{storeId}`（承接：MER-001 门店读切片）

> **契约状态：角色B草案，未冻结。本节全部条目标注【待契约冻结后复核】**——按草案要点 + 既有四条件可见性（服务读切片同款：门店所在商家 `merchantEnabled` ∧ `storeEnabled` ∧ `acceptsNewOrders`，同事务判定）+ HTTP10 §3.3"只展示当前允许新预约的商家/门店"编写。字段名/分页参数/响应示例以冻结版为准，冻结后逐条复核并更新本表。

| ID | 层 | 优先级 | 场景与预期 | 状态 |
|---|---|---|---|---|
| STO-A01 | L1 | P0 | 商家/门店四条件全满足：列表出现该门店，详情 200；响应仅含契约字段，门店 ID 为 String | NOT_EXECUTED【待契约冻结后复核】 |
| STO-A02 | L1 | P0 | 商家 OFFLINE：列表隐藏该门店，详情 404，与"不存在"同响应不区分原因（防探测） | NOT_EXECUTED【待契约冻结后复核】 |
| STO-A03 | L1 | P0 | 门店 FROZEN：列表隐藏，详情 404（确认不合规→隐藏，非 503） | NOT_EXECUTED【待契约冻结后复核】 |
| STO-A04 | L1 | P0 | 商家 `acceptsNewOrders=false`（含未签约）：列表隐藏，详情 404 | NOT_EXECUTED【待契约冻结后复核】 |
| STO-A05 | L1 | P0 | 事实源故障/状态值非法/读取失败：整体 503 `COMMON_DEPENDENCY_UNAVAILABLE`，**与确认不存在 404 严格区分不混同**，失败关闭不降级为可见 | NOT_EXECUTED【待契约冻结后复核】 |
| STO-A06 | L1 | P1 | 状态恢复（商家回 ACTIVE/门店解冻）后：门店重新可见（恢复后可见） | NOT_EXECUTED【待契约冻结后复核】 |
| STO-A07 | L1 | P0 | 不存在/非法门店 ID 详情：404；ID 非雪花词法（前导零/符号/超范围）400 `COMMON_INVALID_ARGUMENT` | NOT_EXECUTED【待契约冻结后复核】 |
| STO-A08 | L1 | P0 | 无会话/会话过期/无效 Bearer：401 `COMMON_UNAUTHORIZED`，错误体 data=null，不泄露门店存在性 | NOT_EXECUTED【待契约冻结后复核】 |
| STO-A09 | L1 | P0 | 分页/筛选契约符合：page/pageSize 边界（参照服务列表 1..10000/1..50 或冻结版）、城市筛选参数按冻结版；未知查询参数拒绝 400；空结果返回空页非错误 | NOT_EXECUTED【待契约冻结后复核】 |
| STO-A10 | L2 | P1 | C 端页面 Mock 数据受已冻结 OpenAPI schema 约束（不用内部工程 fixture 冒充，MINI-004） | NOT_EXECUTED |
| STO-A11 | L3 | P0 | 真实服务器 curl：正例 200（字段/包裹结构/ID String 核对）+ 404 + 401 反例，响应头 `Cache-Control: no-store` | NOT_EXECUTED |
| STO-A12 | L4 | — | 完整业务流程（进店→选服务→预约→订单→支付→核销） | **范围外，禁止标记通过** |

## 2. 特性(b)：服务写入→消费者可见闭环（发布→可见→下架→隐藏→404）

> 状态机依据运营端 PRD：`草稿 → 待审核 → 上架 / 审核驳回`；下架后重新上架必须重新过审（防绕过审核改价）；运营保留强制下架权；"未上架或已下架服务不再对前台用户展示"。
> **当前阻断（SVC-D4）**：V1 尚无 service_item 写入方（商家端服务管理、运营端审核/强制下架均未实现，登记于 ADM-001"服务操作"范围，M-002 消费）。写入侧条目在写入方交付前保持 BLOCKED。读侧语义（给定数据后的可见性）已由 PR#65 模块测试覆盖。
> **Schema 缺口登记**：现行 `service_item.status` 仅 `DRAFT/ACTIVE/OFFLINE`，无法表达 PRD"待审核/审核驳回"。写入方立项时需经 CCR 扩状态机；届时"待审核不可见"按新状态补测，当前以 DRAFT 为最接近反例并明示差距。

| ID | 层 | 优先级 | 场景与预期 | 状态 |
|---|---|---|---|---|
| SVC-B01 | L1 | P0 | 【读侧前提】ACTIVE 服务 + 四条件满足：门店列表含该服务（无 description 全文），详情 200 含 description；`salePrice` 两位小数字符串；`serviceId/merchantId/storeId/categoryId` String；分页字段 `items/page/pageSize/total`；排序 `created_at DESC, id DESC` | **PASS**（2026-09-22 本 worktree 重跑 `ServiceQueryHttpTest` 1/1，证据见 §4） |
| SVC-B02 | L1 | P0 | 下架（OFFLINE）不可见：列表不含，详情 404，与不存在同响应 | **PASS**（同上） |
| SVC-B03 | L1 | P0 | 草稿（DRAFT）不可见：列表不含，详情 404 | **PASS**（同上；"待审核"状态待写入方 schema 后补测） |
| SVC-B04 | L1 | P0 | 商家 OFFLINE：服务列表整体隐藏（200 空页），详情 404；内部 `checkBookable.reasonCodes` 含 MERCHANT_DISABLED（供 ORD/SCH，不出现在 C 端响应） | **PASS**（同上） |
| SVC-B05 | L1 | P0 | 门店 FROZEN：列表空页，详情 404；reasonCodes 含 STORE_DISABLED | **PASS**（同上） |
| SVC-B06 | L1 | P0 | 商家状态值非法（如 CORRUPTED）：详情 503 且列表 503，**不与 404 混同**，失败关闭 | **PASS**（同上） |
| SVC-B07 | L1 | P1 | 恢复（商家回 ACTIVE）后：详情重新 200（恢复后可见） | **PASS**（同上） |
| SVC-B08 | L1 | P0 | 未知 serviceId 详情 404；空门店（不存在/无服务）列表 200 空页不暴露门店状态细节 | **PASS**（同上） |
| SVC-B09 | L1 | P0 | 无会话 401；非法路径 ID 400；`page=0`/`pageSize=51` 400；未知查询参数（`extra=1`）400；详情带任何 query 400 | **PASS**（同上） |
| SVC-B10 | L1 | P0 | 快照值拷贝（W2-SVC-002）：查询后改价/改名，已返回副本不变，再查得新值 | **PASS**（同上） |
| SVC-B11 | L1 | P0 | 读接口不要求 X-Request-Id（23号 §3：查询 GET 无必填）：GET 不带 X-Request-Id 正常 200 | **PASS**（同上，测试全部 GET 未带该头） |
| SVC-B12 | L1 | P0 | SVC-D5 反例：非所有者消费者调所有者视角 `checkOrderEligibility` → NOT_FOUND（未冒用商家身份） | **PASS**（同上） |
| SVC-B13 | L1 | P0 | 404 错误体 code 与已批契约一致（提案/回执写 `SERVICE_NOT_FOUND`；实现现返回 `COMMON_NOT_FOUND`——**契约漂移待裁决**，见 §5 漂移记录与 progress 证据） | **FAIL（契约符合性）**——HTTP 404 语义正确，错误 code 与已批文案不一致 |
| SVC-B20 | L1 | P0 | 【写入方】商家 OWNER 发布服务（草稿创建/编辑/提交审核）：状态机流转、字段校验、X-Request-Id 必填 + requestId 幂等重放（同 requestId 同参重放返回首结果，异参 409 `IDEMPOTENCY_KEY_CONFLICT`） | **BLOCKED**（无写入方，SVC-D4；ADM-001 承接） |
| SVC-B21 | L1 | P0 | 【写入方】运营审核：待审核→上架（C 端立即可见）/审核驳回（不可见）；运营强制下架（违规/投诉/资质不符）→ C 端隐藏、详情 404，不影响在途订单（快照履约属 ORD，另行验收） | **BLOCKED**（同上） |
| SVC-B22 | L1 | P0 | 【写入方】下架→重新上架必须重新过审（防绕过审核改价）；有未完成订单仅允许下架不允许删除 | **BLOCKED**（同上） |
| SVC-B23 | L2 | P1 | C 端/M 端页面 Mock 联调：服务列表/详情页按契约 schema 解析（ID/金额 String、状态由服务端给出） | NOT_EXECUTED |
| SVC-B24 | L3 | P0 | 真实闭环：发布→（审核上架）→列表/详情可见→下架→列表隐藏→详情 404；curl 两路由正例+404+鉴权反例，核对包裹结构/字段类型/错误码与 12 号注册表一致 | **BLOCKED**（写入方缺失；纯读部分本应起服验证，本轮环境被占用按指令跳过，见 §4） |
| SVC-B25 | L4 | — | 完整业务流程（预约/订单/支付/排期/核销） | **范围外，禁止标记通过** |

## 3. 反例覆盖对照（任务必含项 → 清单条目）

| 必含反例 | 条目 |
|---|---|
| 下架/草稿/待审核不可见且详情 404 | SVC-B02/B03（待审核=写入方 schema 缺口登记于 §2） |
| 商家 OFFLINE / 门店 FROZEN 隐藏 | SVC-B04/B05、STO-A02/A03 |
| 事实源故障 503 与确认不存在 404 严格区分不混同 | SVC-B06、STO-A05 |
| 恢复后可见 | SVC-B07、STO-A06 |
| 无会话/会话过期按契约（401，data=null） | SVC-B09、STO-A08 |
| requestId 幂等重放（写操作；读 GET 无此必填） | SVC-B20（BLOCKED）、SVC-B11（PASS） |
| 分页/筛选契约符合 | SVC-B01/B09、STO-A09 |
| ID 与金额 String | SVC-B01、STO-A01 |

## 4. 本轮执行记录与证据

- 2026-09-22 重跑 `ServiceQueryHttpTest`（真实 MySQL 33452 + Redis 16383，Java 21.0.11，真实申请→审批→签署链 + SQL 播种）：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`。命令、完整日志、surefire 摘要、逐断言→用例映射见 `planning/progress/2026-09-22/qa/`（d4-servicequeryhttptest-run.log、d4-contract-conformance.md）。
- L3 起服验证未执行：MySQL 33452 / Redis 16383 均被既有进程占用（非本人启动，不可停止），按任务指令"如被占用，记录并跳过启动改为运行模块测试"处理。环境明细见 d4-contract-conformance.md §1。

## 5. 契约漂移登记（2026-09-22 抽检发现，详见 progress 证据）

1. **HTTP10 §3.3.1 缺失**：决定回执"权威同步（随本切片 PR）：…10 号 §3.3.1…"未落盘——`docs/04-api/10-HTTP-API-Contract-v0.4.md` 无 §3.3.1、无 SERVICE_NOT_FOUND、无两路由响应示例；OpenAPI11 两操作与 07号 §5.1.1 均交叉引用该不存在章节（`see HTTP contract 10 section 3.3.1`）。PR#65 的 docs 提交（`50f7ff4`）stat 中无 10 号文件。
2. **404 错误 code**：已批提案 §3 与决定回执第 1 条明写详情 404 `SERVICE_NOT_FOUND`；实现（`ServiceQueryService.notFound()`）返回 `COMMON_NOT_FOUND`。HTTP 状态与防探测语义正确，错误 code 与已批文案不一致，需 Contract Owner 裁决改实现或修订契约。

## 6. 遗留与下一步

- 特性(a) 全部条目待角色B契约冻结后复核（更新本表并消除【待契约冻结后复核】标注）。
- SVC-B13 漂移、HTTP10 §3.3.1 补同步：提请 Contract Owner（CCR 修订或实现修复）。
- SVC-B20～B22 依赖 ADM-001 服务写入方立项（含 status 状态机扩展 CCR：待审核/审核驳回）。
- L2/L3 层在写入方与门店读切片落地后由 QA 复跑；L4 永不在本 Issue 标记通过。
