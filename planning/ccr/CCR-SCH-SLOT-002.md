# CCR-SCH-SLOT-002 排期模板批量生成 + 可预约范围（P-11b 复活，随统一槽成立）

- 状态：APPROVED（2026-11-13 用户裁决，见 §7；实施中）
- 日期：2026-11-13
- 提出方：用户（"统一槽后 12:6214 可百分百还原"——P-11b 从"降级可选"复活）
- 关联：CCR-SCH-SLOT-001（统一槽，已实施）；Figma 12:6214（整帧原型）；SSOT §12.1；
  Contract 53号（batch-close 先例）/38号（booking hold）；pending-issues P-11b
- 红线复述：禁周模板三字段（dayOfWeek/repeatWeekly/copyNextWeek）指**数据结构**——本 CCR
  的"周模板"仅是前端编辑态草稿，保存即物化为真实分钟级统一槽窗口（用户 2026-11-11 口径），
  不新增任何周期性字段/表。

## 1. 批量生成（53号增补：batch-generate）

`POST /api/v1/merchant/stores/{storeId}/availability-windows/batch-generate`
（开关沿 53号家族；X-Request-Id 幂等同族）

- 请求体严格 JSON：`{merchantId, fromDate, toDate}`（北京历日，跨度 1..366 天）、
  `dailyStart/dailyEnd`（"HH:mm"，start<end）、`intervalMinutes`（1..1440，默认 60）、
  `configuredCapacity`（1..999）、`reason`（1..500，覆盖动作必填沿 close 纪律）。
- 语义=**覆盖重建**：范围内既有 OPEN/SOLD_OUT 统一槽先逐个 close（SOLD_OUT 视同开放，
  沿 batch-close 口径）；**被占用（close 409）的窗跳过**并列入 `skippedWindows`
  （reasonCode=OCCUPIED 沿 batch-close blocked 命名族），该被跳过窗的时间区间内**不再生成**
  （同店重叠校验自然拦住）；随后按 intervalMinutes 逐段创建分钟级窗口（容量=请求值）。
- 响应：`{storeId, createdWindows:[…], closedWindows:[…], skippedWindows:[{window,reasonCode}]}`
  ——部分成功允许，全受阻不伪称成功（沿 batch-close）。单次候选条目（created+closed+skipped）
  上限 200，超出 400（沿 §3.2）。
- 前端"周模板编辑器"= 纯草稿态：周一~周日逐日时段胶囊（×/+/📋/开关）编辑，**保存并生效**
  = 按各启用日组装 fromDate/toDate+当日时段集合逐段调 batch-generate（多段多次调用或逐日调用）；
  「一键生成全部日期」= 门店时间起止 + 间隔 + **容量输入框（默认 6）** 一次调用 7 天。

## 2. 可预约范围（新规则：SSOT §12 增行）

- 规则：客户可预约的时段满足 `startAt ≥ now + leadMinutes` 且 `startAt ≤ now + maxDays 天`；
  默认 lead=60 分钟、maxDays=7，**商家可调**（lead 0..1440 分钟、maxDays 1..90 天）。
- 存储：新表 `merchant_schedule_booking_window`（store_id 唯一键、lead_minutes、max_days、
  version、created/updated；缺行=默认值，不回填）——门店级配置，非窗口级。
- M 端：53号增补 `GET/PUT /api/v1/merchant/stores/{storeId}/booking-window`
  （GET 缺省返回默认值；PUT 全替换+expectedVersion 乐观锁+reason 不需要（非减少可用性））。
- C 端强制：availability 读（含 selection）过滤窗口时间在范围外的时段（available=false 不隐
  藏时段，仅不可选？——**隐藏**：items 不返回范围外时段，最直观）；booking hold 校验
  `startAt` 越界 → 400 COMMON_INVALID_ARGUMENT（message 说明可约范围；不发明新码，最小面）。
  reschedule（46号）同样校验（换期也是新预约时间）。
- 「不可约日期」（黑名单）：**不新增规则**——由商家端"批量关窗"（既有 batch-close）实现，
  前端把"添加不可约日期"翻译为对该日历日 batch-close（reason 自动生成）。

## 3. 页面（12:6214 百分百还原，merchant/pages/schedule/index 整页重做）

- 区块 A 一键生成：门店时间 09:00 至 19:00（两个时间选择）+ 容量输入（默认 6，新增，原稿未画
  ——用户 2026-11-13 拍板）+「一键生成全部日期」按钮 + 说明文案。
- 区块 B 时间设置：周一~周日 7 行；每行=开关（当天启用）+ 时段胶囊（点编辑起止）+ ×删除 +
  ＋添加 + 📋复制到其他日期；底部 💡 提示文案照抄。
- 区块 C 通用设置：可预约范围（两个数字输入：分钟后/天内，默认 60/7，调 PUT booking-window）；
  不可约日期列表（-/-/-）+ 添加日期（调 batch-close）+ 💡 文案。
- 底栏：取消（重载草稿）/ 保存并生效（A/B 区→batch-generate；C 区→booking-window PUT）。
- 既有 windows.tsx 子页保留（真实窗口列表/单窗管理/状态查看）；index 主页从"列表页"变为
  "模板编辑页"（P-11a 的列表视觉迁往子页或保留在编辑页下方折叠——按实现简洁取舍，H5 定）。

## 4. 影响面

SSOT §12 增行；53号 §3 增补 batch-generate + §x booking-window 两路由 + SQL53/V29 新表；
e2e smoke 新路由登记×3 + 计数钉；schedule-biz（生成内核/配置面/过滤）+ booking hold 校验；
前端 schedule/index 重做 + windows 子页微调；运营 admin 无涉。

## 5. 不变项

禁三字段（无周期数据结构）；分钟级窗口；占用保护（跳过不强制）；幂等/审计/乐观锁全套；
batch 200 上限家族；统一槽单池语义。

## 6. 风险

- batch-generate 单次 200 候选上限：7 天×10 段=70 窗+关闭旧窗可能触顶——前端分段调用
  （≤7 天×24 段内安全；H5 按天上限保护）。
- 覆盖语义关闭旧窗后创建失败（部分成功）会留下"旧已关、新未建"的空档——响应明列
  created/closed/skipped，前端如实展示让商家重试。

## 7. 裁决记录（2026-11-13，用户）

1. 容量：生成前填一个容量输入（默认 6，可改）。
2. 覆盖冲突：跳过被占用的时段（明确列出未覆盖清单），其余照常覆盖。
3. 可预约范围：做，按原稿 1 小时/7 天默认且商家可调（新规则随本 CCR 入 SSOT）。
