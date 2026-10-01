# 运营售后真实页面联调：独立 QA

日期：2026-10-01。状态：QA_COMPLETE。起始HEAD：`e2a6ce61d04ddfb783b0e245183e509a4734e0b8`。3个真实页面联调用例首次运行全部通过，0 skip/failure/flaky；剩余可复现运营UI缺陷0。仅写运营测试和本报告；根代理负责生产UI及统一提交，后端代理负责隔离联调环境。

## 已独立核验的本次 CI

[PR99](https://github.com/blueXYing/pet-platform/pull/99) 的 head 为上述e2a6ce6；[CI run36828547377](https://github.com/blueXYing/pet-platform/actions/runs/36828547377) 六个job均SUCCESS。下载本次 `backend-test-reports` artifact（id11147326947，未过期）到本机专用ignored cache，逐份解析140个Surefire XML：986 tests /0 failures /0 errors /0 skipped，suite名称无重复。不是沿用旧本地37项分轮结果。

本次CI的新增 `AfterSaleOptionsConflictHttpAcceptanceTest` 单次7项全部通过，XML SHA256 `44f858e3cc397fb854fcf9dbfece4aca561366a82cd5ad3af49e5e4caf25adbf`；既有HTTP4项、Evidence HTTP3项全部通过。另下载本次web artifact（id11145669882）的实际results.json，独立解析66 expected/pass、2 skipped、0 unexpected/flaky；SHA256 `caac61ee28e22485cc4a4223fce8a41157f6b359f8c59992241ab200c10b0bbe`。Linux CI的运营测试仍为HTTP拦截/工作台fixture，两个已有live opt-in测试未设置环境时跳过；不称为真实页面联调。微信真机及VIS也不由本次CI验证。

## 本批实际联调边界

使用当前生产运营构建，经现有受控代理访问持久在线Spring Boot、隔离MySQL/Redis及真实认证/RBAC/事务；外部微信、离线签名支付通知、审核、扫描、OSS provider仍为test-only，使用隔离业务资料及固定2030测试时钟。联调服务显式导入根按正式PRD冻结的目录资源，不沿用QA代码目录；该资源/联调增量在上述CI head之后，不以e2a6ce6 CI证明新资源已验收。runtime、凭证、原始网络内容只留ignored路径，不进入正式报告。生产运营build与新增live测试typecheck通过；生产运营src本批未修改。

主要用例：真实网页登录及指定店列表；完整P4历史逐笔人工核对后受理；要求USER补证及另一真实参与方补证后刷新；非退款终局；真实提交成功后仅丢一次客户端回执、撤权、保留原业务journal、重新真实登录后显式原UUID重放及业务计数不增加；独立于以上fixture buyer动作，另以根执行的实际微信C/M页面和本代理执行的运营A页面完成同工单全链路。

丢回执使用经根确认的受控客户端网络故障注入：仅单次指定真实POST以 `route.fetch()` 透传实际后端，确认真实回执后 `route.abort()`；不fulfill、不改payload、不伪造后端响应，其余读写直连。后端buyer/control动作不算作C端页面交互。

## 页面联调结果

本批第一次实际运营页面运行：前两个独立lane已通过（6.7秒、4.5秒），无生产运营UI改动/缺陷。

- P4：实际登录与指定店列表，当前私图经真实grant/consume加载且naturalWidth有效；三笔历史全部真实读取，最后一笔确认前受理持续禁用；受理、USER补证要求、另一真实buyer参与方HTTP补证、刷新、OTHER终局均通过实际运营页面。最终RESOLVED/version4；本卷2批证据、1决定、5个命令/transition/statusLog/outbox。buyer/control补证属于真实参与方fixture动作，不算C页面操作。
- 恢复：独立工单受理已真实200提交，再有意丢客户端ACK；原UUID及完整body持久化，实际撤handle/decide保留read后原请求403，页面登出并清图；业务journal仍在。保存origin state后关闭旧context，以新context真实登录、指定店列表及详情重新授权，人工重试同UUID取得完全相同回执。三次发送的UUID/body一致（成功丢ACK、403、成功重放）；重放前后完整SQL业务摘要相同。最终PROCESSING/version1，2命令、1证据批、0决定、2个transition/statusLog/outbox。该浏览器context恢复不称实际OS重启。

上述两lane的6种资金相关产物计数及channelCalls均0；无pageerror。未以业务响应fulfill替代真实服务。

第三用例为同工单C/M/A页面协作，耗时约8.1分钟（包含两次跨代理UI握手等待）：根在微信C页面选正式目录、输入问题说明，真实HTTP创建并由本人详情确认后发CREATED；运营实际页面从指定店列表打开，受理至version1、要求USER补证至version2，再发只含业务坐标的ignored握手。根随后在实际C页面上传PNG（仅相册选择为外部fixture，Taro upload真实HTTP）、满足当前USER补证轮次至version3；本人私图真实grant/consume。根真实切换OWNER账号，memberships/admission进入当前店，在实际M列表/详情读取私图，再经意见抽屉提交AGREE至version4，发PARTIES_RESPONDED。

本代理的A页面刷新读取双方完整卷宗，确认两批USER证据和一批MERCHANT意见；SQL独立核当前version4、证据批次比创建增加2；经实际终局选择、原因、确认checkbox提交OTHER。最终页面及独立SQL均RESOLVED/version5、证据3批、决定1笔、6个命令/transition/statusLog/outbox；业务journal清空。第三用例没有buyer/control写；本代理没有代替根操作微信C/M页面。此用例的C/M页面证据由根执行与记录，A页面/SQL交叉核验由独立QA执行。

最终独立读取Playwright `results.json`：3 expected/pass /0 skipped /0 unexpected /0 flaky，总497.238秒；SHA256 `48abc7ee26e5d790d941c3d384a44a7de50b32266ca5bf73b90ca9a7c8452e01`。三用例均无pageerror，6种资金相关产物及channelCalls均0。trace/video/screenshot关闭，真实session bearer、runtime原文、私图及原始网络内容不进入正式仓库报告；本地原始结果/日志仅在精确ignored `.cache/aftersale-joint-qa/`。

## 收尾与边界

Playwright及其唯一4174生产preview已经结束，独立检查4174无监听；本树 `git diff --check` 通过。本代理无commit/push，无修改用户已有服务/配置。后端尚留给根读取C/M最终卷宗，临时数据库/Redis及runtime的最终精确清理由后端Owner记录在同目录 `BACKEND-HANDOFF.md`；本报告不提前声称后端已清理。

本次证明确有同单真实页面连接本机后端闭环；微信环境为开发者工具页面，外部provider与相册选择仍为test-only，不声明物理微信真机、VIS、生产外部provider、真实支付/退款渠道或生产环境启用通过。新增资源及live脚本提交后CI由根另行记录，不以此前e2a6ce6 CI覆盖尚未提交的增量。
