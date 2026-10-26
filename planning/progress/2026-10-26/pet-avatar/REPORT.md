# 宠物头像上传、默认头像与类型前移（2026-10-26）

## 用户裁决

`consumer/pages/pet-archive/form` 当前无法上传头像；按 Figma 编辑页头像卡与默认头像落地。用户选择“真实上传 + CCR”。

## 实现

- **类型前移**：添加表单开头新增「宠物类型」三选一（狗狗/猫猫/其他），`PetDraft.petType` 随创建保存；保存时不再弹原生 action sheet；编辑模式类型只读（`petType` 创建后不可改）。
- **默认头像**：Figma `688:480`（猫）/`688:479`（狗）导出 PNG 入 `pet-archive/assets`；无上传头像时按 `petType` 展示对应默认头像，`OTHER` 不展示默认图（空头像卡 + “更换头像”）。真实宠物列表/详情同样回退默认猫/狗；`OTHER` 维持首字占位。
- **头像卡**：按 `645:22`（343x86 浅蓝卡）、`645:10`（65x54 头像）、`645:11`（`#b89682` 11px“更换头像”）落地；头像与「宠物名字」输入在同一矩形卡内（`95:1764`/`95:1773`），类型选择改为 8px 小圆角矩形（非半圆）；表单高度 1066/1067 → 1162/1163 设计px，既有字段/按钮下移；顶部「宠物信息」栏固定，只有信息填写区滚动；`referenceCanvas` 截图保持画布内绝对定位。
- **真实上传**：C 私有素材新增 `PET_AVATAR` 用途（`POST /api/v1/c/private-assets`，消费者本人，复用不可变上传/扫描/规范化/10MiB 管线）；宠物创建/更新接受 `avatarAssetId`，服务端校验 owner/purpose/READY 后写入内部不透明 `asset:<id>`；`PetView.avatarUrl` 读取时通过 `PetAvatarSigningApi` 返回短期签名 URL。CCR：`planning/ccr/CCR-C-PET-AVATAR-001.md`。无 SQL06 Schema 变更。
- **首页回退修正（2026-10-26 用户反馈追加）**：首页「宠物档案」分区原用 `pet.avatarUrl || placeholderAvatar`，无上传头像的宠物显示通用人像占位图；档案列表/详情已按类型回退默认猫/狗，导致同一宠物在首页与详情不一致。现首页与列表/详情统一：`DOG` 回退默认狗、`CAT` 回退默认猫、`OTHER` 保留通用占位图；新增静态断言防回归。
- **首页展示与新建返回（2026-10-26 用户二次反馈）**：首页宠物档案最多展示 3 只，标题显示宠物总数；超过 3 只时标题旁出现「N 只萌宠 ▾」，下拉列出未直接展示的其他宠物，选择后进入详情；移除「查看全部」跳转。新建宠物保存成功后 `Taro.switchTab` 返回首页；编辑保存仍留在表单。
- **契约/设计登记**：`docs/08-engineering/20-设计源登记表-figma-map.md` §4.7 偏差 13；`assets/manifest.json` 登记两个默认头像节点、字节和 sha256。

## 验证

- 前端 `npm run typecheck`：PASS
- 前端单测：**619 / 619**（新增首页默认头像回退、3 只展示/下拉查找、新建返回首页断言）
- `python3 backend/tools/run-frontend-gate.py miniapp-build`：**PASS**
- `check:package`：总包 **8,159,521** 字节，预算内
- 后端 JDK 21 `mvn compile` / `test-compile`：PASS（pet-user-biz / pet-thirdparty-biz / pet-boot 及依赖）
- `PetValidatorAvatarTest`：PASS（asset ref 与 https 校验）
- `check-module-deps.py`：PASS
- 微信开发者工具（当前构建）：
  - 狗狗类型：默认狗头像
  - 猫猫类型：默认猫头像
  - 其他类型：空头像卡，无默认图
  - 类型选择为矩形小圆角；头像与名字在同一矩形卡内
  - 页面顶部「宠物信息」固定，表单内容滚动（`form-fixed-header-top.png` / `form-fixed-header-scrolled.png`）
  - 默认头像切换与类型选择已截图（`form-avatar-combined.png` 为合并头像/名字卡）
  - 首页宠物档案默认头像回退：修复前 `home-default-avatar-before.png` 显示通用占位图；修复后 `home-default-avatar-after.png` 中「满满」（CAT）显示默认猫、「旺财」（DOG）显示默认狗；`pet-detail-default-avatar.png` 为「满满」详情，详情与首页一致
  - 首页 5 只宠物时只展示前 3 只，标题显示总数：`home-three-pets.png`
  - 点击「5 只萌宠 ▾」下拉显示其余 2 只（旺财/豆豆），可进入详情：`home-other-pets-dropdown.png`
  - 新建宠物保存成功后自动回到首页：`home-after-create-return.png`（截图中的测试宠物已删除）

## 遗留 / 环境说明

- 本地 18081 已用本分支后端重建/重启（复用原数据库，Snowflake node 41）。E2E 测试域新增 `PetAvatarSigningApi` 夹具，把上传对象签名成 `data:` URL；已通过真实 HTTP 验证：上传 PNG → `avatarAssetId` 创建宠物 → 返回 `data:image/png;base64,...` 头像。测试宠物 `头像测试`（petId `108091698969743360`）及新建导航测试宠物 `nav-test`/`nav-test2` 已在验证后删除。生产仍走 OSS 受控签名 URL，不使用 data URL。
- 本地 E2E 的 data URL 签名仅为离线验收夹具，不改变生产 `PetAvatarSigningApiImpl` 的 OSS/HTTPS/24h 校验。
- 物理真机 VIS、私有对象读取签名过期/刷新尚未在本轮实机执行。
- 头像上传目前为整图上传，裁剪/旋转/相册不在本轮（由既有图片规范化管线处理）。
