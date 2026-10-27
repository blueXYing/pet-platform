# 宠物档案添加表单头部修正（2026-10-26）

## 用户反馈

`consumer/pages/pet-archive/form`：

1. 右上角有「笔」图标，但添加模式不应显示；该图标应只属于编辑场景。
2. 「添加宠物信息」卡右上角有一个无功能的关闭/箭头图标，点击无反应，直接移除避免语义冲突。

## 改动

- `frontend-miniapp/src/consumer/pages/pet-archive/form.tsx`
  - 右上角 `nav-edit` 仅在有 `petId`（编辑模式）时渲染；添加模式不显示。
  - 移除「添加宠物信息」卡右上角的 `form-chevron` 图片。
  - 移除不再使用的 `formChevron` import。
- `frontend-miniapp/src/consumer/pages/pet-archive/form.css`
  - 删除 `.pet-form-chevron` 定位样式。
- `docs/08-engineering/20-设计源登记表-figma-map.md` §4.7 登记偏差 12。
- 不改接口、状态机、requestId、预览夹具或页面路由。

## 验证

- `npm run typecheck`：PASS
- `npm test`：**615 / 615**，0 失败
- `python3 backend/tools/run-frontend-gate.py miniapp-build`：**PASS**
- `check:package`：总包 **8,140,131** 字节，预算内
- 微信开发者工具（真实模式，`consumer/pages/pet-archive/form`）：
  - `.pet-form-navedit` + `.pet-form-chevron` 查询结果为空
  - 添加页截图确认右上角无笔、卡片右上角无 X
- 编辑模式（`preview=1&petId=30001`）：
  - `.pet-form-navedit` 数量 1（仅编辑模式显示）
  - `.pet-form-chevron` 数量 0

## 证据

- `form-header-fixed.png`：真实添加页，右上角无笔、卡片右上角无 X
- `form-edit-pencil.png`：编辑模式保留右上角笔，叉形图标已移除（该截图走 `preview=1`，底部预览导航按既有规则保留）

## 遗留

- 编辑模式页面标题仍为「添加宠物信息」（既有文案，不在本次两项调整范围）。
- 未执行物理真机 VIS；当前为微信开发者工具 + 本地真实后端。
