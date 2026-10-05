# IMPLEMENTATION — 商家排期维护页面（M端切片，消费53号契约）

分支 `codex/mer-schedule-page-20261006`（基于 origin/develop 02abe89，含 #104 排期写侧）。
Draft PR，不合并。后端零改动。

## 范围

四个 M 端页面（merchant 分包），消费 53号契约（docs/04-api/53-Schedule-Write-Contract-v0.1.md）
+ MerchantScheduleController（HTTP，开关 `pet.schedule.command.http.enabled` 默认关）：

| 路由 | 页面 | 覆盖操作 |
|---|---|---|
| `merchant/pages/schedule/index` | 排期工作台（读） | GET availability-windows 呈现（类型/状态筛选、OPEN/SOLD_OUT/CLOSED 计数与卡片、SOLD_OUT 只读标签及说明）、GET staff availability-windows 快捷查询、三入口 |
| `merchant/pages/schedule/windows` | 服务时段管理 | 列表（按服务+时段卡片分组呈现）、新建（服务×类型×分钟级起止×容量）、编辑（OPEN/SOLD_OUT，服务与类型固定）、关闭（reason 必填）、开放（重开按占用重判可能直接 SOLD_OUT）、批量关闭（日历日范围+reason，200 上限 400 呈现、部分成功 blockedWindows 明示）、SOLD_OUT 只读标签（无手工置满/强制可约入口） |
| `merchant/pages/schedule/staff` | 员工排班维护 | 按 staffId 列表、新增、编辑、关闭（reason 必填）、开放；重叠 409、减员保护 409、CAS 409 呈现。契约无删除语义：关闭保留历史（页面文案明示） |
| `merchant/pages/schedule/capabilities` | 员工能力维护 | 按 staffId 读取集合（服务名 picker 沿服务管理列表）、全量替换 PUT（expectedVersion CAS）、撤销项 reason 必填、过期 409 呈现重读；LEGACY_UNVERSIONED 503 隔离呈现为「功能未开放（待盘点）」 |

错误语义（失败关闭）：
- `scheduleAvailability()`：503 与 404（开关关闭路由不存在 / 非 OWNER 防枚举 404）→ 整页「功能未开放」不可交互面板（`ScheduleShell` phase='closed'），不渲染表单。
- `scheduleMessage()` 按契约错误码映射中文：`SCHEDULE_WINDOW_OVERLAP`/`SCHEDULE_WINDOW_STATE_NOT_ALLOWED`/`SCHEDULE_CAPACITY_EXCEEDED`/`SERVICE_STATE_NOT_ALLOWED`/`COMMON_CONFLICT`（CAS 提示刷新重读）/`IDEMPOTENCY_KEY_CONFLICT`/`COMMON_INVALID_ARGUMENT`（含批量 200 上限提示）/401/403（限商家主账号 OWNER）/404/503。
- 请求沿 `ConsumerApi` workspace scope（merchantId/storeId 取自 scope；merchant 请求强制 body/query 带 merchantId）；写命令走 `api.write(slot,…)` 幂等槽位：同槽同参重放同一 X-Request-Id（23号），确定性 409 在 repository 内调 `retireRejectedCommand` 释放槽位，5xx 保留槽位并提示重试原操作。

## 共享层

- `src/merchant/schedule/model.ts`：类型+严格 exact-key 失败关闭解码（window item/receipt/page、batch（含 blockedWindows.reasonCode、解码层 200 条上限）、staff item/receipt/page、capability（serviceIds 去重校验））；className 变体映射（`windowStatusTagClass`/`staffStatusTagClass`/`chipClass`，无 data-* 依赖）；北京时间助手（`composeTimestamp` 组 `+08:00` 毫秒精度、`splitBeijingParts`、`formatWindowInterval`、`beijingToday`、`batchRangeDays`）；表单预校验（`windowFormProblems`/`staffWindowFormProblems`/`kindsForFulfillment`（PRD29 页面引导 IN_STORE→GENERAL、PICKUP_DELIVERY→PICKUP/RETURN）/`reasonProblem`(1..500)/`capabilityProblems`）；`PreviewScheduleRepository` 契约 mock（SOLD_OUT 派生+容量提升回位、占用守卫、重叠、CAS、批量 200、能力集合版本、scenario: normal/empty/load-error/closed/legacy）。
- `src/merchant/schedule/repository.ts`：`RealScheduleRepository`（真实路径装配，方法全 async，校验失败走 rejected promise）+ `loadServiceOptions`（沿服务管理列表 best-effort 补充服务名/履约方式，失败回退 id 呈现不阻塞）。
- `src/shared/consumer-api.ts`：`send()` 路径白名单新增 schedule 家族（stores/{storeId}/availability-windows…、staff/{staffId}/availability-windows…、staff/{staffId}/service-capabilities）+ success 校验纳入。
- WXSS 铁律遵守：状态样式全部 className 模板变体（`sch-tag-open/soldout/closed` 等），CSS 注释与 services/page.css 同规。

## 设计源

Figma key `Usvn3d6UCVCAlDxou5KAK8`。任务预期「无排期维护原稿」经在线查证修正为：**原稿存在但语义冲突**——`12:6214`「首页-排期管理」为周模板+固定1小时槽模型（一键生成/复制到其他日期/7天×每小时胶囊），与 53号契约已裁决（分钟级、禁周模板三字段）直接冲突，且无服务×类型/容量/SOLD_OUT/排班/能力维度。已如实登记进 docs/08-engineering/20-设计源登记表-figma-map.md 新增 §4.1「视同设计缺稿」；页面沿 M 端现行页面规范（services 页 page.css measures/tokens → schedule/page.css）实现，未虚构设计。

## 门禁（本 worktree frontend-miniapp）

| 门禁 | 结果 |
|---|---|
| `npm ci` | PASS |
| `npx tsc --noEmit` | PASS（0 error） |
| `npm test` | PASS 203/203（新增 19：schedule-model 8 + schedule-repository 11） |
| `npm run build:weapp` | PASS（dist/merchant/pages/schedule/* 四页 js/json/wxml/wxss 齐全） |
| `npm run check:package` | PASS（mainBytes 772,203 < 2M；merchant 206,804 < 2M；total 4,115,082 < 20M；路由清单断言已同步登记 4 页） |

注：`package-check.cjs` 属路由清单门禁，新增页面须同步其期望清单（含 artifact 存在性断言），非产品行为变更。
`project.config.json` 已复制真实 appid 供本地预览，**未提交**。

## 本地预览

- 夹具模式：页面 URL 带 `preview=1`（可加 `scenario=normal|empty|load-error|closed|legacy`），无网络无会话；例：`/merchant/pages/schedule/index?preview=1`、`/merchant/pages/schedule/windows?preview=1&scenario=closed`（呈现「功能未开放」）。
- 真实模式：从工作台「排期管理」进入（admission ALLOWED 且含 `merchant.schedule.manage` action 时显示入口）；开关关闭时请求 404/503 → 失败关闭面板。

## 阻塞与待裁决（后端零改动，仅登记）

1. **控制器与契约不一致（真实缺陷，需后端切片修正）**：契约 §3.1「GET …/availability-windows 的 status ∈ OPEN/CLOSED/SOLD_OUT、商家工作台可按 status=SOLD_OUT 过滤」，但 `MerchantScheduleController.WINDOW_STATUSES = Set.of("OPEN","CLOSED")`（02abe89）——`status=SOLD_OUT` 在 HTTP 层 400（biz 层 `ScheduleMerchantQueryApiImpl.STATUSES` 实际已支持）。前端规避：不发送 SOLD_OUT 过滤，列表取全量后客户端分组；待后端修正后可直接切换为服务端过滤。
2. 契约无服务窗口/员工排班的分页参数（GET 全量返回）；页面以解码层 sanity 上限（1000 条）防御，超出即失败关闭。若真实门店窗量超限需契约补充分页。
3. 契约无「删除」：员工排班任务口径中的“删除”按关闭（保留历史）语义实现并明示。
4. 批量关闭 >200 的 400 属通用 `COMMON_INVALID_ARGUMENT`，无法与其他参数错误区分；页面文案统一提示含 200 上限。如需精准呈现需契约新增专用错误码（本批不做）。
5. LEGACY_UNVERSIONED（能力集合头缺失）503 与一般依赖故障在 HTTP 上同码（`COMMON_DEPENDENCY_UNAVAILABLE`），页面统一呈现「功能未开放」；「待盘点」细分文案无法从响应区分，如需细分需契约区分码（本批不做）。
6. 服务名/履约方式依赖服务管理列表（best-effort 增强）；服务模块未交付时页面以 `服务 {id}` 呈现，不阻塞排期维护。

## 遗留风险

- 未做真机/微信开发者工具运行时验证（本环境无 IDE 会话）；preview 模式逻辑由单测覆盖（映射/解码/mock），wxml 渲染由 build:weapp 产物存在性 + check:package 断言覆盖。
- 与并行 M 端（员工管理页）代理都会改 merchant 分包 app.config.ts 与 package-check.cjs 期望清单——本分支改动保持单一紧凑块（一行数组扩展 + 一段 artifact 断言），合并时按序 rebase。
