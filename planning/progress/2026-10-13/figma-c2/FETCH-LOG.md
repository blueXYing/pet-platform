# Figma REST 抓取日志（figma-c2 切片，2026-10-13）

文件 key：`bp2vpcjjA5vZbHvtKkA8wl`（用户端 C 端）；token：dev（~/.zcode/figma-token-dev.txt）。
文件版本（2026-10-10 新版）：`2408620332145508244`。结构数据零配额（走 SSE 缓存 `D:/Temp/figma-mcp/user-end-page1.raw`，`extract_frames.py` 提取九帧 XML）。

REST images 调用共 **4 次**（本切片配额消耗，含 1 次误端点弃用）：

| # | 端点 | ids | 格式 | 产物 |
|---|---|---|---|---|
| 1 | `GET /v1/files/{key}/images`（误用，返回图片填充表，未用于切图） | 9 帧 | png scale=2 | （弃用） |
| 2 | `GET /v1/images/{key}?ids=61:599,690:6370,690:6660,97:7825,101:3,690:2025,690:4205,96:3328,96:4355` | 9 帧 | png scale=2 | design-*.png 九帧渲染对照图 |
| 3 | `GET /v1/images/{key}?ids=687:45,75:30,75:35,75:40,75:44,690:6709,690:6844,690:6877,62:27,62:43` | 10 节点 | png scale=2 | cutouts/（logo、保障图标、门店头像、评价头像、搜索/铃铛复核） |
| — | `GET /v1/images/{key}?ids=61:31,62:49` | 2 节点 | png scale=2 | cutouts/tex-61_31.png、tex-62_49.png（纹理复核与展平源） |

另：`GET /v1/files/{key}?depth=1` 一次（仅取版本号）。

复核结论（像素级）：
- `62:27`/`62:43` 切图与既有 `home-search.png`/`home-bell.png` 逐字节一致（沿用不换）。
- `61:31`（网格纸纹）与门店页既有 `690-6661-bg-huaban@2x.png` 像素一致（JPEG 再压后入主包）。
- `690:6709`/`690:6844`/`690:6877`（门店头像/评价头像）与既有切图 sha256 一致——新版未换素材（早前 AI 目视结论「已换」为误判，以像素/哈希为准）。
- `690:6660` 与 `690:2025` 渲染逐像素一致；`690:4205` 仅横幅/头像插画差异；`101:3` 与 `690:6370` 仅 2px 抖动。

产物去向：logo/保障图标 → `frontend-miniapp/src/consumer/assets/home/`（manifest 登记）；纹理 → 同目录 JPEG（huaban-grid/huaban-doodle）；门店/评价头像 → 弃用（一致）。
