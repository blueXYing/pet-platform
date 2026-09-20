# MER-001 S4 申请审核契约独立 QA

状态：`CONTRACT_DDL_AND_EVENT_SCHEMA_QA_PASSED / BUSINESS_IMPLEMENTATION_OUT_OF_SCOPE`

基线：`65df1c3cf57d40b591b5930941673c17b648d762`。本轮只审查商家申请审核的 HTTP、内部 API、事件、存储及其相互一致性，不把 fixture、H2 或只建状态表视为真实审核闭环。

## 1. 权威边界

- SSOT §27 与 PRD26 已批准四项规则：门店照片必填 1～6 张；OCR 异常可提交但核验完成前不得通过；`REQUEST_CORRECTION` 投影为 `REJECTED`；`APPROVE` 建档时 merchant 为 `ACTIVE`，经营仍须独立 `SIGNED`。本 QA 不重新请求这些裁决。
- V1 是单运营领取和决定。claim 只处理并发占有，不引入第二审核人、复核人或 MFA。
- `APPROVED` 必须连接真实的同申请、同提交 revision 的 `APPROVE` decision 和对应审核 audit；不能从 `merchant.status`、测试 fixture 或孤立状态字段推断。
- 申请人接口不得返回 `internalNote`、证件明文、原始 OCR 包、审核员账号或长期原件 URL。
- admin 公共授权契约保持资源无关，不能依赖 merchant 专用 DTO。

## 2. 契约审查门禁

| 门禁 | 通过条件 | 当前状态 |
|---|---|---|
| 审批事实链 | application、submitted revision、task、APPROVE decision、decision audit 可按同一申请闭环校验；错 application、错 revision、REJECT decision、错 audit 均失败 | PASS：真实 MySQL 8.4 正反例 |
| 主体去重 | 同一信用代码或身份证不能因并发或 HMAC key-policy 变化获得第二条 ACTIVE claim | PASS：ACTIVE 唯一与 policy 外键负例 |
| 第一切片 HMAC 策略 | 若不实现在线轮换，必须锁定单一持久 key-policy 版本；已有 claim 时配置不一致失败关闭，不以新 digest 继续写入 | PASS：SQL29/Storage29 明确不支持在线轮换，policy 被引用后不可改 |
| 单人领取 | claim/release/manual verification/decision 均以当前 task claimant 为执行前提；不出现 secondApprover | PASS（契约层）：OAS claimant 门禁、task/decision claimant 复合 FK；业务实现待后续 |
| DTO 隔离 | admin-api 只接受通用资源 scope；申请人 DTO 无内部备注或证件明文；运营敏感读取另带 reveal 权限、purpose 和审计 | PASS（契约层）：通用 AdminResourceScope，运营 GET 仅脱敏且无 reveal 参数 |
| 四项已批规则 | 三份契约使用相同状态、材料、OCR 和建档语义，不再出现“待确认”或第五公开状态 | PASS |
| 事件隐私与幂等 | ReviewedEvent 不含 internalNote/证件/手机号/审核员；decision/status 配对，驳回/补正意见必填 | PASS（静态契约）：Event08 与 OAS payload 已同步；Outbox/消费实现待后续 |

## 3. 已发现并要求收敛的问题

1. 原 proposal 的 `AdminActionCheckQuery` 直接接收 `MerchantApplicationScopeFact`，会令 admin-api 依赖 merchant 专用 DTO。Contract30 已改为通用 `AdminResourceScope`，由适配层从申请事实构造。
2. 原 proposal 只以 `(claimType, lookupDigest)` 唯一；HMAC key 改变后同一明文会产生新 digest，单靠 `lookupKeyVersion` 无法防止绕过。SQL29/Storage29 已锁定单一持久 policy，明确在线轮换未实现，配置不一致失败关闭。
3. 原 proposal 未明确 manual verification 只能由当前 claimant 执行。OAS 与 Contract30 已要求 manual verification/release/decision 均为当前 claimant，不出现第二审核人。
4. SQL29 初稿允许 revision 绑定其他申请的 material、evidence 伪造 material hash/错 revision、claim 脱离 VERIFIED evidence、decision 脱离 task revision/claimant、audit 脱离 decision revision/actor，以及 profile 映射其他申请的 reserved merchant。最终 SQL29 均以复合外键收敛。
5. SQL29 初稿把 material 槽位放在 application 级，导致补正 revision 无法替换同槽材料；最终 position 只属于 revision_material，同一申请两个 revision 可使用同类型/位置的不同资产。
6. SQL29 曾把可变 task status 镜像进 application 复合外键，真实 MySQL 证明 AVAILABLE→CLAIMED、CLAIMED→CLOSED 会因非延迟 FK 双向阻塞。最终移除状态镜像，task 状态与 application 状态的跨行一致性明确由锁内服务守卫和读取 join 检查。
7. OAS 初稿把 App schema 误放在 `components.examples`，并把申请编号当 PublicId。最终 App schema 位于 `components.schemas`，`AppApplicationNo` 使用 `SQ+8位日期+8位随机码`，DRAFT 与提交后状态使用 oneOf 绑定编号、决定和时间形状。

以上均为契约/存储收敛，不改变四项已批准产品规则。

## 4. 已执行验证

在独立 MySQL `8.4.9`（`127.0.0.1:33452`）中，每个用例创建随机 `mer001_app_schema_*` 数据库，按顺序执行权威 Schema06、SQL28、SQL29，结束后只删除该随机测试库。`MerchantApplicationSchemaMySqlTest` 的 5 个用例已通过：

1. 合法 DRAFT、revision、material、evidence、claim、review task、decision、audit 关系可建立。
2. APPROVE 只接受同 task claimant、同 submitted revision 的两类本轮 VERIFIED evidence；decision audit 的 revision/actor 必须一致。缺两类 ACTIVE claim 时不能把 application 更新为 APPROVED。
3. 重提 revision 可沿用相同 digest 的旧 ACTIVE claim，但 APPROVE decision 必须绑定新 submitted revision 的 evidence proof；历史 claim/evidence 不改写。
4. 同一 ACTIVE 主体 claim 被唯一约束拒绝；claim digest 必须与其 VERIFIED evidence 一致；policy 被 evidence/claim 引用后不能改 key_version。
5. 同申请两个 revision 可在同 material type/position 使用不同资产；跨申请材料、未附着到 revision 的材料、伪造 hash 和 credential/material 类型错配均被拒绝。
6. APPLICATION profile 的 merchantId 必须等于 application.reservedMerchantId，不能映射到另一申请的商家。

离线 OpenAPI 验证也已通过：

- `python -m unittest test_merchant_application_contract.py`：8/8 PASS，覆盖草稿/提交差异、ID/日期/有效期、manual verification、三类 decision、confirmed、未知权限字段、申请编号状态分支、申请人/运营 DTO 脱敏，以及 ReviewedEvent 决定/状态/意见/隐私正反例。
- 全量离线契约回归：99/99 PASS（contract/auth/merchant/application）。
- `python contract_smoke.py`：PASS，70 operations、49 writes、10 application operations、929 resolved refs、197 string ID properties。
- Maven 定向：`MerchantApplicationSchemaMySqlTest` 5/5 PASS；父 reactor 的 Java/Maven baseline、dependency convergence 和跨 biz 依赖禁令同时通过。

测试直接执行权威 SQL29，不用手写 fixture 复制简化 schema，也不用 H2 替代 MySQL。专用 fixture 只负责隔离数据库的创建、权威脚本执行和安全清理。

## 5. 完成判定

本轮 HTTP/OpenAPI/存储/事件静态契约和 DDL QA 已通过。ReviewedEvent 只有目录和 payload schema，尚无 Outbox 生产者、notification 消费者或真实站内消息验证；不能据此声称申请业务实现、OCR Provider、私有资产上传、通知消费或 MER-001 完整闭环已完成。
