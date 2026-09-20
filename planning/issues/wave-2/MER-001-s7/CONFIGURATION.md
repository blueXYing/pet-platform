# S7 配置交接（默认不启用）

本文件为配置示例，不自动写入本地主目录、数据库或生产。应用启动仍要求私有材料、服务端地图事实等真实依赖就绪；不允许配置常量成功Provider绕过它们。

```yaml
pet:
  merchant:
    application:
      enabled: false
      notifications-enabled: false
      open-cities:
        - code: chengdu
          name: 成都
    protection:
      enabled: false
    subject:
      enabled: false
```

用户已确认首批成都、大陆居民身份证。`chengdu`为平台稳定key，不是伪造行政区划代码；空目录返回503。扩城市须再获产品批准。

加密所需外部变量（仅变量名，绝不提交真实值）：

- `MERCHANT_PROTECTED_KEY_VERSION`
- `MERCHANT_PROTECTED_AES_KEY_BASE64`：32字节AES密钥的Base64。
- `MERCHANT_PROTECTED_HMAC_KEY_BASE64`：独立32字节equality HMAC密钥的Base64。
- `MERCHANT_SUBJECT_POLICY_VERSION`：与SQL29固定policy一致，1～32位规定字符。
- `MERCHANT_SUBJECT_HMAC_KEY_BASE64`：独立32字节主体lookup HMAC密钥的Base64。

三个密钥用途不能共用，也不能静默轮换版本绕过重复主体判断。代码不生成/保存生产密钥；外部secret托管和迁移政策须由部署配置承接。

小程序申请/城市真实HTTP接入需构建变量 `PET_MERCHANT_APPLICATION_ENABLED=true` 和 `PET_C_API_ORIGIN`。没有同时提供时默认关闭；预览始终不写业务。用户已指定微信原生选点，AppID后台仍需按平台要求声明位置用途和开启相关接口；用户取消选点不发起提交。

OSS复用主目录被Git忽略的 `ops/oss.env.local`。本轮只读核验桶ACL为private，未上传证件；该文件不能复制进本分支或CI。使用桶并不自动提供私有材料归属、扫描和水印读取服务，新增契约见[CCR](../../../ccr/CCR-MER-PRIVATE-001.md)。
