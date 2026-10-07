# Schedule Write Contract v0.1（商家排期写侧）

状态：IMPLEMENTED_DEFAULT_OFF，2026-10-02；默认关闭，不表示生产已开放或页面已联调。2026-10-05 用户裁决：§7-1（B5）按方案 A 落地显式 SOLD_OUT 派生态与释放联动、§7-3（原登记的 200 上限问题）落地批量命令单次 200 条上限，均已并入本契约（见 §3 与 §3.1）；§7-2（C 端 kind）裁决本批不做。**2026-10-07 增补：§3.3 商家窗口列表标准分页（#106 登记的契约缺口，向后兼容、无新错误码、无 Schema 变更）。****本文档原编号 52：与 #101（staff-identity，52-Merchant-Staff-Identity）撞号，#101 合并后已重编号为 53（2026-10-06，含 docs/03-database/SQL53、11号 x-contract、代码注释与测试引用同步）。** 产品依据：[SSOT §29](../00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md)、[PRD29](../01-prd/29-排期人员容量与维护人工裁决补充-v1.0.md)、SSOT §12/§13；技术契约：[34号排期保护契约](34-Schedule-Protection-Contract-v0.1.md)（SCHC-1～4，已批）、[34号存储](../03-database/34-Schedule-Protection-Storage-v0.1.md)、[36号预约与订单人员保护](36-Reservation-Order-Protection-Contract-v0.1.md)（ROC 已批、事实 API 已交付）、[写入提案 v0.2](../../planning/ccr/CCR-W2-API-001/schedule-write-proposal.md) 与[联合审阅回执](../../planning/ccr/CCR-W2-API-001/schedule-review-decisions.md)、[四项技术裁决回执](../../planning/ccr/CCR-W2-API-001/schedule-write-completion-decisions.md)。存储增量见 [SQL53](../03-database/53-Schedule-Write-Schema-v0.1.sql) 与隔离迁移 `schedule-migration/V29__schedule_write.sql`（仅显式 `schw001_*` 库可执行，永不跑共享数据源；SOLD_OUT 为既有 `status VARCHAR(16)` 列的应用层枚举值，无 DDL 变更）。

## 1. 范围与开关

本契约承接 SCH-004 写侧：服务时段窗口（`window_kind=GENERAL/PICKUP/RETURN`）、员工排班（AVAILABLE/CLOSED）、员工具体服务能力集合及商家工作台读。员工档案六接口归 MER（27号 §6.1），订单指派归 SCH-003/ORDER（07号 §6.2），运营只读监管延后（SCHW-D8），均不在本批。C 端可约响应的 `windowId/kind` 增列须与 SCH-003 hold/swap 及 07/10/11 联合交付，本批不改 C 端读契约。

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

窗口身份 `storeId+serviceId+windowKind` 创建后固定，PUT 不可转移归属或改 kind/服务（34号 §4）；换目标=受保护关闭后新建。履约方式×kind 写入校验 400：IN_STORE 仅 GENERAL，PICKUP_DELIVERY 仅 PICKUP/RETURN。同店同服务同 kind 的 OPEN 窗不重叠、相邻半开可衔接；reopen 同样核验。已占用（TEMP_LOCKED/CONFIRMED 原窗 claim）时禁止关闭、降容量、改时间；升容量放行（SCHW-D4）。

### 3.1 SOLD_OUT 派生态与释放联动（2026-10-05 裁决，方案 A）

窗口 `status` 为三值：`OPEN`/`CLOSED`/`SOLD_OUT`（既有 `VARCHAR(16)` 列的应用层枚举，无 DDL 变更、无新错误码）。SOLD_OUT 是**系统计算的派生态**（"已约满"），不是商家手工置位的状态：

- **占用口径**：窗口原行上的有效 claim 数（TEMP_LOCKED/CONFIRMED，与 SCHW-D4 occupied 及求解器全店占用不变量同口径）；容量为窗口自身 `configuredCapacity`。有效占用 ≥ 容量即 SOLD_OUT，释放后回到 OPEN。
- **进入**：预约占容量的同一事务内派生——临时锁位（hold）写满窗口、商家 open 重开且占用已满、以及换期（swap）把占用换入时，系统即翻转。
- **退出（释放联动）**：退款释放（`ReservationRefundReleaseApiImpl`）、临时锁位超时过期/取消（`ReservationExpiryApiImpl`）及换期换出，均在释放同一事务内重判并回到 OPEN；确认（confirm）不改占用，故不翻转。翻转 CAS 在观察到的前置状态上、随事务提交，无双写窗口；翻转仅推进窗口 `version`（商家乐观并发可见），不写 `schedule_write_action` 审计（非商家动作）。
- **商家语义（最小口径，PRD29 未另设规则）**：SOLD_OUT 窗口不得由商家"强制可约"。占用保护照旧适用——close 409 `SCHEDULE_WINDOW_STATE_NOT_ALLOWED`（满窗必被占用拦截）、改时间/降容量 409（SCHW-D4）；**升容量放行**，同事务按新容量重判（占用低于新容量即回 OPEN）。对 CLOSED 窗 open 重开后由系统按占用重判（可能直接呈 SOLD_OUT）。SOLD_OUT 与 OPEN 同占一个"开放位"：重叠校验（创建/改期/reopen）把 SOLD_OUT 视同 OPEN，杜绝释放回 OPEN 后出现两个重叠开放窗。
- **读侧尊重**：预约/改期容量证明的原窗资格认 `OPEN` 及其派生 `SOLD_OUT`（SOLD_OUT 视同原开放窗），净新增占用由 `CapacityFeasibilitySolver` 占用守卫拒绝（409 `SCHEDULE_CAPACITY_EXCEEDED`）；改期（swap）先移除本单旧占用再整体复核，故在 SOLD_OUT 原窗内换时段仍可行（容量守恒）。C 端 availability/selection 列表仍列 SOLD_OUT 窗（`remaining=0`/`available=false`，与此前满量 OPEN 窗的呈现一致，不改变 C 端响应形状）；商家工作台列表可按 `status=SOLD_OUT` 过滤。

| Method | Path | Body | 回执/状态 |
|---|---|---|---|
| GET | /merchant/stores/{storeId}/availability-windows | query: merchantId 必填，serviceId/kind/status 可选（status ∈ OPEN/CLOSED/SOLD_OUT），page/pageSize 可选（2026-10-07 分页增补，见 §3.3） | 不带分页参数：`{storeId,items:[{windowId,merchantId,storeId,serviceId,windowKind,startAt,endAt,configuredCapacity,status,version,updatedAt}]}` 与既有形状一致（全量，无 LIMIT）；任一带分页参数：同一 items 外加标准信封 `page/pageSize/total`（§3.3）。含 CLOSED/SOLD_OUT 与版本，`Cache-Control: no-store` |
| POST | /merchant/stores/{storeId}/availability-windows | merchantId,serviceId,windowKind,startAt,endAt,configuredCapacity | 201/200 `{windowId,…,status:"OPEN",version:"0"}` |
| PUT | /merchant/stores/{storeId}/availability-windows/{windowId} | merchantId,startAt,endAt,expectedVersion；configuredCapacity/reason 可选 | `{window,…}`；仅 OPEN/SOLD_OUT 可编辑；占用时改时间或降容量 409；升容量同事务按占用重判（SOLD_OUT 可回 OPEN） |
| POST | /merchant/stores/{storeId}/availability-windows/{windowId}/close | merchantId,expectedVersion,reason 必填 | `{window,status:"CLOSED"}`；占用（含 SOLD_OUT 满窗）409 `SCHEDULE_WINDOW_STATE_NOT_ALLOWED` |
| POST | /merchant/stores/{storeId}/availability-windows/{windowId}/open | merchantId,expectedVersion | `{window,status:"OPEN"}`；重叠（含对 SOLD_OUT 窗）409 `SCHEDULE_WINDOW_OVERLAP`；重开后系统按占用重判，可能直接返回 SOLD_OUT |
| POST | /merchant/stores/{storeId}/availability-windows/batch-close | merchantId,fromDate,toDate(Asia/Shanghai 日历日),reason 必填 | `{storeId,closedWindows:[…],blockedWindows:[{window…,reasonCode}]}`；部分成功允许，全受阻不伪称成功；跨天相交整窗处理（SCHW-D5）；**单次条目上限 200**（见 §3.2）；SOLD_OUT 目标视同开放目标参与相交计数，满窗占用照常列入 blockedWindows 明示 |

不提供 DELETE/物理删除（SCHW-D3）；禁周模板三字段（dayOfWeek/repeatWeekly/copyNextWeek）不引入（SSOT §12.1）。

### 3.2 批量命令单次条目上限 200（2026-10-05 裁决）

`batch-close` 单次处理的窗口条目（= 相交区间内 OPEN/SOLD_OUT 目标，即 `closedWindows+blockedWindows` 候选合计）**超过 200 时整笔拒绝**：400 `COMMON_INVALID_ARGUMENT`（沿用既有参数错误码，无新错误码），拒绝发生在任何窗口关闭之前，不存在"前 200 已关、余量被静默丢弃"的部分执行；商家缩小日历日范围分批重试（复用同一 requestId + 不同参数按 23 号属异参 409，应换新 requestId）。人员能力集合（§5）非批量命令，其条目数维持不设上限（仅 06号 INT 技术边界）。

### 3.3 商家窗口列表分页（2026-10-07 增补，#106 登记缺口）

`GET /merchant/stores/{storeId}/availability-windows` 增补可选分页参数，口径沿仓库通用分页基线：`page` 1..10000 默认 1、`pageSize` 1..50 默认 20（对齐通知列表先例 CCR-W2-NOTIFICATION-001 与 10号 §3.3 门店服务列表同款；信封遵 10号 §2.9 通则），无新错误码、无 Schema 变更：

- **向后兼容（非 breaking）**：`page`/`pageSize` 均不携带（含空串视同缺省）时为**不分页模式**——行为与 2026-10-07 之前逐字节一致：全量返回、SQL 无 LIMIT、响应 data 仅 `{storeId,items}` 不含信封字段。现网 M 端排期页（#106 全量拉取+客户端分组、1000 条解码 sanity 上限）不受任何影响；前端切换分页消费属后续切片。
- **分页模式**：任一参数出现即进入分页模式，未携带的那个取默认值（如仅 `pageSize=10` 则 page=1）。响应 data 为 `{storeId,items,page,pageSize,total}`；`total` 为过滤后（serviceId/kind/status 与 items 同一 WHERE）匹配总窗数，与页无关；超末页 `items` 为空数组、`total` 不变（10号通则）。行与 total 在同一 repeatable-read 快照内读取，不混两个时刻。
- **非法值 400 `COMMON_INVALID_ARGUMENT`**：非数字、越界（page<1 或 >10000、pageSize<1 或 >50）、未知参数、重复参数；空串视同缺省（仅剩空串则不分页/取默认）。
- **排序固定不变**：`start_at` 升序、`id` 升序 tiebreak（既有口径），不接排序参数；过滤先应用、分页在过滤之后（先过滤再计数与切片）。
- 幂等、审计（读侧本无审计）、`no-store`、OWNER 准入、开关分层语义均不变。

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

`schedule_write_action` 唯一键 `(request_id,target_type,target_id)`；批量关窗按受影响目标逐窗留审计。测试映射：W2-SCHW-001/003/004/005/006/007/008/012 的核心行由 `ScheduleMerchantCommandMySqlTest`（真实 MySQL，含容量守卫与写侧一致性：占用窗守卫、指派保护、全店复核、CAS 冲突、幂等重放）与 `ScheduleWriteDisabledTest` 覆盖；§3.1/§3.2 裁决行为由 `ScheduleSoldOutLinkMySqlTest` 覆盖（真实 MySQL：SOLD_OUT 进入/退款释放与过期回位、双连接并发不双卖、守卫尊重 SOLD_OUT、批量 >200 整笔拒绝与 SOLD_OUT 目标明示）及 `ScheduleSelectionQueryMySqlTest`（SOLD_OUT 窗读侧保留呈现 remaining=0）；§3.3 分页增补由 `MerchantSchedulePaginationHttpTest`（pet-boot，隔离真实 MySQL：不分页向后兼容形状、显式切片与 total、超末页空 items、非法参数 400、排序稳定、过滤×分页合成）覆盖；W2-SCHW-002/011 中涉及 C 端 kind 增列与 07/10/11 旧字段兼容的完整联调、W2-SCHW-013 MER 员工录入全链路属后续切片验收，本批不冒认（真实 MySQL 多连接并发演练由 §3.1 的双连接竞争用例部分覆盖，全链路压测仍属后续）。

## 7. 阻塞与待裁决（2026-10-05 用户裁决落定）

1. **B5 售罄自动/手动规则——已裁决，方案 A 已实现**：窗口引入系统派生 SOLD_OUT 状态（占用达容量进入，退款释放/超时过期取消/换期释放联动回位），实现口径与商家交互见 §3.1。原登记「PRD29 未定义自动置 CLOSED/手工售罄」由本裁决补充关闭；不设商家"一键售罄"手工置位（最小语义）。
2. **C 端可选 kind 过滤与 `windowId/kind` 增列——已裁决，本批不做**：维持与 SCH-003 hold/swap 及 07/10/11 联合交付；本批不改 C 端契约（仅读侧保留 SOLD_OUT 呈现，见 §3.1）。
3. **批量操作上限——已裁决，已实现**：batch-close/batch 类命令单次条目 >200 拒绝（400 既有参数错误码），见 §3.2。原登记的「跨日/节假日语义」仍维持：仅已批的批量关窗跨天整窗+逐窗恢复；持续性停业开关、节假日模板范围外，任何扩展需新裁决。
4. **存量 GENERAL 盘点/迁移（SCHC-4）**：上线前盘点与隔离脚本、回填核对与回滚路径未交付；本批迁移不回填任何旧行。归属后续切片（与员工身份切片 #101 协调排序）。
5. **「每人最多 200 项服务」上限**：随 §3.2 裁决明确为"批量命令条目上限"而非"每人能力项数上限"；每员工能力项数仍不设上限（仅 06号 INT 技术边界校验）。
6. **排期负责人子账号角色**：员工相关项，**裁决归属 #101（staff-identity）/后续切片**；V1 写入门禁=主账号 OWNER 不变。

> 编号备注：本文档（Contract53/SQL53）原编号 52 与 #101 撞号，#101 合并（develop 0292681）后已重编号为 53 并同步全部交叉引用；历史 commit 内的"待重编号"登记就此了结。
