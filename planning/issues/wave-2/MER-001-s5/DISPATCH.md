# MER-001 S5 申请审核运行实现

基线为 PR53 head `9d6b828`，承接已合入 PR52。用户在收到 PR53 技术契约待审说明后明确“那么请你实施”，作为该实施范围的批准；既有四项产品决定直接执行，不重复索取裁决。此授权不扩展为生产部署、默认数据库迁移或自动合并新 PR。

实施分支：`codex/mer001-application-runtime-20260917`。原主目录的 `frontend-miniapp/project.config.json` 保持不动。

## 写入范围

- application_runtime（GPT-5.6 Sol medium）：merchant-api / merchant-biz，申请与审核事务、材料与证据关联、真实审核投影及 MySQL 验证。
- application_auth（GPT-5.6 Sol medium）：admin-api / admin-biz，真实会话、动作、数据范围的最终当前读复核及回归。
- 根任务：notification-biz 消费者、boot 组合适配、集成审查、验证及交接。
- frontend_transport（GPT-6 Astra medium）：小程序申请与协议仓储、精确wire解码、会话/工作区隔离及测试；未注册页面。
- 前端按用户要求保留 GPT-6 medium + Figma 插件路径。当前 Figma 插件未安装连接，不能把历史稿读取表述为线上设计核对或视觉验收。

## 必须保留的界限

申请状态只有 DRAFT / REVIEWING / REJECTED / APPROVED；补正是决定类型，修改新版本后重提；APPROVE 建档 ACTIVE，签约仍独立。无 OCR Provider 时可以待人工核验，不能默认 VERIFIED。私有材料不得使用公开资产 URL 替代；真实资产、城市地图、密钥或规范化依赖不可用时失败关闭。

单运营无双人审批、无额外 MFA。审核必须当前领取人；执行与成功回执重放都重新确认权限。通知消费日志和站内消息同事务，失败回滚，重试不得重复。完成判断以实际测试和交接记录为准，不以接口或表存在代替业务验收。
