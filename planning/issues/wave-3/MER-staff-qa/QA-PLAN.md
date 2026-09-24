# MER 员工基础操作独立 HTTP 验收计划

状态：独立 HTTP 验收已运行通过。源基线 `980a830`，冻结提交 `86ee9c8`，共享安全接线 `1526702`，员工实现 `5eeea3f`。这不代表 MER-001 全部完成。

## 依据与边界

- SSOT §29、`docs/04-api/27-Merchant-Domain-Contract-v0.1.md` §3/5/6/7、23 号公共幂等 §2～6、34 号排期保护 §2、35 号员工管理契约与 SQL35。
- 本轮可交付员工列表、详情、创建、档案更新、启用。`disable` 因 ORDER 当前指派和并发保护缺失继续 `IMPLEMENTATION_BLOCKED`，不得把路由存在或简单先查当成可安全停用。
- 实际 `merchant_member` / grant DDL 与绑定流程尚缺。子账号验收只能证明**没有真实绑定时**不得通过手机号、staffId、角色参数或店铺 ID 自报取得主账号权限；不得伪造一条绑定来声称完整成员授权通过。
- 能力与排班事实可在隔离 MySQL 中 SQL 播种，用于验证新员工事实进入 SCH-002 的只读容量交集；不代表人员能力维护、排班维护、SCH-003 hold 或完整预约闭环。

## 准备与 fixture

1. 每个测试实例使用 `CAuthHttpTest.HttpFixture` 的随机数据库和 Redis 前缀，隔离执行 SQL35，不作生产迁移。运行环境仅连接指定的本地 MySQL 8 / Redis，关闭时仅清理自身随机数据库与缓存键。
2. 通过真实 C 会话、申请提交、运营登录与审核动作建立商家/门店，再发布测试协议并由真实主账号签署。检查签署前员工读取仍可按真实 owner/归属进行，而写入 409；签署后准入 `ALLOWED` 且有 `merchant.staff.manage`。
3. 另建真实 C 主体及商家，核对跨商家、跨门店读写 404；未绑定主体即使使用员工手机号或提交 `staffId` 也无管理权。不得用插入 `merchant` 的状态或 `merchant_staff.phone` 推断已审核/已签/已绑定。

## 独立断言矩阵

| 场景 | 判定 |
|---|---|
| 创建 ACTIVE、`serviceEnabled=false` | 首次 201，`staffId`/`merchantId`/`storeId`/`version` 为规范十进制 String；姓名原样保存，电话仅掩码。相同 requestId/参数重放 200、同一 ID/首次版本，DB 无第二行。 |
| 查询与分页 | owner 列表/详情仅见目标店员工；ID 按数值升序，过滤、总数和分页在归属范围内；页码超出1～10000、pageSize超出1～100或未知字段 400。已知未签、FROZEN/OFFLINE仍可按归属只读。跨商家、跨店、其他主体访问 404 且 `data=null`。 |
| 档案替换 | PUT 携带 `expectedVersion` 成功 200 并递增版本；省略或显式 null `phone` 均清空；再次 GET 反映当前事实且不泄露旧号码。旧版本 409；未知字段、重复JSON键、尾随内容、非法枚举/布尔/ID/版本 400；DB 保持不变。 |
| 启用 | 仅 ACTIVE 人员、商家/门店可经营时成功 200、版本递增；即使目标已为 true 的新请求也推进一次；重放不重复推进。INACTIVE 且 enabled=true 创建拒绝，不能借 enable 把 INACTIVE 自动转在职。 |
| 幂等与权限 | 同命令/主体 requestId 异参 409 `IDEMPOTENCY_KEY_CONFLICT`；成功后撤销 owner 关系，再用旧 requestId 重放必须重新核当前权限、返回防枚举 404 且 `data=null`，不能泄露旧回执。 |
| 停用边界 | `disable` 在保护契约未接入时不能产生 2xx 或改变 `service_enabled`；具体错误码随主协调冻结的关闭路径断言。 |
| 默认关闭 | 不设置 `pet.merchant.staff.enabled` 时员工Controller、命令Bean和全部员工handler均缺席，有效MINIAPP会话请求仍403；显式开启时五路由存在，但没有`disable` handler。 |
| SCH-002 交集 | 对 HTTP 创建并启用的员工，隔离库中补具体服务能力与完整 AVAILABLE 排班后，GET C 端可用性显示人数/容量增加；再次读证明员工事实来自 MER。能力或排班为空仍为 0。SQL 播种不算维护接口。 |

## 实施与验收记录

实现仅写 `backend/pet-boot/src/test/java/com/petplatform/boot/auth/MerchantStaffAcceptanceHttpTest.java`，使用真实 HTTP、会话、MySQL 及 Redis。2026-09-24 JDK21、隔离本地 MySQL 8 端口33455及Redis端口16383运行：`mvn -q -pl pet-boot -am -Dtest=MerchantStaffAcceptanceHttpTest -Dsurefire.failIfNoSpecifiedTests=false test`，41模块reactor成功，2 tests、0 failures/errors/skips（Surefire报告40.54秒）。测试从真实审核与签署取得准入；另一个真实商家及同手机号无绑定主体验证隔离；SQL能力/排班种子只证明SCH-002读侧交集；另以独立context证明默认关闭和disable无handler。审核来源SQL29、协议SQL28、员工审计SQL35均只在随机fixture库执行。

仍未覆盖：员工disable及其ORDER并发守卫、真实成员绑定、能力与排班维护、SCH-003 hold/预约完整闭环、生产迁移与运行环境开关。上述功能保持原合同门禁，不据本测试标记为完成。

独立静态审查：五操作未见阻断性契约偏差。Execution在同一MySQL事务内锁幂等绑定和MER当前归属/审核事实，再读加入当前事务的审核链与签署记录；成功重放前重验身份与经营写资格。非阻断风险：`listAllStaff`按门店取全部员工后在Java过滤分页，人员规模增长时请求内存和时延随全店行数增长。后续若转SQL分页，仍须保留损坏事实失败关闭和先范围过滤的语义。
