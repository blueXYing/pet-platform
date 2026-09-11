# GOV-001 基线交付与阻断报告

日期：2026-09-11。执行者：Backend Core。状态：**未完成，等待阻断处理及审核**。本Worker不解锁依赖，不合并main/develop，不启动其他Issue或Wave。

后续调度记录：根Work已登记EX-W1-001，允许固定导入提交作为下游独立输入，GOV-001仍未完成；PLAT-001已报告修复提交6cfefa8799a6c1bcba8c48a35940215e1a6c4f7d及Java21 clean verify通过，GOV-002正在补完整规则。以下失败证据针对原始GOV-001快照；完整ARCH复验等待后续修复集成，本分支不纳入其他任务代码或根调度新增文件。

## 本次完成的范围

- 读取启动协议、Issue、角色、SSOT、相关三端PRD正文、22补充、技术基线05/17/20、工程18/19及ARCH/21测试定义。
- 核实真实私有仓库blueXYing/pet-platform只有main及初始README；本机gh未登录，非交互Git认证失败；GitHub连接可读且已成功创建Issue远端分支。
- 保留远端签名初始提交 `8c1aea572333fe64eb56d8373e5f3f20d085dea8`，还原对象并验证SHA。未复制凭据，未覆盖远端历史。
- 将430份原始用户文件（含根Work已批准的W2台账）原字节导入提交 `e8654c30b4dfe2cfc9e3c050110322d2a18bb6a1`，随后才在独立worktree修改本Issue文件。
- 新增最终资料索引、所有权表、Git审核/交付规则、可复跑校验脚本与证据。真实审核人保持 `.github/CODEOWNERS` 的 `* @blueXYing`；没有虚构岗位账号。
- 前端目录仍只有说明；backend骨架、SSOT、PRD、Schema、API/OpenAPI、Event、Scheduler与原测试正文均未修改。身份CCR、原始切图与公共文件所有权已在索引和所有权表明确。

## 分支和工作区

- 根资料目录：`C:\Users\Administrator\Desktop\宠物平台V1.0`，本地分支 `chore/local-import-baseline`。
- Issue工作区：`C:\Users\Administrator\Desktop\wt-gov-001`，分支 `chore/GOV-001-repository-baseline`。
- 远端Issue分支创建成功；最终提交/草稿PR结果以交接消息和Git实际记录为准，本报告不预填未完成的远端操作。
- 远端尚无develop；草稿PR若建立则以main提供差异审阅，禁止合并；人工决定develop基线及重定向后再走独立merge批准。

## 验证结果

| 验证 | 结果与边界 |
|---|---|
| 430份原始文件SHA-256 | 通过：仅README有获准扩充，其他429份未变；见original-sha256.json及baseline-check.json |
| 目录与模块 | 通过：统一小程序/运营网页/历史迁移目录存在，40个模块及POM齐全 |
| ARCH-001静态正例 | 通过：既有check-module-deps.py退出0，无biz→biz依赖 |
| ARCH-001静态负例 | 通过：隔离临时副本注入refund-biz→order-biz，既有检查退出1并点名违规；未污染原backend |
| ARCH-001完整Maven/Enforcer负例 | **未通过验收**：原项目Maven模型即失败，未能执行指定门禁，静态结果不替代完整矩阵 |
| ARCH-002 | **未通过验收**：已有ArchUnit只有API框架/domain→infra/web→persistence三条，普通biz application跨模块Repository/Entity/DO没有对应规则；构建也未进入测试 |
| Maven verify | **失败，exit 1**：大量内部com.petplatform依赖缺version；详见maven-verify.txt，非业务测试失败 |
| 产品/Contract/前端构建/运行时/视觉 | 本次无产品或Contract修改；前端尚无脚手架，未运行且未声称这些测试通过 |

运行环境：Windows PowerShell，Maven 3.9.12，当前构建进程专用 `JAVA_HOME=D:\soft\Android Studio\jbr`（Java 21.0.5）；默认系统Java17未修改。

复跑基础校验：`python scripts/verify-gov001.py`。复跑Maven前为当前进程指定上述Java21及PATH，再运行 `mvn -B -f backend/pom.xml verify`。原始日志和JSON均在 `evidence/GOV-001/`。QA只读审阅独立确认了规则缺口和依赖版本问题。

## 阻断与依赖交接

1. Maven骨架修复属于PLAT-001的backend范围；本Issue禁止修改backend。需要修复内部依赖版本并在Java21实际构建验证。
2. ARCH-002规则建立及ARCH-001～005持续门禁属于GOV-002；需完整普通biz跨模块持久化访问负例及有效失败证明，不能只扫描import或测试domain/web。
3. 两者都依赖GOV-001，而GOV-001 DoD要求对应测试通过，形成验收依赖循环。交Work记录并决定最小范围/依赖调整；本Worker不降低DoD、不擅自放行、不派发后续Issue。
4. 审核人确认资料基线及以上阻断解决、测试/CI通过且人工批准集成后，Work才能更新依赖解锁。GOV-002/PLAT-001/C-001/A-001/QA-001目前均不因本报告自动解锁；M-001另须C-001完成。
5. 远端分支保护尚未配置验证；本机Git认证仍不可用。连接发布结果须另核验。会话CCR、权限技术映射及商家设计/切图缺项保持原状态，不在本Issue扩大实现。

回滚/兼容：仅独立分支增加治理文件和基线导入；原资料和初始提交历史可追溯。无资金/交易行为变更。导入快照较大（包含Word原件），属于保留用户资料，并非新增业务实现。PR/CI失败或规则未齐时保持草稿和未完成状态。
