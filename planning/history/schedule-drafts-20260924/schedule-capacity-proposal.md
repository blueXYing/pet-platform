# CCR-W2-API-001 排期域容量人员事实源契约提案（schedule-capacity）

> **⚠️ DRAFT v0.1（2026-09-24）——未获批，不得据此实现。**
> 本文档为 SCH-002 准备阶段草案（角色：排期域后端）。所有 SCH2-D1～D7 均为**建议+备选**，须经人工 Contract Owner 裁决回执（对齐 [schedule-availability-decisions.md](schedule-availability-decisions.md) 先例）后方可同步权威文档并派发实现。与已批 SCH-D1～D11 冲突之处一律以已批裁决为准。

规范版本：0.1（DRAFT），日期：2026-09-24。提出方/唯一编辑者：Backend Core（SCH-002 规范阶段）。
关联 Issue：SCH-002 / EPIC-05 / ST-SCH-01（Tests 映射 ORD-004/ORD-005/CON-001/CON-002）；上游：SCH-001（PR#77 已合入）；下游：SCH-003、TX-001、C-003、M-002。
基线：develop `0c2d7ae`；分支 `codex/sch002-prep-20260924`。
配套核验：[SCOPE-VERIFY.md](../../issues/wave-2/SCH-002-prep/SCOPE-VERIFY.md)（S-A～S-D 事实盘点）、[TEST-PLAN.md](../../issues/wave-2/SCH-002-prep/TEST-PLAN.md)（W2-SCH2-*）。

## 修订记录

| 版本 | 变更 |
|---|---|
| 0.1（2026-09-24） | 初稿，SCH2-D1～D7：可用人员定义（含排班关系三口径）、第六内部查询（MerchantStoreStaffFactsApi 明细形）、能力表语义消费、事实源故障失败关闭口径、min 公式启用与真实装配、既有 SCH-001 测试更新、OpenAPI/权威文档影响确认 |

## 人工 CTO 一页阅读指南

**用通俗话说：排期查询里"实际可预约容量=min(商家配置,可用人员)"的"可用人员"这一半，目前是个空端口——真实环境一律报"依赖不可用"。SCH-002 就是把这个空端口接上真数据：商家域补一个"这家门店有哪些在职、在岗员工"的内部查询（现有五个查询都答不了这个问题），排期域再拿这个名单去对两张排期表（谁会做这个服务、谁那个时段有排班）取交集。要裁的核心问题只有一个：**"可用"到底算不算"排了班"**——排班表和员工表今天都没有录入页面（归 SCH-004/M-002），三种选法：①算排班（没排班=0 人=全不可约，最严格，但测试播种后语义完整）；②不算排班（在职在岗就会做=偏乐观）；③没排班就报错（最保守，但等于什么都没解锁）。我推荐①，它跟已批的端口注释"整个时段可用"和"空表读出 0 是事实不是假设"两条既有裁决最一致。对外接口的字段一个都不变，纯内部接通。**

| 分工 | 内容 |
|---|---|
| 我负责 | 可用人员事实逐层盘点、第六查询形状、提供器组合语义与失败关闭、测试计划、决策点+推荐+备选 |
| 你负责 | 裁决 SCH2-D1～D7（重点 D1 排班口径、D2 查询形状、D7 状态标签）；批准后 Owner 同步 07/27/11 号再派实现 |
| 不变 | SCH-D1～D11（尤其 D6 失败关闭框架）；响应形状/错误码/开关默认关；写入方归属（SCH-004/M-002）；occupiedCount 口径；不实现员工 HTTP/写入命令 |

## 待裁决决定（SCH2-D1～D7）

1. **SCH2-D1 "可用服务人员"定义与排班关系（本提案核心）**
   - 口径①（**推荐**）：`qualifiedAvailableStaffCount(storeId, serviceId, [from,to))` = |{staff : `merchant_staff.employment_status=ACTIVE`（在职）∧ `merchant_staff.service_enabled=1`（在岗）∧ `merchant_staff.store_id=storeId`（归属门店）∧ `staff_service_capability(staff_id, service_id, status=ENABLED)`（可服务该服务）∧ `staff_availability_window` 中该 staff 的 AVAILABLE 窗口**并集完全覆盖** [from,to)（排班在场）}|。
     - 理由：(a) 已批端口 javadoc 即此语义（QualifiedStaffFactsPort.java:14-19「available over the whole [from, to) range」），口径②需改已批措辞；(b) 商家端 PRD §5.6 排班管理「按人员 × 营业时段设置可约时间」「与门店可预约时段联动」（提取文本 797/808 行）——人员可约时间是产品模型的一部分；(c) 空排班表读出 0 是"读权威表的事实"，与 D6 裁决对 occupiedCount 确立的先例一致（schedule-availability-proposal.md:33），不违反失败关闭——失败关闭针对**提供器缺席/读失败/未知状态**（503），不针对权威表的确定内容；(d) 向"不可约"方向失败，绝不虚增可约；(e) SCH-004 排班写入交付后无需复裁。
     - 覆盖判定附则（推荐）：多段 AVAILABLE 窗口**并集**覆盖即计入（分钟级排班的产品形态），CLOSED 段不构成覆盖；备选=单窗包含（SQL 更简，但把"上午+下午两段班"误判为不可用）。时间维度：不同窗口 [from,to) 覆盖判定不同，可用性**随窗口变化**（与端口签名吻合）。
   - 口径②（备选）：可用 = 在职∧在岗∧归属∧能力，**不消费排班**（排班延后至 SCH-004 届时增补）。更简，缺排班事实时也计可用；但①与已批端口语义冲突需修订，②把"无排班事实"当"排班不设限"属乐观假设，与 D6"不得拿占位冒充已核实"精神有张力。**若选②必须同步修订端口 javadoc 并登记"available=true 可能先于排班事实存在"的风险披露**。
   - 口径③（不推荐）：排班事实缺失（该店排班表无行）→ 503 失败关闭（D6 字面延伸）。与 occupiedCount=0 先例冲突（空权威表≠依赖不可用）；所有门店均无排班写入方，交付后路由仍恒 503，与 D6「启用完整可约判断须等 SCH-002 接通」预告相悖，SCH-002 无解锁实效。
   - **三口径生产行为当前一致**（窗口表亦无写入方→空 items 200），差异是语义锁定、测试播种与 SCH-004 到货后行为——故本裁决是"提前锁语义"，不是立即改变线上结果。
2. **SCH2-D2 第六内部查询形状（27 号 §4 增补，SVC-D5/STR-D6 命名先例）**
   - **推荐**：pet-merchant-api 新增**独立接口**（不并入 MerchantQueryApi——既有三方法为所有者授权面，第四/第五查询均独立成接口）：
     ```java
     /** 第六查询（CCR-W2-API-001 排期域 SCH-002）：门店在职在岗服务人员事实。 */
     public interface MerchantStoreStaffFactsApi {
         StoreStaffFactsDTO listActiveStoreStaffFacts(StoreStaffFactsQuery query);
     }

     public record StoreStaffFactsQuery(String storeId, QueryContext context) {}

     /** activeStaffIds=该门店 employment_status=ACTIVE ∧ service_enabled=1 的 staffId（十进制String，数值升序）；空列表=确认 0 人（正常事实）。 */
     public record StoreStaffFactsDTO(String storeId, java.util.List<String> activeStaffIds) {}
     ```
   - 语义：**无所有者前提**（仅供 SCH 域容量事实消费，不授予商家操作权限，QueryContext 仅链路信息——SVC-D5/STR-D6 同款）；**serviceId 不入参**（能力/排班表归 SCH 域，由提供器在 SCH 域组合，27号:27「schedule 拥有……可用人数」）；**返回明细而非计数**（计数形无法与能力/排班按 staffId 求交）；过滤在查询语义内固定（ACTIVE∧enabled=1），未知 `employment_status` 值不静默排除 → 503（requireKnown 先例 MerchantQueryService.java:131-143；STR-D6「SQL 过滤不得隐藏未知状态」注记）；`idx_staff_store_status` 现成，无 Schema 变更。
   - 错误两分（同既有，不得混同）：确认门店不存在（merchant_store 无行）→ NOT_FOUND；事实源故障/读取失败/状态未知 → DEPENDENCY_UNAVAILABLE（503）；空列表=确认 0（正常，对齐"开放城市无可见门店=正常空页"）。
   - 附则（推荐）：SCH 域调用侧将 NOT_FOUND 投影为 404 `SERVICE_NOT_FOUND`（与可见性两分一致——门店不存在即服务不可见；可见性已过后出现属跨快照漂移，按不可见投影不做区分探测面扩大）；503 原样透传。备选：NOT_FOUND 投影 503（视为跨快照不一致）——不推荐，多一种混合语义。
   - 备选形状：并入 MerchantQueryApi 增第四方法（不推荐，混所有者面）；`countActiveStoreStaff` 计数形（不推荐，无法组合）。
3. **SCH2-D3 能力表（staff_service_capability）语义消费**
   - **推荐**：计入条件=`service_id=目标服务 ∧ status=ENABLED`；**该 staff 无行=不具备资格（不计入）**；悬挂行（capability 行的 staff 不在第六查询名单内）经交集自然排除；未知 status 值（表注释仅定义 ENABLED，06号:152-153 无第二枚举）→ 503 失败关闭，不静默排除。
   - 粒度偏差登记：PRD §5.6「可服务类目」（平台统一服务字典，枚举数组）vs 表粒度 `service_id`（**服务项级**）——按表粒度执行（更严：服务项级资格蕴含类目级），类目→服务项的展开规则归写入方（SCH-004/员工管理扩展）届时裁决，本切片不造展开规则。
   - 无写入方披露（SCH-D3 同款）：能力表无任何写路径（员工六接口不含能力维护，27号:113），生产恒空→计数 0；实现切片测试以 SQL 播种，不据此声称"商家配置人员能力→容量生效"E2E 已验收。
4. **SCH2-D4 员工事实源故障失败关闭口径（D6 框架的 SCH-002 细化）**
   - **推荐**：提供器**缺席**（装配未注册）→ 维持既有 503（AvailabilityQueryService staffFacts==null 分支，:71-72，保留不删）；提供器在场但**读失败/未知枚举/返回负数** → 503（:119-124 既有路径）；**空表/空交集=0** → 正常事实（min(configured,0)=0、available=false，occupiedCount=0 同先例）；第六查询 NOT_FOUND → 404 投影（D2 附则）。**不降级为 available=true、不输出伪计数**（D6 原文约束在提供器接通后继续成立）。
5. **SCH2-D5 min 公式启用条件与真实装配**
   - **推荐**：boot 在 `ScheduleQueryConfiguration` 组合真实提供器（第六查询真实现 + SCH 域能力/排班真 Mapper 的适配器，落 `infrastructure/provider`）；提供器 bean 在场即启用 min 公式（服务层零改动，PR#77 已实现）；开关 `pet.schedule.query.enabled` **默认关闭不变**；`ScheduleQueryDisabledTest` 口径不变。
   - 解锁披露（随实现 PR 与上线说明明示）：SCH-001 的"真实装配下可见服务恒 503（提供器缺席）"**有意交付状态由本切片解除**（D6 预告兑现）；此后 503 仅剩真实事实源故障。生产当前窗口表无写入方→空 items 200；**真实 available=true 需窗口+员工+能力（+排班）四/五类事实齐备**——其中员工/能力/排班写入方均未交付（员工六接口未实现、能力/排班归 SCH-004/M-002），故生产出现 available=true 的时点实际受制于写入方交付节奏，如实登记不夸大本切片成效。
6. **SCH2-D6 既有 SCH-001 测试更新口径**
   - **推荐**：`ScheduleAvailabilityHttpTest` 聚合断言（W2-SCH-001/004/005/006）改走**真实提供器+SQL 播种**（merchant_staff/capability/排班行），种子替身 bean 仅保留给构造"提供器在场但需固定计数"的最小场景或删除（实现切片定，如实登记）；W2-SCH-007"提供器缺席失败关闭"用例**保留**（staffFacts=null 构造仍有效，验证缺席防御分支）；W2-SCH-001～006 全部复跑不回退；三个连续快照（service/SCH/MER 员工）注记随 07 号 §6.1.1 更新。
7. **SCH2-D7 OpenAPI/权威文档影响（预计无契约变更，请确认）**
   - **无变化**：10 号 §3.4（响应 items 六字段不变——本切片纯事实接通，C 端无感知）；12 号（无新增错误码，404/503/400/401 全沿用）；06 号（三张人员表+索引已建，无 Schema 变更）；Event（纯读切片无事件）；`AvailabilityQuery/AvailabilityWindowDTO/AvailabilityPageDTO`/`QualifiedStaffFactsPort` 签名（已批形状不动）。
   - **增补/维护（随实现 PR 权威同步，获批后执行）**：07 号 §4 增补第六查询形状（新 §4.4）+ §6.1.1 注记（"SCH-002 交付前失败关闭"句更新为已接通+三快照注记）；27 号 §4 增补第六查询行；11 号 `cGetServiceAvailability` 的 `x-contract-status: IMPLEMENTED_DEFAULT_OFF_REQUIRES_PROVIDERS`（11号:6717）——提供器已接通，标签建议改 `IMPLEMENTED_DEFAULT_OFF`（保留"默认关"语义、去掉 REQUIRES_PROVIDERS），注释同步更新；**具体标签请人工裁决**。

## 1. 范围与来源（依赖核验结论）

| 来源 | 核验结果 |
|---|---|
| SSOT §12.2/§13 | 公式=min(配置,当前可用服务人员数)；用户不选人、商家指派；**"可用"构成未定义**（定义细节在商家端 PRD §5.6，见 SCH2-D1） |
| 商家端 PRD §5.6 | 在职（离职不派单）/在岗（三值，Schema 折叠布尔）/可服务类目（粒度偏差）/排班（人员×营业时段，与门店时段联动）字段齐全（提取文本 759-892 行） |
| Schema 06 | `merchant_staff`（97-114，idx_staff_store_status）/`staff_service_capability`（149-158，uk(staff_id,service_id)）/`staff_availability_window`（182-198）齐备，无 Schema 变更 |
| 07 号 §6.1.1 | AvailabilityQuery/窗口 DTO 已批；D6 失败关闭规则原文预告 SCH-002 接通 |
| 27 号 §4 | 五查询均不覆盖门店在职在岗员工列表（getStaff 单查+所有者前提）；§2 边界"schedule 拥有可用人数、MER 不推算容量" |
| 10 号 §4.8/§4.9 | 窗口/排班写入仅路由壳；SCH-D7 已裁决 SCH-004+M-002 承接（本切片不做） |
| 代码现状（PR#77） | QualifiedStaffFactsPort（形状+whole-range 语义 javadoc）、AvailabilityQueryService（min+失败关闭已实现）、ScheduleQueryConfiguration（ObjectProvider 缺席→null）、CScheduleController/CSessionSecurityConfiguration 门控装配齐备；schedule-biz pom 已依赖 merchant-api（pom.xml:30） |
| 员工写入现状 | 员工六接口已批未实现（无控制器）；能力/排班无任何写路径——员工/能力/排班事实实现测试一律 SQL 播种（SCH-D3 披露） |
| 验收映射 | ORD-004（min 公式，docs/07-testing/14号:47）/ORD-005（超卖拒绝=hold 侧 SCH-003，本切片供容量事实）/CON-001/002（并发 hold=SCH-003/QA-002；本切片仅模块级容量事实，不声明并发结论） |

## 2. 内部 API 契约（随本切片同步 07 号 §4.4 与 27 号 §4）

形状见 SCH2-D2。补充实现语义：

- merchant-biz 侧：自有 MerchantReadMapper 增无所有者前提读取（SELECT 该 store 全部 staff 行 → 服务层校验 employment_status ∈ {ACTIVE,INACTIVE}（未知 503）→ 过滤 ACTIVE ∧ service_enabled=1 → staffId 数值升序）；返回不可变 DTO。
- schedule-biz 侧提供器（`QualifiedStaffFactsPort` 实现，落 `infrastructure/provider`）：
  1. 调第六查询取名单（NOT_FOUND→404 投影、503 透传）；
  2. SCH 域自有 Mapper 读 `staff_service_capability`（service_id 目标行；未知 status→503）取 ENABLED staffId 集；
  3. [若 D1 口径①] 读 `staff_availability_window`（store+staff 候选集行；未知 status→503）按 staff 合并 AVAILABLE 区间判定 [from,to) 覆盖；
  4. 计数=三者交集大小；任何环节读失败→503；结果负数→503（既有防御）。
- 架构：biz 不依赖 biz；merchant-biz 不读 SCH 域表；schedule-biz 不读 merchant 表（经 pet-merchant-api）；ARCH001~005 随 CI。

## 3. HTTP 契约

**无变化**（SCH2-D7）。`GET /api/v1/c/services/{serviceId}/availability` 路由/Query/响应/错误码/会话全沿用 SCH-001 已批语义（10 号 §3.4）；本切片仅把 `effectiveCapacity` 第二项从"提供器缺席失败关闭"替换为"真实人员事实计数"。响应不暴露 qualifiedAvailableStaffCount 细分（SCH-D10 不变）。

## 4. Schema / Event / OpenAPI 影响

见 SCH2-D7（无 Schema/Event/对外契约变更；07/27/11 号维护性同步随实现 PR）。

## 5. 测试映射（TEST-PLAN v0.1；编号 W2-SCH2-001～008）

| 验收 | 本提案对应 |
|---|---|
| W2-SCH2-001 真实装配接通 | SCH2-D5：真实提供器+SQL 播种→200 真实计算，恒 503 解除 |
| W2-SCH2-002 人员四条件过滤 | SCH2-D1：在职/在岗/归属/能力逐反例 |
| W2-SCH2-003 能力表语义 | SCH2-D3：ENABLED/无行/悬挂/未知状态 |
| W2-SCH2-004 排班消费（随 D1 裁决定稿） | SCH2-D1：覆盖并集/部分重叠/CLOSED/无行=0；若裁决不纳入→改为"排班不消费"回归锁 |
| W2-SCH2-005 事实源故障失败关闭 | SCH2-D4：未知枚举/读失败/缺席 503；不降级 |
| W2-SCH2-006 min 公式与容量边界 | SSOT §12.2、ORD-004：取小/0/大于配置/占用计数不回退 |
| W2-SCH2-007 第六查询模块契约 | SCH2-D2：过滤/无所有者前提/错误两分/大 ID |
| W2-SCH2-008 既有用例不回退 | SCH2-D6：W2-SCH-001~007 复跑+ARCH 守卫 |
| ORD-004 | 模块证据（SQL 播种）；ORD-005/CON-001/002 归 SCH-003/QA-002（披露不冒认） |

## 6. 承接登记与风险

| 事项 | 承接 | 登记位置 |
|---|---|---|
| 员工六接口 HTTP 实现（含 disable 解锁条件） | MER 域既有归属（27号 §6.1 已批未实现） | 本提案 §1；不扩 SCH-002 scope |
| 能力/排班写入方（§4.9+能力维护） | SCH-004（后端）+M-002（页面），SCH-D7 已裁 | 依赖图既有 |
| 类目→服务项展开规则（PRD 粒度偏差） | 写入方切片届时裁决 | SCH2-D3 登记 |
| 排班语义复裁（若 D1 选②） | SCH-004 交付时 | SCH2-D1 登记 |
| hold/swap/占用生产者 | SCH-003（27号 §7 先例） | 既有归属 |

风险与如实披露：

- **真实 available=true 的前置链条**：窗口+员工+能力（+排班）写入方全部未交付——本切片解除 503 后，生产对外仍表现为空 items（窗口无生产者）；"商家配置人员→容量变化"链路在写入方交付前仅模块测试可证（SCH-D3/D6 披露延续，不冒认 E2E）。
- 三连续快照（service 资格 / SCH 窗口+占用+能力+排班 / MER 员工）理论漂移窗口扩大一层，展示级可接受，权威复核归 SCH-003 hold（07 号 §6.1.1 注记随实现更新）。
- D1 若裁②：端口 javadoc 修订+乐观假设风险披露；D1 若裁③：本切片意义存疑（恒 503 维持）——两条均已写入备选影响。
- 排班覆盖并集判定的实现复杂度（区间合并）适中；若人工偏好简化可选单窗包含（D1 附则备选）。
