PR98提供三端非出款售后HTTP，本PR将它接入本人小程序、当前店OWNER工作区和获权运营页面，并补齐用户已批准的两项契约：申请表单从后端权威目录读取代码和名称；确定版本冲突后刷新、重新核对并手动提交，未知结果仍保留原UUID与内容。

## Issue / Scope

C-005、M-004、A-004、QA-005售后子切片，不关闭完整Issue。C本人列表/详情/资格/真实申请接线/补证/撤回；M当前店OWNER列表/详情/四类意见/补证；A指定店列表、完整P4历史人工核对、受理/补证要求/重复关闭及REJECT、RESERVICE、OTHER。五个小程序路由与既有入口接通。

子代理实际使用Figma插件读取C129:10572和M40:1061/1345/1500/1676，使用原节点本地素材；运营无桌面原稿，遵循既有工作台规范。原稿旧退款/退货规则不覆盖现Contract51；缺服务/客户字段不虚构，不声称VIS通过。

## Changed Modules / Contract Impact

frontend-miniapp shared/consumer/merchant、frontend-admin、backend pet-aftersale-api/biz及pet-boot、契约/e2e门禁与交付记录。

- 2026-10-01用户明确“批准” [目录CCR](../../../ccr/CCR-AFS-PAGE-OPTIONS-001.md) 与 [确定冲突CCR](../../../ccr/CCR-AFS-CONFLICT-001.md)，据此新增认证只读 `GET /api/v1/c/aftersale-options`，冻结完整code+label目录并与新创建共用同一配置。无query/body，缺名称或配置不一致读取和新建均失败关闭；不提供测试目录兜底。
- `AFTERSALE_VERSION_CONFLICT` / `AFTERSALE_FINAL_SET_CONFLICT` 保持409，只用于当前动作权限已复验、命令确定未提交业务作用的比较失败。成功原UUID先重放；幂等忙与授权版本变化仍 `COMMON_CONFLICT`。明确拒绝只退休匹配原请求；C/M详情重新验证期间禁写、清图片，A旧确认和全量历史人工核对失效。
- Contract50/51、Internal07、HTTP10、OpenAPI11和Error12已同步；无SSOT、Schema、Event、Scheduler或生产开关变化。目录生产内容、资金能力及PR合并不在本次批准范围。

## Tests

| 实际检查 | 结果 |
|---|---|
| 小程序Node / typecheck / weapp / package | 252passed，0failed/skipped；其余PASS，总4376328B，包预算通过 |
| 运营生产build / 边界 / Playwright | PASS；66passed，2既有live opt-in skipped，覆盖迟到旧409不退休新请求 |
| Java21 offline单元/配置/边界 | 41passed、0failure/error/skip，根复核[XML摘要](ccr-unit-summary.json) |
| 隔离真实MySQL/Redis | 独立QA累计37个唯一用例通过：14真实HTTP+23真实域数据库，无最终失败/跳过；分轮30+6+1，首轮失败与复测源边界见[CCR-QA-HANDOFF](CCR-QA-HANDOFF.md)与[摘要](ccr-http-summary.json) |
| 契约/架构 | 文档129passed、offline smoke114ops/24AFS/69writes；架构脚本18与模块/持久层/DisplayStatus门禁PASS |

既有旧页面模拟器仅覆盖5个入口未登录/OWNER门禁；本批页面传输测试使用明确fixture，真实HTTP验收独立于页面。页面真正三端联合闭环、物理真机、三档窗口及叠图/字体VIS尚未验收。完整证据与首次失败修正见[CCR-IMPLEMENTATION](CCR-IMPLEMENTATION.md)及[IMPLEMENTATION](IMPLEMENTATION.md)。当前head CI另核，不使用前一版六项成功替代本增量。

## Concurrency / Money / Rollback

业务写先持久保存后发送；401/403/404、429和歧义幂等409保留原命令，重新鉴权及资源证明后仅显式原键回放。迟到旧ACK/明确冲突不能删除较新的未知命令；存储退休失败仍保留原UUID。私有图片离开前台/刷新/撤权清理，启动仅清自有遗留预览。公开资金决定保持关闭。

无需数据库迁移，可回退本批后端、契约和客户端提交；连接旧后端缺目录时禁新申请，通用冲突保持原键，不擦除或自动重放未知意图。PR保持草稿，不自动合入develop/main或启用生产。

## Checklist

- [x] 用户批准的两项CCR范围已披露，完整Issue不提前DONE
- [x] 无未经批准的产品规则/SSOT/Schema/Event变化
- [x] 无biz到biz或跨模块持久层访问
- [x] 相关本地测试、最终浏览器与独立真实HTTP/DB回归通过，已披露分轮复测及初次失败
- [ ] 当前PR head CI另核；前一head六项成功不替代本次增量
- [ ] 页面真实联合闭环、真机/VIS仍未验收
