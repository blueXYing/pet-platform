# S9 真实私有材料 Provider 验证

用户于 2026-09-20 要求现在完成真实联调，并授权复用本地 OSS。使用真实私有 bucket、实际生产 `S3PrivateObjectStore`、真实本机 ClamAV 容器与 ImageIO 处理器；只上传程序生成的 SYNTHETIC INTEGRATION TEST 图片，未读取或上传真实证件。

## 已实测

- bucket 私有 ACL；真实 PUT/GET；下载字节及 SHA256 相同；幂等重放保持 ETag；不同内容拒绝。
- 直接对本次测试对象发起覆盖写，OSS 原子返回 409 FileAlreadyExists；不是仅验证客户端 HEAD 检查。
- 匿名 GET 返回 403；服务端读取后生成动态水印，输出与源图片不同。
- 本次成功验收对象已精准删除并 HEAD 确认不存在。早期诊断曾留下一个同专用 integration-probe 前缀的合成图片，后续清理单独记录，不删除任何业务材料。
- ClamAV 1.5.4、病毒库28128：正常 PNG 为 CLEAN，标准 EICAR 测试字符串检出。测试字符串仅送本机扫描器，未上传 OSS。

结果见 live-provider-result.json。Docker 仅绑定 127.0.0.1:13310，镜像 digest 已记录；扫描服务不是公开端口。

## 真实兼容修复

原 adapter 同时发送 S3 `If-None-Match: *` 和 OSS `x-oss-forbid-overwrite: true`。真实 OSS PUT 返回 400 NotImplemented。保留 SDK 默认 checksum 选项，唯一删除不兼容的 If-None-Match 后通过。现仅阿里云 endpoint 使用 OSS 原生原子防覆盖头；其他 S3 endpoint 继续使用 If-None-Match。真实直接覆盖测试证明原子保护有效，未以先 HEAD 再无条件 PUT 替代。

依据：[OSS PutObject](https://www.alibabacloud.com/help/en/oss/developer-reference/putobject)、[OSS S3兼容范围](https://www.alibabacloud.com/help/en/oss/developer-reference/compatibility-with-amazon-s3)。SDK是否支持操作不能代替真实服务验收。新增阿里云与通用 S3 分支回归测试，5项S3适配器、7项图片/扫描测试通过。

PR57 首轮CI的 ARCH-004 另发现 Web advice 注解直接依赖 DataAccessException。改为 Spring 非持久化公共异常基类，数据库/事务故障仍503；新增脱敏回归。定向30项HTTP/异常/架构测试通过，未放宽架构规则。

本证据只证明 Provider 链路。真实 HTTP+数据库+Provider 的整合验收、微信真实登录/选图、后端地图实测另行记录，不能从本结果推导全部完成。
