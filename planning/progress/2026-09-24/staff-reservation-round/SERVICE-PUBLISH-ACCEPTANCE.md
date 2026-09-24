# 服务发布实际联调回执

2026-09-24。源码：本轮 `9322f90` + `5eed3eb`；模拟器本地 Taro build（同 develop 980a830 前端源码），JDK21/MySQL8.4.9/Redis/真实 OSS mtxoss2/ClamAV。新增 M002_REAL_PRIVATE_ASSETS 测试启动模式，不改变生产默认开关。

## 通过的实际步骤

- 开发者工具中使用真实商家上下文打开工作台、新建服务。输入“真实上传联调洗护”、88.00、45分钟、猫、到店型及美容分类。
- 仅图片选择器替换为本机生成的 SYNTHETIC SERVICE COVER PNG。文件保存/哈希、上传申请、微信 uploadFile→真实 OSS、完成回执、真实 ClamAV/归一化均未替换。素材 READY，源/归一化对象均 version: 标识。
- UI保存草稿、提交审核成功，服务 REVIEWING；使用真实运营会话与真实审核 HTTP APPROVE，变为 ACTIVE。
- C 详情 HTTP200；精确版本签名 GET200、image/png、4166 bytes。GET SHA256 `f3451908bfdec278883c38491d4c5a2061f4c19c588257fdee91bf0c4339e945` 与数据库归一化对象一致，URL 含 versionId。未存入签名URL或访问密钥。
- 小程序 C 详情实际显示该合成封面及刚发布的真实服务名称/88元，见 [封面截图](evidence/service-active-consumer.jpg)、[服务行截图](evidence/service-active-row.jpg)。未填说明也能显示，整分钟有效期保留秒。
- DB确认 ServiceReviewedEvent.v1 为 PUBLISHED 且 SERVICE通知一条；入驻审核事件/通知各一条。此处为真实事件消费与落库，未额外声称消息页视觉验收。
- 所有本次临时素材只按隔离数据库记录的精确key/version清理，每轮 cleanupFailures=0；最终测试服务停止，选图Mock恢复，共享Redis/ClamAV和bucket设置未修改。

## 实际发现并修复

1. C可见性查询只读事务内调用素材SELECT FOR UPDATE失败。签名API专用新可写事务在锁内重读READY及精确版本，保留外层可见性快照。ServiceCoverSigningMySqlTest新增回归先失败后通过（3 tests/0 skipped），覆盖外层旧READY快照在素材被撤下后仍拒签。
2. 可选description为NULL，C前端严格字符串解码失败。仅C投影映射为空文本，存储/商家合同不变；ServiceQueryHttpTest通过。
3. OffsetDateTime整分钟格式省略秒，真实签名窗口因此触发前端时间校验失败。改用Instant规范UTC秒字段；ServiceWriteHttpTest固定整分钟到期并验证。

## 证据边界与环境异常

微信OAuth使用FixedWechatProvider测试登录；入驻证照与地址校验使用测试替身，运营登录验证码使用既有测试接缝。此回执证明服务发布闭环，不证明真实微信/证照或真实入驻验收。消费者页既有商家介绍、地址、评价等区域仍是设计样例，截图不表示这些信息已接入真实门店。

运营审核本次走真实HTTP，未验收运营审核页面；图片选择器为合成文件替身，未证明手机相册/相机；开发者工具本地HTTP，不是手机真机或生产HTTPS发布。旧ETag封面未迁移。

首轮审核遇本地长驻Snowflake OPERATION_TIMEOUT后失败关闭，重建独立测试服务恢复；未调大生产超时或绕过ID策略。后续最终整条链通过，此环境异常保留记录，不据此宣称长时稳定性已验收。
补充：页面自动化导航的实际路由为 `consumer/pages/store-services/service-detail?serviceId=96543645653618688`，该页面复用商家详情设计，截图标题“商家详情”属于既有布局。截图证明真实图片/服务行显示；[脱敏直连接口回执](evidence/consumer-http-redacted.json)保留C详情数据与图片GET结果，不将它冒充模拟器Network原始记录。停止服务、恢复测试AppID后开发者工具已刷新，未额外保存最终Network全量转录；事件/通知数量为本文记录的当时只读SQL结果。

最终契约复核还校正C展示解码器的服务时长上限为既有写契约10080分钟（此前误限1440分钟）；补10080成功/10081拒绝边界，178项前端测试和typecheck通过。截图在此数值边界修正前取得，显示布局/网络路径未变。
