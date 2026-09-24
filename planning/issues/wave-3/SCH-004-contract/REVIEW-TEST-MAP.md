# SCH-004 G1–G3 审阅与测试映射

状态：CONTRACT_REVIEW_ONLY，全部用例 **NOT_EXECUTED**。依据：[补齐提案](../../../ccr/CCR-W2-API-001/schedule-write-completion-proposal.md)、[v0.2 写入提案](../../../ccr/CCR-W2-API-001/schedule-write-proposal.md)、SSOT §12/§13/§29。这里列的是冻结条件和未来 MySQL 集成测试，不替代 SCH-003/ORDER/MER 各 Owner 的实现测试。现有 W2-SCHW-001～013 保留，以下为其缺口的具体反例。

| ID / 对应旧测 | 并发或数据布置 | 必须观察到的事实 |
|---|---|---|
| G1-01 / W2-SCHW-006 | X 同店可做服务 A/B，唯一 AVAILABLE 区间 09:00–11:00；A 已有 09:00–10:00 claim；B 在 09:30–10:30 hold | B 不能因“B 服务有 1 人、B 自身占用 0”成功；跨服务共享 X 只算一个可同时服务的人。 |
| G1-02 / W2-SCHW-006 | A 的当前订单指派 X；Y 也能做 A；撤 X 能力或关 X 排班 | 409，ORDER 原指派和 SCH 员工可用事实都不改；Y 存在不替代已指派 X。 |
| G1-03 / W2-SCHW-006 | 一个 GENERAL claim 是 09:00–11:00；X 仅 09:00–10:00 AVAILABLE，Y 仅 10:00–11:00 AVAILABLE | 拒绝/不可证明，不得把每分钟各有一人当该订单全段由一名人员覆盖。 |
| G1-04 / W2-SCHW-006 | 线程 T1 锁闸门并查无占用后准备缩短排班；T2 同店 hold；反向再跑一次 | T2 等 T1 提交后复核被缩短的资格而失败；反向 T1 见已 hold 而失败。不得都成功。 |
| G1-05 / W2-SCHW-006 | 线程 T1 ORDER 指派 X 与 T2 SCH 撤 X 能力竞态；T3 MER disable X | 三方都参加同一门店闸门；最终不出现“当前指派 X 且 X 已失去资格”。MER disable 仍须保持原门禁，未接闸门前不能启用。 |
| G1-06 / W2-SCHW-003 | 两线程在空门店同时首次 create guard、首次开重叠 OPEN 窗 | 唯一 `(storeId)` guard 收敛为一行；两个命令串行并仅一个相交窗口成功。空结果 `FOR UPDATE` 不算通过。 |
| G1-07 / W2-SCHW-006 | Execution 事务在拿 guard 前先建立 RR 旧快照；另一事务先提交新 hold，再放行 Execution | 锁后当前读看到新 hold。若 `REQUIRES_NEW`/另库/缓存读返回旧事实，该装配测试必须失败并阻止启用。 |
| G1-08 / W2-SCHW-008 | 同一订单/服务/原门店两个改期命令争新旧时段；注入数据库 deadlock/锁超时 | 同店串行，异常整笔回滚，同 requestId 同参有界重试；原预约保留，直到新预约与释放一起提交。多资源升序锁另做协议级测试，不创建跨店改期业务用例。 |
| G1-09 / W2-SCHW-008 | 成功提交后丢失 ACK，客户端原 key 重试；再用相同 key 改参数 | 原 key/同参取主库首次回执、不重复写；异参 409 幂等冲突；权限撤销后重放不得泄漏旧回执。 |
| G1-10 / W2-SCHW-005 | 批量关窗 3 个：一个无占用、一个有 TEMP_LOCKED、第三个查询 ORDER/SCH 事实超时 | 整笔故障回滚并 503，不能报一个 closed、一个 blocked、第三个也当 blocked。单纯业务受阻才可进入 blockedWindows。 |
| G1-11 / W2-SCHW-006 | 已过 `lock_expire_at` 但仍为 TEMP_LOCKED；另案已提交 EXPIRED；再有 CONFIRMED | TEMP_LOCKED 和 CONFIRMED 都保护；只有已提交 EXPIRED 的 claim 可不计。释放与减员共闸门。 |
| G1-12 / W2-SCHW-006 | ORDER `pet_order.service_staff_id` 与当前 `order_staff_assignment` 不一致；或 ORDER 缺当前未完成状态 | 503/故障告警并回滚，不能任取一个值或视为未指派。经对账后再执行。 |
| G2-01 / W2-SCHW-007/008 | 新员工无 capability 明细/集合头；两个会话 GET 都得 `serviceIds:[],version:"0"`，随后不同 requestId PUT `expectedVersion:"0"` | 唯一头行，恰一成功进 `version:"1"`；另一 409 并重读，明细不混合。 |
| G2-02 / W2-SCHW-007 | PUT `expectedVersion:"1"` 写空集合，后另一个会话持旧 `"0"` 或 `"1"` 写 | 头不删除，版本单调至 2；旧版本冲突；GET 空集合返回 `version:"2"`。 |
| G2-03 / W2-SCHW-008 | 同 requestId/同参数 PUT 重放、同 key 异参、不同 requestId 同集合 PUT | 重放不再递增；异参幂等 409；独立成功 PUT 即使集合相同也递增一次，保留两次审计事实。 |
| G2-04 / W2-SCHW-007 | 提交重复 serviceId、跨店 serviceId、未知员工；MER 读取失败；撤销已有指派服务能力 | 前三项按契约拒绝且无半明细；读故障 503 而非空集合；撤销进入 G1 守卫。不得出现 200 项隐藏上限。 |
| G2-05 / W2-SCHW-007/008 | 集合头版本设为 `9007199254740993`（2^53+1），GET→浏览器保存 String→PUT expectedVersion→DB CAS→GET | 每步保持相同十进制字节，不经 JSON number/JS Number；CAS 从该版本准确递增 1，同 requestId 重放回原版本。 |
| G3-01 / W2-SCHW-002/004 | C 端选完整 PICKUP 09:00–10:00、RETURN 12:00–13:00，hold 带 `selectedPickupWindowId/selectedReturnWindowId`；改 RETURN 旧窗；改 PICKUP 旧窗 | 生成两条各自覆盖**完整窗口**的 claim；各自只保护对应 claim；被占用旧窗 close/降容/移时段均 409；改 kind/服务目标不能使旧 claim 消失。 |
| G3-02 / W2-SCHW-002/011，ORD-002/003 | `returnStart=pickupStart+119分钟` 与 +120分钟；跨日；服务/门店不匹配 | +119 拒绝且不产生两条孤儿 claim；+120 可在两个合法窗和容量均满足时同时 hold；跨日按实际偏移时间比较。 |
| G3-03 / W2-SCHW-004 | 仅有旧单 `pickup_start_at/return_start_at`，无两个结束时间；假设单主区间重叠旧 GENERAL | 不得臆造 RETURN 结束、按固定 60 分钟或服务 duration 推算；受影响守卫失败关闭，旧 GENERAL 不投射成两组可约窗。 |
| G3-04 / W2-SCHW-002 | 迁移前存量 GENERAL 分 IN_STORE、PICKUP_DELIVERY；分别有无 TEMP_LOCKED/CONFIRMED；多种无法确定选窗历史 | 盘点报告列数量和 ID；到店保留经核对的 GENERAL；接送旧 GENERAL 不自动复制，未知关联隔离，仍有旧占用者不能被关窗/减员写绕过。 |
| G3-05 / W2-SCHW-011 | C 查询 kind=PICKUP、RETURN、无 kind；SCH-001 原 occupiedCount 为单主区间重叠 | kind 过滤只给相应方向；计数以相应 claim 为准；接送无两条权威 claim 时不给 `available=true`。增列兼容按字段语义验收，不要求 JSON 字节不变。 |
| G3-06 / W2-SCHW-002/011 | 客户端给 `pickupStart` 与所选 PICKUP 窗开始不等，或伪造另一个店/服务的 windowId；另试未定义的窗内 10:15–10:30 子区间 | 拒绝且无孤儿 claim；只允许所选原 OPEN 窗的完整起止。若产品将来批准窗内截取，另走契约，不以本次技术字段暗加。 |

## 冻结与实证门禁

1. **本轮契约审阅**：SCH-003、ORDER、MER、SCH-004 和平台幂等 Owner 共同确认每条写路径的事务参与、锁序、当前事实 API、所选整窗 claim 起止来源、当前指派唯一权威与失败映射。容量完整证明/技术占位转 SCH-003 后续独立 CCR；缺该证明或权威事实时，相关减员/接送 hold 继续失败关闭。
2. **Schema/文档同步**：只在批准后由唯一 Writer 更新 07/10/11/12/34、迁移与相应 OpenAPI；迁移必须先盘点历史 GENERAL、验证回填、可回滚，不能只加 `DEFAULT GENERAL`。
3. **真实测试**：MySQL 多连接并发与故障注入证明 G1-04～10，单元/契约测试证明 G1-01～03、G2、G3；检查 ARCH001～005 模块边界及 23 号幂等/授权顺序。用模拟 ORDER/MER API 的纯单元测试不能证明跨域事务装配。
4. **交付口径**：本文件全部 NOT_EXECUTED；SCH-004、SCH-003、MER disable、M-002 和生产开关分别验收，不因本提案通过文档审阅而标为已实现。
