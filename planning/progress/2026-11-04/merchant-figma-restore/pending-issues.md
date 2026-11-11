# 商家端待修复问题清单（2026-11-11 建立）

> 统一登记商家端 CCR 轮验收/诊断中发现的问题；修复时按本清单逐项闭环，每项单独提交并回填状态。
> 来源轮次：PR #211（五份 CCR）/ PR #212（STORE_PHOTO 上传修复）/ 种子重建补验轮 / 真机验收环境恢复轮。

| 编号 | 优先级 | 问题 | 来源/证据 | 修复草案 | 状态 |
|---|---|---|---|---|---|
| P-01 | 高 | **今日营收票券不可点击**：工作台顶部营收票券只做了视觉还原，未绑点击跳数据统计页；用户拍板要补 | Figma 5:3759 交互暗示；`workspace/index.tsx` mwb-hero-zone 无 onClick（数据统计入口仅在 8 宫格 `#workbench-stats`） | 票券区包可点容器，onClick 走既有 `openStats()`（handoff 到 `/merchant/pages/stats/index`）；不动契约/接口；补一条"票券可点"的前端测试 | OPEN（用户 2026-11-11 拍板） |
| P-02 | 高 | **完成单挂活动售后时退款申请返回 500**（COMMON_INTERNAL_ERROR，应为业务 4xx） | 11-09 种子重建轮实测：COMPLETED+PENDING 售生单上 POST refund-applications → 500 无业务码 | 排查 refund create 链对"活动售后存在"分支的异常包装，映射为合适的业务错误码（如 409 + 已登记码，或按 49 号语义拒绝）；补后端测试 | OPEN |
| P-03 | 中 | **navigateTo 进入工作台白屏**：从首页 navigateTo 工作台触发 Taro 树 dispose 白屏；直接 reLaunch 正常 | 11-11 环境恢复轮实测（白屏截图+路由栈证据）；与已登记"子页返回工作台必须 reLaunch"同族 | 按既有 reLaunch 家族模式收口所有"进入工作台"的导航；回归底部 tab/宫格/我的 hub 三类入口 | OPEN |
| P-04 | 低 | **promoLevel 口径文档/实现不一致**：27 号文档说"promoLevel 以 0 表示未设置"，实现只认 null（传 0 报 400） | 11-09 头像链路实测（PUT 带 promoLevel:0 → INVALID_ARGUMENT） | 二选一：改 27 号文档为"传 null 表示未设置"（推荐，零代码风险），或实现加 0→null 转换；与契约 Owner 确认 | OPEN |
| P-05 | 环境项 | **头像签名 URL 正路径需 OSS 环境验收**：本地无 OSS 签名器，avatar-url 按设计返回三键 null（失败关闭）；"上传→绑定→签名→展示"的正路径仅测试层覆盖（6/6+3/3） | 11-09 实测；StorePhotoSigningApiImpl 注释明确"无 OSS 连接时 bean 可装配、签发失败关闭" | 非代码问题：生产部署（或接真 OSS 的预发）后人工验收正路径；本地 E2E 如需可加 mock 签名器 bean（测试基建，可选） | OPEN（等生产环境） |
| P-06 | 低 | **退款 timeline 的 REFUND_ORDER_CREATED 档未真机验证**：本地验收申请均为 PENDING_MERCHANT（无已建退款单的可回看样本） | 11-09 补验轮；CI 的 MerchantRefundViewHttpAcceptanceTest 已覆盖该档（createApproved 用例，CI 绿） | 已有 CI 覆盖；真机侧如需复验，用商家同意全额后回看详情即可 | COVERED-BY-CI |

## 已裁决不做/保持现状（勿重复登记）

- 已注销用户昵称：商家端保持原样显示（2026-11-09 用户裁决）。
- 评分大卡统计口径：保持全量，页面标注"共 N 条计分评价"（2026-11-09 用户裁决）。
- 退款 3.2 申请说明解密：不批（维持 49 号密文口径）。
