# 2026-09-20 进度台账同步

## 本轮范围与基线

用户要求清理进度台账中的旧结论。本轮唯一Writer为根Work，在独立worktree `codex/progress-ledger-20260920` 操作，仅更新WORK_STATE与planning进度/未决项/证据；不改业务代码、产品规则、Schema/API/Event、Issue Catalog状态或运行环境。

初始主目录develop为 `632ced7`，仅有用户的frontend-miniapp/project.config.json修改。只读远端查询及fetch发现PR56/57已合入，故从origin/develop `c04df900cd7085ee16a68eb93a7951b51080ae5d` 建立本轮worktree。主目录不切换、不快进；其配置SHA-256为 `6A1814D6B2C0F733193F57354761268A398EC128588E955F08983DFBF7C95561`，交付前复核。

## 远端合并事实

下表是2026-09-20核验时点快照，来自 `gh pr list`，原始安全字段见[PR快照](merged-pr-snapshot.json)。查询时开放PR为0，不代表之后不会产生新PR。

| PR | 合并提交 | 合并时间（UTC） | 标题 |
|---|---|---|---|
| [50](https://github.com/blueXYing/pet-platform/pull/50) | `0ade8bc` | 2026-09-17T08:12:36Z | docs(mer001): 同步已批准商家域契约与产品裁决 |
| [51](https://github.com/blueXYing/pet-platform/pull/51) | `5ee8f75` | 2026-09-17T08:58:57Z | feat(mer001): 商家主账号只读基础与真实MySQL回归 |
| [52](https://github.com/blueXYing/pet-platform/pull/52) | `65df1c3` | 2026-09-17T10:00:50Z | feat(mer001): 协议同意持久化与申请审核CCR候选 |
| [53](https://github.com/blueXYing/pet-platform/pull/53) | `8fa642d` | 2026-09-20T01:53:18Z | MER-001 S4: define application review contracts and tested storage |
| [54](https://github.com/blueXYing/pet-platform/pull/54) | `06134f9` | 2026-09-20T01:53:38Z | MER-001 S5: implement application review transactions and reliable inbox |
| [55](https://github.com/blueXYing/pet-platform/pull/55) | `632ced7` | 2026-09-20T01:54:03Z | MER-001 S6: implement Figma merchant application page |
| [56](https://github.com/blueXYing/pet-platform/pull/56) | `8644db2` | 2026-09-20T09:21:14Z | MER-001 S7: connect application HTTP, Chengdu catalog and durable recovery |
| [57](https://github.com/blueXYing/pet-platform/pull/57) | `c04df90` | 2026-09-20T09:28:50Z | feat(mer001): 私有材料与本地微信联调 |

最新业务基线[CI 35502420044](https://github.com/blueXYing/pet-platform/actions/runs/35502420044)绑定c04df90，backend、frontend-inventory、repository-policy、contract-smoke、web-build、miniapp-weapp-build均completed/success。机器可读摘要见[CI快照](baseline-ci.json)。本次未将该CI写成文档分支的检查，也未重跑本地业务或真机测试。

## 清理的旧结论

| 原台账误导点 | 当前结论与来源 |
|---|---|
| PR52～55待合入、网络中断无法确认 | PR50～57均MERGED，最新基线c04df90，以上只读快照作证 |
| 缺申请审核HTTP、原请求跨重启恢复 | S7/PR56已实现；[S7交接](../../issues/wave-2/MER-001-s7/HANDOFF.md)及boot merchant Controller/配置源码 |
| 私有材料方案待确认、上传/扫描/水印未实现 | CCR已APPROVED，S8/S9随PR57合入；[S8交接](../../issues/wave-2/MER-001-s8/HANDOFF.md)及[S9最新验收](../../issues/wave-2/MER-001-s9/PHONE-ACCEPTANCE.md) |
| 地图服务/Key是入驻阻塞 | SSOT §28取消地理匹配/距离/围栏要求；默认LocationInputValidationProvider仅校验输入，成都目录独立执行 |
| 真机尚未登录/上传/提交 | S9手机真实登录、上传、草稿保存/重读及REVIEWING/v4落库已确认；审核任务1、SUBMIT审计1、审核决定0 |
| 无业务Outbox消费者/AsyncTask Handler | 已有审核通知与私有材料处理接入，其他业务和生产运维闭环仍未完成 |
| 签约产品模式/换版策略未决、Figma未安装 | SSOT §26/27及既有批准回执覆盖；S6已读取在线设计，不重复索取决定或链接 |
| PLAT-005仍“可关闭” | Catalog已DONE，保留W3原AC验收与运维范围边界 |

运营前端main.tsx仍为工程壳，生产入口提示服务尚未接入；审核HTTP、自动签约测试与真实运营/手机页面交付分别登记。下一步承接审核页面、手机签约和通知跳转联调，不重复实现已合入能力。

## 验收证据分层与剩余项

- S7自动联合测试：真实TCP/MySQL/Redis/领域事务，外部材料、地图、微信身份使用当时测试适配。此为当时证据，不覆盖S9后来的真实依赖结果。
- S8自动回归：当时392项后端/113项前端等计数仅绑定该阶段；不当作最新提交计数。
- S9真实依赖自动链路：真实OSS、ClamAV、MySQL、Redis，合成材料完成提交/审核/水印/签约/站内通知；微信身份为FixedWechatProvider，不能冒充真机全链路。
- S9模拟器响应丢失恢复有单独记录；实际手机已证明登录/上传/草稿/提交至审核队列，尚未证明该申请在运营页面审核通过与手机签约。完整VIS/跨设备/全部故障场景仍需验收。
- 生产开关仍默认关闭，正式ID/密钥/迁移/HTTPS/扫描运维/RAM最小权限等门禁未解除。成员绑定、核销映射、在途守卫等完整MER范围继续保留。
- 私有材料与读取审计保留期限在CCR §10仍待裁决，本次仅补登记OPEN_DECISIONS，不新增保留天数或删除策略。

## 历史与后续维护

原WORK_STATE全部内容保留在[清理前快照](../../history/WORK_STATE_BEFORE_20260920_LEDGER_SYNC.md)，以代码块保存原文，旧相对链接仍以仓库根目录解释。历史交接保留其当时事实，不回写旧测试结果。当前结论只维护在WORK_STATE、Ready/Blocked Queue中，并指向最新证据。

M-002/A-002 Catalog的BLOCKED表示完整Issue尚未重新派发；部分HTTP依赖已交付不自动把整个Issue改成READY。NEXT_PHASE_APPROVED沿用既有MER授权，本轮只做文档清理，不自动启动新业务、合并PR或发布生产。

## 本轮验证

验证结果见[validation.json](validation.json)：文档相对链接（不扫描历史代码块）、表格列数、远端合并/CI字段、当前台账关键事实、历史正文保留、变更范围、git diff --check及架构源检查。全后端/前端业务测试不因纯文档修改重复运行；基线CI与本轮文档检查分开记载。
