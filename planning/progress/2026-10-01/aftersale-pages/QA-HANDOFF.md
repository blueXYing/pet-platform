# 三端售后页面独立 QA 交接

日期：2026-10-01；独立角色 `/root/aftersale_qa`。已读取 AGENTS.md、Contract51、切片 PLAN，并审查集成树 C/M/shared 与运营实现。仅写本报告；业务修复由各唯一实现 Owner 完成。该报告不把既有 HTTP 回归、合同 fixture 或单测称为三端页面真实闭环，也不宣称完整 C-005/M-004/A-004/QA-005 或 VIS 完成。

## 定向真实后端 HTTP 回归

使用本机既有 Temurin 21.0.11 (`C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`) 与 Maven 3.9.12。Docker 默认 TCP 2375 不通，明确指定本机既有 `npipe:////./pipe/docker_engine` 后可用。只使用已缓存 `mysql:8.4`、`redis:7.4-alpine`，`--pull never`，无下载/安装数据库、无更改用户 MySQL 3306 或其它项目 Redis/配置。

本批独立容器 `pet-aftersale-qa-20261001-mysql` / `pet-aftersale-qa-20261001-redis` 只绑定回环随机端口 34879 / 34912；测试环境 AUTH_MYSQL/REDIS 与 JAVA_HOME 仅设置于 Maven 进程。MySQL 随机测试 schema、Redis 随机命名空间由既有 fixture 管理。外部微信、审核、扫描、OSS 为明确隔离 Provider；身份会话、Spring Boot HTTP、权限/工单事务和 SQL 证据是真实实现。公开资金能力仍关闭，未注入资金 Provider。

命令：`mvn -o -B -f backend/pom.xml -pl pet-boot -am -Dtest=<Suite> -Dsurefire.failIfNoSpecifiedTests=false test`。

| Suite | XML tests | failure/error/skipped | 结果 |
|---|---:|---|---|
| AfterSaleHttpAcceptanceTest | 4 | 0 / 0 / 0 | BUILD SUCCESS，2m53s |
| AfterSaleEvidenceHttpAcceptanceTest | 3 | 0 / 0 / 0 | BUILD SUCCESS，1m53s |

第一套覆盖真实 C/OWNER/ADMIN 会话、补证、三类非退款终局、撤回和重复关闭、严格 DTO/UUID/CAS、出款关闭、冻结/动作撤权下的重放。第二套覆盖双方证据三端读取、grant 单次/端别/会话绑定、对象读取中撤权或隔离不返图片、Servlet multipart 错误不留上传 admission。共 7 tests，零失败/错误/跳过，两套首次运行均成功。

权威本地 XML 位于 `backend/pet-boot/target/surefire-reports/`：

- `TEST-com.petplatform.boot.booking.AfterSaleHttpAcceptanceTest.xml` SHA256 `7a6150728456a74bc1b618d26789f43544fac14d7ba1c401ae64b8b23554145d`。
- `TEST-com.petplatform.boot.booking.AfterSaleEvidenceHttpAcceptanceTest.xml` SHA256 `5ddb2474911c13bce41781b2a847bb6c97c4ac12f7694b92e592638d170020be`。

原始 Maven 日志只在本地 `.cache/aftersale-qa/aftersale-http-maven.log` / `aftersale-evidence-http-maven.log`；不提交原始环境日志或私有数据。运行后核实 MySQL 仅四个系统库、Redis DBSIZE=0 / save 空 / appendonly=no。已核本批精确容器 ID，再删除本批两个容器及匿名卷；34879/34912 监听消失。其它项目 `xinzitong-uat-local-20260930-redis` 仍原状运行。

## 前端独立定向验证

在集成树运行真实测试代码：shared/aftersale-api、C model/upload、M controller/repository 五文件，首次 51 tests / 51 pass / 0 fail / 0 skipped；Owner 补上 COMMON_CONFLICT 保留与重启预览清理后，加入 shared/private-evidence-files，六文件独立重跑 55 tests / 55 pass / 0 fail / 0 skipped。这些是受控传输与存储测试，不能替代真实微信网络或本批页面连接后端的验收。后续实现修复增加的测试以根协调最终集成报告为准。

独立静态核查：C/M/shared 的 scope revision/ticket 拒绝旧身份、门店和同坐标重校验的迟到结果；商家每次进入和动作先查真实 OWNER 准入，STAFF 与错坐标失败关闭；图片请求只用当前路由的相对 grant，无对象裸 URL；商家没有终裁按钮，运营决定选项仅 REJECT/RESERVICE/OTHER。C 生产 catalog port 明确返回 null、禁止创建，没有猜测目录或技术代码输入冒充产品表单。P4 运营逐笔读取整个 priorFinalCaseIds，并验证同单 RESOLVED 非退款决定后人工逐项确认，提交原 finalSetVersion。

## 审查发现与修复核对

1. 401/登出清空未知业务 UUID：已由根修复 `c52c5bf`。仅保留按 userId/party/merchant/store/target 绑定的售后命令，重新鉴权前不可见；pending 返回副本，不自动重放。进一步发现403/404仅表明当前访问被拒，不能证明先前未知请求未提交；根 `9a158d0` 仅对售后命令保留访问拒绝下的原 journal，C保持原命令锁定。静态核查与shared+C变更后的28项定向测试独立通过，包含未知→403/404→同人恢复→原UUID/body。
2. 异常失败 envelope 被当明确400退休命令：已由根修复 `6f865a4`，先验证四字段与失败 data=null；非法响应保持未知。实际回归已通过。
3. 成功回执未核目标：已由根修复 `af2e256`，create 核 orderId，其余核 afterSaleId；不同目标回执不退休命令。实际回归已通过。
4. COMMON_CONFLICT / 幂等409 / 429被当确定CAS：M `8a9f60b`、C/shared 和 A `28fd89a` 已改为确定业务码白名单；通用/未知409和429保留原命令。已静态复核运营最终补丁。后端实际 CAS、P4 finalSetVersion 和权限版本变动也使用 COMMON_CONFLICT（AfterSaleService 第96/180/190行），该码又表示幂等争锁，因此现契约不能支持可靠区分并退休实际 CAS。`CCR-AFS-CONFLICT-001`已登记，未改后端/公共错误码，不得宣称所有 CAS 已有编辑恢复闭环。
5. 字符串末尾换行、恢复 journal 验证：公共 ID/金额/版本/hash/grant/UUID 严格边界已补回归；C 页面 ID/金额与上传 journal 完整 UUID/hash 已独立静态核为严格结束。根 `4fea653` 收紧 M journal UUID/hash，已静态复核，根定向 M repository 4 tests PASS。
6. 运营未知写仅内存、隐藏未清 blob URL、刷新未清人工确认：已由 A `6880b6b` 修复并集成为 `28fd89a`。独立终审核了 localStorage 持久原 UUID/body、operator/store/case 绑定、真实session+permissions后绑定身份、实际详情+指定店分页证实资源后才显示/恢复日志；无/非法scope清既有proof，换人/店不可见。grant/token/图片只在内存，401/403/404不清业务日志。storage读/写失败暂停处理而非发新命令，统一安全读取覆盖command/grant/execute；刷新清人工确认，visibilitychange/pagehide 清blob URL及页面generation，迟到二进制不重新显示。另发现跨tab原命令迟到ACK会删新日志，已改为当前持久entry匹配本次serialized snapshot才退休，并补共享storage双client测试。
7. 小程序私有预览文件写 USER_DATA_PATH、进程被终止后 Set 丢失：根已补 `PrivateEvidenceFiles` 启动恢复；严格只拥有 `pet-aftersale-完整UUID.img`，不碰 durable upload 副本。静态复核及两个定向测试已通过（重启恢复、删除失败可重试、部分写入仍清理、非法名字不写）；真实微信文件适配仍由平台取证确认。
8. C原生图库缓存改为页面内私有overlay：已独立复核 `51d81b7`，close/hide/scope/unmount 的 epoch、visible 和 flight 拒绝迟到读取；根 `34811e6` 在重新验证详情/session前清除当前图片，403刷新后不残留overlay。该页面生命周期已静态核查，未冒充真实微信授权图片读取。

## 最终集成复核

根集成 `28fd89a` 与已审 A 原提交 `6880b6b` 的整个 frontend-admin 及 A-HANDOFF 文件 `git diff --name-only` 无差异，实际集成代码与终审对象一致。本报告差异检查通过。A Owner 的生产构建/边界、完整浏览器合同fixture为 63 passed / 2 既有 live opt-in skipped，最后scope补丁7项定向通过；根正在执行该集成树唯一一次最终运营回归，结果记根 IMPLEMENTATION。关闭旧context、复制持久origin状态到新context后原键恢复属于浏览器profile模型测试，不宣称操作系统重启已实测。

根报告小程序235项全量通过，最终构建/类型/包体检查通过；后续M journal正则小改另4项定向通过。模拟器五个入口成功打开并人工核未登录失败关闭；149×321截图不作VIS。automation_runtime_info超时、console查询无匹配error，仅按这些事实记录；真实后端origin未配置。模拟器本批窗口已关闭、project.config原字节恢复。上述为根平台证据，本独立QA未重复操作或冒充亲自执行。

独立审查发现的代码问题均已交唯一Owner修复并完成上述核对；剩余阻断为已登记的两项合同缺口与尚未执行的验收。没有新增后端/Contract/Schema/Event/Scheduler、生产配置或发布动作，没有push/PR/merge。

## 仍未完成的验收

权威申请目录缺口由 `CCR-AFS-PAGE-OPTIONS-001` 承接，当前生产新建申请保持禁用。当前合同错误码的 CAS/争锁歧义需要单独 CCR。前述真实 HTTP 测试未调用本批页面；运营浏览器测试用 HTTP fixture，C/M 真正会话上传/读取、联合页面闭环、物理真机、VIS 截图叠图及字体/稿件差异评审，均不能以该报告标为通过。Figma 读取和真实节点导出记录来自实现代理交接及资产 manifest，本 QA 未重新调用 Figma，不重复宣称其已通过视觉验收。
