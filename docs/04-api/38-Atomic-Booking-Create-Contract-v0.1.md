# 预约占位与订单原子创建内核 v0.1

状态：IMPLEMENTATION_IN_PROGRESS / INTERNAL_ONLY。2026-09-27。依据已批[36号](36-Reservation-Order-Protection-Contract-v0.1.md)、[37号](37-Reservation-Protection-Foundation-Contract-v0.1.md)、[23号](23-公共接口与幂等契约补充-v0.1.md)及用户“合并完成之后按照顺序推进”。本轮只交真实内部写入内核，默认`pet.order.creation.enabled=false`；不开放C创建路由、不改变现有六字段可约GET，不宣称完整结算已上线。

## 1. 原始PRD缺口及阶段边界

C端最终PRD §5.1.15/16要求：接送必填省市区和详细服务地址，到店不填；服务备注可空，超过200字符/敏感词拦截；10分钟临时占位，有券冻结，付款另行发起。SQL06/旧HTTP遗漏服务地址和订单备注，07旧图将payment放在create流程，不能据此静默丢字段或在数据库事务内调用渠道。

本轮补`serviceAddress`内部输入及加密快照，内部选择字段按34/36号。无券且无备注路径使用真实Owner事实；有券时COUPON尚无实现，503且不创建业务行；非空备注缺真实内容校验时503，不用假白名单当生产审核。备注检查只能在Admission前进行无写副作用检查，不在持有业务锁时调用外部Provider。地址/备注保护依赖未配置时相应请求503，规范参数仅保存稳定HMAC等值token，不落明文。

十分钟到期时间在订单/预约同事务持久化，但本轮**未实现自动关闭/释放worker**，不能开放外部下单。后续必须接齐选窗输出、超时关闭、支付状态安全协调和需要的优惠券/备注服务，再逐项允许HTTP；不自动启用过时09号“无需查询订单直接过期”逻辑，因为已批共同保护要求不能留下在途订单与已释放预约不一致。

## 2. 内部命令与可信事实

Java骨架为`OrderCreationApi.create(CreateOrderCommand)`及`ReservationHoldApi.hold(HoldCommand)`；跨域字段见各自DTO。所有ID为正Long十进制String，USER来自可信CommandContext而非body。创建只接受USER；检查requestId的既有512 UTF-8字节规则，后续HTTP另强制UUID。traceId最多128 UTF-8字节，不参与幂等。

IN_STORE：实际分钟区间、可选selectedGeneralWindowId，禁止接送两开始值/方向ID和服务地址；区间长度严格等于SERVICE当前服务时长。PICKUP_DELIVERY：两个不同方向原ID及用户选择的pickupStart/returnStart，禁止到店appointmentStart/end和GENERAL ID；两开始值必须等于原窗且满足120分钟。服务履约类型由SERVICE确定，客户端不得改变。到店无ID只能唯一原窗完整容纳；显式ID仍不能绕过同kind OPEN窗重叠坏事实。

接送主区间用于订单/预约主字段展示：`[pickup.start,max(pickup.end,return.end))`。实际容量只占两条完整claim，不占两个方向之间空档，也不新增双窗不得重叠的限制。

remark缺省/null表示无备注；有值须非空白、最多200 Unicode字符，保护器与内容校验通过。serviceAddress接送必填非空白文本（省市区＋详细地址），不臆造联系人/地理围栏要求；单项受保护原文不得超64KiB UTF-8技术上限。拒绝孤立surrogate；不trim/大小写折叠。到店服务地址必须null。可空内部record字段缺省/null按本契约等价，无其他隐式默认。请求中没有价格、商家名、宠物快照或最终员工，不能信任客户端提供这些值。

同guard下新增USER当前可用/宠物归属、MER当前经营资格和名称地址、SERVICE当前可售/价格/时长/履约类型快照公共API；各Owner只读本域持久层、同DataSource当前读，不沿用展示REQUIRES_NEW快照。MER读取为消费者创建用途，不要求顾客是商家OWNER。创建执行校验当前USER可用、pet ACTIVE且归本人、merchant/store ACTIVE且真实APPROVED+SIGNED、service ACTIVE且归店。服务适用宠物类型按现有Owner字典语义核对，不自行猜别名。

用户已批准[宠物类型衔接](../../planning/ccr/CCR-W2-API-001/booking-pet-type-mapping-proposal.md)：仅在下单适用性校验将USER OTHER对应SERVICE EXOTIC，DOG/CAT精确匹配，ALL覆盖全部。各Owner及订单宠物快照仍保留原值；未知枚举503，不用别名推断扩大准入。

## 3. 幂等与原子写入

SQL38的ORDER私有request表使用VARBINARY(1024)唯一完整键，逻辑tuple为固定`order.create/USER/actorId/CONSUMER/requestId`，每字段按UTF-8长度前缀无歧义编码，非字符折叠/非仅摘要定位。绑定保存canonical版本、SHA256标识和规范字节，等值以原字节复核。规范只包含用户意图字段和敏感值HMAC，不纳入实时价格/生成ID/技术source/trace；同用户同key换目标仍冲突。

不得存在业务外层事务再进行Admission。先当前USER初检、静态字段和受保护canonical；Admission独立短RC事务绑定RESERVED。Execution独立顶层可写RC事务锁绑定，设2秒锁等待预算。任何失败保留绑定但回滚业务，同key异参409；锁争用忙409，无法确认/读取依赖503。commit ACK未知按原key查询/取锁有限重试，绝不换requestId重新创建。

首次执行顺序：锁绑定→guard→USER/MER/SERVICE当前事实与业务资格→预分配orderId/orderNo→SCH在插入前完整容量证明→写TEMP_LOCKED预约+1/2 claim+SCH审计→ORDER写PENDING_PAYMENT/INIT/UNVERIFIED主单、服务/宠物/受保护输入快照、创建审计及ORDER_STAGE初始日志→标首次回执SUCCEEDED→校验双向关系后共同提交。所有实体ID由既有SnowflakeIdGenerator生成，金额BigDecimal且无舍入。service/merchant/store及必要pet字段由真实Owner返回，不读取别域表。

成功重放按23§5.4只再次检查当前USER动作可用、原结果order.user_id归属及绑定参数等值，返回第一次最小回执；不重跑已消耗的容量、宠物当前状态、服务当前可售或优惠资格。价格变化/服务下架不重算原回执。重放created=false/replayed=true；外部将来映射200，首次created=true映射201，内部元数据不成为业务响应字段。当前订单状态需独立查询，不把首次PENDING_PAYMENT回执当未来支付许可。

## 4. SCH hold和提交前保护

hold要求已有同库guard事务，核对context USER==userId、全部ID/区间/原窗/商户关系。先proof，后插入；不得插入未绑定父行后再用现有proof把它误判不一致。共同解析候选原窗，保证证明和落库同一组claim。重复orderId由既有唯一约束及当前读拒绝409，不承担订单级请求重放。

Clock UTC当前时间截到毫秒后加10分钟，写入lock_expire_at并返回同值；订单payment_expire_at必须等于它。lock_token为内部随机token，不替代Snowflake实体ID或幂等键。capacity_snapshot/qualified_staff_count_snapshot仅保存真实证明相关诊断计数，不用配置容量冒充真实人数，也不作为后续权威容量。

hold注册同事务beforeCommit校验，经真实ORDER.getByReservations验证返回唯一对应orderId/reservationId/user/merchant/store/service/fulfillment。缺主单、错双向关系、读取失败或未持guard使事务回滚，独立hold不得提交。不得放宽37号提交后PENDING_BIND错误；正常ORDER在同事务补齐主单后才允许通过。回调不得跨域读表或发网络调用。

## 5. 持久化与验收边界

[SQL38](../03-database/38-Booking-Create-Schema-v0.1.sql)仅隔离库验证：ORDER绑定/创建审计/加密输入快照、SCH HOLD审计及订单支付截止时间。order_status_log不是幂等记录，用户备注不塞审计remark。业务快照、审计、绑定成功和hold同事务；任何一处失败全部回滚。无新增集成事件或声称已登记可执行过期任务。

验收重点为真实MySQL双用户抢位、同key并发/异参、hold后逐阶段失败回滚、独立hold提交拒绝、成功重放不重占用/不重算价、接送双claim与用户地址加密、真实Owner当前事实。支付/优惠券实际使用、HTTP鉴权与页面、自动过期、确认/释放/改期、生产迁移未执行时明确NOT_IMPLEMENTED/NOT_EXECUTED。待这些配套接入前两个创建开关均保持关闭，不把内部验证当真实消费者结算上线。
