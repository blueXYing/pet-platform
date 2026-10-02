# Schedule Write Contract v0.1（商家排期写侧）

状态：IMPLEMENTED_DEFAULT_OFF，2026-10-02；默认关闭，不表示生产已开放或页面已联调。产品依据：[SSOT §29](../00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md)、[PRD29](../01-prd/29-排期人员容量与维护人工裁决补充-v1.0.md)、SSOT §12/§13；技术契约：[34号排期保护契约](34-Schedule-Protection-Contract-v0.1.md)（SCHC-1～4，已批）、[34号存储](../03-database/34-Schedule-Protection-Storage-v0.1.md)、[36号预约与订单人员保护](36-Reservation-Order-Protection-Contract-v0.1.md)（ROC 已批、事实 API 已交付）、[写入提案 v0.2](../../planning/ccr/CCR-W2-API-001/schedule-write-proposal.md) 与[联合审阅回执](../../planning/ccr/CCR-W2-API-001/schedule-review-decisions.md)、[四项技术裁决回执](../../planning/ccr/CCR-W2-API-001/schedule-write-completion-decisions.md)。存储增量见 [SQL52](../03-database/52-Schedule-Write-Schema-v0.1.sql) 与隔离迁移 `schedule-migration/V29__schedule_write.sql`（仅显式 `schw001_*` 库可执行，永不跑共享数据源）。

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

## 3. 服务时段窗口（SCHW-D2/D3/D4/D5）

窗口身份 `storeId+serviceId+windowKind` 创建后固定，PUT 不可转移归属或改 kind/服务（34号 §4）；换目标=受保护关闭后新建。履约方式×kind 写入校验 400：IN_STORE 仅 GENERAL，PICKUP_DELIVERY 仅 PICKUP/RETURN。同店同服务同 kind 的 OPEN 窗不重叠、相邻半开可衔接；reopen 同样核验。已占用（TEMP_LOCKED/CONFIRMED 原窗 claim）时禁止关闭、降容量、改时间；升容量放行（SCHW-D4）。

| Method | Path | Body | 回执/状态 |
|---|---|---|---|
| GET | /merchant/stores/{storeId}/availability-windows | query: merchantId 必填，serviceId/kind/status 可选 | `{storeId,items:[{windowId,merchantId,storeId,serviceId,windowKind,startAt,endAt,configuredCapacity,status,version,updatedAt}]}`；含 CLOSED 与版本，`Cache-Control: no-store` |
| POST | /merchant/stores/{storeId}/availability-windows | merchantId,serviceId,windowKind,startAt,endAt,configuredCapacity | 201/200 `{windowId,…,status:"OPEN",version:"0"}` |
| PUT | /merchant/stores/{storeId}/availability-windows/{windowId} | merchantId,startAt,endAt,expectedVersion；configuredCapacity/reason 可选 | `{window,…}`；仅 OPEN 可编辑；占用时改时间或降容量 409 |
| POST | /merchant/stores/{storeId}/availability-windows/{windowId}/close | merchantId,expectedVersion,reason 必填 | `{window,status:"CLOSED"}`；占用 409 `SCHEDULE_WINDOW_STATE_NOT_ALLOWED` |
| POST | /merchant/stores/{storeId}/availability-windows/{windowId}/open | merchantId,expectedVersion | `{window,status:"OPEN"}`；重叠 409 `SCHEDULE_WINDOW_OVERLAP` |
| POST | /merchant/stores/{storeId}/availability-windows/batch-close | merchantId,fromDate,toDate(Asia/Shanghai 日历日),reason 必填 | `{storeId,closedWindows:[…],blockedWindows:[{window…,reasonCode}]}`；部分成功允许，全受阻不伪称成功；跨天相交整窗处理（SCHW-D5） |

不提供 DELETE/物理删除（SCHW-D3）；禁周模板三字段（dayOfWeek/repeatWeekly/copyNextWeek）不引入（SSOT §12.1）。

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

`schedule_write_action` 唯一键 `(request_id,target_type,target_id)`；批量关窗按受影响目标逐窗留审计。测试映射：W2-SCHW-001/003/004/005/006/007/008/012 的核心行由 `ScheduleMerchantCommandMySqlTest`（真实 MySQL，含容量守卫与写侧一致性：占用窗守卫、指派保护、全店复核、CAS 冲突、幂等重放）与 `ScheduleWriteDisabledTest` 覆盖；W2-SCHW-002/011 中涉及 C 端 kind 增列与 07/10/11 旧字段兼容的完整联调、W2-SCHW-013 MER 员工录入全链路、真实 MySQL 多连接并发演练属后续切片验收，本批不冒认。

## 7. 阻塞与待裁决（不实现，只登记）

1. **B5 售罄自动/手动规则**：PRD29 与 SSOT §29 未定义窗口容量因预约耗尽后的自动置CLOSED/手工售罄语义；历史记录「B5 售罄宜在 SCH-002 前裁」。当前容量耗尽仅由读侧 min 公式与预约守卫表达。
2. **C 端可选 kind 过滤与 `windowId/kind` 增列**：34号 §1 要求与 SCH-003 hold/swap 双选窗 ID 联合交付并同步 07/10/11；本批未改 C 端契约。
3. **跨日/节假日语义**：仅已批的批量关窗跨天整窗+逐窗恢复；持续性停业开关、节假日模板明确不做的范围外，任何扩展需新裁决。
4. **存量 GENERAL 盘点/迁移（SCHC-4）**：上线前盘点与隔离脚本、回填核对与回滚路径未交付；本批迁移不回填任何旧行。
5. **「每人最多 200 项服务」上限**：未批准，不作为规则实现；容量列仅按 06号 INT 技术边界校验。
6. **排期负责人子账号角色**：随 AUTH/MER 成员绑定交付；V1 写入门禁=主账号 OWNER。
