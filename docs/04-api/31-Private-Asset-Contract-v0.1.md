# 私有商家材料契约 v0.1

批准来源：CCR-MER-PRIVATE-001；2026-09-20 用户确认。适用 MER-001 S8，状态 IMPLEMENTED_CANDIDATE_LOCAL_VERIFIED，默认不启用生产。

## 上传与不可变事实

`POST /api/v1/c/private-assets` 使用 MINIAPP bearer 会话与完整 UUID `X-Request-Id`，multipart 仅包含 `purpose=MERCHANT_APPLICATION_MATERIAL` 和 `file`。owner 只取当前会话，不能由客户端传入。文件原字节和最终对象均为 1..10 MiB；明确 JPEG/PNG 声明必须匹配实际图片；空声明或 application/octet-stream 从头部识别类型后仍须完整解码。JPEG 的 EXIF 朝向先纠正，再清除元数据、重编码 PNG。图片单边不超过 8192、总像素不超过 1600 万，标准化超出字节上限同样拒绝。Servlet 文件阈值 10MB、单文件上限 10MB、请求上限 11MB，避免有效文件解析落入普通临时目录；应用层仍独立执行 10MiB 上限。

先保存不可变上传意图和 durable AsyncTask，再执行私有对象写入与收敛。owner/requestId 绑定源摘要及语义参数；相同请求只能恢复同一 assetId，不同内容不能覆盖绑定。原始 sourceSha256 与标准化 objectSha256 分别登记。只有真实对象版本、摘要、扫描、图片解码均成功才 READY；未知写入按原意图收敛，不得返回新标识伪装成功。

成功 envelope 的 data 严格为 `{assetId,status,objectSha256,mediaType,bytes}`，status 只可 READY，ID 是十进制 String。首次 201、同请求恢复 200。不返回 objectKey、原文件名、源字节或 OSS URL。上传未就绪使用 409 PRIVATE_ASSET_NOT_READY；依赖不可用为 503，客户端必须保留原 requestId/原文件重试。

thirdparty 拥有 `PrivateAssetApi`、SQL31 和对象适配器。MER 通过 API 重读 owner/purpose/hash/status/不可变版本，禁止访问 thirdparty Mapper。现有公开 asset_registry、公开签名 URL 与证照隔离。没有保留期限裁决，不执行自动删除。

已绑定上传意图的材料最终为 `REJECTED/QUARANTINED` 时，首次返回及同 requestId 恢复均使用 `422 PRIVATE_ASSET_REJECTED`，data 为 null，不暴露扫描签名。此明确终态允许客户端在用户主动重新选择时清理该意图的本地副本，并以新文件、新 UUID 发起新上传；不修改或覆盖原绑定。即使前次结果未知，此 code 与 HTTP 422 的组合也可确认原意图已拒绝。一般 400/413/415 或其他 422 不能证明历史未知上传已终结，仍保留原文件及 requestId；未知 409/503/网络超时同样保留。

## 单次读取授权

`POST /api/v1/admin/merchant-applications/{applicationId}/private-assets/{assetId}/read-grants`：ADMIN_WEB bearer、UUID `X-Request-Id`，body 包含 `submissionRevisionId,purposeCode,reason,confirmed:true`。用途和原因必填，仅当前领取审核任务的审核人可调用；当前 `identity.reveal` 与审核决定动作及数据范围均须通过。申请必须关联当前 submitted revision 和材料 asset/hash/owner。

授权回调在相同 datasource 事务中锁定当前 MER 事实并做最终权限核对，不能另开事务提前释放锁。签发保存受保护原因、授权/范围版本、会话摘要/代际、材料事实及审计；token 原文不存库。签发回执只返回后端 readUrl 与 expiresAt。有效期 5 分钟，幂等重放不延长时间、不创建第二个授权。

`GET /api/v1/admin/private-asset-read-grants/{token}` 同样要求当前 ADMIN_WEB bearer。首先验证 token 绑定并原子提交消费和 STARTED 审计，再在独立事务重新核对会话、当前任务领取人、材料版本、动作和范围，持有最终授权锁直至水印结果形成；不能仅凭链接访问。入口禁止包裹外层事务，避免消费记录被后续授权失败回滚。成功输出带 operatorId、applicationId、读取时间的重复水印 PNG；小图扩展白色画布保证水印可见。原始对象始终不返回。已使用或过期使用 410 PRIVATE_ASSET_GRANT_GONE；当前身份/权限撤销沿用 401/403。读取失败不将已经消费的授权恢复成可重复使用；需重新签发。进程在 STARTED 后崩溃时不伪造成功，审计保留未完成事实。

所有成功/失败响应禁止缓存；图片还设置 `Pragma: no-cache`、`X-Content-Type-Options: nosniff`。应用日志与反向代理访问日志不得记录原 token 路径、Authorization、私有对象 key、明文原因或证件原件。消费审计应区分授权拒绝、读取失败与成功。

## Provider 与验证边界

扫描器使用 ClamAV 的 VERSION 与 INSTREAM，端点显式配置在可信私网。连接、读写、回执长度有界；只有明确 CLEAN 回执通过，错误、超时、未配置、畸形回执全部失败关闭。部署负责病毒库更新和 ClamAV 扫描大小配置，不得将大小跳过视作 CLEAN。协议依据：[ClamD Protocol](https://docs.clamav.net/manual/Usage/ClamdProtocol.html)。

源文件最多 10 MiB；图片解码不落普通临时文件，输出不包含输入元数据。水印只用可信上下文，不使用 token 或客户端姓名。测试可用协议替身模拟故障，但生产不得注入默认放行扫描器、内存私有材料或公开 URL。

实际 OSS 使用已确认的本地私有 bucket 配置，代码复用 OSS_* 名称，不把密钥复制入仓库。独立 grant HMAC 密钥和原因保护依赖由运行时注入，必须稳定且与其他用途密钥隔离。真实 OSS/ClamAV 可达性和后端地图核验仍须单独验收，不能由协议替身测试声称生产链路已通。
