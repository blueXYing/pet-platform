# MER-001 S2 商家基础查询交接

2026-09-17，基线develop `0ade8bc`（PR50，合并后六项CI成功），分支 `codex/mer001-foundation-20260917`。
本轮为用户已授权的MER-001基础实现切片；后端与依赖复审用Sol medium，QA用Luna xhigh，根任务负责整合。文件所有权和隔离环境见[DISPATCH](DISPATCH.md)。

## 已交付行为

- `MerchantQueryApi.getStore/getStaff`：MyBatis XML读取Schema06已有merchant/store/staff，门店联合所属商家、人员联合所属门店和商家，实际owner必须匹配可信USER查询主体。
- 非USER显式拒绝，不给SYSTEM/运营/子账号隐式全权；不存在/越权/错配资源均返回统一404语义。不将服务人员phone推断为登录身份。
- 公共ID codec保持正Long十进制String，API的版本和坐标为String；DTO不可变，返回脱敏电话。员工手机号坏事实、未知状态、非法版本/坐标、非0/1服务标记和INACTIVE+启用矛盾均失败关闭。
- 每次查询在独立只读REPEATABLE_READ事务执行。测试以独立连接更新已有门店状态，证明同DataSource reader在一次查询中仍观察同一快照；新事务能读到更新值。
- 已批五字段新单资格策略可在明确事实输入下计算；四态签约枚举保留，只有SIGNED允许，UNKNOWN/缺失/异常均503。事实reader错误只返回固定503，不泄露其内部code/message，归属404仍在reader执行前处理。

**资格真实来源未接通**：默认DataSource构造器的`checkOrderEligibility`明确503；内部`MerchantEligibilityFactsReader`是应用端口，测试注入的APPROVED/SIGNED只证明策略分支及事务参与，不能宣称生产审核/电子协议来源已完成。没有虚构申请表，没有使用ACTIVE/provider_merchant_no作为签约证明。

## 组件与调用边界

api模块新增既有三方法接口、三query和三DTO，无Spring/MyBatis类型；biz模块为API实现、应用校验/策略、内部事实端口、持久化store/mapper/entity及单个XML。

可信AUTH适配器构造QueryContext是后续接入前提；本组件不能独立证明外部任意Java调用者所给的Context来自有效会话。本轮没有Controller、@Bean/@Component或pet-boot生产装配，因此没有把HTTP用户参数暴露成身份。当前只交付USER-owner有限读取；ORDER/SYSTEM消费要补可信调用者和资源范围协议，不能简单绕过owner。

SQL全部为本域只读XML，无写操作/ID生成/幂等持久化变更；无需以requestId包装只读查询。公开三查询/DTO按已批27号实现，没有更改Schema/API/Event/Scheduler权威规则。内部事实reader不得被其他biz直接依赖。

## 验证与评审

QA最终定向验证：9项真实MySQL领域测试+1项Jackson序列化测试，10/10、0失败/错误/跳过；详见[QA-HANDOFF](QA-HANDOFF.md)。原有91项离线契约回归与13项架构工具回归通过。

根审查与Sol独立复审发现并修正：7位短号不能按3+4暴露完整原号；TinyInt异常不能转为true；保留签约枚举需区分明确不满足与未知；事实源ApiException不能透传4xx和消息；离职且可服务/非法员工手机号不能作为有效DTO返回。最终独立复审无剩余阻断；不把该结论扩展到未实现业务。

根全量`mvn -B -f backend/pom.xml clean verify`返回0、BUILD SUCCESS：41个reactor模块（另含父项目）全部成功，36套件/271项JUnit，0失败/错误/跳过，其中22项ArchUnit、10项商家新增测试。计数与源码SHA-256见[validation.json](validation.json)；远端CI另以PR实际head检查为准。

本地运行过程：首轮全量执行后已生成36套件/271测试、0失败/错误/跳过，但Windows PowerShell的`ErrorActionPreference=Stop`将原生stderr的SLF4J无provider警告提升为命令错误，外层返回1且构建日志截断，不能据此声称clean verify通过。根修正了仓库外运行脚本对native stderr的处理，再完整执行clean verify；未改业务实现、断言或测试门禁。

环境收尾：全量验证后确认本轮MySQL实例无非系统残留数据库；校验datadir/端口后关闭33450测试实例。核对容器ID后停止并移除本轮mer001-s2-redis-20260917；原MySQL84服务及plat006-auth-redis保持运行。测试datadir与原始日志留本地供排查，未递归删除用户文件。

## 遗留范围与下一步

完整申请材料/审核来源、协议三表DDL及发布/签署、成员绑定、主账号核销staff映射、员工停用在途并发守卫、HTTP/会话集成、下线Outbox、实际迁移与生产发号门禁均未因此完成。W2-MER-003、完整ORD009/010、真实工作台准入和前端验收不报PASS。

下一切片按[依赖交接](../../../ccr/CCR-W2-API-001/merchant-s2-dependency-handoff.md)落真实申请/审核来源及已批协议存储，不重复询问已确认的技术契约、不强制重签及下线存量规则。整项MER-001保持IN_PROGRESS；本轮实现PR不自动合并。
