# PLAT-002 S2 Hutool组件交接

状态：**COMPONENT_REVIEW_CANDIDATE / PRODUCTION_ENABLEMENT_BLOCKED**。完整PLAT-002/004非DONE，CCR非RESOLVED。本阶段承接已接受e81c6bc适配包、5fb3eed批准回执及PR13合并6a559bc；当前实现分支codex/plat-002-hutool-component，原9dd6工作区。根通知PR15已合并后已快进集成develop **ab351ee836b323afe2f5f84fef05530db612f47d**，保留AUTH/取消MFA与新契约测试，不沿用旧16操作作为当前全量结论。最终head/PR/CI以PR正文回执为准，本阶段新PR未获合并授权。

## 一页阅读指南

**本次交付可注入的真实Hutool＋MySQL节点协调组件，生产自动启用仍受限。** 生成算法来自已选5.8.47；SDK输出只有通过授权/时间/单调性检查后才交业务。没有把测试替身注册成生产提供器，也没有启动生产Worker。

| 分工 | 你要知道的内容 |
|---|---|
| 我负责 | 代码、技术节点表候选、单飞CAS与真实MySQL/进程测试，复审、固定提交/CI和明确范围 |
| 你只审批 | 本阶段PR合并；如出现真正超出已批方案的新CCR再单列，不重复Hutool/适配包/是否开始 |
| 必读 | 本页“通过证据”“生产门禁”；需要技术细节时读[README](README.md) State and budget与SQL25 |
| 代码范围 | pet-id-core六个main类；root POM只注册模块；common/task-core/boot源码不变 |
| 下一步 | 根Work按证据安排组件合并及后续生产宿主/迁移/装配阶段；缺生产退出证据不能以Boolean true放行 |

## 1. 实现与Schema映射

| 已接受内容 | 本阶段落点 |
|---|---|
| 5.8.47、原epoch、10bit拆5+5、false/0/0 | HutoolSnowflakeIdProvider私有唯一SDK实例；SnowflakeProviderSettings固定初值，仅node显式配置 |
| 原S1接口/转换/业务Clock不改 | 实现SnowflakeIdGenerator；内部生成Long，API/JSON/TS仍String；无Hutool/Future业务DTO |
| 永久H/授权区间/owner/fence | JdbcSnowflakeNodeStore与SnowflakeNodeGrant，先退出证据→锁后重核→新语句NOW，UTC/READ_COMMITTED/独立事务 |
| 1秒隔离/唯一发布结果/晚结果丢弃 | SingleFlightLane唯一工作线程＋独立控制线程，model/结果同CAS；无SDK队列/线程补建；close不杀SDK |
| 宿主证据默认拒绝 | PreviousJvmExitVerifier.rejecting；真实生产实现未提供，测试证据绑定受控子进程 |
| WARMING不烧H | SDK未调用，确认grant以状态提交保留，本次返回not-ready；不重建SDK或重复预留 |
| 技术表候选 | [25号Schema](../../docs/03-database/25-Snowflake-Worker-Schema-v0.1.sql)只该一表，node天然槽位PK，默认禁用、完整范围/NULL配对约束，无预启用节点 |

25号SQL只在本阶段新建隔离测试库执行，**没有放入pet-boot默认Flyway目录**，没有生产初始化/自动迁移。CHECK不证明跨次H/fence单增，应用锁/CAS路径与部署权限/恢复流程共同保证。初始化不假设生产空库，物理安装顺序与生产Flyway编号另由后续Owner处理。

发号不可用/未知不等于业务成功，三端不得造业务ID、伪造成功或自动换requestId重投；金额/Context/当前授权与业务时间规则不变。

## 2. Owner、环境与复现

Backend Core唯一写：root backend/pom.xml模块/版本管理、pet-id-core/pom.xml与6个main、SQL25、23号仅实现映射、对应CCR实施回执、模块README/HANDOFF/evidence。QA唯一写新模块src/test与CI backend追加3个PLAT002_ID_MYSQL_* env；复用原MySQL8.4 service，不改触发/其他job/门禁。Transaction与三端只读复审。common源码、task-core、boot/Flyway、AUTH/前端、07/10/11/12、SSOT/PRD、06/13/Event/Scheduler和根台账没有本阶段修改。

本地独立MySQL8.4.9位于D:/Temp/plat002-id-mysql-9dd6/data，仅127.0.0.1:33442；未接管原MySQL84服务或AUTH目录。mysql服务父/子进程有各自PID，按datadir/端口核验，不把初始化或包装进程PID当唯一服务证据。QA每次只操作自己创建成功的随机库；父任务负责该独立服务生命周期。Java21.0.11与Maven3.9.12用于最终完整验证。

最终fixture临时库残留为0；核对@@datadir后仅关闭本次33442实例，父/子进程均实际退出，目录为复现保留，见[环境回执](evidence/environment.json)。没有尝试删除/接管AUTH被拒绝的目录。日志归档仅统一LF/行尾空白，不改测试结论。

复现变量与命令见README。现有CI的PLAT004/新增PLAT002使用同一隔离服务、不同随机测试库，生产JAR不得包含test support/probe或反射测试代码。最终源码hash、schema hash、测试计数/日志、jar清单和CI链接需固定对应实际提交。

## 3. 通过证据

最终Java21.0.11 / Maven3.9.12完整clean verify：**42 reactor项目成功，154 JUnit（common74 + id-core36 + task-core22 + architecture22），零失败、错误、跳过**；原13 Python架构负例通过。[最终Maven日志](evidence/maven-final-verify.txt)、[分模块计数](evidence/junit-summary.json)、[源码及Schema SHA256](evidence/source-hashes.json)可以核对。初轮149 JUnit日志保留为历史，不替代增加覆盖后的最终154结果。

[当前Contract Smoke](evidence/contract-smoke.txt)实际52操作/37写（legacy16/13/4创建、AUTH36）、630解析引用/118 String ID；[离线回归](evidence/contract-tests.txt)80测试通过，保留AUTH/无MFA新基线。无真实HTTP/认证业务完成声明。

| 新组件验证 | 实证与边界 |
|---|---|
| SDK/候选 | 同版全部1024 node实际调用与公开解码/epoch核验；provider正常调用与并发；测试反射注入回拨、候选异常/OS域合成边界验证。实际运行jar SHA256与已选5.8.47一致，见[sdk-artifact](evidence/sdk-artifact.json) |
| 非阻塞仲裁 | 单飞成功/超时/关闭、30轮候选与close竞态、迟到Proposal不更新模型；真实SDK monitor被测试线程持有时caller到预算失败、SDK线程未假停止且不补建。无界action是控制层替身，不是SDK冻钟 |
| 真实MySQL | 两连接争node、host验证期间行变更拒绝、锁等待后新语句判断过期、默认拒绝/禁用/错误fence、14组CHECK+PK反例、H窗口越界/MAX_AHEAD拒绝；接近尾部真实扩窗保留L/fence且H=U，实际commit后丢ACK仍保存H但不返回新授权 |
| 独立JVM | 最终日志oldPid16924/start2026-09-14T09:35:41.409Z/incarnation568bf6a3-a39e-4187-8c10-9b5f1f1875b9，先拒绝活旧JVM，Runtime.halt(23)后父Process确认退出，freshPid26964获新fence和L>旧H；只在确认退出后做测试专用lease过期注入以强制WARMING，不冒称生产租约已自动缩短 |
| WARMING/恢复 | 新进程反复等待同grant，H/SDK对象不变且SDK lastTimestamp保持-1，到L后才发号。源代码初审发现原未来窗错误终止分支，运行测试前已修正；未伪造一个运行失败记录 |

QA一个定向Maven命令曾因PowerShell未引用含点的-D参数而在测试前失败（0测试），正确引用后定向与最终全量通过；不是组件测试失败。[生产jar清单](evidence/production-jar-contents.txt)仅main及内部类，无test/probe/反射夹具、迁移或自动注册资源。Transaction最终源码复审无阻断，QA完成全部测试并释放文件，三端边界审阅无阻断，交接文字已补明确内部Long/API String及不造ID/换requestId。

测试必须分别报告：真实Hutool配置/单实例并发、测试范围反射回拨注入；单飞阻塞动作控制层；真实MySQL竞争/锁后时钟/ACK丢失；真实独立JVM halt/退出确认/新区间恢复。反射注入不是改主机时间，阻塞候选不是真SDK冻结，SDK源码私有等待限制不因测试绿灯消失。

## 4. 生产启用门禁

- 真实宿主如何绑定node/旧incarnation/fence与PID+启动时刻或容器身份，并确认JVM确已退出，尚未接入。默认拒绝，不用lease过期、Future取消、线程interrupt证明退出。
- 单写数据库谱系、确认H不丢不回退、持久flush/binlog及恢复演练、生产node分配/存量初始化证据未完成；不能从备份低H或业务max(id)盲恢复。
- 没有boot自动Bean/配置、默认Flyway迁移、生产告警路由/宿主重启控制；终态诊断日志不等完整运维闭环。组件每实例独占一个SDK，生产每JVM单例由后续boot/宿主装配保证，本切片未宣称全局JVM实例注册已部署。
- PLAT004自身producer、DEAD对账/告警等原缺口仍在；通用业务幂等物理表/旧requestId迁移、真实AUTH/Provider/业务E2E不在本切片。

这些是生产启用条件，不阻断已授权的组件验证，也不新增产品裁决。真正更改1秒语义、同JVM恢复/补SDK线程、放松退出确认或H恢复要求才须回CCR，本阶段没有这样做。
