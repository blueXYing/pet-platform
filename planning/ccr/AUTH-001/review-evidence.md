# AUTH-001 审阅和交付证据

状态：**PROPOSED / PENDING_REVIEW · AUTH-001-draft-v2**。此文记录规范阶段检查，不作为真实认证或业务验收。

## 1. Owner与范围

任务ID `01a09eba-f8f7-7230-b89c-ca352aea09ef`；工作区 `C:/Users/Administrator/.codex/worktrees/ff85/宠物平台V1.0`；分支 `codex/auth-001-spec-prep`；原基线 `bcb269c2adc9405e669747d9b3bedfae2bf5ccbd`。初版交付`c1e0f84ba41389d7d10d29624239ba571b1586d1`后已释放Writer；本次根再次正式交接取消MFA修订，S2安全点暂停。

唯一AUTH Writer=Backend Core；C/M、Admin、QA/Transaction只读复审。根产品裁决`9cdc8eeac94fbe673fef420cc7767caddf1ba219`已原样cherry-pick为`7e1c36e`，4文件为SSOT§25、24号补充、AGENTS、FLT-020。根独占产品内容，AUTH不编辑；AUTH仅修订原9Markdown，原PR14最终13文件。无API/Schema/源码/SDK/配置/CI或S2变更，不改根dirty，不创建新PR/Issue。

## 2. draft-v1来源与第一轮审阅（历史）

本节及§3对应`c1e0f84`，不能作draft-v2验收。原MFA设计和审阅经历保留历史，但其活动要求已被SSOT§25及[24号补充](../../../docs/01-prd/24-取消MFA人工裁决补充-v1.0.md)覆盖，禁止照旧实现。

- C/M确认微信+手机号完整登录、拒绝游客公开浏览、手机号冲突不合并；区分冻结明确读取/申诉与未定写动作，下线按SSOT存量继续履约。
- Admin当时核原27行矩阵，财务原●拆默认只读及显式已批准执行；OD001不冻结既有退款/权益补偿但资金独立待定；补齐Web30分钟、后登录踢旧、当时的高权限MFA6位5分钟、无自助找回。MFA部分现已取消，其余独立规则保留。
- QA/Transaction确认最终授权检查时点方案不能宣称严格撤权先于所有commit；匿名认证/秘密回执需专用适配，不假冒SYSTEM，不把token无限保留在通用幂等表。
- 三端fixture与真实Contract界限、原16操作测试安全规则缺口均已登记。

## 3. draft-v1检查状态（历史）

三端和QA/Transaction复审完成，发现的问题均由唯一Writer修订并经原审阅者只读确认：

| 发现 | 修订及复核结论 |
|---|---|
| 401误删refresh、重置用途混用、C图形验证码无请求字段 | 明确刷新处理顺序、attempt/SMS用途矩阵；图形验证码限定Web并给协议，C单独频控；C/M确认闭合 |
| 初次绑定取会话/指定命令回执不完整 | 初绑直接grant；result query携原requestId/联合类型；Bearer换绑原接口重放，秘密60秒与reset最小回执区分；C/M与QA确认闭合 |
| 签约未知DENIED遮住存量、merchantEntry/Step/分页不明确 | 独立例外非空LIMITED；本人申请/STAFF/受限入口优先、固定Step.type和ID数值排序；C/M确认闭合 |
| 单个财务账号显式授权无载体 | extraActionCodes、有效公式/持久化/版本、全部管理路径防提权、服务端delegable；Admin确认闭合 |
| 当时的MFA新challenge绕错误累计、敏感导出遗漏、Web样例缺失 | 当时补账号因素计数/高权限谓词及Web样例，Admin确认闭合；因素部分现已整体取消，脱敏/导出/Web普通会话规则保留 |
| 刷新和撤销竞态、多Owner快照拼接 | family/account generation原子排序，Owner内原子快照及两轮单调版本校验；最终检查时点并非追溯取消；QA/Transaction确认提案闭合 |
| SMS未知与明确拒绝无法恢复、Web会话Owner冲突 | 独立SmsIntentStatus及机器下一步，pet-admin持有Web会话、pet-user仅小程序；QA确认闭合 |

文档结构检查执行方式：内存Python读取9个Markdown，校验状态标记/围栏配对、JSON严格解析/重复键、局部链接存在、通用错误码登记、样例ID String/时间毫秒/无真实秘密；Git diff --check及白名单范围检查。最终计数随固定提交回报；没有写入测试脚本或测试产物。

2026-09-14初版`c1e0f84`实际结果：`PASS_DOCUMENT_STRUCTURE_ONLY`，9个Markdown、11段JSON样例、23个局部链接、9个允许范围文件；CI34819661870六job成功。此结果仅对应旧head，不能覆盖本次取消MFA修订。仅代表文本结构和旧仓库回归，不是完整OpenAPI Schema验证或服务端鉴权测试。

真实服务未运行；W2-AUTH/MINI/WEB/PERM真实用例及Provider/MySQL鉴权均NOT_EXECUTED，签约相关BLOCKED。已有仓库CI即使通过也只证明原基线检查兼容，不证明新协议已实现。git基线CI与本文JSON语法检查不替代获批可执行Schema验证；当前仍是Markdown内协议样例，后续生成正式schema/Mock须另验。

## 4. 交付和后续

本次draft-v2固定后更新原Draft PR14并报告新head/base/tree/最后CI；不merge。取消MFA产品规则已批准，不再请求批准；D1其它参数/D2仍PROPOSED/PENDING_REVIEW。源文档固定及复审完成后释放Writer交回S2，仅CI回执不继续修改head。两CCR不RESOLVED，完整AUTH-001不DONE。

## 5. draft-v2取消MFA修订

已移除活动Web第二步登录、额外因素验证/重发路径、对应进度/回执类型与会话字段、动作目录中的额外因素前置、因素绑定/恢复、Provider阻断及例子；权限变更仍失效旧授权并重新核动作，不触发追加认证。所有角色正常账号密码验证后建立Web会话。

保留原C端SMS主登录、微信手机号校验/换绑、密码重置证明、图形验证码/频控、attempt/cookie请求防串用、RBAC/范围/状态/审计/同人业务确认。短信challenge与OTP只属于原主认证/重置，不是换名恢复第二因素。

本修订检查：活动正文/DTO/路径/角色元数据/JSON无额外因素依赖；历史/取消规则引用允许保留。C/M、Admin及QA/Transaction限定只读复审未发现遗留P1；QA提出来源摘要混淆401/403，已修为未登录按HTTP401、缺动作权按403，根FLT-020内容未动。

2026-09-14新修订实际结构结果：`PASS_DRAFT_V2_STRUCTURE_ONLY`，9个规范Markdown、10段JSON、31个本地链接；状态/围栏/严格JSON与重复键/ID String/毫秒时间/秘密占位/错误码登记检查通过；活动规范无已删除的额外因素字段、路径或状态。4个产品文件当前blob与根commit逐个一致；仅原9规范dirty、无范围外文件，git diff --check通过。此为新文本检查，旧11JSON/CI不用于本版本。

固定后原PR14应恰为4产品+9AUTH共13文件；新head实际CI另在PR回执及根交付消息记录，不为追加CI结果继续改head。真实认证、业务权限及Provider验收均未运行。
