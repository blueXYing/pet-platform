# S2 来源与只读审阅证据

状态：**PROPOSED / PENDING_REVIEW**。本次只编写设计CCR，源码/SQL/环境零改动。后续生产验收仍未执行；CI只用于已有基线回归。

## 1. 输入及授权

- 原方案a9856c14、批准3c7b529；S1公共实现7c484371经PR11合入develop `0cc7d0151cdae3500185957d2a35088d0b31e37f`。已接受布局/epoch/String/金额/Context不再裁决。
- 根任务`01a08e29-e8c2-70d0-94ba-18df3e14d948`明确恢复PLAT002仅S2具体设计；允许CCR主文入口与独立S2文档，禁止实现/DDL/环境改动。9dd6从干净原S1工作区创建`codex/plat-002-s2-id-design`，根dirty不切换/重置；原S1分支保留。
- 根后续告知PR12已批准合并`bcb269c2adc9405e669747d9b3bedfae2bf5ccbd`；fetch核验后本设计分支已快进到该交付基线，原4个未提交草稿保留。仅更新交付基线，不因组件合并重做S1或启动S2代码。

## 2. 仓库依据

| 来源 | 精确内容 |
|---|---|
| [23号公共补充](../../../docs/04-api/23-公共接口与幂等契约补充-v0.1.md) §1/2/9 | 已接受1/41/10/12、epoch2026、1秒预算、纯S1与S2缺口；Clock不替换DB lease |
| [原public-contracts](public-contracts.md) §3/4/6 | worker独占、高水位机制待定，预留上界重启等待，真实provider后再完整Worker |
| [SnowflakeIdGenerator](../../../backend/pet-common/src/main/java/com/petplatform/common/SnowflakeIdGenerator.java) L3～12 | nextId正Long、unsafe失败、无生产provider，非requestId/task_key |
| [05技术基线](../../../docs/02-architecture/05-技术基线-v0.6.md) §1/3 | 模块化单体、boot只装配、MySQL、Long/String及统一时间/业务边界 |
| [13异步Schema](../../../docs/03-database/13-Async-Infra-Schema-v0.1.sql) async_task/attempt | 任务/attempt需要Snowflake；task_key独立，当前无worker高水位表 |
| PLAT004固定c641794119063706d3ab468a90898d3694ed26d8 | 实际只读`C:/Users/Administrator/.codex/worktrees/44dc/宠物平台V1.0/backend/pet-task-core/HANDOFF.md` L26/32～35；README Wiring：专用DataSource/DB UTC与显式provider/Clock；现合并基线可读[HANDOFF](../../../backend/pet-task-core/HANDOFF.md) |
| 同提交JdbcAsyncTaskRepository.java L24～26/47 | 构造要求非null SnowflakeIdGenerator，attemptId生成在claim锁前，无默认生产发号Bean |
| 同提交AsyncTaskWorker.java L36～42、TaskRegistration.java L26～31 | Clock显式注入，Handler context时间；lease/重试仍DB NOW；取消不代表副作用停止 |

44dc是工作区路径，不是commit。新S2设计不修改该任务的task-core/boot/CI；最终PR基线若已含PR12，其真实MySQL组件CI属于旧组件测试，不能冒称S2测试。

## 3. 官方技术事实与本提案推导的区别

- MySQL锁定读及计数器示例说明FOR UPDATE在事务内串行更新同一行；本提案据此设计worker状态行，**区间算法是本CCR新增建议，不是MySQL官方Snowflake实现**。[MySQL 8.4 Locking Reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
- MySQL文档建议事务持久性使用innodb_flush_log_at_trx_commit=1、启binlog时sync_binlog=1，并说明硬件不兑现flush会破坏持久性。本稿要求后续部署验证，不把这些参数当作任意副本零丢失证明，也未改当前配置。[MySQL 8.4 InnoDB参数](https://dev.mysql.com/doc/refman/8.4/en/innodb-parameters.html#sysvar_innodb_flush_log_at_trx_commit)
- Java21 nanoTime仅用于同一JVM内elapsed，起点不等于UTC，比较差值以避免绝对加法溢出；本稿的9秒本地许可是保守工程阈值，不声称nanoTime解决DBfencing或任意暂停即时撤销。[Java21 System.nanoTime](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime())

来源实际查询于2026-09-14；本文没有引用官方材料证明“本实现已经安全运行”。

## 4. 同 Issue 审阅与算术检查

Transaction与QA独立初审共同要求：区分range不相交与lease即时撤销；保护不可回退H；旧ACK不重启旧incarnation；禁止活内存克隆；锁后新语句DB时间；1秒预算与未来窗口上界；0号与41位末端。已纳入设计§2～8和E01～E09。

C/M/Admin初审仅确认String传输、不得从位解码推断业务时间/权限/状态、失败不造ID/换requestId，已纳入E10和设计§1/9。只有Backend Core编辑文档，子代理不写文件、不创建用户任务。

实际用PowerShell64位位运算核对5个编码：100123/17/8→419946369032，110001/17/0→461377703936，123/17/4095→515973119，124/17/0→520163328，最大t/worker/seq→Long.MAX_VALUE；与E02/E07一致。只验证有限算术，不验证并发或数据库。

最终独立复审已完成：

| 岗位 | 结果/修订 |
|---|---|
| Transaction | 条件性区间唯一性推导可进入人工Review，无剩余阻断；补nano差值与每次续租enabled/format/初始化/HUL检查，校正实际claim签名和合并基线，均再次核验关闭 |
| QA | 5个ID/L/U算术独立核对；E04不伪造具体重复反例、E09符合W5000的重叠恢复反例、检查后更新前暂停边界均已修订并复核关闭；E11有符号环绕差值500000001ns核对正确 |
| C/M/Admin | String传输、不可从ID推断业务时间/权限、失败不造ID/换requestId及一页指南无阻断；未深审DB算法、不替代Transaction/QA |

以上只读/算术结论不是人工批准或生产测试通过。本次未触发实现、MySQL灾难演练或运行环境改动。结构/本地链接/差异白名单及原接受正文不变在固定提交前核验；最后head/PR/CI在正文回执，不为转述CI反复改head。
