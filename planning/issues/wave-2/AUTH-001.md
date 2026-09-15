# AUTH-001 登录认证/RBAC与同小程序身份契约和实现

Owner Role: Backend Core
Epic: EPIC-02
Story: ST-ID-01
Priority: P0
Wave: 2
Status: BLOCKED
Dependencies: PLAT-001
Phase Readiness: SPEC_READY

仅规划候选，未获Wave2启动批准。BLOCKED是完整Issue实现门禁；Phase Readiness仅表示批准后可进行明确阶段。阶段完成不等于整个Issue DONE。

## Allowed / Forbidden

原实现范围：`backend/pet-user-* backend/pet-admin-* backend/pet-boot`。不得改变产品规则、跨biz持久化、公共契约或他人文件。规范草案仅可在本Issue负责的planning/ccr/CCR-ACR-001.md；planning/ccr/CCR-PERM-001.md及其planning/ccr附属草案中形成；不意味着可直接改权威Schema/HTTP/OpenAPI。公共文件、测试与boot装配按WAVE_2_PLAN.md登记唯一Writer。

## 来源与门禁

来源：SSOT §24；22号权限补充；商家PRD §5.2；HTTP10 §2/3.1。

CCR-ACR-001/PERM-001及相关Schema/API未批，签约事实受OD-W0-002约束。

原Catalog依赖保留；以上实际Contract、API、视觉、共享文件及完整验收依赖同样是门禁，不以Catalog少写一项绕过。

## Acceptance Criteria

1. 先建立完整会话、用户/商家/门店准入、动作/数据范围及401/403/刷新/撤权契约与正反Mock；规范阶段不等自身实现。
2. 不以客户端staffId或工作区切换授权；每次进入重新校验，错误不放行，保留规定存量履约/售后例外。
3. RBAC执行单运营、获权编辑直发、超管批准范围全权，不增加第二审核人；服务端鉴权/用途/脱敏/审计仍有效。
4. 签约Provider与待签/成功/失败事实不能默认成功；必要产品冲突转OD002，其他独立规范可先审。
5. 获批后按规范实现并运行服务端权限/撤权/越权测试，再允许公共SDK及前端真实接入。
6. pet-user与USR001、pet-boot与平台Issue按具体文件互斥；依赖公共ID/幂等的写入在对应批准能力就绪后接入。

## Required Tests

原要求：权限/登录P0,MINI-002~MINI-004,WEB-002,PERM-001~PERM-006。新增可执行验收定义：W2-AUTH-001～004，见planning/WAVE_2_TEST_ACCEPTANCE.md。所有相关ARCH001～005持续通过。模块基础夹具只证明该能力，不把尚未实现的订单/支付场景编号直接报PASS。

## 交付与DoD

交付固定commit/工作区/PR、规范版本/审批、正反测试结果、未验收范围与风险。所有原AC、Contract及所需测试满足后才DONE；真实E2E未具备服务就保留未完成。Schema/Event变化显式披露，重大Contract和合入develop仍需人工批准。预计阶段顺序与本波承诺见READY_QUEUE_WAVE_2.md，不自动转到后续Wave。

2026-09-15阶段派发:C端微信登录链路任务见[AUTH-001-c-login.md](AUTH-001-c-login.md),并行提示词见planning/prompts/;SMS/密码登录仍为后续门禁。
