# SCH-001 排期域决定回执（2026-09-23）

来源：用户在当前任务中明确回复（对 [排期域提案 v0.1](schedule-availability-proposal.md) 的裁决），逐条记录原话要点；格式对齐 [service-domain-decisions.md](service-domain-decisions.md)。

> 【裁决原文要点】
> 1. SCH-D1/D2/D8/D9/D10 按推荐：登录态401不扩匿名面；日期边界组合；错误码全沿用；storeId不匹配404防探测；items布尔available。
> 2. SCH-D3/D4/D5 接受（读+SQL种子披露/window_kind延后写入方/不引入提前窗口）。**D6 修改（关键）**：SQL种子与容量占位值只能用于 SCH-001 的模块测试；真实接口不能把 effectiveCapacity=configuredCapacity、人员数占位、occupiedCount=0 当成已核实的可约事实返回 available=true（SSOT §12：容量=min(配置,可用人员)）；缺少权威事实时**失败关闭**；面向用户启用完整可约判断须等 SCH-002 人员容量及后续占用事实接通；上门/送回分别开窗须在写入方交付时解决 window_kind，不得把同一组窗口宣称为已完成双时段排期。
>    → 实现含义（按此执行）：真实路由在人员可用性事实缺失时按错误两分失败关闭（503 COMMON_DEPENDENCY_UNAVAILABLE，对齐既有先例），不得降级为 available=true 或伪容量；装配开关默认关闭；本切片交付口径=模块测试（种子事实）+契约，不声明可约事实已核实、不声明完整可约判断启用。
> 3. SCH-D7/D11：排期写入仅商家（运营只读监管）；C端PRD"商家或运营维护"登记勘误（随决定回执与issue文档落盘，不改PRD docx原文）；SCH-004承接后端写入、M-002承接商家页面；真实"开窗→查询→预约"验收前必须交付写入方，但不阻塞SCH-001独立读测试。
> 4. B5：维持不实现售罄态（available=false 表达无位可约）。

## 已确认

1. **SCH-D1**：登录态 401，不扩 STR-D8 匿名面（四路由不含 availability）。
2. **SCH-D2**：日期边界组合（yyyy-MM-dd 必填、跨度≤31 天、Asia/Shanghai 解释、过去窗口过滤、跨天整体返回、CLOSED 不进 items、空结果 200）。
3. **SCH-D3**：读+SQL 种子披露模式；不得声明完整 E2E。
4. **SCH-D4**：窗口不分 kind；`window_kind` 延后写入方（SCH-004）解决；**不得把同一组窗口宣称为已完成双时段排期**。
5. **SCH-D5**：不引入提前预约窗口。
6. **SCH-D6（裁决修改版，核心）**：
   - SQL 种子与容量占位值**只能用于模块测试**；
   - 真实接口不得把 `effectiveCapacity=configuredCapacity`、人员数占位、`occupiedCount=0` 当作已核实的可约事实返回 `available=true`（SSOT §12：容量=min(配置,可用人员)）；
   - 缺少权威事实时**失败关闭**（503 `COMMON_DEPENDENCY_UNAVAILABLE`，对齐既有先例）；
   - 面向用户启用完整可约判断须等 SCH-002 人员容量及后续占用事实接通；
   - 装配开关默认关闭；交付口径=模块测试（种子事实）+契约，不声明可约事实已核实、不声明完整可约判断启用。
7. **SCH-D7**：排期写入仅商家（运营只读监管）；**SCH-004 承接后端写入、M-002 承接商家页面**；真实"开窗→查询→预约"验收前必须交付写入方，但不阻塞 SCH-001 独立读测试。
8. **SCH-D8**：错误码全沿用（404 `SERVICE_NOT_FOUND`/503 `COMMON_DEPENDENCY_UNAVAILABLE`/400/401），12 号无新增。
9. **SCH-D9**：storeId 不匹配 404 防探测。
10. **SCH-D10**：items 布尔 `available`，不新增 status 枚举。
11. **SCH-D11**：排期写入仅商家（运营只读监管）；C 端 PRD §5.1.15"商家排期可由商家或运营维护"句**登记勘误**（本回执与 issue 文档落盘，不改 PRD docx 原文）。
12. **B5（关联登记）**：维持不实现售罄态，`available=false` 表达无位可约（沿用 2026-09-23 收官遗留表口径，此处复核确认）。

## 勘误登记（SCH-D11，不改权威 docx 原文）

| 文档 | 位置 | 原文 | 勘误后口径 | 依据 |
|---|---|---|---|---|
| 《02-PRD-C端用户-V1.0-最终基线.docx》§5.1.15 预约服务页用户角色行 | 提取文本约 1140 行 | 「已登录宠物主；商家排期可由商家或运营维护」 | **排期写入仅商家；运营只读监管** | 运营端 PRD §3.3 边界「不代商家维护排期」、§6.2.5「只读监管，不得代商家修改排期」；SSOT 无运营维护排期条款；22 号运营权限补充不改变"不代商家"边界 |

## 权威同步（随本切片 PR）

07 号 §6.1（AvailabilityQuery/AvailabilityPageDTO 形状+D6 失败关闭规则）、10 号 §3.4（全语义细化含失败关闭）、11 号增补 `cGetServiceAvailability`（`IMPLEMENTED_DEFAULT_OFF_REQUIRES_PROVIDERS`）；12 号无变化；06 号无变化（window_kind 由 SCH-004 届时增补裁决）。

## 保留边界

- 真实装配（无人员事实提供器）下可见服务查询失败关闭 503——有意交付状态；面向用户启用完整可约判断须等 SCH-002。
- 写入方（SCH-004/M-002）交付前"商家开窗→消费者查到→预约"真实链路未验收；本切片仅模块测试（种子事实）+契约。
- hold/swap/TEMP_LOCKED（SCH-003）、人员事实源（SCH-002）、运营监管路由（SCH-004 范围）不提前实现。
