# MER-001 S7 前端恢复与接线

基线 `632ced7`，2026-09-20。工作目录为独立 `wt-mer001-integration`，未修改主目录配置，未提交或推送。

## 已实现

- 入驻页接入持久化 `ApplicationRecovery`。UI意图与ConsumerApi原请求共用按origin隔离的storage journal；仅在服务端session恢复后按当前用户读取。创建、保存、提交恢复原参数与requestId；恢复期间锁定资料，现有按钮显示“重试原操作”。不先读取新版本覆盖待决意图，也不在未知提交前再次保存。
- 解码成功的服务端receipt、下一阶段与原pending移除在同一次同步storage写入中落盘，覆盖“保存ACK已返回、提交尚未开始”和“提交ACK已确认、页面尚未消费”的中断窗口。无UI意图的历史pendingCommand也能恢复其原保存/提交参数。
- 400/401/403/404/422为既有明确拒绝；409/503、畸形回执、网络错误和request取消均保留原操作。未知写入不能通过本地取消丢弃。明确拒绝提交仍保留已确认保存receipt用于后续编辑。退出登录清除journal，迟到响应不能写入新身份。
- `PET_MERCHANT_APPLICATION_ENABLED=true`且`PET_C_API_ORIGIN`非空时，构建才接通批准OAS30申请repository及已开通城市目录。默认关闭；预览不写业务数据。私有材料上传仍使用明确不可用依赖，无猜造HTTP端点/城市ID/assetId。
- 用户确认首推成都后，城市目录接入`GET /api/v1/c/merchant-application-cities`（MINIAPP bearer；`success:true`；`data.items`）。严格接收`cityCode`/`cityName`两个字段：code为1–32位小写ASCII opaque key，name为1–64字符非空名称，拒绝类型错误、未知字段、重复code或name。页面沿用选择后保存code、显示name的行为；成都来自服务端配置，前端不硬编码城市选项或由地址派生cityCode。
- 协议repository增加原始explicit consent的读取/重试能力，保留原版本、hash与requestId。未创建无Figma节点的独立协议页。
- 真实页定位使用 `Taro.chooseLocation({})`，返回GCJ-02坐标并规范为最多7位小数的字符串；名称与详细地址合并，用户取消返回null，拒绝授权及不可用分别提示。不会由地址或微信选点结果生成平台cityCode。预览不调用真实地图。

## 微信定位接入条件

`app.config.ts`已声明`requiredPrivateInfos: ['chooseLocation']`和`scope.userLocation`用途“用于选择入驻店铺的位置与地址”。发布方还需在实际AppID后台的用户隐私保护指引声明店铺选点用途，并按微信平台要求完成接口权限/类目资格和隐私授权配置。代码不能代替后台开通，也未声称真机授权已验收。原生选点只提供用户选择的地址/坐标，后端地图校验、平台已开通城市目录是独立依赖。

依据：安装的Taro4.1.5类型声明 `types/api/location/index.d.ts`（chooseLocation返回GCJ-02经纬度、name/address）与 `types/taro.config.d.ts`（requiredPrivateInfos）；官方API：[wx.chooseLocation](https://developers.weixin.qq.com/miniprogram/dev/api/location/wx.chooseLocation.html)、[app.json权限声明](https://developers.weixin.qq.com/miniprogram/dev/reference/configuration/app.html)。Wechat技能外部地图skill入口访问超时，本轮没有安装来源不可核验的下载，也没有引入腾讯Web API/SDK。

## 验证

- `npm ci --no-audit --no-fund`成功，保留既有依赖版本；有依赖deprecated提示。
- `npm test`：100/100，0失败/跳过。新增覆盖丢失create/save/submit ACK后的新ConsumerApi/新页面flow实例恢复、保存ACK阶段原子落盘、已完成receipt恢复不发写入、旧journal迁移、明确/未知拒绝、请求cancel、logout迟到响应、伪造workspace身份、协议重试、原生定位返回边界，以及城市目录类型/重复项/封装/鉴权校验。
- `npm run typecheck`通过。最初已有测试mock的rest string参数不兼容新增可选checkpoint，改为准确三参数mock后通过。
- 默认关闭能力的`build:weapp`通过；显式启用能力、`https://integration.invalid`编译占位origin的构建通过，仅验证构建，没有访问该域名。
- `check:package`通过：启用构建主包745363 bytes，入驻分包50142 bytes，总2617646 bytes。既有profile/pet资源体积告警仍在；静态包检查不是上传/真机证据。
- Figma插件重新读取线上`bp2vpcjjA5vZbHvtKkA8wl / 132:862`。保留现有布局、素材和CSS；view只调整恢复阶段retry按钮禁用条件。未重做像素比对/模拟器截图，不升级S6的VIS结论。

## 未完成的完整链路条件

尚无真实小程序对新后端的端到端联调、真机位置权限验收；私有上传尚未提供可调用的批准前端契约。城市目录需服务端显式配置，否则503关闭；前端无默认城市兜底。已批准申请/协议HTTP能力可显式开启，但不因此宣称完整入驻/审核/签约业务闭环已经上线。城市增量后的100测试及typecheck已通过，最终构建由根任务统一调度。
