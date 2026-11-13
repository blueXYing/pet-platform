# Schedule Write Contract v0.1（商家排期写侧）

状态：IMPLEMENTED_DEFAULT_OFF，2026-10-02；默认关闭，不表示生产已开放或页面已联调。2026-10-05 用户裁决：§7-1（B5）按方案 A 落地显式 SOLD_OUT 派生态与释放联动、§7-3（原登记的 200 上限问题）落地批量命令单次 200 条上限，均已并入本契约（见 §3 与 §3.1）；§7-2（C 端 kind）裁决本批不做。**2026-10-07 增补：§3.3 商家窗口列表标准分页（#106 登记的契约缺口，向后兼容、无新错误码、无 Schema 变更）。****本文档原编号 52：与 #101（staff-identity，52-Merchant-Staff-Identity）撞号，#101 合并后已重编号为 53（2026-10-06，含 docs/03-database/SQL53、11号 x-contract、代码注释与测试引用同步）。** **2026-11-13 修订（v0.2，[CCR-SCH-SLOT-001](../../planning/ccr/CCR-SCH-SLOT-001.md) 全店统一时间槽+单容量池）：§3 窗口身份改为 `storeId+时间区间`（全店共享，去 `serviceId`/`windowKind` 维度），请求/响应示例与 §3.3 列表过滤参数同步去 serviceId/kind，履约方式×kind 写入校验删除，窗口重叠校验改同店口径；batch-close、SCHC 闸门与事务序、幂等/审计/乐观并发/SOLD_OUT 派生态均不变；涉及 `kind` 的 C 端增列/过滤项随维度取消不再适用。** **2026-11-13 修订（v0.3，[CCR-SCH-SLOT-003](../../planning/ccr/CCR-SCH-SLOT-003.md) 周循环模板·读时展开+下单物化混合模型）：新增 §3.6 周模板配置面（`GET/PUT /merchant/stores/{storeId}/week-template`，新表 `merchant_schedule_week_template` 进 SQL53/V29）；C 端 availability/selection items = 真实窗口 ∪ 模板展开（未来周几展开至 now+maxDays 边界，与真实 OPEN/SOLD_OUT 窗重叠的模板段跳过）；38号 booking hold（及 46号换期同链）增加"模板段物化"内部步骤——选定时刻无真实窗口且命中启用日段内时同事务创建真实分钟级窗口（容量=段容量、version 0、WINDOW_CREATE 审计 actor 系统）后正常 claim，对外错误面不变；§3.4 batch-generate 保留为手动批量入口（不再是周模板的保存路径）；SSOT §12.1 周模板禁令按 CCR §3 措辞修订（窗口数据结构仍禁周期字段）。** 产品依据：[SSOT §29](../00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md)、[PRD29](../01-prd/29-排期人员容量与维护人工裁决补充-v1.0.md)、SSOT §12/§13；技术契约：[34号排期保护契约](34-Schedule-Protection-Contract-v0.1.md)（SCHC-1～4，已批）、[34号存储](../03-database/34-Schedule-Protection-Storage-v0.1.md)、[36号预约与订单人员保护](36-Reservation-Order-Protection-Contract-v0.1.md)（ROC 已批、事实 API 已交付）、[写入提案 v0.2](../../planning/ccr/CCR-W2-API-001/schedule-write-proposal.md) 与[联合审阅回执](../../planning/ccr/CCR-W2-API-001/schedule-review-decisions.md)、[四项技术裁决回执](../../planning/ccr/CCR-W2-API-001/schedule-write-completion-decisions.md)。存储增量见 [SQL53](../03-database/53-Schedule-Write-Schema-v0.1.sql) 与隔离迁移 `schedule-migration/V29__schedule_write.sql`（仅显式 `schw001_*` 库可执行，永不跑共享数据源；SOLD_OUT 为既有 `status VARCHAR(16)` 列的应用层枚举值，无 DDL 变更；v0.3 增 `merchant_schedule_week_template`）。

## 1. 范围与开关

本契约承接 SCH-004 写侧：服务时段窗口（2026-11-13 起为全店统一时间槽，无 `windowKind`/服务维度，见文首 v0.2 修订；原 `window_kind=GENERAL/PICKUP/RETURN` 分类废除）、员工排班（AVAILABLE/CLOSED）、员工具体服务能力集合、门店可预约范围配置（§3.5）及商家工作台读；v0.3 起新增门店周循环模板配置面（§3.6，独立配置行，窗口表仍无周期字段）。员工档案六接口归 MER（27号 §6.1），订单指派归 SCH-003/ORDER（07号 §6.2），运营只读监管延后（SCHW-D8），均不在本批。C 端可约响应的 `windowId` 增列须与 SCH-003 hold/swap 及 07/10/11 联合交付（`kind` 增列随统一槽取消），本批不改 C 端读契约。

开关分层默认关闭：

| 配置 | 默认 | 作用 |
|---|---|---|
| `pet.schedule.protection.enabled` | false | 预约保护基础（共同门店闸门、SCH/ORDER/MER 事实源） |
| `pet.schedule.command.enabled` | false | 排期写侧内部命令装配；开启时要求 protection 事实 bean 齐备，否则启动失败（失败关闭） |
| `pet.schedule.command.http.enabled` | false | 上述 HTTP 路由注册 |
| `pet.schedule.command.migration-enabled` + `migration-database=schw001_*` | false | 仅显式隔离库执行 V29 |

## 2. 公共约定

- 路由前缀 `/api/v1`；MINIAPP Bearer 会话（`CBearerSessionFilter` 对 `/api/v1/merchant/stores/`、`/api/v1/merchant/staff/` 强制商家会话）。V1 写入门禁=商家主账号 OWNER（SCH-D7「排期负责人」子账号角色随成员绑定另行交付）：后端以 `MerchantAdmissionQueryApi.getAdmission` 四态合取校验（SVCW-D5 先例），非 OWNER 404 防枚举，不可经营 409 `SERVICE_STATE_NOT_ALLOWED`，事实不可读 503。
- 幂等遵 [23号](23-公共接口与幂等契约补充-v0.1.md)：所有写请求携带 UUID `X-Request-Id`；同 key 同参成功重放先重验当前 OWNER 准入再返回原回执；同 key 异参 409 `IDEMPOTENCY_KEY_CONFLICT`。首次创建 201、重放与其他命令 200。
- 每个命令在同一主库 DataSource 的一个顶层 READ_COMMITTED 事务内经 `ScheduleCapacityGuardApi.acquire` 按数值升序取得每店稳定闸门（SCHC-1），与 34号 §2 的事务序一致；读侧事实为锁后主库当前读。
- ID 为 Snowflake 十进制 String；窗口/集合版本为非负十进制 String（能力集合版本遵 SCHC-2，不经 JS Number）；时间为分钟精度 `YYYY-MM-DDTHH:mm:ss.SSSZ`。分钟级半开区间 `[startAt,endAt)`，无固定 60 分钟槽（SSOT §12.1）。
- 审计：成功动作同事务写 append-only `schedule_write_action`（动作/操作人/时间/目标/requestId/版本前后/原因），失败不留伪成功审计；关闭、临时停业及减少人员可用性必填 reason（1..500）。
- 错误码：新增 `SCHEDULE_WINDOW_OVERLAP`(409)、`SCHEDULE_WINDOW_STATE_NOT_ALLOWED`(409)（12号 §4 已收录）；其余沿用 12号通用映射（400/401/403/404/409 `COMMON_CONFLICT`/409 `IDEMPOTENCY_KEY_CONFLICT`/503 `COMMON_DEPENDENCY_UNAVAILABLE`）。

## 3. 服务时段窗口（SCHW-D2/D3/D4/D5 + 2026-10-05 SOLD_OUT 裁决）

窗口身份 `storeId+时间区间`（2026-11-13 CCR-SCH-SLOT-001：全店统一时间槽，无 `serviceId`/`windowKind` 维度）创建后固定，PUT 不可转移归属（34号 §4）；换目标=受保护关闭后新建。原履约方式×kind 写入校验（IN_STORE 仅 GENERAL，PICKUP_DELIVERY 仅 PICKUP/RETURN）已随统一槽删除。同店的 OPEN 窗不重叠、相邻半开可衔接；reopen 同样核验。已占用（TEMP_LOCKED/CONFIRMED 原窗 claim）时禁止关闭、降容量、改时间；升容量放行（SCHW-D4）。

### 3.1 SOLD_OUT 派生态与释放联动（2026-10-05 裁决，方案 A）

窗口 `status` 为三值：`OPEN`/`CLOSED`/`SOLD_OUT`（既有 `VARCHAR(16)` 列的应用层枚举，无 DDL 变更、无新错误码）。SOLD_OUT 是**系统计算的派生态**（"已约满"），不是商家手工置位的状态：

- **占用口径**：窗口原行上的有效 claim 数（TEMP_LOCKED/CONFIRMED，全店跨服务合计——统一槽下任何服务的有效预约都占本槽；与 SCHW-D4 occupied 及求解器全店占用不变量同口径）；容量为窗口自身 `configuredCapacity`。有效占用 ≥ 容量即 SOLD_OUT，释放后回到 OPEN。
- **进入**：预约占容量的同一事务内派生——临时锁位（hold）写满窗口、商家 open 重开且占用已满、以及换期（swap）把占用换入时，系统即翻转。
- **退出（释放联动）**：退款释放（`ReservationRefundReleaseApiImpl`）、临时锁位超时过期/取消（`ReservationExpiryApiImpl`）及换期换出，均在释放同一事务内重判并回到 OPEN；确认（confirm）不改占用，故不翻转。翻转 CAS 在观察到的前置状态上、随事务提交，无双写窗口；翻转仅推进窗口 `version`（商家乐观并发可见），不写 `schedule_write_action` 审计（非商家动作）。
- **商家语义（最小口径，PRD29 未另设规则）**：SOLD_OUT 窗口不得由商家"强制可约"。占用保护照旧适用——close 409 `SCHEDULE_WINDOW_STATE_NOT_ALLOWED`（满窗必被占用拦截）、改时间/降容量 409（SCHW-D4）；**升容量放行**，同事务按新容量重判（占用低于新容量即回 OPEN）。对 CLOSED 窗 open 重开后由系统按占用重判（可能直接呈 SOLD_OUT）。SOLD_OUT 与 OPEN 同占一个"开放位"：重叠校验（创建/改期/reopen）把 SOLD_OUT 视同 OPEN，杜绝释放回 OPEN 后出现两个重叠开放窗。
- **读侧尊重**：预约/改期容量证明的原窗资格认 `OPEN` 及其派生 `SOLD_OUT`（SOLD_OUT 视同原开放窗），净新增占用由 `CapacityFeasibilitySolver` 占用守卫拒绝（409 `SCHEDULE_CAPACITY_EXCEEDED`）；改期（swap）先移除本单旧占用再整体复核，故在 SOLD_OUT 原窗内换时段仍可行（容量守恒）。C 端 availability/selection 列表仍列 SOLD_OUT 窗（`remaining=0`/`available=false`，与此前满量 OPEN 窗的呈现一致，不改变 C 端响应形状）；商家工作台列表可按 `status=SOLD_OUT` 过滤。

| Method | Path | Body | 回执/状态 |
|---|---|---|---|
| GET | /merchant/stores/{storeId}/availability-windows | query: merchantId 必填，status 可选（status ∈ OPEN/CLOSED/SOLD_OUT），page/pageSize 可选（2026-10-07 分页增补，见 §3.3；v0.2 起无 serviceId/kind 过滤参数） | 不带分页参数：`{storeId,items:[{windowId,merchantId,storeId,startAt,endAt,configuredCapacity,status,version,updatedAt}]}`（v0.2 起去 serviceId/windowKind 字段，全量，无 LIMIT）；任一带分页参数：同一 items 外加标准信封 `page/pageSize/total`（§3.3）。含 CLOSED/SOLD_OUT 与版本，`Cache-Control: no-store` |
| POST | /merchant/stores/{storeId}/availability-windows | merchantId,startAt,endAt,configuredCapacity | 201/200 `{windowId,…,status:"OPEN",version:"0"}` |
| PUT | /merchant/stores/{storeId}/availability-windows/{windowId} | merchantId,startAt,endAt,expectedVersion；configuredCapacity/reason 可选 | `{window,…}`；仅 OPEN/SOLD_OUT 可编辑；占用时改时间或降容量 409；升容量同事务按占用重判（SOLD_OUT 可回 OPEN） |
| POST | /merchant/stores/{storeId}/availability-windows/{windowId}/close | merchantId,expectedVersion,reason 必填 | `{window,status:"CLOSED"}`；占用（含 SOLD_OUT 满窗）409 `SCHEDULE_WINDOW_STATE_NOT_ALLOWED` |
| POST | /merchant/stores/{storeId}/availability-windows/{windowId}/open | merchantId,expectedVersion | `{window,status:"OPEN"}`；重叠（含对 SOLD_OUT 窗）409 `SCHEDULE_WINDOW_OVERLAP`；重开后系统按占用重判，可能直接返回 SOLD_OUT |
| POST | /merchant/stores/{storeId}/availability-windows/batch-close | merchantId,fromDate,toDate(Asia/Shanghai 日历日),reason 必填 | `{storeId,closedWindows:[…],blockedWindows:[{window…,reasonCode}]}`；部分成功允许，全受阻不伪称成功；跨天相交整窗处理（SCHW-D5）；**单次条目上限 200**（见 §3.2）；SOLD_OUT 目标视同开放目标参与相交计数，满窗占用照常列入 blockedWindows 明示 |
| POST | /merchant/stores/{storeId}/availability-windows/batch-generate | merchantId,fromDate,toDate(Asia/Shanghai 日历日，跨度 1..366 天),dailyStart,dailyEnd("HH:mm"，start<end),intervalMinutes(1..1440，缺省 60),configuredCapacity(1..999),reason(1..500) 必填 | `{storeId,createdWindows:[…],closedWindows:[…],skippedWindows:[{window…,reasonCode:"OCCUPIED"}]}`；**覆盖重建语义**（覆盖关闭范围=与生成时间区间相交，非整天历日）见 §3.4（2026-11-13 CCR-SCH-SLOT-002 增补；v0.3 起保留为手动批量入口，不再是周模板的保存路径，见 §3.6） |
| GET | /merchant/stores/{storeId}/booking-window | query: merchantId 必填 | `{leadMinutes,maxDays,version,updatedAt}`；缺行返回默认 60/7、version "0"、updatedAt null（§3.5；CCR-SCH-SLOT-002 增补；形状与前端 H5 钉死一致，无 storeId 字段） |
| PUT | /merchant/stores/{storeId}/booking-window | merchantId,leadMinutes(0..1440),maxDays(1..90),expectedVersion 必填 | `{leadMinutes,maxDays,version}`（无 updatedAt）；全替换 + 乐观锁 409 `COMMON_CONFLICT`（§3.5）；调宽/调窄均非减少可用性动作，reason 不需要 |
| GET | /merchant/stores/{storeId}/week-template | query: merchantId 必填 | `{days:[{enabled,segments:[{start,end,capacity}]}×7],version,updatedAt}`；缺行=七天全休（enabled=false、segments 空数组）、version "0"、updatedAt null（§3.6；CCR-SCH-SLOT-003 增补） |
| PUT | /merchant/stores/{storeId}/week-template | merchantId,days(7 天全量),expectedVersion 必填 | `{days,version}`；全替换 + 乐观锁 409 `COMMON_CONFLICT`（§3.6）；改模板非减少可用性动作（已物化窗口一律不动），reason 不需要 |

不提供 DELETE/物理删除（SCHW-D3）；窗口表禁周模板三字段（dayOfWeek/repeatWeekly/copyNextWeek）不引入（SSOT §12.1 v0.3 修订：周模板=独立配置面 `merchant_schedule_week_template`，读时展开、下单物化为真实分钟级窗口，见 §3.6）。

### 3.2 批量命令单次条目上限 200（2026-10-05 裁决）

`batch-close` 单次处理的窗口条目（= 相交区间内 OPEN/SOLD_OUT 目标，即 `closedWindows+blockedWindows` 候选合计）**超过 200 时整笔拒绝**：400 `COMMON_INVALID_ARGUMENT`（沿用既有参数错误码，无新错误码），拒绝发生在任何窗口关闭之前，不存在"前 200 已关、余量被静默丢弃"的部分执行；商家缩小日历日范围分批重试（复用同一 requestId + 不同参数按 23 号属异参 409，应换新 requestId）。人员能力集合（§5）非批量命令，其条目数维持不设上限（仅 06号 INT 技术边界）。

### 3.3 商家窗口列表分页（2026-10-07 增补，#106 登记缺口）

`GET /merchant/stores/{storeId}/availability-windows` 增补可选分页参数，口径沿仓库通用分页基线：`page` 1..10000 默认 1、`pageSize` 1..50 默认 20（对齐通知列表先例 CCR-W2-NOTIFICATION-001 与 10号 §3.3 门店服务列表同款；信封遵 10号 §2.9 通则），无新错误码、无 Schema 变更：

- **向后兼容（非 breaking）**：`page`/`pageSize` 均不携带（含空串视同缺省）时为**不分页模式**——行为与 2026-10-07 之前逐字节一致：全量返回、SQL 无 LIMIT、响应 data 仅 `{storeId,items}` 不含信封字段。现网 M 端排期页（#106 全量拉取+客户端分组、1000 条解码 sanity 上限）不受任何影响；前端切换分页消费属后续切片。
- **分页模式**：任一参数出现即进入分页模式，未携带的那个取默认值（如仅 `pageSize=10` 则 page=1）。响应 data 为 `{storeId,items,page,pageSize,total}`；`total` 为过滤后（status 与 items 同一 WHERE；v0.2 起无 serviceId/kind 过滤）匹配总窗数，与页无关；超末页 `items` 为空数组、`total` 不变（10号通则）。行与 total 在同一 repeatable-read 快照内读取，不混两个时刻。
- **非法值 400 `COMMON_INVALID_ARGUMENT`**：非数字、越界（page<1 或 >10000、pageSize<1 或 >50）、未知参数、重复参数；空串视同缺省（仅剩空串则不分页/取默认）。
- **排序固定不变**：`start_at` 升序、`id` 升序 tiebreak（既有口径），不接排序参数；过滤先应用、分页在过滤之后（先过滤再计数与切片）。
- 幂等、审计（读侧本无审计）、`no-store`、OWNER 准入、开关分层语义均不变。

### 3.4 排期模板批量生成 batch-generate（2026-11-13 增补，[CCR-SCH-SLOT-002](../../planning/ccr/CCR-SCH-SLOT-002.md)）

`POST /merchant/stores/{storeId}/availability-windows/batch-generate`：**覆盖重建**语义，一个命令=一个事务（23号幂等绑定 + OWNER 准入 + 每店闸门 + append-only 审计全套沿 §2；关闭目标逐窗记 `WINDOW_BATCH_CLOSE` 审计、生成窗逐窗记 `WINDOW_CREATE` 审计，同表同动作族，无新审计目标类型）：

- **请求体严格 JSON**：`{merchantId, fromDate, toDate, dailyStart, dailyEnd, intervalMinutes, configuredCapacity, reason}`；`fromDate/toDate` 为 Asia/Shanghai 历日（跨度 1..366 天）、`dailyStart/dailyEnd` 为 "HH:mm" 且 start<end、`intervalMinutes` 1..1440（缺省 60）、`configuredCapacity` 1..999、`reason` 1..500（覆盖动作必填沿 close 纪律）。
- **覆盖关闭范围=生成时间区间相交，非整天历日**（2026-11-13 与前端 H5 钉死的口径）：只关闭与生成区间 `[fromDate 当日 dailyStart, toDate 当日 dailyEnd]`（Asia/Shanghai，半开）相交的既有 OPEN/SOLD_OUT 统一槽——保存并生效按"逐日逐段"多次调用时，同日后一段不会关掉前一段刚建的窗（半开相邻不相交）。SOLD_OUT 视同开放目标（沿 batch-close 口径）；**被占用（TEMP_LOCKED/CONFIRMED 有效 claim）的窗跳过**并列入 `skippedWindows`（`reasonCode=OCCUPIED`，沿 batch-close blocked 命名族）；随后按 `intervalMinutes` 从 `dailyStart` 起逐段创建分钟级半开窗口 `[dailyStart+k·interval, +interval)`（仅取完整落入 `[dailyStart,dailyEnd)` 的段；余量不足以再切一段时止；容量=请求值，逐日重复）。
- **跳过窗区间内不再生成**：与仍处开放态的窗（被占用跳过窗 + 范围外开放窗）重叠的候选段不创建（同店重叠校验自然拦住；CLOSED 窗不占开放位、不拦生成）。重叠被跳过的候选段不进入任何返回列表，也不计入上限条目。
- **部分成功允许，全受阻不伪称成功**（沿 batch-close）：响应如实分列 `createdWindows/closedWindows/skippedWindows`；关闭旧窗后创建失败留下空档时，商家按列表重试。
- **单次候选条目（created+closed+skipped）上限 200**：超出整笔 400 `COMMON_INVALID_ARGUMENT`（沿 §3.2，拒绝先于任何关闭/创建）；7 天×细间隔场景由前端分段调用。
- 红线不变：禁周模板三字段（dayOfWeek/repeatWeekly/copyNextWeek）指**窗口数据结构**——无任何周期性字段/表进入 `schedule_availability_window`。（2026-11-13 CCR-SCH-SLOT-003 后：周模板改为独立配置面 `week-template` 持久行、读时展开+下单物化，见 §3.6；本命令保留为手动批量入口，前端"周模板编辑器草稿铺排保存"模式废止。）

### 3.5 可预约范围配置 booking-window（2026-11-13 增补，CCR-SCH-SLOT-002；SSOT §12 增行）

规则：客户可预约的时段满足 `startAt ≥ now + leadMinutes` 且 `startAt ≤ now + maxDays 天`；默认 lead=60 分钟、maxDays=7，商家可调（lead 0..1440 分钟、maxDays 1..90 天）。存储=新表 `merchant_schedule_booking_window`（store_id 唯一键、lead_minutes、max_days、version、created/updated；**缺行=默认值，不回填**）——门店级配置，非窗口级，见 [SQL53](../03-database/53-Schedule-Write-Schema-v0.1.sql) 与 V29 增补。

- **GET** 缺行返回默认值（60/7）与逻辑版本 `"0"`、`updatedAt` null，不建行；回执 data `{leadMinutes,maxDays,version,updatedAt}`（无 storeId，前端 H5 钉死形状）。**PUT** 全替换：`expectedVersion` 乐观锁（过期 409 `COMMON_CONFLICT` 提示重读，不 last-write-wins；首次 PUT 带 `"0"` 原子建行推进 `"1"`），回执 data `{leadMinutes,maxDays,version}`（无 updatedAt）；`leadMinutes/maxDays` 越界（lead<0 或 >1440、maxDays<1 或 >90）400 `COMMON_INVALID_ARGUMENT`；同 requestId 同参重放返回首次回执、异参 409 `IDEMPOTENCY_KEY_CONFLICT`（23号）。reason 不需要（调范围非减少可用性动作）；本配置不写 `schedule_write_action` 审计（该表目标类型词表维持 WINDOW/STAFF_WINDOW/CAPABILITY_SET 不变，未扩）。
- **C 端强制**（不改 C 端读契约形状）：availability（含 selection 投影）items 不返回 `startAt < now+lead || startAt > now+maxDays` 的时段（范围外隐藏，非 available=false 呈现）；booking hold（38号）与 reschedule（46号换期=新预约时间）的新预约时间越界 → 400 `COMMON_INVALID_ARGUMENT`（message 说明可约范围；不发明新码）。「不可约日期」不新增规则——由既有 batch-close 实现。
- 读失败关闭语义沿家族（503）；缺行=默认值。

### 3.6 周循环模板 week-template（2026-11-13 增补，[CCR-SCH-SLOT-003](../../planning/ccr/CCR-SCH-SLOT-003.md)；SSOT §12.1 行修订）

模型=**读时展开 + 下单物化**混合（用户 2026-11-13 两轮澄清拍板："365 天都按这个 7 天循环来，而不是生成 365 个 7 天"）。存储=新表 `merchant_schedule_week_template`（store_id 唯一、`days` JSON=7 天×段列表、version、created/updated；**持久配置，永不自动过期，缺行=七天全休 version "0"，不回填**），见 SQL53 与 V29 增补。`days` 数组固定 7 项（下标 0=周一 … 6=周日，ISO 顺序），每项 `{enabled,segments:[{start,end,capacity}]}`；每天 0..24 段、"HH:mm" start<end 不重叠（半开相邻可衔接）、capacity 1..999（段自带容量，缺省语义无——PUT 必须显式携带），空段=该天休；`enabled=false` 的天休（其 segments 原样存储、GET 原样回显、读侧不展开）。

- **GET** 缺行返回七天全休（`enabled:false,segments:[]`）与逻辑版本 `"0"`、`updatedAt` null，不建行；回执 data `{days,version,updatedAt}`（无 storeId，沿 §3.5 家族形状）。**PUT** 严格 JSON 全替换：根对象仅 `{merchantId,days,expectedVersion}`，days 每项仅 `{enabled,segments}`、每段仅 `{start,end,capacity}`（HH:mm 严格校验、capacity 整数 1..999、每天 ≤24 段——越界 400 `COMMON_INVALID_ARGUMENT`；start<end 与不重叠由服务层复核同码拒绝）；`expectedVersion` 乐观锁（过期 409 `COMMON_CONFLICT` 提示重读；首次 PUT 带 `"0"` 原子建行推进 `"1"`），回执 data `{days,version}`；同 requestId 同参重放返回首次回执、异参 409 `IDEMPOTENCY_KEY_CONFLICT`（23号）。reason 不需要（改模板非减少可用性动作）；本配置不写 `schedule_write_action` 审计（目标类型词表维持 WINDOW/STAFF_WINDOW/CAPABILITY_SET 不变）。
- **PUT 只影响未来未物化的展开；已物化窗口（有预约的/商家手建的）一律不动**（用户 2026-11-11 原口径）；商家窗口列表（§3.3）仍=真实窗口，页顶模板状态提示由前端呈现。
- **C 端可约读**（不改 C 端响应形状）：availability/selection 的 items = 真实窗口 ∪ 模板展开——未来日期按周几展开虚拟段，仅展开到 now+maxDays 可约范围内（§3.5 范围闸共用）；与真实 OPEN/SOLD_OUT 窗重叠（半开相交）的模板段跳过（同一时刻不重复呈现）；虚拟段 `effectiveCapacity=段容量`、`occupied=0`；selection 腿型投影（PICKUP/RETURN 或 GENERAL）照常作用于合并集，虚拟段携带确定性虚拟 `windowId`（非真实窗 ID；下单时由物化步骤换成真实窗）。
- **下单物化（38号 hold 内部步骤，46号换期同链）**：选定目标时刻无真实 OPEN/SOLD_OUT 窗口、且该时刻（Asia/Shanghai）落入某启用日的段内（IN_STORE 需整个预约区间含于段内）→ **同事务创建真实分钟级统一槽窗口**（容量=段容量、version 0、`WINDOW_CREATE` 审计 actor=系统）→ 正常 claim。窗口已存在（选定 windowId 命中既有行，含 CLOSED）则完全原逻辑。占用/SOLD_OUT 派生/退款释放/过期回位/改期不变量/批量关窗全链从此完全照常——预约事实 100% 落在真实窗口行上（红线论证见 CCR §2）；对外错误面不变（无模板命中=既有容量证明 409 语义）。
- 红线不变：**无周期字段进入窗口表**（dayOfWeek/repeatWeekly/copyNextWeek 禁令保持，SSOT §12.1）；模板是独立配置面非窗口属性；§3.4 batch-generate 命令与契约保留（手动批量入口）。

## 4. 员工排班（SCHW-D6）

写入前提=员工属该店、在职 ACTIVE、商家/门店准入通过（经 MER 当前事实，失败关闭 503；INACTIVE 409）。同员工 AVAILABLE 排班不重叠，相邻可衔接；关闭/重开保留历史。

| Method | Path | Body | 回执 |
|---|---|---|---|
| GET | /merchant/staff/{staffId}/availability-windows | merchantId,storeId | `{storeId,staffId,items:[{windowId,…,status,version,updatedAt}]}` |
| POST | /merchant/staff/{staffId}/availability-windows | merchantId,storeId,startAt,endAt | 201/200 `{windowId,…,status:"AVAILABLE",version:"0"}` |
| PUT | /merchant/staff/{staffId}/availability-windows/{windowId} | merchantId,storeId,startAt,endAt,expectedVersion；reason 缩减时必填 | `{window,…}` |
| POST | /merchant/staff/{staffId}/availability-windows/{windowId}/close | merchantId,storeId,expectedVersion,reason 必填 | `{window,status:"CLOSED"}` |
| POST | /merchant/staff/{staffId}/availability-windows/{windowId}/open | merchantId,storeId,expectedVersion | `{window,status:"AVAILABLE"}`；重叠 409 |

减少可用性（close 或缩短/移动使新区间不完全覆盖旧区间）的保护=36号已批语义的本批实现：①ORDER `getCurrentAssignments` 显示该员工仍有 `protectRequired` 当前指派 → 409（先合法改派再重试）；②通过后同事务应用变更，再以既有 `CapacityFeasibilitySolver` 对全店活跃预约做整体可行性复核（SCHC-1 事实源+既定预算），不可行 409、预算/事实故障 503 整笔回滚——恢复依赖服务后重试原意。新增/放宽排班不需要占用保护，仍走鉴权、重叠、版本与审计。

## 5. 员工服务能力（SCHC-2）

能力按具体服务项授权，类目只作页面分组，不自动继承（SSOT §29）。存储=34号存储 §3 集合头 `staff_capability_set`（BIGINT 单调版本）+ 既有 `staff_service_capability` ENABLED 明细；明细存在而头缺失=LEGACY_UNVERSIONED，GET/PUT 一律 503 隔离，等待盘点回填，绝不当作空集合版本 0 或被首次 PUT 覆盖。头 version 0 且明细非空同样失败关闭。

| Method | Path | Body | 回执 |
|---|---|---|---|
| GET | /merchant/staff/{staffId}/service-capabilities | query: merchantId,storeId | `{merchantId,storeId,staffId,serviceIds,version}`；仅头与明细都不存在时返回 `serviceIds:[],version:"0"`（`X-Request-Id` 写请求必填，GET 可省） |
| PUT | /merchant/staff/{staffId}/service-capabilities | merchantId,storeId,serviceIds(去重，重复 400),expectedVersion；reason 移除时任一项必填 | `{merchantId,storeId,staffId,serviceIds,version}` |

PUT 为全量替换：过期 `expectedVersion` 409 `COMMON_CONFLICT` 提示重读，不 last-write-wins；首次空集合 PUT 带 `expectedVersion:"0"` 原子建头推进 "1"；同 requestId 同参重放返回首次版本不递增，不同 requestId 的相同集合仍递增并审计；写成空集合头保留不回 0；版本上溢拒绝。目标服务必须属该店该商家（404），不按类目扩权；撤销项先过 §4 同款减员保护再删除明细，未通过 409 整笔回滚。

## 6. 审计与验收边界

`schedule_write_action` 唯一键 `(request_id,target_type,target_id)`；批量关窗按受影响目标逐窗留审计。测试映射：W2-SCHW-001/003/004/005/006/007/008/012 的核心行由 `ScheduleMerchantCommandMySqlTest`（真实 MySQL，含容量守卫与写侧一致性：占用窗守卫、指派保护、全店复核、CAS 冲突、幂等重放）与 `ScheduleWriteDisabledTest` 覆盖；§3.1/§3.2 裁决行为由 `ScheduleSoldOutLinkMySqlTest` 覆盖（真实 MySQL：SOLD_OUT 进入/退款释放与过期回位、双连接并发不双卖、守卫尊重 SOLD_OUT、批量 >200 整笔拒绝与 SOLD_OUT 目标明示）及 `ScheduleSelectionQueryMySqlTest`（SOLD_OUT 窗读侧保留呈现 remaining=0）；§3.3 分页增补由 `MerchantSchedulePaginationHttpTest`（pet-boot，隔离真实 MySQL：不分页向后兼容形状、显式切片与 total、超末页空 items、非法参数 400、排序稳定、过滤×分页合成）覆盖；§3.4/§3.5（CCR-SCH-SLOT-002）由 `ScheduleMerchantCommandMySqlTest`（batch-generate 覆盖重建/占用跳过+OCCUPIED/重叠不生成/>200 整笔 400/幂等重放与异参 409；booking-window GET 默认/PUT CAS 与 1..1440、1..90 边界 400）、`ReservationHoldMySqlTest`（默认 60/7 下 hold 越界 400 及 message 含可约范围）与 `ScheduleSelectionQueryMySqlTest`（lead/maxDays 边界时段隐藏）覆盖，46号换期越界随 `OrderRescheduleAcceptanceTest` 族；§3.6（CCR-SCH-SLOT-003）由 `ScheduleMerchantCommandMySqlTest`（week-template GET 缺行默认/PUT CAS 首建推进/七天与段边界 400/幂等重放与异参 409）、`ScheduleSelectionQueryMySqlTest`（模板∪真实去重、越 maxDays 不展开、七天休无展开、虚拟段形状与腿型投影）与 `ReservationHoldMySqlTest`（hold 物化建窗→claim 落真实窗→SOLD_OUT/释放链回归、无模板命中保持既有冲突）覆盖；W2-SCHW-002/011 中涉及 C 端 kind 增列与 07/10/11 旧字段兼容的完整联调、W2-SCHW-013 MER 员工录入全链路属后续切片验收，本批不冒认（真实 MySQL 多连接并发演练由 §3.1 的双连接竞争用例部分覆盖，全链路压测仍属后续）。

## 7. 阻塞与待裁决（2026-10-05 用户裁决落定）

1. **B5 售罄自动/手动规则——已裁决，方案 A 已实现**：窗口引入系统派生 SOLD_OUT 状态（占用达容量进入，退款释放/超时过期取消/换期释放联动回位），实现口径与商家交互见 §3.1。原登记「PRD29 未定义自动置 CLOSED/手工售罄」由本裁决补充关闭；不设商家"一键售罄"手工置位（最小语义）。
2. **C 端可选 kind 过滤与 `windowId/kind` 增列——已裁决，本批不做**：维持与 SCH-003 hold/swap 及 07/10/11 联合交付；本批不改 C 端契约（仅读侧保留 SOLD_OUT 呈现，见 §3.1）。（2026-11-13 CCR-SCH-SLOT-001 后窗口无 kind 维度，kind 过滤/增列不再适用；`windowId` 增列仍留待联合交付。）
3. **批量操作上限——已裁决，已实现**：batch-close/batch 类命令单次条目 >200 拒绝（400 既有参数错误码），见 §3.2。原登记的「跨日/节假日语义」仍维持：仅已批的批量关窗跨天整窗+逐窗恢复；持续性停业开关、节假日模板范围外，任何扩展需新裁决。
4. **存量 GENERAL 盘点/迁移（SCHC-4）**：上线前盘点与隔离脚本、回填核对与回滚路径未交付；本批迁移不回填任何旧行。归属后续切片（与员工身份切片 #101 协调排序）。
5. **「每人最多 200 项服务」上限**：随 §3.2 裁决明确为"批量命令条目上限"而非"每人能力项数上限"；每员工能力项数仍不设上限（仅 06号 INT 技术边界校验）。
6. **排期负责人子账号角色**：员工相关项，**裁决归属 #101（staff-identity）/后续切片**；V1 写入门禁=主账号 OWNER 不变。

> 编号备注：本文档（Contract53/SQL53）原编号 52 与 #101 撞号，#101 合并（develop 0292681）后已重编号为 53 并同步全部交叉引用；历史 commit 内的"待重编号"登记就此了结。
