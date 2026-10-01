# 已批准售后目录与确定冲突补充

2026-10-01。用户在两个具体CCR及WORK_EXECUTION_PROTOCOL §4批准依据展示后明确回复“批准”；授权范围为 `CCR-AFS-PAGE-OPTIONS-001` 与 `CCR-AFS-CONFLICT-001`，沿用现有PR99、多角色协作及Figma页面布局。无SSOT、Schema、Event、Scheduler变化；不包含生产目录内容裁决、环境启用、资金能力或PR合并。

## 最终行为

本人当前MINIAPP会话通过 `GET /api/v1/c/aftersale-options` 读取 `{typeOptions,demandOptions}`，不接受query（含空query）或body。每组1..100个唯一、按ASCII code升序的 `{code,label}`；label为1..64 Unicode标量/码点，拒绝孤立surrogate与ECMAScript trim集合首尾空白。后端同一完整不可变ReasonPolicy配置提供查询与新创建校验；code与label集合不一致或标签无效时，两者均503失败关闭。前端在当前会话、完整目录、同订单资格三者均成功后才开放新提交；刷新先清旧证明，目录变化不改写未知申请。

确定新命令的case version或P4历史集合不匹配分别返回 `AFTERSALE_VERSION_CONFLICT` / `AFTERSALE_FINAL_SET_CONFLICT`，HTTP仍409，事务不提交业务副作用。成功原UUID重放仍在目录、版本和内容审核之前读取原回执并复验当前动作权限；未知结果、幂等忙、授权版本复验变化及429继续保留原UUID/内容。缺少P4必要证明是400，不伪装成集合变化。

C/M/A只有收到确定拒绝后，才退休匹配的原UUID+payload快照并重读当前卷宗/资格。C共享client将真实失败error绑定到当次完整Command；存储退休失败保留原journal。A迟到旧回执/冲突不能删除较新的journal；旧确认、原因和P4人工历史核对失效，重新核对后手动提交新UUID。读取失败保持禁写；详情重新验证或离开前台清私有图片。

正式契约同步Contract50/51、Internal07、HTTP10、OpenAPI11与Error12。GET表面新增为第24个AFS操作，总操作114、69个写操作仍要求requestId。生产开关值与用户原桌面配置均未变；缺正式配置目录时不会用fixture替代。

## 实际验证

| 检查 | 结果 |
|---|---|
| 小程序Node全量 | 252 passed，0 failed/skipped |
| 小程序typecheck / weapp / package inventory | PASS；weapp 9.93s，主包773746B、AFS子包277287B、M包177736B、总4376328B，预算通过 |
| 运营生产build / 边界 / Playwright | PASS；66 passed、2既有live opt-in skipped；实际App/源码UI+拦截HTTP响应，覆盖明确版本/完整P4重核及迟到旧冲突不清新请求；不等于页面真实后端联调 |
| Java21 offline单元/配置/边界 | 41 passed、0 failed/error/skipped；Catalog3、Boundary8、OptionsHttp4、WorkflowConfiguration26；根复核XML计数/hash见[摘要](ccr-unit-summary.json) |
| 契约文档 | 129 passed；offline smoke114 operations / 24 AFS / 69 writes |
| 模块/持久层/DisplayStatus门禁 | PASS；41 reactor modules、17biz无biz→biz，SQL仍Mapper XML；脚本18项通过 |
| 独立真实MySQL/Redis回归 | 37个唯一用例通过、0最终失败/跳过：14真实HTTP+23真实域数据库；分轮30+6+1，非单次Maven全绿。详见[独立QA](CCR-QA-HANDOFF.md)与[脱敏摘要](ccr-http-summary.json) |

单元初次加空query探针时，MockMvc把URI末尾 `?` 标准化为无query，导致测试错误地进入mock目录空值分支；改用明确 `setQueryString` 保留请求边界，最终41通过，真实HTTP另独立验证空query。新目录没有404/409业务分支，原文档门禁的统一响应集合首次报错；改为目录专用响应集合并保留既有操作门禁，129文档回归通过。小程序混合CRLF/LF的diff空白检查已统一LF修复。既有素材/字体体积warning及Vite config native兼容warning未扩大本批修复范围，实际构建/预算通过。

真实回归首轮旧4 suites共30通过；新增6项为3 failures/1 error。修复实际enabled安全GET路由遗漏，关闭状态沿用403/无controller；撤权测试修正为外部RBAC写等待AFS提交后生效、原成功UUID再重放403，并新增单独标为FAULT_INJECTION的同事务revision变化回滚。生产授权锁和域逻辑未为测试放宽。第二轮新增7项中6通过，仅尾部空query因Java HttpClient归一化失败；最后以literal HTTP/1.1 socket保留 `?` / `?&&` 复测该方法通过，真实返回400，不改生产约束。最终唯一用例覆盖为首轮旧30+第二轮未变6+最后修复方法1；XML计数、hash及准确源文件边界见QA摘要。最后41单元/配置28.000s、0失败/错误/跳过；冻结后源码门禁、114ops smoke及售后文档11项再次通过，不累加为新的全量计数。

## 交付边界与回滚

本增量没有新的页面真实三端联合闭环、物理真机、三档窗口、叠图或字体VIS证明。此前模拟器5个入口证据只覆盖旧版本未登录/OWNER门禁；不可冒充目录端到端体验。产品目录仍须使用已批准代码和名称配置，生产开关不自动开启。完整C-005/M-004/A-004/QA-005不提前DONE。

本次无需数据库迁移。可一起回退本批后端、契约和客户端提交；若前端连到旧后端，目录缺失保持禁新申请、通用冲突保留原请求。持久未知命令不自动重放，不能以回滚理由擦除它们。PR保持草稿，最终head CI单独记录。
