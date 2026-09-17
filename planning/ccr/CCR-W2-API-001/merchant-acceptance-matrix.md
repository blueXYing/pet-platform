# MER-001 候选验收矩阵

状态：**NOT_EXECUTED — 实现验收待契约批准**。此表是验收设计，不是测试PASS报告。
规范与作用域：[主提案](merchant-domain-proposal.md)。本轮静态/回归检查结果另见 `merchant-review-evidence.md`。

| 用例 | 输入/触发 | 必须验证的结果 | 对应AC/现有验收 | 后续证据 |
|---|---|---|---|---|
| MER-S01 | owner访问本人store/staff，ID=9007199254740993 | 保持String精度、实际归属和独立DTO副本 | AC1/3，W2-MER-001 | Java序列化+HTTP+MySQL |
| MER-S02 | 他商家/他店/不存在资源；伪造staffId | 同一404；无数据泄露；前端参数不能授予权限 | AC3，W2-MER-001 | 真实会话HTTP正反例 |
| MER-S03 | 列表请求跨店筛选，pageSize=101；同前缀不同数值ID | 先授权过滤再total；非法分页400；数值稳定排序 | AC1/3，W2-MER-001 | MySQL分页/HTTP |
| MER-S04 | ACTIVE+APPROVED+SIGNED且门店ACTIVE | 五字段资格DTO中acceptsNewOrders=true | AC2，W2-MER-002 | MySQL领域测试；非完整订单 |
| MER-S05 | 商家/门店OFFLINE/FROZEN或明确未审核/未签 | acceptsNewOrders=false；不能据此否决所有存量动作 | AC2，W2-MER-002 | 组合反例；MER-D2存量待定 |
| MER-S06 | 缺审核事实/未知枚举/数据库失败 | 503或对应内部依赖错误，无默认true/正常false | AC1/2，W2-MER-002 | 故障注入 |
| MER-S07 | 主账号同意首次协议，之前工作台DENIED | 经本人身份和审核检查可以签署；唯一同意事实与回执同提交 | AC1/3，签约25号验收1/2 | 真实MySQL+HTTP |
| MER-S08 | accepted=false、hash错误、旧发布版本、未审核 | 分别400/409，未写同意；RESERVED边界依23号 | AC3，签约25号验收 | 协议负例 |
| MER-S09 | 同UUID同参、异参、首次失败后异参 | 原时间/版本重放；异参409；失败绑定不被覆盖 | AC3 | 多连接MySQL幂等 |
| MER-S10 | 两个不同UUID并发同意同一协议 | 同一业务签署事实，acceptedAt不刷新；各命令回执可重放 | AC3 | 并发事务/唯一键 |
| MER-S11 | 同意提交成功ACK丢失 | 原key查询/重放；不新增签约记录、不换key | AC3 | commit ACK故障注入 |
| MER-S12 | 子账号读取协议或代签、owner撤权后重放 | 403/404且无旧敏感回执；真实身份不由body给出 | AC3，签约25号验收4 | 真实会话及撤权并发 |
| MER-S13 | 发布新协议、已有签署商家、旧页面首次签署 | 按MER-D1已批方案验证；候选是保留旧效力、首次旧版本409 | AC1/2 | 产品批准后版本并发 |
| MER-S14 | 子账号禁用/撤销或授权范围改变 | 每次进入与写入重新核验；个人C身份独立 | AC3，W2-AUTH-004 | AUTH/MER集成 |
| MER-S15 | staff手机号与某user相同但没有成员授予 | 不获得登录/核销/员工管理权限 | AC3 | 归属负例 |
| MER-S16 | 停用/离职staff仍有在途指派 | 409，保留原指派；先由订单域完成/改派；检查与新指派竞态不能漏过 | AC3，PRD5.6 | MER-D4解锁后order集成 |
| MER-S17 | 非owner试图管理staff；离职staff启用服务 | 分别403/409；不改变子账号状态 | AC3 | HTTP+MySQL |
| MER-S18 | 运营下线有存量订单商家 | 不因存量拒绝下线；状态与MerchantDisabledEvent同事务 | AC2/4，W2-MER-002/003 | 真实Outbox+MySQL |
| MER-S19 | 下线事务回滚/ACK丢失/重复命令 | 回滚无事件；原key回执可重放；无重复状态转换事件 | AC4，W2-MER-003 | 故障/并发/事件去重 |
| MER-S20 | 消费MerchantDisabledEvent重放 | service/schedule停止新流量；order不取消存量 | AC4，ORD009/010 | 后续跨域E2E；本模块不得报PASS |
| MER-S21 | expectedVersion过期/同请求成功后重放 | 新意图版本冲突409；成功重放不以已消费旧版本再次失败 | AC3 | MySQL/HTTP |
| MER-S22 | 生产装配缺ID宿主/迁移/协议/真实身份 | 保持关闭/拒绝，不注册测试成功Provider | DoD/架构 | 配置与产物检查 |

## 审阅用示例（不是运行中的Mock）

请求：`POST /api/v1/merchant/agreement/consent`，Bearer为真实主账号会话，`X-Request-Id: 30fd66e7-81f5-4f67-a694-0941f3432084`。

```json
{
  "merchantId": "9007199254740993",
  "agreementVersion": "merchant-v1",
  "contentSha256": "<GET返回的64位小写十六进制hash>",
  "accepted": true
}
```

hash占位符不可作为合法请求运行；测试fixture必须从其明确的非生产协议文本计算hash，不使用真实协议冒充测试数据。

成功data：`{"merchantId":"9007199254740993","agreementVersion":"merchant-v1","acceptedAt":"2026-09-17T08:00:00.000Z","signingStatus":"SIGNED"}`。
子账号同一路径：HTTP403、code=COMMON_FORBIDDEN、data=null；不暴露签署者账号或协议回执。
首次签署版本已变：HTTP409、code=COMMON_CONFLICT、data=null；重新读取并阅读后才以新意图提交。
scope外员工读取：HTTP404、code=COMMON_NOT_FOUND、data=null；不存在同样响应。

新单资格样例：`{"merchantId":"9007199254740993","storeId":"9007199254740995","merchantEnabled":false,"storeEnabled":true,"acceptsNewOrders":false}`，只表示商家OFFLINE的新单资格，不包含存量命令授权结论。

## 完整DoD门禁

契约已批且已同步 → 真实MySQL持久化/幂等/归属/Outbox测试 → 实际HTTP鉴权 → 模块架构检查 → 对应Owner跨域验收。人员/订单完整联动、真机/VIS、生产迁移/启用各自保留，不能由上述文档或离线检查代替。
