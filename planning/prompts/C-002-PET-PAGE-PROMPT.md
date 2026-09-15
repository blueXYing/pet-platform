# 提示词:C-002 宠物档案页(复制以下全部内容到新会话)

你是本项目六岗位中的 **C-End Frontend(小程序C端前端)**。开始任何工作前必须先读取工作区根目录的 AGENTS.md 并严格遵守;然后读取 planning/issues/wave-2/C-002-pet-page.md(本任务规划,含Allowed/Forbidden与验收标准)与 WORK_STATE.md 最新段落了解项目状态。

## 第一步:创建工作区(安全命令,不碰其他分支)

在仓库主目录(C:/Users/Administrator/Desktop/宠物平台V1.0)执行:

```
git fetch origin
git worktree add "C:/Users/Administrator/Desktop/wt-c002-pet" -b codex/c-002-pet-pages origin/develop
```

之后所有工作在新目录 C:/Users/Administrator/Desktop/wt-c002-pet 内进行。基线develop `0e314cff`。

## 任务

按PR25编辑资料页已立的样板标准,完成宠物档案页(列表入口78:2817、详情78:3076、填写表单95:1481/95:1844,疫苗/驱虫/品种百科页不在本阶段)。闭环四步:spec校对→字体与状态→实现→微信截图叠图。

- 样板参照(只读):frontend-miniapp/src/consumer/pages/profile-edit/、src/consumer/profile/model.ts、src/consumer/tests/profile*、planning/issues/wave-2/C-002-profile/(VISUAL_ACCEPTANCE.json与compare-visual.py机制,证据格式照抄)。
- 设计规范只读来源(绝对路径,包未入git,禁止复制进仓库):C:/Users/Administrator/Desktop/宠物平台V1.0/planning/issues/wave-2/C-002-design-inputs/handoff/pages/<节点ID>/ 下的README.md与spec.json。坐标以spec.json精确值为准,禁止取整/合并字号;fonts沿用profile-edit方案与字体文件,不重复引入。
- Mock数据字段严格按已批用户域契约PetView(ID为String,体重两位小数字符串,见planning/ccr/CCR-W2-API-001/user-pet-domain-proposal.md)。
- 只允许修改:src/consumer/pages/pet-archive/**、src/consumer/pet/**、src/consumer/tests/pet-*.cjs/ts、src/app.config.ts(页面注册)、planning/issues/wave-2/C-002-pet-page/(新建证据目录)。禁止碰src/merchant、src/shared、backend、他人文件。

## 工作纪律

1. 动手前先输出"将创建/修改的文件清单",与上述Allowed自查一致后再写代码。
2. npm ci后依次跑 typecheck/test/build:weapp/check:package,全过才算完成;缺设计的状态登记为补充稿需求,不私造样式。
3. 提交推送后创建**草稿PR**(base develop,标题feat(C-002): pet archive pages),用 `gh pr checks --watch` 盯CI到六job全绿,**不得自行merge**——合并权在用户。
4. 最终报告:文件清单、每页叠图对比结论、未实现/登记项、PR链接。

## 环境

本机gh已登录可直接push/建PR;微信开发者工具用于截图(参照C-002-profile证据的环境记录方式,390/414两窗口)。
