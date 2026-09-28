# 占位与订单原子创建实施同步

状态：APPROVED_SCOPE_IMPLEMENTATION_DETAILS。2026-09-27。

用户批准合并PR85并按既定顺序推进，已批ROC-4定义预分配orderId、hold/ORDER主单同事务。C端最终PRD§5.1.15/16明确接送地址、可空服务备注（200字符/敏感词校验）、10分钟占位和优惠券/支付规则。本轮[38号](../../../docs/04-api/38-Atomic-Booking-Create-Contract-v0.1.md)落实其内部API与[SQL38](../../../docs/03-database/38-Booking-Create-Schema-v0.1.sql)，不改变这些产品规则。

补充物理映射：接送主区间为两完整claim外包络，实际资源仅按两段；接送地址与用户备注各存独立加密输入快照，不塞操作日志；ORDER私有二进制请求键避免旧SQL14字符串排序折叠，成功业务回执与业务/审计同commit。共享协议正文/服务类目只读元数据，避免全平台订单被非必要行锁串行。

唯一新增产品字典裁决已由用户单独批准，见[OTHER/EXOTIC回执](booking-pet-type-mapping-proposal.md)及SSOT§32。它只作用于适用性匹配，原快照保留OTHER。

支付/优惠券Owner、生产备注审核、选窗GET增量和10分钟自动关闭尚未交付，不能以测试替身取代。内部内核可真实写入，但不开放HTTP或启用生产；coupon有值和无真实备注审核的请求失败关闭。后续必须补齐过期/支付安全协调才可打开外部下单。此同步不批准自动迁移、生产开关或新PR合并。
