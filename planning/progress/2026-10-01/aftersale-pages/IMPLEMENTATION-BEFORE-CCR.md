# 三端售后非出款页面实施与验收

2026-10-01。用户授权多角色实施并要求子代理使用Figma。基线PR98合并 `e3b846e` / CI36736110015六项成功；事实JSON位于本目录。三角色先独立工作树实现，根串行集成，独立QA反馈由唯一Owner修复。新PR仅草稿；完整C-005/M-004/A-004/QA-005不标DONE。

## 实施

C：本人列表、详情、真实资格和订单深链、申请视图、补证/撤回、6张JPEG/PNG私有图片、持久文件/原UUID/摘要/receipt恢复。M：每次进入与写动作重新验证当前店OWNER，冻结只读、四类意见、补证及同店持久恢复。A：指定店服务端分页及独立权限、完整P4历史人工核对、受理/补证要求/重复关闭、REJECT/RESERVICE/OTHER。五条小程序路由和当前壳/OWNER入口已接线，包检查覆盖5页四类产物。

根shared仅开放Contract51精确路由、四字段成功/失败envelope、String ID/version/money和UTC毫秒，校验列表坐标/过滤/分页与回执目标；后台主体不进命令DTO。写入持久journal必须先成功再发送，未知结果不能换键。当前端相对grant用Bearer单次二进制读取；只显示页面内私有预览，hide/scope/unmount/刷新清UI与自有文件，启动只清严格本批preview名字的遗留文件，失败删除保留拥有记录。

原设计节点/素材hash/取图与裁剪差异见三份HANDOFF和资产manifest。C/M实际读取Figma成功；运营无原稿。旧退款/退货规则、描述选填和3张5MB WEBP/GIF不覆盖Contract51。缺服务/客户资料不虚构，不以整页截图替代UI，不声称VIS完成。

## 实际验证

| 检查 | 实际结果 |
|---|---|
| 小程序全部Node测试 | 235 passed，0 failed/skipped |
| 小程序TypeScript / weapp构建 | PASS，最新shared修复后typecheck与weapp构建复测通过 |
| 包体检查 | PASS，主包约0.77MB；新售后子包约0.27MB；全部4,371,407 bytes（约4.37MB），均低于既定预算 |
| 运营生产构建/边界/浏览器回归 | 根集成生产build与边界PASS，Playwright 63 passed / 2既有live opt-in skipped（含36项售后） |
| 独立真实后端HTTP | AfterSaleHttpAcceptanceTest 4 + AfterSaleEvidenceHttpAcceptanceTest 3，全部0失败/错误/跳过，JDK21+隔离MySQL/Redis，详见QA-HANDOFF |
| 契约/架构 | 文档测试127、脚本测试18、模块/持久层/DisplayStatus源门禁均通过；最终台账后复核文档 |
| 微信模拟器 | 成功打开C index/detail/apply和M index/detail，人工看未登录/OWNER阻断与原返回图标；未连接真实后端 |

已有webpack单个大素材/字体warning仍存在；已跑总包/子包预算检查，不当成新代码构建失败。最初预览缓存工具的USER_DATA_PATH类型可选错误已加明确运行环境校验并复测TypeScript通过。模拟器游客AppID报APPID_ERROR，临时复用用户已有本机AppID后打开成功，收尾恢复配置；automation_runtime_info超时，不隐瞒为交互通过。截图149×321不作VIS或三档视窗验收。所有实际图和原始运行日志仅本地产物，未提交敏感内容。

根首次调用运营边界检查时尚未生成dist，报ENOENT；按顺序执行生产build后边界复测PASS，未通过修改业务代码掩盖顺序错误。运营持久命令使用localStorage，仅存当前operator/店/case绑定的原内容与UUID；后端真实会话+列表资源证明+卷宗重读后才可恢复，令牌/图片/grant不持久化。覆盖关闭浏览器context后带同源持久状态重新鉴权、存储故障、同case迟到旧ACK不删新未知命令。

## QA反馈与修复

修复未知命令被401/登录清除、pending引用可修改、错误400envelope误退休、返回错误目标回执、JS正则末尾换行、COMMON_CONFLICT/429误当确定CAS、上传临时文件丢失及receipt到草稿中间故障、原生图库缓存、隐藏/刷新/撤权迟到图片、进程中断后旧预览残留。对应测试覆盖真实typed client/生产浏览器传输或持久文件故障，不用实现镜像测试冒充后端验收。

## 遗留依赖与范围

1. `CCR-AFS-PAGE-OPTIONS-001`尚未获批：生产目录port返回null，真实创建禁用；不硬编码测试字典。本人列表/补证/撤回及M/A既有处理可用。
2. `CCR-AFS-CONFLICT-001`尚未冻结：COMMON_CONFLICT兼用真实CAS/finalSet变化与幂等争锁忙，保留原UUID避免未知重复；真实CAS可能长期锁旧版本，不能宣称已完成安全编辑恢复。
3. 两套真实HTTP回归是既有后端验收，未调用本批页面；浏览器使用明确合同fixture。真正三端页面联合闭环、真机、三档窗口、叠图/字体VIS与相关稿件裁剪评审仍未完成。
4. 无后端/SSOT/正式Contract/Schema/Event/Scheduler变化，不包含STAFF、跨店全局运营、资金决定、REF-001、通知送达或积分券评价。所有生产开关保持原值。原桌面用户文件保持原样。

回滚：未部署，可回退本PR前端提交；无需数据库回滚。未知命令journal保留原JSON/UUID，不自动重放；目录和新错误码未实现，不存在依赖迁移。
