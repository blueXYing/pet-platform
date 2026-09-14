# AUTH-001 最小Web存储与恢复设计提案

批准回执（2026-09-14）：用户明确回复“可以 按照你描述的进行”，接受PR15/head `d42fdf3c260b612ef7ee4786f442b13bcb58000c`、tree `ac58dedd3ac18f425779da0ced38da165f94bb97` 中已交付的存储A、B1、C；B0未选。仅记录已批准范围，不改表结构、参数、样例或权威API，不执行SQL/实现。内部尚未完整字段/签名仍待具体审阅；PR15合并未授权，继续Draft。

状态：**STORAGE_PROPOSAL_ACCEPTED / PENDING_STORAGE_CONTRACT_SYNC**。仅planning Markdown，无DDL/迁移/业务代码。D1/D2与取消MFA已接受；本文已交付的A/B1/C物理存储、密码算法参数、秘密介质与恢复机制方案已接受；实际契约同步、迁移与实现尚未执行，B0未选。原依据见[来源](sources-and-gaps.md)，字段边界见[同步交接](contract-sync-handoff.md)。

## 1. 已接受的A、B1、C

| 已批准决定 | 已接受方案 | 已说明的影响与边界 |
|---|---|---|
| 存储A 权威存储与授权版本 | MySQL按pet-admin/pet-user Owner隔离；V1运营授权写由单行revision串行，scope/role/extra授予与revision同事务 | 低频授权写易验证，不依赖异步扇出撤权；读请求可能因无关账号授权变化多失效一次。表列/索引与恢复epoch属于新方案 |
| 存储B 密码与短期秘密 | Argon2id初始64MiB/3迭代/并行1、独立用途密钥；grant仅加密存放不持久化专用缓存，数据库留最小结果/墓碑 | 推荐提交前数据库锚点+60秒，可能短于实际commit后完整60秒；缓存或密钥丢失时窗口内也可能503，不重签/不延时。此保守窗口已获本次B1明确接受，待权威契约同步，不凭本回执直接改API；不能承诺所有介质恰到点物理擦除 |
| 存储C 审计及初始化恢复 | 最小登录审计意图与认证同事务，异步投递台账；唯一超管离线初始化/单人受控密码恢复 | 台账故障可降级重试告警；主认证数据库故障不能签发会话。所有审计介质双故障没有无损魔法，须明确处理；恢复命令不开放到HTTP、不新增第二管理员/MFA |

这些方案已由用户明确接受，团队继续负责细节与验证，不再重复请求A/B1/C批准；不重新审批opaque/15分钟/30天/60秒/30分钟/六角色/单运营/最终授权时点。生产采用前还需实际依赖与性能/安全验证及迁移评审。

## 2. Owner、类型与物理事实

pet-admin-biz独占下列admin表及Web密码/会话事务；pet-user-biz独占mini表及user_account/user_auth_identity适配。api只暴露DTO/接口，不暴露Entity/Mapper/连接。下面是候选物理名，不在SQL06新增或执行。

统一：业务主键BIGINT正Snowflake，HTTP String；时间DATETIME(3)以UTC解释，不改历史数据时区；revision/version/generation为非负BIGINT，递增溢出失败关闭。requestId终端UUID原词法按23号保留，建议VARBINARY(36)严格字节比较；不可因DB默认不区分大小写碰撞。namespace和编码版本固定服务端目录，非客户端自报权限。所有秘密/参数禁止普通日志。

| 候选表 | 字段与唯一/查询索引 | 约束及用途 |
|---|---|---|
| admin_account | id PK；account_display VARCHAR(128)、account_lookup VARBINARY(528) UNIQUE、display_name VARCHAR(64)、password_hash VARCHAR(512)、credential_version、status VARCHAR(16)、session_generation、version、last_login_at nullable、created_at/updated_at | lookup容量覆盖128字符×4 UTF-8字节+最多16字节类型前缀，超长拒绝不截断。须先定手机号/邮箱/工号归一：建议类别前缀+精确注册值；邮箱域名小写、本地部分不擅折叠，工号严格大小写；不以DB排序规则代替策略。物理status也用ENABLED/DISABLED |
| admin_role | id PK、role_code VARCHAR(64) binary UNIQUE、display_name、status、version | 六固定roleCode，角色不可物理删除；超管闭包依当前已批准目录 |
| admin_role_action | role_id/action_code组合PK、created_at | 必须命中服务器CONTRACT_READY目录；不接任意通配符 |
| admin_account_role | account_id/role_id组合PK、granted_by、granted_at | 两实体必须存在，不跨Owner外键 |
| admin_extra_grant | account_id/action_code组合PK、granted_by、granted_at | 额外授予独立来源；停角色不自动删除extra |
| admin_account_scope | account_id PK、mode VARCHAR(16)、version | 仅ALL/CITY/MERCHANT；NONE只输出计算值；ALL不得含成员项 |
| admin_scope_city / admin_scope_merchant | account_id/city_code或merchant_id组合PK | CITY非空≤100，MERCHANT非空≤1000；事务内检查模式/子项一致；商家有效性通过API确认而非跨表持久化 |
| admin_authz_revision | id固定单行PK、revision、recovery_epoch VARCHAR(36) | 所有账号状态/role/grant/scope变化同事务递增；角色配置不靠异步遍历账号完成撤权 |
| admin_auth_attempt | id PK、secret_digest BINARY(32) UNIQUE、binding_digest BINARY(32)、purpose固定ADMIN_LOGIN、status、expires_at、account_id nullable、credential_version nullable、completed_result_id nullable、created_at | attempt与cookie摘要绑定，仅认证流程，无业务Bearer；10分钟绝对到期 |
| admin_captcha | id PK、attempt_id索引、answer_mac BINARY(32)、mac_key_id、challenge_created_at/challenge_expires_at/challenge_consumed_at、failure_count；proof_digest BINARY(32) UNIQUE nullable、proof_issued_at/proof_expires_at/proof_consumed_at nullable | challenge与proof分别120秒和独立消费时刻；proof签发不延长原图形题期限。仅普通登录用途，不存裸答案hash、不新增因素 |
| admin_web_session | id PK、account_id索引、token_digest BINARY(32) UNIQUE、generation、status、issued_at、last_interactive_at、idle_expires_at、revoked_at nullable | 30分钟idle；后登录generation使旧会话不可用，active查询需账号与session同时有效 |
| admin_auth_command | id PK、attempt_id或session_id、namespace、request_id VARBINARY(36)、canonical_version、parameter_mac、mac_key_id、state、result_kind/result_id、execution_ref/cache_ref、completed_at、receipt_window_anchor_at、secret_expires_at、result_version | 非空scope_key编码已知attempt/session+namespace与requestId组合UNIQUE；两scope列不可同时空/同时有效；参数/首次结果引用不可变，旧key不重新签发 |
| admin_attempt_creation | 固定namespace ADMIN_LOGIN_CREATE、request_id VARBINARY(36)组合PK、首次attempt_id UNIQUE、参数绑定/created_at、状态及结果墓碑 | 创建前无attemptId，必须此稳定域先去重并同事务绑定首次生成ID；重复UUID不新建attempt且不返秘密。不假冒SYSTEM、不授业务身份；恶意碰撞只能获得无秘密冲突，需限流 |
| admin_login_failure | lookup_digest/IP或attempt范围、window_start、count、locked_until、version | 未知账号等形计数，反机器人与429；不保存明文密码或完整电话，具体IP最小化保留策略后续审查 |
| admin_audit_intent | id PK、request_id/trace、actor/action/resource、occurred_at、checked_versions、脱敏payload、delivery_state、attempt_count、next_attempt_at | 意图只增/状态推进；允许/拒绝均最小留痕，台账故障可重试；不占用未批准Outbox Schema，不把本提案当新事件类型 |
| admin_bootstrap | 单行PK、bootstrap_complete、version、completed_at | 与首个超管创建同事务；防多实例重复初始化；后续HTTP不可重开 |

非空组合UNIQUE及FK/Check需在具体MySQL版本验证，不能只在应用先查后插。secret/lookup摘要不是允许碰撞的授权捷径：密码/phone证明用已解析可信事实复核；requestId参数等值仍按受保护原语义做足，不只靠hash。

小程序后续按相同Owner原则新增mini_auth_attempt、mini_auth_command、mini_session、mini_refresh_family、mini_refresh_token、mini_sms_intent及证明记录；它们不与admin表共用账号/会话PK空间语义。mini额外字段：user_id、family_id、rotation_version、absolute_expires_at、previous_token_digest/consumed_at、SMS provider_intent_ref/状态/证明用途/phone受保护查找键。这里只定需要的事实，真实微信/SMS Provider字段和完整mini DDL在接入资料到位后逐项审查，不能当前造已验证phone或SIGNED。

## 3. 密码、token、HMAC与回执

密码建议Argon2id初始m=65536KiB、t=3、p=1，随机salt16字节、输出32字节，存自描述编码（算法/参数/salt/hash）；部署机基准测资源/并发后冻结参数与依赖。未知算法拒绝；禁明文、裸SHA或静默降级。真正重置递增credential_version+generation；同密码成功登录的成本升级可CAS更新hash，不改变业务权限。Spring Argon2实现所需库依赖仍待本方案与实现阶段审查，当前不改POM。

推荐依据：OWASP建议现代密码哈希优先Argon2id，参数下限与本提案64MiB候选不同，后者是待性能验证选择；见[官方密码存储指南](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)。不从此来源推定项目参数已经批准。

高熵access/attempt/proof至少32随机字节，存SHA-256摘要；无需把可轮换pepper强加到高熵token索引。低熵captcha/请求参数等值用独立HMAC-SHA256密钥和keyId，不能使用容易枚举的裸OTP hash。原始密码、SMS码、WeChat code、完整token不写公共幂等记录。MAC与回执加密使用不同用途密钥，轮换按keyId，不在数据库或Git保存密钥。

60秒grant回执建议只加密存在专用易失Redis实例（关闭AOF/RDB/备份及快照复制；仅独立namespace不能隔离整实例持久化），AES-256-GCM随机96位nonce，AAD绑定audience、attempt/session、namespace、原requestId、resultRef/cacheRef及冻结的secret_expires_at。数据库只存最小成功结果引用、secret_expires_at及不可复用墓碑，无token密文，避免binlog/备份长期持有可解grant。AEAD及密钥分离参考[OWASP加密存储指南](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)。

参数/密码验证和随机秘密准备在事务外；每次执行使用唯一executionRef/cacheRef，不能用原requestId作为可覆盖缓存key。持command锁先查：已成功绝不重制token；未成功才分配本次引用。最终SQL前读取数据库时间作为receipt_window_anchor_at，冻结secret_expires_at=anchor+60秒，与结果引用同事务写入；AAD使用该冻结值，在本地内存生成密文，事务内不调用Redis。主事务commit确认后才SET NX发布该不可覆盖cacheRef，TTL取剩余期限，不延时。若原引用已存在须校验同一不可变密文指纹，禁止SET覆盖或重加密替换首次结果。

commit ACK未知先用新连接查权威DB原command：SUCCEEDED仅接受其原executionRef/resultRef，读取或发布仍在本执行内存的相同密文；未确认时503，不生成新token/attempt。进程崩溃丢失尚未发布的秘密即恢复不可用；缓存存在不能证明commit，任何读取须核DB已成功并只读其固定cacheRef。DB回滚的内存准备材料丢弃，无成功发布。缓存宕机/密钥不可用/缓存未发布→503无秘密，不重签；过期或会话撤销→401。password-reset最小updated回执仍按attempt期限，独立于grant秘密缓存。

时间精度新增边界：MySQL事务内时间早于实际commit，无法在预提交行中取得未来精确commit时刻；本推荐取保守锚点，因此恢复窗口不超过实际commit后60秒、可能短于完整60秒。这不是静默改写已批准D1；现已在本次B1决定中明确接受，仍须后续同步权威契约并授权实现；本次只记回执。若必须精确commit后完整60秒，需另审可信提交时间记录方案，不能把响应后第一次查询时间用来任意延长窗口。首次读取/重试均不重置anchor。

存储B原有两条路线：本次已选择B1，B0未选；两条技术说明保留不变，当前均未实施：

| 路线 | 用户恢复体验 | 代价与门禁 |
|---|---|---|
| 已接受B1：保守precommit锚点 | 恢复请求可能比实际提交后60秒更早过期；例如数据库从锚点到commit耗时2秒，最多剩58秒。缓存故障也可503，这项影响已获本次明确接受 | 只需当前Owner事务与缓存，不增加提交时间捕获基础设施；属于本次已接受的行为决定；权威API本回执不改，后续按批准范围同步 |
| 未选B0：保留原D1“首次成功commit后60秒” | 截止仍由真实首次提交时点计算，不因SQL预提交耗时缩短，也不从首次重试开始延长；普通依赖故障仍按原错误处理 | 增加可验证的commit-time adapter与持久关联事实，先证明MySQL/驱动/崩溃恢复语义，再开放恢复能力。可研究GTID/binlog提交标记到command/resultRef的可靠关联，需启用/校验日志、消费恢复、权限隔离及额外等待，不是把NOW(3)或JDBC返回时间直接当精确commit。若无法证明，保持该实现门禁，不以B1冒充B0 |

MySQL文档中的GTID `original_commit_timestamp`描述原源事务写入二进制日志的提交标记，提供调查入口而非已验证的应用事务墙钟承诺，见[MySQL官方说明](https://dev.mysql.com/doc/refman/8.0/en/replication-delayed.html)。本项目尚未完成adapter与具体MySQL8.4/驱动的映射验证，因此B0不是“已经免费可用”的替代实现。选择B0意味着额外设计/组件验证和延后恢复能力交付；本次已有80合同测试或118JUnit均不证明这一时间语义。

本新物理方案允许60秒窗口内因缓存故障恢复不可用，本次已明确接受存储B1的故障边界；不能在已批准外部契约中悄悄保证“永远恢复”。不承诺磁盘、交换区、内存转储等所有介质恰到点物理清零；建议禁秘密dump/交换/日志、缓存无持久化，并验证运维配置。备份恢复一律丢旧会话/attempt、旋转相关秘密，不恢复旧grant。

MAC key退役须覆盖仍有效attempt/command等值验证窗口；过期墓碑保持不可复用而不要求永久保存能枚举旧密码的MAC材料。密钥泄露时撤销相关会话/attempt、清缓存并告警，不能只换key后接受旧token。具体密钥托管与轮换执行命令属于存储B实现门禁，不配置云KMS或新收费服务。

## 4. 事务、锁序与最终检查

建议统一管理写锁序：authz_revision → bootstrap（仅相关操作）→按ID升序account → role/scope/grant → attempt/command → session。匿名创建尚无account时只锁attempt_creation→新attempt，不反向获取revision/account；纯登录/退出不需要授权全局锁时可从account开始，但绝不在持session后反向等待account/revision。密码哈希、captcha渲染、Provider HTTP不放锁内。所有路径、重放、enable/reset/bootstrap都按同一顺序；bootstrap/离线恢复要求入口维护栅栏关闭且禁止在线管理写，不能仅因叫离线命令就假定无并发。

授权管理：先D2当前检查，锁revision及目标账户，检查expectedVersion、防提权/可转授/ALL范围/最后超管不变量，写role/extra/scope和审计意图，递增revision与实体version，一次commit。全局revision低吞吐但V1授权写低频，避免角色改动后部分账号仍用旧allow；下一阶段测并发配置及读负载再判断是否需要分片，不先扩设计。

登录：事务外验证密码，事务内锁account并重核credential_version/status/generation；锁attempt/command核目的/参数/到期，原子消费证明、创建session、递增登录generation、更新last_login、写最小成功结果和审计意图。密码验证后管理员重置/禁用先提交则签发拒绝；登录先提交则后续重置/禁用使新旧会话一起失效。没有额外MFA环节。

logout/activity：account→command→session顺序，与登录generation一致检查；logout撤session，activity仅未过期真实交互更新idle，旧key只返回原期限，不反复延长。mini后续refresh同family/current-token与账号generation原子CAS；退出先提交则刷新失败，刷新先提交则退出撤同family新旧token；绝对refresh期限不延長。

授权读：Owner短事务取得revision+账号状态+完整role/extra/scope快照；D2跨Owner两轮版本复核保持3次/2秒有界预算，无稳定结果503，不继承业务RR旧快照。最后检查先于撤权的短业务事务可能完成，这是已批准D2，新增revision方案不把它改成全局跨模块串行commit。异步已受理退款仍按其SYSTEM业务承诺执行。

版本恢复：authzVersion建议编码recovery_epoch:revision（不当ID），备份恢复先关闭认证/管理入口，恢复到经确认的一致点并回放、核对其后的账号禁用/密码重置/角色撤权/范围变更事实。无法重建时保持关闭或由单个获授权运维逐项核准当前授予，不自动开放旧备份权限；换epoch不能恢复丢失撤权。完成核对后生成新随机epoch并失效全部缓存/会话/attempt；epoch与revision原子读取，防旧版本ABA。恢复不清空历史审计或复活旧token。该物理编码与恢复程序按本次A/C存储方案接受；完整内部DTO仍需单独定稿，不由本回执直接写入权威接口。

## 5. 审计故障与恢复

最小登录审计意图与会话/attempt同一MySQL事务：台账/投递器故障时可靠意图存在，登录可成功，后台有界重试并告警；不以内存队列冒充可靠。主认证DB不可用则无法建立会话，本来就不能成功登录。高风险管理写与必要审计意图原子提交，拒绝尝试也需最小审计意图/告警，不保存参数秘密。

极端“认证可写而全部审计持久介质不可写”不能同时保证任意登录永不阻塞且审计绝不丢；建议使用同Owner同库最小意图消除常见双路径故障，并将独立权限/介质破坏视为依赖故障关闭认证或人工修复。该极端故障边界已随本次存储C接受，不能静默覆盖原PRD正常台账失败降级要求。日志台账保存/查询只读、至少3年原要求保持，不在此新定法务期限。

bootstrap仅离线受控命令，要求无运营账号且bootstrap_complete=false，锁单行标记后创建唯一初始超管+ALL+版本+审计并关闭bootstrap。账号密码由隐藏交互或限权秘密文件输入，不提供默认密码、不经命令行参数回显；禁止公开初始化HTTP endpoint。

单人恢复同样只限部署宿主离线管理命令，明确目标现有超管ID/原因，重置密码/解除密码失败锁定/按批准权限启用，递增credential_version/generation/revision、撤旧会话并审计实际运维主体。不是用户自助找回、不是第二管理员审批、不是额外因素。恢复主机访问本身由运维控制，不创造运营新业务权限。

## 6. 迁移与下步真实Web切片

迁移前只读盘点现有user账户/身份字节唯一性、账号别名冲突、Schema版本、索引排序规则与时区。新admin表当前无存量事实，建议独立新增迁移而不执行整份SQL06/13；实际编号由唯一migration Owner登记。先建表与约束→种六角色模板/批准动作目录→离线bootstrap→校验→启服务。失败回滚应用需理解新墓碑/generation，不能删成功绑定或降级到旧匿名逻辑；遇不兼容保留表/事实并关闭入口。

未来Web实际切片范围：admin attempts/login/requirements/captcha、session/result/logout/activity、auth/permissions，加真实账号初始化与密码编码、数据存储及读权限事实。管理CRUD在同存储能力稳定后接续；不提前实现所有领域动作。对未就绪Provider或商家模型不造SIGNED，不把缺scope当ALL，不做21接口全部503空壳。

生产ID必须来自PLAT002实际提供器与装配；Hutool算法适配方案接受不代表S2生产worker独占/高水位/配置已上线，AUTH不自行发号或改S2。代码实现后须真实MySQL验证并发登录/重置/退出/activity/角色撤权、回执丢失/密钥缓存故障、审计台账宕机、bootstrap竞态和备份恢复；本次只记录方案接受，不报告这些尚未运行的实现测试PASS。
