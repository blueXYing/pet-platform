# Ready Queue — Wave 1

ACR-001 R1～R6已批准并同步；人工于2026-09-11另行批准启动 Wave 1。先派发GOV-001，其余READY仍为依赖解锁后的候选；不得假定前置任务已完成。

| Issue | Role | 任务 | 依赖 | 候选状态 |
|---|---|---|---|---|
| GOV-001 | Backend Core | 初始化统一仓库目录与最终 docs 基线 | 无 | IN_PROGRESS |
| GOV-002 | QA | 启用架构边界检查与基础 CI Gate | GOV-001 | READY |
| PLAT-001 | Backend Core | 验证并修复 Maven 后端骨架可构建 | GOV-001 | READY |
| C-001 | C-End Frontend | 统一微信小程序壳、用户路由、状态与平台Mock | GOV-001 | READY |
| M-001 | Merchant Frontend | 同一小程序商家工作区、分包路由与Mock | GOV-001,C-001 | BLOCKED |
| A-001 | Admin Frontend | React运营网页壳、路由、权限与Mock | GOV-001 | READY |
| QA-001 | QA | 测试框架、ArchUnit、Contract Smoke、CI首轮 | GOV-001 | READY |

保留7个原Issue，现为6个候选READY、1个依赖BLOCKED；没有新增或合并Issue。所有任务执行20号前端基线、21号测试补充、ISSUE_EXECUTION_NOTES_ACR-001.md与各自Issue正文。

## 批准后按依赖解锁的六岗位协作

- Backend Core：GOV-001形成基线后，另一个短Issue Thread执行PLAT-001。Java21与后端栈不变。
- C-End：C-001建立唯一Taro React应用壳及consumer/shared；公共配置和锁文件由C-End唯一编辑。
- Merchant：C-001完成前可参加所属Issue的只读接口/设计审阅，不派发依赖未解锁的M-001实现。C-001完成后，M-001建立merchant普通分包，与后续consumer工作并行。
- Admin：A-001建立React/Vite运营Web，与小程序独立并行。
- QA：GOV-002后顺序执行QA-001，各自独立Thread，避免同时修改CI和架构测试；壳未形成前建立测试入口而非宣称Smoke已运行。
- Transaction Backend：参与现有Issue的交易契约/Mock语义只读审阅，业务实现仍等待原依赖；不新增第八张任务凑同时开发数量。

一个Issue一个短生命周期Thread，改代码必须独立branch/worktree。GOV-001或C-001未完成不假定基线可用；公共文件修改必须记录唯一编辑者、对应提交及交接。

## 文件所有权与AC

- PLAT-001的backend/**只授权骨架修复，不授权提前实现交易业务；与QA的架构测试/tools及backend/pom.xml共享部分先登记唯一编辑者，禁止并发改同文件。
- C-001的Allowed根通配只指根文件，商家业务只能由Merchant在src/merchant/**中修改；Merchant不改shared/根配置。运营只写frontend-admin/**。所有范围外修改先记录授权，SSOT及受保护Contract不可自行改。
- C-001/M-001/A-001具体AC和测试见对应Issue；不再遵循旧Vue或两个应用壳约定。
- GOV-001检查目录/资料/真实仓库及Owner配置、基线提交和ARCH-001/002；GOV-002落实ARCH-001～005基础门禁；QA-001复用门禁并建立Contract/Web/微信/VIS入口；PLAT-001要求Java21下Maven verify及ARCH-001～003，失败不得跳过。
- 内部fixture只验证通用壳；真实会话/工作台准入需CCR-ACR-001，生产权限产品规则已由OD-W0-003确定，具体API按CCR-PERM-001建立。原型范围与原始切图验收按20/21号文档执行，不在工程壳Issue提前批量实现产品页。
- 代码构建、平台测试、视觉还原均须分别给证据；原始素材尚未全部导出，商家原型未提供，不能因此声称实现完成。

Git上游/Owner、Java21及原技术CCR依照既有门禁处理。状态与依赖更新后才能产生“今天真正启动的Issue”；当前已获启动授权，按依赖逐项创建开发任务。


