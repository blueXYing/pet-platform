# QA-001 · Wave 1 工程壳 INTEGRATION_REVIEW

本次候选仅集成 GOV/PLAT、运营壳、小程序壳和 QA 门禁；M-001未执行，整体Wave未完成。所有原PR保持Draft且未合并。合并必须等待人工批准。

## 固定组合与归属

独立任务 `01a08f04-df69-7be3-8157-94f18647128f`；分支 `codex/qa-001-wave1-integration`；worktree `71bf/宠物平台V1.0`。根工作区台账只读，未重置或纳入dirty文件。

| 来源 | 固定本地输入 | 远端PR/head |
|---|---|---|
| GOV-001资料基线 | 1edacb19f0d9ef8d527dc2d7efcea5c5f0c3b955 | #1 / 93ecce673d3a6c57bbd0ceb2adbb10fc3cda867b |
| PLAT两POM修复 | 6cfefa8799a6c1bcba8c48a35940215e1a6c4f7d | #2 / baf7b9a3b1b9f7e53e518be961efd51af50cb744 |
| GOV-002架构规则及CI | 79180f30c39dcd8f5d2d2a0e4f24746da9c39f1a + 8a98f52b4246ddfdc42c1e3075bd8808e4e072c5 | #5 / d8c2f080ac89618726fba0269ec99d6cb278dc53 |
| A-001运营完整目录 | ec63df34ab2fba7152b746b6a95b7fe08ff7146f | #3 / d558d1fba90d0370fb45daa7df211d600dc20c65 |
| C-001小程序完整提交链 | 9535d02 → 45f1542 → 8c1981a → c4fb0b30d5ce215d82f6d3486c0db02d7efe11a2 | #4 / eff6f8207b9f88f9b8dfb09fc2905f147c7277ee |

所有固定对象存在。采用PLAT一次和QA两个自身补丁，不重复引入QA父提交的PLAT。小程序保留相对e8654c30的完整4提交链，未只摘取最终证据提交。PLAT历史验证报告继续在PR2，不混入QA本次执行。GOV全部资料保留；完整组合树（QA新增前）本地/连接重建均为 `1a7d215bd5efa950f35956bf1ba9998033c830fd`。连接只复用已核实的远端blob并逐文件叠加到固定GOV树，无删除。连接commit元数据与本地不同，以tree匹配核验内容。

完整目录tree：运营 `aca3daf9605086b57874e3cd57210ce11b61a687`；小程序 `7394eed5eeac25cb3da36102f107153530cd92a0`；后端 `900ea75ec9d1bc20d6ea8d2913358f315ea77fad`。本地分别与固定Owner对象完全相同；远端逐文件核对运营、小程序匹配。QA本次只新增/编辑e2e及workflow，没有自行修复应用。

## 验证结果

本地应用/后端测试HEAD为 `3da49a832074483afd90009c1220b920c8ee6ec5`，后续QA变更仅测试入口、workflow和证据。日志在 `evidence/QA-001/`。本报告提交后真实CI必须验证最终PR head及实际merge ref；最终运行链接和job结果写入PR描述，不能引用旧PR5运行代替本候选。

| 状态 | 检查 | 证据与限制 |
|---|---|---|
| PASS | Java21.0.11 / Maven3.9.12 clean verify | 41项目；22JUnit失败/错误/跳过均0；13Python。ARCH001~005及合法/违规fixture。39生产模块294含package-info类、50有效类，真实Controller/Domain仍0，不代表未来业务覆盖 |
| PASS | 运营 Node22.23.1/npm10.9.8 干净npm ci | typecheck、生产build、check:boundaries通过 |
| PASS | Web Playwright 6条 | WEB001/002、PERM005内部fixture及拦截HTTP；生产dist独立验证。不是实际Java RBAC/数据范围/审计或真实API |
| PASS | 小程序同版本干净npm ci | typecheck、14测试（0跳过）、build:weapp、check:package；本次包311632字节/21文件，0普通分包。构建启动曾等待后正常完成，未改代码 |
| PASS | 离线Contract Smoke | 16操作、13写请求requestId、76内部ref、11字符串ID属性、金额字符串；4个故障注入负例通过。非完整OpenAPI规范验证、非在线契约/序列化/语义breaking审批 |
| PASS（引用） | C-001同源平台MINI001/005 | 完整小程序tree一致，引用其真实390×753和414×672/DPR3/SDK3.17.2/DevTools2.01.2510280证据。本次没有重跑平台；构建输出不是平台新执行 |
| NOT_EXECUTED | 本次微信工具/真机、真实API/权限/支付/扫码/上传、E2E01~08 | 不属于本次工程壳已验证业务；既有平台记录physicalClickVerified=false（原生tap回调），不能声称物理点击 |
| N/A（本阶段） | 商家真实分包、产品素材清晰度、VIS001~004、lint | 当前中性壳无商家分包/产品页/图片；复用visual-manifest完整字段入口；无lint脚本，不虚构执行。后续页面仍须原图和叠图验收 |
| BLOCKED（后续真实接入） | 会话与RBAC公共契约 | 复用CCR-ACR-001及CCR-PERM-001，未生成公共DTO或提前接入。不是工程壳构建阻断 |

当前生产包字节数不同于Owner旧报告311582，记录本次实际值；应用源树完全一致，同源平台引用不声称构建二进制相同。C-001 `HANDOFF.md`末尾旧“官方读取失败/配额待确认”已被其最终验收及规则来源JSON覆盖，为Owner文档残留；QA未越权改写。依赖安装的弃用/审计提示保留原日志，本次不升级Owner锁文件。

## 拟合并方案与回滚

建议人工先审核GOV资料PR1，再审核本组合（PLAT → QA规则 → A壳 → C完整链 → QA集成门禁）；此组合已经包含PR2~5实现，不再重复合并它们。原PR1~5本次保持不动，后续如何关闭/标记由人工和根Work决定。另可选择原PR逐一合并，但其最终组合树必须与本次受测树匹配并重新检查目标分支CI，不沿用不匹配的绿灯。

合并范围仅工程壳，无Schema/公共API/Event/Scheduler/产品规则修改，无数据库迁移。合并前回滚为放弃该候选分支；合并后由获授权Owner revert集成合并提交并验证，GOV资料基线保留。不直接push main/develop，不自动merge任何PR。

最终交付条件：真正包含两前端的最终head后端/Web/小程序及Contract CI全部成功，无必要job skipped；报告和PR可审查。仅等人工approve merge，不将本报告当作Wave整体完成或M-001开工批准。
