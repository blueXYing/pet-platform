# MS-3 交接：工作台准入前端接线（切片D）

日期：2026-09-22。分支 `feat/m002-ms3-workbench-20260922`（基于 develop `4b8549d`，独立 worktree）。主任务 M-002；消费 MS-2 交付的 CCR-W2-ADMISSION-001 两端点。

## 交付

- **共享层（C-End 唯一Writer 名义登记变更）**：`merchant-repositories.ts` 新增 `MerchantAdmissionRepository`（memberships + admission）与严格解码器（CCR 字段/枚举/排序/OWNER-staffEnabled 不变量）；`consumer-api.ts` 路径白名单加入 `GET /api/v1/merchant/auth/admission`。
- **`src/merchant/admission.ts`**：改为依赖注入形态 `AdmissionDeps`（memberships/admission 两查询），fixtureDeps 提供六种测试替身；真实装配在 `admission-runtime.ts`（隔离 Taro 依赖，node 测试可跑）。
- **`src/merchant/workspace.ts`** 控制器重写：enter=进入即失效旧准入并回 consumer 坐标 → memberships → 零店引导/单店自动选/多店显式选（禁止默认第一间）→ 选店切换 merchant 坐标 → admission 重查 → allowed/limited/denied 渲染；错误一律失败关闭（401/坐标错位有独立文案）；旧查询结果经 ticket 修订号守卫不得覆盖新判定。
- **工作台页**（无原稿，沿用申请页语言，不声称 VIS）：选店列表、ALLOWED 占位看板（动作码 chips+后续切片提示）、LIMITED 受限说明、DENIED 原因中文映射+nextSteps 白名单路由（COMPLETE_SIGNING→签约页、VIEW_APPLICATION/APPLY→申请页，未知 type 仅提示不跳转）、no-stores 入驻引导、错误重试。工程 fixture 注入 UI 移除（fixtureDeps 仅测试用）；shell 入口文案去掉"内部 fixture"。

## 语义要点

- admission 每次进入/回显重查；`authzVersion` 仅用于失效识别，前端不缓存放行。
- denied 后已选门店坐标保留至离开（签约引导从该坐标直达，不构成"必须先准入才能签约"的死锁）。
- memberships 在 consumer 坐标调用、admission 在 merchant 坐标调用（沿用客户端路径门禁）。

## 测试证据

- 工作台控制器 15 项（单店自动选/多店显式选/零店/受限/拒绝/双阶段失败关闭/每次重查/旧结果不覆盖/离开恢复坐标/旧页面不撤销新准入）。
- 共享客户端新增 3 项（CCR 解码器形状与枚举/成员分页边界/端点与回显校验）；全套 **133 项通过**。
- 真实链路 harness 扩展：consent 后 memberships(1)→admission ALLOWED（含 facts/动作/空原因码断言），随 Java 生命周期测试在 CI 执行。
- typecheck / build:weapp / check:package 全绿。

## 模拟器人工验收（2026-09-22，已通过）

真实链路终验于本 worktree 环境（真实微信登录/手机号授权、真实 OSS/ClamAV、隔离 MySQL/挥发 Redis、真实审批 API）：登录 → 申请填写/上传/提交 → 管理端审批 APPROVED（商家 ACTIVE、通知落库）→ 申请页重读 APPROVED → 签约页勾选签署成功 → 首页"进入商家工作区" → 单门店自动选中 → "工作台已就绪"（动作码提示、checkedAt/authzVersion 展示、工作区坐标切换 merchant）。子账号/多门店/受限态与真机走查未做（多门店与 FROZEN 受限展示有自动化覆盖）。

## 边界

- 完整工作台业务（订单/服务/排期/核销）不在本切片；LIMITED 的存量入口按钮仅提示"后续切片提供"。
- STAFF 路径与 merchantEntry 未实现（CCR §1）。
- 模拟器/真机人工验收待做（本地联调环境准备就绪后由用户执行）。
