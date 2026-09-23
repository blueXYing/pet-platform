# 2026-09-23 多角色并行轮收官记录

协调者：ZCode（主会话）。角色：A（服务写入方）、B（门店读侧）、C（前端/设计资料负责人）、D（QA）、E（通知消费）、F（漂移修复）、W（接线）、CI（集成）。用户2026-09-22四项集中裁决与2026-09-23"按序合并并继续任务"授权为本轮依据。

## 一、交付与合并清单

| PR | 内容 | 状态（本文撰写时） |
|---|---|---|
| [#66](https://github.com/blueXYing/pet-platform/pull/66) | 契约符合性修复：404→SERVICE_NOT_FOUND、10号§3.3.1逐字补齐、PR65交接勘误、测试补状态码+body code双断言 | 已合并（develop 4c34f33） |
| [#67](https://github.com/blueXYing/pet-platform/pull/67) | MER-001 门店读侧：/c/stores两路由、第五查询MerchantStoreDisplayApi、四条浏览路由匿名（STR-D8）、城市目录、完整性runbook、SERVICE_COVER管线（31号增补） | 已合并（develop c65c738；合并前集成核验：10号节序重排、异常映射双保留、e2e 115/115、三测试类全绿） |
| [#68](https://github.com/blueXYing/pet-platform/pull/68) | ADM-001 服务写入方：状态机五值、六命令幂等/乐观锁、getFacts门禁、运营审核四路由、审核事件Outbox、封面展示、33号Schema+V27迁移 | 已合并（develop f342fea；集成核验：census双家族89、CStore夹具补33号修复读投影503回归） |
| [#69](https://github.com/blueXYing/pet-platform/pull/69) | 通知消费侧：ServiceReviewedEvent.v1九字段（含ownerUserId）严格消费、商家收件箱、权限隔离 | 已合并（develop 25bf601） |
| [#70](https://github.com/blueXYing/pet-platform/pull/70) | 一行修复：PR68合并提交遗漏的contract_smoke.py闭合花括号（develop CI红） | OPEN待合并（已包含于#71/#72分支） |
| [#71](https://github.com/blueXYing/pet-platform/pull/71) | 消费者boot接线+开关+NOTIFICATION-001 §5登记；ARCH-002修复（消费者便利构造器） | OPEN，CI 6/6绿 |
| [#72](https://github.com/blueXYing/pet-platform/pull/72) | M-002服务管理页+C-003门店页+契约对齐修复10项+M002IntegrationServer+共享层真实API联调取证 | OPEN，CI 6/6绿 |

## 二、证据索引

- QA基线：planning/issues/wave-2/SVC-STORES-QA/（验收清单/权限反例/测试数据）+ planning/progress/2026-09-22/qa/（PR65契约符合性抽检，含两项漂移发现）
- 漂移修复证据：planning/progress/2026-09-22/svc-conformance-fix/
- 门店读侧：planning/issues/wave-2/MER-001-store-read/（SCOPE-VERIFY/TEST-PLAN/INTEGRITY-RUNBOOK）+ CCR store-read-proposal v0.2与决定回执
- 服务写入方：planning/issues/wave-2/ADM-001-service-write/（SCOPE-VERIFY/TEST-PLAN）+ CCR service-write-proposal v0.2与决定回执
- 通知消费：planning/issues/wave-2/NTF-SERVICE-REVIEW/HANDOFF.md
- 前端与联调：planning/issues/wave-2/C-003-design-inputs/（INVENTORY/VIS-004二十条差异/assets/FETCH-LOG）+ M-002-service-pages/（PLAN/NAVIGATION-BASIS/INTEGRATION-EVIDENCE.md）
- 设计源登记表：docs/08-engineering/20-设计源登记表-figma-map.md（随PR72分支进入仓库，无秘密内容已核验）

## 三、真实API联调覆盖矩阵（层级：共享客户端层真实HTTP，非模拟器窗口）

申请→审批APPROVE→签署SIGNED→准入ALLOWED ✅｜建服务→幂等重放/异参409→编辑→提交REVIEWING ✅｜运营decision APPROVE→ACTIVE ✅｜匿名找店列表含新店 ✅｜进店九字段详情 ✅｜店内服务+详情含cover三件套 ✅｜下架→列表隐藏/详情404 SERVICE_NOT_FOUND（REVIEWING同404防探测）✅｜通知收件箱（待#71接线后复验）⏳

QA缝隙如实披露：登录码/素材/封面签名为既有验收同缝桩；模拟器窗口级/VIS/真机留用户人工验收。

## 四、本轮已披露偏差（合并即接受，均已在PR描述）

1. 动作码 service.forceOffline → service.force.offline（AUTH词法，语义不变）
2. service_item 新增 owner_user_id 列（事件自包含收件人；存量行=0审核失败关闭）
3. 已合入服务两路由会话语义改为匿名可选（STR-D8用户裁决直接执行）

## 五、遗留待裁决项（不阻塞当前合并，均有推荐）

| # | 事项 | 推荐 | 状态 |
|---|---|---|---|
| B1 | 公开营业电话vs私人号码边界 | 维持掩码，完整号码待单独裁决 | 未决 |
| B2 | 强制下架是否发商家通知 | 建议发，走Event08增补小额切片 | 未决 |
| B3 | 无在售服务门店是否展示 | 维持三条件（空店可见） | 已按默认执行，可推翻 |
| B4 | 匿名浏览公共限流/防爬 | 登记运维项，出现滥用再补 | 未决 |
| B5 | 售罄口径（库存事实源） | 维持不实现，待产品裁决 | 未决 |
| B6 | SERVICE_COVER签名URL细节 | 沿用公开素材签名机制默认值 | 未决 |
| B7 | 商家端服务销量/预约量展示 | 待订单域统计就绪 | 未决 |

## 六、环境与工作树

- 共享MySQL 33452旧实例（mer001-s4遗留）于2026-09-23全量跑批中死亡；数据目录保留在 D:/Temp/mer001-s4-mysql-0a9e8e2fe9c7438f86a54072656f6795/（可恢复）。协调者临时实例（全新datadir）占用33452支撑本轮验证，验收后处置。
- Redis 16383（ms1-redis容器）与ClamAV持续运行未动。
- 工作树：本轮新增 wt-svc-fix/wt-ntf-svc-review/wt-adm001-write/wt-mer-stores/wt-m-svc-pages/wt-qa-svc-stores/wt-ledger，全部对应分支已推送；清理待各PR合并后按"已合并+无未提交+证据归档+无运行依赖"四条件逐个核验。

## 七、未完成与如实声明

- 完整"发布服务→消费者看到→预约"闭环仍不含预约（排期/订单域不在本轮范围）。
- 通知端到端人工验收、模拟器VIS、真机验证未做，不宣称完成。
- 各PR合并后CI以GitHub记录为准；本记录不冒充生产部署或真实手机验证。
