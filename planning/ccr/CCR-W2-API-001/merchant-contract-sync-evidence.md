# MER-001 已批契约同步交接

2026-09-17。输入：用户依次确认协议换版、下线语义及技术契约；[批准原话](merchant-product-decisions.md)。唯一Writer为当前任务（契约与配套QA），分支codex/mer001-contract-20260917，沿用PR50，未合并develop。

## 同步范围

- SSOT §26/PRD25：不新增强制重签，已签效力保持；下线停止新单，存量履约/退款售后继续；冻结写动作不扩展。
- [27号接口补充](../../../docs/04-api/27-Merchant-Domain-Contract-v0.1.md)：获批查询/DTO/成员/主账号操作者/员工/协议/幂等/错误语义。
- [27号存储映射](../../../docs/03-database/27-Merchant-Domain-Storage-v0.1.md)：已批字段与关系；明确DDL_NOT_DELIVERED，不把设计文档当建表脚本或生产迁移。
- 07/10/12增加正式补充入口和冲突覆盖说明；OpenAPI11新增员工六操作+协议两操作，标记ACCEPTED_CONTRACT_NOT_IMPLEMENTED。已有52操作及既有schema结构未改变。
- smoke精确白名单新增获批八操作，校验固定路径、Bearer、错误包、首次201/重放200及未实现状态；不是放开任意操作。原交易16操作和AUTH36操作各自守卫保留。
- 新增9项离线schema/负例测试：同意true、版本/hash必填、拒绝自报身份/签署时间、档案与成员分离、版本String、已签/未签响应区分、错误无旧数据、操作/鉴权/实现状态防误改、requestId和创建重放。

## 实测

- `python e2e/contract_smoke.py`：PASS；60操作、42写、742引用、136 String ID属性，其中merchantOperations=8。
- `python -m unittest discover -s e2e -p 'test_*.py' -q`：91/91通过（原82+新增9）。不是真实HTTP、数据库或业务E2E。
- 模块依赖/DisplayOrderStatus静态扫描通过；架构工具回归13/13通过；本轮变更文档56个本地链接均可解析，已有OpenAPI路径和schema结构保持不变。独立完整OpenAPI validator在当前Python环境不可用，未宣称通过完整规范验证；使用既有本地引用和受支持schema子集回归。
- 初轮新增操作被现有精确白名单正确拒绝；在已获技术批准的范围内追加具名操作守卫和负例后通过，没有删旧操作断言或降低原保护。
- `git diff --check`通过。HTTP10保留原字节行尾，仅增加补充入口，避免无关整文件格式化。
- 本轮未修改backend、前端或生产配置，未本地重跑Maven/真机。完整PR CI必须以最新head记录，历史3a3178d/68808ad结果不替代新head。

原提案/历史交接中“尚未批准、权威docs未改”的描述属于提案阶段；当前以此回执及27号正式规范为准。原merchant-source-hashes.json绑定609153b的来源快照，不作为本轮修改后docs的hash。

## 剩余范围

申请/审核事实和主体去重、成员绑定、主账号核销staff映射、员工停用与在途指派并发守卫、冻结写动作、实际DDL/迁移、生产ID及完整业务验收继续按原Owner交接。不是整个CCR或MER-001 DONE。三项已批决定不再重复索取；未获合并授权，因此不自动合入PR50。
