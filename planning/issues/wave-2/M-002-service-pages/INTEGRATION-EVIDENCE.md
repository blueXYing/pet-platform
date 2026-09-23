# M-002 服务管理页 × C-003 门店/服务读侧 —— CI 集成联调取证

日期：2026-09-22（机器时钟 2026-09-23）。分支 `codex/m002-service-pages-20260922` 集成 develop `25bf601`（PR#66~#69：服务写入方 + 门店读侧 + 通知消费侧适配）后的真实 API 联调记录。角色：M-002 CI（集成）。

## 1. 环境

- **本地后端**：`M002IntegrationServer`（测试域启动器，**不入库**，源码本轮保留于 worktree `backend/pet-boot/src/test/java/com/petplatform/boot/auth/M002IntegrationServer.java`，未 git add；运行时产物在 `D:/Temp/m002-ci/`）。组装方式＝develop 既有验收配方（`ServiceWriteHttpTest` + `CStoreControllerHttpTest` 同款 boot 装配），并遵循 MS1 本地验收先例：**Hikari 连接池**（防 Snowflake 单飞行道 3 分钟续租卡死）、固定端口、失败路径先关 context 再 DROP 库。
  - 开关：`pet.auth.c.enabled`、`pet.auth.admin.enabled`、`pet.merchant.application.enabled`（+成都开放城市）、`pet.merchant.application.notifications-enabled`、`pet.outbox.enabled`、`pet.service.query.enabled`、`pet.service.command.enabled`、`pet.service.review.notifications-enabled`、`pet.store.query.enabled`。
  - MySQL `jdbc:mysql://127.0.0.1:33452/`（协调者临时实例）+ Redis `16383`，隔离库 `auth001cm002ci_*`（进程退出 DROP）。Snowflake node 21。
  - **QA 缝隙（与库内验收测试同一缝，非生产件）**：`FixedWechatProvider`（`ok:`/`phone:` 登录码）、`PrivateAssetQueryPort`/`ServiceCoverAssetPort` 宽松事实桩、`ServiceCoverUrlPort` 确定性签名桩（`https://cover.example.invalid/signed/{assetId}`，+3600s）。其余链路（申请/审批/签署/准入/服务六路由/运营审核/C 读侧/Outbox）全部真实 Spring + 真实 MySQL/Redis + 真实 HTTP。
  - 种子：`merchant_agreement_version m002-ci-v1`（签署路由校验版本+SHA-256）与 6 个 ENABLED `service_category`（JDBC 播种，同 `ServiceWriteHttpTest` 先例）。
  - 运营账号 `qa-reviewer` 经**真实 HTTP 登录链**（attempts→验证码→verify→login，验证码 MAC 以测试同款 JDBC 定值）取得 Bearer；令牌只落在 `D:/Temp/m002-ci/server-metadata.txt`，不入库。
- **驱动**：`D:/Temp/m002-ci/integration-driver.mjs`（裸 HTTP 全链路）与 `D:/Temp/m002-ci/shared-layer-verify.ts`（tsx 直接 import 小程序真实 repository/decoder 层）。逐请求日志：`integration-evidence.jsonl` / `shared-layer-evidence.jsonl`（本目录外，本地留存）。

## 2. 真实 HTTP 全链路（裸 HTTP 层）

一次完整通过运行（每步 want=got、code=SUCCESS 除注明外）：

| 步骤 | 请求 | 结果摘要 |
|---|---|---|
| 消费者登录 | POST `/api/v1/c/auth/attempts`、POST `/wechat-login` | 201/200，取得 Bearer |
| 商家申请 | POST `/c/merchant-applications` → POST `/{id}/submit` | 201/200 |
| 运营审批 | GET `/admin/merchant-applications/{id}` → POST `/claim` → POST `/manual-verification` → POST `/decision` APPROVE | 200×4，`status=APPROVED`，`reservedMerchantId=96103453281046529` |
| 签署 | POST `/merchant/agreement/consent`（版本 m002-ci-v1+SHA-256） | 201 `signingStatus=SIGNED` |
| 准入 | GET `/c/auth/merchant-memberships` → GET `/merchant/auth/admission` | OWNER 成员；`admission=ALLOWED`，`storeId=96103453780168704` |
| 类目字典 | GET `/merchant/service-categories` | `{items:[6]}`，`{categoryId,categoryName,sortNo}`（实测 sortNo 可为 0——DB 默认值） |
| 建服务 | POST `/merchant/services`（X-Request-Id） | 201 `{serviceId:96103454090547200, merchantId, storeId, status:DRAFT, version:"0"}` |
| 幂等重放 | 同 X-Request-Id 同 body | 200 同 serviceId；**异 body 同 requestId → 409 `IDEMPOTENCY_KEY_CONFLICT`** |
| 工作台列表/详情 | GET `/merchant/services?merchantId&storeId` / GET `/merchant/services/{id}?merchantId&storeId` | 全状态扁平行（22 键，含 `coverAssetId` 平铺、`latestRejection`、`submissionNo/submittedAt`） |
| 提交审核 | POST `/{id}/online` body `{merchantId,storeId,expectedVersion}` | 200 `status=REVIEWING, version:"1"` |
| REVIEWING 不可见 | GET `/c/services/{id}` | **404 `SERVICE_NOT_FOUND`**（SVC-D1b，与不存在同响应） |
| 运营裁决 | POST `/admin/services/{id}/decision` body `{decisionType:APPROVE, expectedVersion}` | 200 `status=ACTIVE, version:"2", decisionId` |
| C 门店列表 | GET `/c/stores?city=chengdu`（**匿名无 Bearer**） | 200，items 含该门店（九字段投影） |
| C 门店详情 | GET `/c/stores/{id}`（匿名） | 200 `storeId/merchantId/storeName/merchantName/address/longitude/latitude/phoneMasked:null/cityCode` |
| C 店内服务 | GET `/c/stores/{id}/services`（匿名） | 200 items 含该服务；**cover 对象** `{coverAssetId:"590000000000000201", coverUrl:"https://cover.example.invalid/signed/…", coverUrlExpiresAt:"2026-09-23T05:40:49Z"}`（秒级精度） |
| C 服务详情 | GET `/c/services/{id}`（匿名） | 200 `salePrice:"128.00"`、`durationMinutes`、`fulfillmentType`、`description`、同 cover |
| 商家下架 | POST `/{id}/offline` body `{merchantId,storeId,expectedVersion}` | 200 `status=OFFLINE, version:"3"` |
| 下架后 C 读 | GET `/c/stores/{id}/services`；GET `/c/services/{id}`；GET `/c/stores` | 列表隐藏（items 空）；**404 `SERVICE_NOT_FOUND`**；门店仍在列表（店内空服务页语义） |
| 收件箱 | GET `/c/notifications`（属主会话） | 200 `items:[]`——**通知链未通**（见 §5） |

## 3. 共享客户端层真实 HTTP（页面同款依赖层）

`shared-layer-verify.ts` 以 tsx import **本仓真实模块**（`ConsumerApi`、`RealServiceManageRepository`、`RealServiceRepository`、`RealStoreRepository`、`emptyDraft`、全部 decoder）对新链路商家（merchantId 96105234346102785 / storeId 96105235273043968 / serviceId 96105235596005376）重跑：

- `categories()` → 新形状 `{categoryId,categoryName,sortNo}` 解码通过；
- `create()` → 5 键回执 `{serviceId,merchantId,storeId,status,version}` 解码通过；
- `list()/detail()` → 22 键扁平行（`coverAssetId` 平铺、`applicablePetTypes` 数组）解码通过；
- `update()/submitOnline()/takeOffline()` → 版本递增 0→1→2→（APPROVE）→3；
- C 端：`RealStoreRepository.list/detail`（匿名）九字段；`RealServiceRepository.list/detail` **cover 对象解码通过**（含秒级 `coverUrlExpiresAt`）；
- 下架后：列表隐藏 + `detail` 抛 `ApiError 404 SERVICE_NOT_FOUND`；过期版本 PUT → 409，`serviceManageMessage` 输出「内容已被修改，请刷新后重试…」。
- 输出 `M002_SHARED_LAYER result=PASS`。

**验证层级声明**：以上为「共享客户端层真实 HTTP」（与页面消费的同一 repository/decoder 代码在 node 侧直连本地服务器），**非模拟器窗口内验证**——DevTools 自动化本轮不可用（见 §5），模拟器窗口级验证与 VIS 留给用户人工验收。

## 4. 契约对齐差异清单（develop 定稿 vs 本分支原 Mock/仓储）与修复

对照 10 号 §3.3/§3.3.1（含封面增补）/§4.10.1/§5.9 与后端真实投影（`MerchantServiceController`/`CServiceController`/`CStoreController`/`ServiceWriteAdminController`）：

| # | 差异 | 修复 |
|---|---|---|
| 1 | 类目字典：后端 `{items:[{categoryId,categoryName,sortNo}]}`；原前端裸数组 `{id,name,sortNo}` | `ServiceCategoryView`/`decodeCategory(List)` 对齐信封与字段名；页面/校验/Mock/测试同步 |
| 2 | 工作台列表行：后端与详情同一 22 键扁平投影（`coverAssetId` 平铺、无 cover 对象）；原列表行为 10 键子集+cover 对象 | `ManagedServiceItem`＝全字段扁平行，`ManagedServiceDetail` 同型别名；Mock/fixtures/测试同步 |
| 3 | 工作台详情：`cover` 对象 → 平铺 `coverAssetId`（cover 对象仅 C 端 §3.3.1） | 详情扁平化；编辑页封面回显改为「已绑定封面素材」锚定态（不伪造未签名 URL） |
| 4 | 命令回执：后端 5 键 `{serviceId,merchantId,storeId,status,version}`；原 3 键 | `decodeCommandReceipt` 对齐（exact 严格键集会拒多余键，必须改） |
| 5 | `applicablePetTypes`：宽松草稿可 `null`；原解码强制数组 | `null → []` 规范化 |
| 6 | C 端服务列表/详情：后端**恒带** `cover`（无绑定为 `null`，有绑定三键齐）；原解码无 cover 键（严格 exact 必拒） | `ServiceItemView/ServiceDetailView` 增 `cover: ServiceCoverView\|null`；fixtures 补 cover（含 null 用例） |
| 7 | `coverUrlExpiresAt` 签名端为**秒级** ISO（`2026-09-23T05:40:49Z`）；原时间戳正则强制毫秒 | C 端 cover 时间解码放宽为可选毫秒 |
| 8 | 类目 `sortNo` 实测可为 0（DB 默认）；原解码下界 1 | 下界放宽为 0（负数仍拒） |
| 9 | STR-D8 四条 C 读路由统一匿名（含 `/c/stores/{id}/services`、`/c/services/{id}`）；原 `anonymousRequest` 白名单仅两条门店路由且服务仓储走鉴权 `request()` | 白名单扩为四路由正则；`RealServiceRepository.list/detail` 改 `anonymousRequest`（Bearer 可选语义） |
| 10 | 通知跳转函数命名/文案：develop（PR#69 已合并基线，NTF 交接定稿）`jumpLabelFor`/「查看服务」vs 本分支 `jumpLabelForNotification`/「查看服务管理」 | 合并取 develop 基线（减少跨角色漂移）；测试断言取两侧并集 |

无差异项复核通过：六路由路径/参数位置（GET query merchantId&storeId；写 body expectedVersion，27 号惯例）、错误码适用面（提交缺必填 400 `COMMON_INVALID_ARGUMENT`；409 `SERVICE_STATE_NOT_ALLOWED`/`COMMON_CONFLICT`/`IDEMPOTENCY_KEY_CONFLICT`；C 端 404 `SERVICE_NOT_FOUND` 防探测）、`salePrice` 两位小数字符串、门店九字段投影、信封 `success/code/message/data/traceId`、201/200 幂等重放语义。

## 5. 边界与如实声明

1. **通知链未通**：`EventOutboxConfiguration` 中 `ServiceReviewedConsumer` bean 注册仍为预留注释（W 切片未合并）；`pet.service.review.notifications-enabled=true` 无消费者。实测运营 APPROVE 后属主收件箱 `/c/notifications` 为空——`ServiceReviewedEvent.v1` 应滞留 Outbox。**消息中心跳转端到端（点「查看服务」→ `/merchant/pages/services/index`）未验证**；前端白名单/文案已由单测覆盖（merge 后两侧断言并集）。
2. **模拟器窗口级验证/VIS 未做**：本机已装微信开发者工具（`D:/soft/微信web开发者工具/cli.bat`），但 IDE 服务端口关闭且需人工在 IDE 安全设置开启（`cli islogin` 双语报错，echo y 管道无效）——自动化取证不可用，跳过并记录。VIS 与真机走查留给用户模拟器人工验收。
3. **QA 缝隙**：微信登录/素材归属/封面签名为测试桩（与库内验收测试同缝）；`coverUrl` 为确定性签名占位（真实签名端口在 main 代码尚无实现——`ServiceQueryConfiguration` 注释明确无 signer 时失败关闭 503；本地以桩提供以验证 §3.3.1 形状）。**真实 OSS 上传链（SERVICE_COVER 管线）未在本次联调范围**（角色 B 交付件，31 号）。
4. 后端无改动（发现的问题均为前端对齐项，已修）；`M002IntegrationServer` 为测试域启动器，未提交。
5. `project.config.json`（本地真实 appid）保留工作区未提交。

## 6. 覆盖矩阵（分层）

| 链路场景 | 模块单测（162 项） | 共享层真实 HTTP | 模拟器窗口 |
|---|---|---|---|
| 发布：申请→审批→签署→准入 | —（develop CI 已覆盖申请链） | ✅ 真实 HTTP | 留人工 |
| 建服务（草稿→幂等→编辑→提交） | ✅ Mock 状态机/409/400 | ✅ 真实六路由+幂等重放/异参 409 | 留人工 |
| 运营 APPROVE→ACTIVE | — | ✅ decision API | — |
| 找店：/c/stores 含新店 | ✅ Mock 契约 | ✅ 匿名真实列表 | 留人工 |
| 进店：/c/stores/{id} 九字段 | ✅ | ✅ 匿名真实详情 | 留人工 |
| 看到：店内服务+详情含 cover | ✅（含 cover/null/秒级过期解码） | ✅ 真实 cover 三件套 | 留人工 |
| 下架：列表隐藏 | ✅ | ✅ | 留人工 |
| 详情 404（防探测，含 REVIEWING 态） | ✅ | ✅ 404 `SERVICE_NOT_FOUND` | 留人工 |
| 通知跳转 | ✅ 白名单/文案单测 | ❌ 消费侧未接线（W 切片） | 留人工 |

## 7. 工程门（对齐修复后复跑）

- `npm test`：**162/162 通过**（0 失败）；
- `npm run typecheck`（tsc --noEmit）：0 错误；
- `npm run build:weapp`：成功（dist 产物生成）；
- `npm run check:package`：exit 0（`platformLimitVerified:true`）。

后端测试未在本 worktree 重跑（后端无改动，develop CI 已绿；本联调以真实服务器运行替代）。
