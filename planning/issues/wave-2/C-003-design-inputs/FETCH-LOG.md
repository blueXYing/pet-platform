# C-003/M-002 本轮设计抓取日志（FETCH-LOG）

> 设计资料负责人（角色C）集中抓取，产物共享给其他角色，避免重复请求 Figma。
> 认证：PAT 运行时从 `C:/Users/Administrator/.zcode/figma-token.txt` 读取，仅用于 `X-Figma-Token` 请求头，不落任何文件。

## 版本核对（2026-09-22 UTC，抓取前）

| 文件 | key | 在线 version | 在线 lastModified (UTC) | 登记表记录 | 结论 |
|---|---|---|---|---|---|
| 宠物小程序（用户端） | `bp2vpcjjA5vZbHvtKkA8wl` | `2401180846413285` | `2026-09-20T07:14:46Z` | version `2401180846436413285`，lastModified `2026-09-20T07:14:46Z` | 一致，无更新 |
| 宠物小程序商家端 | `Usvn3d6UCVCAlDxou5KAK8` | `2401168563353004923` | `2026-09-20T07:05:58Z` | version `2401168563353004923`，lastModified `2026-09-20T07:05:58Z` | 一致，无更新 |

**本轮采用版本 = 登记表版本**（两文件自 2026-09-21 全量复核后未变化）。20 号登记表 §3/§4 无需维护更新。

## 抓取记录（REST API）

| # | 时间 (UTC) | 请求 | HTTP | 产物 |
|---|---|---|---|---|
| 1 | 2026-09-22 | `GET /v1/files/bp2vpcjjA5vZbHvtKkA8wl?depth=1`（元数据核对） | 200 | 版本核对（见上表） |
| 2 | 2026-09-22 | `GET /v1/files/Usvn3d6UCVCAlDxou5KAK8?depth=1`（元数据核对） | 200 | 同上 |
| 3 | 2026-09-22 | `GET /v1/files/bp2vpcjjA5vZbHvtKkA8wl/nodes?ids=110:480,690:6370,690:6660,238:2356,690:7877,690:8107,690:8337` | 200 | `nodes/frame-summaries.json`（用户端7帧） |
| 4 | 2026-09-22 | `GET /v1/files/Usvn3d6UCVCAlDxou5KAK8/nodes?ids=10:5255,11:5768` | 200 | `nodes/frame-summaries.json`（商家端2帧） |
| 5 | 2026-09-22 | `GET /v1/images/bp2vpcjjA5vZbHvtKkA8wl?ids=<7帧>&format=png&scale=2` | 200 | `assets/screenshots/user-*@2x.png` ×7 |
| 6 | 2026-09-22 | `GET /v1/images/Usvn3d6UCVCAlDxou5KAK8?ids=10:5255,11:5768&format=png&scale=2` | 200 | `assets/screenshots/mer-*@2x.png` ×2 |
| 7 | 2026-09-22 | `GET /v1/files/bp2vpcjjA5vZbHvtKkA8wl/nodes?ids=690:2025,690:4205`（补充：服务详情页设计源，登记表§3"热门服务-宠物美容-详情页"） | 200 | `nodes/frame-summaries.json`（用户端2帧）+ 截图 ×2 |
| 8 | 2026-09-22 | `GET /v1/images/bp2vpcjjA5vZbHvtKkA8wl?ids=<商家详情页11节点>&format=png&scale=2` | 200 | `assets/cutouts/690-*@2x.png` ×11（字节原样落盘） |
| 9 | 2026-09-22 | `GET /v1/files/Usvn3d6UCVCAlDxou5KAK8?depth=1`（M-002 实施期版本复核） | 200 | 版本 `2401168563353004923` 无变化（与第2行一致），登记表无需维护 |
| 10 | 2026-09-22 | `GET /v1/files/Usvn3d6UCVCAlDxou5KAK8/nodes?ids=10:5255,11:5768`（M-002 页面切图节点定位；原始 JSON 340KB 留仓库外缓存） | 200 | 节点级图标定位（返回箭头/加号/相机），见下表 |
| 11 | 2026-09-22 | `GET /v1/images/Usvn3d6UCVCAlDxou5KAK8?ids=10:5302,10:5333,11:5772,11:5674&format=png&scale=2` | 200 | `assets/cutouts/mer-*@2x.png` ×4（字节原样落盘；10:5302 与 11:5772 哈希一致，为同一矢量两次导出） |

说明：
- 节点 JSON 原始响应体积 1.9MB/0.3MB/0.5MB，超过登记表"过大只保留结构化摘要"口径，仓库内只落 `nodes/frame-summaries.json`（文本/图片填充/纯色矩形/图层计数的局部坐标摘要）；原始 JSON 留在仓库外缓存。
- 截图为整帧 PNG（scale=2），用作叠图对照基准，不用作运行时素材（20号基线 §4：不用整页截图假装真实交互页面）。
- 切图为实现所需原始资产的 2x PNG 原样导出（节点级），字节未做任何修改；哈希见 `assets/manifest.json`。

## 节点 → 产物对照

| node-id | frame 名 | 截图 | 备注 |
|---|---|---|---|
| `110:480` | 服务 | `screenshots/user-110-480@2x.png` | 服务tab（本轮仅规划，依赖未冻结的 /c/stores） |
| `690:6370` | 服务-全部服务-分类 | `screenshots/user-690-6370@2x.png` | 同上 |
| `690:6660` | 服务-商家详情页 | `screenshots/user-690-6660@2x.png` | 本轮实现设计源；切图 ×11 |
| `690:2025` | 热门服务-宠物美容-详情页 | `screenshots/user-690-2025@2x.png` | 服务详情页设计源；与 690:6660 内容完全一致（同稿三份） |
| `690:4205` | 热门服务-宠物美容-详情页 | `screenshots/user-690-4205@2x.png` | 同上（同稿副本） |
| `238:2356` | 服务-商家详情页-预约详情 | `screenshots/user-238-2356@2x.png` | 预约详情状态1（本轮仅规划） |
| `690:7877` | 服务-商家详情页-预约详情 | `screenshots/user-690-7877@2x.png` | 状态2 |
| `690:8107` | 服务-商家详情页-预约详情 | `screenshots/user-690-8107@2x.png` | 状态3 |
| `690:8337` | 服务-商家详情页-预约详情 | `screenshots/user-690-8337@2x.png` | 状态4 |
| `10:5255` | 首页-商品管理（商家端） | `screenshots/mer-10-5255@2x.png` | M-002 服务项目管理列表页候选设计源（见 INVENTORY §4 裁决建议） |
| `11:5768` | 首页-商品管理-添加商品（商家端） | `screenshots/mer-11-5768@2x.png` | M-002 服务新增/编辑表单候选设计源 |

切图（`assets/cutouts/`，均来自用户端 `690:6660` 一帧）：

| 文件 | node-id | 设计尺寸(1x) | 用途 |
|---|---|---|---|
| `690-6661-bg-huaban@2x.png` | 690:6661 | 402x787 | 页面花卉背景（STRETCH） |
| `690-6908-store-banner@2x.png` | 690:6908 | 375x180 | 店铺头图 |
| `690-6709-store-avatar@2x.png` | 690:6709 | 56x56 | 店铺头像 |
| `690-6722-star-orange@2x.png` | 690:6722 | 10x9 | 评分星（橙 #FF9500） |
| `690-6889-star-blue@2x.png` | 690:6889 | 10x9 | 评价星（蓝 #5BAAE8） |
| `690-6750-icon-location@2x.png` | 690:6750 | 12x14 | 门店信息-地址图标 |
| `690-6758-icon-clock@2x.png` | 690:6758 | 13x13 | 门店信息-营业时间图标 |
| `690-6766-icon-phone@2x.png` | 690:6766 | 12x12 | 门店信息-电话图标 |
| `690-6706-nav-back@2x.png` | 690:6706 | 9x15 | 返回箭头 |
| `690-6844-reviewer-1@2x.png` | 690:6844 | 32x32 | 评价头像1 |
| `690-6877-reviewer-2@2x.png` | 690:6877 | 32x32 | 评价头像2 |

商家端切图（`assets/cutouts/mer-*`，M-002 实施期补导，规则同上）：

| 文件 | node-id | 设计尺寸(1x) | 用途 |
|---|---|---|---|
| `mer-10-5302-nav-back@2x.png` | 10:5302 | 8.9x14.5 | 商品管理列表页导航返回箭头 #3C3C3C |
| `mer-10-5333-icon-plus@2x.png` | 10:5333 | 8.8x8.8 | 添加商品＋号 #5BAAE8 |
| `mer-11-5772-nav-back@2x.png` | 11:5772 | 8.9x14.5 | 添加商品页返回箭头（与 10:5302 字节一致） |
| `mer-11-5674-icon-camera@2x.png` | 11:5674 | 9.7x8.7 | 更换头图角标相机图标 #FFFFFF |
