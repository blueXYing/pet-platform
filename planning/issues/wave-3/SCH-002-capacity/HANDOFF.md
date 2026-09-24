# SCH-002 真实人员容量查询交接

日期：2026-09-24。基线develop d0b7ff5（PR80经用户批准合入）。用户授权GPT-6 Sol/xhigh多角色推进。

## 实现及边界

- MER第六查询：同店原始员工先校验状态/布尔/归属，再筛ACTIVE且enabled=1；返回不可变、去重、数值序String ID，无个人资料，不授予操作权限。
- SCH提供器：员工名单∩目标服务ENABLED能力∩同店AVAILABLE排班完整半开并集；相邻可拼，空档不可跨，重复不多计；正常空事实0、损坏/故障503、明确不存在404投影。
- REQUIRED加入已有SCH RR事务，窗口/占用/能力/排班共享快照；MER独立RR。独立模块调用可自建RR。读结果不是预约授权租约。
- boot默认关；HTTP登录门控及原六字段不变，无新公开路由/Schema/Event。OpenAPI仅可用性实现状态更新。
- 员工/能力/排班/窗口/占用为SQL种子；入驻审核签约发布经真实HTTP、微信入口与材料依原fixture替身。不是商家维护页面或完整预约E2E。
- 跨服务共享员工hold防超卖、员工CRUD、SCH004写入和双向claim均为后续，不标全链DONE。

## 角色提交

| 角色 | 交付 |
|---|---|
| 主协调 | 5c16305冻结/派发，af76372测试驱动，494689b实现状态/HTTP边界 |
| A后端 | 66208ea实现与测试；e838602测试连接整套回退MER001，适配CI |
| C独立QA | c7519bf真实HTTP矩阵，集成为550576f；通知测试等待4f9230e，集成为faf98b4 |
| B契约 | SCH004四项规范另见PR81，已获用户确认，未实施业务代码 |

## 定向验证

- MER查询模块2/2通过；SCH模块2/2通过（含第二连接修改能力，证明复用已有SCH快照）。
- ScheduleAvailabilityHttpTest、ScheduleQueryDisabledTest各1/1通过。
- 独立ScheduleCapacityHttpTest 1/1通过：真实MER/SCH接线、过滤/覆盖/占用/空事实/503；随机库临时RENAME能力表触发503，finally恢复后200。
- A的ArchitectureRulesTest通过；e2e离线115/115、90操作smoke通过。最终全量Maven与远端CI以实现PR回执为准，不用定向结果冒认全量结果。
- 第一次根全量被空SCH002_MYSQL_URL覆盖阻断，测试正确拒绝无效URL；已移除该环境键，按CI的MER001配置复跑。未跳过测试或放宽校验。原始日志留本地，最终结果及精确HEAD写入PR。

## 独立审阅

QA和主协调核对模块边界、共享快照、默认关/登录与公开契约diff。能力DISABLED最初误认为合法，已按权威资料纠正为未知503。最终OpenAPI只改cGetServiceAvailability，其他operation相对develop净变化为零。

## 遗留

默认关、SQL种子不替代真实维护链；SCH003/ORDER继续预约权威保护，SCH004与MER/M002继续写入/页面。生产ID/迁移/正式环境门禁保持。
