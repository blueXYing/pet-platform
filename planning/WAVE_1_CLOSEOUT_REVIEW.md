# Wave 1 收尾集成审查

审查日期：2026-09-14。结论：**工程壳范围审查通过；收尾状态文档待PR同步合并。** 未发现P0/P1代码或验收矛盾阻断本波工程壳封板。不声明业务V1完成，不启动Wave 2，不发布main。

## 1. 固定审查对象与合并事实

- develop：`fb024226898ae38923ddbfa2f55602280a9fd5a8`；tree：`95e2ac9502f7b80218bc8121d4a86021ff531944`。
- PR6实际merge：`2487d7302383d004ac6e6b3c5ef8260abf32df17`；PR7实际merge：上述develop提交，两次均经人工明确批准。
- main：`8c1aea572333fe64eb56d8373e5f3f20d085dea8`，未发布。
- 七个既有Issue均完成工程壳Scope；63 Issue、42 Story、21 Epic保持，未重拆任务。
- PR1～5是被完整集成候选替代而关闭，不能写成各自都merged；其历史失败、独立证据及分支保留。

## 2. 合并后真实CI证据

[push CI 34797686952](https://github.com/blueXYing/pet-platform/actions/runs/34797686952)直接运行在上述最终develop head，completed/success。根Work读取运行、全部job和四类实际日志；这不是以PR合并前的绿灯代替合并后验证。受测文件树不变，不另行重复整套测试。

| Job | ID | 实际结果 |
|---|---|---|
| backend | 103833865910 | PASS：Java21，41项目构建，22 JUnit零失败/错误/跳过；13 Python规则测试；ARCH001～005正反例进入Maven入口 |
| web-build | 103833886045 | PASS：干净安装、类型、生产构建、边界检查、6条Playwright内部fixture测试 |
| miniapp-weapp-build | 103833886031 | PASS：干净安装、类型、34测试全部通过、微信目标构建、普通分包及包体检查 |
| contract-smoke | 103833866085 | PASS：16操作/13幂等写/76内部ref、字符串ID金额；4个故障注入反例 |
| repository-policy | 103833866027 | PASS |
| frontend-inventory | 103833866036 | PASS，仅盘点，不替代实际构建 |

6/6 job实际成功，无skipped。后端是模块化骨架，39模块/294含package-info类、50有效类，真实Controller/Domain实现为0；不能据此推断未来业务测试覆盖。

## 3. 七项交付与跨端一致性

| Issue | 完成内容 | 合并及证据 |
|---|---|---|
| GOV-001 | 统一资料/目录、真实Owner、Git基线 | PR6；docs/08-engineering/GOV-001-report.md及revalidation-review；原基线失败保留历史 |
| PLAT-001 | 内部40模块版本管理、boot普通jar/exec打包 | PR6；合并后backend job重新覆盖，原PR2专属日志保留 |
| GOV-002 | ARCH001～005、负例、真实扫描覆盖、CI | PR6；backend/tools/evidence/GOV-002 |
| C-001 | 单Taro微信壳、consumer/shared、请求与上下文隔离 | PR6；shared/evidence/FINAL-ACCEPTANCE.md |
| A-001 | React运营壳、菜单/按钮/路由权限fixture | PR6；frontend-admin/evidence/VALIDATION.md和合并后Web job |
| QA-001 | 联合集成、Web smoke、离线Contract检查、CI | PR6；e2e/INTEGRATION_REVIEW.md和合并后6 job |
| M-001 | 普通merchant分包、工作区/准入fixture、竞态保护 | PR7；merchant/evidence/REVIEW.md及merchant-acceptance-platform.json |

只读独立代码审查确认：

1. shared/workspace每次replace推进revision、清缓存；成功及失败的旧响应均检查revision，consumer展示亦有revision校验。
2. merchant的ownedRevision保护防止旧页leave/dispose撤销新页；20条商家测试覆盖查询/样本延迟、切店/换用户、注销/撤权及双页面竞态。
3. 新商家页默认deny，不用深链参数决定权限；每次显示重新走准入适配。realAdmission和真实Transport明确CCR阻断，不把fixture身份当服务端授权。
4. C-End独立提交五个公共接入文件，Merchant只写merchant目录；单AppID/单入口/普通分包，不存在第二小程序。无shared或锁文件越权修改。

从批准的导入基线e8654c30到最终develop，`docs/00-ssot`、`docs/01-prd`、`docs/03-database`、`docs/04-api`、`docs/05-events`、`docs/06-scheduler`无变更。没有未披露产品/Schema/API/Event/Scheduler变更。

## 4. 发现与处理

**W3-DOC-001（交接风险，代码不受影响）：远端台账落后。** 审查时develop的WORK_STATE仍v0.7，Issue Catalog仍有READY/BLOCKED；根Work本地虽已更新，下一Worker若仅从远端读取可能重复派发。收尾文档分支同步七项DONE、当前W3结论、有效阻断及执行日志，旧WORK_STATE归档，不删除历史。该文档PR合并前，下一波派发保持暂停。

**W3-DOC-002（低风险历史文字）：** C-001 HANDOFF末尾旧“官方配额读取失败”与后续FINAL-ACCEPTANCE/规则来源JSON不一致。最终证据优先，M-001于2026-09-14重新HTTP200核验。收尾审查仅披露，不越权修改前端Owner文件，不以旧句子推翻实际证据。

**W3-TRACE-001（后续规划缺口）：** 目录有21个Epic，63个Issue实际映射20个Epic，EPIC-19人工客服暂未分配Issue。它对应既有OPEN_DECISIONS第4项人工客服承载未决，不阻断本波工程壳；W4需追踪承载裁决与原范围任务映射，不能声称V1全量需求已覆盖。本次不为填空新拆任务。

**W3-TRACE-002（后续派发门禁）：** C-002～006、M-002～004、A-002～005共12项仍挂在EPIC-20的前端壳Story，但Issue标注了其他业务Epic。下一波必须按既有需求修正或明确跨Epic追踪关系后才能派发这些条目；本次不猜测新Story、不改产品范围。七个Wave1条目没有该问题。结构校验见WAVE_1_CLOSEOUT_CHECKS.json：63唯一Issue、42 Story、21目录Epic、依赖引用及本波7项DONE/Owner/Test通过；全量追踪存在上述12项已披露不一致，不能写成全量一致。

## 5. 通过边界与后续门禁

- C-001共享壳有390×753及414×672真实窗口记录；M-001新增商家页只有390×753实际行为和原生安全区/布局记录，不能混称商家双窗口通过。
- M-001截图通道超时未取得新图，physicalClickVerified=false；App.evaluate调用真实Taro回调不是物理点击。失败及有界只读观察恢复记录保留。工程壳MINI001～004有证据，产品VIS不在本阶段。
- 微信CI是构建和内部测试，真实DevTools证据来自对应Owner，未冒充本次push CI运行平台。真机、MINI006、生产业务E2E、真实API/RBAC/签约/资金、原图VIS均未验收。
- CCR-ACR-001、CCR-PERM-001及CCR-W0-001～003继续OPEN；OD-W0-001资金范围和OD-W0-002签约在相关业务前裁决；已批准单运营权限不再重复裁决。
- 原始商家Figma/切图与用户V1节点确认按后续页面Issue获取，不自行设计替代，不在W3新增业务。

## 6. 下一步

本次仅完成W3审查并准备文档同步。收尾PR合入后，才以同步状态进入W4下一波规划；不自动创建或启动Wave2任务。文档PR按既有协议由blueXYing批准合入develop，main不变。其CI可证明文档候选没有破坏原门禁；最终运行链接写PR正文，避免反复改动报告造成head变化。
