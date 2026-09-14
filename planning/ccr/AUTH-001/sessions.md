# AUTH-001 会话提案

**PROPOSED / PENDING_REVIEW · AUTH-001-draft-v1**。所有新增路径、字段、枚举、超时与存储选择均为技术提案。既有产品约束及待输入见[来源](sources-and-gaps.md)，商家资格见[准入](admission.md)。本文不生成SDK。

## 1. 已定规则与提案边界

SSOT允许微信快捷登录、手机验证码、手机密码；首次验证码/微信注册不生成密码，未设密码统一“账号或密码错误”。C PRD§5.1.1要求微信授权及手机号快捷验证均完成才算微信登录成功；同微信不重复建号，手机号已绑定其他账号联系客服、不自动合并。拒绝授权者可公开浏览，授权页面可留页重试；这不赋予预约、发帖等写权。短信/密码登录是SSOT独立入口，不能因微信流程要求而强制所有入口先微信授权。

允许SQL中存在初始无手机号身份记录，不将其视为可用业务会话。建议微信流程先持认证尝试，完整证明后创建/关联账号；若已有历史无手机号记录，仅通过真实验证完成该记录，不创建第二账号。手机号换绑按C PRD设置页通过微信手机号快捷验证，冲突仍联系客服，不自动迁移订单、身份、邀请关系。邀请规则由原Owner处理，认证只提供真实首次完成事实。

会话只证明身份，不携带永久动作授权。小程序和运营Web使用不同audience、凭据命名空间、存储与校验入口，交叉使用一律401。`CommandContext`保持requestId/traceId/operatorType/operatorId/source；可信主体由服务端适配器解析，不能将工作区、权限版本或客户端ID塞入Context扩权。

## 2. 公共数据类型与响应

沿用HTTP10 ApiResponse：`{code,message,data,traceId}`；成功code=`SUCCESS`，错误data=null，traceId按本次请求生成/传递。所有ID为23号正Long十进制String；opaque token、nonce、code不是业务ID。日期为带偏移ISO-8601，输出固定三位毫秒；输入整数秒合法，非零亚毫秒拒绝。未知字段/重复JSON键/错误类型拒绝400；未注明可空的字段必填且非null；password/code/token不trim、不进入普通日志。分页page为正整数默认1，pageSize为1–100整数默认20，非法值400；Page响应items数组、page/pageSize整数、total非负整数（超过JS安全整数时须先升级契约，不截断），超末页空items但total不变；排序由各查询固定，不接SQL字段名。

| 类型 / 字段 | 约束与权威来源 |
|---|---|
| SessionGrant.accessToken | 256位以上随机opaque凭据，仅认证端通过HTTPS发给持有效认证尝试的客户端；示例只用不可用占位符 |
| tokenType | 固定Bearer |
| expiresAt | 服务端时钟计算；小程序建议15分钟，Web受30分钟无操作及账号session generation约束 |
| refreshToken / refreshExpiresAt | 仅小程序有；随机独立秘密，建议30天绝对有效期、不因刷新延长；Web无此字段 |
| sessionId / userId | String；会话记录及完成验证的用户账号ID；Web改为operatorId且不返回userId |
| audience | MINIAPP或ADMIN_WEB，来自服务端配置，不接受请求指定以升级 |
| CurrentSession | sessionId、userId或operatorId、audience、expiresAt；小程序另有phoneMasked(String)、merchantEntry(见准入)；Web另有idleExpiresAt、mfaVerified(Boolean) |
| AuthAttempt | 创建响应含attemptId(String)、attemptToken(秘密)、expiresAt、nextStep；后续进度响应不再含attemptToken。建议尝试绝对有效10分钟，MFA challenge最多5分钟且不超过attempt期限；nextStep=PROVE_IDENTITY/VERIFY_PHONE/MFA/COMPLETED。不是业务Bearer，不返回商家授权 |
| phone | 依原PRD11位ASCII手机号，提案模式`^1[0-9]{10}$`；真实证明来自微信/SMS，不信任输入号码；不接受任意国际号码作为隐式扩展 |

所有敏感响应`Cache-Control: no-store`，禁止缓存access/refresh/attempt秘密到通用业务查询缓存、URL、遥测。Web access只存内存；同源Secure/HttpOnly/SameSite=Strict绑定cookie配合Origin校验保护MFA/刷新会话交互，cookie本身不是业务Bearer。跨域部署须另行评审CORS/CSRF，不假设配置已存在。小程序使用平台受控本地存储，退出清理；两端退出不是注销账号。

## 3. 路径、请求与状态

下表请求body只列字段名，精确类型取本节及§2。除GET外要求23号完整UUID `X-Request-Id`。现有路径仅“已列用途”，响应仍是新提案。请求不得传operatorId/staffId作为授权。

| HTTP路径 | 已有/新增 | 请求 | 成功与认证要求 |
|---|---|---|---|
| POST /api/v1/c/auth/attempts | 新 | `{purpose}`；枚举WECHAT_LOGIN/SMS_LOGIN/PASSWORD_LOGIN/PASSWORD_RESET | 匿名创建仅限认证的尝试，201 AuthAttempt；限流，不产生用户/商家事实 |
| POST /api/v1/c/auth/wechat-login | 既有 | `{attemptId,wechatCode,phoneCode?}`；code为1–512字符opaque，空串拒绝 | `X-Auth-Attempt`秘密；服务端交换真实微信证明；缺手机号验证200 nextStep=VERIFY_PHONE且无SessionGrant，完整才200 SessionGrant |
| POST /api/v1/c/auth/sms-codes | 新 | `{attemptId,phone,purpose}`；purpose=LOGIN/RESET_PASSWORD | X-Auth-Attempt；200 receipt={challengeId,expiresAt,resendAfterAt}仅表示Provider确认接受发送，不返回验证码；未知发送状态503+原attempt原key查询，不自动重发 |
| POST /api/v1/c/auth/sms-login | 既有 | `{attemptId,challengeId,phone,code}`，code六位ASCII | X-Auth-Attempt，真实一次性SMS证明；200 SessionGrant；账号不存在时按首次验证码注册且不设密码 |
| POST /api/v1/c/auth/password-login | 既有 | `{attemptId,phone,password}`；password 8–64字符为本提案技术建议，不改变已存密码验证兼容策略 | X-Auth-Attempt；200 SessionGrant；无密码/错密/不存在统一401同文案，不泄露password_enabled |
| POST /api/v1/c/account/password/reset | 既有 | `{attemptId,challengeId,phone,code,newPassword}`；newPassword与提案密码8–64字符约束一致 | X-Auth-Attempt且attempt目的PASSWORD_RESET、challenge目的RESET_PASSWORD；200 `{updated:true}`，成功撤销该用户旧access/refresh；不自动登录，不产生平台支付密码 |
| POST /api/v1/c/account/phone-binding | 新 | `{phoneCode,attemptId?}`；phoneCode同§3 code约束 | MINIAPP Bearer或已验证微信的X-Auth-Attempt二选一；attempt链路attemptId必填，Bearer链路禁止attemptId；换绑200 `{phoneMasked}`；初次完整认证200 SessionGrant并将原attempt置COMPLETED |
| GET /api/v1/c/auth/session | 新 | 无body | MINIAPP Bearer，200 CurrentSession |
| POST /api/v1/c/auth/refresh | 新 | `{refreshToken}` | 专用刷新秘密，200 SessionGrant；不接受ADMIN_WEB凭据 |
| POST /api/v1/c/auth/logout | 新 | `{}` | 当前MINIAPP Bearer，200 `{loggedOut:true}`；同会话重复退出仍安全，不返回业务数据 |
| GET /api/v1/c/auth/attempts/{attemptId}/result | 新 | 无body；可选requestId查询参数限定已提交命令的UUID | X-Auth-Attempt；200 AttemptResult（下文）；未知/已过期401/503按§5，不能凭attemptId取令牌 |
| GET /api/v1/c/auth/attempts/{attemptId}/sms-intents/{requestId} | 新 | requestId是原发送命令UUID，不是此次GET新key；X-Auth-Attempt | 200 SmsIntentStatus，状态联合见下文；权限/依赖读取失败用401/503且data=null |

短信默认要求Provider确认接受发送后200，否则503并查原发送意图；不把受理当短信已送达或认证成功。投递成功也不等验证码验证成功。challengeId为String；建议SMS验证码6位/5分钟，重发间隔60秒，最多5次错误后该challenge失效；实际Provider上限若更严须按能力评审，不静默放宽。purpose必须和attempt创建目的相符，不能用LOGIN验证码重置密码。

AttemptResult必填attemptId、nextStep、expiresAt；可选commandResult仅当请求requestId属于该attempt已绑定命令且结果允许当前读取时返回，结构`{requestId,kind,data}`，kind=SESSION_GRANT/PASSWORD_RESET/SMS_ACCEPTED/MFA_CHALLENGE，data对应上表/下表成功data。没有指定requestId只返回流程进度，不能枚举所有敏感历史结果。未完成且无确定结果返回503；VERIFY_PHONE进度可200；SESSION_GRANT秘密恢复窗口关闭401，不再次签发；PASSWORD_RESET最小updated事实在attempt有效期内仍可读取。接收phoneCode是新的明确验证步骤，用新requestId，不修改原wechat-login同key已绑定的参数。Bearer换绑不属于attempt结果，按原phone-binding接口/原key/原参数及当前有效Bearer重放最小phoneMasked回执。

用途绑定矩阵：WECHAT_LOGIN仅wechat-login/初绑；SMS_LOGIN仅sms-codes(purpose=LOGIN)/sms-login；PASSWORD_LOGIN仅password-login；PASSWORD_RESET仅sms-codes(purpose=RESET_PASSWORD)/password-reset。Web attempt由其独立路径固定ADMIN_LOGIN，仅login/captcha/MFA。SMS challenge同时绑定attemptId、经过服务端规范化的同一phone、purpose及有效期，不能跨attempt/phone/purpose复用；不匹配统一验证失败。发送POST未定或Provider拒绝均503，但客户端从上述sms-intents读取明确机器状态，不解析中文决定重发。

SmsIntentStatus=`{requestId,status,nextAction,challengeId?,expiresAt?,resendAfterAt?}`。status=PENDING/UNKNOWN时nextAction=QUERY_SAME_REQUEST，后三字段禁止返回；ACCEPTED时nextAction=ENTER_CODE且后三字段必填String ID/时间（原有效期，不续期）；REJECTED时nextAction=START_NEW_ATTEMPT，后三字段禁止。REJECTED只表示Provider明确拒绝且无成功投递事实，不表示账号不存在；UNKNOWN不能变为REJECTED。200 UNKNOWN表示数据库已知意图未知，查询数据库失败则503/data=null。禁止客户端用新attempt绕过未决原发送意图：服务端按受保护phone/purpose及有效窗口关联未决发送并限流，未知须查证或人工处理，不自动二次发送。验证码已过期的ACCEPTED可读原expiresAt但不能继续验证，新的发送须明确新意图并受间隔约束。

phone-binding初次绑定与换绑必须分目的和已验证主体；Bearer换绑必须证明当前会话及新的微信手机号授权，若证明的微信身份与当前用户关联不一致拒绝；不允许以手机相同静默合并。号码被其他账号占用返回409 COMMON_CONFLICT、统一联系客服，data=null，不返回对方ID或信息。绑定/重置与邀请/注销业务没有跨模块大事务；这些下游副作用须原Owner设计事实交付。

## 4. 会话生命周期与Web

推荐opaque会话存服务端可撤销记录，token只存不可逆摘要。Redis可缓存但不能在权威状态不可查时放行。小程序access到期可用有效refresh；refresh每次轮换，旧refresh仅在同requestId/同参数/同设备绑定的60秒响应恢复窗口内返回同一当前grant；不同key再次使用已消费refresh拒绝并撤销该refresh family。窗口外旧key不能重新发新令牌，要求重新登录；不以全局requestId缓存永久明文令牌。并发刷新由客户端single-flight避免；重放检测细节、加密回执、秘密销毁与墓碑见§6。

本版“同设备绑定”不采用客户端deviceId：小程序以该refresh秘密所绑定的服务端session/family为认证边界；Web使用服务端随机绑定cookie，不承诺硬件设备身份。refresh恢复直接重试refresh接口，携原refreshToken和原X-Request-Id，不走AuthAttempt查询。60秒从首次轮换提交起算，读取不续期。

轮换与logout在同一family/session记录原子CAS排序，并核账号generation及ACTIVE；密码重置/账号禁用递增账号generation并与该Owner的签发/轮换检查串行化。撤销先提交，后轮换不能生成可用token；轮换先提交，退出撤销该family新旧所有凭据。禁用/重置后旧generation签发必须失败。Web完整认证最终提交也核当前账号/MFA要求/generation，防止密码验证后授权变化绕过MFA；新会话签发及踢旧generation原子提交。事务/唯一键/跨记录锁顺序须后续Schema审查，不跨biz实施。

Web复用Bearer标准，但独立以下新增提案接口：

| HTTP路径 | body / 约束 | 响应 |
|---|---|---|
| POST /api/v1/admin/auth/attempts | `{}`；匿名限流，X-Request-Id | 201 AuthAttempt及受限绑定cookie |
| POST /api/v1/admin/auth/login | `{attemptId,account,password,captchaProof?}`；X-Auth-Attempt+绑定cookie/Origin；account=1–128字符的手机/邮箱/工号；password8–64，不归一密码；服务端按登记的账号别名规则查找 | 非高权限验证完成200 Web SessionGrant；高权限200 `{attemptId,nextStep:"MFA",challengeId,expiresAt,deliveryHint}`，绝无业务Bearer；deliveryHint仅实际通道存在时返回脱敏值 |
| POST /api/v1/admin/auth/mfa/verify | `{attemptId,challengeId,code}`；6位ASCII，X-Auth-Attempt+绑定cookie | 200 Web SessionGrant，服务端确认后创建新generation并撤销同账号旧Web会话 |
| POST /api/v1/admin/auth/mfa/resend | `{attemptId,challengeId}`；X-Auth-Attempt+绑定cookie/Origin，已验证密码的attempt未过期且满足60秒间隔 | 200新的MFA challenge结构同login分支，增加resendAfterAt；同key不再次发送，替换成功后旧challenge失效；投递未知不自动重发 |
| GET /api/v1/admin/auth/attempts/{attemptId}/requirements | X-Auth-Attempt+绑定cookie/Origin | 200 `{requiredVerification}`，枚举NONE/CAPTCHA/MFA；状态按同形失败策略计算，未知账号也有同类限流 |
| POST /api/v1/admin/auth/captcha/challenges | `{attemptId}`；X-Auth-Attempt+绑定cookie/Origin | 200 `{captchaId,imageDataUrl,expiresAt}`；captchaId String，imageDataUrl受限PNG data URL≤256KiB，120秒；实际生成器未装配503 |
| POST /api/v1/admin/auth/captcha/verify | `{attemptId,captchaId,answer}`；answer1–32字符，X-Auth-Attempt+绑定cookie/Origin | 200 `{captchaProof,expiresAt}`；proof为opaque120秒一次性、绑定attempt，供login条件必填字段，不是业务权限 |
| GET /api/v1/admin/auth/session | ADMIN_WEB Bearer | 200 CurrentSession及当前authzVersion(String版本标签，不是授权凭证) |
| GET /api/v1/admin/auth/attempts/{attemptId}/result | X-Auth-Attempt+绑定cookie/Origin；可选requestId同小程序 | 200 AttemptResult，仅本Web attempt，不接受小程序attempt |
| POST /api/v1/admin/auth/logout | `{}`；ADMIN_WEB Bearer | 200 `{loggedOut:true}` |
| POST /api/v1/admin/auth/activity | `{}`；ADMIN_WEB Bearer、X-Request-Id | 200 `{idleExpiresAt}`；只有前台真实交互才调用；后台轮询、自动刷新、旧key重放不能延长空闲 |

已有30分钟无操作由服务端lastInteractiveAt执行，前端计时仅提示。activity仅可在未过期时延后，重复key不能重复延后；查询轮询不重置。Web access有效至当前idleExpiresAt并以每次会话事实核验为准，activity只延长同一有效会话的服务器期限，业务Bearer不依JWT静态exp；响应expiresAt是当时期限快照，不是允许离线验证的永久声明。会话查询返回最新期限。页面刷新丢内存Bearer则重新登录，不从cookie生成业务权限。

账号禁用/密码重置立即使后续身份检查失效；权限变更刷新authzVersion；高权限新增到原未MFA会话时撤销该Web会话，重登完成MFA后才可用。MFA判定提案：有效roleCode属于REVIEWER/OPERATIONS_ADMIN/FINANCE_READER/PLATFORM_SUPER_ADMIN，或任何当前有效动作的requiresMfa=true（高风险/敏感动作目录必须true），则必需MFA。展示名不参与；超管必需MFA。角色停用撤销对应授权，是否完全退出按账号身份仍有效与否区分。

建议Web密码连续5次失败需图形验证码，10次/15分钟暂锁15分钟；未知账号采用等形限流避免枚举，同时以IP/尝试防滥用。C密码路径本版仅429频控，不套用Web图形验证码要求；其失败阈值建议10次/15分钟锁15分钟。MFA每challenge5次错误失效，另按账号+因素累计10次/15分钟锁15分钟，新attempt或重发不重置累计计数；重发间隔60秒且使旧challenge失效。MFA建议密码验证后发随机6位OTP至管理员预先登记并验证的手机号，5分钟有效、一次消费；投递/验真通道未配置503不跳过。普通运维不得从日志查看OTP。账号可用邮箱/工号登录，但MFA投递目标不由此次请求指定。阈值/短信建议是技术提案，实际Provider、验证码证明和单运营因素绑定/恢复仍BLOCKED；不能用固定验证码宣称接入。没有自助找回或第二管理员审批。

## 5. 错误、刷新和页面恢复

| 场景 | HTTP/code | 客户端处理 |
|---|---|---|
| 当前会话的受保护小程序请求401 | 401 COMMON_UNAUTHORIZED | 先暂停业务并失效旧epoch；若持refresh则仅保留该秘密single-flight刷新一次，失败才清该端全部凭据；无refresh直接清。旧session/epoch的401丢弃，不处理新会话 |
| 小程序refresh成功 | 200 | GET按新上下文重查；写仅以原key/原参数显式重试，不能未知结果自动换UUID；不根据401中文猜到期/撤销，刷新端核真实状态 |
| Web无效/过期/撤销或跨audience凭据；小程序refresh被拒 | 401 COMMON_UNAUTHORIZED | 清该端凭据/缓存/epoch；仅保留安全导航意图，重认证及重新准入后恢复；不清另一端 |
| 普通动作/范围被撤销 | 403 COMMON_FORBIDDEN | 不自动登出同微信用户；清相关授权缓存、废弃在途结果并刷新准入/权限；无权页面安全返回 |
| 他人资源/认证attempt枚举 | 404 COMMON_NOT_FOUND或不透露存在性的401 | 固定策略，message不披露对象存在；不返回旧data |
| 身份、权限或Provider事实查询失败 | 503 COMMON_DEPENDENCY_UNAVAILABLE | 关闭受保护路径，保留安全导航，显示重试；不是401、不是签约失败/成功 |
| 验证码/密码错误、无密码、不存在账号 | 401 COMMON_UNAUTHORIZED | 一致“账号或密码错误”或统一“验证失败”，不披露哪一项存在 |
| 非法参数/非法ID | 400 COMMON_INVALID_ARGUMENT | 修正输入，不自动重试 |
| 同key异参 / 并发忙 | 409 IDEMPOTENCY_KEY_CONFLICT / COMMON_CONFLICT | 前者不可自动重试；后者仅明确忙语义原key稍后重试 |
| 频控 | 429 COMMON_RATE_LIMITED | 到达服务端Retry-After再试，不绕过验证码/锁定 |

此表已逐字核对12号通用码登记，不新增全局错误码。错误message用于展示，不能让客户端解析中文决定授权；准入业务原因码通过成功的资格查询返回，详见准入文档。

## 6. 认证写入、秘密回执与公共幂等

认证前没有USER主体。推荐认证模块专属AuthAttempt幂等适配器，不假冒SYSTEM、不扩OperatorType，不直接接公共业务执行器。全部写接口保留UUID和同参/异参约束；认证尝试只承担受限证明交换。此为对23号“匿名映射由AUTH负责”的具体提案，须Contract Owner批准及后续逐命令实现设计，S1检查工具不提供该能力。

1. attempts创建先持久绑定requestId和用途，生成不可预测attemptId/attemptToken；限流依据不是业务权限。无业务账号写入；原请求重复不再新建attempt。首次响应秘密若丢失，原key只返回409重新开始提示，不能凭requestId重放秘密；用户显式新尝试可换key，旧尝试过期。不能通过此例外类推业务写入换key。
2. 后续调用须attemptId+attemptToken（Web另需cookie/Origin），服务端定位已验证身份；requestId只去重，不是秘密。微信/SMS一次性凭据在同attempt同key绑定后不可换参复用；Provider交换未知保留原意图查证，不能凭网络失败新建用户。没有上游查证能力的短期凭据失败应重新证明，持久业务唯一约束仍避免重复账号。
3. 完整身份验证成功后，账号创建/绑定、会话事实和最小完成回执在所属模块本地事务内一次提交；Phone唯一、微信(appId,openId)唯一均由DB兜底。若两个已存在账号证明相冲突拒绝，不自动合并。登录成功日志按原PRD故障降级告警，不能因日志不可用把身份猜成功。
4. 最小永久回执不含原密码、OTP、wechatCode、access/refresh秘密，仅保存结果标识与安全墓碑；短期秘密响应加密、最小读取权限，首次成功提交后60秒销毁密文，读取不续期。SESSION_GRANT窗口内持原attempt秘密同key且新签发会话仍有效才可恢复；窗口外/撤销401，不再次签发。PASSWORD_RESET只允许仍有效受限attempt取得原key最小updated事实，不要求旧登录会话有效或一次性证明尚未消费；不返回新的登录能力，attempt到期后401。PHONE_BOUND换绑通过当前有效Bearer和原命令重放，不经attempt。MFA_CHALLENGE只含不敏感投递提示与期限，重放不能重发/延期或恢复失效challenge。新认证须新证明。参数等值使用受保护摘要，不保存明文密码或易枚举OTP裸hash；采用服务端密钥HMAC及最小必要加密记录，密钥策略为实现门禁。
5. refresh、密码重置、绑定、MFA验证均有自身稳定namespace和可信主体/attempt作用域；完成后重放仍核当前身份/敏感可读性。不能重放密码或旧MFA状态来获得新授权；重置成功不把重置码再次当有效一次性证明。

逐命令持久化、Provider未知恢复、短期加密/清载荷仍须后续设计和组件验证；这份协议提案没有实现公共幂等/S2，不能宣称登录重试已验收。
