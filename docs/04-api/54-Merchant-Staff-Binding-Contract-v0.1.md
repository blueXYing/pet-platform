# Merchant Staff Binding Contract v0.1

状态：IMPLEMENTED_DEFAULT_OFF，2026-10-06。本契约实现[绑定与动作授权 CCR](../../planning/ccr/CCR-W2-API-001/staff-identity-binding-proposal.md) §5 已批裁决中的 **D1-a 邀请-确认两步绑定** 与 **D2 最小动作目录（V1 仅核销一个动作）** 的写侧：OWNER 邀请/撤销邀请/成员停用恢复/门店动作授予回收、员工本人确认。读侧事实形状、成员/授予/动作存储与动作门仍以 [52 号契约](52-Merchant-Staff-Identity-Contract-v0.1.md)为准，本契约不改变其语义；52 号三表 DDL 无任何变更。开关 `pet.merchant.staff-member.enabled`（默认 false）独立于 52 号读侧开关。授权依据：商家 PRD §5.2（主账号开通子账号、每次进入工作台校验子账号启用状态）、§5.6（核销授权指定员工、授权与回收留痕、核销权限必须绑定员工账号禁止共享账号）、§5.8（核销必须由已授权商家员工操作、核销人所属门店须与订单门店一致）、SSOT §25/§30/§37、[23 号公共幂等契约](23-公共接口与幂等契约补充-v0.1.md)。

## 1. 本批边界

已实现（默认关闭）：

- [54 号存储](../03-database/54-Merchant-Staff-Binding-Schema-v0.1.sql)三表：`merchant_member_invitation` / `merchant_member_invitation_action` / `merchant_member_audit`；52 号三表不变。
- 内部命令 API `MerchantStaffMemberCommandApi`（pet-merchant-api）与 pet-merchant-biz 实现：invite / cancelInvitation / disableMember / enableMember / grantActions / revokeStoreGrant / confirmInvitation。
- OWNER 管理查询 `MerchantStaffMemberManagementQueryApi`（成员列表、邀请列表）与员工本人确认页查询 `getMyInvitation`。
- pet-boot HTTP：OWNER 侧 `/api/v1/merchant/staff-members/**`，员工侧 `/api/v1/c/staff/invitations/{invitationId}`（读 + confirm）；随 `pet.merchant.staff-member.enabled` 装配（员工侧另需 `pet.auth.c.enabled`）。
- 跨模块新增内部只读 API：pet-user-api `UserPhoneVerificationApi.hasVerifiedPhone(userId, phone)`（pet-user-biz 实现，账号手机号恒不出用户模块）；商家模块经 pet-boot 注入端口使用。事实来源即 S9 既有链路：登录会话发放前账号手机号必已经微信 getPhoneNumber 一次性 code 兑换（`WechatSessionProvider.exchangePhone`），无手机号事实的账号不发放会话。

未实现且不声称完成（随 CCR §3/§5 既定切片）：成员 REVOKED（撤销成员）命令与一切"撤销后重邀/换绑/恢复"语义（**D3 保留待裁决**，本批整店撤权与撤销邀请均为终局，不可原地复活）；grant.staff_id 展示引用的回填规则（本批恒 NULL，属 48 号 K1 修订切片裁决）；核销完成内核接入 `requireStaffAction`（STAFF-C 另立契约修订）；站内通知投递（NTF-001）；员工端确认页面（CCR §3.3 随 M 端工作台员工入口另行交付，本批仅交付服务端确认通道）；邀请有效期与次数上限（CCR D1 待明确子项，本批不设自动过期，仅可被 OWNER 撤销）。

## 2. 邀请-确认状态机

```
OWNER invite ──► INVITED ──(OWNER cancelInvitation)──► CANCELED（终局）
                     │
                     └──(员工本人 confirmInvitation，同事务)──► CONFIRMED（终局）
                                └─ 创建 merchant_member(ENABLED) + merchant_member_store_grant(ENABLED)
                                   + merchant_member_store_action(邀请动作集) + 审计，成员/授予版本同事务落库
成员：ENABLED ⇄ DISABLED（OWNER disable/enable，独立于员工档案 service_enabled；52 号读侧对 DISABLED 即时 403 STAFF_DISABLED）
授予：grant ENABLED ──(revokeStoreGrant)──► REVOKED（终局，动作行删除；D3 未裁决前不得重新授予）
```

- 邀请目标：门店 + 11 位手机号 + 姓名 + 动作集（V1 目录子集，非空）。同一商家同一手机号至多一条 INVITED 邀请（存储层 `uk_mer_member_inv_pending` 保证，应用层映射为 CONFLICT）。
- 确认前提（全部失败关闭）：邀请存在且 INVITED；确认者为本人在册 ACTIVE MINIAPP USER；`UserPhoneVerificationApi` 核对邀请手机号与登录身份账号手机号一致（不一致按不存在处理，404 防枚举）；账号非 OWNER 本人；`merchant_member(merchant_id,user_id)` 不存在（已绑定即 CONFLICT，换绑属 D3）；商家非 CANCELED、入驻 APPROVED、协议 SIGNED；门店属于该商家。确认同事务完成上述复核、关系创建、动作复制、审计与幂等回执。
- 动作门协同（D4 已批机制）：`grantActions` / `revokeStoreGrant` / `disableMember` / `enableMember` / `confirmInvitation` 在单个 READ_COMMITTED 事务内先 `ScheduleCapacityGuardApi.acquire`（共享门店 guard）再对 member/grant 行 `FOR UPDATE` 锁定重验后提交；在途核销动作门（52 号 `requireStaffAction` 锁内重验）与撤权先提交者胜，后提交者在锁内重验失败即回滚。invite / cancelInvitation 不触碰成员/授予关系，不取 guard。
- 幂等：全部命令按 [23 号](23-公共接口与幂等契约补充-v0.1.md)走 `merchant_command_idempotency`（canonical 参数含命令名与全部分 Scoped id；姓名/手机号以既有 ProtectedValuePort 等值 token 参与，明文不入 canonical 字节）；成功回执重放前重验 OWNER 准入/（确认命令）当前关系，授权已失效的重放一律拒绝。语义冲突（重复确认、确认已撤销邀请、重复撤权等）为 CONFLICT，不产生第二条审计。
- 审计：七类命令成功各写一行 `merchant_member_audit`，与业务写同事务；request_key 唯一使重放不产生新审计行。

## 3. 内部命令 API（pet-merchant-api `command`）

实现：`MerchantStaffMemberApiImpl` → `MerchantStaffMemberService`。operator 恒为真实 MINIAPP USER（HTTP10 §1）；OWNER 命令准入沿 35 号 `MerchantStaffService` 模式：`selectOwnedScope`（merchant.owner_user_id = actor）→ 商家与门店 ACTIVE → 入驻 APPROVED → 协议 SIGNED，执行事务内以 `lockOwnedScope` 重验。

| 方法 | 输入 | 成功 | 失败关闭 |
|---|---|---|---|
| `inviteMember` | merchantId/storeId/phone/memberName/actions/context | `MerchantStaffMemberCommandResult`（邀请投影，created/replayed） | 非 OWNER 403；门店非经营 409；同商家同手机号已有 INVITED 409；动作码非法或超出 V1 目录 400 |
| `cancelInvitation` | +invitationId/expectedVersion/context | 邀请投影（CANCELED） | 非 INVITED 409；版本冲突 409 |
| `disableMember` / `enableMember` | +memberId/expectedVersion/context | 成员投影 | 成员不存在 404；状态不匹配 409；版本冲突 409 |
| `grantActions` | +memberId/actions(list)/expectedVersion/context | 成员投影（新动作集） | 动作码非法/空集/超出 V1 目录 400；授予 REVOKED 409（D3）；跨店授予 409 |
| `revokeStoreGrant` | +memberId/expectedVersion/context | 成员投影（grant REVOKED） | 授予不存在/已 REVOKED 409 |
| `confirmInvitation` | invitationId/context | 成员投影（created=true） | 见 §2 确认前提；手机号不一致或邀请不存在 404；其余 403/409 |

动作目录（D2 已批最小集，本契约冻结为常量）：`merchant.order.verify`（48 号核销语义）。`merchant.order.read` 与订单处理权不随本批授予；目录扩展须修订本契约。

## 4. 查询与 HTTP

- OWNER 查询（X-Request-Id 不适用，GET 幂等只读）：`listMembers(merchantId,storeId,page,pageSize)` 返回成员/授予状态、动作集、member/grant 版本、脱敏手机号与姓名（姓名/手机号来自其 CONFIRMED 邀请行，52 号三表不存姓名手机号）；`listInvitations(merchantId,storeId,page,pageSize)` 返回邀请状态机行。二者先过 OWNER 准入。
- OWNER HTTP：`GET/POST /api/v1/merchant/staff-members`，`POST /api/v1/merchant/staff-members/invitations/{invitationId}/cancel`，`POST /api/v1/merchant/staff-members/{memberId}/{disable|enable|grant-actions|revoke-store}`；全部 `X-Request-Id` + JSON 严格体。
- 员工 HTTP：`GET /api/v1/c/staff/invitations/{invitationId}`（确认页展示：商家名/门店名/被邀姓名/动作集/邀请状态；仅当会话账号手机号与邀请手机号一致时返回，否则 404 防枚举）；`POST /api/v1/c/staff/invitations/{invitationId}/confirm`（同 §3 confirmInvitation，X-Request-Id 必带）。

## 5. 验收状态

pet-merchant-biz 真实 MySQL 隔离测试（`MerchantStaffMemberBindingMySqlTest`，STAFFB→MER001→AUTH 环境前缀回退链）覆盖：邀请创建与待邀唯一性、撤销终局、确认创建 member+grant+action 与审计、重复确认/撤销后确认/换绑冲突、OWNER 本人手机号确认拒绝、停用→动作门 403→恢复、授予整体替换与版本递增、整店撤权终局与动作门 404、并发确认竞争（两事务串行化后一胜一 409）、重放幂等回执。单元测试覆盖邀请状态机纯语义。开关关闭时无任何 bean/路由装配（52 号读侧内核不受影响）。
