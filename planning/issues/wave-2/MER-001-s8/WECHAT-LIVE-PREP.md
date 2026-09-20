# 微信本机联调准备（2026-09-20）

仅本机开发者工具，未发布、未上传体验版、未扫码手机验收，未 mock wx API。

## 实际检查

复用根任务已确认的 wechatide 0.3.9/equal、loginExpired=false、tokenRequired=false。CLI `D:/soft/微信web开发者工具/wechatide.cmd -c Codex`。

项目 `C:/Users/Administrator/Desktop/wt-mer001-private/frontend-miniapp` 初始 touristappid 返回 APPID_ERROR；仅临时把 worktree 的 appid 改为主项目真实 AppID `wx1a64646b1d75306e`，其余字段保持；主项目文件完全未动。open_project_window 成功创建 s0。原 worktree 配置保存于 `project.config.original.json`，需联调结束恢复，不能提交真实本机配置变动。

- 第一次 runtime_info 超时；第二次真实查询成功。没有反复刷新或关闭其他窗口。
- 原生运行时 `typeof wx.chooseMedia/chooseLocation/uploadFile/getPrivacySetting` 均为 function，`wx.env.USER_DATA_PATH` 存在。证据 `wechat-native-capabilities.txt`。
- 真实 `wx.getPrivacySetting` 返回 needAuthorization=false、hasContract=true。证据 `wechat-privacy-state.txt`。仅代表该模拟器当前身份，不能推断新用户或手机授权。
- 真实 `wx.getSetting` 返回 scope.userLocation=true；未清除或伪造授权。
- automation_navigate navigateTo 成功进入 `/consumer/pages/merchant-application/index`，currentPage 已核对；截图 `simulator-private-material-login-required.jpg`。此页面不是 preview 模式，未认证时材料上传受登录门禁保护。
- get_simulator_console `grep -i error` 返回空匹配；这不等于所有日志为空，也不证明真实后端接通。
- simulator_open_page 成功只代表编译触发；随后 currentPage 仍为 shell，因此改用明确 navigateTo，未把编译触发当页面验收。

## 尚缺的真实链路

当前产物仍使用此前静态编译检查的 `https://api.example.invalid`；这不是可用服务地址。根任务应以真实 HTTPS origin 重新 build，再刷新本窗口。现有前端严格要求 HTTPS，且 worktree urlCheck 保持 true。不能靠修改 dist、注入会话、伪造 authSetting 或关闭全局校验声称联调通过。

正常微信登录/手机号授权、后端真实身份提供方、会话及私有扫描/OSS 依赖须就绪；上传域名、证书、当前 AppID 接口权限与隐私指引应由真实环境验证。本机已授权状态不能覆盖新用户隐私弹窗和手机相册/相机权限。

源码当前 app.config 声明 `requiredPrivateInfos: ['chooseLocation']` 与 scope.userLocation 用途；位置使用 Taro.chooseLocation；上传使用 Taro.chooseMedia 原图单张，复制到 USER_DATA_PATH，再 Taro.uploadFile。地图结果仅填地址/坐标，不代替后端可信城市/地图校验。

## 最小执行步骤（后端就绪后）

1. 根任务构建真实 HTTPS origin，启用申请与私有上传开关。刷新本机模拟器，正常点击微信登录并完成手机号授权；读取真实会话后打开“成为商家”。不得注入测试 token 替代登录验收。
2. 点击门店照片入口，真实选图弹窗先取消：材料列表不应新增、无上传请求。再次选择一张不含真实个人信息的本地 JPEG/PNG 测试图片，确认后台收到 multipart file/purpose、源摘要和请求 UUID；成功只返回五字段 READY，无对象 key/URL。只记录脱敏状态，不输出 Bearer 或私有图片字节。
3. 后端/代理受控丢弃一次真实上传响应（不是 mock wx），页面应提示未确认。关闭并重开本项目，不清缓存；恢复提示对应材料类别，原入口“稍后处理”保留记录，“重试上传”发送同一 SHA256/长度/UUID，并取得同 assetId。
4. 对真实扫描拒绝的非敏感测试图片，确认服务返回422 PRIVATE_ASSET_REJECTED；页面允许明确重选，使用新副本/新 UUID。一般400、网络错、503不得解锁历史未知意图。
5. 点击地图原生选点，先取消再选择成都测试位置；取消不改地址，成功填入地址和坐标。选点成功不等于后端地图一致性校验已通过。
6. 执行正常退出并换另一个合法账号：旧用户未确认上传不显示且不能发送；回原账号后恢复同一意图。保持一个未确认上传时不得清空小程序数据。

## 必须由手机持有人完成的动作

若根任务另行协调预览：持有人微信扫码、相册选择/拍摄、系统权限及小程序隐私确认必须在设备上完成。iOS/Android 各至少执行真实选图成功/取消、相机方向、系统拒绝后恢复、结束进程后重试、网络切换。当前未生成预览二维码、未调用auto_preview/upload，不能声称手机验证完成。

微信官方 chooseMedia/chooseLocation/uploadFile/getPrivacySetting 文档本次 web 读取均返回不可重试访问错误；没有依据第三方转载补造平台批准事实。上述已确认项来自本机真实接口返回与项目代码。

## 2026-09-20 局域网开发联调补充

用户明确授权工具“不校验域名”及本地IP联调。新增共享 origin 校验器：只有 `NODE_ENV=development` 和 `PET_ALLOW_LOCAL_HTTP=true` 同时存在，才允许规范 RFC1918 IPv4、127/8、localhost 或 [::1] 的 HTTP origin；公开 HTTP、DNS伪装、userinfo、URL路径/查询/片段、八进制/整数IP、非法端口均拒绝。生产与默认构建仍要求 HTTPS。

116项前端测试及typecheck通过。以 `http://192.168.1.44:18080` + 显式开发开关完成构建（frontend-build-local-http.txt），确认产物没有旧example.invalid origin。仅worktree appid临时恢复真实值；用户 project.private.config.json 的 urlCheck=false 保持原样。本机刷新后shell渲染正常，截图wechat-local-http-start.jpg；runtime_info自动化连接超时仍需恢复，不能据截图声称登录或上传成功。等待root后端ready后再实际点击微信登录。

## 真实登录阶段（13:56）

root确认LAN后端READY后，使用真实automation_element_action点击#c-login成功。页面出现#c-phone“授权手机号并完成登录”，状态仍未登录，无c-auth-notice错误元素。截图wechat-live-login-result.jpg不含手机号/token。这证明正常wx.login流程已进入手机号授权阶段，尚不能声称已创建用户会话。未调用mock/注入token/伪造phoneCode，未抓取可能含Authorization的网络全量日志。

待用户在当前模拟器窗口点击“授权手机号并完成登录”并确认微信手机号授权，收到完成通知后继续实际选图上传。当前窗口与临时真实AppID保留。
