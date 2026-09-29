# 商家首轮确认/拒单及退款实现记录

授权：用户2026-09-29“批准以上三项，按推荐方案继续”，对应CCR D1/D2/D3。分支`codex/merchant-order-actions-20260929`，基于PR91合并`1eb96ac`，不包含原用户目录未提交内容。提交PR供审阅，不合并或生产启用。

## 已实现范围

- 主账号USER当前会话及MERCHANT owner关系复核，round0两条HTTP命令，真实业务入口默认关闭。OFFLINE已有履约可处理，员工及FROZEN未决写入保持未接通。
- ORDER独立幂等占号、精确输入绑定与加密首成功回执；锁内DB时间严格早于paidAt+30分钟。已成功请求重新鉴权后返回原回执，不因异步退款或超时改变结果。
- 确认写MERCHANT/PENDING_SERVICE；拒单写CANCELED/MERCHANT_REJECT_ORDER并同事务创建实付全额退款、来源证明、任务、Outbox、日志和回执。失败不留下半笔业务，保留原请求绑定。
- PAYMENT/REFUND区分正常拒单与迟到付款，保持原退款号及MAY_HAVE_SENT/UNKNOWN只查不重发。来源字段兼容历史迟到行，独立任务类型避免误领取；任务执行再核来源。
- REFUND真实成功证明通过后，ORDER金额投影、消费claim及SCH释放同事务提交。此前预约保持CONFIRMED；保留claim历史，以reservation状态决定实际占用。原支付事件与自动任务重放不能复活已拒单订单。
- SSOT §34、07/10/11/12、Event08、Scheduler09、Test14、45号Contract/Schema同步。跨Owner只走公共API，SQL在本Owner MyBatis XML。

## 验收证据与边界

本地Java 21、MySQL 8.4隔离实例（localhost:23391）和离线签名付款回执，无真实渠道调用。[local-tests.json](local-tests.json)记录63项主验收/回归（含15项商家MySQL、3项HTTP、4项商家装配、21项自动确认、16项迟到退款和4项原装配），全部零失败/错误/跳过。随后增加的确认/拒单竞争、等锁跨截止、历史回填及增强来源校验，最终复验另记[local-final-tests.json](local-final-tests.json)。完整Java clean verify、前端构建与其余CI以当前PR检查页和backend-test-reports附件为准，不以PR91旧报告替代。

架构模块依赖、MyBatis SQL和DisplayOrderStatus门禁通过；18项架构负例及115项契约回归通过；contract smoke解析90个操作、57个写操作requestId、1032个引用和214个String ID属性。这些静态结果不计作真实HTTP或微信真机验收。

测试中先后修正了共享测试终端号超长、测试事件误用Outbox行ID/本地时区以及新增测试事务未显式设RC的问题；最终复验结果而非早期失败日志为交付依据。业务防御补充包括支付退款后原事件重放、退款任务类型与来源一致性及最终投影中的付款证明逐项匹配。

隔离MySQL仅为加速Windows测试将innodb_flush_log_at_trx_commit设为2。提交ACK丢失、真实并发与SQL故障注入均已覆盖；不声称验证了断电/存储设备故障耐久性。Schema只在临时测试库应用，未生产执行。

## 保留限制

本切片不等于完整ORD-001/002 DONE。员工权限、round1、公开订单读侧/展示状态接线、合法C端原因读取、小程序真机、券积分/通知及其他退款来源仍由原Issue承接。

可信敏感词审核Provider尚未交付，缺失即装配失败，没有生产总是放行实现。AES保护要求显式32字节密钥；生产密钥托管/轮换和历史解密、迁移回填/约束收紧、正式渠道验收与外部告警送达均需在启用前落实。普通退款成功投影依赖Outbox分发运行；积压时保留预约占用，不能提前释放。所有运行开关保持默认false。
