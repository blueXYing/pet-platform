# 三端售后非出款页面切片

日期：2026-10-01。用户明确“那么开始实现吧”，并要求子代理使用 Figma 插件。基线 PR98 已合入 develop `e3b846e5038aa617e42a919862f20a927057f53e`；合并后 CI36736110015 六项成功。该 CI 只证明旧基线，本批以独立测试记录为准。

## 范围与唯一写入者

| 角色 | 原 Issue | 唯一写入范围 | 分支 |
|---|---|---|---|
| 总协调/C公共实现 | C-005/M-004 集成 | miniapp shared、app路由、原入口接线、WORK_STATE/台账 | codex/aftersale-pages-20261001 |
| C子代理 | C-005售后子切片 | consumer/aftersale、pages/aftersale、对应测试及C-HANDOFF | codex/aftersale-c-ui-20261001 |
| M子代理 | M-004 OWNER售后子切片 | merchant/aftersale、pages/aftersale、对应测试及M-HANDOFF | codex/aftersale-m-ui-20261001 |
| A子代理 | A-004非退款终裁子切片 | frontend-admin及A-HANDOFF | codex/aftersale-a-ui-20261001 |
| 独立QA | QA-005相关切片 | 测试与审查报告，问题交唯一实现Owner修复 | 实现角色释放并发位后启动 |

各工作树来自同一基线；根协调维护公共客户端并以提交交接，业务子代理只提交自己的实现。构建与集成由根协调串行执行。不修改 SSOT、生产配置或数据库，不自动合入 develop/main。

## 权威规则与交付

SSOT §40、PRD31、Contract51/OpenAPI11、前端技术基线20和验收21。仅 USER本人、真实 OWNER、获权 ADMIN；C/M私有图片、持久原请求恢复、明确版本冲突后重读、当前会话/工作区/门店隔离、冻结只读、补证及三种非退款终裁。全部写入仍由后端鉴权；前端 eligible/准入提示不产生授权。公开FULL_REFUND/PARTIAL_REFUND关闭；不新增 STAFF、跨店运营聚合、复审、通知送达、积分券评价消费者或资金产品。

C Figma `bp2vpcjjA5vZbHvtKkA8wl`、售后申请 `129:10572`；M Figma `Usvn3d6UCVCAlDxou5KAK8`、节点 `40:1061/40:1345/40:1500/40:1676`。子代理均使用插件 get_design_context 及截图；首次认证失败后，用户明确回复“已连接”，重试成功。运营无桌面原稿，按既有风格和PRD实施，移动参考不冒充运营设计。原图与现合同差异记录于各交接及VIS差异，不复制旧退款/退货产品规则、不用虚构服务/客户资料补齐缺失字段。素材使用真实导出节点与本地文件，不以整页截图作UI。

## 必要依赖缺口

Contract51只给 typeCode/demandCode 服务端配置校验，没有供页面展示的权威代码+名称目录。按[CCR](../../../ccr/CCR-AFS-PAGE-OPTIONS-001.md)登记最小增量，未批准前不新增公共接口、不用硬编码退款选项或原始代码输入冒充产品流程。创建表单支持目录注入，未接通时禁提交；其余本人列表、详情、补证、撤回不因这个缺口阻塞。订单列表/详情尚无完整前端交付，直接订单深链按真实本人资格查询，不伪造订单摘要。

## 验收边界

自动化、浏览器、真实HTTP/数据库、小程序模拟器、物理真机与VIS分别记录。页面测试不能替代原有交易/并发测试。未执行的场景标未验收，完整 C-005/M-004/A-004/QA-005 不提前标DONE。
