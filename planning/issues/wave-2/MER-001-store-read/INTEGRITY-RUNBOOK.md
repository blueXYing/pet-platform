# MER-001 门店读侧：merchant_profile_compat 完整性检查与修复 Runbook（STR-D3 增补）

日期：2026-09-22。Owner：角色B（MER-001 门店读侧切片）。依据：[store-read-proposal.md v0.2](../../../ccr/CCR-W2-API-001/store-read-proposal.md) STR-D3、用户 2026-09-22 裁决第 3 条（"补齐存量完整性检查、告警和修复方案，不作为无处置的遗留风险"）。

## 1. 异常语义与检查范围

运行时判定（`MerchantStoreDisplayService`，随本切片交付）：某商家**通过资格合取**（`merchant.status='ACTIVE'` ∧ `merchant_store.status='ACTIVE'` ∧ 审核 APPROVED ∧ 签约 SIGNED）但

- `merchant_profile_compat` **缺行**（APPROVE 建档同事务写入的行不存在），或
- compat 行存在但 `city_code` **词法损坏**（不匹配 `[a-z][a-z0-9_-]{0,31}`，含空值以外的不合法值——列本身 NOT NULL），

则该商家的城市事实视为损坏：`GET /c/stores` **整页 503**（`COMMON_DEPENDENCY_UNAVAILABLE`，message 含 `merchant profile compat row is missing` / `merchant profile city fact is invalid`）、`GET /c/stores/{storeId}` 503。不得静默隐藏、不得归入任何城市、不得降级为空页。

**巡检范围（超集，宁多报不漏报）**：上表只盯"已合格"商家；巡检对全部 `merchant.status='ACTIVE' ∧ merchant_store.status='ACTIVE'` 的商家执行（审核/签约两事实在申请审批链内，随流程收敛；巡检超集能在商家变为 eligible **之前**发现缺行）。

## 2. 存量完整性检查 SQL（迁移后手工执行或定时巡检）

```sql
-- 检查 A：ACTIVE 商家/门店但 compat 缺行（运行时将整页 503 的直接前因）
SELECT m.id AS merchant_id, m.merchant_name, m.status AS merchant_status,
       s.id AS store_id, s.store_name, s.status AS store_status
FROM merchant m
JOIN merchant_store s ON s.merchant_id = m.id
LEFT JOIN merchant_profile_compat p ON p.merchant_id = m.id
WHERE m.status = 'ACTIVE'
  AND s.status = 'ACTIVE'
  AND p.merchant_id IS NULL;

-- 检查 B：compat 行存在但 city_code 词法损坏（同触发整页 503）
SELECT p.merchant_id, p.city_code, p.source_kind, p.application_id, p.updated_at
FROM merchant_profile_compat p
JOIN merchant m ON m.id = p.merchant_id
JOIN merchant_store s ON s.merchant_id = m.id
WHERE m.status = 'ACTIVE'
  AND s.status = 'ACTIVE'
  AND p.city_code NOT REGEXP '^[a-z][a-z0-9_-]{0,31}$';

-- 检查 C（定位修复来源用）：检查 A/B 命中商家的申请审批链事实
-- （application_id / source_revision_id / city_code 应从此链取，不得猜测）
SELECT a.reserved_merchant_id, a.id AS application_id, a.status AS application_status,
       a.submitted_revision_id, r.city_code AS revision_city_code,
       r.merchant_type_code, r.created_at AS revision_created_at
FROM merchant_application a
JOIN merchant_application_revision r ON r.id = a.submitted_revision_id
WHERE a.reserved_merchant_id IN (/* 检查 A/B 命中的 merchant_id 列表 */);
```

执行口径：任何环境应用 29号 Schema 或其迁移后执行一次全量；之后随巡检节奏重跑。检查 A/B 命中数应为 0；非 0 即进入 §4 修复流程。

## 3. 告警建议

- **业务指标**：对 `GET /api/v1/c/stores*` 的 503 `COMMON_DEPENDENCY_UNAVAILABLE` 响应按 message 细分——`merchant profile compat row is missing` / `merchant profile city fact is invalid` 命中即告警（阈值建议：任一出现 >0 持续 2 个采样周期，P2；整页 503 率 >1% 升 P1）。message 不含业务标识，仅作计数聚合，不记录请求参数。
- **巡检告警**：检查 A/B 结果集非空即告警（同上分级），并在值班记录登记命中 merchant_id 清单。
- **关联面**：该完整性事实同时被 C 端门店列表消费（本切片）；巡检应与 `merchant_profile_compat` 所属的申请审批域共用一份口径，避免两套判定漂移（巡检 SQL 与 `MerchantStoreDisplayService.cityFact` 词法保持一致：`[a-z][a-z0-9_-]{0,31}`）。

## 4. 修复步骤

**原则：补行来源=申请审批链留存的 revision 事实（29号 `insertProfile` 同款字段），LEGACY 行须受控回填，没有来源时不得从名称、地址或手机号猜测（29号存储裁决原文）。**

1. 用检查 C 定位该商家最新 `APPROVED` 申请的 `submitted_revision_id` 与其 `city_code/merchant_type_code`；确认该 city_code 属于当前开放城市目录且词法合法。
2. 补行（幂等：`INSERT ... ON DUPLICATE KEY` 不更新既有行——compat 以 merchant_id 为主键，**已存在即人工核对而非覆盖**）：

```sql
INSERT INTO merchant_profile_compat
  (merchant_id, application_id, source_revision_id, merchant_type_code,
   city_code, source_kind, version, created_at, updated_at)
SELECT a.reserved_merchant_id, a.id, a.submitted_revision_id,
       r.merchant_type_code, r.city_code, 'APPLICATION', 0, NOW(3), NOW(3)
FROM merchant_application a
JOIN merchant_application_revision r ON r.id = a.submitted_revision_id
WHERE a.reserved_merchant_id = :merchant_id
  AND a.status = 'APPROVED'
  AND NOT EXISTS (SELECT 1 FROM merchant_profile_compat p WHERE p.merchant_id = :merchant_id);
```

3. 词法损坏（检查 B）不执行 UPDATE 覆盖：先人工核对 revision 事实，确认后按同来源 `UPDATE ... SET city_code=:from_revision, updated_at=NOW(3) WHERE merchant_id=:id AND city_code=:corrupt_value`（带旧值条件防并发双改）。
4. 无 APPROVED 申请事实的 LEGACY 商家：按 29号裁决走受控回填（人工裁决来源，登记审批记录后补 `source_kind='LEGACY'、application_id=NULL、source_revision_id=NULL` 行）；裁决前该商家保持 503（失败关闭是既定行为，不是待修 bug）。
5. **幂等重放注意事项**：补行/修正是对共享事实表的人工写，不产生 outbox 事件（纯读切片无事件）；重复执行步骤 2 的 INSERT 因 `NOT EXISTS` 守卫与主键约束天然幂等；修复后立即用同请求参数重放 `GET /c/stores?city=<该城市>` 验证恢复 200（TEST-PLAN N9 覆盖同路径）；巡检复跑检查 A/B 应归零。
6. 修复留痕：值班记录登记 merchant_id、来源 revision、执行人与复核人（V1 单运营口径仍要求审计可追）。

## 5. 回归验证锚点

- `CStoreControllerHttpTest`（随本切片交付）：SQL 播种删除 compat 行 → 整页 503 + 详情 503（不出现 200 空页）；按 §4 步骤补行后同请求 200 恢复；巡检 SQL（检查 A）在播种态命中、修复后归零——运行时判定与巡检口径一致的直接证据。
