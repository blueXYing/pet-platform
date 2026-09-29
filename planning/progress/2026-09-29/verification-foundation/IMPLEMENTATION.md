# V1/V2 实现与验收

用户于2026-09-29批准 V1、V2。执行范围为真实凭证内核及改期失效，正式依据为SSOT §36、Contract47和Schema47；不关闭完整VER-001/VER-002/ORD-003。

## 已实现

- 160bit随机动态码、5分钟有效期；只读查询不生成、不续期，INITIAL/AUTO/MANUAL写入带requestId和凭证版本。
- 原子换码、滚动60秒最多5次成功手动刷新；旧版本冲突，失败保留旧码，首回执加密持久化并核对业务证据。
- 滚动5分钟第3个独立无效/过期尝试锁15分钟；锁、尝试、告警Outbox同提交，同请求重放不重复计数。刷新/改期/重启不清锁，缺状态但有风险历史时失败关闭。
- VER真实改期Provider与ORDER/SCH/TASK共享原事务及门店guard；旧码失效、新epoch、fence和ORDER相互引用一起提交。孤立fence和逐点故障不能留下部分结果。
- ORDER通过自身Mapper及其他Owner公共API核对支付、确认来源、当前预约与分钟级占用、退款事实。仅售后/退款申请不阻断，预约开始后仍可取码；退款单、取消或已核销实时拒绝。
- SQL47新增六张VER表；新增ORDER公共事实、内部凭证API、风险事件及未来取码HTTP契约，公开HTTP保持NOT_IMPLEMENTED。

## 验收证据

Java21、专用MySQL8.4端口3314验收完成：6份Surefire报告合计73项，零失败/错误/跳过（核销码23、改期19、配置6、保护3、ArchUnit22）。实际摘要、方法名及报告哈希见同目录 `local-targeted-tests.json`。另有契约回归118项、源码架构负例18项，以及模块依赖、MyBatis、DisplayOrderStatus、契约smoke和diff检查通过。契约smoke覆盖92操作、58幂等写入、1056引用、223个String ID字段。

执行命令为Maven `-pl pet-boot -am test -Dtest=VerificationCredentialAcceptanceTest,OrderRescheduleAcceptanceTest,VerificationCredentialConfigurationTest,CredentialProtectionTest`，随后独立执行 `-pl pet-architecture-test -am test -Dtest=ArchitectureRulesTest,ArchitectureRulesFixtureTest`（均设置无匹配测试模块不失败）。中断的一轮没有完整结果，已用最终代码重跑成功。远端全量CI以PR当前提交检查为准；PR93的698项基线报告不作为本次新增代码通过的证据。

首轮联动测试发现QA装配各自创建guard实例，导致真实Provider无法确认调用方持锁；已让同一调用链共享同一实例，未放宽生产守卫检查。后续回归以修复后的实际报告为准。

| 验收范围 | 主要验证方式 |
|---|---|
| VC-01～04：资格、期限、刷新与幂等 | 真实支付/人工及自动确认，服务开始后资格，旧码失效，AUTO提前拒绝，手动额度与并发争抢，同key并发及真实commit ACK丢失 |
| VC-05～07：改期失效及一致性 | 已发码/未发码、到店及接送窗口、换码与改期竞态、孤立fence拒绝、VER/ORDER/Outbox逐点故障注入 |
| VC-08～10：实时拒绝与风险锁 | 退款/取消/已核销拒绝，仅售后不阻断，第三次失败及并发锁，重放不计数，跨刷新/改期/重启，Outbox故障回滚，未发码时风险状态丢失拒绝初始化 |
| VC-11：保护和装配 | 密文篡改/AAD、旧密钥轮换读取、缺密钥/权限适配器拒绝启动、默认关闭和HTTP拒绝启用、架构门禁 |
| VC-12：既有回归 | 原19项改期数据库用例改用真实VER Provider，QA表仅作同事务故障探针 |

时间窗口测试通过调整隔离库历史时间验证到期和恢复；未等待真实5/15分钟，也未声称执行了公开HTTP或用户端倒计时验收。

## 交付边界与启用限制

`pet.verification.credential.enabled`及`.http.enabled`默认false。HTTP置true直接失败；内核开启要求既有基础能力、显式密钥和可信AttemptAuthority。没有生产默认商家身份适配器；QA使用显式授权适配器仅验证内核，不代表主账号/员工身份契约已实现。

`check`的VALID只表示当次凭证预检成功，不是核销完成或可缓存复用的最终授权。后续真正VERIFY仍需在最终事务重新校验，完成markVerified、未履约售后失效和审计闭环。

未交付：公开Controller、小程序取码页面、商家成功核销、完整OrderOperationGuard、手动订单号兜底、真实通知送达。未执行生产DDL、生产密钥配置、正式渠道调用或生产启用。后续启用前还需验证密钥轮换/恢复及迁移，不能以QA零密钥作为默认值。

停用入口不删除凭证历史、风险锁或fence；已有改期依赖真实VER证据时不能回退到伪fence、清空epoch或重新接受旧码。PR仅供审阅，不自动合并。
