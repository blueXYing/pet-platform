# MER-001 S2 基础查询阶段派发

2026-09-17，用户授权开始任务，并允许按复杂度选择5.6 Luna极高或5.6 Sol中等的子代理。基线develop `0ade8bc`（PR50已合入）；合并后CI 35198466155已核实成功。

独立工作区：`C:/Users/Administrator/Desktop/wt-mer001-foundation`；分支：`codex/mer001-foundation-20260917`。本Issue一个共享工作树，各子代理只写明确分配的不同路径，由根任务统一提交/审查/PR，无子代理自行提交或合并。

## 任务与文件唯一所有权

| 执行者 | 模型 | 唯一写入范围 | 交付 |
|---|---|---|---|
| 根任务 | 当前模型 | POM、.github/workflows/ci.yml、根/进度台账、本目录主交接与整合证据 | 环境、依赖、CI、独立审查、整合测试、PR |
| merchant_backend | gpt-5.6-sol / medium | pet-merchant-api/src/main、pet-merchant-biz/src/main | 已批三查询/DTO及MyBatis XML只读基础；USER-owner归属 |
| merchant_dependencies | gpt-5.6-sol / medium | planning/ccr/CCR-W2-API-001/merchant-s2-dependency-handoff.md | 剩余申请/成员/人员停用依赖的精确交接方案，不能私自升为权威Contract |
| merchant_qa | gpt-5.6-luna / xhigh | 两个merchant模块src/test、本目录QA-HANDOFF.md | 独立API/真实MySQL正反例、准确证据边界 |

公共源文件交接经根任务协调；生产代码与测试协商类签名，不同时修改同一文件。每个子代理完成后释放范围。没有另启用户拥有的长期任务或服务/交易/前端实现。

## 本轮范围

先交付Schema06已有商家/门店/服务人员的实际数据库查询、严格ID/状态、主账号归属隔离与脱敏。资格公式使用已批五字段DTO；申请/审核真实表与Adapter未具备，不能从ACTIVE/空值推断APPROVED/SIGNED。允许内部显式事实读取端口做隔离策略测试，默认未装配时503，不把测试事实注入表述为生产资格链路完成。

本轮不新增业务写入/同意协议HTTP、不实现员工停用/离职/软删除、不创造成员关系，不改生产装配或启用迁移。协议/成员新表与完整申请事实仍按下一切片交接；不是为了凑建表交付而先造无权威来源的审核表。本轮使用现有权威Schema06在随机测试库初始化，未做生产DDL。

验收：严格来源与授权；不存在/越权防枚举；大ID和坐标/版本字符串；未知事实或来源失败关闭；资格替身与真实MySQL分层；有意义的事务一致性测试；架构/已有契约回归和完整CI。W2-MER-003/完整HTTP/真实认证/订单E2E不因该组件通过而完成。

## 测试环境

Java21：`C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`，Maven3.9.12。
本轮专用MySQL8.4.9：127.0.0.1:33450，独立datadir `D:/Temp/mer001-mysql-c147ce725a554b0ab6b699d2e28fe75b`；只create/drop成功创建的随机测试库，不接管现有MySQL84服务或读取它的凭据。
本轮专用Redis7.4：容器mer001-s2-redis-20260917，仅127.0.0.1:16381，RDB/AOF关闭；既有容器/配置保持原样。通过显式Docker desktop-linux context连接，不沿用指向其他隧道的DOCKER_HOST。

新MER MySQL环境变量：MER001_MYSQL_URL/USER/PASSWORD。CI复用既有隔离MySQL service，新增同名变量，不放宽原测试门禁。测试结束由根任务检查残留随机库并关闭本轮自建服务，保留原始证据。
