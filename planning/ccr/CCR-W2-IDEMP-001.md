# CCR-W2-IDEMP-001：公共持久化幂等事实与事务映射

状态：OPEN_SPEC_REQUIRED。Owner：PLAT-002 / Backend Core；Transaction、QA审查，Contract Owner blueXYing审批。此文是缺口登记，不是新API或DDL授权。

证据：内部API07 §2、§21及HTTP10 §2.3规定写requestId、相同参数重放结果、不同参数冲突、数据库唯一键或幂等表且不能只靠Redis。现有核心表仅分散提供业务唯一键，未提供可直接实施的跨业务公共记录生命周期。

待草案逐项明确：

1. key的业务命令、主体/租户及requestId作用域；不得仅requestId全局混用。
2. 参数摘要的规范化、排除字段与版本；同参数重放和不同参数冲突语义。
3. 并发处理中响应/等待、成功结果持久化与再读取、失败/重试/过期和保留策略。
4. 业务写入与幂等事实的原子事务、崩溃恢复及外部副作用边界；后续Provider幂等不被本机制替代。
5. 选择已有业务唯一键封装、独立记录或其他合规持久化的依据；逐项Schema/API/Event影响及迁移，不预定必须新增表。
6. 反例：重复并发、参数差异、跨主体相同requestId、提交前后崩溃、结果重放；见WAVE_2_TEST_ACCEPTANCE.md。

PLAT-002可先起草本规范，ID/Clock/金额的已确定部分可在精确文件Owner下推进；公共幂等实现与全Issue DONE等待审批。仅规划，不关闭本CCR、不运行测试、不改产品。
