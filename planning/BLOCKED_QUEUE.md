# Blocked Queue

## 当前ACR-001同步状态（2026-09-11）

R1～R6已获批准并同步，但Wave 1仍整体暂停待人工启动批准。M-001因统一小程序壳C-001未完成而BLOCKED，保持原Issue不重拆。CCR-ACR-001的具体会话API/DTO为OPEN_SPEC_REQUIRED：AUTH-001负责先建立规范；C/M通用壳可用内部fixture，真实会话接入/公共SDK在规范批准前不得推进。此处覆盖下方初始“Wave1全部Ready”的历史描述。

原型/素材门禁：商家Figma及对应V1节点/切图待提供；用户设计逐页范围和删减布局差异待对应页面确认。它们不阻断通用壳，但阻断相关页面宣称视觉验收完成。原产品/技术未决项如下，未因ACR批准而关闭。

初始情况下，除 Wave 1 Ready Issue 外，大部分业务 Issue 因依赖未完成而 BLOCKED。

Work 每次 Merge 后重新读取 `ISSUE_CATALOG.csv`：
- 依赖全部 DONE → 检查 Contract/Test → 可进入 READY；
- 存在未决 CCR / 产品裁决 → 保持 BLOCKED。

不要为了“让AI有活干”而忽略依赖。

## W0 核验（2026-09-11）

Wave 1 工程基础范围无已知产品级 BLOCKER。人工启动批准仍未获得；EXECUTION_READY 不等于已派发任务或已完成依赖。

| 范围 | 阻断项 | 解锁证据 |
|---|---|---|
| PLAT-003 的 Outbox 持久化/恢复实现 | CCR-W0-001：租约与事件字段映射不齐 | Architect/Contract Owner 批准并完成契约同步 |
| REF-003/PAY-004/CPN-002 的迟到支付来源处理 | CCR-W0-002：source_type 与 refundSource 值域/映射不齐 | 来源契约统一；已封板迟到支付规则不变 |
| 订单关闭/改期确认轮次相关实现 | CCR-W0-003：cancelReason/confirmRound 事实映射未明确 | 权威存储或推导契约明确，旧任务失效与支付超时识别可测试 |
| 资金冻结/分账/提现/保证金实现 | OD-W0-001：引用的资金基线缺失，V1有效范围待裁决 | 产品提供权威基线与范围裁决；不得自行新增 Epic |
| 入驻签约真实集成及工作台准入 | OD-W0-002：签约接口/状态映射未冻结 | 签约技术契约与产品准入规则明确 |
| 生产 RBAC 技术映射 | CCR-PERM-001：产品规则已决，具体权限码/DTO尚需规范 | AUTH-001建立并由Contract Owner审批技术映射；OD-W0-003已RESOLVED，不阻断A-001通用壳 |

执行前置另行记录：当前未初始化 Git，上游/Owner待提供；当前 Maven 使用 Java 17，PLAT-001 执行环境必须切至 Java 21。它们属于批准后 GOV-001/PLAT-001 的工程准备，不是重新裁决产品的理由。

2026-09-11权限裁决更新：OD-W0-003已关闭，不再等待双运营/审批规则的产品确认；资金与签约未决项未改变，Wave整体仍暂停。
