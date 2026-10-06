# Merchant Staff Identity Contract v0.1

状态：IMPLEMENTED_DEFAULT_OFF（内部读侧内核），2026-10-02。本契约只覆盖员工登录身份关系的**读侧解析内核**：成员/门店授予/动作授予的存储（已批准）与真实会话到关系的失败关闭解析。成员绑定命令、授予/撤回写命令、公开 HTTP、正式动作目录、核销 STAFF 身份映射**均不在本切片**，进入实施前须按[绑定与动作授权 CCR 草案](../../planning/ccr/CCR-W2-API-001/staff-identity-binding-proposal.md)完成裁决。授权依据：[27 号存储设计 §2](../03-database/27-Merchant-Domain-Storage-v0.1.md)、[27 号契约 §5](27-Merchant-Domain-Contract-v0.1.md)、[HTTP10「商家工作台选择与准入」§1–3](10-HTTP-API-Contract-v0.4.md)、[SSOT §25/§30/§37](../00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md)、[商家 PRD §5.2/§5.6](../01-prd/03-PRD-商家端-V1.0-最终基线.docx)、[员工核销权威准备](../../planning/ccr/CCR-W2-API-001/staff-verification-authority-preparation.md)。

## 1. 本批边界

已实现（开关 `pet.merchant.staff-identity.enabled`，默认 false）：

- `merchant_member` / `merchant_member_store_grant` / `merchant_member_store_action` 三表正式 DDL（52 号存储，见下）。
- 内部查询 API `MerchantStaffIdentityQueryApi`（pet-merchant-api）及 pet-merchant-biz 实现：成员会话关系列表、单店身份事实、共享门店 guard 下的动作门。
- pet-boot 装配类，默认关闭；无任何 HTTP 路由、无前端改动。

未实现且不声称完成：绑定/授予/撤回写命令与 HTTP（STA-01/06）、动作目录与角色矩阵（STA-08）、STAFF 核销身份映射与事件兼容（48 号 K1 明确 STAFF 不可用）、员工档案 disable 的在途订单守卫（35 号 §6.1 保持 IMPLEMENTATION_BLOCKED）、FROZEN 写动作例外（B-FROZEN-WRITE 仍待裁决）。既有多会员准入（memberships/admission）保持仅 OWNER 输出不变；接通 STAFF 投影属绑定切片。

## 2. 权威关系存储

[52 号存储](../03-database/52-Merchant-Staff-Identity-Schema-v0.1.sql)沿用 27 号存储设计 §2 已批准字段与唯一关系：

- `merchant_member`：UNIQUE(merchant_id,user_id)；status ENABLED/DISABLED/REVOKED；OWNER 永远来自 `merchant.owner_user_id`，不存在也不允许伪造 OWNER 成员行。员工档案手机号不是登录绑定（SSOT §30），phone/roleName/service_enabled 一律不参与授权。
- `merchant_member_store_grant`：UNIQUE(member_id,store_id)；store 必须同商家；`staff_id` 可空且仅作历史操作主体展示，存在时必须属于该 store。grant 版本在授予/动作变化时递增，是 authzVersion 的输入之一。
- `merchant_member_store_action`：UNIQUE(member_id,store_id,action_code)；没有授予行不隐含全权限；动作码词法 `[a-z0-9][a-z0-9.-]{0,99}`（沿用 HTTP10 管理目录词法），正式目录仍待 CCR。
- 成员/grant 版本递增与审计在同事务（写命令到货时按 23 号执行）；本切片只有读取与锁内复核，无写路径。撤回后不得原地覆盖历史操作者引用。

## 3. 内部解析接口（受信任会话适配器专用）

实现：`MerchantStaffIdentityApiImpl`。operator 恒为真实 MINIAPP USER（C/M 同会话，HTTP10 §1）；userId 取自会话 context，不接受客户端声明操作者。普通读与成功重放都重查当前关系，缓存标签不是许可。

| 方法 | 输入 | 成功 | 失败关闭映射 |
|---|---|---|---|
| `listStaffMemberships` | page(1..10000)/pageSize(1..50)/context | 仅 ENABLED 成员 × ENABLED grant 的 STAFF 投影（merchantId/storeId 数值升序，先过滤后分页计数），`staffId` 仅展示 | 会话缺失 401；非 USER 403；事实损坏/依赖失败 503 |
| `getStaffFacts` | merchantId/storeId/context | membershipKind=STAFF、membershipEnabled、applicationStatus、signingStatus、merchantStatus、storeStatus、authzVersion、checkedAt、staffId?、grantedActions | 无关系/成员 REVOKED/grant 缺失或 REVOKED 404（防枚举，不泄露商家事实）；成员 DISABLED 403（STAFF_DISABLED）；状态未知/引用跨店/申请事实不可读 503 |
| `requireStaffAction` | merchantId/storeId/actionCode/context | void（通过） | 同上；动作未授予 403；merchant/store FROZEN 403（无已批冻结写例外）；OFFLINE 403（存量履约动作归类未决，随动作目录 CCR 放开）；申请未 APPROVED 或协议未 SIGNED 403 |

`authzVersion`：`STAFF|merchantStatus|storeStatus|applicationStatus|signingStatus|merchantVersion|storeVersion|memberVersion|grantVersion` 固定顺序的 SHA-256 截断标签，仅作客户端失效识别，不是执行许可证；动作授予变化随 grant 版本递增反映。

## 4. 动作门与在途协调

`requireStaffAction` 在调用方持有的共享门店 guard 事务内（`ScheduleCapacityGuardApi.requireHeld` 先行）对 member/grant 行 `FOR UPDATE` 锁定并重验当前事实，动作行同锁读取；任何一步失败即拒绝，不使用准入快照或缓存 TTL。撤权与在途命令的锁序/提交判定最终协议属绑定 CCR 待冻结项（D4），本门已按"锁内重验 + 失败关闭"实现机制部分。

## 5. 验收状态

真实 MySQL 隔离测试（`MerchantStaffIdentityApiMySqlTest`）覆盖：关系解析与 facts 投影、authzVersion 随 grant 版本变化、 memberships 过滤分页（DISABLED/REVOKED/无 grant 不出现）、动作门成功路径、未授动作/DISABLED/REVOKED/无 grant/跨店引用损坏/FROZEN/OFFLINE/未签/非 USER 的拒绝路径、防枚举 404。STA-01/02/05/06/07/10/12 的绑定与竞争部分等待绑定切片以真实业务路径证明，本批不以 seed 声称完成。
