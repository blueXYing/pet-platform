# MER-001 门店读侧切片：测试计划（TEST-PLAN）

日期：2026-09-22。状态：**计划，未执行**（依赖 [store-read-proposal.md](../../../ccr/CCR-W2-API-001/store-read-proposal.md) 获批后方可进入实现与测试）。风格对齐 `planning/WAVE_2_TEST_ACCEPTANCE.md`（PASS/FAIL/NOT_EXECUTED 如实报告；本文件不表示测试已运行）。

## 1. 验收项定义（建议新增 W2 编号，不取代既有 W2-MER-001～003）

| ID | 触发与预期 |
|---|---|
| W2-STR-001 | 门店可见性三条件合取（merchantEnabled∧storeEnabled∧acceptsNewOrders）经批准事实读取；商家 OFFLINE/FROZEN、门店 OFFLINE/FROZEN、未签约/审核未过在列表隐藏且详情 404，响应与"门店不存在"不可区分（防探测）；不跨模块 Repository |
| W2-STR-002 | 列表分页/城市/参数：`page`1..10000、`pageSize`1..50 默认20、信封 items/page/pageSize/total、`merchantId,storeId` 数值升序；`city` 缺省=开放城市集合（成都）；开放但无店城市=200 空页；未开放/非法 city、未知参数、非法 ID = 400；ID 为 String |
| W2-STR-003 | 详情 404 语义：不存在/不可见一律 404 `STORE_NOT_FOUND` 不区分原因；与已冻结 `/c/stores/{storeId}/services` 的配合（详情 404 而服务列表 200 空页，均为已批语义，不互为矛盾） |
| W2-STR-004 | 错误两分不混同：事实源故障/状态未知/`merchant_profile_compat` 缺行（完整性反例）/坐标非法 → 503 `COMMON_DEPENDENCY_UNAVAILABLE` 失败关闭（列表整页 503，不得降级空页或部分页）；故障恢复后同请求 200 可见 |
| W2-STR-005 | 会话语义：无/坏 Bearer = 401 `COMMON_UNAUTHORIZED`；有效 MINIAPP 会话可读；未启用开关时路由不可达（默认关闭，403/404 落全局拒绝）；第五内部查询无所有者前提（消费者上下文调用成功，反例：同一消费者调 owner 视角 `getStore` 必 NOT_FOUND，证明未冒用） |

映射：ARCH001~005（模块边界）；W2-MER-001 保持其 owner 侧范围不因此解除。

## 2. 测试矩阵（正反例）

载体：pet-boot `StoreQueryHttpTest`（`ServiceQueryHttpTest` 同款：真实 MySQL/Redis，真实申请→审核 APPROVE→签约 SIGNED 链路造出可见门店；OFFLINE/FROZEN/缺 compat 行等异常态用 SQL 播种并在测试文档标注——比 SVC-D4 依赖面小，如实区分）。**本轮不跑全量、不占用共享端口**（本地受控实例，按既有测试基建）。

| # | 用例 | 预期 |
|---|---|---|
| P1 | 可见门店（ACTIVE+ACTIVE+APPROVED+SIGNED）分页列表 | 200；字段=九字段投影；无 merchantStatus/storeStatus/version/bookability 类恒真字段；金额/坐标格式合法（坐标可空） |
| P2 | 第二页与 total 一致性；pageSize 边界 1/50；page=10000 | 分页正确；排序 merchantId,storeId 数值升序（用大 ID 验证非词典序） |
| P3 | `city=chengdu` 显式过滤 | 仅返回 compat city_code=chengdu 的可见门店 |
| P4 | 省略 `city` | 等价开放城市集合（当前=成都），结果与 P3 一致；不硬编码城市名于断言以外的契约 |
| P5 | 详情 200 | 与列表同字段集；可见即资格合格 |
| N1 | 商家 OFFLINE / FROZEN（SQL 播种） | 列表隐藏；详情 404 STORE_NOT_FOUND；响应与不存在门店逐字节同构（同 code/message 形态，防探测） |
| N2 | 门店 OFFLINE / FROZEN | 同 N1 |
| N3 | 审核未过 / 未签约（资格链不完整） | 同 N1（acceptsNewOrders=false） |
| N4 | 详情不存在 ID / 非法 ID / 超长 ID | 404 / 400 / 400 |
| N5 | 未开放城市 `city=beijing`、非法词法 `city=Abc`、未知参数 `sort=distance`、`keyword`、`categoryId`（STR-D7 延后） | 400 COMMON_INVALID_ARGUMENT |
| N6 | page=0 / pageSize=51 / 非数字 | 400 |
| N7 | 无 Bearer / 过期 Bearer | 401（Filter 前置，先于参数校验） |
| N8 | 签约事实读取失败（注入故障）/ 状态枚举损坏 / compat 缺行 / 坐标越界 | 列表 503 整页失败关闭、详情 503；**不出现 200 空页**（与"空城市空页"区分的核心断言） |
| N9 | 恢复（撤销故障/补齐行）后同请求 | 200 可见（可恢复性） |
| N10 | 开放城市目录为空配置（boot Catalog 空） | 503（城市事实不可用，失败关闭；不返回未过滤全量） |
| N11 | 不可见门店的 `/stores/{id}/services`（已冻结路由复跑） | 200 空列表——与 N1 详情 404 并存，验证两路由已批语义不被本切片改变 |
| N12 | 消费者上下文调内部 `getDisplayStore` 成功；同一上下文调 owner 视角 `MerchantQueryApi.getStore` | 成功 / NOT_FOUND（无所有者前提冒用，SVC-D5 同款反例） |

## 3. 内部查询（第五查询）模块测试

- merchant-biz 隔离 MySQL 测试：`pageDisplayStores` 城市集合过滤、可见性合取、整页一次资格判定（N1~N3 数据面）、排序、分页 total 诚实（不含不可见门店）；`getDisplayStore` 两分错误；compat 缺行 503；不读别域表（ARCH 守卫）。

## 4. 不在本切片验证范围（如实声明）

- 完整"找店→进店→选服务→预约"流程（排期/订单未实现，不提前）；C-003 页面联调与 VIS（前端另行）；
- 分类筛选/关键词搜索（STR-D7 延后，无对应参数即无用例）；
- 评分/月售/距离/收藏/评价/相册/促销/人员字段（无事实源，不测不存在的能力）；
- 匿名浏览（STR-D8 沿用登录态；若人工裁决改匿名需另行安全测试计划）；
- 生产部署/开关启用（`pet.store.query.enabled` 默认关闭，生产启用另行门禁）。
