# AUTH-001 C端登录链路阶段(微信登录先行)

> 2026-09-16 状态同步：PR31及PR33已合入。PR31交付C会话/HTTP链路，PR33补齐真实微信Provider和环境配置字段；凭据不入仓库，真实小程序code联调仍需执行。下文范围描述保留首阶段来由，不重复请求已经合并的阶段。

Owner Role: Backend Core
关联 Issue: AUTH-001(本文件为其阶段任务;SMS/密码登录为后续门禁,不在本阶段)
Priority: P0
Wave: 2
Status: STAGE_MERGED
基线: develop `0e314cffe5e5085ad13386410002e59f43afaa0a`

2026-09-15用户批准并行推进;提示词见 planning/prompts/AUTH-C-LOGIN-PROMPT.md。

## Allowed / Forbidden

- 允许:`backend/pet-user-api/**`(C端会话DTO)、`backend/pet-user-biz/**`(user-auth应用服务/WeChatProvider接口+测试替身/会话签发)、`backend/pet-boot/src/main/java/com/petplatform/boot/adapter/web/c/**`、`boot/config/**`(C端会话装配,默认关闭)、对应QA测试与CI必要env。
- 禁止:admin/**与AdminAuth既有文件(只读参照)、frontend/**、pet-event/task/id-core源码、Schema权威文件、真实微信凭据入库。
- 参照范式(只读):AdminAuthService/AdminBearerAuthenticationFilter/AdminAuthHttpTest(PR17)、PR21全局异常/trace。

## 范围

1. POST /api/v1/c/auth/wechat-login(HTTP10 §3.1):code经WechatSessionProvider接口换取openid(真实Provider未授权——接口+测试替身,默认关闭,不内置任何真实凭据);首次注册不生成随机密码;按user_auth_identity(WECHAT_MINI)绑定/创建user_account。
2. C端会话签发与校验:Redis易失会话(对齐admin模式,B1精确同步已批),C端会话过滤器保护/api/v1/c/**写端点;会话主体=USER/operatorId,接通PR24已实现的PetService与用户资料查询(X-Request-Id幂等由服务层既有框架承担)。
3. attempt语义按CCR-ACR-001已批条款;不实现SMS/密码登录/重置(Provider后续门禁),登记不遗漏。

## 测试与DoD

- 真实MySQL+隔离Redis(CI已有AUTH_MYSQL_*/AUTH_REDIS_* env);HTTP全流程测试对齐AdminAuthHttpTest模式:登录成功/失败/重放/会话过期/宠物接口带会话可写、无会话401、冻结用户拒写。
- 首次创建201/幂等200按23号;无凭据不报微信链路PASS(替身如实标注)。
- CI六job通过+用户批准合并;完整AUTH-001非DONE,CCR-ACR-001非RESOLVED。
