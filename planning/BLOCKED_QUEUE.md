# Blocked Queue

## Wave 1收尾（2026-09-14）

七个工程壳Issue均已合入develop；M-001不再BLOCKED。W3未发现工程壳封板代码阻断。收尾文档同步PR待人工合并，下一波尚未启动。以下均为对应后续范围的有效门禁，不将其变成已批准公共契约或业务需求。

| 范围 | 未解决项 | Owner与解除条件 |
|---|---|---|
| 会话/工作台真实准入、C/M真实接入 | CCR-ACR-001 | AUTH-001建立规范，Contract Owner审批；内部fixture不得冒充后端授权 |
| 真实RBAC权限码/DTO/数据范围/撤权 | CCR-PERM-001 | AUTH-001建立技术映射；单运营产品决策已定，不重复审批产品 |
| Outbox持久化/租约恢复 | CCR-W0-001 | PLAT-003前由Architect/Contract Owner明确权威字段映射 |
| 迟到支付退款来源 | CCR-W0-002 | REF-003/PAY-004/CPN-002前统一存储/API/Event来源映射 |
| 关闭原因/确认轮次事实 | CCR-W0-003 | TX-001/PAY-003/PAY-004/ORD-002/003前明确权威事实 |
| 资金冻结/分账/提现/保证金 | OD-W0-001 | 产品提供资金基线及V1有效范围，相关业务实现前裁决 |
| 入驻签约/工作台准入 | OD-W0-002 | 产品/Contract Owner明确Provider接口和状态映射 |
| 商家产品页/用户设计范围与素材 | 原型及切图门禁 | 对应页面取得原稿、原始资产和V1映射；删减布局差异须确认 |

## 真实未验收能力

MINI-006真实微信授权/支付/扫码/上传、真机、业务E2E、生产数据权限及产品VIS均未通过，不因工程壳CI绿而解除。M-001截图未取得、只有390窗口行为/布局证据，仍需后续页面按21号持续验证；中性壳不新增产品VIS门禁。

## 执行环境

Java21构建已在CI验证；仓库/审核人blueXYing已落实，PR6/7已合入develop。旧“无Git/Java17/缺Owner/等待Wave启动”等启动快照不再是当前阻断。

## W3新增后续追踪门禁

W3-TRACE-001：EPIC-19客服承载待既有产品决定，未分配Issue，W4补跟踪。W3-TRACE-002：C-002～006、M-002～004、A-002～005共12项Issue的Epic与Story归属不一致；受影响条目在派发前修正/明确跨Epic映射。本次不新增或重拆任务，详情见WAVE_1_CLOSEOUT_CHECKS.json。

## W4规划更新：以本段覆盖追踪门禁历史

W3-TRACE-002的12项主链已按现有业务Story修正，次级关联见ISSUE_STORY_LINKS.csv；结构门禁可解除，但页面API/原图/真实依赖未因此解除。EPIC-19仍待客服承载原决定，不新增Issue。

新增CCR-W2-IDEMP-001限制公共持久化幂等，新增CCR-W2-API-001按域限制首批业务DTO；现有CCR不关闭。PLAT004已存在AsyncTask表结构，不登记虚假“缺Schema”，但须等公共ID/Clock固定交接。11个Wave2候选的完整Issue状态仍BLOCKED，阶段就绪详见READY_QUEUE_WAVE_2.md，计划/阶段通过不等于实现或整项DONE。
