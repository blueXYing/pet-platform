# 排期人员容量契约提案 v0.2（SCH-002）

状态：CONTRACT_SYNCED_IMPLEMENTATION_STARTED。日期：2026-09-24。
依据：[联合裁决回执](schedule-review-decisions.md)、SSOT §29。v0.1 历史见 planning/history/schedule-drafts-20260924。
2026-09-24用户在确认GPT-6 Sol极高多角色方案后授权“开始推进”。主协调已先同步07/27/11契约，进入实现切片；未实装并通过验证前不得把OpenAPI状态改成IMPLEMENTED_DEFAULT_OFF。

## 决定映射

| 决定 | 定稿方向 |
|---|---|
| SCH2-D1 | 可用员工=在职 ACTIVE∧在岗 service_enabled=1∧属于该店∧具备目标服务能力∧AVAILABLE 排班并集无空档覆盖整个 [from,to)。无排班或能力的确定事实=0；相邻段可以拼接，有间隔不能跨过。 |
| SCH2-D2 | 独立 MerchantStoreStaffFactsApi，返回员工 ID 明细，由 SCH 按 ID 与能力/排班求交，不返回无法求交的总人数。不公开给客户端，不提供操作权限。 |
| SCH2-D3 | 能力按具体 service_id 授权；ENABLED 才计入，无行不计入，未知状态失败关闭。类目仅页面分组，新服务不自动继承能力。 |
| SCH2-D4 | 提供器缺席、读失败、未知枚举、非法负计数=503；明确门店不存在按既有 404 SERVICE_NOT_FOUND 投影；确定空集合=0，不能将故障吞成空集合。 |
| SCH2-D5 | boot 组合 MER 内部查询与 SCH 自有 Mapper 实现真实提供器；min(configuredCapacity, qualifiedStaffCount)；默认关闭保持。占用扣减沿用 SCH-001，不以总容量冒充剩余可约数。 |
| SCH2-D6 | SCH-001 聚合测试改走真实提供器+显式 SQL 人员种子，保留提供器缺席、错误两分、未知状态等反例；测试不冒充商家录入 E2E。 |
| SCH2-D7 | 外部 HTTP 字段、错误码及 Schema/Event 不变；07/27 增第六查询，11 的实现标签仅在真实交付后变更。 |

## 内部接口定稿方向

```java
public interface MerchantStoreStaffFactsApi {
    StoreStaffFactsDTO listActiveStoreStaffFacts(StoreStaffFactsQuery query);
}
public record StoreStaffFactsQuery(String storeId, QueryContext context) {}
public record StoreStaffFactsDTO(String storeId, java.util.List<String> activeStaffIds) {}
```

ID 十进制 String；QueryContext 沿用已有字段；员工 ID 去重、按数值排序。
MER 只读自己的表，确认门店存在，核验枚举再过滤 ACTIVE/enabled；不能先 SQL 排除未知状态导致误报正常少人。
SCH 经 API 获取员工名单，只读自己的 capability/availability 表。悬挂能力行不能扩大名单，重复能力/排班不重复计人。
排班按员工合并半开 AVAILABLE 区间；例如 [09:00,10:00)+[10:00,11:00) 可覆盖 [09:00,11:00)，而 [09:00,10:00)+[10:01,11:00) 不可。
CLOSED 不形成覆盖；失败不返回 available=true。提供器跨域异常映射保持 404/503 两分。

## 审阅发现与交付约束

1. 读侧计算是展示事实，不能宣称已证明并发防超卖；SCH-003 必须在预约锁定时做权威复核并与写入方共享并发协议。
2. 同一员工可服务多个项目，不代表不同服务可以各自独占其整份容量。跨服务共享人员的占用分配属 SCH-003 契约前置，不能用本查询的逐服务计数替代。
3. 查询覆盖的是本次整个窗口范围，不是只检查开始时刻；不扩成固定 60 分钟槽，不改已批 SCH-001 窗口语义。
4. MER 员工录入、SCH-004 能力/排班写入与 M-002 页面均为真实录入链前置。它们未交付时数据播种仅用于测试。
5. 先同步权威契约，再实现第六查询/提供器/装配/测试。准入、冻结写动作及员工 disable 既有门禁不因此解除。

## 验收

沿用 TEST-PLAN 的 W2-SCH2-001～008，并增加：相邻覆盖与一分钟空档；同一员工多行去重；跨门店/无能力/离职/停排各反例；故障不得伪装 0；默认关闭；架构边界。
实现开始时测试仍为NOT_EXECUTED；逐项记录模块/HTTP/集成结果，不因契约冻结标记完整SCH-002 DONE。
