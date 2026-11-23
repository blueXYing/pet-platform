# P1 商家营销工具 E2E 验收报告

- 日期：2026-11-18
- 范围：Phase 1（营销工具 hub + 节日营销 + 商家封面素材）的真实 Spring Boot + MySQL + Redis HTTP 全链
- 结论：**PASS**（API 级全链通过；发现并修复 1 个真实页面兼容问题）
- worktree：`/mnt/d/wt-marketing-tools`
- 分支：`codex/merchant-marketing-tools-20261117`

## 1. 环境

| 项 | 值 |
|---|---|
| 后端 | `E2eCoreLoopServer`（测试域启动器），`127.0.0.1:18082`，JDK 21.0.13 |
| 数据库 | 本机 MySQL `127.0.0.1:13306`，全新隔离库 `auth001czq34a12_e2e_core_loop_0019102858d64ae38d989cf3f3f7a249` |
| Redis | `127.0.0.1:16379`（隔离 auth Redis） |
| 营销开关 | `pet.marketing.enabled/admin.http/merchant.http/c.http=true`；`pet.marketing.asset.public-base-url=http://127.0.0.1:18082`；`pet.marketing.asset.allow-http=true` |
| classpath | 为规避 WSL D 盘 DrvFs 类加载过慢，将 P1 `target/classes` + `pet-boot/target/test-classes` 复制到 `/tmp/wt-e2e-classes-p1` 后启动；源码与 worktree 一致 |
| 账号 | 种子 C 用户 `710100`；商家 `710301`；门店 `710302`；运营 `e2e-round2-operator`（E2E fixture，密码不入仓） |

## 2. 执行的链路

| # | 步骤 | 结果 |
|---|---|---|
| 1 | C 会话：`/c/auth/attempts` → `/c/auth/wechat-login` | 200，取得 accessToken |
| 2 | 运营会话：`/admin/auth/attempts` → `/admin/auth/login`（Origin + attempt cookie） | 200，取得 accessToken |
| 3 | 商家封面：`POST /merchant/marketing/assets?merchantId=710301&storeId=710302`（multipart `file`） | 200，返回 `assetId` / `url` / 尺寸 / sha256 |
| 4 | 公开素材读取：`GET /c/marketing/assets/{assetId}` | 200，`image/*`，非空字节 |
| 5 | 非法主题：`POST /merchant/marketing/my-campaigns` 使用 `themeCode=NOT_A_THEME` | 400 `COMMON_INVALID_ARGUMENT`（边界拒绝） |
| 6 | 创建草稿：合法主题/玩法/封面/说明/有效期 | 200，`DRAFT`，返回 `id`/`version` |
| 7 | 编辑草稿：`PUT`，回传字符串 `expectedVersion` | 200，`DRAFT`，version 递增 |
| 8 | 提交审核：`POST .../submit`，回传字符串 `expectedVersion` | 200，`PENDING_REVIEW` |
| 9 | 运营待审列表：`GET /admin/marketing/campaigns?ownerType=MERCHANT&status=PENDING_REVIEW` | 命中该活动，`description/themeCode/themeLabel/highlightText/playType/coverAssetId/coverUrl` 完整 |
| 10 | 运营发布：`POST /admin/marketing/campaigns/{id}/publish`，回传字符串 `expectedVersion` | 200，`ONLINE` |
| 11 | 商家列表：`GET /merchant/marketing/my-campaigns` | 命中该活动，`ONLINE`，P1 字段与编辑后说明一致 |
| 12 | 商家概览：`GET /merchant/marketing/overview` | `total=1, active=1, pendingReview=0, enrolled=0` |

## 3. 证据

```text
campaignId=116350187983069184
assetId=116350187584610304
assetUrl=http://127.0.0.1:18082/api/v1/c/marketing/assets/116350187584610304
finalStatus=ONLINE
overview={"totalCampaignCount":1,"activeCampaignCount":1,"pendingReviewCount":0,"enrolledCampaignCount":0}
```

E2E 服务为临时实例；停止后数据库由 `databaseLifecycle=DROP_ON_PROCESS_SHUTDOWN` 删除。以上 ID/状态取自本次运行。

## 4. 发现并修复的问题

### P1-E2E-01：`expectedVersion` 字符串/整数不一致（已修）

- 现象：商家小程序与运营后台从读接口拿到 `version` 为十进制字符串，写命令按同一字符串回传；营销控制器 `longValue` 只接受 JSON 整数，导致「编辑草稿」「提交审核」「运营发布」等实际页面动作 400 `COMMON_INVALID_ARGUMENT`。
- 证据：E2E 首次运行在 `POST .../submit` 返回 400；改用整数后全链通过。
- 修复：`MarketingHttpSupport.longValue` 同时接受 JSON 整数与等值数字字符串（拒绝负数/小数/前导零/溢出/非数字）。
- 契约：76 号 §4.4 示例改为字符串 `expectedVersion`，并明确两种线形状都接受。
- 测试：新增 `MarketingHttpSupportTest`，覆盖整数、数字字符串、非法形状。
- 影响：同时修复商家侧编辑/提交/取消/报名撤回与运营侧发布/编辑/驳回等页面的同一类兼容问题。

## 5. 回归测试

| 检查 | 结果 |
|---|---|
| `MarketingHttpSupportTest` | PASS |
| `pet-boot` `Marketing*Test`（含配置/异常处理） | PASS |
| `pet-marketing-biz` `MarketingMerchantToolsMySqlTest` + `MarketingHotServiceMySqlTest` | 20/20 PASS |
| `check-persistence-style.py` | PASS |
| `check-module-deps.py` | PASS（43 模块，无 biz→biz） |
| 小程序 `npm test` | 772/772 PASS |
| 小程序 `npm run typecheck` | PASS |
| 小程序 `npm run build:weapp` | PASS |

## 6. 未覆盖/遗留

1. **WeChat DevTools 真机 UI 全链未跑**：本次为真实 Spring + MySQL + Redis 的 HTTP 全链；页面交互由小程序单测/typecheck/build 覆盖。建议在 DevTools 可用时补一轮点击级走查（封面选择 → 保存 → 提交 → 运营发布）。
2. **admin Playwright e2e 未跑**：WSL 缺浏览器系统库（`libnspr4`）且无免密 sudo，属环境存量限制；本次已用真实 admin HTTP 发布链路替代验证。
3. 存量旧活动 `themeCode/playType` 为 null：前端解码容忍；商家编辑旧草稿必须补选主题（设计如此）。
