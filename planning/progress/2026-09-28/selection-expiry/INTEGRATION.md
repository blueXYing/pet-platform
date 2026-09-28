# 选窗与预约到期集成回执

2026-09-28。开发基线为 PR86 head `3e54ed7`，develop 仍为 PR85 合并结果 `fac6b73`。PR86 未合并，本轮未执行任何生产迁移、开关启用或 PR 合并。

## 分工与落地

- SCH 角色（GPT-6 Sol/xhigh）：独立八字段选窗投影，按原窗和 claim 统计、接送 GENERAL 隔离、履约类型和 kind 校验；随后独立审查到期路径。
- ORDER 角色（GPT-6 Sol/xhigh）：创建同事务定时任务、同 guard 的订单关闭与预约过期、分页补投；显式 UTC 时间修复。
- QA 角色：独立真实 MySQL/HTTP/任务验收，与实现角色分离。
- 根角色：Owner 暴露事实 API、SCH 过期命令、任务基础设施计划时间与领取范围、Boot/HTTP 集成、契约和总回归。

## 范围

见 [39 号契约](../../../../docs/04-api/39-Selection-Booking-Expiry-Contract-v0.1.md)。选窗仍是原窗保守投影，最终占位采用完整当前证明。到期仅处理 Owner 明确无支付记录且无券记录的订单，任何未知均保留占用；没有假渠道查询或假优惠券释放。

原子创建新增 durable task；任务重复派发由固定代际命令、CAS、原日志及 EXPIRED 状态复核。SYSTEM SCH 审计保存真实 actor_type，避免伪造用户 actor。当前事务取消证明保证独立 SCH 过期无法借旧取消记录提交。

## 验证记录

- SCH 作者：新增 4 项真实 MySQL 选窗测试通过，SCH 模块合计 26 项通过；无 JDBC 时区参数的投影端点仍为正确 UTC。
- ORDER 作者：11 项真实 MySQL 测试通过，含任务提交、到期协调、回滚、重放和分页补投。
- 时区缺陷：已通过原始 DATE_FORMAT 证据确认并修正 Windows 默认时区造成的八小时偏移；不能只依赖 Java 写入再读回的自洽断言。
- 独立审查：NOOP 坏事实、过期前 claim 完整性、独立释放事务证明、超大版本整数解析边界已修复。无其他待修 P0/P1 报告。
- 独立到期 QA：7/7 通过，覆盖真实 Owner 创建 → durable task runOne → CANCELED/EXPIRED/SUCCEEDED、两种 JVM 时区的原始 UTC 数据、支付/券未知保留、回滚/重放/坏 claim、任务类型隔离与恶意代际字段。
- 独立 HTTP 契约：4/4 通过；MockMvc 使用真实 bearer filter 和选窗服务，Owner 与会话解析使用显式 double，不宣称真实微信登录或页面 E2E。既有真实 HTTP 回归另由标准门禁覆盖。
- 本地首次全量：业务用例通过，出现旧 ServiceWriteHttpTest 的临时库 DROP 五秒清理超时，以及旧 guard 用例将建库/删库错误计入五秒断言。后者已把 DDL 移出计时区间，仍保持原 guard 五秒限制；前者不改超时阈值，停止并发占库后单独复跑。
- 首次 CI：选窗测试错误回退本机端口，已改为与 CI 成组读取 URL/user/password；没有跳过用例。最终全量 CI 和本地复跑结果记录在 [PR87](https://github.com/blueXYing/pet-platform/pull/87) 最新检查与验证说明，失败历史保留可核验。

## 下一阶段

先审阅并按依赖合并 PR86 与本轮变更（仍需用户批准），再推进真实支付意图、渠道状态查询/回调与预约确认的协调；优惠券冻结/释放及备注审核补齐后才接消费者创建 HTTP 和页面。非到期的取消、改期和 SCH004 维护继续沿已批准契约推进。当前开关均默认关闭。
