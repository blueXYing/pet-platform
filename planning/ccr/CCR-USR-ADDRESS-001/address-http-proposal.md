# 用户地址簿 HTTP 面与 C 端三页接续方案

日期：2026-10-13。基线：develop `6263f17`（工作树 codex/mod-address-20261013）。状态：候选，随本切片实施；按 WORK_EXECUTION_PROTOCOL §4 属 Contract 增量（新增 59 号契约（原拟 58，因并行收藏/足迹切片 #155 先占 58 而让号） + OpenAPI 同步 + SQL55），本文记录形成依据与裁决点，产品硬规则不新增、不自创。

## 1. 本次审阅的具体内容

32号裁决补充第二节把「地址管理」列入未建七页全做批次。本批交付用户收货/服务地址簿全链：

| 决定 | 推荐方案与影响 |
|---|---|
| A1：地址簿契约 | 新增 59 号契约（原拟 58，因并行收藏/足迹切片 #155 先占 58 而让号） + OpenAPI `/c/addresses` 六路由（列表/详情/新增/全量编辑/软删除/设默认），沿 `pet.auth.c.enabled` 用户域装配（同宠物档案/用户资料，无新部署开关）。 |
| A2：Schema | 新增 SQL55 `user_address`（镜像 `user_pet`：Snowflake 主键、软删除、默认唯一索引），不改 SQL06 冻结基线。 |
| A3：下单链接法 | 订单地址语义侦查结论：OrderCreationService 把 `serviceAddress` 明文做受保护快照（`order_booking_input_snapshot.service_address_ciphertext` + canonical equality token），**纯快照、无地址引用**。故地址簿与下单链接法 = 前端平铺快照：BookingSheet 选地址后把 `region + ' ' + detail` 写入既有 `serviceAddress` 字段，订单契约/幂等链/快照存储零改动（不平行造第二套，也不引入 addressId 引用破坏快照语义）。 |
| A4：C 端三页 | `consumer/pages/address` 列表/新增/编辑三页（129:13141/13533/13731，新增与编辑为列表之上的底部弹层），「我的」地址格位接通，预约半屏接送地址区接入地址簿快选。 |
| A5：微信原生地址能力 | `chooseAddress`（拉取微信收货地址）/`chooseLocation`（地图选点）按设计稿评估：129:13731/13533 表单为纯文本输入（收货人/手机号/所在地区/详细地址），无微信导入按钮、无地图入口——**不接入**，PR 披露。 |

不包括：地址标签、商家/运营侧地址读路由、地图 POI、运营后台地址管理、发版提审材料（32号第三节暂缓）。

## 2. 已核实基线与来源

- **订单地址存储**：`OrderCreationTypes.CreateOrderCommand.serviceAddress`（String）→ `OrderCreationService.prepare`：IN_STORE 禁带；PICKUP_DELIVERY `protectedText(...,65536,true)` 必填 → `protect("SERVICE_ADDRESS",...)` 密文进 `order_booking_input_snapshot`，equality token 进 canonical 幂等参数。无任何地址 ID 字段——引用型接法无落点，强接需改订单契约（超出本切片，且违背「快照不随地址簿删除失效」的既有语义）。
- **BookingSheet 现状**：`consumer/components/booking-sheet` 接送流程 `serviceAddress` 为自由文本 Textarea（必填校验 `bookingFormError`→'address'），23号 X-Request-Id 幂等恢复会还原该值——快选地址只改 Textarea 值，恢复/重试链零影响。
- **用户域先例**：PetService（防枚举 PET_NOT_FOUND、原子默认唯一、软删除、23号五元组幂等、201/200）、CPetController（严格 JSON Request、thin adapter）、`user_pet` 表结构。
- **错误码**：12号 §13.1 USER/PET 族现有 USER_FROZEN/PET_NOT_FOUND；地址新增 ADDRESS_NOT_FOUND/ADDRESS_LIMIT_EXCEEDED 追加同节。
- **设计稿**：129:13141（列表：47 头带+地址卡×2〔默认胶囊/设为默认/编辑/删除〕+「+ 新增地址」底钮）、129:13533/129:13731（列表之上 y=250 h=561 底部弹层：标题+关闭、收货人/手机号/所在地区三输入、详细地址 Textarea、设为默认地址开关行、保存地址钮）。原稿标题残留「编辑资料」（129:13213）为复制残留，按页面语义用「地址管理」；两弹层帧同构（13731 标题同为「新增地址」），编辑帧按语义用「编辑地址」——登记表 §4.14 记偏差。

## 3. 必须保留的不变规则

1. 只动本人地址：非本人/不存在/已删除统一 404 ADDRESS_NOT_FOUND（防枚举，镜像 PET_NOT_FOUND）。
2. 写操作 23号五元组幂等（scope USER_SELF）；同 requestId 异参 409 IDEMPOTENCY_KEY_CONFLICT；POST 201/200 区分。
3. 冻结用户写 403 USER_FROZEN；读不受限。
4. 默认地址唯一（清旧默认同事务）；删除默认后无默认直至再设置。
5. ID String、no-store、严格 JSON（未知字段/显式 null/尾随 token 400）、query 全禁止。
6. biz 不依赖 biz；地址 SQL 全部在本模块 AddressMapper.xml（22号 MyBatis 裁决）。
7. 订单快照语义不变：不向 order 契约/表加 addressId；删除地址簿行不影响存量订单。

## 4. 字段与上限裁决点

- 字段：`contactName`(1..32 码点)、`contactPhone`(大陆手机号 11 位 `1[3-9]` 开头)、`region`(1..64)、`detail`(1..256)、`isDefault`(boolean)。与设计稿表单一一对应，无多余字段。
- **上限 20**：32号裁决未定单用户地址上限；取保守值 20（第 21 条 400 ADDRESS_LIMIT_EXCEEDED）。若产品后续要求更大上限，仅改常量与契约数值。
- 手机号读侧：本人读侧返回明文（用户自录的收货联系电话，宠物档案同口径）；C 端列表页展示层脱敏（设计稿 138****5678）。无跨用户读路径。

## 5. C 端形态

- 列表页：白卡家族（#fff6e5 头带 47、「默认」胶囊、设为默认/编辑/删除行内按钮、底部「+ 新增地址」主钮 #5baae8）；空态/登录态/加载失败态沿页族语言；删除走 Taro.showModal 确认 + 幂等删除槽。
- 新增/编辑：同一表单组件，底部弹层形态（遮罩+圆角面板），五字段 + 设为默认开关；保存走幂等槽（address:create / address:{id}），未确认提交原参数重放。
- BookingSheet：接送地址区新增「地址簿」快选 chips（默认地址带默认标记，失败静默降级为纯手输）；选中即平铺 `region + ' ' + detail` 进 Textarea（仍可手改）；无地址/未登录/preview 通道不渲染 chips，功能零回归。
- 入口：「我的」页地址管理格位（#150 占位）接通 navigateTo 列表页。

## 6. 必须通过的验收

1. HTTP 六路由真实回环（隔离 MySQL+Redis、真实登录链测试替身）：201/200 重放一致、异参 409、跨用户 404、上限 400、默认唯一、冻结 403、严格 JSON 400、匿名 401。
2. 内核 MySQL 测试：CRUD/幂等/上限/默认切换/软删除后防枚举。
3. contract_smoke 新 ADDRESS_OPERATIONS 家族 + 计数；check-persistence-style.py；架构测试。
4. 前端 tsc/build/单测全绿；BookingSheet 既有单测零回归。
5. 不新增部署开关组合（沿 pet.auth.c.enabled）。

## 7. 尚待审阅的边界

仅上述 A1~A5。不重问 32号已批范围；chooseAddress/chooseLocation 接入、地址标签、运营侧地址管理、真机回归留后续批次。
