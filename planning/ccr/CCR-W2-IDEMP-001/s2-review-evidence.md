# Hutool S2 修订来源与审阅回执

**当前状态：SDK_SELECTED / ADAPTER_ACCEPTED / PENDING_CONTRACT_SYNC。** 2026-09-14人工接受新增适配包，批准对象 **e81c6bcaba360a192001167823c30f2afa24a92a**，tree **b49fc1c126fc15b672186c0130da9f444eea2456**。根任务明确确认用户该回复对应协调/候选发布/1秒失效/旧JVM确认终止后恢复及指南初始参数；不再请求SDK/旧两项/本适配包的相同批准。

本次仅5Markdown状态/回执行政同步，以下原审阅、参数与样例结论不变；原ADAPTER_PROPOSED/待审保留为历史。本专用分支无冲突合入develop643f05cd3a9357772bb3029ff97b750afeebb1ca，AUTH/取消MFA文件与新base一致；不编辑根dirty或产品正文。旧CI34822297593受测base bcb269c2仅是历史，新head/新base的真实CI另记PR13正文。

适配接受不等权威Contract同步、DDL/部署或实现验收完成；实际宿主退出确认、DB持久/恢复前提仍需落实，通用业务幂等物理表与旧业务键迁移仍待后续设计。CCR非RESOLVED，PLAT002/004非DONE，PR13合并尚待人工批准。以下为批准前证据快照。

状态：**SDK_SELECTED / ADAPTER_PROPOSED**。本次只修5份S2 CCR Markdown，原两项公共约定与S1代码不变。旧d5acc3a自研方案未获批准，以Git历史保留，不把其只读审阅当当前适配批准。

## 1. 授权与Writer

人工明确“采用Hutool”，根指定cn.hutool:hutool-core:5.8.47并正式交回PLAT002规范Writer。期间为AUTH产品纠正曾临时暂停；AUTH固定并释放后恢复；另一次AUTH行政回执不占新增技术主写，根明确继续。当前只写CCR主文S2入口及s2四附属，AUTH/root产品/代码/pom/DDL/环境不动。

SDK选择已批准，新增节点协调、候选发布、超时失效与真实JVM退出后恢复仍待评审。原PR13 d5acc3a保留历史，本修订更新同PR13，不创建新Issue/用户任务。原分支codex/plat-002-s2-id-design，9dd6工作区，输入develop bcb269c2adc9405e669747d9b3bedfae2bf5ccbd。

## 2. 固定制品/源码核对

只读根报告：C:/Users/Administrator/Desktop/宠物平台V1.0/planning/SNOWFLAKE_SDK_COMPARISON.md。报告中历史“未批准选型”已由最新人工选择覆盖，不改变其有限测试范围。只读目录D:/Temp/pet-snowflake-sdk-review中的jar/sources jar/Snowflake.java/SdkProbe.java/artifact.json/pom，未安装到项目或重跑实验。

本任务独立只读计算jar SHA-256：
7cc076ad4ed9846dc129edcd2a5e4e01b61a9d715bf0d3b9a7fa707d4f637266。
与根artifact.json一致；提取的Snowflake.java文本与5.8.47 sources JAR内cn/hutool/core/lang/Snowflake.java一致。制品来源[Maven Central 5.8.47](https://repo.maven.apache.org/maven2/cn/hutool/hutool-core/5.8.47/)，不是只读浮动分支推断。

| 固定源码行 | 事实与影响 |
|---|---|
| L35 | Serializable不等于可安全克隆/恢复生成器 |
| L89～90 | sequence/lastTimestamp只存在实例内存 |
| L161～168 | 可明确epoch、worker/dataCenter、false/0/0参数 |
| L240～274 | synchronized nextId，内部序列与拼位由SDK承担；timeOffset=0拒已检测回拨 |
| L292～303 | private tilNextMillis在时间相等时循环，无deadline/interrupt检查 |
| L311～312 | private genTime从SystemClock或System.currentTimeMillis取时，业务Clock不可注入 |
| 解码getter/拼位 | 解码时间使用41位掩码，不能单靠getter证明真实OS UTC未溢出 |

根已有实验：Java21.0.11，同一SDK实例8线程100000个正Long唯一、epoch/node映射及String往返；反射注入未来lastTimestamp且timeOffset0拒绝；两个相同node新SDK实例实测可重复。我们仅核源码/制品及读取根实验证据，**没有把它称为本适配器或分布式/重启/截止测试**。root SDK子pom无直接依赖项，未以此保证整个未来依赖树已验证。

## 3. 既有权威与组件来源

- [23号补充](../../../docs/04-api/23-公共接口与幂等契约补充-v0.1.md) §1/2/9：[S1接口](../../../backend/pet-common/src/main/java/com/petplatform/common/SnowflakeIdGenerator.java)、原布局/epoch/String、1秒预算、Clock与S2缺口。
- 已接受a9856c14/批准3c7b529、S1 7c484371/合并0cc7d015；这些没有因为SDK切换重新批准或被弱化。
- PLAT004固定c641794119063706d3ab468a90898d3694ed26d8经PR12合入bcb269c：[HANDOFF](../../../backend/pet-task-core/HANDOFF.md)、[README](../../../backend/pet-task-core/README.md)。JdbcAsyncTaskRepository构造需显式provider，claim(owner,leaseDuration)在task锁前调用nextId；TaskRegistration的Clock仅供Handler context，Task lease仍DB NOW。
- [MySQL锁定读](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)与[持久性参数](https://dev.mysql.com/doc/refman/8.4/en/innodb-parameters.html#sysvar_innodb_flush_log_at_trx_commit)支撑协调实现前提，不证明任何副本零丢失。
- [Java21 nanoTime](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime())支撑同JVM差值预算；不证明不可中断SDK线程能被Future取消。
- [Hutool官方使用说明](https://doc.hutool.cn/pages/IdUtil/)提示单例；精确实现以上述固定版本源码为准。

## 4. 已完成只读输入与修订处理

Transaction/QA已核同版源码，要求：显式false/0/0与节点拆分；删除自写sequence/拼位/等待/回拨自愈；候选返回后校验；不能持SDK或可暂停发布锁堵住timeout；单一非阻塞成功/终态仲裁；最多一在途、无积压/不补线程；迟到SDK/DBACK不得复活；恢复需真实JVM退出；OS UTC检查不能只靠解码掩码。均已纳入当前设计§1～7。

三端只核S1接口/ID String不变、SDK类型不进API、时间/权限读业务字段、错误不造ID/换requestId，不重裁金额/Context/单运营或AUTH产品。本次不让其代替算法安全审阅。

当前文稿与上一版实质差异不是只加一个SDK名称：算法状态和等待循环删除，授权范围从控制生成改为校验候选发布，新增不可取消等待的资源失效成本，取消同JVM恢复旧状态路径。保留H/owner/fence是SDK缺能力且原保证要求的必要协调，是否接受其具体参数仍是新适配包审批。

最终只读复审完成：Transaction与QA均无适配设计阻断；Transaction追加复核了DB/SDK阻塞隔离及精确OS/DB采样不等式，仍无新阻断。三端发现新版遗漏“超时/未知不得造ID、伪造成功或换requestId重投”，已在设计§8/H01补回并再次确认关闭。所有审阅只读，无新源码实验或文件并写。

提交前核对5文件白名单、本地链接/围栏、原已接受正文不变；1024个node的5+5映射仅作整数算术检查，不是SDK运行验证。固定提交后只在PR13正文记录最后head/CI与Owner释放。没有代码/pom/DDL/环境或新实验；SDK_SELECTED不等ADAPTER_APPROVED，CCR非RESOLVED、完整Issue非DONE。
