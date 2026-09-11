# C-001 平台排查与分项结论（后续证据覆盖 RESULTS 初次失败状态）

第二窗口已完成：用户切换后的414×672实际测试/安全区测量通过，最新状态见FINAL-ACCEPTANCE.md。下文“待补”和人工操作是当时排查记录，保留用于复现。

2026-09-11：本次只在任务工程内更新配置、开发测试依赖和证据，未关闭无关进程、修改防火墙、安全或全局系统配置。初次日志保留，不把CLI退出0当通过。

## 已定位并解除的连接问题

- 工具路径 D:/soft/微信web开发者工具/cli.bat，真实版本 Stable 2.01.2510280（包版本/窗口标题核对）。
- 3799不是已占用监听端口，也不在Windows IPv4/IPv6排除列表；任务内临时绑定3799、9421、19420都成功后立即关闭。EACCES未能稳定复现，不能断言是系统保留端口或防火墙。
- 首轮日志显示旧IDE端口36376超时；之后实际发现9420由本次工具实例监听。3799是CLI内部回调服务器端口，9420是IDE HTTP端口，二者不是自动化WebSocket端口。
- 明确 `--port 9420 --auto-port 19420`，进程级 `NO_PROXY=127.0.0.1,localhost` 后成功。工具原实现本地HTTP请求已有proxy:false，不能把成功单独归因于NO_PROXY。
- 实际日志 `Using AppID: touristappid` / `√ auto` 之后，又连接19420验证SDK3.17.2、真实页面、截图；不是仅凭CLI输出判成功。见 wechat-auto-port19420.txt、wechat-listeners.json。
- 配置变更后的复跑曾在首页计数后超时（platform-recompile-timeout.json）；重新执行同工程auto后完整复跑通过，见wechat-auto-reconnect.txt。无法证明超时仅由配置变更引起，保留失败作为自动化稳定性限制。

## 平台实证与范围

`npm run test:platform` 使用 miniprogram-automator 0.12.1 连接真实开发工具。初次 Page 选择器/数据调用超时已留 platform-selector-timeout.json。改用官方SDK的App.evaluate读取真实Page渲染数据并调用Taro原生tap事件回调，不setData、不替换React状态、不mock wx。使用基线锁定Taro4.1.5的内部按钮节点标识，未来升级需复验脚本。

已验证：390×753、DPR3、iPhone12/13(Pro)模拟器；首页真实渲染和计数从0到1；按钮事件引发实际navigateTo；样本ID/金额字符串显示；清除上下文后旧样本消失；重新注入后重新显示；当前容器横向边界在0～390，内容高度339未超出窗口。platform-smoke.json/txt及platform-shell.png、platform-diagnostics.png为实际输出，截图已人工式视觉检查。

物理点击未验证：computer-use读取了窗口可访问树和实际计数；截图恢复重试报 `SetIsBorderRequired failed: 不支持此接口 (0x80004002)`，可访问按钮点击报 `coordinate input geometry is unavailable`。没有反复无效重试，也不把调用原生tap回调称作物理点击。现有SDK没有公开机型切换接口，本次未修改全局工具配置冒充多窗口。

## 21号测试补充分项

| 项目 | 本Issue当前结果 | 对DoD的含义 |
|---|---|---|
| MINI-001 typecheck/微信目标构建 | PASS | 原应用代码对应9535d024；当前类型检查再次通过 |
| MINI-001 开发工具启动/基础库记录 | PASS | 真实SDK3.17.2、工具2.01.2510280、单一游客AppID；配置固定基础库3.17.2。不是业务登录/支付通过 |
| MINI-003 基础隔离 | PASS（内部+平台样本显示） | 14项内部测试含跨用户/商家/门店/工作区的延迟回写；平台清除/重新进入实测 |
| MINI-004 平台隔离和fixture | PASS（工程壳） | 真实会话不在本阶段通过范围，CCR-ACR-001继续阻断；无新公共DTO/API |
| MINI-005 本地包体/检查入口 | PASS | 当前主包311582字节。官方文档直接HTTPS读取200：单主/分包2M、总30M、服务商代开发20M；脚本采用保守20MiB总预算及2MiB单包预算，真实上传压缩包校验尚未执行 |
| MINI-005 普通分包接入 | 检查与交接说明已建立；实际加载N/A于当前0分包壳 | Merchant未解锁，无商家页可加载。M-001加入真实普通分包后复验，不能提前造业务页面为测而测 |
| MINI-005 当前窗口布局 | PASS（单窗口） | 真实截图/容器范围，无横向溢出；未验证长内容贴底安全区或其他窗口 |
| MINI-005 多个窗口/安全区 | 缺验证 | 至少需另一个真实模拟器尺寸与相应截图/边界；不能以修改返回的systemInfo或浏览器viewport冒充 |
| MINI-005 图片清晰度/原始派生追踪 | 当前无图片，运行清晰度N/A；清单已建立 | 后续产品页面引入原始素材时必须执行，不是本中性无图壳新增完成条件 |
| VIS-001～004 | 当前未引用产品原稿，产品视觉N/A | 无原图还原交付声明，不以缺本Issue未使用的素材阻断壳；后续业务页面必须原图1:1 |
| MINI-006及真实业务回归 | 后续业务阶段N/A | 真实微信授权、支付、扫码、上传未实现或验收 |

当前C-001仍不声明完整DoD；最直接缺口是MINI-005多窗口/安全区验证。全仓架构/CI结果由GOV-002/QA门禁提供，不能由本地构建替代；M-001解锁由根Work核查。

## 最少人工操作与通过标准

当前工程已成功启动，无需用户登录或新增授权来复现游客壳。若工具重新启动后失去连接，在本目录执行：

```powershell
& 'D:/soft/微信web开发者工具/cli.bat' auto --project 'C:/Users/Administrator/.codex/worktrees/186f/宠物平台V1.0/frontend-miniapp' --port 9420 --auto-port 19420 --trust-project
npm run test:platform
```

1. 用户在已打开的 pet-platform-miniapp 微信开发工具模拟器机型/尺寸选择处，切换到另一个明显不同宽度（例如375或414），保持基础库3.17.2；不要只缩放预览比例。用户只需回复“已切换”，不需要自己运行命令。
2. 任务收到切换消息后设置 `$env:WECHAT_EVIDENCE_LABEL='second-window'` 并运行 `npm run test:platform`，为JSON/截图增加独立后缀，保留390宽窗口证据。脚本直接读取真实systemInfo，second-window若仍390宽明确失败，不允许用label冒充窗口变化。通过标准：实际windowWidth变化；两页文字/按钮完整、无横向溢出，滚动到底无底部安全区遮挡，测试真实通过。
3. 在模拟器实际点击“增加计数”“打开隔离验证页”，在诊断页依次读取样本、清除、重新注入并读取。通过标准：计数变化/路由正确，清除后旧ID消失，无报错；记录物理点击证据。该项补强交互证据，不把21号未明定的物理输入单列新增硬门禁。

若工具要求真实账号登录/授权，由用户自己完成；本Issue没有必要创建新AppID或登录业务账号。真实账号与原图验收仍留在对应Issue，不为工程壳扩业务。
