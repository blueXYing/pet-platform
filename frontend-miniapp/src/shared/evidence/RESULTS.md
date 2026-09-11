# C-001 首次交付验证记录 · 2026-09-11

后续平台连接已恢复，单窗口实测通过；当前结论以 [PLATFORM-FOLLOWUP.md](PLATFORM-FOLLOWUP.md) 为准。下表保留9535d024首次交付的历史结果，不能作为最新平台失败判断。

结论：候选公共壳可供代码审阅；C-001 未达到 DoD，M-001 不解锁。

环境：Windows，Node v22.23.1，npm 10.9.8。依赖通过 npm install 安装及 npm ls --depth=0 核验，准确版本见 versions.txt 和 package-lock.json。未额外执行干净 npm ci 复装；CI 应以 npm ci 验证可复现安装。安装提示若干上游弃用依赖；本 Issue 没有执行供应链安全审计，不把安装通过当成无漏洞证明。

| 验证 | 结果 | 证据与边界 |
|---|---|---|
| typecheck | PASS | typecheck.txt；最终退出0。首轮 scanCode 空参数不符合 Taro 类型，改成 `{}` 后通过 |
| MINI-001 微信目标构建 | PASS（构建子项） | build-weapp.txt，退出0，Compiled successfully in 41.92s。首次缺 @babel/preset-react 已补固定7.26.3；失败原始日志单独保留 |
| MINI-001 开发者工具运行 | NOT PASSED | 已定位 D:/soft/微信web开发者工具/cli.bat；auto 首次检测旧端口36376连接超时；观察9420监听后重试得到 listen EACCES 127.0.0.1:3799。两次CLI退出0但日志为失败，绝不判通过。见 wechat-auto*.txt |
| MINI-003 基础隔离 | PASS（内部） | mini-unit.txt；用户、工作区、商家、门店切换、登出、撤权、同坐标重入、延迟成功与失败、登出后同用户登录均拒绝旧数据写入 |
| MINI-004 请求/平台端口注入 | PASS（内部） | 共14项测试全部通过；真实API分区只在截获测试使用，无网络业务请求；ID和金额保留String、写requestId复用、服务端展示态/actions不重算、网络失败与错误code传播、跨区/路径穿越拒绝、real模式被CCR阻断 |
| MINI-004 真实会话 | BLOCKED | CCR-ACR-001 未批准，不预造会话DTO/API |
| MINI-005 本地包体盘点 | PASS（内部预算） | package-inventory.txt；主包/总计311582字节，21文件，0实际分包；2MiB是内部保守预算，非已核验平台配额 |
| MINI-005 平台分包/多窗口/安全区/图片清晰度 | NOT PASSED | 无可用自动化平台连接、无实际merchant分包、无真机截图。当前官方分包规则页面访问失败，现行限额未核验 |
| VIS-001～004 | NOT PASSED / 后续产品页阶段 | 无产品页/原始切图/截图，visual-manifest.json提供证据字段。示例未引用Figma视觉，不宣称原型还原 |
| 本Issue范围检查 | PASS | 变更仅frontend-miniapp Allowed。单一入口、无merchant业务/运营/后端修改、无公共Contract/Schema/Event变化；git diff --check通过 |
| 全仓架构门禁 | NOT CLAIMED | GOV-001/GOV-002独立负责，EX-W1-001允许本Issue工程基础推进，不将其已有失败改为通过 |

Merchant 岗位在本 Issue 内子代理只读复审结论：未发现阻断当前fixture工程范围的隔离或目录问题；缓存与 UI 修订号防护有效；普通分包所有权和单入口符合基线。仍须沿用消费方 revision 校验，不能只依赖 run 后自行无条件写局部状态。当前没有真实分包加载与平台验收证据，不解锁M-001。

源资料：SSOT、C端最终PRD（原Word只读提取）、HTTP Contract §2/§9、20号技术基线、21号测试补充、C-001和CCR-ACR-001。未改根Work台账，未扩业务范围。
