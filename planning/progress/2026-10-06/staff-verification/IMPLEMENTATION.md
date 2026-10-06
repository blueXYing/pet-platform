# 员工核销接入切片实施记录：核销动作接入员工身份与操作人归属（48 号 K1 v0.2）

日期：2026-10-06。分支 `codex/staff-verification-20261006`（基于 origin/develop 02abe89，含 #100~#104；不基于并行 #105~#107 draft）。PR 草稿：`feat(verification): 核销接入员工身份与操作人归属（默认关闭）`。

## 0. 接管说明（第二次接管）

前代理失活时留下的未提交半成品（14 个改动文件 + 1 个未跟踪测试 + 本文档）经全量审查**方向正确**（核销命令接 52 号动作门 + operatorStaffId 落库 + 契约原号修订），本接管代理补齐：门禁执行中发现并修复两处测试缺陷（§4.1），补跑全套门禁，提交推送并开草稿 PR。生产代码与契约文档除下述两处测试修复外未改动前代理实现。

## 1. 范围结论

用户已批原则「核销记录必须可追溯到店员」+ 52 号绑定 CCR §5 裁决（D2/D4/D5）。本切片 = 准备文档 §4 的 **STAFF-C**：把 develop 已合入的 52 号只读解析内核（`MerchantStaffIdentityQueryApi` 动作门）接入既有 48 号核销完成内核，并原号修订 48 号契约 K1，使 STAFF 成员核销在失败关闭的动作门之后落出可追溯到店员的核销记录。默认关闭开关 `pet.verification.staff-identity.enabled=false`。

**不包含**（阻塞清单见 §5）：成员绑定/授予/撤回写命令与 HTTP（STAFF-B，#107 并行在途，本切片仅以种子数据作反例与门禁证据）、员工端核销页面（M 端工作台另行交付）、动作目录扩展（D2：V1 店员仅 `merchant.order.verify` 一个动作）、35 号 §6.1 员工档案 disable 在途守卫、B-FROZEN-WRITE。

## 2. 48 号契约 K1 修订前后对照（原号修订，v0.2）

| 维度 | 修订前（2026-09-30 K1） | 修订后（2026-10-06 K1 v0.2） |
|---|---|---|
| 身份路径 | 仅 OWNER；STAFF 明确不可用 | OWNER 不变；新增 STAFF 路径（开关默认关），两路径互斥（52 号存储禁止伪造 OWNER 成员行） |
| STAFF 语义 | — | OWNER 校验未通过 → 52 号 `requireStaffAction` 在 guard 事务内 `FOR UPDATE` 锁定 member/grant/动作行并重验；动作仅 `merchant.order.verify`（D2）；FROZEN/OFFLINE 失败关闭无存量例外（D5）；门通过后同锁下读 grant 展示 staffId |
| 身份四元组 | operatorType=USER、membershipKind=OWNER、operatorStaffId=null、operator_id=真实 userId | OWNER 同前；STAFF：operatorType=MERCHANT_STAFF、membershipKind=STAFF、operatorStaffId=grant staffId、operator_id=operator_staff_id（SQL48 CHECK 固定）；grant 无 staffId 拒绝（可追溯到店员，PRD §5.6） |
| 真实登录用户留痕 | 五元组+attempt 记录 | 不变：`verification_credential_command(actor_type='USER',actor_id=真实 userId)` + requestId 五元组；风险计数仍记真实会话用户 |
| 鉴权链 | 会话→`requireOwner`（共享门店 guard 下） | 会话→OWNER 门或「成员动作门→核销」；命令事务、beforeCommit 复核、成功重放三处重新解析身份且必须一致；撤权后提交与撤权后重放均拒绝（STA-05） |
| 落库/审计口径 | verification_attempt/verification_record 固定 OWNER 形状 | 四元组写入 attempt/record；`order_verification_commit.operator_id` 恒为真实会话 USER id；OrderVerifiedEvent.v2 身份四元组镜像 VER 落库值 |
| markVerified | 不携带身份 | 携带 VER 解析出的 OperatorIdentity（ORDER 不自行推导、不读 VER 表）；非法形状失败关闭 |
| 事件 v2 兼容 | payload 身份字段固定 USER/OWNER/null | 旧消费者按“operatorType=USER 且 membershipKind=OWNER”过滤即保持原语义 |
| 存储结构 | SQL48（CHECK 含 STAFF 可表达结构，无写入口） | 表结构不变，仅按 K1 v0.2 开放 STAFF 写入语义（48 存储说明同步修订） |

裁决引用：[staff-identity-binding-proposal §5](../../../planning/ccr/CCR-W2-API-001/staff-identity-binding-proposal.md)（D2=店员仅核销；D4=guard 锁序+FOR UPDATE 锁内重验+先提交者胜，协议冻结随 STA-05；D5=OFFLINE 失败关闭；核销身份映射=K1 修订在员工核销接入切片实施）；SSOT §37/§30；商家 PRD §5.6（核销权限绑定员工账号、禁止共享账号）、§5.8（核销人所属门店须与订单门店一致，由既有 attemptLocation 门店一致性校验保证）。

## 3. 实现（默认关闭）

- `docs/04-api/48-Verification-Completion-Contract-v0.1.md`：K1 段原号修订 v0.2（§2 对照表全部落文）；K2/事件与迁移/边界段落同步。
- `docs/03-database/48-Verification-Completion-Storage-v0.1.md`：STAFF 行写入语义与追溯链说明；SQL48 文件不变。
- pet-order-api：`OrderVerificationCommitApi` 新增 `OperatorIdentity` 记录，`markVerified` 增加身份参数（48 号 K2 自有提交协议的契约内修订）。
- pet-order-biz：`OrderVerificationCommitApiImpl.markVerified` 校验仅 OWNER/STAFF 两种合法形状（其余失败关闭），v2 事件身份四元组改来自参数；ORDER 提交证据 operator_id 恒为真实会话 USER。
- pet-verification-api：`VerificationCommitProofApi.Proof` 扩展身份四元组字段。
- pet-verification-biz：`CredentialPorts.AttemptAuthority` 由 void 改为返回解析身份（可信适配器语义不变）；`VerificationCredentialService.attemptLocation` 返回 guard 位置+身份；`VerificationCompletionService` 身份贯穿 attempt/record 落库、Proof、receipt 校验与 beforeCommit 复核（复解析身份必须一致）；`resolved()` 仅放行两种合法形状。**biz 不互相依赖**：verification-biz 未新增对 merchant 任何依赖，身份事实全部经 pet-boot 装配的端口注入。
- pet-boot：`VerificationCredentialConfiguration` 新增 `staffAwareVerificationAuthority`（`pet.verification.staff-identity.enabled=true` 时装配；OWNER 先行、非 OWNER 走 52 号动作门+facts 读 staffId、无 staffId 拒绝）；OWNER 适配器按开关互斥装配（`matchIfMissing` 边界）；启动期互锁：staff-identity 开启而 `pet.merchant.staff-identity.enabled` 未开则启动失败。merchant-biz 内部零改动。
- 动作码常量 `merchant.order.verify` 固化于 `VerificationCredentialConfiguration.VERIFY_ACTION`（D2 批准的最小动作目录）。

## 4. 测试证据（门禁数字）

### 4.1 接管代理修复的两处测试缺陷（生产代码零改动）

1. `StaffVerificationAcceptanceTest` 事件断言用 SQL `LIKE '%"operatorType":"MERCHANT_STAFF"%'` 匹配 `integration_event_outbox.payload`，该列是 MySQL `JSON` 类型，存储时被规范化（冒号后加空格）导致恒不匹配。改为按 `event_type` 取回后用 Jackson 解析断言四个业务字段，并独立断言事件行数=1。
2. 首个用例在同一 fixture 库内先完成 STAFF 核销再 `ready()` 预订 OWNER 回归单：完成单持有 2030 年 future active claim，而 store 快照不变式（develop 既有 `OrderProtectionFactsApiImpl`，本 PR 未改）拒绝「已完成订单仍有未来活跃 claim」；同时调度不变式要求活跃预约的 claim 必须保持分钟精度且锁在原窗口内，无法通过回拨 claim 造旧。改为 STAFF 路径与 OWNER 回归各自独立 Env（同开关、同种子、隔离库）——OWNER 优先解析是 bean 级语义，同库并非其证明条件。

### 4.2 本地门禁数字（Java 21 Temurin、本机 MySQL 8.4.9 随机库用后 DROP、本机 Redis 16379（save="" appendonly no）、SERVER_PORT=0、CI 八组 MySQL env、`-Dmaven.repo.local=D:\m2-sch004\repository`）

- `python backend/tools/check-module-deps.py` → PASS（41 reactor 模块、17 biz POM，无 biz→biz）。
- `python -m unittest discover -s backend/tools -p 'test_*.py'` → OK（13 架构负例）。
- `python backend/tools/check-display-status.py` / `check-persistence-style.py` → PASS / PASS。
- 全 reactor `mvn test`（41 模块）：40 模块 SUCCESS；16 个含测试模块合计 **508 tests, 0 failures**（common 80、id-core 41、event-core 10、task-core 47、user-biz 27、merchant-api 2、merchant-biz 82、schedule-biz 45、order-biz 29、payment-biz 45、refund-biz 4、verification-biz 3、aftersale-biz 8、notification-biz 14、thirdparty-biz 30、admin-biz 41）。
- pet-boot：67 个测试类 **470 tests**；首轮仅 §4.1 两处缺陷失败，其余全绿（含 `VerificationCredentialAcceptanceTest` 23、`VerificationCompletionAcceptanceTest` 19 OWNER 回归全套——fixture 已适配新签名）。两处修复后定点重跑：`StaffVerificationAcceptanceTest` **6/6 绿**、`VerificationCredentialConfigurationTest` **10/10 绿**。
- `pet-architecture-test` 全套：`ArchitectureRulesFixtureTest` 24 + `ArchitectureRulesTest` 7 = **31/31 绿**。
- 合计 **1009 tests, 0 failures**（508 + 470 + 31）。

### 4.3 用例清单

- 模块测试 pet-boot `VerificationCredentialConfigurationTest`：开关互锁（staff-identity 开而 merchant staff-identity 未开 → 启动失败）与双开关组合装配（唯一 AttemptAuthority bean）；OWNER 默认装配与 missingKeys 失败关闭回归。
- 真实 MySQL 验收 pet-boot `StaffVerificationAcceptanceTest`（复用既有 BOOKING→AUTH 前缀回退链的隔离库，CI 以 AUTH 前缀覆盖，未硬编码新前缀；随机库用后 DROP；52 号 schema 仅测试库内应用，SQL48/SQL52 生产迁移未运行）：
  - STAFF 成功路径：核销记录/尝试落 `MERCHANT_STAFF/STAFF/staffId` 四元组、command 表留真实 USER、v2 事件（解析 JSON 断言）含 staffId、同参重放返回原回执。
  - OWNER 回归：同开关同装配下 OWNER 路径记录仍为 USER/OWNER/null（独立隔离库）。
  - 拒绝矩阵（零落库）：邀请在途无成员行 404、DISABLED 403、REVOKED 404、动作未授予 403、grant REVOKED 404、无 staffId 的 grant 403（可追溯性失败关闭）。
  - D5：门店 OFFLINE 时 STAFF 核销 403 失败关闭、同单 OWNER 仍可完成存量履约；FROZEN 时 STAFF 拒绝。
  - 撤权时序（D4/STA-05）：撤权先提交 → 核销 404 零落库；核销先提交 → 记录保留真实员工身份、撤权后同参重放被拒。
  - STA-05 真实竞争：核销命令与“guard→REVOKED”撤权事务并发（CountDownLatch 起跑），断言恰一一致结果——记录存在（先提交者胜，历史不篡改）或核销被拒零落库，撤权最终生效。
- CI 将在同 commit 上复跑全套（含依赖 Redis 的 auth/HTTP 套件）。

## 5. 阻塞与待裁决（预期项）

1. **与 #107 绑定流的集成测试归属**：本切片的 member/grant/动作数据为种子反例（STA-01 绑定证据仍属绑定切片）；真实邀请-确认流产生的关系与撤权命令接入本门后的端到端竞争（含 grant staffId 换绑 D3 待裁决项）应在 #107 或其后续切片验收。
2. **员工端核销页面归属**：M 端工作台核销入口/员工准入 UI 与 C 端展示核销操作人，属页面切片，未在本 PR。
3. **48 号其他消费方对 K1 变更的兼容确认**：v2 事件消费方（通知/评价）尚未交付；已在本契约写明兼容规则（旧消费者按 operatorType=USER 且 membershipKind=OWNER 过滤），待消费方交付时确认。
4. 既有阻塞不变：35 号 §6.1 员工档案 disable 在途守卫、B-FROZEN-WRITE（FROZEN 写例外）、D3 换绑/撤销恢复细则。
