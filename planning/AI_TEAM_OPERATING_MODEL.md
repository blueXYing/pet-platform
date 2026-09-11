# AI Team Operating Model

## 同时存在的是“岗位”，不是永久聊天

建议并发岗位：
1. Backend Core
2. Transaction Backend
3. C-End
4. Merchant
5. Admin
6. QA

每个岗位每次领取一个短 Issue。
同一个 Issue → 一个 Codex Thread → 一个 task branch/worktree。

## Wave 运行循环

```text
Work读取Issue/PR/CI
→ 计算READY
→ CTO批准Wave
→ 创建worktrees
→ 多Codex并发实现
→ PR/CI
→ AI Reviewer
→ CTO批准Merge
→ develop集成
→ QA回归
→ Work重新计算下一Wave
```

## 人类只做
- 产品裁决；
- 优先级/范围；
- CCR产品影响判断；
- Blocker处理；
- Merge/Release批准。

Git细节、冲突解决、测试执行优先交给 Codex/Integration Worker。

## ACR-001已批准的前端协作

六岗位不变。C-End负责统一小程序壳、consumer与shared；Merchant负责merchant分包，M-001依赖C-001；Admin负责React Web。共享package/锁文件/app配置由C-End唯一编辑，其他角色在所属Issue协作而非并发抢写。依赖解锁后C/M各自目录并行，Transaction按原业务依赖解锁或参与只读契约审阅，不为凑线程提前写交易。
