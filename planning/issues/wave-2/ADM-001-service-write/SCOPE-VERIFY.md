# ADM-001 服务写入方切片 — 归属与范围核验结论（第一阶段）

日期：2026-09-22。分支 `codex/adm001-service-write-20260922`（基于 develop `52a1c45`，PR#65 服务域读切片已合入）。
角色：角色A（服务写入方后端）。本文件是**核验与规划文档**，不含任何已实现的 HTTP/Schema/代码变更；权威文档与 backend/ 未被修改。
配套文档：CCR 草案 [service-write-proposal.md](../../../ccr/CCR-W2-API-001/service-write-proposal.md)（DRAFT 未提交人工批准）、[TEST-PLAN.md](TEST-PLAN.md)。

引用约定：PRD 为 docx，引用按章节号（提取自 word/document.xml 纯文本，行号为提取文本行号，仅供复核定位）；仓库内文档与代码引用为 文件:行号。

---

## 0. 范围登记核验（本切片从哪里来）

| 登记点 | 原文 | 位置 |
|---|---|---|
| SVC-001 提案 §7 承接表 | "服务写入方（商家端/运营端服务管理 CRUD，HTTP10 merchant/services 路由族）→ ADM-001 运营治理行既有'服务操作'范围（M-002 工作台消费；具体切片届时立项）" | planning/ccr/CCR-W2-API-001/service-domain-proposal.md:146 |
| CCR-W2-API-001 索引"运营治理"行 | "service_item写入方（商家/运营服务管理CRUD）在'服务操作'范围内由后续ADM切片承接，M-002工作台消费（SVC-001提案§7）" | planning/ccr/CCR-W2-API-001.md:16 |
| SVC-001 读切片交接 | "服务写入方登记于 ADM-001'服务操作'范围（M-002 消费）" | planning/issues/wave-2/SVC-001-read-slice/HANDOFF.md:28 |
| SVC-D4 披露 | "V1 尚无 service_item 写路径，测试经 SQL 夹具播种；完整'发布→看到→预约'未验收" | planning/ccr/CCR-W2-API-001/service-domain-proposal.md:33 |
| Issue Catalog | ADM-001 运营商家/服务治理API，P0，依赖 MER-001/SVC-001/AUTH-001，原实现范围 `backend/pet-admin-*`，状态 BLOCKED（完整 Issue 门禁） | planning/ISSUE_CATALOG.csv:42 |

**结论**：本切片归属清晰——service_item 写路径（商家侧 CRUD/上下架/提交审核 + 运营侧审核/强制下架）由 ADM-001 承接，SVC-001 已交付读侧四条件可见性（本切片必须保持不破坏）。注意：Catalog 登记的 ADM-001 实现范围是 `backend/pet-admin-*`，但 service_item 的域归属在 pet-service-*（读切片先例：命令/DTO 落 pet-service-api，biz 落 pet-service-biz，HTTP 适配落 pet-boot）。**模块落位本身列为待裁决问题 Q1**（见 §9），推荐域归属 pet-service-*，理由见 A5。

---

## A1. 归属核验（谁可以做什么）

### A1.1 PRD 原文核验

**商家侧（商家端 PRD《03-PRD-商家端-V1.0-最终基线.docx》）**：

- §5.5 服务项目管理（提取文本约 653-780 行）：页面用户角色＝**商家管理员**；核心功能＝"支持新增、编辑、上下架服务项目"；"支持查看服务审核结果、驳回原因，并支持修改后重新提交审核"；"审核被驳回时列表展示驳回标签，点击进入可查看原因并修改重提"。
- §5.6 角色权限矩阵（提取文本约 787-800 行）：**服务项目列仅主账号 ✓，核销员 ✕**。§5.6 员工管理开头明确"员工管理仅主账号（PRD §5.6 明确）"同类语义已被 27 号 §5 引用（docs/04-api/27-Merchant-Domain-Contract-v0.1.md:91）。
- §6.3 服务项目状态机（提取文本约 1857-1915 行）逐行：

| 状态 | 触发 | 条件 | 下一状态 |
|---|---|---|---|
| 草稿 | 保存草稿 | 可重复编辑 | 草稿 |
| 草稿 | 提交审核 | 必填项齐全 | 待审核 |
| 待审核 | 审核通过 | 资料与服务内容合规 | 上架 |
| 待审核 | 审核驳回 | 信息不完整或不合规 | 审核驳回 |
| 审核驳回 | 修改后重新提交 | 已按驳回原因修正 | 待审核（保留历史驳回记录） |
| 上架 | 商家下架 | 无未完成订单，或仅存在可继续履约的未完成订单 | 下架 |
| 上架 | 库存售罄 | 可售次数或库存为 0 | 售罄 |
| 售罄 | 补充库存 | 库存或次数大于 0 | 上架 |
| 下架 | 商家重新上架 | 服务信息仍有效 | **待审核（需重新过审，防止绕过审核改价）** |
| 上架/下架 | 硬删除 | 无任何关联订单 | 已删除 |

**运营侧（运营端 PRD《04-PRD-运营端-V1.0-最终基线.docx》）**：

- §3.3 适用范围与边界（提取文本约 67-68 行）：商家管理本期包含"服务项目审核"；边界明确**"不代商家上下架服务"**（同表列出"不代商家维护排期、不代商家接单拒单…"）。
- §5.2 服务上架闭环（提取文本约 402-408 行）：草稿→提交审核（校验必填项：名称、分类、履约方式、售价、封面、适用宠物类型、门店）→审核员校验（名称合规、分类命中平台字典、价格合理、图片内容安全、售后规则完整）→通过置"上架"/驳回填原因商家可重提→"第五步：运营端保留强制下架权（违规、投诉成立、资质不符），下架不影响已支付订单按快照履约"；衔接点明确"下架→重新上架→待审核路径……运营端必须保留该强制审核环节"。
- §6.2.4 服务项目审核与管理（提取文本约 887-1000 行）：审核列表/审核操作（通过/驳回必填原因 10-500 字，"支持批量通过低风险服务"）/服务库巡检（按分类、价格区间、销量、评价均分筛选，"支持强制下架"）/驳回记录（历史驳回原因与重提次数）；状态机规则与商家端 §6.3 一致；"已支付订单引用服务快照，服务后续下架或改价不得覆盖历史订单；强制下架不影响在途订单履约"；字段表含 审核意见（驳回必填 10-500 字）与 审核SLA（默认 24 小时，超时标红）。
- §6.1 运营工作台（提取文本约 474 行）："SLA 规则：商家入驻审核 24 小时、**服务审核 24 小时**、售后首次响应 24 小时"。
- §7.2 服务项目审核状态机（提取文本约 2817-2860 行）：与商家端 §6.3 逐行一致，并增加运营分支"上架→运营强制下架（违规、投诉成立或资质不符）→下架，运营分支，同步通知商家"。

**结论（与任务指示一致，逐条证实）**：
1. 商家管理员（主账号 OWNER）负责新增/编辑/上下架；运营负责审核通过/驳回与强制下架、不代商家上下架——**证实**。
2. 状态机 草稿→提交审核→待审核→(通过)上架/(驳回)审核驳回→(修改重提)→待审核；上架→(商家下架)→下架；下架→(重新上架)→待审核（防绕审改价）——**证实**，商家端 §6.3 与运营端 §7.2 逐行一致。

### A1.2 与 SSOT / 技术基线冲突核对

检索 `docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md` 与 `docs/02-architecture/05-技术基线-v0.6.md` 中"上下架/强制下架/售罄/服务管理/服务项目/服务审核"关键词：**均无条款**（SSOT 仅规定服务形态=单次预约、必须预约、分钟级排期、容量=min(配置容量,可用人员)、用户不可选服务人员——SSOT §一/§十九 封板规则，见 01-SSOT:21-30、925-940、1000-1030 行区域）。

**结论：未发现 SSOT/技术基线与 PRD 状态机冲突**。发现的三处文档内/跨文档张力（如实报告，不是冲突，需在 CCR 中显式裁决）：

| # | 张力 | 事实 | 处置建议 |
|---|---|---|---|
| T1 | HTTP10 §4.10 路由名 vs 状态机语义 | HTTP10 §4.10（docs/04-api/10-HTTP-API-Contract-v0.4.md:990-999）列有 `POST /merchant/services/{serviceId}/online`，但 PRD 状态机中商家动作**从不直接产生"上架"**（上架只由运营审核通过产生）；下架→重新上架→**待审核** | 保留既有路由名（不改权威已列路由），`online` 语义定义为"提交审核/重新提交"（进入 REVIEWING），由 CCR 显式裁决（SVCW-D2） |
| T2 | 商家端 §5.5 字段表"状态"枚举仅 草稿/上架/下架 | 商家端 §5.5 字段表（提取文本约 764-766 行）只列 3 态，而同文档 §6.3 与运营端 §6.2.4/§7.2 均为完整状态机（待审核/审核驳回等） | 以 §6.3/§7.2 状态机为权威（页面状态字段按完整枚举建模），在 CCR 中披露该字段表简写 |
| T3 | 售罄态只在商家端 §6.3 出现 | 运营端 §7.2 状态机与 §6.2.4 字段表均**无售罄**；权威 Schema 无库存/可售次数字段（见 A2）；V1 容量=min(商家配置,可用人员) 属排期域 | 本轮不实现售罄（见 A3），列未决项供产品确认口径 |

另核验 26 号申请审核补充、22 号运营权限补充、24 号 MFA 裁决：与本切片无冲突（单运营直接发布不影响"审核"动作本身的权限校验；审计/原因要求不变——AGENTS.md 55-59 行）。

---

## A2. Schema 差距清单

### A2.1 权威现状

`service_item`（docs/03-database/06-核心数据库Schema-v0.1.sql:129-146）：`id/merchant_id/store_id/category_id/service_name/description(TEXT)/price DECIMAL(18,2)/duration_minutes/fulfillment_type(IN_STORE|PICKUP_DELIVERY)/status VARCHAR(32) 注释'DRAFT/ACTIVE/OFFLINE'/version/created_at/updated_at`；索引 idx_service_store_status(store_id,status)、idx_service_merchant_status、idx_service_category。`service_category`（同文件:118-127）平台字典已存在（category_name 唯一、status ENABLED、sort_no）。

### A2.2 status 值差距

- 缺 `REVIEWING`（待审核）、`REJECTED`（审核驳回）。
- `SOLD_OUT`（售罄）与 `DELETED`（已删除）：PRD 有、权威无——分别按 A3/A4 建议本轮不实现。
- 扩展方式：权威 06 SQL 的 status 列无 CHECK 约束（仅注释），值域扩展=注释修订+应用层校验；读侧索引 idx_service_store_status 不受影响（C 端仍只取 status='ACTIVE'）。

### A2.3 字段差距（PRD 商家端 §5.5 字段表 + 运营端 §6.2-5 字段表 逐列对照）

| PRD 字段 | 权威现状 | 差距结论 |
|---|---|---|
| 服务名称 2-50 字 | service_name VARCHAR(128) | 长度收紧在应用层校验（2-50），不改列 |
| 服务分类（平台字典） | category_id + service_category 表 | 已有，无差距；需 ENABLED 校验 |
| 服务形态（固定单次预约） | 无列 | V1 固定值，**不加列**（SSOT 封板"只做单次服务"） |
| 履约方式 | fulfillment_type | 已有 |
| 销售价格 >0.00 精确到分 | price DECIMAL(18,2) | 已有；>0 校验在应用层（列注释无约束） |
| 划线价（可空，≥售价，低于售价拦截） | **缺** | 新增 `list_price DECIMAL(18,2) NULL` + CHECK(list_price IS NULL OR list_price>=price) |
| 封面图（必填，JPG/PNG/JPEG，需内容安全审核） | **缺** | 新增 `cover_asset_id BIGINT NULL`（提交审核时应用层必填；资产链路见 SVCW-D4） |
| 服务时长（分钟，>0） | duration_minutes INT | 已有 |
| 是否需要预约（固定"是"） | 无列 | **不加列**（SSOT/AGENTS 硬规则"必须预约"） |
| 是否需要核销（是/否，默认是） | **缺** | 新增 `verification_required TINYINT(1) NOT NULL DEFAULT 1` |
| 适用宠物类型（狗/猫/异宠/全部，多选，可空） | **缺** | 新增 `applicable_pet_types VARCHAR(64) NULL`（逗号分隔 DOG/CAT/EXOTIC/ALL；ALL 与其他互斥在应用层校验） |
| 门店 | store_id | 已有 |
| 服务人员要求（0-200 字，可空） | **缺** | 新增 `staff_requirement VARCHAR(200) NULL` |
| 状态 | status | 见 A2.2 |
| 服务说明（0-1000 字） | description TEXT | 已有（长度校验应用层） |
| 备注（可空） | **缺** | 新增 `remark VARCHAR(500) NULL`（PRD 未给长度，按 500 提案，见 SVCW-D3） |
| 售后说明（可空） | **缺** | 新增 `aftersale_note VARCHAR(500) NULL`（同上） |
| 服务编号（系统生成） | id BIGINT Snowflake | 已有 |
| （审核 SLA 起算） | **缺** | 新增 `submitted_at DATETIME(3) NULL`（最近一次提交审核时间，运营端剩余时长计算来源） |

销量/预约量/评价均分（商家端查看、运营端巡检筛选）：**不在本轮**（统计数据属报表/聚合域，无权威事实源，见 §8 未做事项）。

### A2.4 审核记录/驳回原因是否需要新表

对照申请审核既有表设计模式（docs/03-database/29-Merchant-Application-Schema-v0.1.sql:411-452 `merchant_application_review_task`（AVAILABLE/CLAIMED/CLOSED 领取模型）、:453-560 `merchant_application_review_decision`（append-only，opinion CHECK 10-500、authz_version/scope_version/request_id VARBINARY(512)/trace_id））：

- **需要新表**（决定不可只存 service_item 当前列）：PRD 要求"历史驳回记录保留""展示历史驳回原因与重提次数"，单列无法承载多轮审核历史。
- **建议轻量模型**（SVCW-D6 推荐）：只建 append-only `service_review_decision`（含 submission_no、decision_type APPROVE/REJECT、opinion、operator、authz/scope、request_id、trace_id），**不建领取任务表**。理由：V1 单运营（AGENTS.md 已批准运营权限补充），无多人领单场景；并发双审由 status CAS（REVIEWING→ACTIVE/REJECTED）+ version 乐观锁拦截。申请审核的领取模型是为审核队列多人领单设计，服务审核 V1 无此需要。
- 运营强制下架审计：另建 `service_governance_action`（action_type=FORCE_OFFLINE、reason 10-500、operator、authz/scope、request_id）或并入决定表——提案取"分表"（语义不同：强制下架不是对某次提交的审核结论），备选合并见 SVCW-D6。
- **防"审核中偷改"**：不引入提交快照/revision 表（申请审核模式），改为**状态机写保护**——仅 DRAFT/REJECTED/OFFLINE 可编辑，REVIEWING/ACTIVE 不可编辑（ACTIVE 改内容必须先下架，与"防绕审改价"一致）。见 SVCW-D1。

### A2.5 精确 DDL 草案（详见 CCR 草案 SVCW-D3，此处为结论）

- 文档编号：docs/03-database/ 现有编号至 **31**（06/13/14/15/25/26/27/28/29/31），全库文档编号已用至 **32**（04-api/32）；**建议新文档编号 33**（如 `33-Service-Write-Schema-v0.1.sql`，遵循"编号-名称-Schema-v0.x.sql"命名）。
- pet-boot Flyway 目录现状：`backend/pet-boot/src/main/resources/db/migration/` 仅有 README（要求实现前整合为有序 Flyway 迁移），`db/admin-auth-migration/V26__admin_auth.sql` 是唯一先例——**Flyway 版本号在实现阶段另行分派，不在本草案虚构**。
- DDL 明细（列名/类型/可空/默认/注释/CHECK）见 service-write-proposal.md §4。

### A2.6 service_category 是否纳入本轮

**建议不纳入运营类目 CRUD**，理由：
1. 类目是平台统一字典（唯一名约束+sort_no 已备），V1 类目集合稳定，运营维护类目属 ADM-001 其余"服务治理"范围，本轮聚焦 service_item 写路径（不扩大切片）。
2. 但商家提交审核必填分类且须"命中平台字典"（运营端 §5.2 第三步/§6.2.4），商家端表单需要 ENABLED 类目列表——**建议随本切片提供最小只读路由**（如 `GET /api/v1/merchant/service-categories`，仅 ENABLED、按 sort_no 排序；C 端筛选消费后续由 C-003 需求另走 CCR），运营 CRUD 后续切片。备选：完全不提供（前端硬编码，违反"平台统一字典"事实源原则，不推荐）。见 SVCW-D3。

---

## A3. 状态机设计建议

**推荐：status 单列扩展五值（DRAFT/REVIEWING/ACTIVE/OFFLINE/REJECTED）+ append-only 审核决定表**，不采用"审核子表承载审核态"方案。理由：

1. **读切片不破坏**：C 端可见性=四条件合取中 `service.status='ACTIVE'`（07 号 §5.1.1，docs/04-api/07-内部API-Contract-v0.6.md:291-294；10 号 §3.3.1）。REVIEWING/REJECTED 与 OFFLINE/DRAFT 一样对 C 端不可见——读契约、可见性 SQL、索引（idx_service_store_status）零变化；内部 `checkBookable` reasonCodes 继续用 SERVICE_OFFLINE 表达非 ACTIVE。
2. 若审核态放子表，可见性判定需 join 或双列复合，已批读切片语义被改动，违背"不披露变更不得改契约"。
3. 审核历史/驳回原因由决定表承载（A2.4），status 只表达当前态——与申请审核"主表 status + 决定表历史"同构。

**编辑权限与状态机（SVCW-D1）**：PRD 状态机无 ACTIVE→REVIEWING 直接转移边；防绕审改价的机制就是"下架→重新上架→待审"。故：PUT 编辑仅允许 DRAFT/REJECTED/OFFLINE；对 ACTIVE/REVIEWING 编辑返回 409（新码 SERVICE_STATE_NOT_ALLOWED，见 A6）。备选（编辑 ACTIVE 自动转待审核）新增 PRD 未定义的转移边，**不推荐**。

**售罄态本轮不实现**（推荐，理由）：
1. **无事实源**：PRD 触发条件是"可售次数或库存为 0"，但 V1 权威 Schema 无库存/可售次数字段，PRD 字段表亦无该字段；AGENTS/SSOT 硬规则为分钟级排期、容量=min(商家配置容量,当前可用服务人员)（AGENTS.md:34、SSOT §一/§十九）——容量属排期域动态事实，不是服务静态库存。
2. **依赖排期域**：真实"无位可约"由 availability 查询（HTTP10 §3.4，SCH 域）表达，本轮明令不提前实现排期。
3. **跨 PRD 口径不一**：运营端 §7.2 状态机无售罄（T3）。
4. 实现售罄需要"库存字段+售罄/恢复转移+排期联动"整链，属扩大范围。→ 列未决项（产品确认 V1 是否保留售罄概念；若保留，需先裁决库存事实源归属）。

---

## A4. 硬删除

**结论：本轮不含删除能力，列为未决项。** 证据链：

1. PRD 允许"上架/下架→硬删除（无任何关联订单）→已删除"（商家端 §6.3；运营端 §7.2/§6.2.4"当服务存在未完成订单时，仅允许下架，不允许硬删除"）。
2. 权威 Schema 无已删除态（A2.2），也没有软删除列。
3. **跨模块引用查询缺失**："无任何关联订单"需查询订单域 order_service_snapshot（06 SQL:273-291）是否引用该 serviceId——订单域未提供该内部查询，service-biz 也不得跨模块访问 order Repository/DO/Entity（AGENTS.md:12-13）。
4. **先例**：27 号 §6.1 员工 disable 即因"通过 order-api 查询当前在途指派"契约缺失而保留 **IMPLEMENTATION_BLOCKED**，并明确"即使未来有一次查询，也需与指派建立受审查的并发协调，不能'先查无订单再停用'宣称安全"（docs/04-api/27-Merchant-Domain-Contract-v0.1.md:114）。服务硬删除同理（查无订单后到删除提交之间存在新订单创建窗口），且 V1 尚无订单创建路径，无法验证关联完整性。

---

## A5. 内部 API 草案要点（详见 CCR 草案 §3）

- **命名**：pet-service-api 新增 `ServiceCommandApi`（对齐 pet-merchant-api `MerchantApplicationCommandApi` 先例，backend/pet-merchant-api/.../command/MerchantApplicationCommandApi.java:5-20）；稳定命令名沿用 27 号 §7 风格（`merchant.service.create/update/submit/offline`、`admin.service.review.decide`、`admin.service.forceOffline`），参与 23 号公共幂等 scope。
- **商家事实获取（禁止跨模块）**：pet-service-biz 仅依赖 pet-merchant-api 模块（pom 已依赖 pet-merchant-api，backend/pet-service-biz/pom.xml:30），范式=`ServiceQueryApiImpl` 构造注入 `MerchantDisplayEligibilityApi`（backend/pet-service-biz/.../apiimpl/ServiceQueryApiImpl.java:19-21，boot 装配 ServiceQueryConfiguration.java:18-21）。写入方需两类事实：归属/主账号资格 与 商家可经营状态：
  - **推荐**：`MerchantMembershipQueryApi.getFacts(userId, merchantId, storeId)`（27 号 §5，docs/04-api/27-Merchant-Domain-Contract-v0.1.md:73-76）——单次返回 membershipKind=OWNER、membershipEnabled、applicationStatus、signingStatus、merchantStatus、storeStatus、authzVersion，足以判 OWNER+可经营；无关系 404、依赖失败 503 语义已批。
  - 备选：`checkOrderEligibility`（27 号 §4，所有者视角三布尔）——需再补 OWNER 归属判定，两查询拼装，不推荐。
  - 注意：SVC-D5 明确 `checkOrderEligibility` 消费者不可借用（service-domain-proposal.md:34-46），但写入方命令的主体恰是商家主账号本人（operator=USER/会话 userId），与该接口的 owner_user_id 匹配前提一致——即便如此仍推荐 getFacts（显式 membership 事实，避免把"新单资格"误当"管理资格"）。
- **幂等与乐观锁**：所有写命令 CommandContext.requestId 必填（23 号 §3；HTTP10 §2.3）；沿用 27 号 §7 已批执行序（独立短事务绑定 RESERVED→业务事务复核权限/状态/expectedVersion→写事实+审计+最小回执→同一次提交）；绑定记录用既有 `command_idempotency`（docs/03-database/14-Command-Idempotency-Schema-v0.1.sql:5-22），service 模块自有 Mapper。编辑/上下架/审核携带 `expectedVersion`（service_item.version，27 号 §3 同款语义）。

---

## A6. HTTP 草案要点（详见 CCR 草案 §5）

**商家端路由**：沿用 HTTP10 §4.10 已列六条（10 号:990-999）——`GET/POST /api/v1/merchant/services`、`GET/PUT /api/v1/merchant/services/{serviceId}`、`POST .../{serviceId}/online`、`POST .../{serviceId}/offline`；列表/详情必须提供目标 merchantId/storeId 并校验主账号范围（27 号 §6 同款）。
**会话机制先例**：`/api/v1/merchant/*` 前缀由 C 端 MINIAPP Bearer 会话保护——CBearerSessionFilter.protectedPath 已含 `/api/v1/merchant/agreement`、`/api/v1/merchant/auth/admission`（backend/pet-boot/.../config/CBearerSessionFilter.java:48-51），CSessionSecurityConfiguration 对应 permitAll+filter 强制（CSessionSecurityConfiguration.java:69-82）；控制器先例 CMerchantMembershipController（/api/v1/c 前缀但同会话族）与 MerchantAdmissionController（/api/v1/merchant 前缀）。新路由需加入 protectedPath 与 securityMatcher 白名单（boot 装配属实现阶段）。
**`online` 语义**=提交审核/重新提交（→REVIEWING），`offline`=ACTIVE→OFFLINE；"上架"仅由运营审核通过产生（SVCW-D2，见 T1）。

**运营端路由**：HTTP10 §5 现无服务审核路由（§5.1-5.8 覆盖订单/退款/售后/商家下线/评价/券积消息审计，10 号:1118-1307）——**契约缺口，由本 CCR 草案补**。仿 MerchantApplicationAdminController 先例（backend/pet-boot/.../merchant/MerchantApplicationAdminController.java:17-39，`/api/v1/admin/merchant-applications` + `pet.merchant.application.enabled` 开关）：提议 `GET /api/v1/admin/services`（审核列表，status=REVIEWING 等筛选+SLA 剩余）、`GET /api/v1/admin/services/{serviceId}`、`POST /api/v1/admin/services/{serviceId}/decision`（APPROVE/REJECT，REJECT 必填 opinion 10-500）、`POST /api/v1/admin/services/{serviceId}/force-offline`（强制下架，必填 reason）。真实 admin 会话（AdminBearerAuthenticationFilter→AdminSessionView）+ 动作码校验先例=`admin(req,"merchant.application.decide")`（MerchantHttpSupport.java:32-41）；动作码命名沿用 `merchant.application.read/decide` 风格提议 `service.review.read`、`service.review.decide`、`service.forceOffline`（登记归 AUTH/RBAC Owner，跨文件依赖）。批量通过低风险服务（PRD §6.2.4）建议延后（SVCW-D6）。

**错误码**：沿用 12 号注册表 COMMON 族+既有 SERVICE_NOT_FOUND/SERVICE_NOT_BOOKABLE（12 号:163-169）；27 号式映射（400/401/403/404 防枚举/409 COMMON_CONFLICT/409 IDEMPOTENCY_KEY_CONFLICT/503，12 号:223）。**新码提案（对齐 `<DOMAIN>_<SEMANTIC>` 与 ORDER_STATE_NOT_ALLOWED/REFUND_MERCHANT_REASON_REQUIRED 风格）**：`SERVICE_STATE_NOT_ALLOWED`（409，状态机违规：编辑 ACTIVE/REVIEWING、对非 DRAFT/REJECTED/OFFLINE 提交、对非 ACTIVE 下架、对非 REVIEWING 审核决定等）与 `SERVICE_REVIEW_REASON_REQUIRED`（400/驳回与强制下架原因缺失）。是否复用 COMMON_CONFLICT 表达状态冲突（不新增码）列为备选（SVCW-D8）。
**通用**：金额 BigDecimal/DECIMAL(18,2)、HTTP 金额两位小数 String（10 号 §2.7）；HTTP/JSON ID 一律 String（10 号 §2.5）；写必带 X-Request-Id UUID（10 号 §2.3）；首次创建 201、幂等重放 200（27 号 §6）。

---

## A7. 权限反例矩阵（测试将逐项覆盖，见 TEST-PLAN）

| # | 主体 | 动作 | 预期 |
|---|---|---|---|
| P1 | 无会话/失效会话 | 任一 merchant/services 或 admin/services 路由 | 401 COMMON_UNAUTHORIZED |
| P2 | 普通消费者（C 会话，与商家无归属关系） | GET/POST/PUT merchant services | 404 COMMON_NOT_FOUND（防枚举，与"服务不存在"同响应；对齐 27 号 §3"商家/门店/人员不存在或超出授权归属→404 不区分存在与越权"与 SVC-D1b 语义） |
| P3 | STAFF/子账号（V1 无 STAFF 行，按 membershipKind!=OWNER 构造反例） | 服务项目管理（任一写命令） | 有成员关系但非 OWNER → 403 COMMON_FORBIDDEN；无关系 → 404（PRD §5.6 矩阵：服务项目仅主账号） |
| P4 | 主账号但商家/门店非 ACTIVE 可经营（如 FROZEN/OFFLINE） | 新增/编辑/提交服务 | 按事实拒绝（409/403 语义在 CCR 定：推荐 409 SERVICE_STATE_NOT_ALLOWED 或 403，见 SVCW-D5），不得放行 |
| P5 | 运营（admin 会话） | 新增/编辑/上下架商家服务 | **路由不存在**（契约层面禁止）；运营仅 decision/force-offline（PRD §3.3"不代商家上下架服务"） |
| P6 | 运营无 `service.review.decide` 动作码 | 审核决定 | 403 COMMON_FORBIDDEN（先例 MerchantHttpSupport.admin()） |
| P7 | 商家 A 主账号 | 操作商家 B 的 serviceId | 404 COMMON_NOT_FOUND（跨商家归属防枚举） |
| P8 | 消费者（C 会话） | 直接调用 /api/v1/admin/services | 401/403（admin 会话面隔离，AdminBearerAuthenticationFilter） |
| P9 | 运营对非 REVIEWING 服务 | decision | 409 SERVICE_STATE_NOT_ALLOWED |
| P10 | 任何人 | 对 ACTIVE/REVIEWING 服务 PUT 编辑 | 409（防审核中偷改/防绕审改价，SVCW-D1） |

---

## A8. 事件

核验 docs/05-events/08-Integration-Event-Catalog-v0.6.md：
- 首批事件表（08 号:28-46）覆盖 PAYMENT/ORDER/REFUND/AFTERSALE/**MERCHANT(仅 MerchantDisabledEvent)**/REVIEW 聚合，**无 SERVICE 聚合事件**、无服务上架/审核/下架事件定义。
- 后补的 `MerchantApplicationReviewedEvent.v1`（08 号:440+）是申请审核专用，载荷/消费者（notification 域）与 service_item 无关。

**结论：本轮不新增事件、不做 Outbox 接入。理由**：
1. Event08 无 SERVICE 事件定义，新增属权威 Event 契约变更，必须走 CCR+人工批准（AGENTS"无未披露 Contract/Schema/Event 变化"；本 CCR 草案仅提案、未批）。
2. 已识别消费者仅"审核结果站内通知商家"——但 C 端通知收件箱仅覆盖 receiver_type=USER（CCR-W2-NOTIFICATION-001 §1：商家端/运营端消息路径维持未实现），MERCHANT 收件箱未交付，新增事件无落地消费者。
3. C 端读切片按同事务 DB 直读（SVC-D1 四条件），不依赖事件驱动。
- **遗留张力（如实报告，列为待裁决 Q4）**：SSOT §一 强制通知行"订单、退款、核销、售后、**审核**必须保留站内消息"（docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md:46）。若该"审核"覆盖服务审核结果通知，则后续需要：MERCHANT 收件箱切片 + ServiceReviewedEvent（仿申请审核模式）+ Outbox 接入。本轮先不实现，提案 SVCW-D10 登记。

---

## 9. 待人工裁决问题清单（汇总）

| # | 问题 | 推荐 | 影响面 |
|---|---|---|---|
| Q1 | 写入方模块落位：ADM-001 Catalog 写 backend/pet-admin-*，但 service_item 域事实在 pet-service-*（读切片先例） | 命令/DTO 落 pet-service-api、biz 落 pet-service-biz、HTTP 适配（merchant/admin 两面）落 pet-boot，pet-admin-* 不新建服务写实现 | 模块边界/架构检查/文件唯一 Writer |
| Q2 | `POST /merchant/services/{id}/online` 语义＝提交审核（→REVIEWING），保留路由名不改权威 | 接受推荐（SVCW-D2） | HTTP10 §4.10 权威同步措辞 |
| Q3 | Schema 扩列+两新表（审核决定/治理动作）+status 五值，新文档编号 33 | 接受推荐（SVCW-D1/D3） | 06 号/新 33 号、迁移计划 |
| Q4 | SSOT"审核必须保留站内消息"是否覆盖服务审核结果→MERCHANT 收件箱+事件+Outbox | 本轮不做，登记后续切片（SVCW-D10） | notification 域、Event08、Outbox |
| Q5 | 封面图链路：cover_asset_id 落列后，上传/内容安全/展示走哪条通道（复用私有资产管线新增 SERVICE_COVER 类型 vs 延后整条链路并放宽提交必填） | 复用私有资产上传+扫描管线（需 MER 域 Owner 评审 31 号契约增补）；若被否，则封面延后并同步调整"提交必填"校验（与 PRD 冲突须产品确认）（SVCW-D4） | 31 号契约、MER 域文件、PRD 必填项 |
| Q6 | 编辑 ACTIVE 服务是否允许"编辑即自动重审"（PRD 无此转移边） | 不允许，必须先下架再编辑再重提（SVCW-D1） | 商家端交互文案 |
| Q7 | 商家/门店非可经营时商家写命令的失败语义（409 vs 403） | 409 SERVICE_STATE_NOT_ALLOWED（事实可读但状态不允许写；对齐"状态冲突"族）（SVCW-D5） | 错误码注册表、前端提示 |
| Q8 | 售罄态 V1 是否保留（无库存事实源、运营端状态机无此态） | 本轮不实现，请产品确认口径（SVCW-D9） | 状态机、排期域联动 |
| Q9 | 运营动作码（service.review.read/decide/forceOffline）登记进 admin RBAC | 接受命名并交 AUTH/RBAC Owner 登记（SVCW-D7） | 26 号 admin auth、CCR-PERM |
| Q10 | 新错误码 SERVICE_STATE_NOT_ALLOWED / SERVICE_REVIEW_REASON_REQUIRED | 接受（或复用 COMMON_CONFLICT+COMMON_INVALID_ARGUMENT 不新增）（SVCW-D8） | 12 号注册表 |

---

## 10. 本轮未做 / 受阻塞事项

1. 未修改任何权威文档（00-ssot/01-prd/02-architecture/03-database/04-api/05-events）、backend/ 代码、READY_QUEUE*/WORK_STATE/CCR-W2-API-001 主索引——按分工仅新增本目录与 CCR 草案目录内文件。
2. 未实现排期/库存锁/订单创建/支付（AGENTS 硬规则）；未实现售罄（A3）、硬删除（A4）、批量通过、运营类目 CRUD（A2.6）、审核结果通知/事件/Outbox（A8）、销量预约量统计字段。
3. 模块测试本轮不跑（第一阶段为文档与核验；测试计划见 TEST-PLAN.md，执行待实现切片）。
4. 依赖待办：商家事实查询通道（getFacts）已批（27 号 §5）可直接消费；运营动作码登记、封面资产管线增补（若 Q5 采纳推荐）需跨域 Owner 配合。
