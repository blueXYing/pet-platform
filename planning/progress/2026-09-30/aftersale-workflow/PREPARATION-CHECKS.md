# AFS 准备提交检查

日期：2026-09-30。分支：codex/aftersale-workflow-20260930；实现基线ff983596cad899c7b4e1ce9c8278228787ca7ac6。当前只改规划/方案/状态记录，未改正式契约、Schema、业务或测试源码。

| 实际执行 | 结果 | 能证明的范围 |
|---|---|---|
| `python -m unittest discover -s e2e -p 'test_*.py'` | 118项通过，0失败 | 既有离线契约回归，不是AFS实际接口验收 |
| `python e2e/contract_smoke.py` | PASS；92个既有操作、58个写requestId、1056个引用 | 既有OpenAPI静态结构；没有新AFS HTTP操作 |
| `python -m unittest discover -s backend/tools -p 'test_*.py'` | 18项通过，0失败 | 架构/持久化检查器自身正负用例 |
| `python backend/tools/check-module-deps.py` | PASS；41 reactor modules、17 biz POMs | 现有源码依赖未出现biz→biz |
| `python backend/tools/check-display-status.py` | PASS | 现有源码未出现重复展示态计算模式 |
| `python backend/tools/check-persistence-style.py` | PASS | 现有生产SQL保持本域MyBatis XML规范 |
| `git diff --check` | PASS | 本次已跟踪文件空白检查；提交前新增文件一并复验 |

Python实际使用 `D:/Python/python.exe`，工作目录为附属refund-aftersale工作树；没有使用原Desktop用户目录做修改。测试发现过程无新增用例。

PR96合并后六项CI及852后端测试的实际报告见[接续记录](START.md)；这批准备修改不改变其代码树，但不能用该历史结果冒充本提交CI。新AFS功能测试/SQL50迁移/真实资金Provider/HTTP/前端均NOT_EXECUTED。文档改动没有启动Maven、MySQL或Redis；后续正式实现按串行构建及隔离数据库执行。

独立方案审查见[审查报告](CONTRACT-REVIEW.md)。产品四项未获答复、重大契约待具体批准；资金权威来源OD-W0-001保留，不把默认关闭或测试夹具当生产交付。

附加静态核验：本轮8份Markdown的61个本地链接存在，新增文件UTF-8无BOM/LF，三份JSON可解析；历史WORK_STATE字节与基线Git对象完全一致。START新增检查链接随后计入提交前全量本地链接复验。原Desktop目录状态仍仅原来的project.config.json修改、.zcodeignore及设计源登记表；pr96-ci配置仍为PAUSED。
