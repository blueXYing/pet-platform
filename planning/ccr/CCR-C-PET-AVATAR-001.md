# CCR-C-PET-AVATAR-001 — 宠物头像真实上传与默认头像（2026-10-26 用户裁决）

状态：用户 2026-10-26 选择“真实上传 + CCR”；本 CCR 随实现 PR 提交。
依据：用户 2026-10-26 反馈 `consumer/pages/pet-archive/form` 无头像上传；要求按 Figma `645:22`/`645:10`/`645:11`/`688:480`/`688:479`/`95:1764`/`95:1773` 落地头像卡、猫/狗默认头像，并把添加时“最后一步”选择 `DOG/CAT/OTHER` 前移到表单开头；未上传时按类型展示默认头像，已上传时以上传图为准，`OTHER` 不展示默认头像。
关联：`docs/04-api/CCR-W2-API-001.md`（宠物 domain 0.1）、`docs/04-api/31-Private-Asset-Contract-v0.1.md`、`docs/03-database/06-核心数据库Schema-v0.1.sql`、`docs/03-database/31-Private-Asset-Schema-v0.1.sql`、HTTP10 §3.2、`docs/08-engineering/20-设计源登记表-figma-map.md` §4.7。

## 1. 现状与缺口

- `PetView.avatarUrl` 已有读取字段；`CreatePet`/`UpdatePet` 后端命令已有 `avatarUrl` 字符串（https，≤512）。
- 31 号私有资产管线的 C 端上传 purpose 仅 `MERCHANT_APPLICATION_MATERIAL` / `SERVICE_COVER` / `STORE_PHOTO` / `FEEDBACK_ATTACHMENT`；没有宠物头像用途，也没有宠物头像的受控读取/签名通道。
- 因此前端只能显示外部 https URL；真实选图无法持久化。`CCR-W2-API-001` 已明确“头像上传仍缺公共协议”。
- 添加表单的 `petType` 目前由保存时的原生 action sheet 选择；用户要求前移到添加开始，并据此选择默认头像。

## 2. 本 CCR 提案（最小闭环）

1. **新增私有素材用途 `PET_AVATAR`**：加入 `PrivateAssetApiImpl.UPLOAD_PURPOSES` 与 C 端 `POST /api/v1/c/private-assets` 用途集；消费者本人可上传，复用现有不可变上传/扫描/图片规范化/10MiB 上限/幂等管线。无新错误码，无 `private_asset` Schema 变更。
2. **宠物保存请求新增选填 `avatarAssetId`**（Snowflake String）：`POST /api/v1/c/pets` 与 `PUT /api/v1/c/pets/{petId}` 接受；服务端经 31 号 `resolveOwned`/签名通道重证=本人所有 + `PET_AVATAR` + `READY`。`petType` 仍创建必填、创建后不可改。
3. **`user_pet.avatar_url` 作为不透明内部引用**：上传保存时写入 `asset:<assetId>`（不新增列）；外部 https URL 继续兼容。读取时 `PetView.avatarUrl` 返回由 31 号对象签名得到的短期 https URL（建议 24h，失败返回 null 由客户端默认头像兜底），不把 `asset:` 引用暴露给客户端。无 SQL06 Schema 变更，CCR 明确列语义扩展。
4. **受控签名 API**：thirdparty-api 新增 `PetAvatarSigningApi.signOwnedAvatar(ownerUserId, assetId)`，实现只允许 `purpose=PET_AVATAR`、owner 匹配、`READY`、JPEG/PNG、≤10MiB、不可变 `normalized-v1` 对象；不新增任意 assetId 外部签名路由。PetService 读取时签名；签名失败/null 时 `avatarUrl=null`。
5. **前端**：添加表单开头新增宠物类型三选一（狗狗/猫猫/其他），选择即展示对应默认头像（猫/狗）；`OTHER` 不显示默认图，保留空头像卡 + “更换头像”；上传成功后用本地预览即时展示，保存后以服务端签名 URL 为准；宠物列表/详情在真实宠物无头像时同样按 `petType` 展示默认猫/狗（`OTHER` 维持首字占位）。
6. **默认头像素材**：Figma `688:480`（猫）/`688:479`（狗）导出 PNG 入宠物分包并登记 manifest；不写入用户数据，不改变 `PetView` 契约。

## 3. 明确不做

- 不做宠物头像的公开静态 URL/公开 bucket；继续走私有资产 + 受控短期签名。
- 不做头像裁剪/旋转/滤镜/多图相册；上传后由既有图片规范化管线处理。
- 不改 `petType` 创建后不可改规则；编辑模式类型只读。
- 不做头像历史版本/删除资产/回收站；替换头像只更新 `avatar_url` 指针。
- 不把 `PET_AVATAR` 用于商家、资质、售后等其它用途。

## 4. 契约/影响清单

- API：`POST/PUT /api/v1/c/pets` 请求增选填 `avatarAssetId`；`PetView.avatarUrl` 语义改为“服务端签名 URL（可空）”；C 私有素材上传 purpose 增 `PET_AVATAR`。
- DB：无 Schema 变更；`user_pet.avatar_url` 允许内部不透明 `asset:<id>` 引用（`VARCHAR(512)` 足够）。
- Event/Scheduler：无。
- 权限：消费者本人；31 号 owner/purpose/READY 校验；不新增运营/商家权限。
- 开关：沿用 `pet.private-assets.enabled`；未装配时上传/签名失败关闭，`avatarAssetId` 保存 503，客户端显示默认头像；外部 `avatarUrl` 不受影响。

## 5. 验收

- 前端：typecheck、615+ 单测、`miniapp-build` 门禁；开发者工具添加页选择狗狗/猫猫/其他时默认头像切换；上传后保存并重进页面仍显示上传图；无上传的 DOG/CAT 显示默认猫/狗，OTHER 无默认图。
- 后端：私有素材 `PET_AVATAR` 上传/幂等/owner 校验；宠物创建/更新 `avatarAssetId` 签名校验；非本人/错误 purpose/未 READY 拒绝；读取返回签名 URL，签名不可用降级 null；既有外部 `avatarUrl` 兼容。
- 真机 VIS 与生产对象存储签名需在环境具备后另行验收；本 CCR 不改变生产开关默认值。
