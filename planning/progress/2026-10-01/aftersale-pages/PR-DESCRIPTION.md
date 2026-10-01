PR98已有三端非出款售后HTTP，本PR将它接入本人小程序、真实OWNER工作区和获权运营页面。每次进入/写入仍由当前后端权限与资源范围校验；未知写结果恢复原UUID与内容，私有证据通过当前会话单次grant展示。保持草稿：两项CCR与真机/VIS仍未验收。

## Issue / Scope

C-005、M-004、A-004、QA-005售后子切片；不关闭完整Issue。C本人列表/详情/资格/申请视图/补证/撤回，M当前店OWNER列表/详情/四类意见/补证，A指定店列表、完整P4历史人工核对、受理/补证要求/重复关闭及REJECT、RESERVICE、OTHER。接通5个小程序路由与既有入口。

实现角色实际使用Figma插件读取C129:10572、M40:1061/1345/1500/1676，素材使用原节点本地导出；运营无桌面原稿，遵循既有工作台风格。原稿旧退款/退货文案和图片规则不覆盖现Contract51；缺服务/客户字段不虚构。不声称一比一或VIS通过。

## Changed Modules / SSOT Impact

frontend-miniapp shared/consumer/merchant、frontend-admin及规划/交接/验证记录。
- [x] No SSOT/product rule change
- [x] No backend or production configuration change

## Contract Impact

消费既有Contract51；无正式API、DB、Event、Scheduler变化。两个具体CCR仅提案待人工审核：

- [申请目录](../../../ccr/CCR-AFS-PAGE-OPTIONS-001.md)：缺权威code+label读取，生产新建申请禁用，不硬编码目录；列表/补证/撤回及M/A已有处理可用。
- [确定冲突](../../../ccr/CCR-AFS-CONFLICT-001.md)：COMMON_CONFLICT混用CAS/finalSet与幂等争锁忙，原请求保留以避免未知重复，真实CAS可能长期锁定旧版本。须明确码后补安全解锁，未宣称该分支验收。

## Tests

| 实际检查 | 结果 |
|---|---|
| 小程序Node全部 / typecheck / weapp构建 / 包体 | 235 passed，0failed/skipped；其余PASS，主包约0.77MB、新AFS子包约0.27MB、总约4.37MB |
| 运营生产build / 边界 / Playwright | PASS；63passed / 2既有live opt-in skipped，包含36项售后 |
| 真实MySQL/Redis两个既有HTTP套件 | 7tests，0失败/错误/跳过，实际XML摘要[local-http-summary](local-http-summary.json) |
| 契约文档 / 架构脚本 / 源门禁 | 127文档、18脚本及模块/持久层/DisplayStatus门禁PASS |
| 微信模拟器 | 5个入口实际打开，未登录/OWNER阻断截图人工检查；未走新页面真实后台联调 |

页面传输测试使用明确fixture；既有真实HTTP回归未调用新页面。物理真机、三档视窗、页面联合闭环及叠图/字体VIS仍未验收。失败修复与证据边界见[IMPLEMENTATION](IMPLEMENTATION.md) / [QA-HANDOFF](QA-HANDOFF.md)，CI状态按当前PR head另核。

## Concurrency / Money Risk / Rollback

业务写先持久记录后发送；401/403/404及429/歧义幂等409不能擦除旧未知结果，重新鉴权/门店证明后显式原键回放。身份变化拒绝迟到结果；私有预览隐藏/刷新/撤权清除，进程重启只清自身遗留预览。公开资金决定无按钮/路径，生产开关保持原值。未部署，可回退本PR前端提交，无数据库回滚；已保存未知意图不自动发送。

## Checklist

- [x] 仅已授权售后子切片，完整Issue不提前DONE
- [x] 没有未经批准的产品规则或正式契约变化
- [x] 相关实际测试通过，未验场景已披露
- [x] 无biz到biz或跨模块持久层访问
- [ ] 当前PR最终head CI待核
- [ ] 两项CCR、页面真实联合闭环、真机/VIS待完成
