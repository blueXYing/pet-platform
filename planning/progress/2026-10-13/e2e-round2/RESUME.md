# E2E 二轮断点记录（RESUME）

更新：2026-10-13 22:35（第三棒收尾完成，本文件转为终态存档）。供后续复盘/补充验收零成本接力。

## 终态：任务已交付
- 矩阵 6 场景全部通过（截图 01-53），ACCEPTANCE-REPORT.md 已定稿（{{AUTO_CONFIRM_RESULT}} 已落定）。
- 截图 53（第三棒 22:28）：C 详情终态——改期单 103335140960333824 经 30 分钟自动确认回到**待服务**、新时段 2026-10-14 17:00~18:00、未核销。取图路径：home-orders 入口→列表卡 ord-card-<orderId>→详情；会话过期静默重登自愈实测有效。
- 自动确认 DB 证据（第三棒 22:2x 查证）：`async_task` ORDER_AUTO_CONFIRM:103335140960333824:**0=CANCELED / 1=SUCCEEDED(retry 0)**（30 分钟钟随改期重启+到期触发的实证）；`order_status_log` 09:02:15 ORDER_RESCHEDULED（USER）→ 09:32:16 ORDER_AUTO_CONFIRMED（SYSTEM）PENDING_CONFIRM→PENDING_SERVICE；日志 server-console6.log 17:32:32 `E2E_EVENT_SINK OrderConfirmedEvent.v1 aggregate=103335140960333824`；order_stage=PENDING_SERVICE、version=4。
- 日期雷复查（第三棒 grep 复核）：字面日期仅 1090 行 '2020-01-01'/'2035-01-01'（凭据 DATED 远期区间）；无 LocalDate.of/Instant.parse；年份字面仅 1024 行 SQ20261007E2ELOOP1 展示编号（无比较逻辑）。**无写死当日雷，结论与报告 §5 一致。**
- 提交/PR：staged=E2eCoreLoopServer.java+报告+RESUME+截图 01-53；**project.config.json 保持未 staged（本地 appid 红线）**；PR 已建（见 git/gh，标题 test(e2e): 二轮模拟器 E2E 验收（商家退款/评价申诉/改期/售后/商家列表/核心回归）），未合并不 push develop/main。

## 环境现状（收尾时点，均未清理——见报告 §6 复现）
- MySQL 33442（pid 10744，环境又重启过一次、数据无损）；服务器 18082（pid 25072，health UP，复用模式，DROP_ON_PROCESS_SHUTDOWN 进程退出即清）；Redis 16379 共享。
- DevTools 窗口 s0 重开过一次（环境关闭后 open_project_window reuse）；ui.sh 助手 /d/Temp/e2e-r2/ui.sh（tap/wxml/page/shot）仍可用。
- 服务器日志 /d/Temp/e2e-r2/server-console6.log（40KB，含确认事件与 snowflake 自愈尾巴）。

## 遗留（已在报告 §3/§4 登记，不在本 PR 修）
- F2R-1（中）：改期后 M 端手动接单 409（前端 expectedConfirmRound 硬编码 0 + M 读侧无 confirmRound）——需产品/架构裁决后另开 Issue。
- F2R-2（低）：改期重试在 401 静默重登后控制器停 error 态（幂等正确）。
- 未覆盖组合分支（如实披露）：refund_order 创建后禁止核销的反向触发（本轮 0 张 refund_order）；售后平台裁决（V1 单运营，admin 裁决 UI 非本轮矩阵）。
- QA 噪声：snowflake 单飞行偶发失闭自愈（E-2）、503 同 X-Request-Id 重放。
