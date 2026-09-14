# M-001 工程壳候选交付

范围：EPIC-20 → ST-FE-M-01 → M-001 → typecheck / MINI-001～004。依据 SSOT、商家最终 PRD §5.2、20号前端基线和21号验收补充；2026-09-14 根 Work 明确解除 GOV-001/C-001 依赖并启动本 Issue，资料中旧 BLOCKED/暂停为历史。仅候选 Review，不标 DONE，不合并。

## 基线与唯一所有权

- 实际任务：01a09d80-f55f-7be0-8f72-86354e54518c；独立 worktree b082；分支 codex/m-001-merchant-shell。
- 真实远端 develop：2487d7302383d004ac6e6b3c5ef8260abf32df17，tree ed35cff8f205c9dd8769ea1d13a4d30aa8002603。直接 git fetch 建分支，没有把本地不同元数据 SHA 当远端对象，没有资料重新导入。
- C-End 子代理 c_end 独占公共文件并提交 b1b67133dc42c7155c8fc0f28ec7258ba0c911c1，随后释放：frontend-miniapp/src/app.config.ts、src/consumer/pages/shell/index.tsx、package.json、package-check.cjs、platform-smoke.cjs。分别承担普通分包、最小中性导航、测试入口、真实分包产物/包体检查、可指定新证据目录。
- Merchant 仅编辑 src/merchant/**；应用及初始测试提交 a401c56ecdafd41cefb0aa1bc397d5651ccce5e3。后续平台脚本/证据提交由 Git 历史和 PR head 标识。
- QA 子代理 qa_review 只读审阅与独立运行34测试，未写文件。发现并复现旧页面 dispose 清除新页面准入的竞态；Merchant 以 ownedRevision 修复，并覆盖新页面正在查询/已经通过两种负例，QA复审通过。
- 未改锁文件、shared、登录壳、AppID、backend、frontend-admin、CI、SSOT、API、Schema或Event。DevTools自动改写 project.config.json 的换行格式由 C-End 核对并恢复，不纳入 PR。

## AC 对照

| AC | 工程结果与证据 |
|---|---|
| 1 | 一个 Taro/React 小程序，普通分包 root=merchant，页面 pages/workspace/index，无 independent。沿用 consumerFixture 与 WorkspaceProvider，游客 AppID 仅工程运行。真实 weapp 构建和原生导航加载新分包；见 build-weapp.txt、package-inventory.txt、merchant-runtime-platform.json。 |
| 2 | 中性示例含允许/拒绝/查询失败/重试，允许才渲染样本；返回用户清除商家坐标并保留同一内部用户。34测试及实际微信状态断言通过；不是入驻或商家首页设计。 |
| 3 | 每次 useDidShow 与内部重试均经过准入适配；深链 query 从不注入权限或坐标。先失效旧上下文再查准入；拒绝/错误不放行。缓存、准入在途、样本在途及本地页面结果随 revision 失效，旧页清理不能覆盖新页。真实微信伪造深链及切换后超过1600ms的旧结果负例通过；unit-tests.txt另覆盖登出/撤权/换用户/查询竞态。 |
| 4 | 五个共享接入文件仅 C-End 编辑，独立commit与释放见上；Merchant无公共目录编辑。无真实商家设计稿、原始素材使用或自创产品页。 |
| 5 | 未实现 M-002～004、订单/履约/支付/签约/权限业务；全部运行边界和待接入项如下。 |

## 实际验证

- npm ci 使用既有锁成功；Node22.23.1/npm10.9.8；typecheck通过。
- npm test：34/34 PASS，0失败/跳过；原共享14项保持原文，新增商家20项。覆盖允许/拒绝/错误/重试、同用户返回、查询与样本延迟成功/失败、门店/用户切换、退出/撤权、旧页面dispose、真实适配CCR阻断、字符串ID/金额及服务端样本status/actions原样保留。
- build:weapp成功；check:package通过：主包312004B，merchant普通分包7180B，总319184B，25文件。主包与分包分别累加，无双计；实际产物 app.json 与 merchant JS 哈希已写入平台报告。
- 2026-09-14 再次HTTP200读取微信官方配额：主包/单分包2M，总30M（代开发20M）；工程使用保守总20M。见 package-rules-current.json，官方来源 https://developers.weixin.qq.com/miniprogram/dev/framework/subpackages.html 。本地字节检查不是平台上传验证。
- 真实工具：DevTools2.01.2510280、SDK3.17.2、iPhone12/13(Pro)、390×753、DPR3，详见 versions.json 和平台JSON。当前IDE HTTP为38259；原9420已不可沿用。仅任务进程 NO_PROXY，自动化端口19420后同工程恢复使用19421，未改全局网络/防火墙或关闭其他工程。
- 最终 merchant-acceptance-platform.json 与 platform-acceptance-run.txt 为完整实际回归 PASS_RUNTIME_SCREENSHOTS_NOT_CAPTURED：原用户壳计数/导航/诊断读取清除重注入、全部商家正负例、返回和伪造深链均通过，exceptions=[]，screenshots=false。merchant-runtime-platform.json 是较早的商家单独通过记录，不能替代最终回归。原生 App.evaluate 调用真实 Taro page.eh tap 回调，不使用 setData、不 mock wx、不用浏览器替代。physicalClickVerified=false，不能声称物理点击或真机通过。
- 新商家原生布局：允许态容器0～390、bottom461；错误态bottom287；回用户bottom329；原生滚动区域390×753。安全下界为 min(753,810-(844-753))=719；无横向溢出，工程内容在安全区域内。
- 截图通道 App.captureScreenshot 两次超时。失败/时序恢复记录均保留；未取得新的商家截图，未复用 C-001 的0商家分包截图。原生状态/布局已验与截图未取得分别报告。VIS属后续真实产品页，不以本中性工程壳新增视觉门禁。
- 新增原用户壳回归时保留的 merchant-final/verified/regression 失败为导航期间只读求值被DevTools拒绝（Uncaught [object Object]），merchant-complete曾在8秒返回渲染期限超时。当前测试以15秒有界实际文字条件等待，导航期间只读求值错误另记transientReadErrors，不重放点击，不替换页面数据、不删负例。最终同一已构建应用全部断言通过；应用源码与编译哈希未因工具时序调整而变化。失败未标N/A，也未用之前的单独PASS掩盖回归。
- PR #7 base develop，草稿；初始实现head a401c56 的 CI run34796300241全部6 job成功（backend、repository-policy、frontend-inventory、web-build、miniapp-weapp-build、contract-smoke），非最终证据head结果。最后head CI在PR最终描述/交接中固定，不能拿初始运行冒称最后head已过。

## 复现

在 frontend-miniapp 执行 npm ci、npm run typecheck、npm test、npm run build:weapp、npm run check:package。工具对本工程启用 automation 后设置 WECHAT_WS=ws://127.0.0.1:19421；WECHAT_EVIDENCE_LABEL使用新标签；npm run test:merchant-platform。默认尝试截图，每张截图20秒超时即失败；若独立验证行为，显式 WECHAT_CAPTURE_SCREENSHOTS=0，报告状态为 PASS_RUNTIME_SCREENSHOTS_NOT_CAPTURED，不能伪装截图完成。

原C-End平台脚本若重跑，设置 WECHAT_EVIDENCE_DIR=src/merchant/evidence 和独立标签，避免覆盖原共享历史证据。本次商家脚本也包含原用户壳计数、诊断样本读取/清除/重新注入回归。

## 未验收与风险

- CCR-ACR-001仍OPEN_SPEC_REQUIRED，AUTH-001建立并由@blueXYing评审真实会话、准入/权限、审核/签约/门店/子账号、401/403与失效/刷新等契约。本地 AdmissionResult 仅私有工程注入，无 endpoint/公共DTO/SDK；realAdmission明确抛出CCR阻断。
- 允许/拒绝样本没有映射业务审核或门店状态；不把冻结/下线统一当拒绝。SSOT下线存量履约与售后、PRD冻结历史订单/待处理售后例外，必须由将来获准契约提供真实动作与服务端校验，本次没有验收这些业务。
- 无真实登录、签约、支付/扫码/上传等 MINI-006、业务 E2E、真机、平台上传或商家产品VIS验收；商家设计/素材仍待对应页面Issue。不扩展M002～004。
- 截图/模拟器导航工具偶发超时的原记录保留；一次fixture/runtime通过不代表真实后端授权。截图未取得不声明为PASS。
- PR及Contract审核人登记@blueXYing；GitHub拒绝向PR作者本人发送review request（422），保留正文/CODEOWNERS审核归属，不擅自改派审核人。
- 回滚可revert本PR新增商家实现与披露的C-End5文件接入；没有数据迁移、业务写入或不可逆状态变更。合并须用户另行批准。
