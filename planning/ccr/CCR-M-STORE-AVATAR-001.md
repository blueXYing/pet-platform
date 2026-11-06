# CCR-M-STORE-AVATAR-001 店铺资料头像展示 URL（STORE_PHOTO 签名读）

- 状态：APPROVED（2026-11-06 用户批准；裁决记录见 §8）
- 日期：2026-11-06
- 提出方：商家端 Figma 还原（Claude Code）
- 关联：Figma `25:20684`（门店设置/店铺信息）；
  Contract 27 §6.4、Contract 31（私有素材）、CCR-W2-API-001 服务封面签名补充；
  PR #211；PLAN §18/§20
- 当前 Contract：`docs/04-api/27-Merchant-Domain-Contract-v0.1.md` §6.4
  `StoreProfileDTO.profile.avatarAssetId`（String，可空）
- 发现的问题：商家上传头像后，M 端页面无法显示图片；只能显示首字占位
- 是否已有 SSOT/PRD 支撑：店铺资料头像是 27 号已批的可维护字段；展示属技术缺口
- 是否涉及产品裁决：**NO**（不新增产品行为；若扩到 C 端公开展示需另裁）

## 1. 背景与现状

- 27 号 §6.4 的 `StoreProfileDTO.profile` 含 `avatarAssetId` 与 `photoAssetIds`，
  M 端上传走 31 号 `STORE_PHOTO` 私有素材管线（上传/标准化/扫描/READY 校验已落地）。
- 31 号明确：`STORE_PHOTO` 的消费方目前只有 27 号写面 `resolveOwned` 所有权校验；
  **没有给商家自己的展示 URL 读取面**。C 端展示属“店铺资料 C 端展示切片”，另行处理。
- 服务封面已有同类先例：`CCR-W2-API-001/service-cover-signing-amendment.md` 新增
  内部签名 API，只对 `purpose=SERVICE_COVER`、READY、版本化对象签发短期 GET URL，
  不暴露对象 key，不新增“任意 assetId 签名”路由。
- 前端 `shop-info` 页当前只显示“已绑定的封面素材（展示链接由 C 端签名回显提供）”
  占位文案，Figma `25:20684` 的头像/门店照片无法展示。

## 2. 最小变更提议

**新增 M 端只读签名路由（推荐）**：

```text
GET /api/v1/merchant/store-profile/avatar-url?merchantId&storeId
Header: MINIAPP Bearer
Cache-Control: no-store
data: { "assetId": "710402", "url": "https://…", "expiresAt": "2026-11-06T02:00:00.000Z" }
```

- 服务端在既有门店 guard + `requireOwnerRead` 之后读取本店 `avatarAssetId`，仅当素材
  为当前 OWNER 以 `STORE_PHOTO` 用途上传、READY、JPEG/PNG、版本化对象时签发 GET URL；
  无头像返回 404 或 `{assetId:null,url:null,expiresAt:null}`（二选一，推荐后者，页面
  直接降级为首字占位）。
- 签名实现复用 `CCR-W2-API-001` 的内部签名端口模式：扩展其 purpose 允许集加入
  `STORE_PHOTO`（或新增 `StorePhotoSigningApi`），**不扩 PrivateAssetApi 的证照接口**，
  不把对象 key/版本暴露给 boot/前端。
- TTL 沿用 CCR-OSS-001 的量化窗口（最小 600 秒，不臆造生产默认值）；响应
  `expiresAt` 与实际签名一致。客户端临期/过期重新调用本路由（页面 onShow 或图片
  加载失败时重取）。
- 可选实现：把 `avatarUrl/avatarUrlExpiresAt` 直接嵌进 `GET /merchant/store-profile`
  的 `profile`。不推荐：每次资料读都要签名，且过期后整页重读；独立路由更符合
  “临期重签”模型。

## 3. 隐私与安全

- 路由只对 OWNER 本人开放，素材归属和 purpose 在签发事务内重证；跨店/非 OWNER 同一 403。
- 不提供任意 `assetId` 签名入口；不接受客户端传入 assetId。
- 日志/证据不落完整签名 URL 或对象 key；URL 短 TTL，过期后拒绝。
- 不改变 31 号证照素材的水印/单次读取规则；`STORE_PHOTO` 的签名是“本店自有展示”
  例外，不扩到 C 端匿名读取。

## 4. 兼容性与影响

- API：新增一条 M 端只读路由；无既有字段变更。
- Schema/Event：无变化。
- 配置：复用私有素材与签名开关；新增/复用签名窗口配置，未配置时失败关闭（503），
  不返回裸 URL。
- 前端影响：`shop-info` 页显示头像/照片；无头像时保持首字占位；图片加载失败重取签名。
- 测试影响：
  - 后端：OWNER/跨店/无头像/非 READY/非 STORE_PHOTO/过期负向；签名 URL 可 GET。
  - 前端：头像 URL 成功/失败降级；不缓存签名 URL。
  - 本地 E2E：mock OSS 可用时做真实上传→签名读取；否则登记环境阻塞。

## 5. 验收标准

1. OWNER 上传头像并保存后，`avatar-url` 返回可展示的短期签名 URL；页面显示真实图片。
2. 无头像/素材未 READY/跨店/非 OWNER 均按约定降级或失败关闭，不泄漏任何对象信息。
3. 签名过期后重新读取获得新 URL；旧 URL 过期拒绝。
4. 不改变 C 端匿名展示（仍为后续切片）。

## 6. 不做范围

- C 端门店头像展示（另行切片）。
- 任意 assetId 通用签名路由。
- 图片裁剪/多尺寸/CDN 变体；沿用标准化对象原图（页面用 CSS 裁圆）。
- 头像审核（沿用 27 号现有上传校验，不新增人工审核）。

## 7. 待裁决项

1. 采用独立 `avatar-url` 路由（推荐）还是嵌入店铺资料响应？
2. 签名 purpose 扩展复用现有端口，还是新增独立 `StorePhotoSigningApi`？
3. 无头像返回 `null` 字段（推荐）还是 404？

## 8. 裁决记录（2026-11-06，用户）

1. 独立 `avatar-url` 只读路由。
2. 复用既有签名端口模式（扩展 purpose 或等价新端口，取最小侵入实现）。
3. 无头像返回 `{assetId:null,url:null,expiresAt:null}`（非 404），前端降级首字占位。
4. 范围仅 avatar；门店照片墙展示不在本 CCR。

契约同步：27号 §6.4 后 2026-11-06 补充 + OpenAPI11 /merchant/store-profile/avatar-url；实施随 PR #211。
