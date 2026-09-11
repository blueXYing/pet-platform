# A-001：运营 React 工程壳与权限 fixture

原frontend-admin只有说明，无法启动或验证权限。新增可构建的React/TypeScript/Vite/React Router工程、概览/权限示例路由及独立Web请求适配。菜单、按钮和直达路由覆盖允许、拒绝、未登录、查询失败。普通/财务账号按显式授权判断；单运营直接执行示例动作；超管只覆盖已知示例动作。

内部fixture不构成公共DTO或真实API。默认生产包关闭入口且排除fixture；真实权限等待CCR-PERM-001/AUTH-001。未新增业务页、财务系统或审批流。只修改frontend-admin，无公共Contract变化。

验证：typecheck、生产build、WEB-001、WEB-002、PERM-005 fixture共6个Playwright用例全部通过；前端导入/生产包边界检查通过。详见evidence/VALIDATION.md及原始输出。

未执行：真实Java鉴权/数据范围/审计、业务E2E、视觉还原、集成CI和后端架构测试。GOV-001尚未完成；依赖启动按EX-W1-001，只使用核验后的固定资料。此PR堆叠至chore/GOV-001-repository-baseline，保留其治理增量；不得直接合并main/develop。

审核人及技术契约审核人：blueXYing。请人工Review，未授权合并。
