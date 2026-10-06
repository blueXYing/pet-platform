# CCR-C006-COUPON-POINTS-READ-001：C 端"我的优惠券/我的积分"只读查询契约提案（已批准）

状态：**APPROVED_FOR_SLICE（2026-10-06 用户裁决"批准 CCR"）**。批准范围：P1 四个只读 GET 查询表面，由明确 Backend Owner 落地并同步权威契约（HTTP 10 号新增两组只读路径、07 号 §12.1/§13.1 实现排期联动）；D1~D6 细则未逐条裁决，维持各条"不批准则对应能力不交付"的缺省行为，待后续单独裁决。2026-10-06；提出方/唯一 Writer：C-End（C-006 切片：我的优惠券与积分只读页）。本提案只申请**只读查询**表面；不涉及任何发放、冻结、核销、退券、兑换、抵扣、签到/邀请/任务的规则与写路径。真实接口交付前前端仍以 preview=1 本地夹具呈现（分支 `codex/c-coupon-points-20261006`），不接真实接口、不自行实现后端。

## 一页结论

develop（02abe89）上券/积分域没有可用的查询事实链：`pet-points-api` 全部为包骨架（仅 package-info），`pet-coupon-api` 仅有预订无券证明 `BookingCouponExposureApi`（07 号内部契约 §12.1 `CouponQueryApi`、§13.1 `PointsQueryApi` 均为文档形态、无实现）；`pet-boot/adapter/web/c` 无 coupon/points 控制器；后端 `CPN-001/CPN-002/PTS-001/PTS-002` 在 ISSUE_CATALOG 全部 BLOCKED。C 端页面（128:2078 优惠券、129:8174 积分明细）无法接真实契约，本切片按"阻塞分析 + 可行子集"交付只读预览。本 CCR 登记缺口与提案，供 Contract Owner 裁决后再由明确 Backend Owner 落地。

## 事实基础（本次核实，非提案内容）

- Schema 事实（docs/03-database/06-核心数据库Schema-v0.1.sql §8/§9，注意：pet-boot Flyway 目录尚无对应迁移，仅测试路径按需建表）：
  - `coupon_instance.status`：AVAILABLE/FROZEN/USED/EXPIRED/RISK_FROZEN；`expire_at`（全额退款可延至退款成功+24h）；`order_id`。
  - `coupon_template`：`name`、`valid_start_at/valid_end_at`、`rule_json`（"门槛、适用商家/服务、优惠计算等配置"——**结构未冻结**）。
  - `points_account.balance` BIGINT；`points_ledger`：`biz_type`（SIGN_IN/INVITE/TASK/ORDER_REWARD/REFUND_CLAWBACK）、`delta`（增加为正、扣回为负）、`balance_after`、`created_at`。
- 产品硬规则：V1.0 积分仅"赚取 + 余额 + 流水"，无积分抵现/抵扣/商城/兑换（SSOT §十、07 号 §13）；积分只赚取/扣回不消费（根 AGENTS.md）。券侧 V1 无发放渠道裁决（CPN-001 BLOCKED），不导入第三方券包（SSOT §651 行）。
- 设计事实：用户端 Figma key `bp2vpcjjA5vZbHvtKkA8wl`，`128:2078`（我的-优惠券：可用/已使用/已过期三个 tab，卡片含面额/门槛/名称/适用范围/类型标签/有效期至）、`129:8174`（我的-积分明细：余额样例 1280，流水行"名称 +delta 相对时间"）。设计样例中的"5折"折扣券与"兑换 -500"流水属 V1 范围外（折扣计算未冻结；兑换被硬规则禁止），不进入任何实现或夹具。

## 提案（P1 已于 2026-10-06 获用户批准；落地与权威契约同步归 Backend Owner）

### P1 C 端只读查询表面（会话主体，登录态）

1. `GET /api/v1/c/coupons?status=AVAILABLE|USED|EXPIRED&page=&pageSize=`——返回当前会话用户 `coupon_instance` 投影。**D1 展示投影**：`couponId`(String)、`name`(模板名，≤128)、`amountOff`(两位小数 String，来自 rule_json 面额——待 rule_json 冻结口径)、`thresholdAmount`(两位小数 String|null，null=无门槛)、`scopeSummary`(≤64 展示串)、`typeLabel`(≤16 展示串)、`validTo`(ISO 日期)、`status`、`usedAt`(ISO|null)。分页沿用既有 PageResult 包裹。状态过滤仅允许 AVAILABLE/USED/EXPIRED 三值；默认 AVAILABLE。
2. `GET /api/v1/c/coupons/{couponId}`——单券同投影；非本人券一律 404（不区分不存在/不属于，防枚举）。
3. `GET /api/v1/c/points/balance`——`balance`（非负整数 String，BIGINT 传输安全）。
4. `GET /api/v1/c/points/ledger?page=&pageSize=`——`ledgerId`(String)、`bizType`（上述五枚举）、`delta`（带符号整数 String，非零）、`balanceAfter`（整数 String）、`createdAt`；按 `created_at` 倒序固定排序。

### D 待裁决点（不批准则对应能力不交付）

- **D1 rule_json 派生展示字段**：`amountOff/thresholdAmount/scopeSummary/typeLabel` 均为 rule_json 的服务端投影。rule_json 结构未冻结前，这四个字段无法定 schema；建议随 CPN-001 的模板规则契约一并冻结，C 端只消费投影、不解析 rule_json。
- **D2 FROZEN/RISK_FROZEN 呈现**：冻结中券是否对用户可见（如"使用中"tab 或并入可用）未裁决；本切片两态一律不展示，夹具不含。
- **D3 已使用券的订单跳转**：设计未呈现，订单详情页未交付（C-004 范围），本切片 `usedAt` 只读展示、无跳转。
- **D4 金额展示格式**：金额基线要求两位小数原样输出（"¥20.00"），与设计稿整数简写（"¥20"）不一致；建议按契约串原样渲染（与 C-003 salePrice 先例一致）。
- **D5 签到/邀请/任务入口**：129:8174 含签到日历与"立即签到·得5积分"按钮；签到/邀请/任务奖励规则未裁决（PTS-001 BLOCKED），本切片不提供任何赚取动作入口。
- **D6 积分明细的相对时间**："今天/昨天/M月D日 HH:mm"为客户端展示格式（UTC 计算），契约仍返回 ISO 时间戳；跨日边界以 UTC 日历日为准，是否改按 Asia/Shanghai 由 Owner 裁决。

### 验收案例（批准后由 Backend Owner 与前端联调轮执行）

- 正：登录态查列表三态分桶正确；分页稳定排序；余额=流水最新 `balance_after`；流水中 REFUND_CLAWBACK 为负值且镜像退款扣回规则；非本人券 404。
- 反：未登录 401；`status` 非法值 400；rule_json 未冻结时投影字段缺失的设计退回（不得以解析 rule_json 的方式在前端补）；任何发放/核销/兑换写路径在本表面不存在。

影响：HTTP 10 号（C 端）新增两组只读路径与错误码引用、07 号 §12.1/§13.1 内部查询 API 的实现排期（CPN-001/PTS-001 解阻塞后的联动）、无新表无迁移。只在批准后由明确 Backend Owner 同步；本前端分支不越权写权威契约文档。

依据：根 AGENTS.md"Contract 缺失走 CCR"、硬规则"积分只赚取/扣回，不消费"；07-内部API-Contract-v0.6 §12/§13；06-核心数据库Schema §8/§9；20-设计源登记表 §3；最终 PRD 优惠券/积分规则章节；ISSUE_CATALOG CPN-001/002、PTS-001/002、C-006 BLOCKED 状态。
