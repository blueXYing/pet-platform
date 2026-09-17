# MER-001 契约阶段交接与验证

日期2026-09-17；基线 `609153bd4584d538e32cf828aae06efaba8f9ce5`。
独立工作区 `C:/Users/Administrator/Desktop/wt-mer001-contract`，分支 `codex/mer001-contract-20260917`；唯一Writer为当前任务，无其他Worker。

## 交付

- [主提案](merchant-domain-proposal.md)：范围、三查询DTO、成员事实、员工六路由、协议两条新路由、鉴权/错误/版本/幂等、阶段进入条件。
- [存储映射](merchant-storage-design.md)：现有表含义、候选协议/成员/本域幂等关系与归属，不是执行DDL。
- [来源和差异](merchant-sources-and-gaps.md)：SSOT/原始Word/Contract映射、7类差异及Owner；13份来源文件hash可复核。
- [验收矩阵](merchant-acceptance-matrix.md)：22项未来用例及审阅示例，全部明确NOT_EXECUTED。
- 根Work/Ready Queue/Issue Catalog/MER Issue/CCR索引登记契约阶段IN_PROGRESS和REVIEW_REQUIRED；不关闭整个MER-001或CCR。

## 实际验证结果

| 命令/检查 | 本轮实测 |
|---|---|
| `python backend/tools/check-module-deps.py` | PASS，41 reactor模块、17 biz POM，无biz→biz依赖 |
| `python backend/tools/check-display-status.py` | PASS，既有后端/两前端扫描未见重复状态推导模式 |
| `python -m unittest discover -s backend/tools -p 'test_*.py' -v` | 13/13通过 |
| `python e2e/contract_smoke.py` | PASS_OFFLINE_DOCUMENT_SMOKE；52操作、37写、630引用、118 String ID属性 |
| `python -m unittest discover -s e2e -p 'test_*.py' -v` | 82/82通过 |
| 提案本地链接/来源hash/用例唯一性检查 | 13个源文件hash、22个唯一用例通过；最终5份提案/交接共10个相对链接全部通过 |
| 权威基线/代码差异检查 | docs/backend/frontend-miniapp/frontend-admin/e2e/.github/AGENTS/WORK_EXECUTION_PROTOCOL相对HEAD无改动 |
| `git diff --check`及`git diff --cached --check` | CSV与hash清单换行修正后均通过 |

此次是planning文档变更，未在本地重跑Maven/ArchUnit、数据库集成、HTTP/微信真机测试。以上静态门禁与离线契约回归不证明新提案已获批或商家功能已实现；PR的完整CI状态单独报告，不能用既有261项JUnit的历史记录当新代码通过证据。

工具过程：初次Python stdin包含中文路径，被本机PowerShell编码导致SyntaxError，未写入目标；改用ASCII glob定位源文件后完成。首次Catalog保存引入CRLF整文件差异，被diff检查发现，已按HEAD原始字节只替换MER-001状态，最终仅一行变化。均未改权威产品资料或业务代码。

## 下一步与门禁

先审阅主提案三个决定：技术契约、协议换版政策、下线存量条款解释。依据WORK_EXECUTION_PROTOCOL §4，“产品裁决”“Contract重大变更”须人工批准；CCR-W2-API-001规定“受保护HTTP/OpenAPI/Internal/Schema修改须在草案获批后由明确Owner执行”。当前用户授权已经足够启动和完成提案，不自动代表批准尚未见过的新Contract。

批准后由本Owner按提案S2/S3同步权威契约和实施；MER-D3/D4/D5/D7的技术交接继续由开发负责，不能要求用户设计DTO/数据库，也不能以成功fixture假装依赖完成。协议强制重新同意、下线存量规则没有本次私自裁决。

不合并develop，不发布生产，不改主目录用户project.config.json。主账号核销映射、完整入驻/OCR/主体去重、成员绑定、员工停用与订单并发、冻结写动作、生产ID/迁移、真机/VIS均保留明确边界。
