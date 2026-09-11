# A-001 验证记录 · 2026-09-11

状态：工程壳指定检查已通过，可Review；未合并，未验收真实权限API或业务功能。

独立worktree：C:/Users/Administrator/.codex/worktrees/9b9e/宠物平台V1.0
分支：codex/a-001-admin-shell
输入基线：e8654c30b4dfe2cfc9e3c050110322d2a18bb6a1（EX-W1-001）

| 检查 | 实际结果 | 证据 |
|---|---|---|
| typecheck | PASS，tsc --noEmit，含源码/配置/测试 | build.txt |
| WEB-001 | PASS，生产构建；路由/Mock；浏览器fetch拦截；字符串精度；写requestId；401/403；失效响应；生产包关闭fixture | build.txt / playwright.txt |
| WEB-002 | PASS，路由直达未登录/403；菜单/按钮允许与拒绝；权限查询失败关闭访问；退出 | playwright.txt |
| PERM-005 fixture | PASS，财务账号未授权403、显式授权200；普通账号拒绝；角色重命名不提权；超管未知动作拒绝 | playwright.txt |
| 前端边界 | PASS，仅依赖本工程及声明的React依赖，无小程序平台调用，生产JS无fixture | boundaries.txt |
| Playwright合计 | 6 passed (24.2s)，0 failed | playwright.txt |

环境：Windows、Node 22.23.1、npm 10.9.8、Chromium 153.0.8010.12 revision1243、1440×900、2 workers。依赖准确版本见 versions.txt 和锁文件。测试HTTP采用Playwright路由拦截，未连接真实Java后端。fixture的再次授权检查发生在内存Transport，不是生产服务端验证。

首次类型检查发现浏览器绝对路径动态import声明无法解析，已改为变量导入并绑定源码类型；最终类型检查和全部测试通过。运行时仅有 NO_COLOR/FORCE_COLOR 环境告警，不影响结果。

未通过/未执行范围：真实RBAC与数据范围、CCR-PERM-001及CCR-ACR-001接入、登录/MFA、真实审计/脱敏、资金/退款/内容发布业务、跨端E2E、Figma视觉验收、仓库集成CI。后端ARCH-001～005由GOV/QA负责，本记录前端边界检查不替代后端测试；本Issue未修改后端。

SSOT、Schema/API/Event/Scheduler及公共DTO无修改；只改frontend-admin。默认生产构建封闭，待AUTH-001批准契约后再接真实服务。无第二运营或内部审批流程，无A-002～005业务页面。
