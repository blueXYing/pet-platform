# 排期写入方契约提案 v0.2（SCH-004）

状态：REVIEWED_WITH_GAPS（业务方向已批准，关键保护契约仍须冻结）。
日期：2026-09-24。依据：[联合裁决回执](schedule-review-decisions.md)、SSOT §29；原 v0.1 见 planning/history/schedule-drafts-20260924。
本轮只审阅，不实施排期写代码、数据库迁移或开关启用。

## 1. 已批准范围与行为

| 决定 | 执行方向 |
|---|---|
| SCHW-D1 | 本切片承接服务窗口、员工排班、具体服务能力及工作台读；MER 员工档案另属原模块，M-002 负责页面，SCH-003/订单负责预约占用与指派。不得由 SCH 直接写 merchant_staff。 |
| SCHW-D2 | window_kind=PICKUP/RETURN/GENERAL。IN_STORE 只写 GENERAL，PICKUP_DELIVERY 只写 PICKUP/RETURN。查询可选 kind 过滤，items 增 kind 字段；既有字段保持兼容。最终预约校验 returnStart>=pickupStart+120分钟；开窗不校验全部组合。 |
| SCHW-D3 | 新增/编辑/close/open，不提供物理删除。同 store/service/kind 的 OPEN 窗口不重叠；同 staff 的 AVAILABLE 排班不重叠。相邻半开区间可衔接；重开同样检查重叠。 |
| SCHW-D4 | TEMP_LOCKED/CONFIRMED 占用时禁止关闭、降容量、改变时段；提高容量可放行。修改服务目标/kind 不能绕过守卫。旧窗口和新窗口都须按共同并发协议核验。 |
| SCHW-D5 | 日期范围批量关窗允许部分成功，closedWindows 与 blockedWindows 分别返回。界面明确“部分关闭、仍有未关闭时段”；全受阻不能声称关闭成功。部分相交的跨天窗整段处理；恢复逐窗 open。本轮没有持续性停业标志，也不禁止未来另行开窗。 |
| SCHW-D6 | 员工排班 AVAILABLE/CLOSED，归属+在职+主账号准入；**撤回 v0.1 无占用守卫**。减少人员可用性的变更须保护已有容量和已指派订单，见 §3。没有事实源时不得默认无占用。 |
| SCHW-D7 | 员工能力按具体服务项全量替换，类目只分组。必须校验集合版本，过期编辑冲突并重读，禁止 last-write-wins。撤销能力纳入 §3 的保护。200项上限尚未批准，不作为本轮定稿规则。 |
| SCHW-D8 | 运营只读监管延后到 SCH-003 有锁定/占用事实后交付，归属不取消；运营始终不得代商家修改排期。 |
| SCHW-D9 | 状态变更留操作人、时间、动作；关闭、临时停业、减少人员可用性的操作必填 reason。独立 append-only 审计与业务同事务，不以幂等表替代审计。 |
| SCHW-D10 | 保留 SCHEDULE_WINDOW_OVERLAP、SCHEDULE_WINDOW_STATE_NOT_ALLOWED 两个新增 409 码方向，其余错误映射沿用已有约定；集合过期用 COMMON_CONFLICT。 |

## 2. 操作面（契约同步候选）

所有路由 MINIAPP 会话，后端 owner/商家/门店准入与目标归属核验。操作原因、ID、版本、时间统一校验；所有写请求 UUID X-Request-Id；首次创建 201，其余与重放 200。默认开关关闭。

| 操作族 | 请求与回执 |
|---|---|
| 服务窗口 | GET/POST /api/v1/merchant/stores/{storeId}/availability-windows；PUT /{windowId}；POST /{windowId}/close、/open；POST /batch-close。merchantId、serviceId、windowKind、startAt、endAt、configuredCapacity；更新 expectedVersion。身份字段不能借 PUT 转移归属。 |
| 员工排班 | GET/POST /api/v1/merchant/staff/{staffId}/availability-windows；PUT /{windowId}；POST /{windowId}/close、/open。merchantId/storeId/时间；更新 expectedVersion；减少可用性及关闭必填 reason。 |
| 员工能力 | GET/PUT /api/v1/merchant/staff/{staffId}/service-capabilities。merchantId/storeId/serviceIds，回执增加集合 version；PUT 必须带 expectedVersion。去除服务必填 reason；确切版本存储见 G2。 |
| 批量关窗 | merchantId/fromDate/toDate/reason；范围按 Asia/Shanghai 日历日。逐窗业务占用受阻进入 blockedWindows；可关闭进入 closedWindows。幂等重放返回原结果。基础设施故障不是业务受阻，不得伪装正常部分成功。 |
| C端查询 | kind 可选过滤 PICKUP/RETURN，items 携带窗口类型；不得把 GENERAL 自动当成两个方向同时复用。 |
| 审计 | 成功业务动作同事务写 schedule_write_action，原因随请求保存；失败不得留伪成功审计。批量按目标留明细，唯一键包含目标类型，避免不同对象 ID 意外碰撞。 |

幂等和 CAS 必须与既有 23 号规则一致，身份/权限重验在回放前执行。准确请求形状、Schema、OpenAPI 待 §3 阻塞解除后统一冻结，不能把本表当作已实现接口。

## 3. 审阅发现：实现前必须解决的契约缺口

### G1 人员变更、指派保护和并发（阻塞减少可用性的操作）

已批准的是“保护已有预约和已指派订单”，尚未批准精确算法。v0.1 单凭排班行不关联 reservation 就放行，不能证明安全。
需要 SCH-003/订单域提供权威事实及可共同执行的锁协议：
- 关闭/缩短/移动排班、撤销服务能力，核验受影响时间段、所有受影响服务及已指派订单；
- 不能仅比较整窗总人数与总订单数，必须覆盖分钟边界与跨服务共享同一员工的约束；
- 已指派订单不能因人数仍够就允许撤销被指派人的能力/排班；
- 读取失败或事实源缺席，相关动作失败关闭，不能默认为零占用；
- 与新 hold、确认、指派及其他人员变更竞态必须共同序列化，避免检查后插入预约。
本轮只登记边界，不编造新内部查询、不查询其他域 Repository、不放宽已有员工 disable 门禁。正向安全操作可独立准备；正式实施需对应契约。

### G2 能力集合并发版本（阻塞能力 PUT 定稿）

草案无 version 却 last-write-wins 已被否决。推荐工程候选：SCH 自有 staff capability 集合头记录，含 storeId/staffId/version；GET 返回版本，PUT expectedVersion 在同事务 CAS+替换+审计，首次空集合也有确定版本。不得借用 merchant_staff.version 或只用 requestId 代替版本。
此方案需新的 Schema/内部/API 形状，尚未作为用户已批字段；应形成后续 CCR 补充并冻结后实现。无批准的 200 项业务上限；传输大小边界按通用请求资源约束另定，不悄悄改成产品限制。

### G3 window_kind 与占用匹配（阻塞占用守卫定稿）

v0.1 仅写“按 store/service 时间重叠”，无法证明 PICKUP/RETURN 各匹配正确的 reservation 事实，也不能保护修改 kind 后的旧占用。需与 SCH-003 的 pickup/return 字段和锁定口径统一。
存量 GENERAL 回填只能解释旧数据，不能证明 PICKUP_DELIVERY 旧窗已具备双时段语义；上线前盘点并显式处置，不能无差别复制成两组真实可约窗。

### G4 同步权威面与分工

- 业务裁决已补 SSOT §29 及 PRD 补充；07/10/11/12/34 和迁移不能先写成“已实现”。
- SCH-004 在 ISSUE_CATALOG 原无登记，根 Work 本轮补登为 BLOCKED，解除条件为 G1～G3 及对应契约同步。
- 员工写入归 MER（包括既有 disable 门禁），页面归 M-002；后端三个切片完成不等于真机 E2E。
- 运营监管延后保留追踪，不以当前缺占用数据宣称不需要监管。

## 4. 必须修正的 v0.1 工程细节

1. open 的“无前置”仅能表示无 close 类占用条件；仍需权限、版本、重叠与人员状态检查。
2. 在空区间 SELECT FOR UPDATE 不天然证明防并发重叠；必须定义稳定可锁对象及事务隔离/索引策略，并发创建与 reopen 都要验证。
3. reason 不能只出现在表列而不在 API/命令/审计链中；关闭/减少动作空原因要拒绝。
4. 能力表当前只有 ENABLED 值，移除清单项不能擅自造 DISABLED；使用差集删除关联与独立审计或经 CCR 明确新状态。
5. “serviceIds 去重”与原测试“重复即拒绝”矛盾：技术候选统一拒绝重复项 400，待权威契约定稿时一处定义。
6. C端新增 kind 是兼容增列，不是 JSON 字节完全相同；既有字段值不变，测试应区分这两件事。
7. 批量部分成功应区分业务阻挡和依赖故障，明确事务原子性/幂等回执，不能静默吞故障继续报成功。

## 5. 验收与实施顺序

W2-SCHW-001～013 按更新后的 TEST-PLAN 解释，增加人员保护、能力 CAS、原因必填、跨服务/双方向和竞态反例。所有排期新业务测试本轮 NOT_EXECUTED。

先冻结 G1～G3 → 同步 Schema/API/OpenAPI/错误码 → 实现与测试 → MER 员工+SCH-002+SCH-004+M-002 联调 → SCH-003/TX-001 完成预约权威链。生产 ID/密钥/迁移/真机门禁独立保留。
