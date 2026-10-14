# RESUME — 收藏+浏览足迹模块（32号裁决第二节批次，接力#2）

接力时间：2026-10-13。前任因限额中断（未提交任何 commit，工作树 33 个改动文件全在工作区）。
工作树：`C:/Users/Administrator/Desktop/wt-mod-favtrack`，分支 `codex/mod-favtrack-20261013`（基于 develop 6263f17）。
本文档为断点盘点；接力者从「下一步」继续。

## 一、前任已做（33 个未提交文件盘点）

### 1. 后端（完成度高，契约口径已定但文档未落）
- **契约口径（代码注释中已定，文档未写）**：编号 **58号**（`58-Favorite-Footprint-Contract-v0.1.md`，尚不存在），
  C 端路由族登记在 **10号 §3.16**（尚不存在），CCR 编号 **CCR-C-FAVTRACK-001**（尚不存在）。
  开关 `pet.favtrack.http.enabled` 默认 false，硬 guard：`pet.auth.c.enabled` + `service.query.enabled` + `store.query.enabled`。
- **Schema**：`docs/03-database/06-核心数据库Schema-v0.1.sql` 已加 `user_favorite` + `user_browse_footprint`
  两表（收藏=当前关系行硬删；足迹 (user,target) 唯一键去重、last_viewed_at 刷新置顶；target_type 仅 SERVICE/STORE）。
- **pet-user-api**（新增 10 文件）：FavoriteCommandApi/FavoriteCommands/FootprintCommandApi/FootprintCommands/
  FavoriteQueryApi/FootprintQueryApi/FavoriteItemDTO/FootprintItemDTO/UserTargetDisplayDTO。请求/回执 record 全齐。
- **pet-user-biz**（新增 14 文件）：FavoriteApiImpl/FootprintApiImpl（薄适配）、FavoriteService/FootprintService
  （内核：requestId 幂等复用 CommandIdempotencyStore 的 admit→lock→execute→succeed 帧（TrackCommandFrame，同补充23 §5）；
  业务重复收藏塌缩到既有行（created=false，200）；取消=硬删、缺席成功；足迹 upsert 去重）；
  UserTargetCatalog（目标准入走 ServiceQueryApi.getVisibleService / MerchantStoreDisplayApi.getDisplayStore，
  隐藏/缺失统一 404 SERVICE_NOT_FOUND/STORE_NOT_FOUND，facts 故障 503 fail-closed）；
  FavoriteStore/FootprintStore + FavoriteMapper/FootprintMapper 接口 + **MyBatis XML**（FavoriteMapper.xml/FootprintMapper.xml，
  本模块 XML，user_account 守卫读也在 XML）；FavoriteEntity/FootprintEntity。
  pom.xml 已加 pet-service-api + pet-merchant-api 依赖（经 *-api，不碰别模块 biz/persistence）。
- **pet-boot**：CFavoriteController（POST/DELETE/GET list/GET status 四路由 `/api/v1/c/favorites...`）+
  CFootprintController（POST record/GET list/DELETE `/api/v1/c/footprints...`）+
  FavoriteFootprintConfiguration（四开关齐才装配）+ 安全链/Filter/异常处理器注册（CSessionSecurityConfiguration/
  CBearerSessionFilter/CServiceExceptionHandler 均已改）。
- **测试已写**（未运行验证）：`pet-user-biz/FavoriteFootprintMySqlTest`（内核 367 行，真 MySQL 隔离库，SQL06+SQL14，
  FakeCatalog）+ `pet-boot/CFavoriteFootprintHttpTest`（适配层 418 行 + 默认关装配校验）。
  测试 fixture 直连 127.0.0.1:33440（FAVTRACK_MYSQL_URL 覆盖），自建自删 `favtrack_http_*`/`favtrack_user_*` 库。

### 2. 设计稿（缓存已提取）
`D:/Temp/figma-mcp/favtrack/`：fav.xml（128:3200 我的-我的收藏）、track.xml（129:3484 我的-浏览足迹）、
trackdetail.xml（690:11109 我的-浏览足迹-足迹详情）。要点：
- 收藏列表：服务/人员风卡片（头像60.7、名称+类目、评分+已服务N单、标签、¥xx 起、预约服务按钮）。
  **语义冲突**：原稿是"服务人员"卡（V1 收藏目标是 SERVICE/STORE），按 20号登记表惯例做偏差登记。
- 足迹列表：按 今天/昨天/更早 分组的行（类型 chip 服务/商家/动态 + 时间 + 标题 + 副题 + 头像 + 右侧删除钮）。
  **语义冲突**：原稿含"动态"（宠友圈社区内容）行——V1 足迹只 SERVICE/STORE，动态行不做，登记偏差。
- 足迹详情：整页即"门店详情页"形态（banner、门店卡、门店信息、优惠、团购套餐=服务列表、用户评价、
  拨打电话/立即预约底栏）→ **工程落点：详情不新造页，跳既有 store detail / service detail 页**。
- 官方色值：#5baae8 主蓝 / #c0ecff 浅蓝 / #f0fbff 极浅蓝。
- Figma key `bp2vpcjjA5vZbHvtKkA8wl`，token `~/.zcode/figma-token-dev.txt`（X-Figma-Token），需要渲染图时 REST 取。

### 3. 前端
**完全未动**。现状：mine 页（`frontend-miniapp/src/consumer/pages/mine/index.tsx`）收藏/足迹统计格与
"我的收藏"格位是纯视觉占位（View 无交互，注释指 §4.6）；服务/门店详情页
（`pages/store-services/index.tsx`、`service-detail.tsx`）无收藏入口、无足迹埋点。
页面注册见 `src/app.config.ts`（惯例：C 端子包一行紧凑注释）。

## 二、缺失清单（接力要做的）

1. **契约文档**：`docs/04-api/58-Favorite-Footprint-Contract-v0.1.md`（路由/字段/错误码/幂等/开关）。
2. **10号 HTTP 契约** `docs/04-api/10-HTTP-API-Contract-v0.4.md` 增 §3.16 小节（C 端收藏/足迹路由族）。
3. **CCR**：`planning/ccr/CCR-C-FAVTRACK-001.md`（契约缺失提案+裁决点：足迹侵入最小方案=客户端埋点，口径披露）。
4. **OpenAPI**：`docs/04-api/11-OpenAPI-Core-v0.4.yaml` 登记新路由（x-contract 58 号、x-assembly-switch
   `pet.favtrack.http.enabled`、IMPLEMENTED_DEFAULT_OFF、错误码全集、信封 schema）。
5. **contract_smoke**：`e2e/contract_smoke.py` 加 FAVTRACK_HTTP_OPERATIONS（7 操作：favAdd/favCancel/favList/
   favStatus/trackRecord/trackList/trackDelete）+ check_favtrack_http + census 并集 + 计数字段；
   `e2e/test_contract_smoke.py` 计数更新。
6. **21号部署开关清单** `docs/08-engineering/21-部署开关组合清单-v1.0.md` 加 `pet.favtrack.http.enabled` 行；
   `backend/pet-boot/src/main/resources/application.yml` 可能要加默认关注释（仿 V30/V31 惯例核对）。
7. **前端**：
   - shared api 客户端（favorites/footprints 增删查列表，requestId 幂等 UUID，401 会话复用 consumer-api 机制）；
   - 新子包页面：收藏列表 + 足迹列表（`consumer/pages/favtrack/{favorites,footprints}`，官方色值）；
   - mine 页接线：收藏/足迹统计格 + "我的收藏"格位 → navigateTo；
   - 服务/门店详情页：收藏入口（按设计稿心形/星钮）+ 足迹埋点（登录态 detail 加载成功后 POST record）；
   - 列表行点击 → 跳既有 service-detail / store-services/index（足迹详情页=门店详情，不新造）。
   - WXSS 铁律：button 0-1-1（border:0;background:..;padding:0..）/ 不嵌 button / 禁 data-*。
8. **20号登记表** `docs/08-engineering/20-设计源登记表-figma-map.md` 新增 **§4.13**（本批三 frame + 偏差登记）。
9. **验证**：后端定向测试（-Dtest=FavoriteFootprintMySqlTest,CFavoriteFootprintHttpTest）+ tsc/单测/build:weapp +
   check-persistence-style.py + test_architecture_gates.py + contract_smoke。
10. 提交、push 分支、建 PR（不合并、不 push develop/main、不含 project.config.json）。

## 三、坑与注意

- **禁止全量 reactor 测试**；定向 `-Dtest` + `-Dsurefire.failIfNoSpecifiedTests=false`。
- MySQL 127.0.0.1:33440 root/空、Redis 127.0.0.1:16379 已就绪；禁止 Docker/自装。
- JAVA_HOME=`C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`；npm ci 先行（若 node_modules 缺）。
- 长命令后台 + 60-90s 轮询保活（600s 看门狗）。
- S1ContractMappingTest（pet-common）断言 OpenAPI 操作全集——新路由不能撞 LEGACY_OPERATIONS 名单。
- 20号登记表现有最后小节 §4.11，本批任务书指定用 **§4.13**（§4.12 疑似留给并行批次，勿占用 §4.12）。
- 前端单测在 `src/consumer/tests/`；提交信息惯例参考 57号 commit 4505398（多段中文详述）。
- 限额错误（1308/1310）：更新本文件后停止。

## 四、下一步（接力执行顺序）

1. 先跑后端定向编译+两测试验证前任代码可编译可跑（发现问题先修）。
2. 补 1-6 文档/登记（契约→CCR→OpenAPI→contract_smoke→开关清单→yml）。
3. 跑 contract_smoke + pet-common S1 + 架构检查。
4. 前端 7（客户端→页面→接线→埋点/收藏入口）。
5. 前端验证 + 登记表 §4.13。
6. 提交/PR/报告。

## 五、接力执行日志

- 2026-10-13 接力#2：完成盘点，写入本文件。开始执行「下一步」。
- 2026-10-13 接力#2 续（全部完成，待提交）：
  - 修正控制器 201/200（原实现恒 200 与 javadoc/评价家族惯例不符）；修正前任 HTTP 测试足迹排序断言
    （先记 STORE 再重看 SERVICE 才能验证置顶）；补业务重复塌缩断言。
  - 后端定向测试全绿：FavoriteFootprintMySqlTest 12/12 + CFavoriteFootprintHttpTest 9/9（真实 MySQL 33440）。
  - 契约文档：58-Favorite-Footprint-Contract-v0.1.md 新建；CCR-C-FAVTRACK-001.md 新建（含 D1 埋点口径/
    D2 保留期限/D3 统计回填/D4 设计偏差四处裁决披露）；10号 §3.16 登记；OpenAPI 七路由+信封 schema+
    FavTrackHttpError（注意：可空 id 字段必须内联 string，$ref+nullable 兄弟在 OAS3 非法——57号同教训；
    曾误全局替换 storeId 波及 18 个 schema，已全部回滚，只留 favtrack 三个）。
  - contract_smoke.py：FAVTRACK_HTTP_OPERATIONS 七操作+check_favtrack_http+census 并集+计数；test_contract_smoke
    补 favtrack 计数与两条回归。PASS：operations=150、favtrackHttpOperations=7、pytest 30/30。
  - 21号清单 §1.11 + application.yml pet.favtrack.http.enabled 默认 false（python yaml 校验过）。
  - S1ContractMappingTest 5/5、check-persistence-style.py PASS、check-module-deps.py PASS、
    pet-architecture-test 0 失败。
  - 前端：consumer-api.ts 加 isFavoriteFootprintPath 门（大写 SERVICE/STORE 枚举需专用匹配器，通用小写
    白名单不匹配）；favtrack/ 模块（model 严格解码+分组；repository 走 write 日志槽位幂等；detail-tracking
    详情页收藏入口+埋点 hook）；pages/favtrack 两页（favorites/footprints，官方色，preview 夹具）；
    app.config 子包注册；mine 页统计两格+「我的收藏」格位接通（统计位维持"—"占位，D3）；服务/门店详情页
    收藏入口（导航右缘官方色 chip）+登录态足迹埋点；favtrack.test.ts 10/10（含白名单门/幂等日志重放/
    排序校验/分组）。登记表 §4.13（含 §4.12 编号占位说明）。
  - 前端验证：tsc 0 错、npm test 全量、build:weapp、package-check（后台执行中/见提交前证据）。
  - 遗留风险见 PR 描述。

