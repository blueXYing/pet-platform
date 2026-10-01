# 售后三端页面联调：后端交接

2026-10-01；Backend Owner：`/root/aftersale_contract_backend`。同一集成树 `codex/aftersale-pages-20261001`，本批后端只增加测试与本机联调支持，不改变正式 API、SSOT、产品规则、生产 SQL 或默认开关。正式目录资源由根唯一维护。

## 环境与真实边界

`backend/tools/run-aftersale-joint.ps1 -Mode Start` 显式启动 `AfterSaleJointLiveTest`，使用 Java 21/offline Maven。MySQL 8.4、Redis 7.4-alpine、Spring Boot HTTP 与测试控制入口均仅监听回环随机端口。每批独立容器、随机 schema、随机 Redis namespace；启动和清理都核对完整容器 ID、名称、任务 label 与回环绑定，不操作其它容器或数据库。

真实实现包括 HTTP controller、C/ADMIN 登录尝试与会话持久化、Redis 会话、当前 OWNER 与审核/签约投影读取、当前 RBAC、业务 MyBatis SQL、共享门店锁、事务、Admission 参数绑定、首回执、ORDER 投影与 Outbox。订单通过业务 API 创建；付款为离线签名测试通知，随后真实 OWNER 确认及真实凭证核销。售后与非退款终局通过真实 HTTP 创建，不插入积极售后结果。静态商家/员工/门店/审核签约来源资料属于明确测试 provisioning，不声称本批重新执行入驻审核与签约流程。

微信上游身份/手机号 exchange、内容审核、病毒扫描与内存私有 OSS 为明确 fixture provider；外部服务凭证和真实渠道联调未提供。资金 Provider 不注册，公开退款型裁决、worker/出款/Outbox 自动消费者保持关闭；启动及结束均断言零退款单、执行、资金证明、派发与渠道调用。物理微信真机与公网部署不由本机环境证明。

## 正式目录

Live harness 显式导入根维护的 `classpath:aftersale-catalog.yml`，不覆盖为旧 QA 目录。来源为最终 C PRD 5.1.28 与 M PRD 5.10 的完整 5+5 中文选项；同一生产 ReasonPolicy 同时提供目录和 Create 校验。测试数据描述及外部 provider 仍明示 QA。原 acceptance fixture 默认 `QA_*` 目录不变，未成为正式配置。

新增真实 Spring YAML/binder 验证涵盖括号键保留、ASCII 顺序、全部 25 类别/诉求组合、旧 QA code 拒绝，以及导入目录不打开 workflow/HTTP/worker/refund。`AfterSaleWorkflowConfigurationTest`：27 tests、0 failures、0 errors、0 skipped；Java 21 offline，23.156s，2026-10-01 16:46:13 +08:00。原始日志仅保存在 ignored `backend/pet-boot/target/aftersale-joint-config.log`。

## 页面协议

仅本机 ignored `backend/pet-boot/target/aftersale-joint/runtime.json` 保存真实 fixture 会话、网页登录凭据及随机控制 secret。禁止将其内容、截图中的令牌、请求授权头或私有图片写入 Git/正式报告/console 摘要。

- `backendOrigin`：运营受控代理 target；`baseUrl`：含 `/api/v1` 的后端地址。
- `admin`：真实网页登录 account/password 及 operatorId；初始真实 RBAC 只授售后 read/handle/decide，范围 ALL。浏览器应真实登录，独占运营预览 origin 为 `http://127.0.0.1:4174`。浏览器登录会替换旧 ADMIN session，控制入口不依赖旧 token。
- `buyer` / `owner`：各自手机号、fixture 上游 `wechatCode` / `phoneCode` 与真实登录得到的 `grant`；恢复后仍由真实 `/c/auth/session` 复验。测试码只适用于明确 fixture provider，不冒充官方微信登录。
- `scope`：真实测试 merchantId/storeId。OWNER memberships/admission 通过生产读取 API 复验。
- `cases.p4CaseId` 与 `priorFinalIds`：同单三笔真实 REJECT/RESERVICE/OTHER 非退款正式终局及新问题待受理工单；`workflowCaseId` 与 `recoveryCaseId`：独立订单上的待受理工单。三者均有真实预上传 PNG 证据入卷。
- `mini.orderId` / `cases.miniOrderId`：独立、已核销、未创建售后的订单，供实际 C 页面创建后同单 M 参与。运营测试与小程序使用不同订单。
- `clockInstant`：完成准备后保持固定的测试预约时钟；UI/control 不推进该值。补证截止应按该值构造未来 UTC 毫秒时间。

`control.baseUrl` 为仅回环 test-only JDK server，必须携带 `X-Joint-Control: <runtime secret>`。生产 Spring 不注册这些入口。响应固定 `{fixtureOnly:true,data:...}`。

| 控制入口 | 用途 |
|---|---|
| GET `/summary?caseId=<String decimal>` | 返回 status/version/orderVersion/currentAfterSaleId、按该工单计的 commands/evidenceBatches/decisions/transitions/statusLogs/AFTERSALE outbox，及全环境零资金计数/channelCalls；无令牌、自由文本或对象地址 |
| POST `/admin/permissions`，`{mode:"READ_ONLY"}` 或 `{mode:"FULL_AFS"}` | 同事务修改当前真实 RBAC 与授权 revision，供撤权/恢复验收；FULL_AFS 仅上述三项售后权限 |
| POST `/buyer/fulfill`，`{caseId:"..."}` | 当前买家真实 GET 读取当前 USER 补证轮次，再通过真实 HTTP 提交文本证据；不替代 C 页面交互证据 |
| POST `/stop`，`{}` | 请求显式结束；根也可调用启动脚本 `-Mode Stop` 写 ignored stop 文件 |

运营丢 ACK 验收由 QA 在唯一指定写上真实 `route.fetch()` 提交后 `route.abort()`；无 fulfill、假业务响应或生产网络故障入口。该受控网络故障须与普通 live 页面交互区分。

## 验证状态与清理

配置测试已通过。第三轮 live 环境在联调期间 READY，现已精确关闭：真实 HTTP backend 为 `http://127.0.0.1:42917`，控制/业务接口均已返回；MySQL/Redis 本批回环端口为 42669/42670。准备完成后时钟固定 `2030-01-01T10:30:01Z`，四个独立订单均经真实确认/核销；OWNER memberships 200、admission 200/ALLOWED、本人小程序订单 eligible=true。目录真实返回完整正式 5+5，未登录目录 401，未携控制 secret 的 control 返回 403。恢复工单初始真实 SQL 摘要为 PENDING/version0，命令/批次/迁移/日志/本工单 Outbox 各1、决定0、所有资金项0。Docker 完整 ID/name/label/回环绑定复核通过；原项目 Redis 16389 未受影响。

最终单轮相关 unit/config：34 tests、0 failures、0 errors、0 skipped（Catalog 3、Options HTTP boundary 4、Workflow configuration 27）；Java 21 offline Maven BUILD SUCCESS，21.261s，2026-10-01 17:12:11 +08:00，原始日志 ignored `target/aftersale-joint/maven-final-config.log`。没有重跑无变化的长数据库基线。最终持久化 MyBatis 门禁、41 reactor/17 biz 模块依赖门禁、DisplayStatus 门禁、`git diff --check` 均 PASS；本批生产源变化仅根持有的正式目录 YAML，未修改生产 Java/SQL 或默认开关。

QA 已交接前两项运营真实浏览器用例 PASS（6.7s / 4.5s）：实际私图 grant/consume、完整三历史 P4 核对、USER 补证与 OTHER 非退款终局；独立恢复卷宗真实写提交后仅一次受控网络丢 ACK，撤写权限后原请求 403，恢复并重新真实登录后同 UUID 返回原 200 首回执，完整工单 SQL 摘要严格相等。控制已恢复 FULL_AFS。该两项由 QA 执行，完整证据由 QA 交接记录；不称为 C/M 页面已完成。

后续根实际 C 创建/补证、实际 OWNER 准入与 M 意见完成；QA 第三项真实 A 页面同单协作用例也 PASS，三项 Playwright exit0。A 实际读取两批 USER、一批 MERCHANT，随后 OTHER 决定。根又真实刷新 M、重新登录 C，从双方页面实际读到同单 RESOLVED/OTHER/三批证据，C 补证/撤回入口消失。最终同单独立 SQL 摘要：caseVersion5、orderVersion9、commands6、evidenceBatches3、decisions1、transitions6、statusLogs6、AFTERSALE outbox6；全部资金产物计数及 channelCalls0。原始安全摘要仅在 ignored `target/aftersale-joint/same-case-final-summary.json`；页面/模拟器证据由根与 QA 维护，外部 fixture provider 和物理真机限制仍适用。

首次两次 live 准备失败均为新多订单夹具的时钟假设：2030 预约时钟错误用于 2026 离线付款的 30 分钟确认；以及原单订单 wall-clock booking 读取已完成的未来预约，生产来源完整性正确拒绝。修复仅调整准备顺序：先在付款真实时点创建/确认全部独立订单，再在明确测试预约完成时点重新真实登录并核销，最后才暴露 runtime；未修改生产校验、时限或业务结果。失败日志保存在 ignored runtime 目录，每次失败由 owning launcher 精确清理本批容器。

根确认 C/M 最终读取、真实 logout、恢复上游 API mocks 并关闭隔离微信项目后，于 17:10 显式 Stop。真正最后单轮 `AfterSaleJointLiveTest`：1 test、0 failures、0 errors、0 skipped，906.049s；Maven BUILD SUCCESS / exit0，2026-10-01 17:10:08 +08:00。它仅表示整个真实环境生命周期及业务/零资金断言通过，页面通过证据仍来自上述根/QA记录。独立备份 XML `target/aftersale-joint/TEST-AfterSaleJointLiveTest-final.xml` SHA256=`431f364ca7f410a0d5f40e9bcdddeb91dd0f7a82c09a9547720209ee938f937b`；不得将先前失败启动称为通过或将不同 Maven 执行合并成一次。

已关闭 test control/Spring，fixture JVM PID36964 已退出，schema/Redis namespaces 随 fixture 关闭清理。`runtime.json` 与 `runtime.tmp` 凭据文件已不存在。owning launcher 逐一核对完整 ID/name/task label/回环绑定后只移除本批两个 MySQL/Redis 容器及匿名卷；完整两 ID在 `docker ps -aq` 均不存在，42669/42670/42917 无监听，原项目 `xinzitong-uat-local-20260930-redis` / 16389 仍运行。ignored `final-summary.json`、`cleanup.json` 与 `containers.json` 保存本机精确清理证据；正式报告未复制 credential runtime。本 Owner 未提交或推送。
