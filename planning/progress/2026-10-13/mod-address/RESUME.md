# RESUME — 地址管理模块（32号裁决第二批③，wt-mod-address）

日期：2026-10-13。分支 `codex/mod-address-20261013`（基线 develop `6263f17`）。
任务：地址 CRUD 后端 + C 端三页 + BookingSheet 接入 + 测试 + PR；登记表小节 **§4.14**。

## 交付状态（第三任 2026-10-13 23:5x）

**已交付：PR #158** https://github.com/blueXYing/pet-platform/pull/158（base develop，commit `506b30c`，45 文件 +3679/-419；未合并——按红线等人工审）。PR 描述含全部方案披露（下单链接法/上限20/chooseAddress 不接入/标题残留/契约让号 58→59/e2e 计数与 #155 互操作/三类遗留风险）。以下为第三任补充的关键事实：

## 第三任进展（2026-10-13 22:2x 接手，交付冲刺）

- 环境核验：JAVA21（`C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`）、MySQL 127.0.0.1:33440 root/空、Redis 127.0.0.1:16379 全部就绪。
- **契约让号 58→59**：发现并行 #155（收藏/足迹，已开 PR）先占 `docs/04-api/58-Favorite-Footprint-Contract-v0.1.md`。本切片全量改 59：
  - 文件改名 `docs/04-api/59-User-Address-Contract-v0.1.md`；
  - 同步替换引用：HTTP10 §3.2.2（标题/Contract59 链接/§下单链接法）、OpenAPI（6×x-contract + 描述）、12号 §13.1（两处 59号）、20号登记表 §4.14、SQL55 注释、e2e/contract_smoke.py、前端/后端全部「契约 58/contract 58」注释、CCR A1（附让号原因）。核验：全仓无残留（仅 store/model.ts 预览地址夹具"段58号"街道号，非契约引用）。
- 让号后复跑门禁：e2e contract_smoke 29/29 OK（两次）；check-persistence-style.py PASS；前端 tsc 0 错 + `npm test` 548/548（评论级改动复跑，build:weapp 沿用第二任绿证据未复跑——纯注释变更）。
- 模块测试（-pl 定向，非全量 reactor；脚本 `D:/Temp/wt-mod-address-tests/run-remaining-tests.sh`，日志 full-run.log）：
  - Phase A `mvn -pl pet-architecture-test -am install -DskipTests`：BUILD SUCCESS（33s，增量）；
  - Phase B pet-user-biz 全模块：**32/32 绿**（UserAddressDomainMySqlTest 4/4 在内）；
  - Phase C pet-boot 全模块：**中止于 48/95 类**（4 类失败均环境性，见下；AfterSale 族单类 3-19 分钟，且并行 aftersale-fund 代理的 pet-boot 套件自 22:25 起共用同一 MySQL 33440，双向拖慢）→ 23:2x 改跑定向（run-targeted.sh / targeted-run.log）；
  - Phase D pet-architecture-test：**31/31 绿**（Rules 7 + Fixture 24）。
- 定向重跑（pet-boot，17 类 = 全部 C 切片 + BookingCreate + GlobalApiHandler + 3 个全量跑失败类）：**76/77 绿**：
  - MerchantStaffAcceptanceHttpTest 2/2、NotificationInboxHttpTest 1/1 复绿（全量跑中的失败为 MySQL "Communications link failure"（5s 中断，Aborted_clients 36），与并行套件争用相关，非代码）；
  - **ScheduleAvailabilityHttpTest 1 失败为已知时段性 flake**：L67 注释（2026-10-12 hotfix）原文记录 "expected 3, got 4" —— now±90min 相对窗口在 Asia/Shanghai 22:30-00:00 跨入 D1（明天）；本切片 diff 零 schedule 改动，两次失败（22:3x/23:3x）均在此窗口。PR 披露。
- PR 计数披露口径：本树 `operations=149`（143 基线 + address 6）；#155 未合（origin/develop 仍 6263f17）。若 #155 先合，后合方需把 `test_current_document_passes` 的 operations 断言按其新增操作数上调——PR 描述明示。

## 接力盘点（2026-10-13 第二任，15:5x）

- 原「下一步1」的两处断言 bug **已在前任手中修好**：L51 已是 `assertFalse(created.isDefault())`、L212 已断 5（含 defaulted 新 UUID 那条）。
- 「下一步2」CAddressHttpTest **已写完**（`backend/pet-boot/src/test/java/com/petplatform/boot/adapter/web/c/CAddressHttpTest.java`，415 行完整版：登录链/CRUD 全链/幂等重放/409/严格 JSON/跨用户 404/上限/匿名 401；fixture 加载 25+06+14+55 四 SQL）。
- **前端（下一步3）完全未动**：`git status frontend-miniapp/` 零改动，无 `src/consumer/address`、无 `pages/address`。
- 登记表 §4.14（下一步4）、全量验证（下一步5）、PR（下一步6）均未做。

## 第二任进展

- 16:0x 域测试跑绿：`UserAddressDomainMySqlTest` 4/4（mvn 系统 3.9.12，非 mvnw——backend 下无 wrapper）。
- 16:1x 修 CAddressHttpTest 一处真实 bug：fixture 库名 `addr58_http_*` 不满足 RedisMiniAuthVolatileStore 的 `auth001c[_A-Za-z0-9-]*:` 前缀正则（构造器硬校验）→ 改 `auth001c_addr58_*`。
- 16:30 HTTP 测试跑绿：`CAddressHttpTest` 2/2（env：AUTH_MYSQL_URL=jdbc:mysql://127.0.0.1:33440/ AUTH_MYSQL_USER=root 空 password AUTH_REDIS_HOST=127.0.0.1 AUTH_REDIS_PORT=16379）。
- **已知偶发**：首轮跑曾 2/2 失败于登录链 500（IllegalStateException，handler 只记类名无消息无堆栈）；同代码重跑即绿。判为 HutoolSnowflakeIdProvider 的 Windows 时钟采样偶发（Observed OS clock rollback/jump 族），CAuthHttpTest 共享同一基础设施、非本切片引入。pet-boot 全模块测试若复现需在 PR 披露。
- 16:3x 前端全部落地：
  - `src/consumer/address/model.ts`（类型/校验 §28 同口径/maskPhone/addressFlat 下单链接法/夹具/PreviewAddressRepository 幂等回执；flipDefault 映射拷贝实现避免只读字段赋值）
  - `src/consumer/address/repository.ts`（Real：list/get/save/set-default/remove；幂等槽 address:create / address:{id} / address-default:{id} / address-delete:{id}；方法 async + 可选 _previewId 沿 pet 页约定）
  - `pages/address/{index,new,edit}` 三页 + 共用 `address-form.tsx`（弹层形态：遮罩+底部面板；标题按语义修正两处残留）+ `app.config.ts` subPackages + navigation/model.ts 加 addressList/New/Edit 页键
  - mine 入口：`MINE_ADDRESS_BOOK_URL` + mi-svc-address 格位接真实 navigateTo
  - BookingSheet：接送地址区快选 chips（真实登录态懒加载每 revision 一次、失败静默降级、preview/未登录不渲染、点击平铺进 serviceAddress；幂等恢复优先由 pending 载荷还原）
- 16:4x 前端门禁全绿：typecheck 0 错、`npm test` 548/548（含 booking.test.ts 零回归）、`npm run build:weapp` exit 0（仅 webpack 性能告警）。
- 16:4x `address.test.ts` 14/14（含 ConsumerApi 跨重启同 X-Request-Id 重放、严格解码拒绝、六路由方法/路径/载荷）。
- 16:5x 登记表 §4.14 写入（帧号/落地/五条偏差含 chooseAddress·chooseLocation 不接入/范围排除）；渲染核对 1 次 REST（129:13141/13533/13731 scale=2，存 D:/Temp/figma-mcp/addr-render-url-*.png）。
- 16:5x 持久层检查 PASS、e2e contract_smoke 29/29 OK。
- 进行中：pet-architecture-test + pet-user-biz 全模块、pet-boot 全模块（均定向 -pl 单模块，非全量 reactor）。

## 已完成（未提交，全在工作树）

### 契约/文档（已完成）
- `docs/04-api/58-User-Address-Contract-v0.1.md`（编号契约：六路由/上限20/默认唯一/防枚举/幂等）
- `docs/04-api/10-HTTP-API-Contract-v0.4.md` §3.2.2（登记新节）
- `docs/04-api/11-OpenAPI-Core-v0.4.yaml`（`/c/addresses` 三路径六操作 + AddressUpsert/Data/Envelope/ListEnvelope/DeleteEnvelope/ErrorEnvelope + AddressId 参数 + AddressHttpError 响应 + C-Address tag）
- `docs/04-api/12-Error-Code-Registry-v0.5.md` §13.1 追加 ADDRESS_NOT_FOUND(404)/ADDRESS_LIMIT_EXCEEDED(400)
- `docs/03-database/55-User-Address-Schema-v0.1.sql`（user_address 表，镜像 user_pet）
- `planning/ccr/CCR-USR-ADDRESS-001/address-http-proposal.md`（含下单链接法裁决：订单地址=加密文本快照，地址簿只做前端平铺源，订单契约零改动）
- `e2e/contract_smoke.py`：ADDRESS_OPERATIONS 六操作家族 + check_address_operations + union + addressOperations 计数
- `e2e/test_contract_smoke.py`：test_current_document_passes 加 addressOperations=6/operations=149 断言 + 新回归测试 test_address_family_regression_rejected → **29/29 通过**（`cd e2e && python -m unittest test_contract_smoke`）

### 后端（已完成、编译通过）
- pet-user-api：AddressCommands/AddressCommandApi/AddressView/AddressQueryApi
- pet-user-biz：AddressService（PetService 同款幂等框架，scope USER_SELF；上限20；防枚举 ADDRESS_NOT_FOUND；冻结 USER_FROZEN）+ AddressStore（含共享 user_account guard 读）+ AddressEntity + AddressMapper(.xml，SQL 全在 XML)
- pet-boot：CAddressController（GET 列表/GET 详情/POST 201-200/PUT/DELETE/POST set-default 无业务体）；CAuthConfiguration 加 addressService bean；CSessionSecurityConfiguration 加 permitAll 六路由（沿 pet.auth.c.enabled，无新开关）；CBearerSessionFilter PROTECTED_EXACT+前缀；CServiceExceptionHandler assignableTypes+两错误码映射
- `mvn -pl pet-user-api,pet-user-biz,pet-boot -am compile` 通过
- 测试库 fixture：MySqlUserDomainTestDatabase 追加加载 SQL55

### 设计稿（已完成提取）
- `D:/Temp/figma-mcp/addr-{list,edit,new}.xml`（129:13141/13533/13731 树）。要点：
  - 列表：47 头带（原稿标题残留「编辑资料」→用「地址管理」，记偏差）+ 地址卡（姓名+脱敏手机号+默认胶囊/地址两行/设为默认·编辑·删除行内钮）+ 底部「+ 新增地址」钮；卡区 x=13 w=375、内卡 16 边距
  - 新增/编辑：列表之上 y=250 h=561 底部弹层（标题+×、收货人/手机号/所在地区 INPUT h42、详细地址 TEXTAREA h82、设为默认地址开关行 h44（开关 44x24 右置）、保存地址钮 h44）；两帧同构、编辑帧标题残留「新增地址」→按语义用「编辑地址」
  - 官方色值 #5baae8/#c0ecff/#f0fbff；底部 tab 原稿有但工程为 navigateTo 子页不带

## 下一步（按序）

1. **修 UserAddressDomainMySqlTest 两个断言 bug 后重跑**（`mvn -pl pet-user-biz -am -Dtest=UserAddressDomainMySqlTest -Dsurefire.failIfNoSpecifiedTests=false test`，env USR001_MYSQL_URL=jdbc:mysql://127.0.0.1:33440/ USR001_MYSQL_USER=root USR001_MYSQL_PASSWORD=空）：
   - L51：`assertEquals(1, created.isDefault() ? 1 : 0)` —— created 以 isDefault=false 创建，应断 0（删掉这句或改 assertFalse）
   - L211：幂等计数断 4 实为 5 —— 测试中 defaulted 更新用了新 UUID（多一条 SUCCEEDED），改断 5
   （invalidFields 与 capLimit 两用例已过；内核逻辑本身无问题）
2. **pet-boot HTTP 验收测试**：仿 CAuthHttpTest（HttpFixture 内嵌同文件，需 AUTH_MYSQL_URL/AUTH_REDIS_HOST/AUTH_REDIS_PORT env；加载 06+14+**25+55** 四个 SQL；FixedWechatProvider 测试替身 ok:/phone: 前缀码）。建 `CAddressHttpTest` 覆盖：登录链→建 201→同 key 重放 200→异参 409→列表/详情→PUT→set-default→DELETE→跨用户 404→上限 400→匿名 401→未知字段 400。注意 fixture 建库脚本里要补 55-User-Address-Schema。
3. **前端**（frontend-miniapp，npm ci 已装好）：
   - `src/consumer/address/model.ts`（类型/校验/脱敏 maskPhone/平铺 addressFlat=region+' '+detail/preview 夹具）+ `repository.ts`（Real：api.request/api.write 槽位 address:create / address:{id} / address-default:{id} / address-delete:{id}；decode 仿 decodePet）
   - `src/consumer/pages/address/{index,new,edit}` 三页（index.config.ts 等；新增/编辑共用一个表单组件，弹层形态）+ app.config.ts subPackages 加 `{ root: 'consumer/pages/address', pages: ['index','new','edit'] }`
   - mine/index.tsx 地址管理格位（mi-svc-address）接 navigateTo `/consumer/pages/address/index`
   - BookingSheet：pickupFlow 地址区加地址簿快选 chips（real 模式懒加载、失败静默降级；选中平铺进 serviceAddress Textarea；preview/未登录不渲染）——幂等恢复优先（draft.serviceAddress 先恢复）
   - 单测 `src/consumer/tests/address.test.ts`（仿 booking.test.ts：model 校验/decode/preview 仓库）
4. **登记表 §4.14**：`docs/08-engineering/20-设计源登记表-figma-map.md` 末尾（§4.11 之后、§5 之前）加「### 4.14 C 端地址管理三页（2026-10-13）」：帧号、实现口径、偏差（标题残留「编辑资料」/编辑帧标题「新增地址」/tab 栏不带/chooseAddress·chooseLocation 不接入）
5. **全量验证**：check-persistence-style.py、pet-architecture-test、pet-user-biz 全模块测试、pet-boot 定向（CAuthHttpTest 等不回归）、e2e smoke、前端 `npm run typecheck && npm test && npm run build:weapp`（用 `python backend/tools/run-frontend-gate.py miniapp-build` 也行）
6. **PR**：推分支、gh pr create --base develop；PR 描述含方案披露（下单链接法/上限20/chooseAddress 不做/标题残留偏差）

## 坑

- Windows Git Bash：mvn 需显式 JAVA_HOME=C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot（系统默认 JDK17）
- e2e 测试须在 `e2e/` 目录内跑（import contract_smoke 同目录）
- 禁止全量 reactor；定向 `-pl ... -am`
- BookingSheet 是共享组件（create 直连页/store-services 两宿主，idPrefix bkg/bks）——改动保持零回归，booking.test.ts 必须仍绿
- WXSS 铁律：按钮重置不用 margin !important、无 button 嵌套、状态仅 className 变体、禁 data-*
- 设计稿三帧 XML 已提取在 D:/Temp/figma-mcp/addr-*.xml（零 API 配额）；若需渲染图用 REST dev token（~/.zcode/figma-token-dev.txt，X-Figma-Token，key bp2vpcjjA5vZbHvtKkA8wl）——目前不需要
- CAddressController 的 set-default 无业务体（POST 无 body 也无 @RequestBody）
