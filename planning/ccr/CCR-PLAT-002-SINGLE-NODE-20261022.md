# CCR-PLAT-002-SINGLE-NODE-20261022 — 发号器单实例简化（默认装配切换）

- 提出方：用户（产品/项目负责人）2026-10-22 裁决；实施：后端。
- 状态：**APPROVED_AND_IMPLEMENTED**（本 PR 为载体）。
- 关联：PLAT-002、CCR-W2-IDEMP-001、`backend/pet-id-core`、SQL25。

## 1. 背景与现场事实

现网/本地长驻服务使用的 `HutoolSnowflakeIdProvider` 是数据库租约方案：每 2 秒续租、10 秒租约、单飞行道 1 秒预算；任一被接纳操作超过 1 秒即 `failPending` 永久关闭 lane，后续所有 `nextId` 抛 `Provider closed`，且没有自愈。全后端只有一个 `SnowflakeIdGenerator` Bean，所以一个抖动会让所有写服务一起不可用。

2026-10-22 核查当前 18081 服务日志：累计 270 次 `failed closed`（266 次 `OPERATION_TIMEOUT`、4 次 `ACTION_FAILED`），193 次靠测试壳换新节点恢复，86 次换节点也失败。历史记录（2026-10-07 F-4、MER-001-s9、2026-10-19 报告）同样记录了共享 MySQL/并行构建下的同类故障。

当前项目处于初期，只有一台应用实例；数据库租约、fence、宿主退出证明、跨重启高水位和 1 秒 fail-closed 的收益远小于其可用性成本。

## 2. 裁决

1. `pet.auth.admin.enabled=true` 时，`pet-boot` 默认装配新的 `SimpleSnowflakeIdProvider`：Hutool 承担算法，直接使用配置的 `pet.auth.admin.node-id`；不访问 `snowflake_worker_state`，不续租，不进入 fail-closed。
2. `pet.auth.admin.node-id` 默认值从 `-1` 改为 `1`（0..1023 合法）。单实例可直接用；多实例部署必须显式给每个进程配置互异值。
3. `HutoolSnowflakeIdProvider`、`JdbcSnowflakeNodeStore`、`SingleFlightLane`、`PreviousJvmExitVerifier` 保留在 `pet-id-core`，作为未来多实例/强安全模式的组件；默认不再装配，也不删除其测试。
4. E2E、LocalMerchantAcceptanceServer、MS1、M002 等长驻验收服务同步切换到单实例 provider，避免本地验收再被租约抖动打断。

## 3. 不变项

- `SnowflakeIdGenerator` 接口不变；所有业务模块仍注入该接口，无业务代码改动。
- ID 格式不变：2026-01-01 epoch、41/10/12 位、node 低 5 位 + 高 5 位的既有拆分；HTTP/JSON 仍以 String 输出，数据库仍为 BIGINT。
- 无 API/Contract/Event/Schema 变化；SQL25 的 `snowflake_worker_state` 表保留，但单实例默认实现不读写它。
- requestId 幂等、金额、Clock、订单/退款等硬规则不变。

## 4. 取舍与风险

- **放弃的保护**：自动 workerId 分配、跨实例 fencing、跨重启高水位、时钟回拨/租约异常时的强制失败关闭。
- **保留的风险**：如果同一 node-id 被两个进程同时使用，可能生成重复 ID；如果进程重启且系统时钟被回拨到重启前已发号的时间段，也可能重复。单实例部署下，正确配置 node-id + 可靠 NTP/不手工改时钟即可把风险降到很低。
- **回退路径**：未来多实例部署时，重新装配 `HutoolSnowflakeIdProvider`，并实现可信的 `PreviousJvmExitVerifier`；SQL25 和既有测试可直接复用。
- 该取舍只适用于当前单实例阶段；不是对所有部署形态的通用结论。

## 5. 验证与影响

- 新增 `SimpleSnowflakeIdProviderTest`：格式/node 位/单调性/并发唯一性；`pet-id-core`、`pet-boot` 编译通过。
- 运行中的 18081 E2E 服务需重启后生效；重启后不再创建/续租 `snowflake_worker_state` 节点行。
- 本次不修改 `pet-id-core` 的数据库租约组件及其回归测试；其生产启用门禁仍然有效，只是默认装配不再走它。

## 6. 批准记录

用户 2026-10-22 明确：“项目初期只有一台实例，所以不用那么复杂……不要老是因为一个发号安全壳导致我整个业务不可用。”本条作为该裁决的执行记录。
