# AUTH-001 正反样例与验收提案

**PROPOSED / PENDING_REVIEW · AUTH-001-draft-v2**。所有ID为虚构String、所有秘密为不可用占位符；没有真实用户/令牌/手机号。JSON是协议设计例子，不是已批准公共Mock，也不证明服务已运行。以下响应展示完整ApiResponse；省略的HTTP头按[会话](sessions.md)定义。

## 1. 微信已验证、手机号未完成：不签发业务会话

POST `/api/v1/c/auth/wechat-login`，X-Request-Id=`aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa`，X-Auth-Attempt=`EXAMPLE_ONLY_NOT_A_CREDENTIAL`。

```json
{"attemptId":"9007199254740993","wechatCode":"EXAMPLE_ONLY_WECHAT_CODE"}
```

200；nextStep仅认证流程进度。公开浏览可继续，不能调用预约/商家写API。

```json
{"code":"SUCCESS","message":"请完成手机号验证","data":{"attemptId":"9007199254740993","nextStep":"VERIFY_PHONE","expiresAt":"2026-09-14T15:05:00.000+08:00"},"traceId":"example-trace-01"}
```

同一用户phoneCode真实证明完成后才发SessionGrant；若手机号绑定其他账号，409 COMMON_CONFLICT、data=null、联系客服，不返回另一账号。首次验证码/微信注册不设置随机密码。

## 2. 小程序会话示例

仅代表“所有真实证明均已完成”的会话设计假设，与签约无关；签约成功事实不在此样例造出。grant从被验证的attempt取得，token不允许用于测试环境之外。

```json
{"code":"SUCCESS","message":"成功","data":{"sessionId":"9007199254741001","userId":"9007199254741002","audience":"MINIAPP","tokenType":"Bearer","accessToken":"EXAMPLE_ONLY_ACCESS_NOT_VALID","expiresAt":"2026-09-14T15:15:00.000+08:00","refreshToken":"EXAMPLE_ONLY_REFRESH_NOT_VALID","refreshExpiresAt":"2026-10-14T15:00:00.000+08:00"},"traceId":"example-trace-02"}
```

拿此MINIAPP凭据访问admin，401；客户端切workspace=admin也不能改变audience。token丢响应时须原attempt秘密+原key恢复；过期或撤销后不发新token、不重建账号。

## 3. 签约未知：完整工作台不放行

下例只验证UNKNOWN且**没有独立确认的存量例外**时拒绝入口的协议分支，不表示Provider已经选定。merchant/store归属已独立验证；若存量允许动作已证实则用LIMITED及相应actions/nextSteps；若查询本身不可用则503/data=null，而不是下面200已知“未知”事实。

```json
{"code":"SUCCESS","message":"签约状态待确认","data":{"merchantId":"2001","storeId":"3001","membershipKind":"OWNER","admission":"DENIED","checkedAt":"2026-09-14T15:00:00.000+08:00","authzVersion":"example-v1","facts":{"application":{"status":"APPROVED"},"signing":{"status":"UNKNOWN"},"storeStatus":"ACTIVE","merchantStatus":"ACTIVE","staffEnabled":null},"allowedActions":[],"reasonCodes":["SIGNING_UNKNOWN"],"nextSteps":[{"type":"CONTACT_SUPPORT"}]},"traceId":"example-trace-03"}
```

不能从`provider_merchant_no`非空把UNKNOWN改为SIGNED。真实SIGNED/FAILED/待签响应样例须OD002 Provider映射后补成获批Mock；这里只保留语义矩阵和输入要求。

## 4. 冻结与下线的独立资格测试表

| 虚构输入（非真实签约事实） | 应核行为 | 状态 |
|---|---|---|
| 归属有效、已验证可继续存量关系、商家OFFLINE、存在订单 | LIMITED，已有订单履约/退款/售后资源动作不因新单资格失败被抹掉 | 已定规则，具体业务Mock待字段同步 |
| 商家OFFLINE、新预约请求 | 拒绝新单 | 已定规则 |
| 商家FROZEN、历史订单/待处理售后读取/处罚申诉 | LIMITED，保持明确可用动作 | 已定读取及申诉规则 |
| 商家FROZEN、核销/售后补证/退款处理写入 | 不生成生产允许或拒绝断言 | B-FROZEN-WRITE，产品边界待核 |
| 原子账号已停用、伪造历史staffId | 拒绝，保留历史审计但不保留该主体授权 | 已定角色/启用约束 |
| 当前用户不属于目标store、深链附workspace=merchant | 403或固定防枚举404，不能返回Membership/旧data | 已定越权拒绝 |

## 5. Web普通主认证完成

按SSOT§25及[24号补充](../../../docs/01-prd/24-取消MFA人工裁决补充-v1.0.md)，取消额外MFA。所有角色的账号密码主认证完成后直接签发Web SessionGrant；没有额外因素输入、投递或绑定。反机器人图形验证码仅在密码登录失败阈值触发时按原规则执行，不成为高权限动作的追加认证。

Web SessionGrant示例，无refreshToken/userId。新登录只有账号密码完整验证成功才使旧Web会话失效，避免攻击者只提交账号就踢下线。

```json
{"code":"SUCCESS","message":"成功","data":{"sessionId":"4101","operatorId":"5001","audience":"ADMIN_WEB","tokenType":"Bearer","accessToken":"EXAMPLE_ONLY_WEB_ACCESS_NOT_VALID","expiresAt":"2026-09-14T15:30:00.000+08:00"},"traceId":"example-trace-web-grant"}
```

Web CurrentSession示例：

```json
{"code":"SUCCESS","message":"成功","data":{"sessionId":"4101","operatorId":"5001","audience":"ADMIN_WEB","expiresAt":"2026-09-14T15:30:00.000+08:00","idleExpiresAt":"2026-09-14T15:30:00.000+08:00","authzVersion":"example-v7"},"traceId":"example-trace-web-session"}
```

| Web触发 | 预期 |
|---|---|
| 任意角色账号密码完整验证后重复登录 | 新generation签发并踢旧，无角色专属第二认证步骤 |
| 高权限账号仅提交账号、密码未验证通过 | 不签业务Bearer、不踢旧；不能以“取消额外因素”为由省略密码验证 |
| 15:10真实交互提交activity新key | idleExpiresAt延至15:40；此为新的交互意图 |
| 15:15重放15:10 activity原key | 回原15:40，不再延至15:45；后台轮询也不延长 |
| idleExpiresAt已过后activity | 401，不能续命 |
| 有效会话后来获高权限角色或extraAction | 更新authzVersion、失效旧授权缓存；后续动作重新核权限/范围/业务资格，无额外认证要求 |
| 有效登录且获权的超管执行高风险动作 | 按用途/同人确认/业务资格/审计执行，不因未提供第二因素而拒绝 |
| 已登录但无动作权或跨范围 | 仍403或既定防枚举404，业务无变化并审计；与FLT-020取消额外因素前置后的定义一致 |
| 客户端发送已删除的额外认证字段或访问旧额外认证路径 | 不在当前契约：字段按未知参数400，路径不提供；不能借旧证明绕过RBAC |

## 6. 真实权限与显示名无关

```json
{"code":"SUCCESS","message":"成功","data":{"operatorId":"5001","authzVersion":"example-v7","checkedAt":"2026-09-14T15:00:00.000+08:00","roles":[{"roleId":"5002","roleCode":"FINANCE_READER","displayName":"财务只读"}],"dataScope":{"mode":"MERCHANT","cityCodes":[],"merchantIds":["2001"]},"actionCodes":["refund.read"]},"traceId":"example-trace-06"}
```

refund.retry应403；仅把displayName改成“平台超级管理员”仍403。若后续经合法授权显式赋予已批准refund.retry，可在2001范围内按当前退款资格执行，无第二账号；对2002拒绝。超管执行未批准withdrawal动作同样拒绝，未知actionCode不在目录。

单账号显式赋权请求示例（全局FINANCE_READER模板不变）：

```json
{"roleIds":["5002"],"extraActionCodes":["refund.retry"],"dataScope":{"mode":"MERCHANT","cityCodes":[],"merchantIds":["2001"]},"expectedVersion":"7","reason":"赋予已批准退款重试职责","confirmed":true}
```

仅当前有效超管或满足ALL/可转授/目标保护的管理者可提交；生效后只改变5001账号。移除extraAction若另一角色仍授同码，最终权限仍有该动作，响应/审计如实说明，不能谎称撤净。

账号所有actionCodes共用账号dataScope；不能把某个角色的ALL和另一个局部动作拼成全平台执行。列表请求merchantId=2002过滤交集为空，不可返回2002 total。订单详情他人资源固定404策略。

## 7. 撤权后旧成功回执不可读

已有业务成功，原requestId与参数不变；当前身份有效但refund.retry授权已撤销：

```json
{"code":"COMMON_FORBIDDEN","message":"无权限","data":null,"traceId":"example-trace-07"}
```

HTTP403；不返回旧退款金额/渠道敏感数据或旧actions。若身份已撤销用401 COMMON_UNAUTHORIZED；如果权限事实不可查用503 COMMON_DEPENDENCY_UNAVAILABLE。重放不是再次执行业务，不能二次退款。

## 8. 最终检查时点并发矩阵

| 时间线 | 预期 | 需要的证据 |
|---|---|---|
| A等待业务锁；B撤权commit；A获锁后最终检查 | 拒绝，无副作用 | 强一致权限读取，不沿用RR旧快照 |
| A最终检查通过；B撤权commit；A短本地事务commit | 允许该次已检查业务完成，审计记录旧版本/检查点 | 不宣称撤权追溯取消 |
| A死锁回滚；B撤权；A重试 | 重新检查并拒绝，旧allow失效 | 屏障控制重试 |
| A返回数据前，B撤权；A READ_RESULT检查 | 401/403/404，无旧data | 对敏感/回执单独测 |
| 权限缓存允许；权威查询失败 | 503，无执行 | 缓存不能降级放行 |
| 可靠退款任务已受理；原操作员后撤权 | 依既有SYSTEM任务和退款承诺继续，不创造新用户指令 | 与交易Owner核事实/任务契约 |
| 前端切店/重登后旧响应晚到 | 成功及错误均废弃，不污染新scope、不清新token | epoch/身份标签测试 |
| 两轮权限读取间撤动作/扩范围，或员工换店 | 版本变化重取，不能合成从未同时存在的动作与范围；3次/2秒不稳503 | Owner原子快照/单调版本/无ABA |
| 只授order.export无敏感明文条件 | 默认脱敏且水印，地址明文营销导出仍拒绝 | 原PRD逐字段条件，不凭export越权 |

## 9. 认证幂等反例

| 场景 | 预期 |
|---|---|
| attempt创建响应丢失、只持UUID重试 | 不返回秘密、不新建第二attempt，明确重新开始；新attempt是用户明确新认证意图且无业务账号副作用 |
| 持正确attempt秘密重试已签发结果（60秒内） | 同key同参且当前会话有效，只恢复相同grant；不会新增会话/用户 |
| 知道他人attemptId/requestId但无秘密 | 拒绝，不能读取grant或判断手机号归属 |
| 60秒后/登出后重放旧SESSION_GRANT | 401，不复活会话，保留墓碑，不重执行；PASSWORD_RESET最小updated回执仍按attempt有效期规则 |
| 同refresh不同key并发 | 只一次轮换，旧token复用按family撤销策略，客户端应single-flight |
| logout先提交、refresh后CAS | 刷新失败，无可用新token |
| refresh先提交、logout后提交 | 退出撤销同family新旧全部token |
| 禁用/密码重置与在途登录签发交错 | 当前generation/账号状态最终核验，旧generation不可签发有效会话 |
| SMS发送超时 | UNKNOWN/503、查原意图，不再次发码、不默认送达 |
| 验证码过期后原key重放 | 不延长有效期、不再投递 |
| 密码重置提交前失败 | 密码及证明消费一并回滚或可恢复，无半提交 |
| 密码重置成功响应丢失 | 原尝试可取最小updated事实，不重复改密码、不重新开放reset能力 |
| 同key异参phone/newPassword | 409 IDEMPOTENCY_KEY_CONFLICT，不覆盖原绑定 |
| LOGIN challenge跨用于PASSWORD_RESET、跨attempt或手机号 | 拒绝，无密码更新；证明目的/主体不能仅靠客户端传值 |

查询原SMS发送意图返回200已知UNKNOWN示例；数据库读取失败则503/data=null，不能把错误假装为此状态。

```json
{"code":"SUCCESS","message":"发送结果待确认","data":{"requestId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","status":"UNKNOWN","nextAction":"QUERY_SAME_REQUEST"},"traceId":"example-trace-sms-unknown"}
```

只有真实Provider明确REJECTED才可nextAction=START_NEW_ATTEMPT；ACCEPTED必须附challengeId/expiresAt/resendAfterAt，不返回OTP。

## 10. 原测试ID与执行界限

W2-AUTH-001：登录/会话/过期/撤销与规范样例；W2-AUTH-002：深链/跨身份/旧响应/失败关闭；W2-AUTH-003：六角色/数据范围/单运营/PERM-001～006/WEB-002，并按24号补充验证无额外因素前置仍严格授权；W2-AUTH-004：每次准入、冻结下线及签约独立门禁。MINI-002～004必须在真实微信平台接入后另验，浏览器壳测试不互代。交易P0由真实订单/退款服务另举证，本文没有运行这些测试。

已知测试修订影响：e2e/contract_smoke.py当前要求operation.security非空，会错拒匿名security:[]；e2e/test_contract_smoke.py及S1ContractMappingTest固定16操作。获批同步新操作时由Owner改覆盖与匿名白名单/继承语义。本次不改这些代码，也不拿现有CI绿灯证明新接口。
