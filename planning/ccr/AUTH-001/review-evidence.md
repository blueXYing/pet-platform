# AUTH-001 审阅和交付证据

状态：**PROPOSED / PENDING_REVIEW · AUTH-001-draft-v1**。此文记录规范阶段检查，不作为真实认证或业务验收。

## 1. Owner与范围

任务ID `01a09eba-f8f7-7230-b89c-ca352aea09ef`；工作区 `C:/Users/Administrator/.codex/worktrees/ff85/宠物平台V1.0`；分支 `codex/auth-001-spec-prep`；基线 `bcb269c2adc9405e669747d9b3bedfae2bf5ccbd`。根Work在S2固定`d5acc3a80c610dcc8cb064dc9571181cffb1056e`并释放后明确授予AUTH规范Writer。

唯一Writer=Backend Core；C/M、Admin、QA/Transaction审阅只读，不新建用户Issue，不共同编辑。只允许两个CCR和本目录Markdown。无权威docs、Schema、源码、SDK、CI或S2变更。根dirty未改。

## 2. 来源与第一轮审阅

- C/M确认微信+手机号完整登录、拒绝游客公开浏览、手机号冲突不合并；区分冻结明确读取/申诉与未定写动作，下线按SSOT存量继续履约。
- Admin核原27行矩阵，财务原●拆默认只读及显式已批准执行；OD001不冻结既有退款/权益补偿但资金独立待定；补齐Web30分钟、后登录踢旧、高权限MFA6位5分钟、无自助找回。
- QA/Transaction确认最终授权检查时点方案不能宣称严格撤权先于所有commit；匿名认证/秘密回执需专用适配，不假冒SYSTEM，不把token无限保留在通用幂等表。
- 三端fixture与真实Contract界限、原16操作测试安全规则缺口均已登记。

## 3. 检查状态

三端和QA/Transaction复审完成，发现的问题均由唯一Writer修订并经原审阅者只读确认：

| 发现 | 修订及复核结论 |
|---|---|
| 401误删refresh、重置用途混用、C图形验证码无请求字段 | 明确刷新处理顺序、attempt/SMS用途矩阵；图形验证码限定Web并给协议，C单独频控；C/M确认闭合 |
| 初次绑定取会话/指定命令回执不完整 | 初绑直接grant；result query携原requestId/联合类型；Bearer换绑原接口重放，秘密60秒与reset最小回执区分；C/M与QA确认闭合 |
| 签约未知DENIED遮住存量、merchantEntry/Step/分页不明确 | 独立例外非空LIMITED；本人申请/STAFF/受限入口优先、固定Step.type和ID数值排序；C/M确认闭合 |
| 单个财务账号显式授权无载体 | extraActionCodes、有效公式/持久化/版本、全部管理路径防提权、服务端delegable；Admin确认闭合 |
| MFA新challenge绕错误累计、敏感导出遗漏、Web样例缺失 | 账号因素计数/稳定高权限谓词，默认脱敏/地址/昵称头像/渠道号，Web grant/session/activity完整样例；Admin确认闭合 |
| 刷新和撤销竞态、多Owner快照拼接 | family/account generation原子排序，Owner内原子快照及两轮单调版本校验；最终检查时点并非追溯取消；QA/Transaction确认提案闭合 |
| SMS未知与明确拒绝无法恢复、Web会话Owner冲突 | 独立SmsIntentStatus及机器下一步，pet-admin持有Web会话、pet-user仅小程序；QA确认闭合 |

文档结构检查执行方式：内存Python读取9个Markdown，校验状态标记/围栏配对、JSON严格解析/重复键、局部链接存在、通用错误码登记、样例ID String/时间毫秒/无真实秘密；Git diff --check及白名单范围检查。最终计数随固定提交回报；没有写入测试脚本或测试产物。

2026-09-14固定前实际结果：`PASS_DOCUMENT_STRUCTURE_ONLY`，9个Markdown、11段JSON样例、23个局部链接、9个允许范围文件；上述检查无失败，git diff --check无输出。仅代表文本结构和示例约束检查，不是完整OpenAPI Schema验证或服务端鉴权测试。

真实服务未运行；W2-AUTH/MINI/WEB/PERM真实用例及Provider/MySQL鉴权均NOT_EXECUTED，签约相关BLOCKED。已有仓库CI即使通过也只证明原基线检查兼容，不证明新协议已实现。git基线CI与本文JSON语法检查不替代获批可执行Schema验证；当前仍是Markdown内协议样例，后续生成正式schema/Mock须另验。

## 4. 交付和后续

固定文档commit与Draft PR后报告head/base/tree/最后CI；不merge。源文档固定并复审完成后明确释放Writer，等待人工审阅。接受技术方案、PR文档合入、权威Contract同步及实现分别按协议授权；两CCR不RESOLVED，完整AUTH-001不DONE。
