# figma-mine 素材与工具登记（2026-10-12 我的族视觉重做）

未入仓的工作缓存（可从 `D:/Temp/figma-mcp/user-end-page1.raw` 重新提取，体积大不提交、不删除）：
`nodes-all.json`（1.25MB）/ `node-126-675.json`（332KB）/ `tree.xml`（2.5MB）/ `f-126-675-mine.xml` / `mcp-126-675.txt` / `figma-*.png.raw`（S3 响应存根）/ `icon-svg-urls.json` / `retry.txt`。

接力素材（前任代理拉的未跟踪文件，全部保留；截图与样式树已随本 PR 入仓）：
- `figma-{frame}.png` — 8 帧参考渲染（126:675/127:1652/128:2078/129:8174/688:371/78:3076/95:1481/95:1844，scale=2，REST 1 次批量调用）。
- `node-126-675.json` / `nodes-all.json` — 2026-10-10 版（version 2408620332145508244）nodes JSON（自 `D:/Temp/figma-mcp/user-end-page1.raw` 提取，零配额）。
- `rest-images.json` / `rest-test.json` — REST images 调用记录（dev token）。
- `tree.xml` / `f-126-675-mine.xml` / `mcp-126-675.txt` / `frames-list.txt` — 全稿结构缓存与帧目录。

本次生成：
- `dump_tree.py` → `tree-*.txt`：8 帧可读样式树（类型/坐标/尺寸/fills/strokes/圆角/阴影/auto-layout/文本样式），实现时的逐项对照依据。
- `icons/`：29 个原矢量 SVG（REST 1 次批量 format=svg）+ `manifest.json` 节点清单 + `_icons.css` base64 片段（已内嵌 `mine.css`/`coupon-points.css`）。
- `strip-top-preview.png` / `strip-top-jpg-preview.png`：mine 头部纸纹（huaban a20e1db6…，与 pet 分包 strip-main.png 同源，STRETCH 402x175 → `mine/assets/strip-top.jpg`）。
- 结论与偏差登记：`docs/08-engineering/20-设计源登记表-figma-map.md` §4.6。
