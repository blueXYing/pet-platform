# CCR-MER-PRIVATE-001：商家申请私有材料与 Provider 契约

状态：`APPROVED / IMPLEMENTED_CANDIDATE_LOCAL_VERIFIED`
日期：2026-09-20
范围：MER001 生产依赖。用户于 2026-09-20 对私有材料登记、服务端上传检查、短时一次性水印读取方案回复“确认”。本次同步 31 号 API/Storage/Schema 后实施；该确认不代表生产启用、数据迁移、地图验收或 PR 合入已完成。

## 1. 权威约束

`docs/04-api/30-Merchant-Application-Contract-v0.1.md` 与 `docs/03-database/29-Merchant-Application-Storage-v0.1.md` 已要求：

- 证照不能进入公开资产表、普通日志或普通 GET；运营查看必须有独立授权、用途、审计、动态水印和短时读取协议；
- `PrivateAssetRef` 提供当前 owner/hash/MIME/bytes/status，MER 只保存 opaque assetId/hash，并在保存、提交、批准时重读；
- 上传、恶意文件与图片解码检查、授权读取和生命周期必须有 Contract；
- 证件规范化由受信证据适配契约定义，不得擅自限定 18 位或选择 OCR 厂商；
- cityCode 来自已开通城市字典；按最新SSOT §28，地址/坐标只做输入合法性校验，不再执行地理匹配或距离拦截；
- key policy 缺失、版本不匹配或 secret service 不可用时依赖失败关闭。

## 2. 只读审计（基线 `632ced7`）

### 2.1 现有 OSS 能力和凭据事实

`pet-thirdparty-biz` 已通过 AWS SDK S3 接入阿里云 OSS：`S3OssAssetClient` 支持服务端 `HEAD/PUT`，`PresignedAssetUrlService` 为公开运营素材签发 GET URL。代码读取 `OSS_ENDPOINT/REGION/BUCKET/ACCESS_KEY_ID/SECRET_ACCESS_KEY/PUBLIC_BASE_URL`；同步工具还读取 `OSS_SYNC_MIN_BYTES`、`OSS_PRESIGN_WINDOW_SECONDS`。

用户已明确使用本地 OSS。主工作区存在被 Git 忽略的 `C:/Users/Administrator/Desktop/宠物平台V1.0/ops/oss.env.local`。只检查配置名和非空状态的结果为：`ENDPOINT/REGION/BUCKET/ACCESS_KEY_ID/SECRET_ACCESS_KEY` 已配置；`PUBLIC_BASE_URL/IMPORT_SOURCE` 未配置。配置值未写入本文、Git、日志或测试。根任务随后按用户授权只读GET bucket ACL，HTTP200且ACL=private，未改变权限、未上传/读取证件；RAM权限是否完全最小化尚未证明。

推荐继续由 `pet-thirdparty-api` 拥有私有资产内部契约，`pet-thirdparty-biz` 复用现有 S3 SDK、连接配置和服务端上传能力。MER 只依赖 API，不访问 thirdparty 持久层，不新增资产模块。

现有 `asset_registry` 不能保存证照：它没有 owner、用途、授权、读取审计、扫描、隔离对象位置或生命周期事实，且按 assetKey 返回可复用签名 URL。

### 2.2 当前缺口

| 依赖 | 当前事实 | 当前生产行为 |
|---|---|---|
| `ProtectedValuePort` | S7已实现真实AES-GCM/HMAC与显式secret装配 | 未配置独立密钥时关闭 |
| `SubjectCredentialPort` | 已批准并实现大陆身份证/统一社会信用代码 scheme；尚未 boot 装配 | 未装配时 503 fail-closed |
| `PrivateAssetQueryPort` | 无 Owner contract/schema | 保存材料、提交、批准 503 fail-closed |
| 私有上传/读取 | 无 HTTP、私有表、grant、代理、水印 | 不能取得 assetId 或查看原件 |
| 城市/地图 | 成都可信目录已实现；微信原生选点已接；后端地图校验仍缺 | 缺少后端位置事实时submit失败关闭 |

## 3. 推荐私有资产内部契约

在 `pet-thirdparty-api` 增加：

```java
interface PrivateAssetQueryApi {
  List<PrivateAssetFact> resolveOwned(ResolveOwnedPrivateAssetsQuery query);
}
record ResolveOwnedPrivateAssetsQuery(
    String ownerUserId, List<String> assetIds, String requiredPurpose) {}
record PrivateAssetFact(
    String assetId, String ownerUserId,
    String sourceSha256, String objectSha256, String objectVersionRef,
    String mediaType, long bytes, PrivateAssetStatus status, String factVersion) {}
```

精确语义：

1. Owner 返回当前强一致事实；未知、重复、跨 owner、错误 purpose 不伪装成功，依赖失败不返回部分列表。
2. thirdparty 状态为 `UPLOADING/SCANNING/READY/REJECTED/QUARANTINED/RETIRED`。MER adapter 仅接受 `READY` 并保留既有端口的 `READY`；其他状态被 MER 拒绝。
3. `sourceSha256` 是流式接收的原始字节摘要；`objectSha256` 是最终对象摘要；`objectVersionRef` 固定不可变版本。安全处理若改变字节，两种 hash 分别保存。MER material 使用 `objectSha256`，后续 current read 匹配同一 version/hash。
4. MIME 来自服务端解码，只允许 `image/jpeg`、`image/png`；bytes 为最终对象长度，1..10 MiB。JPEG 纠正 EXIF 朝向、清除元数据并以 quality 0.95 保留 JPEG 编码，PNG 清除元数据并保留 PNG 编码；不得因统一转 PNG 使合法 JPEG 膨胀越界。`READY` 保证对象存在、事实一致、恶意文件检查和完整解码通过。

私有资产Owner必须保证assetId只对应一个不可变对象版本。现有SQL29的assetId/hash可以据此核对当前对象事实；是否另冗余object_version_ref须在Schema同步时决定，不能把推测的字段增量冒充已批准要求。

## 4. 推荐上传：现有单一通道的服务端流式上传

沿用 CCR-OSS-001“一条上传通道”，不向小程序签发 OSS PUT：

1. 新增 `POST /api/v1/c/private-assets`，`multipart/form-data`，字段 `purpose=MERCHANT_APPLICATION_MATERIAL`、文件和完整 UUID `X-Request-Id`；ownerUserId 只取当前 C 会话。
2. adapter 限制请求体并流式交给 thirdparty；thirdparty 同步计算 source hash，不把完整字节写日志或 MER 临时目录。
3. 使用现有私有 bucket 的隔离前缀 `merchant-materials/{ownerPartition}/{assetId}/{objectVersion}`；key 不含原文件名、姓名、证件号或手机号。bucket 必须保持私有，RAM policy 只允许该前缀对象读写且禁止 ACL 写。
4. thirdparty 解码图片、验证真实 MIME/10 MiB 上限并做恶意文件检查；元数据清理或重编码产生新的不可变 object version，并保存 source/object hash。
5. 通过后转 `READY`；失败转 `REJECTED/QUARANTINED`。响应只含 `assetId/status/objectSha256/mediaType/bytes`，不含 objectKey/OSS URL。
6. `ownerUserId + X-Request-Id` 绑定 canonical request hash/result；重放返回同一 assetId，同 key 异参返回 `IDEMPOTENCY_KEY_CONFLICT`。对象写成功但 DB 未提交由 durable AsyncTask/outbox 收敛。

扫描引擎和图片处理库属于 Provider 选型；未配置时必须失败，不能跳过检查。

## 5. 推荐读取：一次性后端代理 + 动态水印

不复用公开素材 `presign(assetKey)`，不把 OSS URL 返回浏览器：

1. `POST /api/v1/admin/merchant-applications/{applicationId}/private-assets/{assetId}/read-grants`，body 含 `submissionRevisionId,purposeCode,reason,confirmed:true` 和 `X-Request-Id`。
2. 解析真实 ADMIN_WEB 会话；检查 `identity.reveal`/scope；核对当前 CLAIMED task 的 claimant、submitted revision、revision/material/asset 关系；执行 READ_RESULT 最终授权。
3. 同事务写 grant audit 和单次 token 摘要。响应返回后端代理 URL `/api/v1/admin/private-asset-read-grants/{token}` 与 `expiresAt`；token 原文不入库/日志。
4. 代理 GET 原子消费 token，再重查 session generation、授权版本、material 关系；服务端读 OSS；按 operatorId、applicationId、读取时间生成动态水印，保持标准化对象 JPEG/PNG 编码类别后输出。终端只接受 `image/jpeg` 或 `image/png`、1..10 MiB，不返回其他格式。
5. 默认 TTL 5 分钟，一次消费；响应 `Cache-Control: no-store, private`、`Pragma: no-cache`、`X-Content-Type-Options: nosniff`。审计保存 operator、application/revision/asset、purpose、受保护 reason、request/authz/scope 版本及签发/消费结果。

S3 预签名 URL 不能即时撤销、不能保证一次消费、不能可靠加水印，因此不满足此契约。

## 6. 推荐 DDL 方向（同步后实施）

```sql
CREATE TABLE private_asset (
  id BIGINT PRIMARY KEY,
  owner_user_id BIGINT NOT NULL,
  purpose VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  object_version_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  object_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  media_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  bytes BIGINT NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version BIGINT NOT NULL,
  scan_provider_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  scan_result_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL,
  ready_at DATETIME(3) NULL,
  retired_at DATETIME(3) NULL,
  UNIQUE KEY uk_private_asset_object_version (object_key,object_version_ref),
  KEY idx_private_asset_owner_status (owner_user_id,status,id),
  CHECK (id > 0 AND owner_user_id > 0 AND bytes BETWEEN 1 AND 10485760),
  CHECK (status IN ('UPLOADING','SCANNING','READY','REJECTED','QUARANTINED','RETIRED'))
);

CREATE TABLE private_asset_read_grant (
  id BIGINT PRIMARY KEY,
  token_digest BINARY(32) NOT NULL UNIQUE,
  asset_id BIGINT NOT NULL,
  application_id BIGINT NOT NULL,
  revision_id BIGINT NOT NULL,
  operator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  session_id_digest BINARY(32) NOT NULL,
  session_generation BIGINT NOT NULL,
  purpose_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason_protected VARBINARY(2048) NOT NULL,
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  authz_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scope_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  issued_at DATETIME(3) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  consumed_at DATETIME(3) NULL,
  UNIQUE KEY uk_private_asset_grant_request (operator_id,request_id),
  KEY idx_private_asset_grant_asset_time (asset_id,issued_at),
  CHECK (status IN ('ISSUED','CONSUMED','EXPIRED','REVOKED'))
);
```

Schema 同步还须定义上传 request binding、durable task/outbox及grant/asset复合一致性。没有明确期限前不实现自动删除默认值。

## 7. `SubjectCredentialPort` 已批准规则

用户已裁决只接受大陆居民身份证，兼容历史 15 位与现行 18 位；营业执照主体使用 18 位统一社会信用代码。实现 scheme 固定为 `CN-ID15-18-USCC18-v1`：

- 身份证按 GB 11643-1999 校验出生日期与 MOD 11-2 校验位；15 位号码插入 `19` 并计算校验位后形成 18 位 canonical identifier；
- 统一社会信用代码按 GB 32100-2015 的 31 字符集、17 个权重和校验字符验证；
- identifier 先 NFKC、只裁剪首尾 ASCII whitespace、转大写；内部空白/分隔符拒绝，不通过删除字符制造别名；
- subjectName 只做 NFC 与首尾 trim，不做名称启发式改写；行业许可证号是 1..128 字符的 issuer-opaque 支持格式，不发明校验算法；
- 敏感字段经 purpose-bound AES-GCM 保护；claim digest 使用第三把独立、持久锁定的 HMAC-SHA-256 key，并绑定 credentialType、scheme、keyVersion 和 canonical identifier。

权威依据：国家标准平台的 [GB 11643-1999](https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=080D6FBF2BB468F9007657F26D60013E) 与 [GB 32100-2015](https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=24691C25985C1073D3A7C85629378AC0)。不接受护照、港澳台/外国证件、`QA-ID` 或无法通过日期/校验位的号码。

## 8. 城市与地图

用户已选择首推城市成都，前端使用微信原生选点，开放城市仍来自服务端目录。后续用户明确“不对位置进行限制”（SSOT §28）：取消原先的地址/坐标地理匹配、距离阈值、位置围栏与腾讯WebService Key依赖。后端保留地址长度及经纬度合法范围校验，不能把这个格式检查称为地理真实性核验。`MapValidationPort`仅保留内部兼容名称，实际默认实现是`LocationInputValidationProvider`，不发外部地图请求。

## 9. 密钥与配置名称

- protected value：`MERCHANT_PROTECTED_KEY_VERSION`、`MERCHANT_PROTECTED_AES_KEY_BASE64`、`MERCHANT_PROTECTED_HMAC_KEY_BASE64`；
- subject lookup：`MERCHANT_SUBJECT_POLICY_VERSION`、`MERCHANT_SUBJECT_HMAC_KEY_BASE64`；
- OSS 使用现有本地配置，经 boot 适配为代码所需 `OSS_*` 名称；私有对象固定现有 bucket + `merchant-materials/` 前缀。

密钥由部署 secret 注入；值不得进入 Git、SQL、日志、错误、测试或本文档。keyVersion 是持久数据策略，有存量密文/token 时不能悄然修改。

## 10. 保留期限与新私有材料契约审阅

2026-09-20已收到补充确认：首批只开放成都；身份证件仅接受大陆居民身份证，确认问题明确包含历史15位。证件接受范围已不再待决；技术规范化按15/18位大陆身份证及营业执照统一社会信用代码的公开标准同步，不能据此开放其他证件。

1. **保留期限**：未引用、被拒绝、历史 revision、已批准材料和读取审计各保留多久；裁决前只保留且不自动删除，不发明天数。

城市目录及扫描等仍按各自真实依赖验收；外部地图Provider及Key门禁已由SSOT §28取消。

## 11. 本次交付与门禁

S7 已交付 `ProtectedValuePort` AES-256-GCM + HMAC-SHA-256 adapter、单元测试和本 CCR。S8 根据本次确认实施新增私有资产 API、服务端上传、代理水印读取及表结构；精确实现与状态见 `docs/04-api/31-Private-Asset-Contract-v0.1.md`、SQL31/Storage31。运行配置仍默认关闭，真实 Provider 不可用时失败关闭。保留期限仍待裁决；位置限制按后续SSOT §28裁决取消。
