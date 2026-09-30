# 48 存储说明

K1/K2 用户已批准。执行 SQL48 前必须已应用 SQL47。仅隔离 QA 自动建库使用，生产迁移未授权。

核销成功和尝试扩展真实 actor、membership_kind、command_id；成功记录另关联 credential_id、attempt_id、order_version。command_id 指向 47 的五元组 Admission，移除错误的全局 request_id 唯一索引；保留每订单最多一次成功核销。OWNER 的 staff_id 必须 NULL；STAFF 只保留可表达结构，不提供写入口。

目前没有可验证的旧员工登录绑定来源：迁移第一步检查旧成功/尝试表必须为空；有旧行则 CHECK 拒绝后续 ALTER。不得删除旧记录以通过门禁，不得猜测 OWNER。若迁移门禁失败，留下 gate 表用于核对，人工查明数据并制定真实映射后另行评审迁移；本脚本不是可重跑生产工具。

ORDER 独占 order_verification_commit、pet_order.aftersale_status 和 VERIFY operation guard；AFS 独占 aftersale_verification_proof、工单和日志。proof 保存无当前工单或保留历史工单的明确证据。业务模块不跨域查表。数据库时间 UTC 毫秒；凭证消耗仍在 SQL47 的历史码行标记 VERIFIED，保留风险和不可变首回执。

无自动降级迁移：停用入口不删除 proof/历史/幂等记录，不恢复已消耗码。
