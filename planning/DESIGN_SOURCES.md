# 设计来源与本地素材保管

更新时间：2026-09-16。只记录设计来源和既有盘点事实，不上传Figma原始文件，不新增页面范围或变更产品规则。

| 端 | Figma来源 | 已归档来源版本 | 本地原始资料位置（非GitHub文件链接） |
|---|---|---|---|
| 用户端 | [用户设计](https://www.figma.com/design/bp2vpcjjA5vZbHvtKkA8wl) | 2397539525915641008 | `planning/issues/wave-2/C-002-design-inputs/` |
| 商家端 | [商家设计](https://www.figma.com/design/Usvn3d6UCVCAlDxou5KAK8) | 2399015827492126503 | `planning/issues/wave-2/M-002-design-inputs/` |

以上资料已于2026-09-15盘点、导出并在本机保留。2026-09-16整理时用户端1048文件、商家端572文件，共约230MB，已备份并按文件哈希核对。这是历史导出事实，不表示本轮重新在线读取了Figma，也不表示全部布局/状态/字体均通过VIS。

## Git保管规则

- 用户明确要求原始Figma素材不上传GitHub。这两处资料目录在根 .gitignore 中忽略，不复制进本同步PR。
- 原始大图、全文件导出、压缩快照和字体来源包保持本地；本机备份位置可查主目录的本地工作区说明（同样不入Git）。
- 已随已合并页面提交的运行必需图片/图标、字体子集和既有验收证据不在此次删除范围。后续页面按获批范围、来源追溯和包体预算使用资源，不把原始资料整包入库。
- Git内记录文件key、版本、节点、页面范围与已提交交接的链接。未跟踪的本地路径用普通代码文字标注，不能写成GitHub可用的文件链接。

## 当前页面证据

[编辑资料交接](issues/wave-2/C-002-profile/HANDOFF.md)、[宠物页交接](issues/wave-2/C-002-pet-page/HANDOFF.md)、[统一导航说明](issues/wave-2/C-002-pet-page/NAVIGATION-REFACTOR.md)、[添加页标题人工修正](issues/wave-2/C-002-pet-page/ADD-PET-TITLE-CORRECTION.md)均已入库。

用户确认95:1481/95:1844对应当前添加页，标题固定为“添加宠物信息”；独立编辑页尚无稿，旧编辑入口复用问题仍登记为待拆分。商家端虽然已取得原稿，V1删减、页面状态、接口与真实业务门禁仍需逐页解决。
