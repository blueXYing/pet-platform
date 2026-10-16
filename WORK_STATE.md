# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 41.0
UPDATED_AT: 2026-10-16
CURRENT_PHASE: W2_WAVE_IN_PROGRESS
CURRENT_STATUS: DOCS_ONLY_CATALOG_REAUDIT_THROUGH_20261016_ENGINEERING_STATE_UNCHANGED_FROM_40_0
VERIFIED_BASELINE: PR98 已合入 develop e3b846e5038aa617e42a919862f20a927057f53e。PR99 CCR head e2a6ce61d04ddfb783b0e245183e509a4734e0b8 的 CI36828547377 六项成功，实际后端报告140 suites/986 tests/0 failures/errors/skips；不覆盖本批正式资源与联调增量。本批head 4bb317f63a0851eb50325659acd557f338379d06的[CI36842324802](https://github.com/blueXYing/pet-platform/actions/runs/36842324802)六项全部SUCCESS（backend 2026-10-01T10:03:42Z完成），无遗留未核项。2026-10-02经用户批准完成5项VIS P1修复并经真实渲染像素级复验全部关闭（[VIS-OVERLAY §12](planning/progress/2026-10-01/aftersale-pages/joint/VIS-OVERLAY-HANDOFF.md)、[P1-FIX-VERIFICATION](planning/progress/2026-10-02/aftersale-vis/P1-FIX-VERIFICATION.md)），其间发现Taro 4.1.5动态data-*/WXSS属性选择器静默失配根因并改为类名变体。
NEXT_PHASE: VIS规格级叠图、5项P1修复与像素级复验已完成（C apply/M list/M detail叠图边缘归零）。下一步：物理微信真机按DEVICE-PREP-HANDOFF清单验收（含P1-4修后真机复核）→人工审阅草稿，不自动合并。services页同类data-*/属性选择器迁移需独立Issue。
NEXT_PHASE_APPROVED: 用户已授权多角色实施并要求子代理使用Figma，重连后回复“已连接”，两项具体CCR后回复“批准”，随后明确“开始下一步”。本轮完成最终PRD目录登记、独立资源及真实三端本机联调。生产启用、资金能力、SSOT变更、PR合并未获授权。目录中文内容已在最终PRD明确，不需重复产品裁决。

## 基线与切片

前一版状态见[联调前状态](planning/history/WORK_STATE_BEFORE_20261001_AFS_JOINT.md)。P1–P4执行SSOT §40/PRD31，真实资金依据OD-W0-001仍未具备。

集成树codex/aftersale-pages-20261001：C-005本人列表/详情/资格/权威目录申请/补证/撤回，M-004当前店真实OWNER卷宗/四类意见/补证，A-004独立read/handle/decide权限/指定店列表/完整P4历史/受理/补证/三种非退款决定。本轮三端UI生产源码无新增改动，新增正式目录、配置测试、隔离后端harness及运营live用例。默认生产开关与用户原桌面配置保持原样。

三实现角色此前实际使用Figma插件；本轮子代理又实际在线核C129:10572与M40:1061/1345/1500及素材，见[原稿审查](planning/progress/2026-10-01/aftersale-pages/joint/CATALOG-VIS-HANDOFF.md)。运营无桌面原稿，沿已有工作台规范；缺投影资料不虚构，未声称一比一/VIS通过。

## 已批准契约与正式目录

- CCR-AFS-PAGE-OPTIONS-001及CCR-AFS-CONFLICT-001已实施，详见[CCR记录](planning/progress/2026-10-01/aftersale-pages/CCR-IMPLEMENTATION.md)。确定case/finalSet冲突与未知结果分开恢复；已成功原UUID重放优先且当前鉴权仍必要。
- 最终C PRD§5.1.28、M§5.10已定义完整问题类型和诉求各5项。根独立Word核验后登记工程key及[可显式导入资源](backend/pet-boot/src/main/resources/aftersale-catalog.yml)，装配与来源见[CATALOG-CONFIG](planning/progress/2026-10-01/aftersale-pages/joint/CATALOG-CONFIG.md)。旧报告“目录内容待裁决”保留为历史，此误判已由本轮最终PRD原文消除。
- 正式诉求完整保留退款/部分补偿，它们不授权出款。目录资源没有workflow/http/worker/refund/provider开关，默认application.yml未改；缺配置仍失败关闭，不用QA目录兜底。

前批Contract50/51、Internal07、HTTP10、OpenAPI11及Error12变化已披露；本轮无新增Contract/Schema/Event/Scheduler/SSOT变化。

## 验收结果与来源

PR99 e2a6ce6 [CI36828547377](https://github.com/blueXYing/pet-platform/actions/runs/36828547377)实际artifact已独立下载逐XML解析：986通过、0跳过；新增OptionsConflict HTTP单轮7通过，既有HTTP4/Evidence3通过。运营artifact66通过、2既有live opt-in跳过。这是该head完整CI，不把旧本地37分轮或fixture浏览器当真实页面联调。

本批head 4bb317f CI36842324802六项全部SUCCESS（backend 2026-10-01T10:03:42Z完成），增量CI核对完成。VIS规格级完整叠图见[VIS-OVERLAY-HANDOFF](planning/progress/2026-10-01/aftersale-pages/joint/VIS-OVERLAY-HANDOFF.md)：C129:10572与M40:1061/1345/1500逐属性比对，5项P1（C卡宽+0.992设计px、M详情卡宽+1.0、M列表金额字号小1.92设计px、M机器码typeCode/demandCode直出、M完成态按钮未按稿分状态）待裁决，16项P2及设计缺稿（C index/detail整页）已登记；像素级渲染叠图与真机字体回退仍未验。物理真机前置物料见[DEVICE-PREP-HANDOFF](planning/progress/2026-10-01/aftersale-pages/joint/DEVICE-PREP-HANDOFF.md)：typecheck/weapp/分包门禁与带上传开关dev构建PASS（主包774305/总4376887字节），预览二维码已生成（DevTools本地预览，约25分钟时效），LAN可达方案、隔离启动/清理recipe与13用例+6失败关闭态清单就绪；真机实测未执行。

本轮[同单联调](planning/progress/2026-10-01/aftersale-pages/joint/MINI-JOINT-HANDOFF.md)及[独立QA](planning/progress/2026-10-01/aftersale-pages/joint/ADMIN-QA-HANDOFF.md)：真实微信C页面目录创建→实际运营受理/USER补证要求→实际C上传/补证→真实OWNER准入后M意见→运营OTHER终局→C/M读取。真实SQL最终RESOLVED/version5、3证据批、唯一决定1、6命令/迁移/日志/Outbox，全部资金产物/渠道调用0。运营live3项首次全部通过，含P4三笔完整历史核对和真实commit丢ACK/撤权403/新context重登/同UUID重放无新增副作用。

真实服务为隔离Spring Boot/MySQL/Redis/认证/RBAC/事务；微信exchange、相册选择、离线付款通知、审核/扫描/内存私有OSS为明确fixture。未fake业务HTTP或page data。实际模拟器390×753/pixelRatio3/基础库3.17.2，只一个窗口。后端live显式Stop单轮JUnit1通过，906.049s；最终相关单轮34通过（Catalog3/OptionsHTTP边界4/Configuration27），两轮不混称一次35。精确两容器/本批服务已清理，原Redis不受影响，runtime凭据已删除，见[后端交接](planning/progress/2026-10-01/aftersale-pages/joint/BACKEND-HANDOFF.md)。恢复默认生产mini构建、typecheck/分包门禁通过，原用户配置hash不变。

## 未完成范围

完整C-005/M-004/A-004/QA-005仍未DONE。物理真机尚无手机实测证据；本机回环不能当手机后端。Figma精确字体/汉字回退、缺状态稿、机器code显示及约1设计像素卡片宽度差的5项P1已于2026-10-02修复并像素级复验关闭；C apply/M list/M detail三页像素级叠图完成（M抽屉豁免：设计为旧稿无可比状态）。真机字体回退、SVG镜像、dashed线型、statusBar几何及P1-4修后真机复核仍待物理真机，不设自造容差或冒称VIS通过。STAFF、全局运营聚合、REF-001、公开资金、通知送达及积分券评价未提前完成。

PR99保持草稿。本批提交后的CI须另外核验并记录，不用旧head成功覆盖新head；未运行生产迁移、开启环境、上传体验版、发布或合入develop/main；2026-10-01仅生成过DevTools本地预览二维码（未上传体验版）。

## 2026-10-10 目录重核（STATE_VERSION 39.0，纯文档）

本版为 docs-only 版本升位：上表与各节工程事实（38.0，2026-10-01/02 批次收尾，前一存档 35.0 见 [history](planning/history/WORK_STATE_BEFORE_20261001_AFS_JOINT.md)）原样保留，未重核未改写；工程状态字段以 38.0 内容为准，Issue 级权威状态见 [ISSUE_CATALOG](planning/ISSUE_CATALOG.csv)（本次重核至 2026-10-10）。38.0 之后（2026-10-02~10-10）的工程推进未逐版记录于本文件，由 PR#100~#141 与目录承担记录。

重核范围：上次目录重核停在 2026-10-07（PR#125，merge 5eed641）；其后 PR#126~#141 共 16 个 PR 合入 develop（M 端排期分页消费、C 端预约下单/支付发起页与 HTTP、商家手动接单/拒单、C 端退款申请、商家订单列表读侧、C 端改期、核心交易闭环模拟器 E2E 一轮全链通过、评价资格与创建、接送履约解锁、部署开关组合清单与备注宽容直收裁决、五 tab 导航与静默登录、券/积分安全链 403 修复、CI 提速三连）。ISSUE_CATALOG 9 行更新（TX-002/ORD-001/ORD-003/REV-001/C-003/C-004/C-005/M-003→DONE；QA-005→IN_PROGRESS，一轮模拟器 E2E 已过、运营Web主链与二轮未做），DEPENDENCY_GRAPH 补 ORD-003/REV-001 节点与 2026-10-10 注记。

遗留与红线不变：物理真机验收、二轮 E2E、运营Web主链 E2E、REF-002 商家退款决定面、A 端页面族仍未交付；本批零代码/契约/脚本改动，不合并本 PR。

## 2026-10-14 目录重核（STATE_VERSION 40.0，纯文档）

本版为 docs-only 版本升位：39.0 及以前各节工程事实原样保留，未重核未改写；Issue 级权威状态见 [ISSUE_CATALOG](planning/ISSUE_CATALOG.csv)（本次重核至 2026-10-14）。39.0 之后（2026-10-10~10-14）的工程推进由 PR#142~#163 与目录承担记录。

重核范围：上次目录重核停在 2026-10-10（PR#142）；其后 PR#143~#163 共 20 个 PR 合入 develop（HEAD 2922ca4）。ISSUE_CATALOG 8 行更新：REF-002/REF-003/AFS-002/REV-002/ADM-003/A-004→DONE（#145 商家退款决定面+#156 二轮 E2E 实测、#160 售后资金闭环按 32号裁决原路退回执行且全量 refund/aftersale 套件复跑全绿、#144 评价申诉一次终局+部分退款 scoreIncluded 联动测试既有）、PTS-001/A-005→IN_PROGRESS（#157 签到+拉新赚取切片与 admin 积分任务配置页交付，订单奖励与券/社区/百科/台账/设置治理未交付）。

QA-005 如实登记（仍 IN_PROGRESS）：二轮模拟器 E2E 已过（PR#156，走查矩阵 6 场景全链通过：核心闭环回归、商家退款处理 57号、评价+申诉 56号、售后 50/51号、改期 46+39号含 30 分钟自动确认终态、商家订单列表 45号，53 张截图）；未覆盖：售后平台裁决 UI 不在本轮矩阵、refund_order 创建后禁止核销反向分支未触发、运营Web主链整链矩阵与物理真机验收仍未做。

32号裁决七页批新能力（收藏/足迹 #155 契约58、地址管理 #158 契约59、成为服务者入口 #161、积分商城展示态 #162、签到/拉新 C 面 #157 契约60）已交付且代码在树，但无对应 Issue 行（原 66 行任务清单不含 32号新增能力，未自造 IssueID），状态与证据见本次重核 PR body 与 [DEPENDENCY_GRAPH](planning/DEPENDENCY_GRAPH.md) 2026-10-14 注记新能力节点。

遗留与红线不变：物理真机验收、运营Web主链整链 E2E、REF-004 24小时超时自动批准、A-002/A-003、SCH-004 专项验收、CPN/COM/THD/QA-002/QA-003 等仍 BLOCKED；默认关闭开关不因状态翻 DONE 而开启；本批零代码/契约/脚本改动，不合并本 PR。

## 2026-10-16 目录重核（STATE_VERSION 41.0，纯文档）

本版为 docs-only 版本升位：40.0 及以前各节工程事实原样保留，未重核未改写；Issue 级权威状态见 [ISSUE_CATALOG](planning/ISSUE_CATALOG.csv)（本次重核至 2026-10-16）。40.0 之后（2026-10-14~10-16）的工程推进由 PR#165~#174 与目录承担记录。唯一非目录改动为 PRD 对齐（见下），零代码/契约/Schema/Event 变化。

重核范围：上次目录重核停在 2026-10-14（PR#164，merge d13faa8）；其后 PR#165~#174 共 10 个 PR 合入 develop（HEAD 911230cf，逐一经 `git log --merges` 与 PR body 核实）：#165 百科 C 端读侧（62 号契约+SQL62+列表/详情两页）、#166 运营交易查询面（63 号 v0.1+六页+服务审核页）、#167 SCH-004 专项验收收口（Sch004JointAcceptanceMySqlTest 联链 4 用例，schedule-biz 51/51）、#168 券全链（61 号，CPN-001/002+QA-002 券并发+容量补缺+C 端选券入口）、#169 CI retrigger 空提交、#170 积分订单奖励绑单发放+退款按比例扣回（60 号修订 §7）、#171 运营治理查询面（64 号+商家台账/排期查看/员工治理三块页）、#172 运营处置动作（63 号 v0.2+33 号裁决补充：渠道查单/重试、异常关单、商家下线）、#173 支付超时×回调并发矩阵（QA-003）+REF-004 核实收口、#174 首付事实 O(1) 端口（CCR-POINTS-ORDER-001 收口）。

ISSUE_CATALOG 11 行翻转 BLOCKED/IN_PROGRESS→DONE（逐行证据）：SCH-004→DONE（#167 专项验收全绿，53 号联链）；REF-004→DONE（#173 B 节核实链+新增 RefundMerchantTimeoutAcceptanceMySqlTest+结论文档 [docs/07-testing/17 号](docs/07-testing/17-REF004-商家退款超时自动批准核实-v1.0.md)）；CPN-001/CPN-002→DONE（#168：CouponCommandApi 冻结/消费/释放+COUPON_REFUND_RESTORE 返券消费者，pet-coupon-biz 18/18、order-biz 45/45）；PTS-001/PTS-002→DONE（#170 绑单发放+Clawback 消费者，points-biz 20/20；#174 O(1) 首付事实端口收口 CCR 缺口）；ADM-002→DONE（#166 63 号七条只读查询+#172 63 号 v0.2 四条处置写路由）；A-003→DONE（#166 六页[订单总览/详情、退款列表/详情、核销列表/详情]+#172 处置按钮[异常关单/商家下线/渠道查单/重试]，前端 typecheck/build/boundaries 过；整链 E2E 归 QA-005 未覆盖，如实注记）；QA-002→DONE（#168：CouponLifecycleMySqlTest 11 用例[CON-003 同券并发一单胜]+OrderCreationMySqlTest 增 3 用例+ScheduleSoldOutLinkMySqlTest 增十并发用例[CON-002 补缺]，CON-001 既有覆盖核验无缺口）；QA-003→DONE（#173：PayTimeoutCallbackMySqlTest 5/5 三轮复跑全绿，矩阵覆盖 CON-014/CON-015/FLT-005 及 PAY-004 查单推进；FLT-003/FLT-004 为 PAY-003 既有验收覆盖项，本轮矩阵复核未发现生产 bug）；M-004→DONE（M 端退款/核销/售后/评价/消息页面均在树[refund 3 页/staff-verify/aftersale 3 页/reviews/messages]，二轮 E2E 已覆盖商家退款处理；第三方核销子项经用户 2026-10-14 裁决暂不做，随 THD-001 冻结，不在本行记欠账）。

保留状态并补注记（8 行）：A-002 保持 BLOCKED（服务审核页 #166+治理查询页 #171 已交付两期切片；写侧治理动作[商家冻结/解冻、员工处置等]无授权仍开放，需 CCR）；ADM-001 保持 IN_PROGRESS（#166 服务审核面+#171 治理查询补齐，写侧动作缺口同上）；A-005 保持 IN_PROGRESS（#157 积分任务配置页已交付，券/百科/台账运营治理批与正式封面图上传在途）；C-006 保持 IN_PROGRESS（券页 #168 选券入口+券列表读面、百科 #165 两页、签到/拉新 #157、积分商城展示态 #162 已交付；社区经用户 2026-10-14 裁决不做，剩余状态待目录 Owner 终判）；COM-001 保持 BLOCKED 并补记用户裁决（2026-10-14：社区不做，#165 落地时社区一行代码未动）；THD-001 保持 BLOCKED 并补记用户裁决（2026-10-14：第三方团购核销暂不做）；QA-004 保持 IN_PROGRESS（PR #175 CREATE_REFUND×VERIFY 并发矩阵在途**未合**，2026-10-16 核实仍 OPEN，不得记为已合）；QA-005 保持 IN_PROGRESS（一轮核心闭环 #133、二轮走查矩阵 #156 已过；三轮矩阵在途——截至本次重核无已合 PR/分支证据，以下一轮合入为准；运营Web主链整链与物理真机仍未覆盖）。

PRD 对齐（用户 2026-10-15 裁决"按 SSOT 执行"=售后全额退款券返还）：C 端 PRD §5.1.21「我的优惠券」退款返还规则中"订单售后存在退款时，该订单使用的抵扣券不返还（已核销不退）"与 SSOT §9.1 冲突，已修订为按 SSOT §9.1/§9.2 口径（售后全额退款且原券仍处有效期→恢复可用返还、不足 24h 延长至退款成功后 24h；部分退款不返），修订处插入修订注记 bullet 标注裁决来源与日期；登记文档 [docs/01-prd/34 号](docs/01-prd/34-售后退款优惠券返还SSOT对齐裁决补充-v1.0.md)，收口 CCR-CPN-LIFECYCLE-RULES-001 §2"待 PRD 修订轮"项。运营端 PRD §6.4 券实例状态机本已与 SSOT 一致，未改动；其余 PRD 内容零改动。

遗留与红线不变：物理真机验收、运营Web主链整链 E2E、QA-004（#175 在途）、QA-005 三轮、A-002 写侧治理动作授权、ADM-001 写侧缺口、券实例无发放渠道（模板管理不在 Scope）、百科封面图正式素材、默认关闭开关（pet.coupon.lifecycle.enabled、pet.points.refund-clawback.enabled、pet.admin.trade.http.enabled、pet.admin.governance.http.enabled 等）不因状态翻 DONE 而开启；本批零代码/契约/Schema 改动，不合并本 PR。
