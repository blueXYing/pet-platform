# AUTH-001：真实运营登录切片

状态：**COMPONENT_REVIEW_CANDIDATE / PRODUCTION_ENABLEMENT_BLOCKED**。本阶段只做运营账号密码登录、会话及本人权限查询，不做商家/短信/微信接入，不开放账号管理或其他业务动作。默认不启用Auth、不迁移、不创建账号。D1/D2、取消MFA和存储A/B1/C均已接受，不重复审批。最终固定head/CI及真实测试计数以PR17最新回执为准，候选文档本身不宣称已生产启用。

## 用户先看这里

本次要交付一条真能验证账号密码、建立登录会话、查看本人角色和数据范围的后端链路。断网后短时找回登录结果使用已接受B1，可能不足60秒；它不改变登录会话本身的30分钟空闲期限。没有额外身份认证或第二管理员。

当前只有本人会话操作，没有已实现的订单/退款治理功能，因此权限响应会显示真实角色与范围，业务动作列表为空，不虚构全平台功能已上线。前端未修改。完整集成验收仍在运行；本页最终会记录实际通过与未验收范围。

## 来源、依赖与范围

分支codex/auth-001-web-login，原ff85工作区。已从ab351ee继承固定Hutool组件58b79efd，随后快进到其人工合并develop `3a35432798550c7c2359313309d8fb196645595f`，没有重复cherry-pick或修改S2/common/task-core/root POM。原14份AUTH来源保留。

BackendCore唯一写：admin-api五个DTO/查询接口；admin-biz实际服务/SQL事务/密码/AEAD/Redis缓存/权限求值/审计投递及模块必要依赖；boot十接口、默认关闭的配置/安全链及离线维护入口；docs26及独立V26镜像；07/10/11/12的B1与Admin字段映射；本实施交接。QA独占新增admin-biz/boot测试和CI backend的Redis/AUTH环境、必要合同状态回归。其他角色仅只读审阅。

实际新增数据仍为17张admin表；`risk_lookup_digest`用于requirements与登录一致判断，`actor_reference`区分可信运维主体和恢复目标，captcha图像用于同key返回同一图形题。团队核字段和SQL，不把这些工程选择包装成产品重新裁决。

## 安全与迁移开关

- `pet.auth.admin.enabled=false`默认；迁移另需`migration-enabled=true`及明确`migration-database`，此候选仅允许新隔离`auth001_*`库。SQL放`db/admin-auth-migration/V26__admin_auth.sql`，不在当前默认`db/migration`路径。与docs/03-database/26-Admin-Auth-Schema-v0.1.sql逐字一致，无生产执行授权。
- 主认证后才签发Web Bearer；attempt后续要求秘密、绑定cookie、可信Origin同时正确。其他未实现路径默认拒绝，无Spring自动默认账号。输入未知字段、null、重复JSON键拒绝；密码/token/密钥的承载对象toString已脱敏。
- Argon2id使用已接受64MiB/3/1；Spring Security7.1.1由Boot4.1.1 BOM管理，BouncyCastle1.86固定，Lettuce7.5.2由BOM管理。实际参数必须由测试验证，不换成默认hash算法。
- 密钥分别由`key-id/mac-key-base64/encryption-key-base64`注入，独立256位；不可写Git/数据库/普通日志。专用`redis-host/redis-port/redis-username/redis-password/cache-prefix`不得复用业务Redis；每次缓存操作检查RDB/AOF关闭，不只换namespace。
- B1以DB提交前锚点固定60秒，commit确认后只NX发布剩余TTL；缓存或密钥丢失不重签，返回503；过期/撤销401。缓存I/O后重新读当前权威身份/结果，不沿用旧RR快照。
- 技术限流：匿名attempt每来源IP最多30次/60秒、每attempt最多10个不同图形题；原密码5次需captcha、10次/15分钟锁15分钟保持。Argon2最多2个并行工作，锁定预检在昂贵hash之前，事务内再核。

## 初始化、恢复与审计

仅宿主受控离线命令可初始化/恢复：`pet.auth.admin.maintenance=true`且`spring.main.web-application-type=none`，交互私有控制台输入密码，不接受命令行明文密码或公开初始化HTTP。初始化只在空账号且bootstrap未完成时创建一名超管；恢复记实际HOST用户/PID与目标operator分离，维护栅栏关闭在线认证，递增凭据/generation/权限版本，不恢复旧token。备份和生产宿主恢复仍需部署核验，不能凭此候选自动接管生产库。

审计意图与认证事实同事务，独立投递器将其送到可观察文件组件sink。文件按帧校验和哈希链记录，并有持久checkpoint校验已确认长度/尾摘要；新写及幂等命中均force后才ACK。删除、清零、合法边界截断、损坏、写后ACK丢失都需要测试。该组件不是生产不可篡改台账；真实不可变介质、权限/备份/保留策略仍是生产门禁。台账失败不影响已成功持久化意图的登录，恢复后按同ID重投，不以内存去重假装可靠。

## 发号及实际环境门禁

只注入已合并HutoolSnowflakeIdProvider，不实现第二套发号器。生产PreviousJvmExitVerifier缺可信实现时默认拒绝，不提供always-true Bean，不自动安装SQL25或启用node。Spring启动成功不是生产发号已可用；Auth默认关闭，显式启用仍需真实node/宿主证据/独立密钥和迁移窗口。

本机Java21可编译；新隔离MySQL位于`D:/Temp/auth001-web-mysql-ff85`/33443，不触旧被策略拒绝目录或MySQL84业务服务。本机无原生Redis，Docker连接不可用，已配置SSH连接也关闭；因此本地真实Redis/HTTP组合用例明确NOT_EXECUTED。CI使用私有MySQL+Redis7.4，Redis关闭RDB/AOF，所有随机库/键只清理本次成功创建的对象，无assume/skip。

## 当前验证

主代码及测试编译通过；本地15个组件/默认关闭测试、22架构规则测试及82个Python合同回归通过，真实MySQL+Redis/HTTP组合本地仍未执行。首轮CI34834432080真实运行了S2/Task与新admin测试，发现同key已提交结果尚未发布的并发窗口；已增加最多250ms只等待同一不可变cacheRef，不重签/不延时。QA加入确定性100ms延迟发布和缓存读取期间退出/重登反例；原并发和503断言不放宽。

HTTP层已去除持久化异常引用，使用直接响应包及Servlet状态/头设置，遵守原架构门禁而非修改测试规则。完整MySQL+Redis/HTTP和154原S2/Worker组合必须在最终CI实际运行且零skip；PR17最新回执记录结果，旧失败和本地编译不作为通过证明。未merge，生产Enablement与完整AUTH其余范围仍非DONE。之后用户只审批最终PR或真正新增CCR，不重复已接受方案。
