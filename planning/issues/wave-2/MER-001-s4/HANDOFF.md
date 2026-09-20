# MER-001 S4 申请审核接口与数据基础交接

基线：PR52已合入`65df1c3`，合并CI 35208225200成功。用户已授权按顺序继续，并指定前端使用Figma插件及GPT-6中等。本地主目录develop已同步到该提交，用户project.config.json哈希前后相同。

## 本轮产物与边界

- [SQL29及存储说明](../../../../docs/03-database/29-Merchant-Application-Storage-v0.1.md)：申请/不可变版本/材料/证据/主体占用/领取任务/决定/审计/类型城市映射及单一HMAC policy，共11表。复合FK防跨申请/跨材料错配；补正可替换新材料而保留旧版本；APPROVE绑定本轮证据，旧claim不代替新核验。
- [30号接口补充](../../../../docs/04-api/30-Merchant-Application-Contract-v0.1.md)及OpenAPI：10个申请/审核操作，草稿与提交态、编号/决定/任务状态关联、严格请求白名单、后台脱敏与当前领取人约束。申请编号按原PRD为SQ+日期+随机码，主键仍Snowflake String。
- 07/10/12和Event08同步兼容增量；admin最终授权使用通用资源scope，不依赖merchant DTO。强制审核通知事件有严格payload和幂等/Outbox要求，尚未实现发布/消费。
- 全部新操作标CONTRACT_SYNC_CANDIDATE_NOT_IMPLEMENTED。已有60操作与原schema逐项深比较保持一致。本轮没有业务handler/Controller或生产迁移，不把SQL和schema测试称为真实审核闭环。

## 协作与复审

根负责OpenAPI/事件/公共说明与整合；原Sol实施代理因容量失败，由Luna xhigh写DDL；Sol medium做独立契约及真实MySQL QA；GPT-6 Astra medium独立核对前端历史原稿。三个子代理均在独占文件范围完成并释放，未自行提交/推送。

关键修正包括：避免admin-api耦合merchant DTO；HMAC固定持久policy不假装支持热轮换；人工核验必须当前领取人；材料位置归revision；新提交证据不复用旧版本；删除锁死合法任务迁移的双向可变状态FK；保持审批/audit/profile同申请链；修正OpenAPI分支示例及申请编号形状。SQL结构保证与服务层必须执行的授权、有效期、字段等值核验分别记录。

## 验证

- 真实MySQL8.4.9：SQL06+28+29初始化和5项正反约束场景通过。
- 离线契约99项通过（含8项本轮申请/事件用例），smoke为70操作、49写、10个申请操作、929引用。
- 全后端clean verify返回0/BUILD SUCCESS：38套件、294 JUnit，0失败/错误/跳过，含22项ArchUnit。计数与源哈希见[validation.json](validation.json)。
- 初轮发现并修复DDL引用缺唯一键、可变task状态FK死锁；OAS生成时发现定义分组/alias冲突及null分支示例不相容，均由真实检查发现后修复，不删旧门禁。结果不代表应用权限、密钥服务、私有资产或OCR已接通。

## 前端与Figma

按用户指定由GPT-6中等执行了[前端准备](FRONTEND-READINESS.md)，找到C端申请原稿132:862/132:1170，24个历史素材引用及哈希匹配。页面、路由和前端生产代码未修改，未声称VIS通过。

Figma插件经查询尚未安装，已给出安装入口；本轮没有Figma工具调用，没有用浏览器/REST偷偷替代插件。必须实际安装连接后再核对在线版本/节点。已有文件地址及file key：

- 用户端：https://www.figma.com/design/bp2vpcjjA5vZbHvtKkA8wl ，key `bp2vpcjjA5vZbHvtKkA8wl`。
- 商家端：https://www.figma.com/design/Usvn3d6UCVCAlDxou5KAK8 ，key `Usvn3d6UCVCAlDxou5KAK8`。

上述key用于定位设计文件，不是个人访问令牌。本轮未读取、生成或提交个人令牌；原始Figma大文件仍只在原本地归档目录，未上传Git。

## 后续次序

此技术同步包经审阅形成统一基线后，按已批准四项规则实现申请/审核事务、真实权限/私有材料和证据适配、协议/准入组合、强制站内通知，再接页面和全链测试。不重问既有产品决定；Figma连接是前端按指定工具实施的外部前置。新PR不自动合并，完整MER-001仍IN_PROGRESS。
