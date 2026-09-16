# C-002 宠物档案页阶段(逐页推进第2页)

> 2026-09-16 状态同步：PR34最新head 80b5536已合入，添加页标题固定为“添加宠物信息”，独立编辑页尚无稿；现有旧入口复用问题仍待拆分。公共底栏、分包与预览业务回归已有证据，真实HTTP/真机等限制保留。

Owner Role: C-End Frontend
关联 Issue: C-002(本文件为其阶段任务,不替代原Issue Scope)
Priority: P0
Wave: 2
Status: STAGE_MERGED
基线: develop `0e314cffe5e5085ad13386410002e59f43afaa0a`(PR25合并后)

2026-09-15用户批准以编辑资料页样板(PR25)为标准逐页推进,本阶段为宠物档案页;并行会话执行,提示词见 planning/prompts/C-002-PET-PAGE-PROMPT.md。

## Allowed / Forbidden

2026-09-16 用户在 PR34 接手任务中授权统一 C 端底栏并保留分包结构；本阶段增加轻量导航/页面容器、公共导航素材、profile-edit 的底栏接入，以及包体依赖检查。唯一 Writer 仍为 C-End，本次不触及 shared/merchant/backend 或公共接口。范围、理由和验收见 [NAVIGATION-REFACTOR.md](C-002-pet-page/NAVIGATION-REFACTOR.md)。

- 允许:`frontend-miniapp/src/consumer/pages/pet-archive/**`、`src/consumer/pet/**`(数据模型)、`src/consumer/tests/pet-*.cjs/ts`、`src/app.config.ts`(页面注册,C-End唯一编辑者)、`planning/issues/wave-2/C-002-pet-page/`(验收证据目录,新建)。
- 禁止:`src/merchant/**`、`src/shared/**`(公共改动需另行登记)、backend/**、fonts/shared素材复制、提交design-inputs素材包本体。
- 设计规范只读来源:`C:/Users/Administrator/Desktop/宠物平台V1.0/planning/issues/wave-2/C-002-design-inputs/handoff/pages/`(本机绝对路径,该包untracked不入库,引用其节点ID/哈希即可)。

## 页面范围(设计节点)

| 节点 | 页面 | 本阶段 |
|---|---|---|
| 78:2817 | 首页-宠物档案(列表入口) | 实现 |
| 78:3076 | 宠物档案详情 | 实现 |
| 95:1481 / 95:1844 | 宠物档案填写(两种示例态) | 实现(新增/编辑共用) |
| 96:2580 / 96:3006 | 疫苗/驱虫记录添加 | 不实现,登记后续页 |
| 245:5375 等 | 品种百科系列 | 不实现,登记后续页 |

页内超范围入口按素材包coverage排除,不因有素材擅自实现。

## 数据与验收

- Mock数据字段严格按已批用户域契约PetView(petId/name/petType/breedName/birthDate/sex/weightKg/sterilizationStatus/vaccineStatus/healthNote/avatarUrl/isDefault/status);ID为String;金额/体重两位小数字符串。
- 闭环标准=PR25样板四步:spec.json校对→字体/状态补齐(字体沿用PR25方案,不重复引入)→页面实现→微信真实窗口截图叠图(390/414,复用C-002-profile/compare-visual.py机制与证据格式)。
- 状态覆盖:加载/空列表/有数据/删除确认/表单校验错误/保存中/保存成功/保存失败,缺设计状态登记补充稿,不私造。
- npm run typecheck/test/build:weapp/check:package全过;包体预算沿用既有脚本。

## DoD

CI六job通过+VISUAL_ACCEPTANCE.json证据齐+用户人工确认叠图后PR合并(会话不得自merge)。完整C-002仍非DONE。
