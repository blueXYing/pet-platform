# S9 实际联调进展

2026-09-20 用户要求立即完成实际联调，并明确授权微信开发者工具“不校验域名”、使用本地IP。

## 已执行

- 真实私有 OSS + 真实 ClamAV + 图片处理：PASS。修复仅真实环境暴露的 OSS PUT If-None-Match 不兼容；匿名403、服务端原子防覆盖409、下载摘要、水印与合成对象精确清理均实测。见 live-provider-result.json。
- 真实 loopback HTTP→MySQL→OSS→ClamAV：PASS，201首次/200重放，清理失败数0。该自动化验收中的微信身份入口明确使用 FixedWechatProvider，不能称为手机登录。
- 常驻LAN服务 `http://192.168.1.44:18080` 使用真实微信配置、OSS/ClamAV、独立临时MySQL/Redis与真实Snowflake/Outbox；地图缺配置时503，未放行。
- 微信开发者工具真实 wx.login 与后端已完成手机号授权，页面显示已登录。无注入token、伪造手机号或mock网络。首次页面选图暴露跨 realm ArrayBuffer 校验误判，尚未发起上传；修复后已重新触发选图，等待页面上传复验，未宣称真机完成。
- 前端只在 NODE_ENV=development 且 PET_ALLOW_LOCAL_HTTP=true 时允许显式本机/私网HTTP；生产默认仍HTTPS。116项前端测试、typecheck与LAN开发构建通过。
- 后端地图适配及8项受控协议/装配测试通过；需腾讯 WebService Key 与明确距离策略，当前没有真实腾讯服务通过证据。
- 最后增量 Java 定向验证：S3适配器5、地图Provider5、地图装配3、异常回归1、架构22，共36项通过；既有7项图片/扫描测试另通过。

## 真实选图发现与修复

微信原生文件系统返回 `constructor.name=ArrayBuffer`、byteLength正确，但与页面 realm 的 `ArrayBuffer` 做 instanceof 为 false。真实诊断文件读回复现，详见 wechat-buffer-diagnostic.json。save/inspect 改为用原生 byteLength getter 验证内部类型，拒绝伪造对象、Proxy、TypedArray、SharedArrayBuffer及超限/已分离数据；仍限制1..10MiB。Node vm跨realm与边界回归后，119项前端测试、typecheck及LAN开发构建通过。模拟器仅刷新，未清缓存、会话或注入凭据。

## 启动与持久性

常驻入口在 test scope：LocalMerchantAcceptanceServer；环境变量名称与人工命令见 LIVE-PROVIDERS.md。开发密钥仅保存在仓库外，服务器原有生产配置未迁移。临时数据库在服务关闭时删除；小程序未确认上传须先恢复/核对，避免关闭测试服务后误以为记录仍对应可用测试申请。

微信本地 AppID 和 project.private.config 的域名校验配置用于当前联调，不纳入生产提交。测试服务器保持本地LAN监听，未建立公网隧道。
