# 提示词:AUTH-001 C端登录链路(复制以下全部内容到新会话)

你是本项目六岗位中的 **Backend Core**。开始任何工作前必须先读取工作区根目录的 AGENTS.md 并严格遵守;然后读取 planning/issues/wave-2/AUTH-001-c-login.md(本任务规划)、planning/ccr/CCR-ACR-001.md(已批会话规范)与 WORK_STATE.md 最新段落。

## 第一步:创建工作区(安全命令,不碰其他分支)

在仓库主目录(C:/Users/Administrator/Desktop/宠物平台V1.0)执行:

```
git fetch origin
git worktree add "C:/Users/Administrator/Desktop/wt-auth-clogin" -b codex/auth-001-c-login origin/develop
```

之后所有工作在 C:/Users/Administrator/Desktop/wt-auth-clogin 内进行。基线develop `0e314cff`。JDK21位于"C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"(export JAVA_HOME后用mvn -f backend/pom.xml)。

## 任务

C端微信登录链路(POST /api/v1/c/auth/wechat-login,HTTP10 §3.1)+C端会话,让PR24已实现的宠物服务与PR25前端可接真实会话:

1. WechatSessionProvider接口(code换openid/unionid)+**测试替身**;真实微信Provider未获授权,不内置任何真实凭据,装配默认关闭。
2. user-auth应用服务:按user_auth_identity(WECHAT_MINI)绑定既有账号或创建新账号(首次注册不生成随机密码,user_account.phone可空);Redis易失会话签发,对齐AdminAuthService模式(PR17,只读参照)。
3. C端会话过滤器保护/api/v1/c/**写端点,会话主体映射CommandContext(USER/operatorId),接通PetService与用户资料查询;X-Request-Id幂等由既有服务层框架承担。
4. attempt语义按CCR-ACR-001已批条款;SMS/密码登录/重置不在本阶段(Provider后续门禁),在交付说明中登记。

## 边界(违反即返工)

- 允许修改:backend/pet-user-api/**、backend/pet-user-biz/**、backend/pet-boot .../adapter/web/c/**与config/**、pet-boot/pom必要依赖、对应测试、.github/workflows/ci.yml必要env。
- 禁止:pet-admin-*(AdminAuth文件只读参照)、frontend/**、pet-event/pet-task/pet-id-core源码、docs权威契约、迁移目录、真实凭据/密钥入库。
- PR24的PetService/幂等框架**直接使用不得重写**;全局异常/trace(PR21)直接复用。

## 工作纪律

1. 动手前先输出"将创建/修改的文件清单"与Allowed自查结果。
2. 测试:真实MySQL+Redis对齐AdminAuthHttpTest模式(HTTP全流程:登录成功/失败/会话过期/宠物写接口带会话201、无会话401、冻结拒写/幂等重放)。本地可用临时MySQL实例模式(参照backend/pet-user-biz现有测试夹具);本地无Redis时如实报告以CI为准,不得skip报绿。
3. mvn -f backend/pom.xml -DskipTests install + 模块测试 + pet-architecture-test全过后,提交推送建**草稿PR**(base develop),盯CI六job,**不得自行merge**。
4. 最终报告:文件清单、测试结果(本地/CI分开)、替身与真实Provider边界、未做项(SMS/密码登录等)、PR链接。

## 环境

本机gh已登录;CI已有AUTH_MYSQL_*/AUTH_REDIS_* env(见ci.yml backend job)。
