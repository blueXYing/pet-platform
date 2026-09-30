# 48 — OWNER 核销完成与最小售后失效

状态：APPROVED，2026-09-30，用户批准 M94/K1/K2。依据 [批准方案](../../planning/ccr/CCR-W2-API-001/verification-completion-proposal.md)，SSOT §37。本契约覆盖 47 中“无真实主账号适配/无核销完成”的阶段限制；不改码期限、风险阈值或退款规则。

## K1 身份

只接受有效 MINIAPP 会话的 ACTIVE USER，operatorId 必须为该会话真实 userId；MER 在共享门店 guard 下重验 merchant.owner_user_id 与门店归属。每次执行及重放均复验，旧回执不授予权限。OWNER 的 operatorType=USER、membershipKind=OWNER、operatorStaffId=null。STAFF 仍不可用，不从服务人员档案推导权限。OFFLINE 允许存量履约；FROZEN 保持原失败关闭边界。

## K2 命令与首回执

内部 VerificationCompletionApi.verify(Command)：context/orderId/storeId/verificationCode/expectedCredentialVersion/confirmed。confirmed 必须 true，方式固定 SCAN；无手动订单号兜底。码语法沿用 47 的 1～128 位大写字母数字，版本非负十进制 String。成功回执 orderId/attemptId/resultCode=VERIFIED/verificationId/verifiedAt/orderVersion。无效码、过期码、风险锁为已提交业务结果，其后三字段 null；风险计数与 47 共用系列。

namespace=verification.complete，二进制五元组(namespace,operatorType,operatorId,STORE:storeId,requestId)唯一。独立 Admission 持久绑定加密参数；执行回滚保留绑定。成功重放返回首回执的原始时间与版本，经当前权限及本域持久证据校验；另一个 key 对已核销订单返回 VERIFICATION_ALREADY_DONE。没有 HTTP Controller，不接受客户端声明的身份。

## 同事务提交协议

顶层 VER 事务为同 DataSource、可写 READ_COMMITTED，命令行锁→共享门店 guard→ORDER 原始资格/VERIFY token→退款当前事实→VER 当前码/版本/风险→AFS 当前工单。公共 check 不得嵌套；仅复用 VER 包内校验内核。

OrderVerificationCommitApi.acquire 创建仅当前事务有效的随机 VERIFY token，绑定 commandId、可信 context、ORDER 当前版本与完整事实；不提供 CREATE_REFUND。负面码结果 release token 后提交风险尝试。markVerified 必须使用相同 token 和持久化 VER 成功证据、AFS 证据；更新 COMPLETED/VERIFIED、verified_at/completed_at、version+1、状态日志及唯一 OrderVerifiedEvent.v2。ORDER 自有提交记录保存版本、核销/凭证/尝试/命令/事件关联。

AfterSaleVerificationApi.invalidateCurrent 从 ORDER token 取得真实来源，只访问自身工单。核对订单、用户、商家、门店、source_stage、active_flag 和指针。无指针且无 active 行返回明确 NONE 证据；指针缺行、反向孤儿、身份不一致或未知状态均失败。当前 active 的 UNVERIFIED_POST_START 且 PENDING/PROCESSING/WAITING_SUPPLEMENT 才失效，version+1、active=0、INVALIDATED、invalidatedAt=verifiedAt，保留证据和历史指针。已结束历史工单不改写；不能以未履约订单为来源失效 VERIFIED 工单。AFS 记录不可变核销证明，ORDER 同步 aftersale_status。

VER 写唯一成功记录、真实操作人尝试、凭证 VERIFIED 消耗；ORDER/AFS/VER 提交前相互复核同一次核销的持久证据。孤立完成、孤立失效、错误 DataSource、只读事务、旧 token/跨事务 token 均不能提交。任一持久化、日志、Outbox 或首回执失败整体回滚。

## 事件与迁移

OrderVerifiedEvent.v1 不变。v2 由 ORDER 唯一生产，aggregate=ORDER/orderId，payload 严格为 orderId/verificationId/merchantId/storeId/operatorType/operatorId/membershipKind/operatorStaffId/verifiedAt；所有 ID 为 String，staffId 可空，时间 UTC 毫秒，无码/手机号/昵称。通知与评价消费者后续交付。

SQL48 扩展原成功/尝试表的真实身份和 command_id，移除全局 request_id 唯一键、保留 order_id 成功唯一键；新增 ORDER/AFS 提交证据与 ORDER 售后状态投影。现无可核实员工绑定历史的来源，因此迁移遇到任何旧核销行即拒绝，必须先另行提供并审阅真实映射，绝不猜测 OWNER 或抹除历史。仅隔离 QA 执行迁移。

pet.verification.completion.enabled=false；开启需要 credential 内核及其真实依赖/密钥。不开 HTTP、生产开关或 worker。既有迟到支付及商家拒单退款保留真实来源保护；完整售后创建/裁决/CREATE_REFUND、员工授权和端到端 VER-002/QA-004 均未交付。
