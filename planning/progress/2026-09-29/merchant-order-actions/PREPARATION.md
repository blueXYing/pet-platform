# PR91收尾与ORD-001准备

日期：2026-09-29。实现基线：`1eb96ac74316c2aa9b66dae89f5d208b4c89b6d9`。准备分支：`codex/merchant-order-actions-20260929`。

## 已完成

- PR91合并状态、提交和完整CI已从GitHub核实：[合并](pr91-merge.json)、[六项CI](pr91-merge-ci.json)。[CI运行](https://github.com/blueXYing/pet-platform/actions/runs/36512780916)成功。
- 下载该次CI后端artifact，解析107份Surefire XML：649测试，failures/errors/skipped均为0；[逐报告哈希和重点用例汇总](pr91-backend-summary.json)。这是PR91基线证据。
- 归档旧台账，更新WORK_STATE与CCR索引。原业务代码和默认关闭开关未改。
- 核对SSOT、最终PRD及代码，完成[商家命令CCR候选](../../../ccr/CCR-W2-API-001/merchant-order-actions-proposal.md)。发现普通退款不能直接复用迟到退款特有的来源校验，且成功释放预约尚缺公共命令。

## 本轮验证

| 命令 / 检查 | 结果 |
|---|---|
| `python e2e/contract_smoke.py` | PASS_OFFLINE_DOCUMENT_SMOKE；90 operations、57写requestId、1023引用；不是HTTP实测。 |
| `python -m unittest discover -s e2e -p 'test_*.py' -v` | 115 tests，OK。 |
| `python backend/tools/check-module-deps.py` | 41 modules / 17 biz POMs，PASS。 |
| `python backend/tools/check-display-status.py` | PASS。 |
| `python backend/tools/check-persistence-style.py` | PASS，生产SQL均归属MyBatis XML。 |
| `python -m unittest discover -s backend/tools -p 'test_*.py' -v` | 18 tests，OK。 |
| 本轮三份当前文档的本地链接、CI摘要提交/数量核验 | PASS；旧历史台账保留当时内容。 |
| `git diff --check` | 已修正WORK_STATE末行CRLF混用后PASS。 |

本轮仅文档准备，不运行或宣称通过ORD-001业务验收。候选D1/D2/D3尚未批准，不把候选DDL放入生产迁移，不改正式API。已有后端Java代码未改，本轮不重复运行649项Java测试，以PR91合并CI实际artifact为基线。

## 后续及风险

- 新的字段级存储、HTTP、退款来源与释放协议，以及“服务端先到”对比“锁内截止＋事务提交”的边界，需要按WORK_EXECUTION_PROTOCOL §4完成具体CCR审阅。
- 推荐批准D1/D2/D3后先同步正式契约，依既有Issue Owner实现并测试，提交PR，不合并或启用生产。
- 主账号首轮切片不能冒称员工权限、一次改期、C端主动退款、完整读侧/小程序或真实渠道闭环全部完成；11组业务验收当前全部NOT_EXECUTED。
