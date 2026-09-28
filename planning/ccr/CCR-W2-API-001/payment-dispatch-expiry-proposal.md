# 支付发起、短期参数与十分钟到期协调候选

状态：**DRAFT_PENDING_APPROVAL**。2026-09-28。本文仅供审阅，不是已生效契约、Schema 或实现授权；不修改 SSOT、39/40 号正式契约、现有代码或生产开关。实施基线为未合并 PR88 的 `f1682eb`。若批准，先由 PAYMENT/ORDER/SCH/COUPON Owner 将公共接口、持久证据、任务及验收条件同步到正式契约，再分切片实现。

## 给业务审阅者的摘要

订单仍只有原来的十分钟付款期限。到期后，系统必须先弄清原付款编号是否真的付成；查不到、网络超时或渠道只回答“关单请求收到了”时，先保留预约和冻结权益。确认没付款且不会再有旧发起请求到达渠道，才关闭订单并释放资源。若关闭后渠道又确认付款成功，订单保持关闭，按渠道实付金额走既定自动全额原路退款链路。用户重试不生成新的付款编号，也不延长十分钟期限。

本候选还处理微信支付参数的短期有效性：只在可证明仍有效时返回原参数；过期或安全存储丢失时，返回明确的“当前不可调起，请查询原支付结果”，不把本地意图当作已取得支付参数的成功响应。

## 已有基线与缺口

- SSOT §22 规定迟到支付保持原订单关闭、按渠道真实金额全额退款；Scheduler 09 §11 规定十分钟到期后，未向渠道发起可关闭，已发起且未知须查单，UNKNOWN 不释放。
- 23 号 §5/§7 要求 Provider HTTP 不在本地事务闭包中；`createPayment` 的成功仍须带 `wechatPayParameters`，不能新增 202、改成仅受理、或无限重放过期参数。
- 39 号及当前 `OrderExpiryApiImpl` 在同店 guard、同 DataSource、READ_COMMITTED 顶层事务中调用 `BookingPaymentExposureApi.requireNoPayment`；任何 `payment_order` 都拒绝到期关闭。40 号目前只有内部 `prepare`、离线协议构包与已验签通知；`PREPARED/INIT` 本身不是可释放证据。
- `LakalaProtocol` 已有 1111 查单和 1071 关单的构包/验签解析；其关单结果仅是命令 ACK。`PaymentNotificationService` 已有验签 SUCCESS 写入 PAYMENT 成功事实与 Outbox 的受保护路径。尚无真实网络发送、持久发送界线、短期参数安全存储、查单入账或关单协调。
- 本轮不声称真实渠道联调、自动退款执行或消费者发起支付已交付。无正式商户参数、可信微信支付身份或经渠道确认的时间配置时保持入口关闭。

## 候选执行协议

1. **固定身份和截止。** 一张订单继续最多对应原 `payment_order`、原 `paymentNo` 与原 `paymentExpireAt`；同键重试只读原绑定。任何重试、查询、关单和补偿不得另造付款编号或延长原截止。PAYMENT 持久记录发起代际、状态、原截止和必要最小渠道证据，状态转换采用行锁/CAS，并始终服从同店 guard。
2. **先持久化发送界线，再出锁联网。** 发送者在短事务里核对 ORDER 当前可支付、原截止尚有效、可信商户与支付身份，并把原号标为“可能已发送”（见下表）；同事务保存首次发起的原始 `req_time` 及其在**经渠道确认的时区**下对应的 `trade_req_date`。提交后才向渠道发预下单。未知 HTTP 结果保持可能已发送。不得持有店锁或数据库事务做 Provider HTTP。网络发送、回调与到期协调都使用同一付款号和同一店 guard 序列化本地决定。
3. **到期先阻断未来发起。** 处理原 `RESERVATION_HOLD_EXPIRE:{reservationId}:0` 前，PAYMENT 在店锁下设置持久的禁止新发起界线。所有发送者必须在实际网络发出前依据该界线取得有效发送资格；仅有过期 worker lease、内存布尔值、旧查询快照或 `PREPARED` 字段均不能证明请求从未离机。如果一次发送已获资格但仍可能在途，协调不得凭早到的 query not-found 或 close ACK 释放。需要发送完成的可证明边界；崩溃或 HTTP 超时无法证明时保留占用，并进入原号查单/人工核验，直到渠道语义能证明关闭对迟到原请求仍有约束。
4. **网络协调在事务外。** 1111 按原付款号查询时 `trade_req_date` 必须使用首次派发的**原交易请求日期**（`yyyyMMdd`），跨午夜重启后不可填今天；缺少可信原日期时失败关闭。已验签 SUCCESS 就进入统一的权威成功写入路径。状态 UNKNOWN、处理中、响应缺失、验签失败或来源不明时保留占用并重试。查询给出候选终局未付时，可在渠道协议允许的条件下显式关单；关单 ACK 后再查原号，不能把 ACK 当释放许可。1111 的 `BBS00000` 仅表示查到记录，现有协议未给出“查不到等于永久不存在”的保证；任何 not-found 均不得单独释放。
5. **回店锁原子裁决。** 持久化查询/关单的最小可审计证据及其来源，重新锁定 PAYMENT、ORDER 与对应店 guard，核对付款号、发起代际、原截止、没有新的成功/退款/撤销事实、没有未决发送。PAYMENT 公共 API 在 ORDER 关闭事务内对当前行给出可释放证明；ORDER 再运行已有订单 CAS、COUPON Owner 检查、SCH 到期及审计。PAYMENT 不能在事务外给一个稍后可盲用的布尔许可。任何交叉事实冲突、读取失败或当前证据不足均 503/重试，保持预约与权益。
6. **竞争结果。** 回调或已验签查单 SUCCESS 先获得店锁时，写入一次 PAYMENT PAID 与 `PaymentSucceededEvent.v1`；ORDER 到期不得释放。ORDER 先在足够证据下关闭时，后到的渠道 SUCCESS 仍要入账并走 `LatePaymentSucceededAfterTimeoutEvent.v1`，不恢复订单、SCH 或券。SUCCESS 与退款/撤销状态冲突时沿用 `RECONCILIATION_REQUIRED`，不得误履约或重复退款。

| 候选持久事实 | 可用于到期释放吗 | 恢复要求 |
|---|---|---|
| `PREPARED_UNSENT`，且已原子转为 `FENCED_NEVER_SENT`，所有发送入口均受此 fence 约束 | 可以作为“从未发起”的候选证明；仍须当前事务核对订单、券及孤立流水 | 重启后检查持久 fence，不靠进程状态 |
| `MAY_HAVE_SENT` / `SEND_UNKNOWN` / `QUERY_PENDING` | 不可以 | 原号查单；超时或崩溃不自动改为未发送 |
| `CLOSE_ACKED` | 不可以 | 继续原号查单并解决旧发起在途问题 |
| `TERMINAL_UNPAID_PROVEN` | 仅当渠道终局语义、签名和发送界线均已核实 | ORDER 同事务重新读证据；后到 SUCCESS 仍走迟到退款 |
| `PAID` / `RECONCILIATION_REQUIRED` | 不可以 | 分别走成功事件或 Owner 核验 |

这些是**逻辑候选状态**，不是直接增加的数据库列值或已批准迁移。尤其 `MAY_HAVE_SENT` 不能因为租约到期回到 `PREPARED_UNSENT`。如渠道无法保证关单或终局查单会挡住已发送但延迟到达的预下单，则该分支暂不释放，占用异常由对账处理；不能用轮询次数替代证明。

## 查询 SUCCESS 和短期支付参数

已验签查询 SUCCESS 必须核对 app/证书、商户、原 `paymentNo`、WECHAT、订单金额、唯一渠道交易号、有效渠道实付金额与支付时间；渠道时区须经核实并显式配置。查询与通知共用 PAYMENT Owner 的成功状态写入器：同店 guard + 本地事务锁权威 PAYMENT，保存最小回执来源/摘要、不可变成功事实、唯一 `successEventId` 及 `PaymentSucceededEvent.v1` Outbox。通知重放或查询与回调同时到达只能产生一次事件；不一致金额/时间/交易号失败关闭。现有 `PaymentSuccessFactsApi.requireSucceeded` 必须能从查询来源的真实签名证据确认成功，不能只看 `PAID` 字段或一个 SYSTEM DTO。退款/撤销先到的冲突保留核验标记。

`timeout_express` 的候选映射以原十分钟截止减当前可信时间为上限，按渠道允许的**整分钟**设置；不能用当前离线构包缺省的五分钟代替，也不能靠重试重新起算十分钟。由于渠道生效时间可能晚于本地构包，正式映射还需确认渠道计时起点/允许值，并预留传输余量或取得渠道返回的绝对失效时间，保证本地可用期限不超过原订单截止。最后剩余不足一分钟时，候选是**不再发新预下单/不返回新参数**；已取得参数仅在其原本、本地计算的有效期内可返回，且必须再核对原订单当前可支付、未被到期 fence。这个不足一分钟的体验边界须产品批准，不能作为已封板规则直接上线。**本地拒绝返回参数仅是停止向用户提供调起能力，不证明渠道已关闭或不能再收到支付；到期释放仍按上节渠道终局与发送界线核验。**

微信支付参数是短期敏感能力：与长期幂等绑定/最小业务回执分开、加密存储并限定读取范围；保存原参数的本地有效期和版本，过期即拒绝返回，不在长期绑定表、日志、事件或普通响应缓存中留明文。返回时再核对当前可信 USER、订单归属与可支付事实。若参数丢失、过期或原渠道结果未知，优先用原 `paymentNo` 查询；在**未证实渠道允许同号重提**以前，不自动再次预下单，返回明确不可调起/原支付结果待查询，不返回 202 或伪成功，也不新建支付意图。原 `createPayment` 既有成功形状（含有效 `wechatPayParameters`）不变，错误/查询展示映射需另审。

首次发起预下单所需用户 openid 必须由 USER 基于**当次可信 USER 登录**与小程序 AppID 绑定事实提供，并与服务端 `sub_appid`、订单用户一致。USER 已有 `user_auth_identity` 的 `WECHAT_MINI`、`app_id`、`open_id` 持久事实与 Mapper，但尚无 PAYMENT 可调用的公共当前事实 API；由 USER Owner 增加受控 getter，不允许 PAYMENT 跨表读 Mapper。客户端不能提交 openid 来决定支付身份；首次发起时缺失、多重有效绑定、AppID 或当前身份不一致均失败关闭。后续 SYSTEM 查单/关单按 PAYMENT 原已持久绑定的商户、付款号、首次 `trade_req_date` 和支付身份工作，不要求用户会话仍有效，也不重新选择另一个 openid。

## 候选公共接口与 Owner 分工

| 边界 | 候选接口/职责 | 条件 |
|---|---|---|
| ORDER → PAYMENT | `PaymentExpiryCoordinationApi.reconcileForExpiry(orderId, reservationId, expectedDeadline, context)` | SYSTEM 命令；调用时无外层事务，负责 fence、查单、关单、重查与持久证据；回执仅提示 READY/PENDING/SUCCESS_OBSERVED，不能代替最终事务证明 |
| ORDER → PAYMENT | `BookingPaymentExposureApi.requireSafeToExpire(orderId, storeId, expectedDeadline, context)` | 在 ORDER 原到期事务、同店 guard/同 DataSource 下锁 PAYMENT 当前事实；无记录继续原有严格证明，有记录只接受已批准的无发送或终局未付证据；孤立流水/历史未知仍拒绝 |
| PAYMENT → ORDER | 既有 `OrderPaymentFactsApi`/`PaymentSucceededEvent.v1` 方向 | 查询 SUCCESS 经 PAYMENT Owner 同一事实写入路径，再由 ORDER 消费；不得由过期任务直接给 ORDER 写 PAID |
| PAYMENT → USER | 待定义可信微信支付身份当前事实查询 | 只用于首次 USER 发起：USER Owner 从现有 `user_auth_identity` 提供受控公共 getter，按已认证用户、AppID 和绑定代际取唯一有效 openid；缺失、多绑定或权限失败关闭。SYSTEM 对账用 PAYMENT 原持久绑定 |
| PAYMENT → 渠道 | 独立 Provider adapter | 原号预下单/查单/关单，验签原始响应；无店锁与 DB 事务跨网络 |

接口名、DTO、错误码和物理表尚待 Owner 审批。PAYMENT `biz` 不直接依赖 ORDER/SCH/USER 的 `biz`、Repository、Mapper 或实体；ORDER 不直接访问 PAYMENT 表或渠道。既有到期 task key 与原 generation 保留。`PAYMENT_EXPIRE:{paymentId}` 与 `RESERVATION_HOLD_EXPIRE:{reservationId}:0` 的职责和重试/DEAD 对账边界需同步 09/39 号，不能让两任务各自释放一次。

## 必需验收

| 场景 | 最低断言与可复用夹具 |
|---|---|
| 从未发送、到期 fence | `PaymentFoundationAcceptanceTest` 的 `Fixture`（约 584 行）与现有到期实例（约 240 行）；原 paymentNo 不再可发，ORDER/SCH 一次关闭，重放 NOOP |
| 发起 HTTP 超时、在途预下单、query not-found 先于旧请求、关单仅 ACK | 模拟阻塞 Provider 与重启；全部保留占用/原号，不能因 lease、ACK 或单次 not-found 释放；覆盖 PAY-004/005/006、FLT-003 |
| 查询 SUCCESS 与回调/到期竞争 | 扩展 `PaymentFoundationAcceptanceTest` 回调与到期双连接并发（约 299 行）、通知幂等与迟到路径（约 60/240 行）；唯一 PAYMENT 成功事件。正常竞胜为 ORDER `PENDING_CONFIRM/PAID` + SCH `CONFIRMED`；到期先关闭后迟到付款可为 ORDER `CANCELED/PAID` + SCH `EXPIRED`，只发迟到退款意图且不恢复预约 |
| 终局 CLOSE/FAIL、迟到 SUCCESS、退款/撤销冲突 | 已验签终局与无在途条件才关闭；后到 SUCCESS 保持关闭并只发一次迟到事件；复用 370 行附近 `RECONCILIATION_REQUIRED` 夹具 |
| 原号/金额/签名与短期参数 | 复用 `LakalaProtocolTest` 约 129–178 行的查单/关单签名夹具；测不符商户、付款号、金额、时区、验签失败、参数过期/丢失、最后不足一分钟、同键重放、openid 缺失/多绑定；首次请求日期持久化且跨午夜查询仍用原 `trade_req_date`；不得产生第二付款编号或有效期延长 |
| 故障恢复和模块边界 | DB 提交 ACK 丢失、Outbox 重放、任务 DEAD、跨数据源失败关闭；断言短期参数无明文、原始包/密钥不入日志或事件；执行架构检查 |

PR88 已有基础测试不构成本方案通过。实现后按 14/15/16 号测试矩阵和 Definition of Done 跑相关真实 MySQL、并发和架构测试；实际拉卡拉联调须在正式参数与时间语义确认后另验。

## 待批准或外部核实

1. **产品裁决：** 最后不足一分钟不再创建新微信支付参数的体验、已有参数本地失效后的提示和查询方式；确认不改变原十分钟关单规则。
2. **Contract/Schema 批准：** 39/40 号新增 PAYMENT 发送 fence、终局证据、公共接口、最小回执来源和短期加密参数存储；23 号同步 `createPayment` 原响应/失效/重放映射；09 号同步 PAYMENT_EXPIRE 与原预约到期任务职责。重大契约变化按 `WORK_EXECUTION_PROTOCOL.md` §4 走人工批准，Contract 缺失按 `AGENTS.md` 走 CCR。本稿不写实施 SQL。
3. **渠道事实核实：** `timeout_express` 精确单位、允许范围、计时起点、绝对失效时间；1111 查询必须提供原 `trade_req_date`，且目前未证实 not-found 等于永久不存在；须再核实 FAIL/CLOSE 是否为可证明的终局、1071 关单对已发但迟到的原号是否有约束、原号重提是否安全。未证实的分支一律不释放或重发。
4. **依赖与上线门禁：** USER 当前唯一 openid 公共映射、真实商户/小程序绑定及渠道时区，短期参数加密密钥管理、真实渠道发送/退款实现、退款消费链路、生产迁移与开关。PR88 合并和生产启用各需独立批准；不能以本草案当批准回执。
