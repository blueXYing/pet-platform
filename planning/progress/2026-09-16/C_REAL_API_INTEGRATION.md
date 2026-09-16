# C 端登录、资料与宠物真实接口接入切片

基线：重新 fetch 后的 `origin/develop=3dfb253e642880daecc815976d373f69e6df5e21`（PR37）。分支 `codex/c-real-api-integration-20260916`；独立工作树 `C:/Users/Administrator/Desktop/wt-c-real-api`。只由当前任务实施，无子代理、其他开发会话或生产启用。

## 当前远端事实与证据分类

- PR37 已合入；develop 合并后 [CI 35074218261](https://github.com/blueXYing/pet-platform/actions/runs/35074218261) 的 backend、frontend-inventory、repository-policy、contract-smoke、web-build、miniapp-weapp-build 六项全部 success。它是本轮起点，不能代替本轮提交的 CI。
- PR36 评论确有真实微信 code、手机号绑定、会话、带会话 GET pets、登出及随后 401 的历史回执；本轮未重跑真实微信、不将历史冒烟当成页面业务验收。
- PR37 仅新增发号回归并纠正事实；未复现池化时区推测，未修改生产发号实现及 1 秒保护。参见 [ID closeout](ID_INTEGRATION_CLOSEOUT.md)。
- 主目录的 `frontend-miniapp/project.config.json` 用户修改保留；本轮仅在独立工作树临时使用其中公开 AppID 做模拟器验证，不提交本机配置或读取微信密钥。

## 页面与 API 对照

| 页面/操作 | 已实现并消费的端点 | 本切片边界 |
|---|---|---|
| 既有工程入口的微信登录 | POST `/c/auth/attempts`、POST `/c/auth/wechat-login` | 平台适配层 `Taro.login`；不是新设计的产品登录页 |
| 首次手机号授权 | POST `/c/account/phone-binding` | 原生 `getPhoneNumber` 用户手势回调；拒绝时不登录；保留原 attempt，不新增短信能力 |
| 会话/受保护请求/退出 | GET `/c/auth/session`、POST `/c/auth/logout` | Bearer；会话主体仅在服务端查询成功后发布；401 清理；不实现刷新、SMS、密码或商家认证 |
| 现有编辑资料 | GET/PUT `/c/profile` | 展示服务端昵称、头像 URL、脱敏手机号；本页仅提交昵称，成功文案明确“昵称已保存” |
| 宠物列表/详情 | GET `/c/pets`、GET `/c/pets/{petId}` | 页面重新进入从服务器读取；详情不再从列表数据猜测 |
| 现有新增/编辑表单 | POST `/c/pets`、PUT `/c/pets/{petId}` | 名称、品种、出生日期、性别、体重、健康备注；新增前原生选项确认 DOG/CAT/OTHER，创建后不改 petType |
| 删除 | DELETE `/c/pets/{petId}` | 原有卡片长按及原生确认；未知结果可用原 key 重试；重新入页仍可确认未决删除 |

所有路径均带 `/api/v1` 前缀。全部写入使用 UUID `X-Request-Id`；同一待确认操作保存原请求及参数，进程内 single flight，平台受控存储在重进页面/重启后保留待确认写入。未知结果锁定原参数，不自动换号重投。登出/失效清空业务缓存、草稿及会话；登出未知时仅保留专用于重放原退出请求的受控撤销记录，阻止换账号，成功后清除。令牌不进入 URL、普通业务缓存或日志。

现有宠物 PUT 会替换可空字段，因此保留表单未显示的 sterilizationStatus/vaccineStatus/avatarUrl/isDefault，不清空它们。C HTTP 请求解析器拒绝显式 null，可空字段通过省略表达，保持当前服务语义；未修改 API、Schema 或产品规则。真实模式没有任何 preview fallback，芯片、医疗记录、设计宠物照片只在 `preview=1` 路径使用。原布局、CSS、切图、公共底栏与两个普通分包不重设计。

## 契约缺口和后续门禁

1. 性别/签名沿用 [CCR-C002-PROFILE-001](../../ccr/CCR-C002-PROFILE-001.md)。产品决定已接受，但当前权威 HTTP、Schema、后端尚未同步；真实页禁用这两项，不暗示四项一起保存成功。
2. 芯片号、疫苗/驱虫记录名与日期、头像上传沿用 [CCR-W2-API-001 用户域](../../ccr/CCR-W2-API-001.md) 及 [宠物交接](../../issues/wave-2/C-002-pet-page/HANDOFF.md)。真实页显示未接通/空占位，不将无接口解释成医学记录为零。服务端已存头像 URL 可展示，但不提交临时本地路径。
3. 现有表单无宠物类型设计，新增必须明确选已批枚举，不默认 OTHER；本轮使用原生 action sheet，未新建页面或字段。此交互与真实空态需视觉/真机确认，不扩写成完整 VIS 通过。
4. C 当前实现未提供 refresh、attempt result 查询及 merchantEntry 等完整 AUTH 能力。本切片只用既有相同命令/key 回放，不新建协议，不把缺失权限推断成准入。

## 环境核查

- `CAuthConfiguration` 默认关闭，启用必须有 DataSource、PLAT-002 `SnowflakeIdGenerator`、微信 Provider、Redis 配置。真实 `WechatMiniApiProvider` 已实现，缺少配置会拒绝启动，不用替身补成生产 Bean。
- 当前可选发号装配仍位于 Admin 配置，并以 `PreviousJvmExitVerifier::rejecting` 为缺省。没有可信宿主退出证明、受审计节点初始化、高水位持久/恢复证据，不得开启真实业务；本轮未为凑联调启用 admin 或替换 rejecting。
- 默认 Flyway 目录仍只有迁移说明，不能把历史权威 SQL 当成正式环境已迁移。测试 fixture 在独立随机库加载 Schema06、幂等 SQL14、节点 SQL25；无正式库迁移。
- 会话使用易失 Redis；测试新建 Redis7.4，RDB/AOF 关闭，独立前缀。未读取或修改既有 `c-smoke` 数据/配置。
- 真实网络构建仅接受公开 HTTPS origin：`PET_C_API_ORIGIN=https://<已批准域名> npm run build:weapp`，由平台合法域名及 TLS 校验保护；未配置时明确失败。无秘密打包、HTTP 降级或关闭域名校验。

最小正式联调依赖：由相应 Owner 交付具备可信退出证明与节点恢复流程的 C 测试宿主、经审阅的必需迁移、独立易失会话存储，以及已授权微信 Provider/HTTPS 域名装配，再用真实微信 code/手机号和页面操作复测。生产宿主集成、全平台迁移不在本切片。

## 验证

本地全量后端最终通过：261项JUnit（34套件，含22项ArchUnit），0失败/错误/跳过；前端64项单元通过，类型检查、微信构建/包体、13项架构负例与82项契约测试通过。详细结果见同目录 `c-integration-evidence/results.json`。证据按以下类别区分：

- 前端单元：纯 TypeScript + 注入 transport，不能称真实后端。
- 后端链路：同一前端 ConsumerApi/Repository 经 loopback HTTP 调用真实 Boot/MySQL/Redis；微信固定码 Provider 替身；发号为严格限定此测试随机库 virgin node 的既有 fixture，不是生产宿主证明。
- 微信运行：真实开发工具编译/页面事件/布局；预览回归和 real 页面路径的 wx Provider doubles 分开记录；不等真机微信授权。
- 真实微信/正式环境整链：本轮未执行，环境门禁保留。

最终模拟器：real页面路径的5组回归通过（Provider doubles）；显式预览14组回归通过。分别见 `real-mode-final/report.json` 与 `preview-final-source/platform.json`。首次物理坐标tap在键盘/导航上下文中命中了底栏，保留 `native-hit-test-failure.json`；最终按既有回调测试口径使用指定控件trigger/input，不以此掩盖物理键盘/点击尚未验证的限制。

复验入口：先 `npm --prefix frontend-miniapp ci`，再运行 typecheck/test/build:weapp/check:package；后端按 `.github/workflows/ci.yml` 准备全部隔离 MySQL 测试变量与易失 Redis，执行 `mvn -B -f backend/pom.xml clean verify`。新增 CFrontendHttpTest 自动启动随机库 Boot 并调用前端 `backend-chain.ts`，不需要手工装配常驻服务。微信回归使用 `real-mode-platform.cjs`（公开测试 origin `https://c-integration.invalid`、显式 wx mocks）与 `pet-platform.cjs`；mock 响应经 JSON 序列化，执行后恢复平台 API、删除仅此测试 origin 的凭据。控件用 trigger/input 回调验证，物理触摸、键盘与真机授权没有因此验收。

资料卡片的原生几何已与既有402设计尺寸按实际390窗口比例核对，6项最大偏差小于0.074 CSS px，见 `c-integration-evidence/profile-geometry.json`。这是几何证据，不是自行设定的像素相似率通过线；图片、真机字形、未提供设计状态与完整VIS验收仍单独记录。

宠物列表、详情、弟弟/妹妹表单已在390窗口采集参考画布、原始滚动截图、叠图与差分，见 `c-integration-evidence/visual/`。详情参照官方帧；列表/表单参照既有spec合成图。实际截图为开发工具面板缩放后图像，按已记录比例还原到CSS画布；顶部系统区与被390视口裁掉的右12列不算已验证。已人工查看并排图，保留此前统一底栏、添加标题、年龄派生及字体光栅差异，不把这些指标标为完整VIS通过。414窗口与真机本轮未重跑。修正了旧采集工具“仅reference模式却列出全部device状态”和“实际重采样却标crop-only”的证据描述；无页面CSS或切图改动。

失败记录：Docker 首轮全量后端在既有 AsyncTaskWorkerMySqlTest 子进程 15 秒等待超时，后续模块当轮未执行。独立 C HTTP 首轮暴露前端显式 null 导致 400，已修正；新增链路最初共用测试类的库触发旧测试全库计数断言，已改为独立测试类/随机库。预览平台快速切页先后出现 `no such element` 和缓存page handle不在栈顶，测试改为读取currentPage并等待目标selector；编译启动异步及旧bundle缓存另作清理后复验。real页面路径替身最初复用对象引用，导致React视图不重绘；wire记录确认PUT/GET已返回新名称，改为每次JSON序列化响应以模拟真实HTTP边界，保留失败证据。未放宽生产/测试超时或跳过断言。

完整 AUTH-001、USR-001、PLAT-002、C-002 与相关 CCR 均不标 DONE/RESOLVED；不合并 PR，不启动商家、交易、服务、排期或全量 MyBatis 迁移。

PR：[38](https://github.com/blueXYing/pet-platform/pull/38)，未合并。最终head/CI与审阅检查以PR为准；本轮未执行正式环境完整真实微信业务闭环。

阶段结论：已批字段的接入代码与隔离链路可供审阅；正式真实微信页面业务闭环、缺口字段及完整VIS/真机验收尚未达成。下一步只需先由对应Owner提供满足上述门禁的测试宿主/迁移/Provider，再重跑相同页面链路；契约缺口按既有CCR同步，不扩大本PR。
