# STAFF 切片实施记录：商家员工独立身份与受控动作（读侧内核）

日期：2026-10-02。分支 `codex/staff-identity-20261002`（基于 origin/develop e3b846e，PR#98 合并基线）。PR 草稿：`feat(staff): 商家员工独立身份与受控动作切片`。

## 1. 范围结论

WORK_STATE/BLOCKED_QUEUE 的未交付项"STAFF独立身份/动作"（MER-001 剩余：成员/核销映射/在途守卫/冻结写动作）**未**被既有代码覆盖：develop 上员工仅有档案管理（35 号契约五接口）与 OWNER 核销内核（48 号 K1 固定 `operatorStaffId=null`），`MerchantStaffService` 注释即"员工手机号不授予登录身份"；`MerchantOrderAuthorityApiImpl` 仅有 OWNER/SYSTEM 分支；[准备文档](../../ccr/CCR-W2-API-001/staff-verification-authority-preparation.md) 盘点的 member/grant 持久化缺口属实。

本切片交付 **STAFF-A 契约包 + 已批准设计的读侧身份内核**（默认关闭），不发明产品规则：

- `docs/03-database/52-Merchant-Staff-Identity-Schema-v0.1.sql`：`merchant_member` / `merchant_member_store_grant` / `merchant_member_store_action` 正式 DDL，字段与唯一关系完全按 [27号存储设计 §2](../../../docs/03-database/27-Merchant-Domain-Storage-v0.1.md) 已批准内容（member 状态 ENABLED/DISABLED/REVOKED、grant 状态 ENABLED/REVOKED 为实现候选并在契约中声明；不隐含全权限；OWNER 不落成员行）。
- `docs/04-api/52-Merchant-Staff-Identity-Contract-v0.1.md`：内部解析接口（列表/单店事实/动作门）、失败关闭错误映射（无关系/撤销 404、停用/未授动作 403、事实损坏 503）、authzVersion 固定字段标签。
- pet-merchant-api：`MerchantStaffIdentityQueryApi` + 3 查询记录 + 3 DTO（staffId 仅 STAFF 展示返回，OWNER 不虚构）。
- pet-merchant-biz：`MerchantStaffIdentityStore`（只读 + caller 事务加入）、`MerchantStaffIdentityService`、`MerchantStaffIdentityApiImpl`、`MerchantStaffIdentityMapper.xml`（全部 SQL 在 Mapper XML，动作门 `FOR UPDATE` 锁内重验，与共享门店 guard 协同）。
- pet-boot：`MerchantStaffIdentityConfiguration`，`pet.merchant.staff-identity.enabled` **默认 false**；无 HTTP、无前端改动。

**不包含**（见 §5 阻塞）：成员绑定命令与授予/撤回写命令、公开 HTTP、动作目录、STAFF 核销身份映射、员工档案 disable 在途守卫、FROZEN 写例外。

## 2. PRD/SSOT 原文依据清单

| 出处 | 原文（摘） | 本切片用途 |
|---|---|---|
| 商家 PRD（docs/01-prd/03-PRD-商家端-V1.0-最终基线.docx）§5.2 登录与身份切换 | "支持商家主账号为门店开通子账号，子账号登录只可见被授权范围内的功能。"；"每次进入工作台前系统校验最新权限状态，包括入驻审核状态、签约状态、门店冻结状态与子账号启用状态。" | 子账号登录主体存在；准入四态校验进入 facts 解析 |
| 同上 §5.2 字段表 | 账号角色=主账号/门店店长/核销员/排期负责人；子账号状态=已启用/已停用，"停用时保留历史操作记录" | member 状态机（ENABLED/DISABLED/REVOKED）；停用不删关系 |
| 同上 §5.6 员工管理 | "核销权限必须与员工账号绑定，禁止使用共享账号核销；授权记录需可追溯。"；员工账号="子账号登录标识 唯一，启用后可登录商家端"；"子账号登录时不得查看或修改主账号的结算与签约信息。" | 动作与成员绑定、禁止共享账号 → (member,store,action) 授予；授权留痕 → 写命令属绑定切片（待裁决） |
| 同上 §5.6 角色权限矩阵 | 矩阵仅两行：主账号（负责人）与核销员 | 本切片不定动作目录；店长/排期负责人未细化，列入待裁决 |
| 同上 §5.8 平台订单核销 | "核销必须由已授权商家员工操作，且核销人所属门店须与订单门店一致。"；"展示核销结果、核销时间、核销操作人和关联订单。" | 核销动作须同店授予（动作门含 merchant/store 归属校验）；STAFF 核销映射待契约修订（48号K1 现禁用） |
| SSOT（docs/00-ssot/01-SSOT-宠物平台V1.0-最终业务基线.md）§30 员工基础管理读写门槛 | "员工停用、离职、删除继续等待预约/订单保护，不通过update改变这些状态。员工手机号不是登录绑定。" | 档案≠登录身份；phone/roleName/service_enabled 不参与授权 |
| SSOT §37 主账号核销与同事务完成 | "第一切片只接受真实有效会话的商家 OWNER…不得将 userId 冒充员工 ID。STAFF 登录成员绑定和门店动作授予后续交付。"；"没有可核实身份映射的历史核销行禁止猜测回填。" | 本切片=该"后续交付"的准备+读侧内核；pet-verification-biz 未改动，不猜测回填 |
| SSOT §25 / docs/01-prd/24-取消MFA人工裁决补充-v1.0.md | 取消额外 MFA 门禁；登录、手机号校验与 RBAC 不取消 | 绑定流程候选不引入 MFA（CCR 草案 D1） |
| 27号契约 §5（docs/04-api/27-Merchant-Domain-Contract-v0.1.md） | "OWNER依merchant.owner_user_id；STAFF依显式成员表和门店授予"；authzVersion 标签；404/DENIED/LIMITED 矩阵 | facts/list 接口形状与错误映射的直接依据 |
| 27号存储设计 §2 | member/grant/动作关联字段与唯一关系；"创建成员需要'该真实用户同意绑定/身份核实'的完整已批流程，目前尚缺" | 52 号 DDL 逐字对应；绑定流程缺口=待裁决 D1 |
| HTTP10「商家工作台选择与准入」§1–3（docs/04-api/10-HTTP-API-Contract-v0.4.md） | C/M 同一会话；"不能信任客户端staffId"；Membership membershipKind=OWNER/STAFF、staffId 仅 STAFF 展示；STAFF_DISABLED/OFFLINE/FROZEN 准入矩阵 | 解析口径（会话 USER、staffId 展示、防枚举 404、FROZEN/OFFLINE 失败关闭） |
| 48号契约 K1（docs/04-api/48-Verification-Completion-Contract-v0.1.md） | "STAFF 仍不可用，不从服务人员档案推导权限。" | 核销模块保持不变的理由 |
| [员工核销权威准备](../../ccr/CCR-W2-API-001/staff-verification-authority-preparation.md)（2026-09-30，只读盘点） | STAFF-A/B/C 切分与 STA-01..12 验收清单 | 本切片=STAFF-A+读侧机制部分；STA 项覆盖情况见契约 §5 |

## 3. 实现与测试证据

代码：

- `backend/pet-merchant-api/src/main/java/com/petplatform/merchant/api/query/MerchantStaffIdentityQueryApi.java`（+ MerchantStaffMembershipQuery / MerchantStaffIdentityFactsQuery / MerchantStaffActionQuery）
- `backend/pet-merchant-api/src/main/java/com/petplatform/merchant/api/dto/MerchantStaffMembershipDTO.java` / `MerchantStaffMembershipPageDTO.java` / `MerchantStaffIdentityFactsDTO.java`
- `backend/pet-merchant-biz/src/main/java/com/petplatform/merchant/biz/application/MerchantStaffIdentityService.java`、`apiimpl/MerchantStaffIdentityApiImpl.java`、`infrastructure/persistence/MerchantStaffIdentityStore.java`、`mapper/MerchantStaffIdentityMapper.java`、`entity/MerchantMemberEntity|MerchantMemberGrantEntity|MerchantStaffIdentityScopeEntity|MerchantStaffMembershipRowEntity.java`
- `backend/pet-merchant-biz/src/main/resources/mapper/MerchantStaffIdentityMapper.xml`
- `backend/pet-boot/src/main/java/com/petplatform/boot/config/MerchantStaffIdentityConfiguration.java`、`application.yml`（`pet.merchant.staff-identity.enabled=false`）

测试（真实 MySQL 8.4，隔离随机库，seed 仅为测试夹具、不是绑定证据）：

- `MerchantStaffIdentityApiMySqlTest`（5/5 PASS）：facts 投影与 grant 版本驱动的 authzVersion 变化；memberships 过滤分页（DISABLED/REVOKED 成员、他人成员不出现在列表，merchant/store 升序）；动作门成功路径 + 未授动作 403 + 错店 404 + 跨商家 404；DISABLED 403 / REVOKED 404 / grant REVOKED 404 / FROZEN 403 / OFFLINE 403 / 申请未 APPROVED 403 / 协议未 SIGNED 403 / 非 USER 403 / 无会话 401 / 非法动作码 400；grant.staffId 跨店引用损坏 503 失败关闭。
- `MerchantDtoSerializationTest` 增加 STAFF DTO 断言（ID String、checkedAt ISO 字符串）。

门禁结果（本机 JDK21（IDEA jbr 21.0.10）/ Maven 3.9.12 / MySQL 8.4.9 本机实例，隔离随机 schema）：

| 门禁 | 结果 |
|---|---|
| `mvn -pl pet-merchant-biz -am test` | **BUILD SUCCESS**，174 项测试 0 失败/错误/跳过：pet-common 80、pet-event-core 10、pet-merchant-api 2、pet-merchant-biz 82（含 `MerchantStaffIdentityApiMySqlTest` 5 项、既有 `MerchantStaffApiMySqlTest` 7 项回归通过） |
| `mvn -pl pet-boot -am test-compile` | PASS（pet-boot 测试运行需 Redis，见 §4 披露） |
| `python backend/tools/check-module-deps.py` | PASS：41 模块、17 个 biz POM，无 biz→biz 依赖 |
| `python backend/tools/check-persistence-style.py backend` | PASS：生产 SQL 全部在 Mapper XML（自测 `test_persistence_style.py` 退出码 0） |
| `python backend/tools/test_architecture_gates.py` | PASS：13 项 OK |
| CI（GitHub Actions 六项） | 提交后由 PR CI 验证，本切片不含合并授权 |

## 4. 环境与验证边界披露

- 本机无 Docker/Redis；pet-boot 的 HTTP/验收测试（如 `MerchantStaffHttpTest`、`CAuthHttpTest`）依赖 `AUTH_REDIS_HOST/PORT`，未在本机执行。本切片对 pet-boot 的唯一改动是默认关闭的装配类与 yml 开关，不影响既有 Bean 装配；pet-boot 测试全部通过 `test-compile`。
- 新开关默认关闭；未运行任何生产迁移；52 号 DDL 仅作为测试与后续验收输入。
- `planning/ccr/CCR-W2-API-001/staff-identity-binding-proposal.md` 为 **NOT_APPROVED 草案**，其 D1–D5 在用户裁决前不实施。

## 5. 阻塞与待裁决（PR 同步披露）

1. **D1 成员绑定流程（最高优先）**：员工本人如何认可绑定、主账号如何核实目标身份。27号存储设计明文"目前尚缺"。候选（D1-a 邀请-确认 / D1-b 登记认领）见 CCR 草案；在裁决前不存在成员创建路径，本切片的关系表只能由获批写命令填充。
2. **D2 首切片动作目录与角色矩阵**：核销动作码及"核销员是否随核销获得订单处理权"（PRD §5.6 矩阵核销员行含"订单处理"）须业务 Owner 收窄；店长/排期负责人矩阵未细化。
3. **D4 撤权与在途命令的锁序/提交判定正式冻结**：本切片动作门已实现"guard 持有事务内 FOR UPDATE 重验 + 失败关闭"的机制部分；正式协议须随绑定切片以真实 MySQL 竞争测试（STA-05）冻结。
4. **D3 换绑/重新开通/撤销恢复**、**D5 OFFLINE 存量履约动作归类**（当前动作门对 OFFLINE 一律拒绝，失败关闭）。
5. **STAFF 核销身份映射**：48号 K1 明确 STAFF 不可用；operatorType/membershipKind/operatorStaffId 与 v2 事件、存储、回执的兼容规则需契约修订（STAFF-C），本切片未触碰 pet-verification-biz。
6. **员工档案 disable 在途守卫**（35号 §6.1 IMPLEMENTATION_BLOCKED）与 **FROZEN 写动作例外**（B-FROZEN-WRITE）：既有阻塞不变。

## 6. 遗留风险

- 关系表在绑定切片交付前无业务写入方；如他人误开开关且库中无 52 号表，解析接口将 503 失败关闭（不会放行）。
- 动作门目前未接任何调用方；STAFF-C 接入核销内核时须按 D4 完成竞争测试后才能开关开放。
- memberships/admission 的 STAFF 投影接通（HTTP10 `Membership`、`facts.staffEnabled`）留待绑定切片，避免在线 OWNER 准入面依赖尚不存在的表。
