# C/M/A 同单真实页面联调

2026-10-01；根执行微信 C/M，独立 QA 执行运营 A。状态：本机同单页面闭环 PASS；物理真机、VIS 未通过。源码基线为 e2a6ce6 加本批正式目录/测试增量，不把上一提交 CI 当作增量 CI。

## 真实路径与边界

使用真实微信开发者工具、Taro 编译页面、Taro request/upload、Spring Boot Controller、会话、OWNER 准入、RBAC、MySQL 事务、Redis、首回执与 Outbox。无 page data 替换，无业务 HTTP mock，无伪造 UI 角色。仅 wx.login 上游返回隔离身份兑换码、相册选择返回本机 QA PNG；服务端微信 exchange、离线签名付款通知、审核/扫描和内存私有 OSS 是明确测试依赖。订单先经业务创建/确认/核销，页面没有插入售后业务结果。

小程序本机开发构建临时使用受控回环 origin、ALLOW_LOCAL_HTTP 与商家准入开关。实际 AppID 只复制到 ignored 隔离项目配置，未写入 Git/报告；用户桌面原配置 SHA256 前后均为 `6a1814d6b2c0f733193f57354761268a398ec128588e955f08983dfbf7c95561`。开发工具未读取目录链接中的 app.json，已改为独立真实构建副本；启动期间 automation 超时与一次窗口丢失均记录，实际截图及重新开窗后再读取验证，未用触发成功冒充验收通过。

实际平台信息：devtools，model iPhone 12/13 (Pro)，window 390×753、screen 390×844、pixelRatio 3，基础库 3.17.2，模拟微信版本8.0.5。这里只是一个模拟窗口，不是物理 iPhone。截图输出363×785是开发工具显示结果，不能将其当作第二窗口或直接用363反推设计比例。固定业务时钟2030-01-01T10:30:01Z，页面显示北京时间；该日期属于受控测试资料。

## 同单链路与交叉核对

| 步骤 | 实际页面操作及真实服务结果 | case version |
|---|---|---|
| C 登录/申请 | shell 微信登录得到真实本人会话；apply 实际读正式完整5+5目录与本人资格；原生 Picker change 选 SERVICE_QUALITY/OTHER、textarea 输入30字、点击提交、自动进入工单详情。真实本人 GET 独立核订单/类型/诉求与回执一致 | 0/PENDING |
| A 受理/指定补证 | QA 实际网页登录、指定店列表、详情受理；实际要求 USER 补证后通过仅含业务坐标的本机握手通知根 | 1/PROCESSING → 2/WAITING_SUPPLEMENT |
| C 补证/图片 | C 详情实际读当前补证轮次，输入26字；仅相册选择为fixture。平台FS保存/检查779字节PNG，Taro multipart upload真实HTTP成功，再从页面提交补证；真实详情含第二USER批与图片，当前USER轮次清除；实际私图grant/consume显示水印后关闭 | 3/PROCESSING |
| M 准入/意见 | C shell真实退出、真实登录OWNER；memberships/admission经过后端校验进入当前店，工作台点击售后管理、列表打开同工单、实际读取用户私图；意见抽屉选择AGREE并输入34字、点击提交；真实详情新增唯一MERCHANT批 | 4/PROCESSING |
| A 正式终局 | QA实际运营页面刷新，核对两USER批与一MERCHANT批、当前version4，选择OTHER、填写理由并确认，真实提交终局 | 5/RESOLVED |
| C/M 结果读取 | M刷新展示平台结论及只读提示；切回真实BUYER，C列表及详情展示同单已裁决、OTHER理由、双方3批证据。C补证/撤回控件已从原生渲染树消失 | 5/RESOLVED |

所有可操作交互均经已注册 `wechatide automation_element_action` 的 tap/input/trigger；目录的内层View不是原生Picker，初次trigger没有选择成功，随后只读量测父Picker的真实generated sid后对该原生Picker触发change。没有直接调用 React handler 或 setData。测试不宣称手指点击或相册权限/取消已验证。

同工单全部持久事实独立核验：6 commands、3 evidence batches、1 decision、6 transitions、6 status logs、6 AFTERSALE outbox；orderVersion9、caseVersion5。退款单/执行/资金证明/售后资金证明/订单退款commit/渠道dispatch各0、channelCalls0。精确业务坐标与计数见[脱敏摘要](mini-summary.json)；临时会话、authorization、控制secret、原始网络和私有图片不提交。

QA另实际完成P4全部三笔历史逐笔核对，以及一次真实后端提交成功后丢ACK、撤权403、清图登出、重登后原UUID/body重放且完整SQL副作用不增。[独立运营交接](ADMIN-QA-HANDOFF.md)记录三个Playwright实际用例3 PASS/0 skip/0 failure/flaky；前两个用例中的buyer HTTP参与动作不冒充C页面，第三用例才为上述三端同单闭环。

## 本轮有限视觉实证

子代理实际读取Figma插件四节点/截图与原始素材，见[原稿审查](CATALOG-VIS-HANDOFF.md)。本机已查看C apply、C/M水印私图及M意见抽屉的实际平台截图；只是对应运行状态的检查，不是完整原稿叠图或VIS通过。

native boundingClientRect 实际测得C五卡片left12.125/width365.75，M五卡片left28.125/width333.75；按实际390/402归一，C为12.498/377.0046，M为28.990/344.0192。与已读参考C x12.51/width375.988、M x29/width343仍有约1设计像素宽度差，须按原稿继续校正；没有自行新增容差。五卡片横向均在当前390窗口内，但selectAll('button')返回空数组，不能称所有控件水平边界已测。已登记字体子集缺字/回退、M精确字体缺素材、C index/detail及新状态缺稿、M/A仍显示机器code等差异，未以本轮功能联调关闭VIS。

## 收尾与未完成项

wx.login / chooseMedia mocks已恢复，实际logout完成，只关闭本批隔离项目窗口。默认无origin的生产构建、typecheck与分包静态门禁恢复通过；未上传预览或发布。临时后端生命周期/精确数据库与Redis资源清理见[后端交接](BACKEND-HANDOFF.md)。

物理手机是否可配合及同网条件已向用户询问，尚未取得实际手机操作证据。本机回环地址不能作为手机可达后端；真实微信上游、对象扫描/审核/存储与域名仍需对应环境。真机多窗口/字体/键盘/安全区/选图权限/生命周期、VIS、新状态稿、STAFF、全局运营聚合与资金能力未宣称完成。C-005/M-004/A-004/QA-005整体不置DONE；PR仍草稿，不因本验收启用生产或合并。
