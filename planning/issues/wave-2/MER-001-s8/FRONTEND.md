# S8 私有材料上传前端

2026-09-20；基线 PR56；实现范围为 `frontend-miniapp` 商家申请既有页面。无提交或推送。

## 已实现

- `ConsumerApi.uploadPrivateAsset` 为专用 MINIAPP multipart 鉴权入口，不暴露 Bearer getter。验证 consumer 工作区、owner、当前 sessionId、credential 与 scope epoch，401 清除登录态，旧身份迟到响应不能发布回执。
- `POST /api/v1/c/private-assets` 固定 `file` 文件字段、`purpose=MERCHANT_APPLICATION_MATERIAL`、完整 UUID `X-Request-Id`。上传使用 `Taro.uploadFile`；不设置错误的 JSON Content-Type，不调用公开 CDN/OSS 直传。
- 仅接受 200/201、`success:true`、`code:SUCCESS` 与精确五字段 `assetId/status/objectSha256/mediaType/bytes` 的 READY 回执。禁止 URL/额外字段、数字 ID、非法 hash、错误 MIME 或超限长度。object hash 允许与 source hash 不同，以支持服务端安全重编码。
- `Taro.chooseMedia` 只选择一张原始图片。选择后复制到当前小程序 `USER_DATA_PATH/pet-private-material-{uuid}.jpg|.png`；原始选图路径不进入上传恢复记录。记录只含 owner、材料类别、请求 UUID、私有副本路径、SHA256、长度与已确认回执，不含文件字节、base64、身份证号或上传凭据。
- 新增固定版本 `@noble/hashes@1.8.0` 计算源文件 SHA256。发送前重读副本并核对 SHA256/长度，不能用同一 key 替换不同文件。副本上限 10MiB。
- 独立的 origin+owner 恢复记录在页面重挂载、进程重启、401 和退出登录后保留；其他身份不能读取/发送。应用运行时共享上传实例，重复点击合并为同一 Promise。只有得到服务端 READY 并向页面交付回执后清理精确副本；首次发送明确 400/413/415 拒绝后可由用户明确重新选择并清理原副本。未知结果的“稍后处理”保留记录，不声称取消远程上传。
- 页面复用原材料入口、提示区和原生弹窗呈现恢复/重试。上传未确认时拦截保存/提交；保留原申请草稿、材料 1–6 张、身份证正反面/营业执照必填与医院行业证照规则。未改 `view.tsx`、SCSS 或原始切图。

## 配置

默认关闭。仅在运营明确配置已可用服务后设置 `PET_PRIVATE_MATERIAL_UPLOAD_ENABLED=true`，同时要求 `PET_MERCHANT_APPLICATION_ENABLED=true` 与 HTTPS `PET_C_API_ORIGIN`。未启用时维持“材料上传暂不可用”，不生成占位 ID。

## 验证

- `npm test`：113/113 通过，含 13 个私有上传测试；见 `frontend-tests.txt`。
- `npm run typecheck`：通过；见 `frontend-typecheck.txt`。
- `npm run build:weapp`：默认关闭构建通过；随后用 `https://api.example.invalid` 仅作编译期无网络占位 origin，开启两项能力开关的构建通过；见 `frontend-build-enabled.txt`。该构建不是可连接生产环境的发行包。
- `npm run check:package`：通过。开启能力包 main 748154 bytes、merchant application 66086 bytes、总计 2636387 bytes；见 `frontend-package.txt`。既有 profile/pet 大图片与样式构建提示仍在。
- 测试覆盖 multipart URL/字段/UUID、标准 SHA256 与10MiB输入、断网后同请求重放、进程重启、双击、不同材料互斥、同长度异内容篡改、401与另一身份隔离、同用户 scope replacement、非法回执、选图取消、任意路径拒绝、未知结果不允许丢弃、明确拒绝后重新选图。
- `git diff --check -- frontend-miniapp` 通过。

## 未完成的运行验收与限制

真实设备选图、相机/相册隐私授权、实际 `USER_DATA_PATH` 副本跨进程恢复及真实 HTTPS 上传尚未执行；须待服务端扫描/私有 OSS 依赖就绪后验收，不能用当前单元测试或静态包检查代替。平台合法域名/上传域名及隐私声明须由发布环境配置。

本地副本只在小程序私有数据目录中保留，未额外进行客户端加密；退出登录时保留原用户未确认文件用于同 key 恢复，其他用户界面和操作不暴露该记录。缺失/损坏恢复文件失败关闭，不重选新文件复用旧 key；需保留记录后人工核对。无自动超期删除策略。

补充：发送前持久化 attempted 标记；只要存在过未确认尝试，后续入口层400/413/415/422不能证明原操作已终结，继续保留原记录。副本后缀只从JPEG/PNG魔数推导，避免无后缀导致平台错误推断multipart MIME；真实设备仍须核验。

独立图片边界复核后，按root追加任务实现JPEG APP1 EXIF朝向（IFD0 SHORT count1，大小端，边界与重复拒绝，8向像素转换，随后剥离metadata）；无新依赖。PrivateImageProvidersTest新增8向×大小端逐像素矩形测试与非法朝向/offset/type测试。生产类独立javac通过，Java21 JUnit/Maven由root统一执行。


终态恢复修复：精确 HTTP422 + PRIVATE_ASSET_REJECTED 可确认任意历史attempted意图已REJECTED/QUARANTINED，允许用户主动重选新文件并创建新UUID。其他422或错误code/status组合继续保留记录。新增未知ACK→错误code/status锁定→明确422终态→新SHA256/UUID测试；前端113/113、Python契约114/114通过，typecheck/enabled build/package通过。API31、错误码注册、OAS与负门禁同步。

