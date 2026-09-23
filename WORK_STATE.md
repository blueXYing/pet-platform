# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 8.0
UPDATED_AT: 2026-09-23

CURRENT_PHASE: W3_INTEGRATION_REVIEW
CURRENT_STATUS: STORE_READ_AND_SERVICE_WRITE_MERGED_FRONTEND_PR_IN_REVIEW
VERIFIED_BASELINE: develop 25bf601（PR66~69 已合入；PR70/71/72 在途待用户合并）
NEXT_PHASE: 合并 PR70（冒烟语法修复）→PR71（通知接线）→PR72（M-002/C-003前端）；随后用户模拟器人工验收与完整"发布服务→消费者看到"闭环集成验收
NEXT_PHASE_APPROVED: YES（2026-09-23用户授权按序合并并继续任务；新PR合并与人工验收仍归用户）

## 当前结论

2026-09-23 多角色并行轮收官（协调者ZCode+子代理A/B/C/D/E/F/W/CI）：用户2026-09-22分项批准门店读侧CCR v0.2（STR-D1~D8，含四条浏览路由匿名）、服务写入方CCR v0.2（SVCW，含审核事件Outbox拆分）、契约漂移修复方向与设计源裁决后，四切片经逐级合并核验合入 develop 25bf601——PR66（漂移修复：404码对齐SERVICE_NOT_FOUND+§3.3.1补写+PR65勘误）、PR67（门店读侧：/c/stores两路由+第五查询MerchantStoreDisplayApi+匿名四路由+SERVICE_COVER管线31号+完整性runbook）、PR68（服务写入方：六命令状态机五值/幂等/OWNER门禁/运营审核/admin四路由/审核事件Outbox/封面展示/33号Schema+V27迁移；动作码按AUTH词法落地service.force.offline已披露）、PR69（通知消费侧：ServiceReviewedEvent.v1九字段消费+商家收件箱+权限隔离）。合并中发现并修复两处集成级问题：CStore测试夹具缺33号Schema（读投影SELECT新列即503）、契约冒烟脚本语法损坏（PR70一行修复，源于PR68合并提交遗漏）。在途：PR71（消费者boot接线+NOTIFICATION-001登记，CI全绿，含ARCH-002修复）、PR72（M-002服务管理页+C-003门店页+10项契约对齐修复+共享层真实API联调取证，CI全绿）。真实API联调证据（共享客户端层，非模拟器窗口）：申请→审批→签署→建服务→幂等→提交审核→运营APPROVE→匿名找店→进店→店内服务/详情含cover→下架→隐藏+404防探测，见[集成证据](planning/issues/wave-2/M-002-service-pages/INTEGRATION-EVIDENCE.md)。通知链生产/消费/页面三方代码齐备但端到端人工验收未做，完整审核流程不标完成；模拟器窗口级VIS/真机留用户人工验收。本轮剩余裁决项（B1~B7）与证据清单见[2026-09-23收官记录](planning/progress/2026-09-23/ROUND-CLOSEOUT.md)。

2026-09-22 PR64（NTF-001+C-006 通知读取/消息页/受控跳转，CCR-W2-NOTIFICATION-001）经用户模拟器人工验收（消息中心→详情→自动已读→查看入驻申请→重鉴权 APPROVED）后合入 develop bb6bb5c；验收中修复消息页状态栏侵入（statusBarHeight 变量机制）与 DevTools 编译缓存损坏，并修复集成脚本通知计数竞态。同日用户批准 CCR-W2-API-001 服务域提案 v0.3（SVC-D1～D5，含错误两分细化：确认不存在/无资格→隐藏或404，故障/未知→503，不混同），SVC-001 服务域读切片（两条 C 端服务路由 + SVC-D5 展示资格第四查询独立提交）已实现并测试通过；该切片于2026-09-22经PR65合入，其§3.3.1漏同步与404码偏差由PR66勘误修复。

2026-09-22用户确认设计裁决(b)后连续交付并合入三个切片：PR61（MS-1手机签约页+申请页签约入口）、PR62（MS-2准入契约CCR-W2-ADMISSION-001冻结+memberships/admission OWNER实现）、PR63（MS-3工作台前端接真实准入）。三PR各自CI六项全绿；MS-1与MS-3另经模拟器真实链路人工验收（登录→申请→审批APPROVED→签署→工作台ALLOWED，真实微信/OSS/ClamAV/MySQL/Redis），证据在M-002-mobile-signing各交接文档分层记录。"审核通过→手机签署→按真实资格进入工作台"主链闭环；工作台业务（订单/服务/排期）、子账号、STAFF、merchantEntry、冻结写动作维持未交付。仓库已于当日转public，CI分钟限制解除。


2026-09-21用户授权按顺序推进最终核验及收尾。PR60（材料引用后端，head 4f00914）先合入00eceff，PR59（运营审核页面，head 30d2b61）随后合入54cf51e。两PR合并前六项CI均成功，组合无冲突；本轮前端复跑27通过、2个live门控跳过。合并后CI与精确证据见[合并回执](planning/progress/2026-09-21/A002_MERGE_CLOSEOUT.md)。PR58为本轮台账分支，不冒充已经合入。

运营入驻审核页面核心切片已交付并合入：运营登录、列表、详情材料引用、领取、单次水印查看、人工核验和决定。ZCODE登记的方案b浏览器联调使用PR60真实后端、MySQL/Redis、OSS/ClamAV和合成材料，通过APPROVED/VERIFIED、ACTIVE建档及站内通知落库；微信入口为固定code替身。本轮复核源码、交接及本地启动器存档，未重建已拆除环境或重新查询该测试库。具体边界见[A-002交接](planning/issues/wave-2/A-002-review-page/HANDOFF.md)，不将回环HTTP联调称为生产HTTPS入口验收。

MER-001整体仍为IN_PROGRESS。申请审核/协议HTTP、跨重启原请求恢复、私有材料上传/扫描/受控水印读取均已实现并合入，不再列为“尚无HTTP”“上传未实现”或“等待私有材料CCR确认”。Figma已实际连接并读取申请页设计，安装不再是阻塞。

S9真实手机已完成登录、材料上传、保存草稿、重新读取和申请提交；该阶段用户申请数据库证据为REVIEWING/v4、1条未领取审核任务、1条SUBMIT审计、0条审核决定。见[S9手机验收](planning/issues/wave-2/MER-001-s9/PHONE-ACCEPTANCE.md)。PR59方案b的已审核合成申请是另一份数据，不代表用户原申请已被审批，也不证明手机签约已验收。

隔离自动验收使用真实OSS、ClamAV、MySQL、Redis，已走通申请、审核、水印读取、签约和站内通知；微信身份入口使用FixedWechatProvider，不能将其称为真实手机完整闭环。模拟器有草稿/上传响应丢失后的恢复证据；真实手机上传和重读证据不能代替所有故障恢复场景。

SSOT §28已批准取消入驻地址/坐标地理匹配、距离和围栏限制，不再要求腾讯WebService Key；仍校验输入合法性，开放城市目录保持成都。大陆身份证15/18位范围、申请审核四项与协议换版规则均沿用已批裁决，不重复索取确认。

## 已交付与剩余范围

| 范围 | 已交付或已有验证 | 尚未完成或未验收 |
|---|---|---|
| Wave1 / PR6～9 | 工程壳与基础CI | 不代表完整业务完成 |
| PLAT-002 | ID节点协调及幂等基础；本地受控联调使用真实发号器 | 正式宿主退出证明、节点/高水位恢复、生产迁移与启用、完整公共幂等范围 |
| PLAT-003 / PLAT-004 | Outbox、Durable AsyncTask；申请审核通知及私有材料任务已有业务接入 | 其余业务接入、DEAD对账/告警、归档与生产运维闭环 |
| AUTH-001 / USR-001 / C-002 | 运营/C端认证后端；资料与宠物接口；模拟器资料/宠物联调；S9实际微信手机号授权及手机登录证据 | 未实现的SMS/密码/刷新范围、完整商家身份/准入页面、资料宠物全量真机与VIS、生产配置 |
| MER-001 S2～S7 / PR50～56、PR61～63 | 主账号只读基础、协议存储、申请审核事务、可靠通知、申请页、精确HTTP、字段保护、成都目录、原请求恢复、材料引用；MS-1签约页、MS-2准入两端点（CCR-W2-ADMISSION-001）、MS-3工作台真实准入均已合入 develop 12bdc38 | 通知读取/消息页/受控跳转（NTF-001/C-006 切片）；成员绑定、核销映射、停用在途守卫、冻结写动作等既有范围 |
| MER-001 S8/S9 / PR57 | 私有上传/扫描/归属/水印读取；真实OSS/ClamAV；手机上传、草稿保存/重读、提交与审核队列落库 | 真实手机审核至签约的完整闭环、全故障恢复/跨设备/VIS；RAM最小权限、正式运维与生产准入；材料/审计保留期限待裁决 |
| A-002 / M-002 | PR59/60运营审核页与材料引用已交付；PR61～63手机签约+工作台准入主链闭环（方案b人工验收含在内） | A-002其他治理页、生产HTTPS入口及完整视觉验收；M-002完整工作台业务页。完整Issue不标DONE |
| CCR-OSS-001 | 公开运营素材注册/同步/私有签名URL；私有证照另走已批准CCR-MER-PRIVATE-001 | 公开素材前端完整消费/缓存刷新及运营全流程，与私有证照验收分别记录 |
| PLAT-005 / PLAT-006 | PLAT-005原AC核对通过，Catalog已DONE；MyBatis六模块迁移已DONE | 运维采集按原范围；生产数据库迁移与PLAT-002门禁不因DONE解除 |
| SVC-001 服务域读 / PR65~66 | CCR 服务域 v0.3 已批（SVC-D1～D5）；两条 C 端服务读路由 + D5 展示资格第四查询 + 快照/资格/可见性（四条件合取、错误两分）实现与 ServiceQueryHttpTest 通过；PR66 勘误修复 §3.3.1 漏同步与 404 码（SERVICE_NOT_FOUND，测试补 body code 断言） | 读侧仅在 ACTIVE 可见已含 REVIEWING/REJECTED 兼容回归（PR68）；排期/订单不提前；完整预约流程未验收 |
| MER-001 门店读侧 / PR67 | CCR v0.2（STR-D1~D8）已批并合入：/c/stores 两路由+第五查询 MerchantStoreDisplayApi+四条浏览路由匿名（STR-D8 七面核验无冲突批准）+城市目录+完整性runbook+SERVICE_COVER 管线（31号）；CStoreControllerHttpTest 2/2 | 分类筛选/关键词搜索延后（STR-D7）；评分/月售/距离/收藏/相册/评价/促销等无事实源字段不实现；完整门店页面验收未完成 |
| ADM-001 服务操作（写入方）/ PR68 | CCR v0.2 已批并合入：状态机五值（商家动作不产生 ACTIVE）+六命令幂等/乐观锁+getFacts 门禁+运营审核四路由+审核事件 Outbox（ServiceReviewedEvent.v1 九字段）+封面展示授权+33号 Schema/V27 迁移；ServiceWriteHttpTest 真实链路通过；owner_user_id 列小幅增补已披露 | 售罄/硬删除/批量通过/类目 CRUD 延后；动作码 service.force.offline 词法更正已披露；强制下架通知待裁决 |
| NTF 服务审核通知 / PR69+PR71 | 消费侧（严格载荷校验/幂等/权限隔离）合入；boot 接线+开关+NOTIFICATION-001 §5 登记在 PR71（CI 绿待合并） | 三方（生产#68+消费#69+页面#72）代码齐备但端到端人工验收未做；完整审核流程不标完成；force-offline 不发通知 |
| M-002 服务管理页 / C-003 门店页 / PR72 | 设计源（10:5255/11:5768 确认+V1裁剪20条VIS-004差异登记）+六页面实现+10项契约对齐修复+共享层真实API联调取证（发布→审核→匿名找店/进店/看到→下架隐藏/404）；162/162 前端测试（CI 绿待合并） | 模拟器窗口级验证/VIS/真机留用户人工验收；通知跳转端到端待 #71 合并；封面真实签名端口无 signer 时 503（设计内） |
| 服务/排期/交易/治理其余范围 | 以各Issue证据为准，未因以上合并自动完成 | 契约、业务实现与端到端验收按依赖推进 |

## 下一步与保留门禁

1. 运营审核页面切片已交付，接续手机协议签署、通知跳转和工作台准入；见[下一阶段接续清单](planning/progress/2026-09-21/MOBILE_SIGNING_NEXT.md)，不重复开发现有审核及材料能力。
2. 按原AC验证权限/数据范围、人工核验、补正重提、审核与签约分离、未签约不能开启新业务；记录真实页面与自动测试各自证据。
3. 保留生产ID、密钥策略、迁移、正式HTTPS/微信配置、扫描维护、日志脱敏与运维门禁；默认生产开关关闭。本轮未部署或调整运行服务。
4. 资金冻结/分账/提现/保证金、人工客服承载以及私有材料保留期限见[未决项](planning/OPEN_DECISIONS.md)。位置Key与签约产品模式不再是未决项。

## 证据与历史入口

- 当前可推进范围：[Ready Queue](planning/READY_QUEUE_WAVE_2.md)；当前剩余项：[Blocked Queue](planning/BLOCKED_QUEUE.md)。
- [S7交接](planning/issues/wave-2/MER-001-s7/HANDOFF.md)、[S8交接](planning/issues/wave-2/MER-001-s8/HANDOFF.md)是当时阶段快照；其中地图待配置、上传/真机待验收等旧结论以S9手机验收和SSOT §28覆盖，不回写历史测试结果。
- [W3整合验收](planning/progress/2026-09-17/W3_INTEGRATION_ACCEPTANCE.md)证明其当时基线；本次CI查询不冒充重新运行本地业务/真机测试。
- 清理前所有阶段叙述保存在[历史快照](planning/history/WORK_STATE_BEFORE_20260920_LEDGER_SYNC.md)。更早记录见[9月16日同步](planning/progress/2026-09-16/PROGRESS_SYNC.md)。
- 本轮唯一Writer为根Work，在 `codex/progress-ledger-20260920` 独立worktree更新PR58台账。PR60/59按本次授权先后合入；主目录用户project.config.json保持原样。未发布main或生产，未清理其他任务worktree。
