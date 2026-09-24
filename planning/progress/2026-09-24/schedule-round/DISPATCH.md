# SCH-002实现与SCH-004契约补全：多角色派发

日期：2026-09-24。用户明确授权按“主协调＋后端实现＋契约完善＋独立QA”推进；执行角色模型GPT-6 Sol/xhigh。
共同基线：develop d0b7ff52b47d8bee9db496d9f4d18821235bbeda（PR80经用户另行批准已合入，合并前head 2af943d六项CI成功）。

## 范围与顺序

主协调先同步已批SCH-002的07/27/11；A在冻结提交后实现，B独立完善SCH-004 G1/G2/G3候选，C独立验证。已批准业务规则不重审；重大新增契约须先形成可审阅CCR。默认关闭、无生产迁移，不实现订单/预约锁位/员工CRUD/排期写命令或页面。

| 角色 | 分支/worktree | 唯一写入范围 |
|---|---|---|
| 主协调 | codex/sch002-integration-20260924 / wt-sch002-integration | 权威契约07/27/11及必要10号阶段说明；主台账/派发/最终状态；统一集成、验证、PR。不与A同时改生产代码。 |
| A 后端实现 | codex/sch002-capacity-worker-20260924 / wt-sch002-capacity | MER第六查询的api/biz及本域Mapper/单测；SCH真实人员provider/本域Mapper/单测；boot ScheduleQueryConfiguration，既有ScheduleAvailabilityHttpTest/ScheduleQueryDisabledTest。 |
| B 契约 | codex/sch004-contract-completion-20260924 / wt-sch004-contract | 新schedule-write-completion-proposal.md与planning/issues/wave-3/SCH-004-contract/**；不改权威API/Schema，不写业务实现。 |
| C QA | codex/sch002-qa-20260924 / wt-sch002-qa | 新ScheduleCapacityHttpTest.java及planning/issues/wave-3/SCH-002-qa/**；不改A的现有测试/生产文件。 |

MER-biz第六查询为SCH-002所需的MER事实关联切片，沿用MER自有模块所有权，由A在本次明确授权范围实现；不把原Catalog仅列merchant-api误解为允许SCH越域读员工表。对MER-biz的允许写入仅限该查询，员工管理写操作仍不在范围。最终共享文件交接记录各commit。

## 验收要求

员工原始未知状态/无效布尔/命中store却商家归属损坏503；不存在404；正常空集合0；完整排班并集、相邻与空档、去重、跨店排除；同SCH快照；原SCH-001与默认关闭回归；真实MySQL/Redis HTTP集成与架构检查。

SQL播种员工/能力/排班仅证明查询链，不声称人员录入/预约并发/真机完成。SCH-004待G1/G2/G3重大契约批准，不因本派发获准实现。所有新PR先draft，合并需单独授权；不push main/develop。
