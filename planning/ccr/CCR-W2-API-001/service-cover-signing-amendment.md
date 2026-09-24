# SERVICE_COVER 真实展示签名补充 CCR v0.1

状态：APPROVED_IMPLEMENTED_NEW_VERSIONED_ASSET_LIVE_VERIFIED。日期：2026-09-24。
来源：已批 SVCW-D4 要求真实封面上传及消费者展示；本轮用户授权服务发布收尾。

## 起草时核实的缺口（历史，当前实现见下方回执）

- POST /api/v1/c/private-assets 已接受 SERVICE_COVER，标准化、扫描、owner/purpose 隔离已有实现。
- 服务写入按 READY/owner/purpose 校验 coverAssetId；C 端服务读先判四条件可见性，再调用 ServiceCoverUrlPort.sign(assetId)。
- boot 没有 ServiceCoverUrlPort 真实 bean；M002IntegrationServer / ServiceWriteHttpTest 只有 cover.example.invalid 签名桩。
- 私有素材事实保存在 private_asset 系列，旧 PresignedAssetUrlService 读取公开运营素材 asset_registry 的 asset_key 映射，两者不是同一注册表，不能把数值 assetId 当成 asset_key 直接调用。
- PrivateAssetApi 当前明确不暴露 object key 或 URL，不能为接线擅自扩它或跨域读 Mapper。

## 已批准的最小内部契约

1. thirdparty-api 新增独立 **ServiceCoverSigningApi**（不扩 PrivateAssetApi 的证照接口）：输入十进制 String assetId + 现有 QueryContext；返回 assetId、HTTPS signedUrl、expiresAt（Instant）。仅后端内部可调用，不新增客户端“任意 assetId 签名”路由。
2. thirdparty-biz 自有素材存储查找资产，只接受 purpose=SERVICE_COVER、READY、JPEG/PNG、已确认不可变对象版本；只为该标准化对象的精确 key+version 签 GET。证照素材、非READY、无版本及对象事实不完整均拒绝。对象 key/version 不出模块；签名URL是专用于已审核服务封面的例外，不改变证照的水印/单次读取规则。
3. boot 实现既有 ServiceCoverUrlPort 到该内部 API 的适配；服务域仍负责四条件可见性及服务—素材绑定授权。不可见服务在签发前404；有绑定但签名缺依赖/事实异常维持503，不返回裸URL、不使用固定域名替身。
4. 默认保持关闭，显式启用已有真实 OSS 私有素材链后才装配。有效期采用已有 CCR-OSS-001 的配置与量化窗口规则（最小600秒，不臆造生产默认时长），读响应 expiresAt 必须与实际签名一致。客户端临期/过期重新读服务接口，经重新可见性校验获取新签名。
5. 无新Schema、HTTP路径或Event；更新07号内部契约、31号SERVICE_COVER展示例外、消费者封面段的真实装配说明。S3版本对象签名能力在第三方模块端口内实现，不能把 private_asset 的对象指针泄露给 boot/service 模块。

## 验收与限制

- READY 封面上传→服务提交/审核→匿名可见读→真实已签名图片GET；对象内容应为标准化图片；图片未就绪、证照purpose、错误绑定、服务下架与签名依赖缺失均负向验证。
- 验证签名有效期、精确对象版本、到期拒绝；日志与证据不落凭据/完整签名URL/原证照内容。
- 现有客户端字段不变，缺签名继续失败关闭。本轮前端改动和Fixture验证不得标成真实OSS展示验收通过。
- 已签发URL在有效期内通常不能因服务下架即时撤销；新读取不再签发。沿用既有短期签名授权模型，不声称即时撤销；更强撤销能力需要另裁代理方案。

## 审批边界

AGENTS.md 要求“Contract 缺失走 CCR”。本文件方案已由用户回复“同意该 CCR，继续实现真实签名”批准；不需重审已经批准的封面必填、上传归属或服务审核规则。


## 实现细化及首次真实环境回执（历史，ETag-only阶段）

- 已新增 ServiceCoverSigningApi、第三方模块签名实现及 boot 适配。无新客户端签名路由，无Schema/Event变化。
- 配置：pet.private-assets.enabled=true 且 pet.service.cover-signing.enabled=true 才装配；pet.service.cover-signing.window-seconds 显式配置，范围600～537600秒（量化续窗仍不得超过S3七天上限），无生产默认值。
- 精确对象版本：仅接受 version:<非null版本ID>；ETag-only 对象无法通过普通 Image URL 携带受签名保护的 If-Match 请求头，不退回签“最新对象”，返回503。签名可作为图片直接GET，不要求额外自定义头。
- 2026-09-24 真实 MySQL+OSS+ClamAV 合成图片上传 READY；对象事实为 ETAG_ONLY，签名按预期拒绝。真实图片下载正向验收 **BLOCKED**，不能报告为成功。
- 用户再次明确选择：“保留 bucket 现状，明确登记环境阻塞”。本轮没有修改bucket版本控制、ACL、防覆盖策略或迁移历史对象；仅清理本轮测试资产的精确对象。
- 版本化bucket只是未来选项，不自动批准。OSS官方说明：[Versioning overview](https://www.alibabacloud.com/help/en/oss/user-guide/overview-78/)（开启后只能暂停，影响防覆盖头与历史版本费用）。


## 用户开启版本控制后的当前回执（2026-09-24）

用户随后明确反馈已在OSS控制台开启版本控制，覆盖之前“保留bucket现状”的阶段决定。agent没有执行bucket配置变更。

同一实现代码重新验证mtxoss2的新上传合成图片：真实OSS/ClamAV READY、非null VERSION_ID、精确版本签名GET 200、标准化内容摘要一致；原私有材料真实HTTP上传和同requestId重放通过，相关专项共19/19通过。测试资产按精确key/version清理，未批量读取或改写历史资产。见[追加证据](../../progress/2026-09-24/service-schedule-closeout/versioned-oss-recheck.json)。

当前状态：新对象的版本条件ENV_BLOCKED已解除，原失败记录保留作历史。旧ETag-only资产不会自动转换，本轮未迁移；生产应用签名开关仍未开启，完整UI/真机验收未由这次后端/provider复验替代。不调整已批准接口/签名语义。
