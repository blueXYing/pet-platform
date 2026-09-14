# PLAT-002 公共契约同步与 S1 交接

状态：**CONTRACT_SYNCED_IN_PR / S1_REVIEW_READY / S2_PENDING_DESIGN**。阶段分支`codex/plat-002-contract-s1`，基线develop `c5a184736c58ddfbaf60c5064b48a97bfb7eff3f`；任务`01a09de1-d112-7f20-9beb-f6df6e5fd41d`，原9dd6工作区，旧spec分支保留。最终提交/CI/PR以PR正文固定回执为准。本阶段新PR未获合并授权；完整Issue仍非DONE，CCR非RESOLVED。

## 一页阅读指南

**已交付可运行的 S1 公共接口和转换，不是完整生产发号或数据库幂等服务。** 已接受的两项方案同步到权威文档，原成功/失败措辞统一；公共纯组件能精确处理大ID、金额和字段边界。

| 分工/步骤 | 内容 |
|---|---|
| 我负责 | 契约逐条映射、纯组件/测试、兼容旧Smoke、来源复审、固定提交与最后head CI；不要求你设计技术细节 |
| 你负责 | 看本页和证据后审核本阶段文档/源码PR是否合并；之前两项方案批准和PR10批准无需重复 |
| 第一步必看 | `docs/04-api/23-公共接口与幂等契约补充-v0.1.md` §1～3（公共接口）及§9（S1/S2边界） |
| 第二步必看 | 本文“通过证据”“下游可以做什么”；需要了解断网/重复提交时看23号§5～7 |
| 源码备查 | `backend/pet-common/src/main/java/com/petplatform/common/`：3接口、2纯codec、PublicContractChecks；测试在同模块src/test |
| 下一步 | 根Work收固定提交和Owner释放后按依赖决定PLAT-004独立测试阶段；本任务不自行派发。生产S2仍需具体独占/高水位/持久化设计审查 |

## 1. 批准与契约映射

范围严格承接已接受`a9856c14`方案及`3c7b529`批准回执，PR10合并只带入规范；根Work的新授权允许本次权威同步与S1代码。未定的物理存储/fencing/表/迁移/Provider映射仍未定，没有选新服务。

| 已接受条款 | 本阶段权威落点 | 源码/验证与限制 |
|---|---|---|
| 公共Snowflake ID/String | 23 §1、07 §2、10 §2.5、11 PublicId及实际ID引用 | SnowflakeIdGenerator仅接口；DecimalPublicIdCodec精确ASCII/Long边界；生产唯一性未测 |
| Clock/时区/毫秒 | 23 §2、07 §2、10 §2.6、11 date-time精度注记 | java.time.Clock/fixed例子和纯毫秒检查；不是DB NOW/lease时钟验证 |
| 金额/无舍入 | 23 §2、10 §2.7、11 DecimalAmount输入/Output输出 | parse严格词法/负零；format精确补零去尾零；既有业务非负限制保留 |
| 五字段Context/UUID | 23 §3、07 §2/21、10 §2.3、11 RequestId | Context原record不变，字段检查无身份认证；内部512字节不当终端UUID |
| scope/canonical版本 | 23 §4、07 §21、10 §2.3 | 权威规范已同步，未实现公共参数摘要/持久化，字段用途/可信身份归原Owner |
| 首次成功/失败绑定/同参重试/异参 | 23 §5、07 §21、10 §2.3、11创建200/既有码、12 | 首次处理措辞已明确成功；没有实现事务组件或请求HTTP |
| 当前权限/保留/并发/未知 | 23 §5/6、07/10/12 | 回执当前授权及不自动删事实，真实AUTH/DB/归档未实现 |
| Provider同步/旧Schema兼容 | 23 §7/8、07/10引用 | createPayment不替受理，五处旧全局unique清单保留；无Schema/Event/Scheduler改动 |
| 05技术基线 | §3仅增加23号补充引用 | 原业务规则不变 |

## 2. 逐文件 Owner 与释放

Backend Core唯一写以下文件；未列业务、root pom、boot、前端、SSOT/PRD/Schema/Event/Scheduler/CI均无权修改。根Work独占WORK_STATE/Catalog/Issue状态。

- docs：05技术基线（仅引用）；07内部API、10 HTTP、11 OpenAPI、12错误码（既有码说明）、新增23公共补充。
- pet-common：pom.xml（仅JUnit/SnakeYAML test依赖）；main下SnowflakeIdGenerator、PublicIdCodec、DecimalPublicIdCodec、MoneyCodec、FixedDecimalMoneyCodec、PublicContractChecks。
- pet-common test：PublicIdCodecTest、MoneyCodecTest、PublicContractChecksTest、S1ContractMappingTest、support/SequenceIdStub。没有src/main测试Bean或固定ID提供器。
- planning：CCR主文、原4附属文件仅当前状态/历史映射，本交接与s1-evidence证据。
- **QA独占追加授权**：e2e/contract_smoke.py、e2e/test_contract_smoke.py。先真实复现Non-string ID: storeId，原4负例不能证明新ref正确；新增本地ref/allOf解析、断引用/循环/忽略siblings/非String/nullable等检查，不绕过ID门禁。其余e2e不动。

Transaction与C/M/Admin只读复审，未写Contract/代码。QA除上述2文件外只读pet-common。最终固定提交后各Writer释放；后续更改需根Work按确切文件重新交接，不自动并写。

## 3. S1 能力与下游边界

公共jar包含：`SnowflakeIdGenerator.nextId()`、`PublicIdCodec.toApi/fromApi`、`MoneyCodec.parse/format`三个接口；两个显式无状态codec；`PublicContractChecks`提供requestId/终端UUID/Context必填requestId/OffsetDateTime毫秒检查。没有生产Bean、Snowflake算法、DB Repository、HTTP Adapter、授权或trace签发。

`SequenceIdStub`只在src/test：有限预设正Long序列，耗尽失败，不支持生产发号；没有打入生产jar，也不发布为运行时依赖。PLAT-004可依据该例子在自身测试scope注入明确替身，不能复制成生产ID算法；跨模块需要测试工件时先由根Work登记交接，不给生产依赖挂测试类。

| 下游阶段 | 能做 | 不能声称 |
|---|---|---|
| 根Work正式派发后的PLAT-004独立测试 | 编译对接固定Snowflake接口/Clock；以显式替身验证到期/确定requestId/Handler控制流；按其既定Schema开展获授权真实MySQL claim/lease测试 | 本任务没有启动这些测试，也没有解锁未授权内容 |
| 完整生产Worker | 必须接真实S2 ID/Clock装配、worker独占/高水位，完成多Worker/重启恢复证据 | 空接口、有限序列、fixed Clock都不能替代生产提供器验收 |
| 公共幂等 | 后续依据23条款落实物理表/事务适配/旧key兼容及真实故障测试 | 不能以String转换、文档Smoke或架构绿灯报幂等数据库成功 |

## 4. 通过证据与范围

本地Java21.0.5 / Maven3.9.12，完整`mvn -B -f backend/pom.xml clean verify`，41项目成功，pet-common 74 JUnit + 原架构22 JUnit、13 Python fixture零失败/错误/跳过。首轮日志保留；金额nullable修正后再次完整验证通过，见[最终Maven日志](s1-evidence/maven-final-verify.txt)与[测试计数/源码hash](s1-evidence/local-validation.json)。实际[生产jar清单](s1-evidence/production-jar-contents.txt)无SequenceIdStub或测试class。仅源代码和测试反映S1，不把测试数量当业务交付。

QA新增28个离线Contract回归通过，见[回归日志](s1-evidence/contract-tests.txt)；[Smoke日志](s1-evidence/contract-smoke.txt)为16操作/13写/132解析引用/13 ID属性，引用数包含沿引用解析的路径，不与旧76计数混称新增业务能力。Transaction、QA、C/M/Admin最终审阅均无遗留阻断，Admin nullable问题修复及18项OAS验证证据已复核；QA已释放其两个文件，Backend Core固定提交后释放本阶段文件。

S1新增合同检查读取实际OpenAPI：仍16操作/13写，4创建操作复用已有schema返回200重放；实际ID引用/nullable、UUID完整词法、输入/输出金额模式、时间精度说明/全部本地ref均验证。JSON Schema pattern采用搜索语义测试尾换行；PublicId 19位pattern不能单独证明Long上界，真正数值边界由Codec验证（有超界反例）。x-precision仅文档注记，毫秒拒绝由Java检查，不宣称OAS自动执行。

独立`openapi-schema-validator==0.6.3`的OAS30Validator对实际2个nullable ID和refundAmount验证15个标量+2个完整payload正反例，另确认旧allOf+nullable拒null；见[oas30-validation.json](s1-evidence/oas30-validation.json)。运行依赖隔离在D:/Temp/plat002-s1-oasdeps，不改变项目运行依赖/CI。规则依据[OAS3.0.3 nullable](https://spec.openapis.org/oas/v3.0.3.html#fixed-fields-24)及[验证器OAS30文档](https://openapi-schema-validator.readthedocs.io/en/latest/validation.html)。这不是实机代码生成器或HTTP请求验证；既有REJECT+refundAmount:null现可靠保留，不新增非退款业务。

ORD-011仅公共大ID串映射，W2-IDEM-001仅S1接口/词法部分；W2-IDEM-002纯金额转换已实测。ORD-013、W2-IDEM-003～005、Snowflake生产并发/回拨/重启、真实DB幂等、AUTH/Provider/业务E2E仍NOT_EXECUTED。最后head六job CI必须独立核验，仅证明S1与原基线/受影响Contract门禁通过，不冒称S2完成。

## 5. 复现与剩余事项

本地：Java21在PATH且JAVA_HOME指向它，运行Maven完整reactor；QA文件运行`python e2e/contract_smoke.py`及`python -m unittest discover -s e2e -p 'test_*.py' -v`。S1ContractMappingTest从仓库向上定位实际docs，不能拷假fixture替代。OAS30独立核验见证据中的版本/实际字段/输入输出，用完整spec作为引用根、validator.evolve(schema=字段schema)逐例验证，不替自写解析器放行null。

归档文本日志仅统一LF与去掉行尾空白，测试输出内容和失败/成功结论未改；源码hash以local-validation.json为准。可点击证据直接核对，远端CI另按最后head记录。

新PR仍待人工合并；源码/规范已有可Review内容不等合入develop已生效。S2所需具体存储/fencing/物理表/迁移/持久化Owner/匿名主体与Provider同步结果映射另待原Owner设计审查；不重复裁决已批准产品，不由此阶段自行新增服务或DDL。
