# SCH-004 排期保护契约四项技术裁决回执（2026-09-24）

来源：用户在审阅[一页四项推荐](schedule-write-completion-proposal.md#5-一页决定摘要本轮仅请批准四项具体技术契约)后明确回复：**“确认四项推荐技术方案”**。本回执将批准范围映射到 [34 号 API 补充](../../../docs/04-api/34-Schedule-Protection-Contract-v0.1.md)和[34 号存储补充](../../../docs/03-database/34-Schedule-Protection-Storage-v0.1.md)。四项为已批准技术契约，不需重问已在 SSOT §29 批准的业务语义。

| 编号 | 本次已批准 | 实施前仍须满足 |
|---|---|---|
| SCHC-1 共同锁与当前事实 | SCH 自有每门店稳定闸门；SCH hold/confirm/swap/release、窗口/排班/能力受保护写、ORDER 指派及 MER 停用等容量相关写方在同一主库 DataSource、同一顶层本地事务中按固定序拿锁。ORDER/MER 经公共 API 提供当前指派、未完成状态、员工资格与版本；不能跨模块 Repository/Mapper。读取失败或证明缺失时，减少可用性的写入失败关闭。 | 完整参与路径、同事务当前读、ORDER 当前指派唯一权威及未完成状态口径须由各 Owner 联合落地；MER disable 原有 `IMPLEMENTATION_BLOCKED` 不因文档获批自动解除。 |
| SCHC-2 能力集合版本 | SCH 独立集合头 BIGINT 版本；合法未编辑员工 GET 空集合版本 `"0"`；首写唯一键原子 CAS，后续同事务 CAS+全量替换+审计；HTTP 版本为十进制 String，过期编辑冲突重读。 | 撤销能力仍受 SCHC-1 和后续容量证明约束；幂等、原因、主账号归属和真实迁移/测试需交付。 |
| SCHC-3 两个所选完整窗 | 按最终 C/商家 PRD，接送分别选择一个 PICKUP 与 RETURN 窗；两个窗口**完整**半开区间各为一条权威占用 claim。hold/swap 技术命令携带两个所选窗口 ID，原窗 ID 与时间留在 SCH 事实中；两个 claim 同事务建立/变化。 | C 端当前可约响应只有六字段，无 windowId/kind；必须由 SCH-004/SCH-003 后续切片协同增列/过滤并同步 07/10/11。现有 SCH-001/002 不能被描述为已供给选窗 ID，也不能直接开启接送预约。 |
| SCHC-4 存量 GENERAL | 上线前盘点历史 GENERAL；到店核验后保留；接送旧 GENERAL 不自动复制成 PICKUP+RETURN 新供给，先隔离新预约，只有可证明恢复的历史占用才迁移。无法恢复时阻断相关发布/减员并逐单处置。 | 不授权自动取消/改变旧订单履约；历史盘点、回填核对、回滚和真实迁移均未做。 |

**没有一并批准**：跨服务共享人员的完整约束求解/暂定人员容量占位算法、接送两个 claim 与最终一个商家指派员工是否必须同人、ORDER 指派保护的完整生命周期状态集合、hold 先于 order 创建与现有 `schedule_reservation.order_id NOT NULL` 的绑定时序、具体索引/可执行迁移脚本、生产启用或 PR 合并。这些由 SCH-003/ORDER/MER 后续独立 CCR 与实现验证处理。当前无法证明保护成立的相关减员和接送 hold 按已批 SSOT §29 失败关闭，不把“失败关闭”重复作为新决策。

本轮规范状态：四项 `ACCEPTED_CONTRACT_NOT_IMPLEMENTED`。34 号文件记录已批逻辑，06/07/10/11 的当前实现和响应在相应实现切片正式增量同步前保持现状。原[联合业务回执](schedule-review-decisions.md)及[写入 v0.2](schedule-write-proposal.md)仍提供产品和 SCH-004 操作范围；若旧草案仍写“待批准”，以本回执对上述四项的后续裁决为准。本文不包含生产代码、Schema 迁移、通过的测试或合并授权。
