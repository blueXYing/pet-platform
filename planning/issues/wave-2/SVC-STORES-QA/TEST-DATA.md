# SVC-STORES-QA 测试数据计划（门店读侧 + 服务可见闭环）

日期：2026-09-22。Owner：角色D（QA/集成）。分支 `codex/qa-svc-stores-20260922`。

模式对齐 SVC-D4：**真实申请→审批→签署链取得合法商家/门店对（拒绝伪造 merchant 行）+ SQL 播种 service_item/service_category**（V1 无服务写入方）。所有数据仅在隔离测试库（每次运行随机 `auth001c_http_<uuid>` 库，HttpFixture 自建自毁）中存在；不使用任何真实凭据/真实用户/生产数据。

## 1. 账号矩阵

| 账号 | 身份 | 取得方式（真实链路） | 用途 |
|---|---|---|---|
| svc-owner（openid `svc-owner`，手机 13800007711） | C 消费者 → 商家主账号 OWNER（申请通过后同一 user 即 owner） | `POST /api/v1/c/auth/attempts`（purpose=WECHAT_LOGIN）→ `POST /api/v1/c/auth/wechat-login`（FixedWechatProvider：`ok:`/`phone:` 前缀代码换发） | 提交入驻、签约、未来主账号管理服务；正例读会话 |
| svc-other（openid `svc-other`，手机 13800007712） | 第二 C 消费者，与商家无归属 | 同上 | 跨归属反例（SVC-D5 所有者视角 NOT_FOUND）、普通 C 端可见性观察者 |
| qa-reviewer（密码 `Example_ONLY_92!`，仅测试） | 运营审核员（admin bootstrap：`AdminAuthService.bootstrap("qa-reviewer",...)`） | `POST /api/v1/admin/auth/attempts` → captcha challenge → 测试内 SQL 回写 answer_mac → verify → login（Origin 回环头） | 审核（claim→manual-verification→APPROVE）；未来运营强制下架反例 |
| （规划）merchant-staff-1 | 商家核销员子账号（STAFF） | **无现成路径**（员工/子账号管理归 M-002/ADM 域） | PN-01/PN-02 服务管理 403/404 反例；BLOCKED 至子账号体系接入 |

FixedWechatProvider 为测试源内置替身（非真实微信）；真实微信环境验收按 21号补充另行立项，不以本计划冒充。

## 2. 真实准入链路种子（每轮重建）

1. 私有材料 201～204（门店照/营业执照/身份证正反面）：`PrivateAssetQueryPort` stub 注册 READY + sha256（`svc-asset-<n>` 的 SHA-256）。
2. 入驻申请：`POST /api/v1/c/merchant-applications`（merchantName 星河服务生活馆；cityCode `chengdu`；address `测试服务地址`——MapValidationPort stub 仅放行该组合；materials 201/202/203/204）→ `POST .../submit`。
3. 审核：admin `GET .../admin/merchant-applications/{id}` → `claim`（带 task version）→ `manual-verification`（证据：营业执照 CREDIT_CODE=校验位合法的统一社会信用代码、身份证 IDENTITY_NUMBER=校验位合法的 18 位）→ `decision APPROVE` → 得 `reservedMerchantId`。
4. 门店：APPROVE 建档时自动创建（`SELECT id FROM merchant_store WHERE merchant_id=?`）。
5. 协议签署：SQL 播 `merchant_agreement_version(svc-http-v1)` + `merchant_agreement_current(MERCHANT)` → owner `POST /api/v1/merchant/agreement/consent` 201 → **acceptsNewOrders=true（四条件闭合）**。

## 3. SQL 播种矩阵（service_category / service_item）

现行已用种子（`ServiceQueryHttpTest` 同款，本轮 PASS 基础）：

| 种子 | 关键值 | 服务的用例 |
|---|---|---|
| category 美容 | 随机雪花 id | categoryName 投影 |
| SVC-ACTIVE 宠物美容-基础洗护 | ACTIVE，128.00，45min，IN_STORE，description 非空 | SVC-B01/B04/B05/B06/B07/B08/B10/B11 |
| SVC-OFFLINE 已下架服务 | OFFLINE，88.00，30min | SVC-B02 |
| SVC-DRAFT 草稿服务 | DRAFT，66.00，20min | SVC-B03 |

扩展种子（写入方/门店读切片交付后启用，本轮仅规划）：

| 种子 | 关键值 | 服务的用例 |
|---|---|---|
| SVC-BIGID | 显式 id=`9007199254740993`（>JS 安全整数，BIGINT 合法），ACTIVE | ID String 精确往返（W2-IDEM-001 同款，服务域） |
| SVC-PRICE-EDGE | price `0.01` 与 `9999999999999999.99`（DECIMAL(18,2) 上界） | 金额 String 两位小数、无精度损失 |
| SVC-PICKUP | PICKUP_DELIVERY，ACTIVE | fulfillmentType 枚举完整（HTTP10 §3.5 接送约束归 ORD，不在本域断言） |
| SVC-BAD-FULFILLMENT | fulfillment_type=`TELEPORT`（非法存储值） | 读侧失败关闭 503（不提前做订单校验） |
| STORE-B / STORE-C | 同商家第二门店 / 空服务门店 | 跨门店分页隔离、空页 |
| MERCHANT-B（完整链路二号商家） | 独立申请→审批→签署 | 跨商家 404 防枚举（PN-03）、列表互不可见 |
| SVC-PENDING-REVIEW | status=待审核态（**schema 缺口**：现仅 DRAFT/ACTIVE/OFFLINE，需写入方 CCR 扩状态机） | 草稿/待审核不可见（SVC-B03 补充态） |

## 4. 状态手术矩阵（反例注入，均在同事务读之前）

| 手术 | 语句（示意） | 断言 |
|---|---|---|
| 商家下线 | `UPDATE merchant SET status='OFFLINE' WHERE id=?` | 列表空页 + 详情 404；内部 reasonCodes 含 MERCHANT_DISABLED；恢复后可见 |
| 门店冻结 | `UPDATE merchant_store SET status='FROZEN' WHERE id=?` | 同上含 STORE_DISABLED |
| 事实源损坏 | `UPDATE merchant SET status='CORRUPTED' WHERE id=?` | 详情与列表均 503，**不与 404 混同** |
| 不接新单 | 签约事实回退（删 acceptance 或重置签署态） | 隐藏 + reasonCodes 含 MERCHANT_NOT_ACCEPTING_ORDERS |
| 主数据变更 | `UPDATE service_item SET price=158.00, service_name=...` | 已取快照副本不变、再查得新值（W2-SVC-002） |

注意：手术仅限隔离测试库；`CORRUPTED` 为故意非法值，仅验证失败关闭，不构成合法状态。

## 5. 环境与运行要点（2026-09-22 实测 recipe）

- JDK21 `C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`（enforcer 强制 21，默认 PATH 为 17）。
- MySQL：本机 127.0.0.1:33452（root/空密码，8.4.9）；Redis：127.0.0.1:16383（docker `ms1-redis`，`--save "" --appendonly no` 挥发参数为 admin 缓存校验必需）。
- 运行命令（本 worktree 已验证）：
  `AUTH_MYSQL_URL="jdbc:mysql://127.0.0.1:33452/" AUTH_REDIS_HOST=127.0.0.1 AUTH_REDIS_PORT=16383 mvn -pl pet-boot -am test -Dtest=ServiceQueryHttpTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
- HttpFixture 每次运行创建随机库并在 close 时 DROP；历史遗留 `auth001c_http_*` 库为此前中断运行残留，非本轮产生，不清理（不属于本人启动的对象）。
- 长跑本地服务器（若未来 L3 需要）：必须 Hikari 连接池（DriverManagerDataSource 会使发号器 3 分钟内 fail-closed）、dev 构建带上传/本地 HTTP 开关、优雅停机会 DROP 测试库需硬杀或改用独立持久库（MS1 教训，见本地环境记忆）。

## 6. 用例映射总表

| 数据 | 用例 |
|---|---|
| 准入链（§2） | 所有可见性正例的前提（四条件） |
| SVC-ACTIVE/OFFLINE/DRAFT | ACCEPTANCE-CHECKLIST SVC-B01～B03/B08～B11 |
| 状态手术（§4） | SVC-B04～B07、STO-A02～A06 |
| 扩展种子（§3） | SVC-B13（错误 code 复核）、PN-03、ID/金额边界、STO-A01/A07/A09 |
| 账号矩阵（§1） | PN-01/02/06/07/08 及全部权限反例 |
