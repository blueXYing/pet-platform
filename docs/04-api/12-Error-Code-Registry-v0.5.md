# 宠物平台 V1.0 Error Code Registry v0.5

> 该文档定义内部 API 与未来 HTTP/Feign 的稳定错误码。中文 message 可调整，业务代码不得依赖 message 判断。

## 1. 结构

```java
public record ApiError(
    String code,
    String message,
    String traceId,
    Map<String, Object> details
) {}
```

错误码格式：

```text
<DOMAIN>_<SEMANTIC>
```

HTTP 映射只属于 Adapter 层；内部 Java API 通过稳定 code 表达同一业务失败。

## 2. 通用错误

| Code | 含义 | 建议 HTTP |
|---|---|---:|
| COMMON_INVALID_ARGUMENT | 参数不合法 | 400 |
| COMMON_UNAUTHORIZED | 未登录/凭证失效 | 401 |
| COMMON_FORBIDDEN | 无权限 | 403 |
| COMMON_NOT_FOUND | 资源不存在 | 404 |
| COMMON_CONFLICT | 当前状态冲突 | 409 |
| COMMON_RATE_LIMITED | 请求过于频繁 | 429 |
| COMMON_INTERNAL_ERROR | 内部异常 | 500 |
| COMMON_DEPENDENCY_UNAVAILABLE | 依赖服务不可用 | 503 |
| IDEMPOTENCY_KEY_CONFLICT | 相同 requestId 对应不同参数 | 409 |

公共幂等沿用这些既有码，具体边界见[23号补充§5～7](23-公共接口与幂等契约补充-v0.1.md)：已绑定异参使用IDEMPOTENCY_KEY_CONFLICT；有界争锁仍忙使用COMMON_CONFLICT且data:null，不能所有409自动重试；DB/旧结果版本读取不可用使用COMMON_DEPENDENCY_UNAVAILABLE，不重做旧业务。当前会话/权限失败使用COMMON_UNAUTHORIZED/COMMON_FORBIDDEN（或既有防枚举NOT_FOUND），不带旧敏感data。ID/金额/requestId/精度字段非法由HTTP适配器映射COMMON_INVALID_ARGUMENT；S1纯Java检查不构成该适配器或真实鉴权实现。不新增幂等busy/过期专码，不用这些错误替代原业务失败。

## 3. ORDER

| Code | 含义 |
|---|---|
| ORDER_NOT_FOUND | 订单不存在 |
| ORDER_STATE_NOT_ALLOWED | 当前订单状态不允许操作 |
| ORDER_NOT_OWNER | 非订单所属用户 |
| ORDER_MERCHANT_MISMATCH | 商家/门店不匹配 |
| ORDER_ALREADY_PAID | 订单已支付 |
| ORDER_PAYMENT_EXPIRED | 支付窗口已过期 |
| ORDER_CONFIRM_DEADLINE_PASSED | 确认时限已由其他流程处理 |
| ORDER_RESCHEDULE_LIMIT_REACHED | 已达到每单 1 次改期上限 |
| ORDER_RESCHEDULE_AFTER_START | 已到预约开始时间，不允许改期 |
| ORDER_OPERATION_BUSY | 核销/退款关键操作正在竞争 |
| ORDER_REFUND_ALREADY_CREATED | 已存在业务退款单 |
| ORDER_ALREADY_VERIFIED | 订单已核销 |
| ORDER_DISPLAY_STATUS_CONFLICT | 展示态计算发现异常事实组合 |

## 4. SCHEDULE

| Code | 含义 |
|---|---|
| SCHEDULE_NOT_AVAILABLE | 时间不可预约 |
| SCHEDULE_CAPACITY_EXCEEDED | 有效容量不足 |
| SCHEDULE_HOLD_EXPIRED | 临时占用已过期 |
| SCHEDULE_RESERVATION_NOT_FOUND | 预约资源不存在 |
| SCHEDULE_INVALID_TIME_RANGE | 时间区间不合法 |
| SCHEDULE_PICKUP_RETURN_INTERVAL_INVALID | 接送时间不足 120 分钟 |
| SCHEDULE_SWAP_FAILED | 改期原子交换失败，原预约保持 |
| SCHEDULE_NO_QUALIFIED_STAFF | 无符合条件且在岗的服务人员 |

## 5. PAYMENT

| Code | 含义 |
|---|---|
| PAYMENT_ORDER_NOT_FOUND | 支付单不存在 |
| PAYMENT_CREATE_FAILED | 支付下单失败 |
| PAYMENT_CHANNEL_TIMEOUT | 支付渠道超时 |
| PAYMENT_CALLBACK_SIGNATURE_INVALID | 支付回调验签失败 |
| PAYMENT_STATUS_UNKNOWN | 渠道状态未知 |
| PAYMENT_ALREADY_CLOSED | 支付单已关闭 |
| PAYMENT_LATE_SUCCESS_AFTER_TIMEOUT | 本地订单已支付超时关闭，但渠道后续确认支付成功；进入自动退款链路 |

## 6. REFUND

| Code | 含义 |
|---|---|
| REFUND_NOT_ELIGIBLE | 当前订单不可退款 |
| REFUND_APPLICATION_NOT_FOUND | 退款申请不存在 |
| REFUND_APPLICATION_ALREADY_PROCESSED | 退款申请已处理 |
| REFUND_MERCHANT_REASON_REQUIRED | 商家拒绝必须填写原因 |
| REFUND_MERCHANT_DEADLINE_PASSED | 商家处理窗口已过，已由系统接管 |
| REFUND_PARTIAL_NOT_ALLOWED | 当前来源不允许部分退款 |
| REFUND_AMOUNT_INVALID | 退款金额不合法 |
| REFUND_ORDER_ALREADY_EXISTS | 一单已存在业务退款单 |
| REFUND_SOURCE_INVALID_AFTER_VERIFICATION | 当前退款来源依赖“未履约”事实，但核销已先成功使该来源失效；不表示核销后所有退款都被禁止 |
| REFUND_CHANNEL_SUBMIT_FAILED | 渠道退款提交失败 |
| REFUND_CHANNEL_STATUS_UNKNOWN | 渠道退款结果未知 |
| LATE_PAYMENT_AUTO_REFUND_FAILED | 迟到支付自动退款多次技术重试仍未成功，进入一致性异常处理 |

## 7. VERIFICATION

| Code | 含义 |
|---|---|
| VERIFICATION_NOT_ALLOWED | 当前订单不允许核销 |
| VERIFICATION_CODE_INVALID | 核销码无效 |
| VERIFICATION_CODE_EXPIRED | 核销码已过期 |
| VERIFICATION_STORE_MISMATCH | 核销门店不一致 |
| VERIFICATION_STAFF_FORBIDDEN | 当前员工无核销权限 |
| VERIFICATION_RISK_LOCKED | 风控临时锁定 |
| VERIFICATION_BLOCKED_BY_REFUND | 退款单已先创建，禁止核销 |
| VERIFICATION_ALREADY_DONE | 已核销，不可重复核销 |

## 8. AFTERSALE

| Code | 含义 |
|---|---|
| AFTERSALE_NOT_ELIGIBLE | 不满足售后时效/资格 |
| AFTERSALE_NOT_FOUND | 售后单不存在 |
| AFTERSALE_STATE_NOT_ALLOWED | 当前售后状态不允许操作 |
| AFTERSALE_ALREADY_INVALIDATED | 当前售后已失效 |
| AFTERSALE_DECISION_FINAL | 已作最终裁决，不支持复审 |
| AFTERSALE_REFUND_BLOCKED_BY_VERIFICATION | 核销先成功使当前未履约售后失效 |
| AFTERSALE_AMOUNT_INVALID | 裁决退款金额不合法 |

## 9. COUPON

| Code | 含义 |
|---|---|
| COUPON_NOT_FOUND | 券不存在 |
| COUPON_NOT_AVAILABLE | 券不可用 |
| COUPON_SCOPE_MISMATCH | 不满足使用范围 |
| COUPON_THRESHOLD_NOT_MET | 未达到使用门槛 |
| COUPON_ALREADY_FROZEN | 已被其他订单冻结 |
| COUPON_FREEZE_EXPIRED | 冻结已失效 |
| COUPON_ALREADY_USED | 已使用 |
| COUPON_COMPENSATION_PENDING | 权益补偿处理中 |

## 10. POINTS

| Code | 含义 |
|---|---|
| POINTS_ACCOUNT_NOT_FOUND | 积分账户不存在 |
| POINTS_LEDGER_DUPLICATE | 相同业务流水已处理 |
| POINTS_REWARD_INVALID | 奖励积分参数不合法 |
| POINTS_CLAWBACK_INVALID | 扣回参数不合法 |
| POINTS_CLAWBACK_ALREADY_DONE | 本业务扣回已完成 |

## 11. REVIEW

| Code | 含义 |
|---|---|
| REVIEW_NOT_ELIGIBLE | 当前订单无评价资格 |
| REVIEW_NOT_VERIFIED | 未核销订单不可评价 |
| REVIEW_WINDOW_EXPIRED | 核销后 30 天评价窗口已过 |
| REVIEW_ALREADY_EXISTS | 一单已评价 |
| REVIEW_APPEAL_ALREADY_USED | 每条评价最多申诉一次 |
| REVIEW_NOT_FOUND | 评价不存在 |

## 12. MERCHANT / SERVICE

| Code | 含义 |
|---|---|
| MERCHANT_NOT_FOUND | 商家不存在 |
| MERCHANT_DISABLED | 商家不可接受新订单 |
| STORE_NOT_FOUND | 门店不存在 |
| STORE_DISABLED | 门店不可接受新订单 |
| SERVICE_NOT_FOUND | 服务不存在 |
| SERVICE_NOT_BOOKABLE | 服务当前不可预约 |
| SERVICE_FULFILLMENT_NOT_SUPPORTED | 履约方式不支持 |

## 13. THIRD_PARTY

| Code | 含义 |
|---|---|
| THIRD_PARTY_COUPON_NOT_FOUND | 上游券码不存在 |
| THIRD_PARTY_COUPON_NOT_USABLE | 上游券码不可核销 |
| THIRD_PARTY_VERIFY_FAILED | 上游核销失败 |
| THIRD_PARTY_CHANNEL_TIMEOUT | 上游渠道超时 |
| THIRD_PARTY_RESULT_UNKNOWN | 上游结果未知，需查单 |

## 13.1 USER / PET

| Code | 含义 | 建议 HTTP |
|---|---|---:|
| USER_FROZEN | 用户账号冻结/注销，拒绝写入 | 403 |
| PET_NOT_FOUND | 宠物不存在/已删除/归属不符（防枚举统一语义，内部与HTTP同义） | 404 |

## 14. 使用规则

```text
1. 不允许前端根据中文 message 判断业务逻辑。
2. code 一旦发布尽量不改语义。
3. 新错误优先追加，不复用旧 code 表达不同语义。
4. 并发竞争返回 409 类业务冲突，不返回 500。
5. 渠道“未知”与“失败”必须区分。
6. 安全相关错误对外可统一文案，但内部日志保留具体 code。
```


## AUTH-001错误映射同步候选

状态：ACCEPTED_MAPPING / SYNC_CANDIDATE_NOT_IMPLEMENTED，尚未合并/实现；映射已接受D1/D2及取消MFA，无新增全局错误码。具体操作及严格error data:null见OpenAPI的AuthErrorEnvelope。

| 情况 | HTTP / 已有码 | 消费限制 |
|---|---|---|
| 未认证、失效/撤销/跨audience凭据 | 401 COMMON_UNAUTHORIZED | 当前小程序access401至多single-flight刷新一次；旧epoch错误不得清新会话；Web或refresh拒绝清该端凭据 |
| 动作未授予 | 403 COMMON_FORBIDDEN | 不自动注销仍有效身份；清授权缓存并重新查询；无旧敏感data |
| 他人资源/attempt枚举 | 404 COMMON_NOT_FOUND或既定不暴露存在性的401 | 固定策略，不按中文message判断授权 |
| 密码错/未设密码/账号不存在、证明验证失败 | 401 COMMON_UNAUTHORIZED | 统一错误语义，不暴露password_enabled或账号存在性 |
| 参数/未知字段/凭据组合与body不匹配 | 400 COMMON_INVALID_ARGUMENT | 不自动换key重试 |
| 同key异参 | 409 IDEMPOTENCY_KEY_CONFLICT | 不覆盖旧绑定 |
| 原attempt创建秘密响应丢失、只凭UUID重试；CAS或明确并发忙 | 409 COMMON_CONFLICT | 不重发bootstrap秘密；各具体分支依操作说明，不能所有409自动重试 |
| 频控或失败锁定 | 429 COMMON_RATE_LIMITED | 遵守Retry-After/窗口；captcha不是登录后的额外因素 |
| 认证/权限/Provider/旧回执读取依赖不可用 | 503 COMMON_DEPENDENCY_UNAVAILABLE | 失败关闭，不能读缓存allow，不默认签约成功，不因未知发送换key重发 |

200 SmsIntentStatus.UNKNOWN是成功读取“原发送意图未知”的事实，不是发送成功；数据库不可查才503。签约NOT_SIGNED/SIGNING/SIGNED/FAILED/UNKNOWN、准入reasonCodes均是成功资格查询DTO状态，不注册成全局错误码；缺实际签约Provider映射仍BLOCKED。当前登录有效且已获权不得因不存在额外MFA证明返回错误。


AUTH Web切片B1说明：恢复窗口按成功提交前DB锚点+60秒，不保证commit后完整60秒；窗口内缓存/密钥/发布缺失503，截止或当前会话/证明撤销401，不重新签发、不延长会话。密码与不存在账号统一401；登录反机器人锁定/有界资源额度429。无额外MFA错误码。真实代码异常回执始终data:null，不回显请求/SQL/凭据。

## 商家域错误映射（2026-09-17）

27号商家域契约复用现有码：非法字段400 COMMON_INVALID_ARGUMENT；无会话401 COMMON_UNAUTHORIZED；无动作权403 COMMON_FORBIDDEN；范围外/不存在404 COMMON_NOT_FOUND；状态/版本冲突409 COMMON_CONFLICT；异参重放409 IDEMPOTENCY_KEY_CONFLICT；事实或回执不可用503 COMMON_DEPENDENCY_UNAVAILABLE。原MERCHANT_NOT_FOUND/MERCHANT_DISABLED保留原消费语义，不全局替换交易错误。

## 商家申请审核错误映射（S4技术同步候选）

沿用既有码：400 COMMON_INVALID_ARGUMENT；401 COMMON_UNAUTHORIZED；403 COMMON_FORBIDDEN；404 COMMON_NOT_FOUND（不存在/越scope统一）；409 COMMON_CONFLICT（版本/状态/领取/主体重复/材料待核）；同requestId异参409 IDEMPOTENCY_KEY_CONFLICT；真实授权、资产、证据、密钥或存储不可用503 COMMON_DEPENDENCY_UNAVAILABLE。不得将不支持字段/未知证据当已核验，主体冲突不暴露他人申请号、证件或联系方式。新HTTP操作未实现。

## 私有商家材料（S8，CCR-MER-PRIVATE-001 已批准）

| Code | HTTP | 语义 |
|---|---:|---|
| PRIVATE_ASSET_NOT_READY | 409 | 原上传意图尚未收敛为 READY；保留同 requestId 与原文件恢复，不换 key 重传 |
| PRIVATE_ASSET_REJECTED | 422 | 已绑定上传意图最终为 REJECTED/QUARANTINED；同 key 重放仍返回此终态；用户明确重选可清理原本地副本并使用新 UUID，data 为 null |
| PRIVATE_ASSET_GRANT_GONE | 410 | 一次性读取授权已消费、过期或失效，不重放图片回执 |

其余复用 COMMON_INVALID_ARGUMENT、COMMON_UNAUTHORIZED、COMMON_FORBIDDEN、COMMON_NOT_FOUND、COMMON_CONFLICT、IDEMPOTENCY_KEY_CONFLICT 和 COMMON_DEPENDENCY_UNAVAILABLE。不得在错误正文中暴露 token、对象 key、扫描签名、SQL、私有图片或明文原因。
