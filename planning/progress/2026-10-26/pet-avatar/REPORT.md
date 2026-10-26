# 宠物头像上传、默认头像与类型前移（2026-10-26）

## 用户裁决

`consumer/pages/pet-archive/form` 当前无法上传头像；按 Figma 编辑页头像卡与默认头像落地。用户选择“真实上传 + CCR”。

## 实现

- **类型前移**：添加表单开头新增「宠物类型」三选一（狗狗/猫猫/其他），`PetDraft.petType` 随创建保存；保存时不再弹原生 action sheet；编辑模式类型只读（`petType` 创建后不可改）。
- **默认头像**：Figma `688:480`（猫）/`688:479`（狗）导出 PNG 入 `pet-archive/assets`；无上传头像时按 `petType` 展示对应默认头像，`OTHER` 不展示默认图（空头像卡 + “更换头像”）。真实宠物列表/详情同样回退默认猫/狗；`OTHER` 维持首字占位。
- **头像卡**：按 `645:22`（343x86 浅蓝卡）、`645:10`（65x54 头像）、`645:11`（`#b89682` 11px“更换头像”）落地；头像与「宠物名字」输入在同一矩形卡内（`95:1764`/`95:1773`），类型选择改为 8px 小圆角矩形（非半圆）；表单高度 1066/1067 → 1162/1163 设计px，既有字段/按钮下移。
- **真实上传**：C 私有素材新增 `PET_AVATAR` 用途（`POST /api/v1/c/private-assets`，消费者本人，复用不可变上传/扫描/规范化/10MiB 管线）；宠物创建/更新接受 `avatarAssetId`，服务端校验 owner/purpose/READY 后写入内部不透明 `asset:<id>`；`PetView.avatarUrl` 读取时通过 `PetAvatarSigningApi` 返回短期签名 URL。CCR：`planning/ccr/CCR-C-PET-AVATAR-001.md`。无 SQL06 Schema 变更。
- **契约/设计登记**：`docs/08-engineering/20-设计源登记表-figma-map.md` §4.7 偏差 13；`assets/manifest.json` 登记两个默认头像节点、字节和 sha256。

## 验证

- 前端 `npm run typecheck`：PASS
- 前端单测：**616 / 616**
- `python3 backend/tools/run-frontend-gate.py miniapp-build`：**PASS**
- `check:package`：总包 **8,155,376** 字节，预算内
- 后端 JDK 21 `mvn compile` / `test-compile`：PASS（pet-user-biz / pet-thirdparty-biz / pet-boot 及依赖）
- `PetValidatorAvatarTest`：PASS（asset ref 与 https 校验）
- `check-module-deps.py`：PASS
- 微信开发者工具（当前构建）：
  - 狗狗类型：默认狗头像
  - 猫猫类型：默认猫头像
  - 其他类型：空头像卡，无默认图
  - 类型选择为矩形小圆角；头像与名字在同一矩形卡内
  - 默认头像切换与类型选择已截图（`form-avatar-combined.png` 为合并头像/名字卡）

## 遗留 / 环境说明

- 本地 18081 后端是从 Windows 独立工程副本启动的旧构建，尚未重新编译本分支后端；因此本地“选择图片→上传”会在旧接口返回失败（UI 会保留本地预览），真实上传需用本分支后端重建并重启。生产/CI 口径以 CCR 为准。
- 本地 E2E 服务器使用内存 `PrivateObjectStore` 夹具、无 OSS 签名器；`PetAvatarSigningApi` 在无 OSS 环境按设计跳过，宠物保存带 `avatarAssetId` 会失败关闭（503），不会伪造头像 URL。真实 OSS 环境才返回签名 URL。
- 物理真机 VIS、私有对象读取签名过期/刷新尚未在本轮实机执行。
- 头像上传目前为整图上传，裁剪/旋转/相册不在本轮（由既有图片规范化管线处理）。
