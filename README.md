# Pet Platform V1.0

AI-native multi-agent development repository bootstrap.

Start with:
- `00-START-HERE.md`
- `01-WORK-FIRST-PROMPT.md`
- `WORK_STATE.md`
- `AGENTS.md`

Do not implement product behavior by guessing. SSOT is authoritative.

## 当前工程基线

远端仓库：https://github.com/blueXYing/pet-platform 。PR及技术契约审核人均为 @blueXYing。

- [最终资料索引](docs/README.md)：SSOT、三端PRD、后端v0.6与前端v0.7及契约/测试来源。
- [文件所有权](.ai/OWNERSHIP.md)：岗位写入范围与公共文件唯一编辑者。
- [Git与交付规则](docs/08-engineering/repository-policy.md)：历史保留、独立worktree、审核与合并门禁。
- [GOV-001交付报告](docs/08-engineering/GOV-001-report.md)：真实验证、阻断与交接。

backend/ 为既有40模块Java21后端骨架；frontend-miniapp/ 为统一Taro React小程序，frontend-admin/ 为React/Vite运营网页。前端目前仅目录说明，脚手架由各自Issue建立。frontend-c/、frontend-merchant/ 仅保留迁移说明。

2026-09-11已批准Wave 1启动；历史文件中的等待批准描述按WORK_STATE.md最新记录解释。启动批准不等于依赖完成、产品/契约变更批准或merge/release批准。
