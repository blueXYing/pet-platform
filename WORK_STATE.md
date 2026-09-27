# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 12.0
UPDATED_AT: 2026-09-27
CURRENT_PHASE: W3_INTEGRATION_REVIEW
CURRENT_STATUS: ATOMIC_BOOKING_CREATE_INTERNAL_REVIEW
VERIFIED_BASELINE: develop fac6b73f344f1b6da3fd996c44d391d2a7068b92（PR85按用户批准合入，合并CI36322764978成功）
NEXT_PHASE: 审阅原子创建内核；补选窗输出、到期关闭与完整结算门禁后才开放C创建
NEXT_PHASE_APPROVED: YES（用户批准合并85并按顺序推进；OTHER/EXOTIC映射已单独批准，新PR合并/生产启用未授权）

## 当前事实

- PR85已合入，保护基础可作为本轮共同基线。原工作目录三个用户改动保持，所有实现仍在独立worktree。
- 本轮落地内部OrderCreationApi与ReservationHoldApi：独立持久Admission、同guard RC事务占位/主单/三类快照/审计/首次回执，一起提交或回滚；独立hold不绑定订单不能commit。10分钟截止值同步存订单与预约。
- 新USER/MER/SERVICE Owner当前事实接口同事务读取，服务价格、资格、宠物归属和快照不取客户端伪造值。成功重放重验当前USER及结果owner，不重消耗容量/重新计算价格。
- 接送完整双claim，主区间只作外包络显示；地址和备注分别加密快照。OTHER只在适用性上匹配EXOTIC，宠物快照仍OTHER，见SSOT§32。
- 同键并发/异参、七阶段故障回滚、commit ACK丢失/回查断连、共享协议与类目行锁、默认关闭均有独立MySQL验收。全量/CI最终结果见实现PR。
- 外部C创建路由仍未开放，六字段可约GET未改变。真实优惠券、生产备注审核、支付、10分钟自动关闭/释放worker仍未实现；有券和缺备注校验依赖的请求503，不能拿内部无券测试冒充完整结算。

## 下一步

1. 审阅本轮原子写入PR，确认默认关闭和未交付边界；合并仍需用户批准。
2. 补原GET选窗ID/kind及基于claim的占用投影，冻结完整C创建输入和错误映射。
3. 按已批规则实现10分钟到期时ORDER关闭与SCH释放的一致性、可靠任务及与支付状态的安全协调；禁止旧“只过期预约不核订单”的直接上线。
4. 补优惠券/备注内容安全/支付必要依赖与真实会话HTTP验收，之后才开放消费者创建；confirm/release/swap与SCH004维护按依赖顺序继续。

## 证据

- [38号契约](docs/04-api/38-Atomic-Booking-Create-Contract-v0.1.md)
- [实施范围与CCR](planning/ccr/CCR-W2-API-001/booking-create-implementation.md)
- [独立QA](planning/issues/wave-3/BOOKING-create-qa/RUN-REPORT.md)
- [本轮集成回执](planning/progress/2026-09-27/booking-create/INTEGRATION.md)
- [上一状态](planning/history/WORK_STATE_BEFORE_20260927_ATOMIC_BOOKING_CREATE.md)