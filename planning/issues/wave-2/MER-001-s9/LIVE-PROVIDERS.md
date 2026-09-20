# S9 真实私有材料 Provider 验证

用户于 2026-09-20 要求现在完成真实联调，并授权复用本地 OSS。使用真实私有 bucket、实际生产 `S3PrivateObjectStore`、真实本机 ClamAV 容器与 ImageIO 处理器；只上传程序生成的 SYNTHETIC INTEGRATION TEST 图片，未读取或上传真实证件。

## 已实测

- bucket 私有 ACL；真实 PUT/GET；下载字节及 SHA256 相同；幂等重放保持 ETag；不同内容拒绝。
- 直接对本次测试对象发起覆盖写，OSS 原子返回 409 FileAlreadyExists；不是仅验证客户端 HEAD 检查。
- 匿名 GET 返回 403；服务端读取后生成动态水印，输出与源图片不同。
- 本次成功验收对象已精准删除并 HEAD 确认不存在。早期诊断留下的一个 integration-probe 合成图片，也在限定该专用前缀、UUID路径、长度、摘要与实际字节完全相同后清理，计数1；未删除任何业务材料。
- ClamAV 1.5.4、病毒库28128：正常 PNG 为 CLEAN，标准 EICAR 测试字符串检出。测试字符串仅送本机扫描器，未上传 OSS。

结果见 live-provider-result.json。Docker 仅绑定 127.0.0.1:13310，镜像 digest 已记录；扫描服务不是公开端口。

## 真实兼容修复

原 adapter 同时发送 S3 `If-None-Match: *` 和 OSS `x-oss-forbid-overwrite: true`。真实 OSS PUT 返回 400 NotImplemented。保留 SDK 默认 checksum 选项，唯一删除不兼容的 If-None-Match 后通过。现仅阿里云 endpoint 使用 OSS 原生原子防覆盖头；其他 S3 endpoint 继续使用 If-None-Match。真实直接覆盖测试证明原子保护有效，未以先 HEAD 再无条件 PUT 替代。

依据：[OSS PutObject](https://www.alibabacloud.com/help/en/oss/developer-reference/putobject)、[OSS S3兼容范围](https://www.alibabacloud.com/help/en/oss/developer-reference/compatibility-with-amazon-s3)。SDK是否支持操作不能代替真实服务验收。新增阿里云与通用 S3 分支回归测试，5项S3适配器、7项图片/扫描测试通过。

PR57 首轮CI的 ARCH-004 另发现 Web advice 注解直接依赖 DataAccessException。改为 Spring 非持久化公共异常基类，数据库/事务故障仍503；新增脱敏回归。定向30项HTTP/异常/架构测试通过，未放宽架构规则。

## HTTP、数据库与本地小程序联调

`PrivateAssetLiveAcceptance` 已通过真实 HTTP → 临时 MySQL → 真实 OSS/ClamAV 链路：带明显
`SYNTHETIC TEST` 字样的 PNG 首次上传 201、同 requestId 重放 200，测试只按该临时库记录的
source/final 两个精确 key 删除，`exact-object-cleanup failures=0`。该 runner 使用
`FixedWechatProvider`，所以这项结果不证明真实微信登录。

`LocalMerchantAcceptanceServer` 当前在 `http://192.168.1.44:18080` 提供本机局域网联调，使用
真实微信、OSS、ClamAV、Snowflake、MySQL、Redis、AdminAuthorization SQL 与 Outbox。真实
`wx.login` 已到 `VERIFY_PHONE`；手机号授权尚未完成，不能声称登录闭环完成。地图 key 未配置，
服务端 `MapValidationPort` 明确返回依赖不可用，申请提交保持 fail-closed。

### 必需环境变量名

不在命令、文档或日志中填写值：

```text
AUTH_MYSQL_URL AUTH_MYSQL_USER AUTH_MYSQL_PASSWORD
AUTH_REDIS_HOST AUTH_REDIS_PORT
AUTH_WECHAT_APP_ID AUTH_WECHAT_APP_SECRET
OSS_ENDPOINT OSS_REGION OSS_BUCKET OSS_ACCESS_KEY_ID OSS_SECRET_ACCESS_KEY
PRIVATE_ASSET_GRANT_HMAC_KEY_BASE64 PRIVATE_ASSET_REASON_AES_KEY_BASE64
MERCHANT_PROTECTED_AES_KEY_BASE64 MERCHANT_PROTECTED_HMAC_KEY_BASE64
MERCHANT_SUBJECT_HMAC_KEY_BASE64
LOCAL_ADMIN_MAC_KEY_BASE64 LOCAL_ADMIN_AES_KEY_BASE64
```

人工 live test 还必须设置 `PRIVATE_ASSET_LIVE_ACCEPTANCE=true`。常驻服务必须设置
`LOCAL_MERCHANT_ACCEPTANCE=true`；可用 `LOCAL_ACCEPTANCE_BIND_ADDRESS`、
`LOCAL_ACCEPTANCE_PORT`、`LOCAL_ACCEPTANCE_METADATA_PATH` 覆盖默认局域网地址、18080 和元数据路径。

### 人工命令

```powershell
mvn -B -f backend/pom.xml -pl pet-boot -am test `
  "-Dtest=PrivateAssetLiveAcceptance" `
  "-Dsurefire.failIfNoSpecifiedTests=false"

mvn -B -f backend/pom.xml -pl pet-boot -am test-compile
mvn -B -f backend/pet-boot/pom.xml dependency:build-classpath `
  "-DincludeScope=test" "-Dmdep.outputFile=target/manual-classpath.txt"
$manualCp = Get-Content backend/pet-boot/target/manual-classpath.txt -Raw
java -cp "backend/pet-boot/target/test-classes;backend/pet-boot/target/classes;$manualCp" `
  com.petplatform.boot.auth.LocalMerchantAcceptanceServer
```

常驻进程只使用随机临时测试数据库，元数据文件记录数据库名；正常 shutdown 会清理独立 C/Admin
Redis namespace 并删除临时数据库。它不会自动删除手机联调产生的私有材料，也不会访问或清理其他
业务对象。Admin HTTP origin 保持 loopback 安全限制；当前 LAN 入口用于 C 小程序联调。
