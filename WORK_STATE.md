# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 7.4
UPDATED_AT: 2026-09-20

CURRENT_PHASE: W3_INTEGRATION_REVIEW
CURRENT_STATUS: MER001_S9_MERGED_PHONE_SUBMISSION_VERIFIED
VERIFIED_BASELINE: develop c04df900cd7085ee16a68eb93a7951b51080ae5d
NEXT_PHASE: 运营审核页面、手机签约与通知跳转的完整联调
NEXT_PHASE_APPROVED: YES（沿用MER-001既有批准范围；本轮仅清理台账，不派发新业务实施）

## 当前结论

PR53～57均已合入develop。2026-09-20通过GitHub只读查询核实：最新业务基线为PR57合并提交 `c04df90`，对应[合并CI 35502420044](https://github.com/blueXYing/pet-platform/actions/runs/35502420044)六项全部成功；查询时无开放PR。精确提交、证据边界和本轮文档检查见[进度同步报告](planning/progress/2026-09-20/PROGRESS_LEDGER_SYNC.md)。这些查询结果是本次核验时点的快照。

MER-001整体仍为IN_PROGRESS。申请审核/协议HTTP、跨重启原请求恢复、私有材料上传/扫描/受控水印读取均已实现并合入，不再列为“尚无HTTP”“上传未实现”或“等待私有材料CCR确认”。Figma已实际连接并读取申请页设计，安装不再是阻塞。

真实手机已完成登录、材料上传、保存草稿、重新读取和申请提交；最新只读数据库证据为REVIEWING/v4、仅1条未领取审核任务、1条SUBMIT审计、0条审核决定。见[S9手机验收](planning/issues/wave-2/MER-001-s9/PHONE-ACCEPTANCE.md)。这不等于运营页面审核或手机签约已经验收。

隔离自动验收使用真实OSS、ClamAV、MySQL、Redis，已走通申请、审核、水印读取、签约和站内通知；微信身份入口使用FixedWechatProvider，不能将其称为真实手机完整闭环。模拟器有草稿/上传响应丢失后的恢复证据；真实手机上传和重读证据不能代替所有故障恢复场景。

SSOT §28已批准取消入驻地址/坐标地理匹配、距离和围栏限制，不再要求腾讯WebService Key；仍校验输入合法性，开放城市目录保持成都。大陆身份证15/18位范围、申请审核四项与协议换版规则均沿用已批裁决，不重复索取确认。

## 已交付与剩余范围

| 范围 | 已交付或已有验证 | 尚未完成或未验收 |
|---|---|---|
| Wave1 / PR6～9 | 工程壳与基础CI | 不代表完整业务完成 |
| PLAT-002 | ID节点协调及幂等基础；本地受控联调使用真实发号器 | 正式宿主退出证明、节点/高水位恢复、生产迁移与启用、完整公共幂等范围 |
| PLAT-003 / PLAT-004 | Outbox、Durable AsyncTask；申请审核通知及私有材料任务已有业务接入 | 其余业务接入、DEAD对账/告警、归档与生产运维闭环 |
| AUTH-001 / USR-001 / C-002 | 运营/C端认证后端；资料与宠物接口；模拟器资料/宠物联调；S9实际微信手机号授权及手机登录证据 | 未实现的SMS/密码/刷新范围、完整商家身份/准入页面、资料宠物全量真机与VIS、生产配置 |
| MER-001 S2～S7 / PR50～56 | 主账号只读基础、协议存储、申请审核事务、可靠通知、申请页、精确HTTP、字段保护、成都目录及原请求恢复 | 完整运营审核和签约页面、工作台准入及通知跳转联调；成员绑定、主账号核销映射、停用在途守卫、冻结写动作等既有未完成范围 |
| MER-001 S8/S9 / PR57 | 私有上传/扫描/归属/水印读取；真实OSS/ClamAV；手机上传、草稿保存/重读、提交与审核队列落库 | 真实手机审核至签约的完整闭环、全故障恢复/跨设备/VIS；RAM最小权限、正式运维与生产准入；材料/审计保留期限待裁决 |
| A-002 / M-002 | 运营和商家工作区工程壳；申请页位于consumer分包；审核/协议后端已交付 | 运营审核业务页、独立签约页面、完整工作台；不能把后端验收当作页面交付 |
| CCR-OSS-001 | 公开运营素材注册/同步/私有签名URL；私有证照另走已批准CCR-MER-PRIVATE-001 | 公开素材前端完整消费/缓存刷新及运营全流程，与私有证照验收分别记录 |
| PLAT-005 / PLAT-006 | PLAT-005原AC核对通过，Catalog已DONE；MyBatis六模块迁移已DONE | 运维采集按原范围；生产数据库迁移与PLAT-002门禁不因DONE解除 |
| 服务/排期/交易/治理其余范围 | 以各Issue证据为准，未因以上合并自动完成 | 契约、业务实现与端到端验收按依赖推进 |

## 下一步与保留门禁

1. 在已批范围内细化运营审核页面、手机签约及通知跳转联调任务，承接已有HTTP与真实材料能力；不重复开发S7/S8能力。
2. 按原AC验证权限/数据范围、人工核验、补正重提、审核与签约分离、未签约不能开启新业务；记录真实页面与自动测试各自证据。
3. 保留生产ID、密钥策略、迁移、正式HTTPS/微信配置、扫描维护、日志脱敏与运维门禁；默认生产开关关闭。本轮未部署或调整运行服务。
4. 资金冻结/分账/提现/保证金、人工客服承载以及私有材料保留期限见[未决项](planning/OPEN_DECISIONS.md)。位置Key与签约产品模式不再是未决项。

## 证据与历史入口

- 当前可推进范围：[Ready Queue](planning/READY_QUEUE_WAVE_2.md)；当前剩余项：[Blocked Queue](planning/BLOCKED_QUEUE.md)。
- [S7交接](planning/issues/wave-2/MER-001-s7/HANDOFF.md)、[S8交接](planning/issues/wave-2/MER-001-s8/HANDOFF.md)是当时阶段快照；其中地图待配置、上传/真机待验收等旧结论以S9手机验收和SSOT §28覆盖，不回写历史测试结果。
- [W3整合验收](planning/progress/2026-09-17/W3_INTEGRATION_ACCEPTANCE.md)证明其当时基线；本次CI查询不冒充重新运行本地业务/真机测试。
- 清理前所有阶段叙述保存在[历史快照](planning/history/WORK_STATE_BEFORE_20260920_LEDGER_SYNC.md)。更早记录见[9月16日同步](planning/progress/2026-09-16/PROGRESS_SYNC.md)。
- 本轮唯一Writer为根Work；在 `codex/progress-ledger-20260920` 独立worktree更新台账。主目录仍为原 `632ced7`，用户project.config.json保持原样；当前权威已合入代码以远端上述基线为准。本轮不自动合并PR或发布生产。
