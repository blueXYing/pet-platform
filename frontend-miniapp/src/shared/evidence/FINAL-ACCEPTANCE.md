# C-001 工程壳验收汇总 · 2026-09-11

结论：C-001 工程壳阶段 AC1–6 与要求的 typecheck、MINI-001/003/004/005 已具备通过证据，提交根Work作完成与依赖解锁审核。PR保持Draft，未合并；不自行创建或启动M-001。本文覆盖RESULTS.md和PLATFORM-FOLLOWUP.md中“第二窗口待补”的历史状态。

## 第二真实窗口

用户确认切换后，任务按固定入口设置 `WECHAT_EVIDENCE_LABEL=second-window` 运行 `npm run test:platform`。读取实际systemInfo：iPhone 6/7/8 Plus，414×672，DPR3，基础库3.17.2；明确不同于首窗口390×753。未mock systemInfo，未修改页面状态制造结果。

platform-smoke-second-window.json及platform-second-window.txt记录退出0：真实渲染、计数、native tap回调引发navigateTo、样本显示、清除后消失、重新注入均通过。platform-shell-second-window.png与platform-diagnostics-second-window.png已直接查看，文字/按钮/内容完整，无横向溢出。这里“native tap回调”不是物理点击，physicalClickVerified仍为false。

platform-layout-second-window.json来自真实wx原生查询：屏幕高736、窗口高672、safeArea.bottom736；安全下界换算到页面为672。诊断页容器左0/右414/底312、padding-bottom17px，viewport scrollWidth414/scrollHeight672。内容位于安全区内，无底部遮挡，不需要滚动。当前工程示例无固定底部控件或长业务列表；这些后续页面需另测，不能外推本结果。

首窗口JSON与两张截图已与8c1981a提交逐字节核对相同，SHA256见first-window-preserved.json。独立文件名未覆盖首窗口。初次capture-shell超时保留platform-second-window-first-timeout.json；随后两次同工程auto重开（第一次后连接尚未就绪，第二次后成功），日志均保留。没有修改全局安全设置或关闭其他项目。

## Acceptance Criteria

| AC | 结论 | 依据 |
|---|---|---|
| 1 单Taro React TS微信入口/固定版本锁 | PASS | package.json/package-lock.json、versions.txt、typecheck.txt、build-weapp.txt；只有app.tsx，微信目标，无H5/App/第二商家工程 |
| 2 示例路由/局部状态/请求/平台Mock/交接 | PASS（工程壳） | 两个中性consumer页；14项内部测试及两个真实窗口平台实测；HANDOFF普通分包接入；无merchant业务 |
| 3 字符串ID金额/requestId/展示态actions/隔离 | PASS | MINI003/004内部测试覆盖延迟成功失败、切换/注销/撤权/同上下文重入；实际诊断页清除隐藏；没有前端订单展示态状态机 |
| 4 仅内部fixture，真实契约等待CCR | PASS（限制已落实） | selectTransport real明确阻断CCR-ACR-001；无新增会话endpoint、权限或签约公共DTO |
| 5 原稿/切图/截图证据承载 | PASS（工程壳） | visual-manifest.json字段完备，实际截图独立存储；示例不引用产品稿，无图片替代或产品1:1声明。VIS对当前中性页N/A |
| 6 固定共享基线/唯一Owner/限制披露 | PASS | 真实独立分支/提交/PR4；C-End唯一维护共享壳；Merchant只读复审；失败和平台边界均记录 |

## Required Tests

| Test | 工程壳阶段结论 | 精确范围 |
|---|---|---|
| typecheck | PASS | 最后源码检查通过，新增本次只有证据和交接文档 |
| MINI-001 | PASS | 微信目标构建、真实开发工具Stable2.01.2510280启动、SDK3.17.2与窗口记录；不代表真实微信业务API通过 |
| MINI-003 | PASS | 内部跨工作区/用户/商家/门店隔离测试；平台样本清除显示验证 |
| MINI-004 | PASS（内部fixture阶段） | 注入请求端口/错误/ID金额/requestId/返回展示态actions/real模式阻断；真实会话仍等待CCR，不作为本阶段已完成接入 |
| MINI-005 | PASS（当前工程壳） | 当前包体311582字节、单/总预算检查、官方来源2026-09-11核验；390与414两个真实窗口截图/布局，第二窗口补充安全区原生测量。当前无图片和实际商家分包，清晰度/真实分包加载N/A于当前范围，后续M/C页面必须复验 |

## DoD逐项与门禁归属

| DoD | 结论 |
|---|---|
| AC满足 | 本Issue工程范围PASS，见逐项映射 |
| 相关测试通过 | 本Issue要求测试在明确阶段范围内PASS；工具偶发超时作为已恢复风险保留 |
| 架构检查 | 本Issue差异只在frontend-miniapp Allowed，共享Owner/单入口/无商家反向业务导入符合基线；后端架构组合证据由根Work提供，见下方。不是本PR远端CI全绿声明，合并前由根Work核查门禁适用性 |
| 无未披露Contract/Schema/Event变化 | PASS，无这些公共文件变化；CCR-ACR-001继续OPEN，不自行解锁真实接口 |
| PR描述完整 | PR4将更新为两窗口证据和实际限制，保持Draft，审核人blueXYing |
| 遗留风险已说明 | PASS，列于下方；完成标记及M-001解锁由根Work审核，不由本Issue自行推动 |

根Work已核验并授权引用的架构组合证据：GOV002 PR5 head d8c2f080ac89618726fba0269ec99d6cb278dc53；[run34559354177](https://github.com/blueXYing/pet-platform/actions/runs/34559354177)/backend job103138705620为completed/success，merge ref837833d含head+base93ecce6，41项目、22JUnit、13Python通过。QA本地79180f3+8a98f52含PLAT6cfefa8；PLAT独立复验最终b375408b0c24bbbd28267d8624f285349d98c73d全通过。该CI的前端build skipped，未含C-001，不能据此声称PR4 CI通过；本Issue前端实测证据单独存储。

遗留范围：真实会话/权限CCR、真实支付/扫码/上传业务、真机、后续产品页原始切图与VIS、实际merchant分包和长页面安全区均未验收且不冒称通过。CLI/截图存在偶发超时，记录与同工程重连方法已给出；物理点击因桌面工具能力错误未验证，21号没有将物理输入方式设为独立工程壳硬门禁。未单独执行干净npm ci复装，锁文件CI安装由QA承接；无lint脚本，不虚构入口。
