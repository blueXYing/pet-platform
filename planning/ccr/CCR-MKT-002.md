# CCR-MKT-002 营销素材上传与后台卡面素材化

- 状态：用户已确认（2026-10-27）
- 关联：71/72 号契约、CCR-MKT-001、SSOT §24
- 目标：活动钜惠卡面插画不再由小程序按 `tagCode` 内置，改为运营后台上传素材 URL；活动内容、素材、投放、排序、上下线均由后台控制。

## 范围

1. 新增营销素材上传接口 `POST /api/v1/admin/marketing/assets`（multipart，PNG/JPEG，≤2MiB，≤2000×2000），沿用 `marketing.campaign.manage` 权限、`X-Request-Id` 幂等和营销审计。
2. 新增只读公开素材接口 `GET /api/v1/c/marketing/assets/{assetId}`，返回 ACTIVE 素材，长缓存。
3. 新增 `marketing_asset` 存储表：配置 `OSS_*` 时上传到私有 OSS bucket（`object_key`，公开端点在应用侧代理读取）；没有 OSS 配置的本地/CI 回退为数据库 LONGBLOB。URL 稳定，可由网关/后续 CDN 替换。
4. `marketing_placement` 增加 `template_code`，用于选择前端白名单卡片模板；插画 URL 继续使用既有 `image_url_override`（C 端字段 `coverUrl`）。
5. 后台运营界面增加图片上传控件，可上传活动封面/投放位图片并选择卡片模板。
6. 小程序首页活动卡只渲染后台 `coverUrl` + `templateCode`；删除 `tagCode -> 本地狗狗/猫咪插画` 的映射。纸框、纸边、字体、交互仍为前端模板资产，属于设计系统，不属于活动内容。
7. 迁移现有两条活动：后台上传狗狗/猫咪插画，设置 `coverUrl`、`imageUrlOverride`、`templateCode`。

## 不做

- 不做任意 HTML/可视化布局编辑器；模板仍由前端代码提供，后台只能选择白名单模板。
- 不做图片内容审核外部服务（V1 单运营，上传权限、格式/尺寸校验、审计、可替换；后续接审核）。
- 不改变活动、券、门店、结算等业务规则。

## 错误码 / 状态

- `MARKETING_ASSET_INVALID` 400：内容非 PNG/JPEG、尺寸超限或空文件。
- `MARKETING_ASSET_TOO_LARGE` 413：文件超过 2MiB。
- 公开素材不存在/已退役：404 `COMMON_NOT_FOUND`（防枚举）。
- 上传写动作：`X-Request-Id` 幂等；同 requestId 同素材返回首个 result，不同素材冲突 409。

## 验收

- 后台上传 PNG/JPEG，返回 assetId + URL；C 端可直接 GET。
- 新建/编辑活动与投放位可引用上传 URL；首页卡面显示该 URL。
- 删除本地插画映射后，两条 Figma 活动仍显示相同狗狗/猫咪插画（素材来自后台 URL）。
- 上传非图片/超大文件被拒绝；重复 requestId 不重复落库。
- 小程序 `/api/v1/c/marketing/placements` 返回 `templateCode`；首页按 `templateCode` 选择版式、按 `coverUrl` 渲染素材。
