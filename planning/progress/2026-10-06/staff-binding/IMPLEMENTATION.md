# IMPLEMENTATION — 员工邀请确认绑定流（D1-a，默认关闭）

- 分支：`codex/staff-binding-20261006`（基于 origin/develop @ 02abe89，#100~#104 已含）
- 契约：**54 号 Merchant-Staff-Binding-Contract v0.1**（52/53 已占用，自 54 顺延；已核对 `docs/04-api/` develop 目录树）
- 存储：**54 号 Merchant-Staff-Binding-Schema v0.1** + pet-boot 隔离 opt-in Flyway `db/staff-member-migration/V30__merchant_staff_binding.sql`（守卫：仅显式 `staffb001_` 库；本 PR 不运行任何迁移）
- 开关：`pet.merchant.staff-member.enabled`（默认 false，独立于 52 号 `pet.merchant.staff-identity.enabled`）

## 1. 依据引用（核实记录）

**已批 CCR**：`planning/ccr/CCR-W2-API-001/staff-identity-binding-proposal.md`（APPROVED，2026-10-05 用户逐项裁决 §5）：
- **D1＝方案 a 邀请-确认两步**：OWNER 发起邀请，员工本人以 MINIAPP 会话确认后绑定生效；确认同事务创建 member(ENABLED)+grant+动作行。
- **D2＝V1 最小动作目录**：店员仅核销一个动作（动作码按 48 号核销语义，本批冻结 `merchant.order.verify`）；订单处理权不授予店员。
- **D4＝机制已批**：撤权与在途命令共用共享门店 guard 锁序 + FOR UPDATE 锁内重验 + 失败关闭。
- **D5＝门店下线失败关闭**（52 号内核保持；本批不动）。
- **D3＝保留待裁决**：撤销/换绑/恢复语义未批——本批 cancel 与 revoke-store 实现为终局、无成员 REVOKED 状态、撤权后不得重新授予（测试覆盖）。

**PRD 商家端**（`docs/01-prd/03-PRD-商家端-V1.0-最终基线.docx`，unzip+grep 提取）：
- §5.2 登录与身份切换（段落 158-159）：“每次进入工作台前系统校验最新权限状态，包括入驻审核状态、签约状态、门店冻结状态与子账号启用状态。”“支持商家主账号为门店开通子账号，子账号登录只可见被授权范围内的功能。”
- §5.6 员工管理（段落 818/820/825/829）：“员工账号管理：主账号开通、停用子账号，分配角色，设置可访问的门店范围。”“核销授权：指定哪些员工可执行平台订单核销……授权与回收均需留痕。”“核销权限必须与员工账号绑定，禁止使用共享账号核销；授权记录需可追溯。”“停用操作需二次确认”（本批页面停用走 showModal 二次确认）。
- §5.8 平台订单核销（段落 1068）：“核销必须由已授权商家员工操作，且核销人所属门店须与订单门店一致。”（52 号动作门语义，本批授予/撤权与之对齐）

**既有实现核实**：52 号契约/三表 DDL/`MerchantStaffIdentityQueryApi` 只读内核（`MerchantStaffIdentityApiImpl`/`MerchantStaffIdentityService`，动作门 `requireStaffAction` FOR UPDATE 失败关闭）develop 已合入，本批不改动其文件；35 号 OWNER 准入/23 号幂等/35 号审计模式沿 `MerchantStaffService`/`MerchantAgreementStore`/`merchant_staff_audit`。

## 2. 实现范围

### 后端
- pet-user-api：新增 `UserPhoneVerificationApi.hasVerifiedPhone(userId, phone)`；pet-user-biz `UserPhoneVerificationApiImpl` 实现（账号手机号即 S9 微信 getPhoneNumber 一次性 code 兑换事实，`UserAuthService.exchangePhone`；明文手机号不出用户模块；非 ACTIVE/无手机号/读取失败一律 false 失败关闭）。
- pet-merchant-api：`MerchantStaffMemberCommandApi`（invite/cancelInvitation/disableMember/enableMember/grantActions/revokeStoreGrant/confirmInvitation）+ `MerchantStaffMemberManagementQueryApi`（listMembers/listInvitations/getMyInvitation）+ 命令/查询/DTO records。
- pet-merchant-biz：`MerchantStaffMemberService`/`MerchantStaffMemberStore`/`MerchantStaffMemberMapper(+XML)`/实体；OWNER 命令沿 35 号模式（`selectOwnedScope`→ACTIVE→APPROVED→SIGNED，执行事务内 `lockOwnedScope` 重验，23 号 `merchant_command_idempotency` 绑定 + CommitUnknown 恢复 + 回执重放前重验）；D4 命令在单事务内 guard.acquire→merchant/store→member/grant FOR UPDATE；审计 `merchant_member_audit` 与业务写同事务、request_key 唯一。
- 确认通道（D1-a 核心）：`confirmInvitation` 事务内：锁邀请行→INVITED 校验→`StaffLoginPhonePort.matchesSessionUserPhone`（boot 侧接 `UserPhoneVerificationApi`）核对邀请手机号与登录身份手机号，不一致 404 防枚举→锁 merchant→OWNER 本人手机号拒绝→CANCELED 商家拒绝→APPROVED+SIGNED 复核→(merchant,user) 已绑定 CONFLICT（D3）→guard→建 member(ENABLED)+grant(ENABLED)+复制邀请动作行→邀请 CONFIRMED（pending_marker=CAST(id AS CHAR) 释放唯一待邀位）→审计+回执。
- pet-boot：`MerchantStaffMemberConfiguration`（默认关；guard bean 直连——52 号先例，要求 `pet.schedule.protection.enabled`）；OWNER HTTP `/api/v1/merchant/staff-members/**`（`MerchantStaffMemberController`，严格 JSON 体、X-Request-Id）；员工 HTTP `/api/v1/c/staff/invitations/{id}`（读+confirm，`CStaffInvitationController`，要求 auth.c.enabled）。
- application.yml：`pet.merchant.staff-member.enabled=${STAFF_MEMBER_ENABLED:false}`。

### 前端（frontend-miniapp，M 端）
- `src/merchant/members/{model,repository,controller}.ts`：严格 exact-key 解码（masked phone、动作码词法、REVOKED grant 零动作不变式）、`MembersController`（双列表、行内更新、CONFLICT→刷新提示语）、`RealMembersRepository`（ConsumerApi.write 每操作 slot 幂等）。
- `src/merchant/pages/members/{index.tsx,index.config.ts,page.css}` + `assets/nav-back@2x.png`：设计源 merchant frame `12:6591`「首页-员工管理」（402x898，取自 figma-map 已登记 key Usvn3d6UCVCAlDxou5KAK8，token 走 `C:/Users/Administrator/.zcode/figma-token.txt`，X-Figma-Token 头）——header 86 #FFF6E5/正文 #F0FBFF/卡片 371x88 白底 #C0ECFF 描边/头像圈 46 #C0ECFF/状态点 #22C55E·#D1D5DB + 文字 #16A34A·#9CA3AF/角色 pill #C0ECFF；成员列表（启停，停用二次确认）、邀请记录（撤销）、邀请表单（手机号+姓名，V1 目录服务端钉死不暴露动作选择）。工作台 `workspace/index.tsx` 增加「成员管理」入口（openMembers，沿 openServices 的 handoffToChild 纪律）。
- **WXSS 铁律**：状态样式一律 className 显式变体（`mmb-member-state-enabled/-disabled`、`mmb-inv-tag-invited/-canceled/-confirmed`、`.mmb-tab-active`），无动态 data-* 属性选择器（变体规则置于基规则后，`.mmb-page` 复合提升优先级压过 button 重置）。
- app.config.ts：merchant 分包 pages 数组内**一行紧凑追加** `'pages/members/index'`（注释标明 contract 54），便于并行 M 端页面 PR rebase。

## 3. 验证

- 门禁命令（本机，C 盘零大产物：`-Dmaven.repo.local=D:\m2-sch004\repository -Djava.io.tmpdir=D:\Temp\staffb-tmp`，JAVA_HOME=JBR 21.0.10；MySQL 本机 3306，STAFFB/MER001/AUTH/USR001/PLAT003/PLAT002_ID 全部映射，库名随机、用后 DROP）：
  - `mvn test -pl pet-merchant-api,pet-merchant-biz,pet-user-api,pet-user-biz -am`：**BUILD SUCCESS**。pet-merchant-biz **90/90**（含新增 `MerchantStaffMemberBindingMySqlTest` **8/8** 真实 MySQL：邀请唯一/终局、确认建关系+52 读侧点亮+审计、重复确认/撤销后确认/换绑/OWNER 本人确认拒绝、停用↔恢复+动作门 403、授予整体替换+版本递增、整店撤权终局+动作门 404、双线程并发确认恰一胜、OWNER 准入/输入校验失败关闭、幂等重放回当前投影+同键异参冲突）；pet-user-biz 90/90；pet-user-api 2/2；pet-merchant-api 2/2；上游 pet-common 80/80、pet-event-core 10/10。
  - pet-boot `compile`：BUILD SUCCESS（新配置/控制器装配编译）。
  - `mvn test -pl pet-architecture-test`（SNAPSHOT 先 install 到 D 仓库后单模块跑）：**31/31**（ArchitectureRulesTest 7/7 全套 ARCH 规则 + ArchitectureRulesFixtureTest 24/24），BUILD SUCCESS。
  - 前端四查：`npm run typecheck` 通过；`npm test` **189/189**（新增 members.test.ts 4 例：严格解码/类名变体/输入校验/控制器流与冲突提示）；`npm run build:weapp` 成功（merchant/pages/members 四件产物生成）；`npm run check:package` 通过（分包注册清单与页面产物断言已随新路由更新）。
- 本地工程注意：worktree `frontend-miniapp/node_modules` 以 junction 复用主目录同名目录（package-lock 逐字节一致）；`project.config.json` 已按纪律复制真实 appid 且**不提交**。

## 4. 边界（本批不做，未声称完成）

- 成员 REVOKED 命令、重新邀请已撤销/撤权成员、换绑、恢复整店撤权（D3 待裁决；存储层 pending_marker/状态机使上述操作在应用层 CONFLICT）。
- grant.staff_id 展示引用回填（本批恒 NULL，随 48 号 K1 修订裁决）。
- 核销完成内核接入 `requireStaffAction`（STAFF-C 另立契约修订）；本批仅保证授予/停用/撤权与 52 号读侧/动作门的语义一致（测试证明）。
- 站内通知投递（NTF-001）；员工端确认页面（CCR §3.3 随 M 端工作台员工入口另行交付——本批交付服务端确认通道 + M 端 OWNER 管理页）。
- 邀请有效期/次数上限（CCR D1 待明确子项）：本批不实现自动过期，仅可被 OWNER 撤销。

## 5. 设计缺稿登记

- 邀请表单（手机号+姓名输入）与邀请记录行在 frame 12:6591 无原稿（该 frame 仅有员工卡片列表 + 底部 tab）；沿用同 frame 卡片设计语言实现，不属 1:1 还原范围。员工管理 frame 的底部 tab 栏（首页/订单/核销/消息/我的）为工作台一级页设计，本页为工作台子页（沿服务管理页先例）不含 tab 栏。

## 6. 遗留风险

- guard bean 直连意味着 `pet.merchant.staff-member.enabled=true` 而 `pet.schedule.protection.enabled=false` 时 boot 启动失败（52 号内核同款先例）；是有意的失败关闭，还是改为 ObjectProvider 空实现拒绝命令，待运维口径。
- 邀请行保留已确认/已撤销手机号明文（确认比对需要，沿 merchant_staff.phone 先例）；投影全部脱敏，审计零明文。
- 双确认并发在 MySQL 层由邀请行锁串行化（后到者 CONFLICT），真实并发测试覆盖（两线程 latch 竞争，恰一胜）。
