# MER-001 S3 协议存储与申请审核契约阶段

2026-09-17。用户批示“按照你的建议继续”：先合PR51，再从最新develop推进申请审核与协议同意存储读取。
已核验PR50合并0ade8bc；PR51合并5ee8f75。主目录develop已快进到5ee8f75，用户project.config.json字节哈希前后相同。

本轮工作区 `C:/Users/Administrator/Desktop/wt-mer001-agreement`，分支 `codex/mer001-agreement-20260917`。新PR不自动合并。

| 执行者 | 模型 | 独占文件范围 | 当前交付 |
|---|---|---|---|
| 根任务 | 当前模型 | docs/03-database/28协议DDL、POM、必要CI、根台账和本目录主交接/证据 | 物理存储、环境与事务/幂等复审、整合验证、PR |
| merchant_backend | Sol medium | 两个merchant模块src/main | 已批准协议查询/首次同意的真实MyBatis存储与持久幂等、协议资格读取组合组件 |
| merchant_dependencies | Sol medium | planning/ccr/CCR-W2-API-001/merchant-application-review-proposal.md | 申请/提交/审核/主体去重/材料与审计的具体缺失契约候选，独立复审建议 |
| merchant_qa | Luna xhigh | 两个merchant模块src/test、本目录QA-HANDOFF.md | 实际SQL28/MySQL并发、幂等、回执原子性和ACK未知等正反验证 |

并行只分配互不冲突的文件；Maven构建权由根串行协调；子代理不提交/推送。根对最终源码、检查与报告负责。

## 范围与门禁

- 协议四表按已批准27号落DDL，包含merchant独立幂等；request_key用VARBINARY(1024)保留严格字节语义，容纳公共512-byte requestId和固定作用域前缀。非秘密固定参数存canonical字节；trace可NULL但不伪造、不截断。
- 只实现已批get/consent域能力，不自行新增运营publish接口/权限码，不启用HTTP/boot/生产迁移。测试发布内容由隔离fixture明确初始化，不假称运营发布链路已完成。
- 申请审核真实物理契约尚缺，先形成具体CCR方案；生产默认无ApplicationReviewFactsReader时503，不用merchant.ACTIVE或固定APPROVED替代。协议端可真实持久化/读取，申请端测试替身必须单列。
- 不强制重签、已签版本效力保持、下线存量履约的已批准规则不再重复询问。未具备成员绑定、员工停用守卫、冻结写动作等仍不扩展。

## 本轮隔离测试环境

Java21.0.11、Maven3.9.12；MySQL8.4.9绑定127.0.0.1:33451，datadir `D:/Temp/mer001-s3-mysql-1e32f4e5a8404eb7bbf88d9bb44939cf`。
Redis7.4独立容器mer001-s3-redis-20260917，仅127.0.0.1:16382，RDB/AOF关闭。测试仅用自行创建的随机mer001_*库，不接管原MySQL84或其他Redis实例。
SQL06+SQL28已在本轮隔离MySQL成功创建并清理。每次fixture仍自己初始化，不运行默认Flyway；生产迁移与Snowflake宿主证明门禁保持。
