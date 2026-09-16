# Pet Platform V1.0

AI-native multi-agent development repository bootstrap.

Start with:
- `00-START-HERE.md`
- `01-WORK-FIRST-PROMPT.md`
- `WORK_STATE.md`
- `AGENTS.md`

Do not implement product behavior by guessing. SSOT is authoritative.

## 当前进度与阅读入口

- [当前项目状态](WORK_STATE.md)：已合入阶段、尚未验收范围和下一步边界。
- [开发流程与文档阅读指南](05-开发流程与文档阅读指南.md)：人工负责人应该看什么、AI负责什么。
- [2026-09-16同步报告](planning/progress/2026-09-16/PROGRESS_SYNC.md)：PR6～34合并证据与本地文档处置。
- [设计资料保管](planning/DESIGN_SOURCES.md)：Figma原始资料只保留本地，不整包上传GitHub。

## 当前工程基线

远端仓库：https://github.com/blueXYing/pet-platform 。PR及技术契约审核人均为 @blueXYing。

- [最终资料索引](docs/README.md)：SSOT、三端PRD、后端v0.6与前端v0.7及契约/测试来源。
- [文件所有权](.ai/OWNERSHIP.md)：岗位写入范围与公共文件唯一编辑者。
- [Git与交付规则](docs/08-engineering/repository-policy.md)：历史保留、独立worktree、审核与合并门禁。
- [GOV-001交付报告](docs/08-engineering/GOV-001-report.md)：真实验证、阻断与交接。

backend/ 为Java21/Spring Boot模块化单体，已交付公共基础及部分认证、用户/宠物和OSS阶段；frontend-miniapp/ 为统一Taro React小程序，frontend-admin/ 为React/Vite运营网页，工程壳和部分C端预览页已实现。完整真实业务与生产验收以WORK_STATE及各Issue证据为准。frontend-c/、frontend-merchant/ 仅保留迁移说明。

2026-09-11已批准Wave 1启动；历史文件中的等待批准描述按WORK_STATE.md最新记录解释。启动批准不等于依赖完成、产品/契约变更批准或merge/release批准。
