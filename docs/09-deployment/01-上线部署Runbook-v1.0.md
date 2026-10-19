# 上线部署 Runbook v1.0

状态：工程交付文档（2026-10-19 目录重核 42.0 批次新增）。目标读者：执行上线操作的运维/后端工程师。标准：**照着本文敲命令即可完成上线**；仓库里不存在的设施（反向代理配置、Docker 编排、CI 上传通道、真实微信通知适配器）一律如实标注"需自备"，不虚构。

与其他文档的关系（冲突时以代码与下列顺序为准）：

- [21号《部署开关组合清单》](../08-engineering/21-部署开关组合清单-v1.0.md)：开关依赖与组合合法性的**单点权威**；
- [35号《部署开关放开与真机验收安排裁决补充》](../01-prd/35-部署开关放开与真机验收安排裁决补充-v1.0.md)：默认值裁决（A/B/C 分类）；
- [36号《治理写动作最小授权裁决补充》](../01-prd/36-治理写动作最小授权裁决补充-v1.0.md)：运营写动作授权范围；
- 本文：把上述裁决落成操作步骤。

已知文档差异（如实披露）：21号 §1.12 中 `pet.admin.ops.http.enabled`、`pet.coupon.grant.enabled`、`pet.coupon.void.enabled` 三行文字仍写"待修复后再开"，落后于合并后 `application.yml`（#185 并集 #183 后已翻默认 true，commit a6ff55a2）。**以 application.yml 实际值为准**；21号三行修正归开关 owner 后续 PR。

---

## 1. 前置条件清单

### 1.1 硬前置（缺一不可）

| # | 项 | 说明 | 谁提供 |
|---|---|---|---|
| 1 | 服务器 | Linux x86_64，能跑 JDK 21 与 MySQL/Redis（可同机或分离）。仓库**不含** Docker 编排/K8s/IaC，主机初始化需自备 | 运营方 |
| 2 | 域名 + ICP 备案 + HTTPS 证书 | 见 §1.2 警告——**最早启动项** | 运营方 |
| 3 | MySQL 8.x | 全部业务表（测试基线 8.4.9） | 运维 |
| 4 | Redis | 管理端会话/授权缓存 + C 端会话缓存 | 运维 |
| 5 | 微信小程序账号（appid/app-secret） | 微信公众平台 mp.weixin.qq.com → 管理 → 开发管理 → 开发设置 | 运营方 |
| 6 | 拉卡拉商户收银资格 | 商户入驻后取得收单凭证与退款 RSA 凭证（见 §2.3） | 运营方/拉卡拉 |
| 7 | 对象存储（S3 兼容，如阿里云 OSS） | 私有素材/售后证据/百科封面（见 §2.5） | 运营方 |
| 8 | JDK 21 + Maven + Node（构建机） | 后端 `mvn package`、前端 `npm run build`；仓库 scripts/ 既有脚本按 Adoptium JDK 21 编写 | 运维 |
| 9 | ClamAV（可选） | 私有素材病毒扫描；缺省时**扫描失败关闭**（上传不可用），不阻塞其他能力 | 运维 |

### 1.2 备案与 HTTPS 警告（最早启动）

> **⚠️ 提前 3~4 周启动**：
> 1. 微信小程序 wx.request/uploadFile/downloadFile **强制 HTTPS 且域名必须已 ICP 备案**——未备案域名在小程序内无法配置为合法域名，C 端完全不可用。
> 2. ICP 备案审核周期通常 **1~3 周**，是整条上线关键路径上最不可控的一环。**服务器与域名确定后第一时间提交备案**，再并行做其余步骤。
> 3. HTTPS 证书需覆盖 `<PLACEHOLDER:生产域名，如 api.example.com>`；管理端 origin（§2.1）同样要求 HTTPS。
> 4. 小程序后台"服务器域名"可配置次数有限（每月少量），一次性填好：request/uploadFile/downloadFile 合法域名均填 `https://<PLACEHOLDER:生产域名>`。

### 1.3 仓库不含、需自备的设施（如实清单）

- 反向代理/静态服务器（Nginx 等）：frontend-admin 构建物为纯静态 `dist/`，且管理端 API 以同源 `/api/v1/...` 访问（`frontend-admin/src/request.ts` 以 `window.location.origin` 解析）——需要反代完成"静态资源 + /api 转发到后端 8080 + TLS 终结"。仓库**无** Nginx 配置文件。
- Docker/编排、监控告警、日志收集：仓库仅暴露 Spring Actuator（`/actuator/health|info|metrics|prometheus`），接入哪套监控需自备。
- 真实微信订阅消息投递适配器（见 §2.8）。
- CI 上传小程序通道：上传体验版只能微信开发者工具手工完成（见 §3.6）。

---

## 2. B 类凭据配置表

口径（35号 §二）：B 类开关的装配期**硬性要求**第三方凭据或部署基座——缺配 = **启动失败**（fail-fast）；个别能启动的缺配面 = **运行期 503 失败关闭**。每项标注失败行为。

### 2.1 部署基座：管理端会话（`pet.auth.admin.enabled=true`）

全平台 ID 提供器（生产 Snowflake）唯一来源，**任何部署都必须先配它**；配好后 A 类开关全部自动生效。以下键缺任一 = 启动失败。

| 键 | 去哪申请/怎么生成 | 不配会怎样 |
|---|---|---|
| `pet.auth.admin.key-id` | 平台自签，任意非空标识串（如 `admin-key-1`） | 启动失败 "Explicit separate auth secrets must be configured" |
| `pet.auth.admin.mac-key-base64` | 平台自签：`openssl rand -base64 32` | 同上；三把密钥必须互相独立（同值即失败） |
| `pet.auth.admin.encryption-key-base64` | 平台自签：`openssl rand -base64 32`（与上一把不同值） | 同上 |
| `pet.auth.admin.origin` | 管理端可信 origin，必须 `https://<PLACEHOLDER:管理端域名>`（本机调试允许 localhost/127.0.0.1 http） | 启动失败 "HTTPS origin or isolated loopback origin required" |
| `pet.auth.admin.redis-host` / `redis-port` / `redis-password` | 指向 §3.1 的 Redis | 启动失败（授权缓存无法装配） |
| `pet.auth.admin.audit-path` | 宿主机上一个**显式文件路径**（如 `/var/log/pet/admin-audit.log`），目录需可写 | 启动失败 "Explicit durable audit sink path required" |
| `pet.auth.admin.node-id` | Snowflake 节点号；多实例部署必须互异（节点状态落库，见 docs/03-database/25号） | 缺省 -1 单机可用；多实例不配会撞节点 |

### 2.2 微信小程序会话（`pet.auth.c.enabled=true`，C 端一切路由的总闸）

| 键（环境变量 → yml） | 去哪申请 | 不配会怎样 |
|---|---|---|
| `AUTH_WECHAT_APP_ID` → `pet.auth.c.wechat.app-id` | 微信公众平台 → 开发设置 → AppID | 启动失败 "must be configured (deployment environment)" |
| `AUTH_WECHAT_APP_SECRET` → `pet.auth.c.wechat.app-secret` | 同上 → AppSecret（重置即旧值失效） | 同上 |
| `AUTH_C_REDIS_HOST` / `AUTH_C_REDIS_PORT` → `pet.auth.c.redis-host/port` | 指向 §3.1 Redis | 启动失败 |

注：配桩值可以启动，但真机登录必然失败——真机验收前必须换成真实凭据（35号：真机验收延后至正式上线前统一执行）。

### 2.3 拉卡拉支付/退款（资金链总闸）

支付发起 `pet.payment.dispatch.enabled=true` 与支付基础 `pet.payment.foundation.enabled=true` 需**同时**为 true。

| 键 | 去哪申请 | 不配会怎样 |
|---|---|---|
| `pet.payment.dispatch.environment` | 拉卡拉联调环境 `SIT`（https://test.wsmsd.cn/sit/）或生产 `PRODUCTION`（https://s2.lakala.com/） | 启动失败 "Payment dispatch credentials or launch configuration unavailable" |
| `pet.payment.dispatch.app-id` | 拉卡拉商户入网后的 appId | 同上 |
| `pet.payment.dispatch.merchant-serial` | 拉卡拉商户证书序列号 | 同上 |
| `pet.payment.dispatch.merchant-private-key-path` | 商户 RSA 私钥 **PEM 文件路径**（PKCS#8，≤16KB；文件放宿主机，不入仓库） | 同上 |
| `pet.payment.dispatch.platform-serial` | 拉卡拉平台证书序列号 | 同上 |
| `pet.payment.dispatch.platform-certificate-path` | 拉卡拉平台证书文件路径 | 同上 |
| `pet.payment.dispatch.parameter-key-path` | 参数 AES 密钥文件（内容为 base64 的 32 字节密钥） | 启动失败 "Dispatch settings unavailable" |
| `pet.payment.dispatch.out-org-code` / `subject` / `request-ip` / `notify-url` | 拉卡拉开户资料；`notify-url` 填 `https://<PLACEHOLDER:生产域名>/api/v1/...`（回调可达） | 同上 |
| `pet.payment.lakala.stores.<storeId>`（merchant-id/merchant-no/term-no/sub-app-id） | 拉卡拉门店/终端绑定 | 缺绑定=支付绑定校验失败关闭 |
| `pet.payment.lakala.notification-certificate-path` | 拉卡拉回调验签证书 | **可启动**，但支付回调全部验签失败关闭——等于半开 |
| `pet.payment.lakala.channel-time-zone` | 渠道时区（如 `Asia/Shanghai`） | 启动失败（dispatch 校验） |

**退款凭证**：不另设键——退款渠道复用上表同一套 dispatch RSA 凭证。`pet.refund.late.enabled`（+worker）、`pet.order.merchant.*`、`pet.aftersale.*` 任一打开时，缺凭证 = 启动失败 "Refund provider credentials or launch configuration unavailable"。

### 2.4 各域保护密钥（缺配 = 占位符解析失败/启动失败）

| 键 | 生成方式 | 不配会怎样 |
|---|---|---|
| `pet.order.merchant.protection-key` | `openssl rand -base64 32` | 开启商家订单域时启动失败 |
| `pet.refund.application.protection-key` | 同上 | 启动失败 |
| `pet.refund.application.reason-codes` | 退款原因码白名单（非空集合，按 49号契约词表） | 启动失败（要求非空） |
| `pet.verification.credential.key-id` / `encryption-key` / `lookup-key` | key-id 自签；两把密钥 `openssl rand -base64 32` | 启动失败 |
| `pet.aftersale.protection-key` | 同上 | 启动失败 |

### 2.5 OSS 私有素材与扫描

`pet.private-assets.enabled=true`（及入驻内核硬依赖它）时：

| 键（环境变量） | 去哪申请 | 不配会怎样 |
|---|---|---|
| `OSS_ENDPOINT` / `OSS_REGION` / `OSS_BUCKET` | 云厂商控制台建 S3 兼容 bucket（如阿里云 `oss-cn-hangzhou.aliyuncs.com`） | 启动失败（required 校验） |
| `OSS_ACCESS_KEY_ID` / `OSS_SECRET_ACCESS_KEY` | 云厂商 RAM 密钥（授予该 bucket 读写） | 同上 |
| `OSS_PUBLIC_BASE_URL` | 可选；CDN 公网基底，缺省按 bucket+endpoint 拼接 | 不配不失败 |
| `PRIVATE_ASSET_GRANT_KEY_VERSION` / `PRIVATE_ASSET_GRANT_HMAC_KEY_BASE64` | 平台自签（版本号 + `openssl rand -base64 32`） | 上传/授权请求 503 失败关闭 |
| `PRIVATE_ASSET_REASON_KEY_VERSION` / `PRIVATE_ASSET_REASON_AES_KEY_BASE64` | 平台自签 | 同上 |
| `PRIVATE_ASSET_CLAMAV_HOST` / `PRIVATE_ASSET_CLAMAV_PORT` / `PRIVATE_ASSET_CLAMAV_TIMEOUT_MILLIS` | 自建 ClamAV 服务地址 | 扫描失败关闭（可启动） |

本地惯例：OSS 密钥放 `ops/oss.env.local`（git 忽略，不入库；`scripts/oss-sync-assets.ps1` 读同一文件）。

### 2.6 入驻主体/受保护值加密（`pet.merchant.subject/protection.enabled=true`）

| 键 | 生成方式 | 不配会怎样 |
|---|---|---|
| `MERCHANT_SUBJECT_POLICY_VERSION` / `MERCHANT_SUBJECT_HMAC_KEY_BASE64` | 版本号 + `openssl rand -base64 32` | 启动失败（密钥独立性校验） |
| `MERCHANT_PROTECTED_KEY_VERSION` / `MERCHANT_PROTECTED_AES_KEY_BASE64` / `MERCHANT_PROTECTED_HMAC_KEY_BASE64` | 版本号 + 两把独立密钥 | 同上 |

下单地址/备注加密同源依赖 `pet.merchant.protection.enabled`；不配时下单该字段请求 503 失败关闭。

### 2.7 需要部署提供、仓库没有的 bean（如实清单）

| 项 | 谁需要 | 不提供会怎样 |
|---|---|---|
| `UserPhoneVerificationApi` bean | `pet.merchant.staff-member.enabled`（员工邀请绑定） | 该域无法开启：启动失败 bean 注入失败（不配 staff-member 即不受影响） |
| 真实微信订阅消息适配器 bean + 模板凭据 | `pet.notification.delivery.enabled`（外部投递） | 可启动，投递任务全部失败关闭（UnconfiguredWechatDeliveryAdapter）；站内通知不受影响 |

---

## 3. 部署步骤

约定：以下命令在 Linux 部署机执行；`<PLACEHOLDER:...>` 为占位，禁止把真实凭据写进仓库。

### 3.1 MySQL / Redis 前置

```bash
# MySQL 8.x：建库建账号（示例，密码占位）
mysql -u root -p -e "CREATE DATABASE pet_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
mysql -u root -p -e "CREATE USER '<PLACEHOLDER:业务账号>'@'%' IDENTIFIED BY '<PLACEHOLDER:密码>'; GRANT ALL PRIVILEGES ON pet_platform.* TO '<PLACEHOLDER:业务账号>'@'%';"
# Redis：按需 requirepass；记录地址端口供 §3.4 使用
```

### 3.2 建库跑 Schema（docs/03-database 全链顺序）

**如实警示：生产合并迁移尚未建立。** `backend/pet-boot/src/main/resources/db/migration/` 仅含 README（要求实施前合并出有序 Flyway 迁移，未做）；当前全仓统一的建库方式就是按下列顺序执行 `docs/03-database/*.sql`。该顺序与 pet-boot 测试装配 `E2eCoreLoopServer.loadSchemas`（#183 FA 测试补齐后的依赖顺序清单）一致：

```
25-Snowflake-Worker-Schema-v0.1.sql          # 节点表，必须最先
06-核心数据库Schema-v0.1.sql
14-Command-Idempotency-Schema-v0.1.sql
26-Admin-Auth-Schema-v0.1.sql
28-Merchant-Agreement-Schema-v0.1.sql
29-Merchant-Application-Schema-v0.1.sql
13-Async-Infra-Schema-v0.1.sql               # outbox/async_task：worker 类开关的前置
31-Private-Asset-Schema-v0.1.sql
33-Service-Write-Schema-v0.1.sql
35-Merchant-Staff-Audit-Schema-v0.1.sql
52-Merchant-Staff-Identity-Schema-v0.1.sql   # 64号治理面 staff-members 读依赖（#183 补）
54-Merchant-Staff-Binding-Schema-v0.1.sql
37-Reservation-Protection-Foundation-Schema-v0.1.sql
38-Booking-Create-Schema-v0.1.sql
39-Booking-Expiry-Schema-v0.1.sql
40-Payment-Foundation-Schema-v0.1.sql
41-Payment-Dispatch-Schema-v0.1.sql
42-Late-Refund-Execution-Schema-v0.1.sql
43-Payment-Refund-Dispatch-Schema-v0.1.sql
44-Late-Refund-Order-Projection-Schema-v0.1.sql
45-Merchant-Order-Actions-Schema-v0.1.sql
46-Order-Reschedule-Schema-v0.1.sql
47-Verification-Credential-Schema-v0.1.sql
48-Verification-Completion-Schema-v0.1.sql
49-Refund-Application-Schema-v0.1.sql
50-AfterSale-Workflow-Schema-v0.1.sql
51-AfterSale-Http-Schema-v0.1.sql
63-Admin-Trade-Disposition-Schema-v0.1.sql
60-Points-Task-Schema-v0.1.sql               # 含 62号种子依赖顺序
62-Baike-Content-Schema-v0.1.sql             # 携带已发布品种内容种子
```

执行：

```bash
cd <PLACEHOLDER:仓库检出目录>
for f in 25 06 14 26 28 29 13 31 33 35 52 54 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 63 60 62; do
  file=$(ls docs/03-database/${f}-*.sql)
  mysql -h <PLACEHOLDER:DB_HOST> -u <PLACEHOLDER:业务账号> -p pet_platform < "$file"
done
```

**opt-in Flyway 迁移（默认全关，生产共享库不执行）**：`db/admin-auth-migration/V26`、`db/service-migration/V27+V28`、`db/schedule-migration/V29`、`db/staff-member-migration/V30+V31`、`db/admin-trade-migration/V32`。这些仅面向"隔离库"（库名前缀硬校验，如 `auth001_*`/`schw001_*`），由 `pet.*.migration-enabled` + `pet.*.migration-database` 显式指定才执行；误配 = 启动失败保护（35号 §五：误开 = 对共享库执行迁移，绝不默认开）。**生产按 §3.2 的 SQL 顺序建库即可，无需触发任何 opt-in 迁移。**

### 3.3 后端构建

```bash
# 构建机：JDK 21 + Maven
cd backend && mvn -T 4 -DskipTests package
# 产物：pet-boot/target/pet-boot-0.1.0-SNAPSHOT-exec.jar   ← 可执行 fat jar（classifier=exec）
#       pet-boot/target/pet-boot-0.1.0-SNAPSHOT.jar        ← 普通 jar，仅供架构测试，勿部署
```

### 3.4 后端启动（jar 启动参数/环境变量）

部署机环境变量（全部经 `application.yml` 占位符注入，无默认凭据）：

```bash
export DB_HOST=<PLACEHOLDER:DB_HOST> DB_PORT=3306 DB_NAME=pet_platform
export DB_USER=<PLACEHOLDER:业务账号> DB_PASSWORD=<PLACEHOLDER:密码>
export REDIS_HOST=<PLACEHOLDER:REDIS_HOST> REDIS_PORT=6379 REDIS_PASSWORD=<PLACEHOLDER:>
export SERVER_PORT=8080
export SPRING_PROFILES_ACTIVE=prod
# ——B 类凭据（§2 全表；未配齐的域保持其开关默认关，不要手工开）——
export AUTH_WECHAT_APP_ID=<PLACEHOLDER> AUTH_WECHAT_APP_SECRET=<PLACEHOLDER>
export OSS_ENDPOINT=<PLACEHOLDER> OSS_REGION=<PLACEHOLDER> OSS_BUCKET=<PLACEHOLDER>
export OSS_ACCESS_KEY_ID=<PLACEHOLDER> OSS_SECRET_ACCESS_KEY=<PLACEHOLDER>
# 其余 pet.auth.admin.* / pet.payment.* / 保护密钥等经外置配置或 -D 注入，见 §2
```

管理端会话密钥等 `pet.auth.admin.*` 键不是环境变量占位，用 Spring 标准松绑定注入（外置 `application-prod.yml` 或 `-D` 参数均可）：

```bash
java -jar pet-boot-0.1.0-SNAPSHOT-exec.jar \
  --spring.profiles.active=prod \
  --pet.auth.admin.enabled=true \
  --pet.auth.admin.origin=https://<PLACEHOLDER:管理端域名> \
  --pet.auth.admin.key-id=<PLACEHOLDER:key-id> \
  --pet.auth.admin.mac-key-base64=<PLACEHOLDER:openssl rand -base64 32> \
  --pet.auth.admin.encryption-key-base64=<PLACEHOLDER:另一把 openssl rand -base64 32> \
  --pet.auth.admin.redis-host=$REDIS_HOST --pet.auth.admin.redis-port=$REDIS_PORT \
  --pet.auth.admin.audit-path=<PLACEHOLDER:/var/log/pet/admin-audit.log> \
  --pet.auth.admin.node-id=1 \
  2>&1 | tee /var/log/pet/boot.log
```

健康检查：`curl -fsS http://127.0.0.1:8080/actuator/health` → 期望 `{"status":"UP"}`（Actuator 暴露 health/info/metrics/prometheus）。

### 3.5 初始化运营账号（bootstrap）

首个运营账号**没有**默认值/HTTP 注册通道，只能用宿主机交互式维护 runner：

```bash
java -jar pet-boot-0.1.0-SNAPSHOT-exec.jar \
  --pet.auth.admin.enabled=true --pet.auth.admin.maintenance=true \
  --spring.main.web-application-type=none \
  # 其余 pet.auth.admin.* 键同 §3.4
# 交互提示依次输入：Operation=bootstrap / 原因 / 新密码 / 账号
# 密码经私有 console 输入，禁止命令行参数传密钥（runner 会拒绝）
# 完成 Ctrl+C 退出，再按 §3.4 正常起服（maintenance 不带）
```

### 3.6 前端构建与部署

**frontend-admin（运营网页，静态部署）：**

```bash
cd frontend-admin
npm ci
npm run build          # 含 tsc --noEmit 门禁；产物 dist/
# 部署：把 dist/ 发布到反代的静态根；反代规则（自备，仓库无配置文件）：
#   /                 → dist/ 静态资源
#   /api/v1/**        → 反代到 http://127.0.0.1:8080（同源，管理端以 window.location.origin 解析）
# 整站 TLS：管理端 origin 校验强制 HTTPS（§2.1）
```

**frontend-miniapp（小程序，体验版上传）：**

```bash
cd frontend-miniapp
npm ci
PET_C_API_ORIGIN=https://<PLACEHOLDER:生产域名> npm run build:weapp   # 产物 dist/
# 上传体验版（无 CI 通道，手工）：
# 1. 微信开发者工具导入 frontend-miniapp（project.config.json 的真实 appid 由本地维护，不入库——
#    本地该文件未被提交属正常，需手工填入真实 appid）
# 2. 工具右上角"上传"，填版本号与备注 → 公众平台"版本管理"设为体验版
# 3. 平台后台"开发设置→服务器域名"确认 request/uploadFile/downloadFile 合法域名含 https://<PLACEHOLDER:生产域名>
```

正式发布需经微信审核，与本文无关的运营流程自行按平台规则走。

---

## 4. 冒烟验收清单

按部署档位递进（每档通过再进下一档）。期望响应均以合并后代码为准。

| # | 档位 | 操作 | 期望 |
|---|---|---|---|
| 1 | 基座 | `curl http://127.0.0.1:8080/actuator/health` | 200 `{"status":"UP"}` |
| 2 | 基座 | 审计文件 §2.1 audit-path | 文件存在且随管理端动作增长 |
| 3 | A 类 | `curl https://<域名>/api/v1/c/encyclopedia/categories` | 200，分类 JSON（百科匿名读，`pet.encyclopedia.query.enabled` 默认 true） |
| 4 | A 类 | 管理端网页登录：`POST /api/v1/admin/auth/attempts`（经页面） | 登录流程可达；未授权账号 403 fail-closed |
| 5 | A 类 | 登录后打开运营台"系统设置"：`GET /api/v1/admin/settings/runtime` | 200 开关视图（65号 ops 面，#183 修复后可达） |
| 6 | A 类 | `GET /api/v1/admin/governance/merchants`（64号面） | 200（或空列表） |
| 7 | C 会话 | 体验版小程序打开 → 微信登录 | C 会话建立；首页/百科/我的可浏览 |
| 8 | 交易占位 | C 端下单（券/库存开关 A 类已开） | 201 占位单；10 分钟后自动关闭（`order.expiry.worker` 默认 true） |
| 9 | B 类未开面 | 对接单/核销/退款域路由发请求（如 M 端核销） | 503 失败关闭（对应内核开关默认关，**不是** 500/404） |
| 10 | 资金链 | 配齐 §2.3 后：发起支付 → 拉卡拉 SIT/PRODUCTION 回调 | 支付回执验签通过、订单推进；`notify-url` 必须公网可达 |

真机验收（字体回退、SVG、多机型）按 35号 §一.2 **延后至正式上线前统一执行**，不在本文冒烟范围。

---

## 5. 开关核对单（上线打勾表）

权威依据：合并后 `backend/pet-boot/src/main/resources/application.yml`（commit a6ff55a2，#185）+ 35号 §三/§四/§五 + 21号（组合合法性）。语义提醒（35号 §七.3）：**A 类开关"从 yml 删除该行"≠关闭（缺省=开）；显式写 `false` 才是关。**

### 5.1 第一勾：部署基座

- [ ] `pet.auth.admin.enabled=true`（+ §2.1 全部键）——配好后 A 类全部自动生效

### 5.2 A 类 18 项（默认已开，逐项确认 yml 为 true 即可）

| # | 开关 | 确认 |
|---|---|---|
| 1 | `pet.outbox.enabled` | [ ] |
| 2 | `pet.merchant.application.notifications-enabled` | [ ] |
| 3 | `pet.merchant.staff.notifications-enabled` | [ ] |
| 4 | `pet.service.review.notifications-enabled` | [ ] |
| 5 | `pet.merchant.staff.enabled` | [ ] |
| 6 | `pet.merchant.staff-identity.enabled` | [ ] |
| 7 | `pet.schedule.protection.enabled` | [ ] |
| 8 | `pet.order.creation.enabled` | [ ] |
| 9 | `pet.order.expiry.enabled` + `pet.order.expiry.worker.enabled` | [ ] |
| 10 | `pet.coupon.lifecycle.enabled` | [ ] |
| 11 | `pet.encyclopedia.query.enabled` | [ ] |
| 12 | `pet.points.task.enabled` + `pet.points.task.http.enabled` | [ ] |
| 13 | `pet.points.refund-clawback.enabled` | [ ] |
| 14 | `pet.admin.governance.http.enabled` | [ ] |
| 15 | `pet.refund.pre-service-auto-refund.enabled` | [ ] |
| 16 | `pet.verification.staff-identity.enabled` | [ ] |
| 17 | `pet.admin.ops.http.enabled`（#185 并集 #183 后默认开；21号 §1.12 该行文字滞后，以 yml 为准） | [ ] |
| 18 | `pet.coupon.grant.enabled` + `pet.coupon.void.enabled`（同上，随运营台放开） | [ ] |

### 5.3 B 类（默认关；配齐对应凭据后逐项放开并打勾）

| # | 域 | 开关 | 卡点（凭据表引用） | 放开 |
|---|---|---|---|---|
| 1 | C 会话总闸 | `pet.auth.c.enabled` | §2.2 微信 appid/secret | [ ] |
| 2 | 入驻内核 | `pet.merchant.application.enabled` | §2.5 OSS + §2.6 主体密钥（+ `pet.merchant.application.open-cities` 至少一城） | [ ] |
| 3 | 主体/保护 | `pet.merchant.subject.enabled` / `pet.merchant.protection.enabled` | §2.6 | [ ] |
| 4 | 私有素材 | `pet.private-assets.enabled`（env PRIVATE_ASSETS_ENABLED） | §2.5 | [ ] |
| 5 | 封面直传 | `pet.service.cover-signing.enabled` | §2.5（必须与 private-assets 同开） | [ ] |
| 6 | C 目录读族 | `pet.service.query` / `pet.store.query` / `pet.schedule.query`(+`selection`) | 装配硬依赖 #2 入驻内核 | [ ] |
| 7 | 服务/排期写 | `pet.service.command.enabled` / `pet.schedule.command.enabled`(+`http`) | 同上 | [ ] |
| 8 | 收藏足迹 | `pet.favtrack.http.enabled` | 硬依赖 #6 两个目录开关 | [ ] |
| 9 | 支付地基 | `pet.payment.foundation.enabled` | §2.3（无凭证可启动但回调验签全失败） | [ ] |
| 10 | 支付发起 | `pet.payment.dispatch.enabled` | §2.3 全表，缺一启动失败 | [ ] |
| 11 | 商家接单 | `pet.order.auto-confirm.*` → `pet.order.merchant.enabled`(+`http`+`worker` + §2.4 protection-key) | 顺序：先 auto-confirm 再 merchant；F-1 互锁要求 refund.late 同开 | [ ] |
| 12 | 迟到退款 | `pet.refund.late.enabled`(+`worker`) | §2.3 退款凭证（复用 dispatch） | [ ] |
| 13 | 改期 | `pet.order.reschedule.enabled`(+`http`) | guard：#7/#9/#11 齐备 | [ ] |
| 14 | 核销 | `pet.verification.credential.enabled`(+`http`) → `verification.completion`(+`http`) + §2.4 三密钥 | guard 依赖 #11 | [ ] |
| 15 | 退款申请 | `pet.refund.application.enabled`(+`worker`+`http`+`merchant.http` + §2.4) | guard：#1/#9/#11 齐备 | [ ] |
| 16 | 售后 | `pet.aftersale.enabled`(+`worker`+`http`+`refund` + §2.4) | guard：#4/#14/#15 齐备 | [ ] |
| 17 | 评价 | `pet.review.enabled`(+`http`) / `pet.review.appeal.enabled`(+`http`) | guard 硬要求 #1 | [ ] |
| 18 | 员工邀请 | `pet.merchant.staff-member.enabled` | §2.7 UserPhoneVerificationApi bean + #7 | [ ] |
| 19 | 真实通知 | `pet.notification.delivery.enabled` | §2.7 自备微信适配器 | [ ] |

### 5.4 C 类（保持不动，勿改）

- [ ] `pet.*.migration-enabled` / `migration-database` ×5：保持 false/空（隔离库专用显式 opt-in）
- [ ] `pet.auth.admin.maintenance`：仅 §3.5 临时进入，完成后去除
- [ ] `pet.merchant.map.enabled`：测试残留，主装配树无消费者，不登记不翻转

---

## 6. 回滚要点

1. **应用回滚**：保留上一版 `pet-boot-*-exec.jar`；停服 → 换 jar → 起服。注意：新版本写入的业务词表值（如 #182 券实例 `VOID` 终态，纯词表注记无 DDL）在旧代码读取时按未知状态处理——回滚前评估是否已有此类数据。
2. **开关回退语义（35号 §七.3）**：A 类单键条件 `matchIfMissing` 已翻转——**删除 yml 行 = 仍然开启**；回退必须显式写 `false`。多键组合条件（运营台/收藏/积分任务 C 面）保持"缺省即关"。
3. **数据库迁移不可回滚项**：
   - `docs/03-database/*.sql` 无自动化回滚脚本——上线前**全量备份**（`mysqldump` + binlog），回滚由 DBA 手工评估；
   - opt-in Flyway（V26~V32）只在隔离库执行，生产共享库从未跑过迁移；V32（`refund_admin_action` 等 append-only 审计表）属"只进不出"——回滚应用版本后表保留无害（写入方消失），删除需 DBA 裁决；
   - 生产合并 Flyway 基线尚未建立（§3.2 警示），升级窗口须人工核对 schema 与代码版本的匹配。
4. **F-1 互锁（回滚/降级时最易踩）**：`outbox=true` + `payment.foundation=true` +（`order.merchant` 或 `aftersale`）任一开着时，`refund.late` 必须保持开启——关掉即启动失败（21号 §2.1）。
5. **凭据轮换**：三把管理端会话密钥（§2.1）轮换后既有会话全部失效（需重登）；OSS/拉卡拉密钥轮换按云厂商/收单机构流程，轮换窗口内对应域失败关闭（不半执行）。
6. **审计与取证**：管理端审计文件（audit-path）与 DB 备份同政策保存；回滚不清空审计。

---

## 7. 占位符汇总

本文出现 `<PLACEHOLDER:...>` 共以下几类，上线前逐项替换并**不得**将真实值提交仓库：生产域名、管理端域名、DB 连接四元组、Redis 地址/密码、三把管理端会话密钥、微信 appid/secret、拉卡拉全套凭证与回调域名、OSS 五元组、各域保护密钥、审计文件路径、Snowflake node-id。
