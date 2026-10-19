# CCR-C-WIRING-20261019 — C 端接线批次契约变更（搜索/城市/订单电话/健康记录 + 修改密码裁决登记）

- 提出方：C 端接线收尾工人（任务书：用户 2026-10-19 裁决"全做"+1 裁决登记）。
- 状态：CONTRACT_SYNCED_IN_PR（本 PR 即载体；受影响契约 10号/11号/12号/68号（新建）/20号登记表同批修订，e2e 离线文档 smoke 计数钉 206→212 全绿）。
- 背景：2026-10-19 帧级覆盖率审计（`planning/progress/2026-10-19/figma-coverage-audit/REPORT.md`）判定 C 端 9 帧 C1 真缺口 + 1 帧 D 待裁决；用户当日裁决本批"全做"，并裁决修改密码页"不做"。
- SSOT/PRD 依据：PRD 5.1.2（首页搜索/城市）、5.1.3（疫苗/驱虫记录字段表）、5.1.17（联系电话唤起拨号）；SSOT 无密码体系条款（V1 登录=微信授权，23号 AUTH 无密码面）。

## 1. 变更清单（均为已批口径的读侧增补/参数化，无新产品状态机）

1. **门店目录关键词搜索**（审计 C1 #4，frame 77:1267）：`GET /api/v1/c/stores` 增可选 `keyword`（trim 后 1..30 码点；仅 `storeName`/`merchantName` 大小写不敏感子串；实现为既有候选读侧内存过滤，零新 SQL）。不改可见性合取/排序/分页/匿名语义；`categoryId`/sort 仍拒绝。契约：10号 §3.3.2 增补 + 11号参数。
2. **服务目录关键词搜索**（同上，PRD"搜索商家、服务"）：新增 `GET /api/v1/c/services?keyword&page&pageSize`（10号 §3.3.3，11号 `cSearchCatalogServices`）。服务域内新增 `selectActiveServiceCandidates`（本模块 Mapper XML）+ `ServiceQueryService.catalogSearch`：同一四条件合取（per (merchantId, storeId) 事实一次）、固定 `merchant,store,service` 升序、诚实 total、503 失败关闭。装配开关 `pet.service.query.enabled`（沿用），匿名 STR-D8 同款。**无城市维度**（`service_item` 无城市事实；跨域过滤需连商家 compat 表 SQL，V1 不引入）——PR 披露。
3. **开放城市目录读侧**（审计 C1 #5，frame 76:761）：新增 `GET /api/v1/c/cities`（10号 §3.3.4，11号 `cListOpenCities`）：`MerchantApplicationCityCatalog` 的匿名伴生读侧（仅 code/name；未配置 503 失败关闭）。**不改动**既有 `/c/merchant-application-cities`（入驻、Bearer 强制）语义。C 端城市选择为手动（V1 无定位能力，§4.4 维持），目录/过滤边界同源。
4. **订单联系电话**（审计 C1 #6，frame 129:11378；PRD 5.1.17"点击'联系电话'：唤起系统拨号"）：merchant 域新增内部只读事实端口 `MerchantStoreContactQueryApi`（`StoreIdQuery → {storeId, phoneMasked, phone}`；行存在为唯一门槛——**故意不做** §3.3.2 三条件合取，已支付订单在商家后续停业/不接新单时仍需可联系）；`GET /api/v1/c/orders/{orderId}` 详情投影增 `merchantContact` 节（`{phoneMasked, phone}` | null）。**隐私口径**：完整号码仅出现在鉴权（MINIAPP Bearer）、本人（404 防枚举不变）、no-store 的详情读侧；列表投影与目录读侧永不携带；掩码规则与 §3.3.2 同源。**降级口径（登记的不对称）**：门店行缺失（NOT_FOUND）或商家事实故障（DEPENDENCY_UNAVAILABLE）→ `merchantContact=null`，订单事实读取不受影响、不整体 503。契约：10号 §3.7 增补 + 11号 `OrderDetailData`/`OrderMerchantContact`。
5. **宠物健康记录子资源**（审计 C1 #7/#8，frames 96:2580/96:3006；PRD 5.1.3 字段表）：新增 [68号契约](../../docs/04-api/68-Pet-Health-Record-Contract-v0.1.md) + SQL68（`user_pet_health_record` + `user_pet_health_record_audit`）+ 四路由 `GET/POST /api/v1/c/pets/{petId}/(vaccination|deworming)-records`（11号 `cList*/cCreate*` 四操作）。沿 `pet.auth.c.enabled`；23号五元组幂等（201/200）；防枚举 `PET_NOT_FOUND`；冻结 403 `USER_FROZEN`；保守上限每宠每类 200（400 `PET_HEALTH_RECORD_LIMIT_EXCEEDED`，12号 §13.1 三处登记）；同事务元数据审计（35号同款，不含健康内容，重放不重复）；无事件/outbox 订阅方。**设计冲突裁决**：设计稿"接种状态"chips（已接种/待接种/即将到期）不入库——读侧只返回 PRD 字段表原始事实，状态胶囊为 C 端按 `nextRecordDate` 的展示派生（已批用户域"事实→展示推导不进契约"口径）；驱虫类型按 PRD 三枚举全集（设计稿两枚举）；附件图片 V1 不做。
6. **修改密码页"不做"裁决登记**（审计 D #106，frame 131:19091）：用户裁决——**V1 微信授权登录无密码体系，修改密码页不适用，原稿 frame 保留不实现**。PRD 5.1.29"修改密码：跳转修改密码页"一句以本裁决覆盖；23号 AUTH 契约不增密码面；后续若引入密码体系另立 CCR。登记表 §4.18 已同步。

## 2. 登记表推翻记录

**登记表 §4.11（2026-10-13）原"范围排除登记"包含「疫苗驱虫记录添加（工程无页面或 V1 范围外）」——本裁决推翻该项**（其余排除项不变）；§4.11 行内已加勘误注记，回补落地登记于 §4.18。推翻依据：审计 REPORT §4.7 #61/#62 判定该排除与 PRD 5.1.3 字段表矛盾（"§4.11 曾登记'范围排除'与 PRD 矛盾，建议回禀定夺"），用户 2026-10-19 裁决"全做"。

## 3. 影响与测试

- API影响：上述 10号 §3.2.2a/§3.3.2/§3.3.3/§3.3.4/§3.7 增补；11号 +6 操作（计数钉 206→212）；12号 +1 码。Schema影响：SQL68 新表两张（非默认 Flyway，随切片执行）。Event影响：无。前端影响：stores 搜索件/城市选择、home 城市芯片、orders 详情电话区块、pet-archive 两表单页与详情接线（app.config/package-check 同步）。
- 测试：离线文档 smoke（212 操作、31 回归）全绿；后端 `UserPetHealthRecordDomainMySqlTest` 真实隔离 MySQL 四用例（防枚举/PRD 校验/幂等重放与异参 409/上限+审计+冻结）全绿；前端 tsc/单测 590 全绿、weapp 构建 + package-check。HTTP 回环（C 端四族路由）随本 PR 交付留 E2E 轮补真实联调（如实披露，同既往切片口径）。
- 是否涉及产品裁决：NO（本批即用户裁决的执行；口径分歧点——拨号完整号码的读侧边界、服务搜索无城市维度、接种状态不入库——均已在 §1/登记偏差中显式登记）。
