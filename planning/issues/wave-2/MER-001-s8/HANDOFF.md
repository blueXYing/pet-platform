# MER-001 S8：私有材料上传与受控读取

批准：用户确认 CCR-MER-PRIVATE-001。基线 PR56 / `78b3aad`；分支 `codex/mer001-private-assets-20260920`。本切片不代表整个 MER-001 完成，不执行生产迁移或自动合并。

## 实现

- thirdparty 内部 API、SQL31 私有资产/上传幂等绑定/一次性授权/读取审计，隔离既有公开素材表。
- 小程序选图、私有目录恢复副本、源摘要、按账号隔离的持久上传记录；请求结果未知保留原文件和 UUID，明确终态 `PRIVATE_ASSET_REJECTED` 才允许重新选择。
- 服务端认证 multipart 上传，源/最终对象均最多 10MiB。ClamAV VERSION/INSTREAM、完整 JPEG/PNG 解码、EXIF 朝向纠正、元数据剥离、标准化 PNG、摘要和固定对象版本。
- 同事务提交材料意图与既有 SQL13 AsyncTask，条件 PUT/HEAD/GET 处理回执丢失，共享 worker 执行可重试收敛。无新的模块私有任务队列。
- 运营当前领取人、当前申请版本、两项操作权限和范围复查；5 分钟一次性授权、摘要保存、加密原因、动态水印、禁止缓存。消费先提交，再在授权事务中生成结果；读取失败不会复活授权。
- 同步 API31、Schema/Storage31、OpenAPI、错误码、Scheduler42 与离线契约门禁。无新跨模块业务事件。

## 验证证据

2026-09-20 最终验证：Java21 `mvn -B -f backend/pom.xml clean verify` 全 41 模块通过，392 项测试 / 61 套件，0 failure/error/skipped（含 ArchUnit）；前端 113 项、离线契约 114 项、架构工具 13 项通过。typecheck、启用能力的微信构建与包检查通过。证据见 backend-test-summary.json、backend-verify-tail.txt、CORE.md、HTTP.md、FRONTEND.md 及本目录日志。

早期失败均记录为失败：修正两项新增测试的 UTC 读取/过期造数、隔离 Redis namespace、运行配置跨模块持久层构造。另既有 600ms lease 的心跳用例曾一次 LEASE_LOST，未放宽断言或修改心跳逻辑，定向复跑与最终整轮通过；后续 CI 仍需关注资源时序抖动。

真实 loopback HTTP + MySQL + Redis 证明 C 会话、multipart、真实私有核心、SQL13/31、201/200、账号隔离和持久事实；外部 OSS/扫描仍使用测试适配。独立真实 SQL26/29 测试证明当前审核/权限锁、撤权和会话代际复查。前端单元测试和构建不代替微信真机验收。

## 运行配置与剩余边界

- 后端 `PRIVATE_ASSETS_ENABLED`、小程序 `PET_PRIVATE_MATERIAL_UPLOAD_ENABLED` 默认 false；小程序还需既有申请开关和 HTTPS API origin。
- OSS 复用 `OSS_ENDPOINT/REGION/BUCKET/ACCESS_KEY_ID/SECRET_ACCESS_KEY`。本地 gitignored `ops/oss.env.local` 仅在本机由运行环境加载，未复制或提交凭据。对象只使用 `merchant-materials/`；真实 bucket 的私有 ACL 曾只读验证，新增适配器的真实对象写入和 RAM 最小权限仍未验收。
- 扫描需 `PRIVATE_ASSET_CLAMAV_HOST/PORT/TIMEOUT_MILLIS`，病毒库和扫描大小限制需由实际部署配置。未配置或不可用不能跳过检查。
- 单独注入 `PRIVATE_ASSET_GRANT_KEY_VERSION/HMAC_KEY_BASE64` 和 `PRIVATE_ASSET_REASON_KEY_VERSION/AES_KEY_BASE64`，要求独立 256-bit 密钥。版本/密钥缺失失败关闭，不在代码生成临时生产密钥。
- 反向代理、链路追踪和外部访问日志必须隐藏一次性 token 路径。应用不记录证件、对象 key 或明文原因，Tomcat access log 默认关闭。
- 无 versioning 时 ETag/If-Match 可检测外部对象变化并拒绝读取；持续不可变性仍依赖隔离前缀的独占写策略。未执行证件保留期限或自动删除策略。
- 后端地图一致性核验、真实 OSS/ClamAV 连通、微信真机选图/重启恢复/上传仍待验收。本次未开放非成都城市、非大陆身份证或新的商家经营资格规则。
