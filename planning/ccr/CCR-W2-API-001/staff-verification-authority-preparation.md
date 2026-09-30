# 员工本人绑定与门店核销动作授权：后续准备

状态：**PREPARATION_ONLY / NOT_APPROVED / NOT_IMPLEMENTED**。日期：2026-09-30。盘点基线：`origin/develop@eb5d16f`（PR #95 合并）。本文仅整理后续最小切片，不是批准回执，不改变已批准规则，不开放 STAFF、生产迁移或开关。

本项与当前第一批退款/售后真实来源建设独立：**员工绑定和授权不是第一批退款的新增前置条件**。退款/售后继续使用各自已批准主体和权限推进；已有 OWNER 核销内核可用于真实退款与核销互斥验收。STAFF 契约准备可以并行，实施按后续切片交付，不以此阻塞退款。

## 1. 权威来源与已批准边界

| 来源 | 已批准内容及本次使用方式 |
|---|---|
| [SSOT](../../../docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md) §25、§30、§37 | 不增加 MFA；员工档案手机号不是登录绑定；核销首切片只接受真实 OWNER，STAFF 登录成员绑定与门店动作授予后续交付。 |
| [商家 PRD](../../../docs/01-prd/03-PRD-商家端-V1.0-最终基线.docx) §5.2、§5.6 | 主账号可开通/停用子账号、分配角色和门店范围；核销权限必须绑定员工账号，禁止共享账号；授权和回收留痕；停用保留历史；子账号不得查看/修改主账号结算和签约信息。 |
| [27 号商家域契约](../../../docs/04-api/27-Merchant-Domain-Contract-v0.1.md) §2、§5、§6.1 | MER 拥有成员/门店/人员事实，AUTH 拥有真实会话及授权组合；OWNER 来源为真实 owner_user_id，STAFF 依显式成员与门店授予；角色名称、phone、service_enabled 均不直接授权；普通读写和成功重放均重验当前权限。 |
| [27 号存储设计](../../../docs/03-database/27-Merchant-Domain-Storage-v0.1.md) §2「merchant_member 与 merchant_member_store_grant」 | 成员、门店授予及动作关联的基础字段/唯一关系已经批准，不能描述为全部未定义；真实用户同意绑定/身份核实流程和撤权并发契约仍缺。 |
| [HTTP10](../../../docs/04-api/10-HTTP-API-Contract-v0.4.md)「商家工作台选择与准入」补充，基线行 1905–1963 | C/M 复用同一 MINIAPP 会话；merchantId/storeId 仅是资源候选；STAFF 由服务端关系解析；准入是提示而非执行许可证；停用子账号不自动注销个人 C 端会话。 |
| [准入 CCR](../CCR-W2-ADMISSION-001.md) §1–4 | 当前已交付切片只产 OWNER；STAFF 字段/成员持久化缺口保留，不据此声称员工入口完成。 |
| [35 号员工基础管理契约](../../../docs/04-api/35-Merchant-Staff-Management-Contract-v0.1.md) §1–3及实现状态 | 列表、详情、创建、档案替换、enable 已实现且默认关闭，不创建登录成员；disable/离职/删除属于独立保护范围。 |
| [48 号核销完成契约](../../../docs/04-api/48-Verification-Completion-Contract-v0.1.md)「K1 身份」及实现边界 | 当前 USER/operatorId 是真实主账号用户，membershipKind=OWNER，operatorStaffId=null；STAFF 不可用，OFFLINE 存量履约保留，FROZEN 写例外不扩展。 |

商家 PRD §5.6 的核销员权限矩阵同时包含「订单处理」和「核销」。后续最小切片应称为「员工核销动作授权」，不能把仅实现核销称为完整核销员角色交付，也不能默默删除角色原有订单处理产品权限。店长、排期负责人未完整细化的动作矩阵仍需后续协议对齐。

## 2. 已批准关系设计与当前实现的差距

### 2.1 已批准字段，不重新发明

沿用 27 号存储设计：

- `merchant_member`：id、merchant_id、user_id、status（ENABLED/DISABLED/REVOKED）、version、created_at/updated_at；唯一 `(merchant_id,user_id)`。OWNER 继续来自 `merchant.owner_user_id`，不伪造员工/成员行。
- `merchant_member_store_grant`：id、member_id、store_id、可空实际 staff_id、status、version、审计时间；唯一 `(member_id,store_id)`。门店须同商家，staff_id 存在时须属于该门店。
- 动作关联按 `(member_id,store_id,action_code)` 唯一；没有授予行不隐含全权限。实际动作清单尚须 AUTH/MER 契约同步。
- 撤权、成员/grant 版本更新与审计在 MER 同一事务；普通读取及成功重放重新查真实关系。授权版本只用于失效提示，不是许可租约。

设计已批准不等于真实 DDL、成员创建来源、HTTP 或运行时授权已经交付。不得通过任意 userId/手机号添加接口绕过尚缺的本人认可和身份核实流程。

### 2.2 代码事实

| 文件/位置 | 现状与后续含义 |
|---|---|
| [MerchantAdmissionService](../../../backend/pet-merchant-biz/src/main/java/com/petplatform/merchant/biz/application/MerchantAdmissionService.java) `listMemberships/getAdmission` | 使用 `selectOwnedStores/selectOwnedStore`，只构造 OWNER；没有真实 STAFF 分支。 |
| [MerchantMembershipDTO](../../../backend/pet-merchant-api/src/main/java/com/petplatform/merchant/api/dto/MerchantMembershipDTO.java) | 当前五字段 OWNER 投影，无 STAFF 的 staffId；需后续契约与 DTO/严格解码同步。 |
| [MerchantReadMapper.xml](../../../backend/pet-merchant-biz/src/main/resources/mapper/MerchantReadMapper.xml) | 当前门店/员工管理归属查询依 owner_user_id；没有 member/grant 查询。 |
| [MerchantOrderAuthorityApi](../../../backend/pet-merchant-api/src/main/java/com/petplatform/merchant/api/query/MerchantOrderAuthorityApi.java) 及其 [实现](../../../backend/pet-merchant-biz/src/main/java/com/petplatform/merchant/biz/apiimpl/MerchantOrderAuthorityApiImpl.java) | 当前人工既有订单动作要求 `requireOwner`；在共享门店 guard 下校验归属及 ACTIVE/OFFLINE。没有 STAFF 的门店动作授予解析。 |
| [VerificationCompletionService](../../../backend/pet-verification-biz/src/main/java/com/petplatform/verification/biz/application/VerificationCompletionService.java) | 当前回执身份校验固定 USER、OWNER、operatorStaffId=null。不能只修改前端会员枚举就开放员工核销。 |

在该基线 backend 与正式 SQL 中未找到 `merchant_member` / `merchant_member_store_grant` 的实现。当前五项员工档案接口和排期读取到的真实服务人员，不足以证明员工登录关系或核销权。

## 3. 缺口分类与裁决边界

### 3.1 已批准但缺实现

- member/grant/动作关联的正式 MyBatis 持久化、真实关系来源及同事务审计。
- 已批准 memberships/admission 的 STAFF 投影、当前关系范围过滤及撤权后的重新鉴权。
- 当前有效 MINIAPP 用户到员工成员/门店授予的真实解析；每次命令和成功重放的最终授权。
- 子账号停用/撤权保留个人 C 端身份及历史操作记录，不沿用历史 staffId 继续授权。

### 3.2 进入实施前须形成 CCR 并同步的技术契约

- 本人绑定命令、查询/管理 HTTP、严格字段、错误码、静态校验、幂等及 CAS；不接受客户端声明操作者或把目标 userId 当验证证据。
- 具体动作码、角色/动作与必要订单读取范围的映射；首切片动作边界与后续角色能力须明确区分。
- 成员/门店/动作撤回与核销在途事务的锁序及提交判定，和既有共享门店 guard 协调；不能用一次准入快照或缓存 TTL 代替。
- STAFF 核销的 operatorType/operatorId/operatorStaffId 映射，以及现有 v2 事件、存储约束、最小回执的兼容规则。必须能追溯真实登录用户与同店员工，不能将 userId 冒充 staffId。
- 已批准成员/grant 的唯一关系、状态与版本设计落为正式 SQL；明确绑定主体变更和历史引用的处理方式，不原地覆盖历史操作者。
- 前端严格解码、成员列表、准入及深链恢复契约同步；前端 allowedActions 不能替代后端授权。

这些是待提交的具体协议工作，不把每个技术字段另包装为产品决策，也不以缺契约为由重问已有 SSOT 或 27 号批准内容。

### 3.3 真正需要产品确认的未定项

| 问题 | 候选最小方案及边界（尚未批准） |
|---|---|
| 员工本人如何认可绑定、主账号如何核实目标身份 | 可准备「主账号发起门店邀请、员工以当前 MINIAPP 会话确认」的具体流程；须明确展示信息、误绑处理与确认节点。本文不选定最终流程，不凭空冻结邀请期限、发送渠道或额外审核步骤。 |
| 换绑、重新开通和撤销关系恢复 | 明确由谁发起、是否重新取得本人确认，以及旧关系/门店授权如何保留历史。27 号已定唯一关系及状态枚举不改变，不能直接以 update userId 代替流程。 |
| 未完整细化的角色权限 | 后续完整角色交付时补店长/排期负责人等动作矩阵；首切片只提出核销及必要读取动作，不擅自定义所有角色，也不把当前切片范围当成产品权限删减。 |

FROZEN 下新增写例外仍未批准。本切片可以维持既有失败关闭边界，不要求现在先决定全部冻结写规则。取消 MFA、C/M 同会话、OWNER 身份、手机号不自动绑定等已批准事项不重新裁决。

## 4. 最小独立后续切片

1. **STAFF-A：具体契约包。** 在原 MER/AUTH/VER Issue 范围内，围绕本人绑定、单门店核销动作授权和撤回形成可审阅 CCR；附请求/响应、状态转换、角色范围、并发序列及验收矩阵。已批准设计直接引用，仅对缺口提出候选。本文不是该批准回执。
2. **STAFF-B：真实绑定与准入。** 获批后实现主账号管理、员工本人确认、门店/动作授予和撤回；复用现有会话，接通 memberships/admission。允许按已批准模型保留服务人员档案与登录成员各自状态，不能让 enable 档案自动恢复撤权。
3. **STAFF-C：核销最终授权。** 将真实 STAFF 解析接到既有核销内核；在同一协调边界下重查当前关系、门店与动作，处理撤权竞争和重放。OWNER 路径保持可用；STAFF 身份、记录与事件一并验收。
4. **后续 HTTP/小程序与端到端接续。** 随整体退款/售后真实来源、核销并发验收及公开交互接通完成；不把内部关系夹具或单模块测试等同完整 VER-002/QA-004。

本切片不顺带实现所有员工角色、人员离职/删除、订单改派、第三方团购核销或未批准冻结写例外。员工档案 disable 的人员占用保护与账号撤权是不同责任，不能混成一个开关。退款第一批不依赖上述 STAFF-A/B/C。

## 5. 后续验收清单（待实现，不是通过证据）

| 编号 | 需要验证的行为 |
|---|---|
| STA-01 | 两个真实 MINIAPP 用户分别完成主账号流程与员工本人绑定；实际业务路径产生 member/grant，SQL seed 仅用于反例，不能作为绑定交付证据。 |
| STA-02 | 同手机号但未绑定、只有档案、只有 roleName、只有 service_enabled=true、客户端伪造 staffId 均不产生核销权。service_enabled=false 也不能直接推断无登录权；最终以获批成员/动作协议判断。 |
| STA-03 | 未确认绑定、DISABLED/REVOKED、无该店 grant、无核销 action、错店 staffId、会话失效/用户停用分别拒绝；依赖未知或读取失败不默认放行。 |
| STA-04 | 同用户多个门店范围隔离；memberships 先授权范围过滤再计数/分页；无关系不泄露资源事实；多门店仍显式选择。 |
| STA-05 | 撤权先提交则后续核销不得成功；核销先提交保留真实历史；撤权后成功回执重放也须拒绝。以真实 MySQL 竞争测试证明协调，不靠测试替身。 |
| STA-06 | 绑定、授权调整、撤权的 requestId 同参重放、异参冲突、CAS 与并发唯一关系成立；业务与审计同事务；失败无半条授权。 |
| STA-07 | OWNER 不虚构 staffId；STAFF 可追溯当前绑定的真实用户/员工；大 ID/version 为 String；审计与事件不泄露手机号、邀请凭证或会话令牌。 |
| STA-08 | 子账号不能读取签约/结算敏感信息；缺服务、排期、员工管理等动作时拒绝；首切片未覆盖的角色能力明确列为后续，不能宣称完整角色完成。 |
| STA-09 | OFFLINE 按当前获权关系保留存量履约；FROZEN 不新增写例外；不以新单资格整体挡住已批准的独立存量责任。 |
| STA-10 | 子账号停用不注销其个人 C 端会话；改档案手机号、enable 服务人员或前端缓存恢复均不能重新授予撤回的权限。 |
| STA-11 | 授权收回与人员档案/在途指派保护相互独立；不为撤权置空订单 staffId，不借本切片删除档案历史。 |
| STA-12 | 后端 MySQL/HTTP、撤权竞争、幂等恢复和架构/持久层检查通过；前端严格解码与最终端到端另有真实证据；开关默认关闭、迁移及生产启用边界披露。 |

## 6. 本次交付证据与限制

本次只读核对 SSOT、商家 PRD 原件、正式契约/存储设计及 `origin/develop@eb5d16f` 代码，形成此准备文件；没有实现 STAFF，没有执行数据库迁移，没有运行 STAFF 业务测试，因此不声明任何上述验收项已通过。后续 CCR 批准、实现、测试和完整角色交付状态须另行记录。
