# 宠物默认头像表单/详情透明裁剪修正（2026-10-27）

## 用户反馈

默认头像上下不完整，且有淡淡的浅蓝色背景，与 Figma 效果不一致。

## 根因

- `688:480` / `688:479` 导出的是 **45x47 / 55x46 蓝底方形组件**（#c5e9f9，首页/列表 `61:599` 的默认头像即此形态），已被用于 `pet-default-cat/dog.png`。
- 但 Figma 表单/详情的头像槽位是 `645:10`：65x54 的 **透明图像填充**，图像按 `h=178.35% / top=-44.61%` 裁剪，背景由 `#d2f1ff` 卡面透出。
- 直接复用 688 蓝底导出会：
  1. 在 `#d2f1ff` 卡面上显示一块更深的蓝底；
  2. 沿用 688 的紧裁切，上下内容与 645:10 的裁切不一致，看起来“上下不完整”。

## 修正

- 新增 **透明** 默认头像 `pet-form-default-cat.png` / `pet-form-default-dog.png`：从 Figma `1080x1611` 透明图像填充裁出 65x54（2x 130x108，RGBA）；狗按 `645:10` 的 frame 变换；猫取 y250..1240 的完整头部（耳尖到下巴）等比缩放居中，保留透明侧边距，避免裁耳或裁下巴。
- `consumer/pages/pet-archive/form` 与 `detail` 改用新透明资源；`home` 与 `pet-archive/list` 继续使用 688 蓝底组件（与 Figma `61:599` 一致）。
- `assets/manifest.json` 登记两个派生资源、字节、sha256 与来源；688 资源标注为 home/list 专用。
- 无 API/契约/Schema/Event 变化。

## 验证

- 前端 `npm run typecheck`：PASS
- 前端单测：**626 / 626**（新增表单/详情透明资源静态断言）
- `check:package`：本地联调包 **8,238,179** 字节，预算内
- 微信开发者工具（`pet-platform-merged`，已同步本地联调构建并刷新）：
  - 表单头像：蓝底方形消失，头像与 `#d2f1ff` 卡面融合，上下完整：`form-avatar-fixed.png`
  - 猫咪头像：耳尖与下巴均完整，按完整头部等比缩放（y250..1240），不再顾头不顾腚
  - 详情头像：同样透明融合：`detail-avatar-fixed.png`
  - Figma `95:1481` 与实现头像并排对比：`compare-form-avatar.png`（左 Figma，右实现）

## 证据

- `form-avatar-fixed.png`：表单默认狗头像
- `form-cat-full.png`：表单默认猫头像（耳尖+下巴完整）
- `form-cat-full-zoom.png`：猫头像 4x 放大核验
- `detail-avatar-fixed.png`：详情默认猫头像
- `compare-form-avatar.png`：Figma `95:1481` vs 实现（65x54 槽位，4x 放大）

## 遗留

- 首页/列表继续保留 Figma 蓝底默认头像组件（`61:599`）；若用户希望首页/列表也去蓝底，需要另行裁决，不在本次范围。
