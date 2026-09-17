# MER-001 S2 剩余依赖可执行交接

状态：`IMPLEMENTATION_HANDOFF / NO_NEW_CONTRACT`
基线：`0ade8bc8d0deec77f6719d031629102a959511c0`（PR50 契约已合入）
适用范围：MER-001 既有 Issue 内的商家域基础、准入事实、成员身份和员工停用联动。本文不新增业务 Issue，不批准新产品规则，也不把提议的方法或字段提升为权威 Contract。

权威依据按 `AGENTS.md` 顺序执行：SSOT → 最终 PRD/25号人工裁决 → `docs/04-api/27-Merchant-Domain-Contract-v0.1.md` → `docs/03-database/27-Merchant-Domain-Storage-v0.1.md` → 07/10/11/12号契约与 Schema06。已批准的电子协议换版、无外部签约 Provider、下线保留存量履约/退款售后不再请求用户确认；冻结写动作仍待原 Owner 裁决。

## 1. 当前可执行结论

1. `merchant`、`merchant_store`、`merchant_staff` 足以独立交付 `getStore`、`getStaff` 的只读基础，但只能证明实体、状态和归属，不能证明审核通过、已签协议、登录成员关系或核销授权。
2. `checkOrderEligibility` 不能从 `merchant.status=ACTIVE` 或 `provider_merchant_no` 推断结果。真实成功必须同时读取商家、门店、真实申请审核事实和真实协议同意事实；缺审核记录、未知枚举、损坏来源为 503，而不是 `acceptsNewOrders=false`。
3. `QueryContext(traceId, operatorType, operatorId)` 的字段本身不能由客户端自报来证明授权。本轮明确派发的是 **USER-owner 只读切片**：仅接受上游可信 AUTH 适配器从当前会话构造的 Context，MER 再以实际 `merchant.owner_user_id` 和门店/员工归属复核目标；当前没有 HTTP/boot 自动装配，因此这条有限调用约定不等于已交付端到端鉴权。未来 ORDER/SYSTEM 等调用者不能冒用 USER-owner 路径，也不能直接绕过 owner 校验，须另行同步调用者身份与资源作用域 Contract。
4. `merchant_staff` 是服务岗位，`merchant_member` 是登录成员，二者不能通过手机号自动合并。OWNER 也不能为了满足核销表的 `operator_staff_id NOT NULL` 被虚构成一条 staff 记录。
5. `/merchant/staff/{staffId}/disable` 的已批语义仅把 `serviceEnabled` 改为 false，但正式契约同时要求有在途指派时拦截，并与新指派互斥。一次 `hasAssignments` 查询不能消除检查后新增指派的竞态。

## 2. 依赖交接表

“提议接口”列仅用于原 Contract Owner 起草增量契约；在 07/27号契约或对应 Owner 契约同步前，不得作为生产接口实现依据。

| 依赖 / 原 Owner | 当前真实接口或存储 | 缺失的精确字段或方法 | 不依赖缺口即可推进的范围 | 解除证据（必须可复核） |
|---|---|---|---|---|
| 申请与审核事实；业务事实 Owner：MER，审核身份/动作 Owner：ADM | Schema06 无申请表；10号仅有 `CurrentSession.merchantEntry`/准入投影；27号要求 `application.status`；ADM 权限目录已有 `merchant.application.read/decide` 语义，但无正式申请审核 HTTP/Command | 存储至少需 `applicationId, ownerUserId, merchantId, status(DRAFT/REVIEWING/APPROVED/REJECTED), version, reviewedAt, reviewAuditRef`；审核动作还需真实 `reviewedByOperatorId`、提交版本/决定与审计引用的一致关系。仍缺申请材料、主体唯一键、驳回重提、审核中只读、城市/定位/证照私有资产的正式 Schema 和迁移；缺 `getApplicationEligibilityFact(merchantId)` 或等价的 MER 内部原子读取；缺 ADM 决定命令的 requestId、expectedVersion、reason/结果与数据范围契约 | 可完成既有三表查询及归属校验；可定义 MER 内部端口，但不得用内存/fixture/手工插入 `APPROVED` 作为生产来源，不得称入驻完成 | 原 Owner 同步申请聚合 Schema/迁移和申请提交→获权运营决定→审计的真实链路；MySQL 用例证明未经决定不能出现 APPROVED、决定审计可追溯、重复/并发决定按 requestId/CAS 收敛；`checkOrderEligibility` 从该真实记录读取，缺记录与未知值返回 503 |
| 电子协议签署来源；Owner：MER，发布操作者身份：ADM | 25号裁决和27号/Storage27已批准端内电子协议；Schema06 的 `provider_merchant_no` 不是来源；`merchant_agreement_version/current/acceptance` 仅为已批存储设计，尚无 DDL/迁移/初始化/发布实现 | 缺三表可执行 DDL、索引/外键或等价约束、迁移号；缺当前版本发布命令及真实 `publishedByOperatorId` 来源；缺 `agreement acceptance` 原子读。资格读取必须判定“存在真实有效 acceptance 即 SIGNED”，不能只连接当前发布版本，因为 V1 不强制重签。首次同意才锁当前发布指针并校验 version/hash | 已批协议 GET/consent 的 DTO、校验与幂等代码可在端口边界内准备；不得内置假协议、写虚构 provider 号或用空值推断已签 | MER DDL/迁移和发布来源落地；MySQL 证明版本内容不可覆盖、首次同意留存版本/hash/user/time、换版后旧商家仍 SIGNED、新申请人签当前版、无已发布内容为 503；无任何外部 Provider mock 参与成功路径 |
| 成员绑定与门店授予；事实 Owner：MER，可信用户/会话与动作组合：AUTH | OpenAPI11 已有 `GET /c/auth/merchant-memberships` 和 `GET /merchant/auth/admission`，均标注未实现；27号给出 `MerchantMembershipQueryApi.listForUser/getFacts` 语义；Storage27 给出 `merchant_member`、`merchant_member_store_grant`，但未交付 DDL。动作授予关系只描述 `(member_id,store_id,action_code)` 唯一，未给正式表名和完整列 | 缺用户同意绑定/身份核实流程；缺 member/grant/action-grant DDL、状态/CAS/审计和迁移；缺 `getFacts` 的正式 Java DTO（尤其实际 `staffId`、动作集合、版本组成的精确 wire/Java 类型）；缺 AUTH 只从当前会话调用 `listForUser(userId,...)` 的可信适配器；缺撤权与在途写最终授权复核的可执行接口 | OWNER 可由 `merchant.owner_user_id` 只读识别；成员接口可建纯 API 类型候选但不能注册可放行的生产 Adapter；员工档案查询不得返回登录绑定信息 | 真实用户证明→成员建立→门店授予→动作授予/撤回全链路落库并审计；AUTH 集成用例证明请求体 userId/phone/staffId 不能创建关系，DISABLED/REVOKED 不进入列表，显式访问防枚举，撤权改变 `authzVersion` 且后续最终检查拒绝 |
| 主账号和子账号核销 staff 映射；事实 Owner：MER/AUTH，执行与记录 Owner：VER/ORDER | `VerificationApi.verify(VerifyOrderCommand)` 必填 `staffId`；Schema06 `verification_record/attempt.operator_staff_id NOT NULL`；OpenAPI Membership 的 STAFF 可携带 staffId，OWNER 不携带；27号明确 OWNER 普通命令用 `OperatorType.USER`，但核销映射未解决 | 子账号需一个“当前可信 user + merchant + store → ENABLED member/grant + 非空实际 staffId + verify 动作 + versions”的原子事实；现有 `getFacts` 描述未明确返回 staffId。OWNER 核销需由原 Owner 在两种方案中正式选择并改契约：建立经过核验的真实 owner↔staff 绑定，或把核销操作者模型改为可表达 USER 与 MERCHANT_STAFF 的显式联合类型；不得直接把 userId 填入 staff 列 | STAFF 只有在真实 member_store_grant.staff_id 存在且同商家同店时，才能准备核销身份解析；OWNER 可继续执行27号已批的普通档案/协议命令，但核销保持阻塞 | 07/VER/Schema 同步同一方案；集成测试证明 OWNER 与 STAFF 都记录真实操作者、跨店/停用/撤权拒绝、手机号相同不授权、历史核销记录在撤权后仍可追溯；不出现虚拟 staff 或 userId/staffId 混写 |
| 员工停用与在途指派并发；指派/订单事实 Owner：ORDER，员工状态 Owner：MER | Schema06 有 `pet_order.service_staff_id`、`order_staff_assignment(is_current)`；现有 `OrderQueryApi` 无按 staff 查询/守卫方法；27号将 staff disable 标记 `IMPLEMENTATION_BLOCKED` | 至少缺“未完成”的权威订单状态集合/判断方法；缺按 merchant/store/staff 的当前指派检查；更关键是缺与指派命令共同遵守的互斥协议。候选协议：ORDER 提供 durable `beginStaffDisableGuard`（同事务检查无未完成指派并阻止新指派）、`complete/abortStaffDisableGuard`，MER 在 guard 有效期内 CAS `serviceEnabled=false`；ORDER 指派命令必须检查 guard，并在最终写入前重新读取 MER staff 可服务事实。需定义 token/requestId、状态、超时恢复、幂等和崩溃补偿 | 员工列表/详情、创建/改档、enable 的非联动校验可继续；disable endpoint 可完成参数/权限/幂等外围，但不得提交 `serviceEnabled=false` 的生产状态变化，也不得用一次查询测试替代互斥 | ORDER 契约登记守卫方法和未完成判定；ORDER 指派路径与 MER disable 都接入同一协议；并发 MySQL/集成用例覆盖“指派先/停用先/同时/超时/重放/崩溃恢复”，证明最终不存在已停用员工的新当前指派，已有在途时返回 409 且不清空 staffId |
| 查询调用授权；身份 Owner：AUTH，资源事实 Owner：MER | `QueryContext` 仅 `traceId/operatorType/operatorId`；07号明确 source、客户端 userId/staffId/角色不授予权限；通用 `AuthorizationQueryApi`/`TrustedPrincipalRef` 仍在 planning，未正式冻结。本轮派发仅覆盖可信 AUTH 构造 Context 后的 USER-owner 查询 | 当前有限切片可用 `context.operatorType=USER`、可信会话解析出的 operatorId，再核 `merchant.owner_user_id` 与目标归属；但尚无代码层机制让 MER 单独证明 Context 确由 AUTH 构造，也无 HTTP/boot 自动装配。未来 ORDER/SYSTEM 调用缺调用者身份、用途、允许读取范围及防枚举的正式协议；不得让 SYSTEM 直接跳过 owner，也不得把该有限 OWNER 规则误当所有内部消费者规则 | 可实现并测试 USER-owner 的 `getStore/getStaff`：可信 Context 输入、实际 owner 复核、三元归属和404防枚举。暂不承诺 ORDER/SYSTEM 通用消费，不对外暴露 HTTP | 当前切片：组件测试证明仅 USER、operatorId匹配实际 owner 才成功，非owner/错误三元组404，且无 boot/HTTP Bean 暴露。未来解锁：AUTH 当前会话适配器和调用链落地；ORDER/SYSTEM 等每类消费者的可信主体与资源作用域 Contract 同步，并有越权/冒用 SYSTEM/依赖失败测试 |
| 门店/资格 snapshot 一致性；Owner：MER | `getStore` 返回 merchant+store 字段；`checkOrderEligibility` 明确要求同一一致性快照；当前无 MER Mapper 实现 | `getStore` 应以单条 join 或明确只读事务读取 merchant/store，避免分别 autocommit 得到混合版本。`checkOrderEligibility` 必须在一个数据库一致性快照内读取 merchant/store/application/acceptance；若未来事实跨库，现契约不足，必须回 Contract Owner，不得用顺序 HTTP 调用拼成“同一快照” | 既有三表只读 Mapper 可用单 SQL join 落地；DTO 均做防御性不可变拷贝，ID 数值语义、坐标范围、手机号掩码和未知枚举失败关闭 | 并发测试在状态/version 更新时只观察到完整旧快照或完整新快照；不得观察到新 store + 旧 merchant/application/signing 的混合组合；执行计划与事务边界写入测试证据 |

## 3. 最小可落地下一切片

### 3.1 立即合入候选：`MER-S2-READ-FOUNDATION`

该切片只依赖 Schema06 已存在的三张商家表，适合当前 MER 后端继续实施；本轮能力边界固定为由可信 AUTH 适配器构造 Context 的 USER-owner 只读组件，不包含 HTTP/boot 装配，也不承诺 ORDER/SYSTEM 消费：

- 在 `pet-merchant-api` 落地 27号已批准的 `StoreIdQuery`、`MerchantStaffQuery`、`MerchantStoreDTO`、`MerchantStaffDTO` 和 `MerchantQueryApi` 既有方法签名；ID 在 API 层为 String，在持久化层解析为正 Long。
- 在 `pet-merchant-biz` 仅用本模块 MyBatis XML 查询；要求 `operatorType=USER`，以可信 Context 的 operatorId 对照实际 `merchant.owner_user_id`；`getStore` 用 merchant+store 单 statement snapshot，`getStaff` 同时核对 merchantId/storeId/staffId 三元归属。
- 不存在和归属不符统一走防枚举 not-found；未知状态、非法坐标或无法构造契约 DTO 时失败关闭，不返回猜测值；手机号只返回掩码。
- 为隔离 MySQL 补实体存在、越权归属、>2^53 ID、坐标边界、nullable phone、未知枚举和快照一致性测试。
- `checkOrderEligibility` 的接口类型可以随 API 编译，但在真实 application/acceptance 来源就绪前不得注册会返回业务布尔值的推断实现。若当前 Bean 装配必须实现该方法，应显式抛出可映射为 `COMMON_DEPENDENCY_UNAVAILABLE` 的领域依赖异常，并以测试证明没有 ACTIVE/provider fallback；这只是暂时失败关闭，不算 S2 资格完成。

这一切片的完成证据只能写“商家/门店/员工只读基础完成”，不能写“新单资格、工作台准入或 MER-001 完成”。

### 3.2 随后最小解除序列

1. **MER+ADM 先补真实申请审核闭环**：不是只建 status 表；需提交申请材料版本、获权运营决定和审计引用能形成真实 APPROVED。完成前资格查询继续 503。
2. **MER 补协议三表 DDL、发布来源和首次同意**：完全使用已批准电子协议方案，无需用户再次确认协议换版，也无需任何外部签约 Provider。资格读取接受任一真实有效历史同意记录。
3. **MER+AUTH 补成员绑定和动作授予**：先完成真实用户同意/核验，再开放 STAFF 工作台；不通过 phone 自动绑定。
4. **VER/ORDER+MER 决定核销操作者模型并补员工停用守卫**：两项均涉及其他域写入与并发，不能由 MER 单边实现。

前两项同时具备后，`checkOrderEligibility` 才能从 503 切换为正式五字段结果；第三项解除工作台子账号；第四项解除主账号核销和 staff disable。它们互不应以 fixture 或“测试已通过”提前宣称解除。

## 4. 后端实现审查清单

- [ ] 没有把 `merchant.status=ACTIVE` 当作审核通过或签约成功。
- [ ] 没有读取 `provider_merchant_no` 作为电子协议事实。
- [ ] 已签判断不要求 acceptance 指向当前发布版本。
- [ ] 没有通过手机号把 user/member/staff 自动绑定。
- [ ] OWNER 普通命令使用真实 USER/operatorId；核销没有虚构 staffId。
- [ ] Context 只由约定的可信 AUTH 适配器从当前会话构造；本轮 USER-owner 查询仍核实际 `owner_user_id`，没有接受客户端自报 Context，也没有对 HTTP/boot 暴露。
- [ ] ORDER/SYSTEM 尚未借本切片绕过 owner；其调用者身份、用途和资源范围留待后续 Contract 交接。
- [ ] store/staff 查询由单 statement 或明确 snapshot 得到一致 merchant/store 事实。
- [ ] 缺申请记录、未知枚举、签约事实损坏返回 503，而不是正常 false；明确未通过/未签才返回正常 false。
- [ ] staff disable 在 ORDER 守卫契约和并发测试前没有生产写入。
- [ ] 冻结写动作没有因下线存量规则被统一开放或统一拒绝。

## 5. 不构成解除的证据

以下结果只能证明局部代码质量，不能解除依赖：用 fixture 构造 APPROVED/SIGNED；Mock `OrderQueryApi` 返回“无在途”；单线程先查后改；只验证 Java record/Mapper 编译；只跑 H2；通过前端隐藏按钮；把 OpenAPI 的 membership 字段当作已有成员数据；以 PR50 合入等同 DDL/迁移/生产数据已存在。
