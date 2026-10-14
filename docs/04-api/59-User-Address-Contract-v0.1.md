# 用户地址簿 HTTP 契约 v0.1

2026-10-13；候选随切片实施（[提案](../../planning/ccr/CCR-USR-ADDRESS-001/address-http-proposal.md)）。来源：32号裁决补充第二节（地址管理页全做）、SSOT §5（用户资料）、HTTP10 §3.2 用户域先例（宠物档案/用户资料）、SQL55（`user_address`）、23号公共幂等。本契约只新增 C 端用户地址簿读写；不新增订单/售后/商家产品规则，不改订单地址快照语义。

## 范围与归属

USER 域拥有地址簿全部读写、幂等、上限、默认唯一与防枚举规则；HTTP 面是薄边界（会话主体解析、严格 JSON、字段校验、错误映射），不做跨域判断。订单不引用地址簿行：下单地址仍走既有 `order_booking_input_snapshot` 文本快照（`service_address_ciphertext`，OrderCreationService 原样不动），地址簿只是 C 端录入源——选择地址 = 前端把 `region + 空格 + detail` 平铺进既有 `serviceAddress` 字段提交，订单契约零变更。

包括：列表、详情、新增、全量编辑、删除（软）、设默认六路由 + C 端三页（列表/新增/编辑）。不包括：地址标签（家/公司）、微信 `chooseAddress`/`chooseLocation` 导入（设计稿表单为纯文本输入，未接入，PR 披露）、地图 POI、商家/运营侧任何地址读路由（商家只看订单快照）、`user_address` 与订单的引用关系。

## 路由与开关

沿 `pet.auth.c.enabled`（与宠物档案/用户资料同一用户域装配，无新开关；默认 false）。全部路由 USER/MINIAPP、真实 Bearer 会话、`Cache-Control: no-store`、信封 `{success,code,message,data,traceId}`。

```text
GET    /api/v1/c/addresses                     本人地址列表（默认在前，其余 created_at ASC,id ASC；不 hiding 任何状态差异，仅 ACTIVE）
GET    /api/v1/c/addresses/{addressId}          单条详情；非本人/不存在/已删除统一 404 ADDRESS_NOT_FOUND 防枚举
POST   /api/v1/c/addresses                      新增；201 首次 / 200 同 requestId 重放
PUT    /api/v1/c/addresses/{addressId}          全量替换（contactName/contactPhone/region/detail/isDefault）
DELETE /api/v1/c/addresses/{addressId}          软删除（status=DELETED）；回执 {addressId,status}
POST   /api/v1/c/addresses/{addressId}/set-default  设默认；回执同详情投影
```

写路由 Header `X-Request-Id` 必填（终端 UUID，23号）；GET 不绑定 X-Request-Id；query 全禁止。

## 字段与校验

AddressView：`addressId`（Snowflake 十进制 String）、`contactName`（收货人，1..32 Unicode 码点、非全空白）、`contactPhone`（大陆手机号 `1[3-9]` 开头 11 位数字）、`region`（所在地区，1..64 码点、非全空白）、`detail`（详细地址，1..256 码点、非全空白）、`isDefault`（boolean）、`status`（`ACTIVE|DELETED`）。PUT 为全量替换，五字段必填（isDefault 可省，缺省保持现值）；petType 式不可变字段无。

读侧返回明文 `contactPhone`：这是用户自己录入的收货联系电话，本人读侧与宠物档案同口径（C 端列表页按设计稿 `138****5678` 形态做展示层脱敏，编辑表单回填明文）；跨用户读不到（防枚举 404）。

## 硬规则

1. **单用户上限**：ACTIVE 地址至多 **20** 条（32号裁决未定上限，本批取保守值，超出 400 `ADDRESS_LIMIT_EXCEEDED`，PR 披露）。
2. **默认地址唯一**：每用户至多一条 `isDefault=1`；新增/编辑/设默认置默认与清旧默认同事务；删除默认地址后无默认直至再设置（镜像宠物档案 §3.2 口径）。
3. **防越权/防枚举**：所有读改删仅作用本人地址；非本人/不存在/已删除统一 404 `ADDRESS_NOT_FOUND`（不区分）。
4. **幂等（23号五元组）**：scope `USER_SELF`，键 `address.create|USER|<userId>|USER_SELF|<requestId>`（update/delete/set-default 同构）；同 requestId 同参重放返回首回执；同 key 异参 409 `IDEMPOTENCY_KEY_CONFLICT`。
5. **冻结用户**：写操作 403 `USER_FROZEN`（读不受限）。
6. **软删除**：DELETE 置 `status=DELETED`，不物理删除；订单快照不依赖本表，删除不影响存量订单。
7. ID String（Snowflake BIGINT，HTTP/JSON 十进制 String）；无金额字段。

## 错误映射

控制器本地映射，未识别一律 500 不泄内核：400 `COMMON_INVALID_ARGUMENT`/`ADDRESS_LIMIT_EXCEEDED`；401 `COMMON_UNAUTHORIZED`；403 `USER_FROZEN`（写）；404 `ADDRESS_NOT_FOUND`；409 `IDEMPOTENCY_KEY_CONFLICT`/`COMMON_CONFLICT`；500 `COMMON_INTERNAL_ERROR`；503 `COMMON_DEPENDENCY_UNAVAILABLE`。新增码 `ADDRESS_NOT_FOUND`、`ADDRESS_LIMIT_EXCEEDED` 登记 12号 §13.1。

## 实现与页面

`AddressService`（pet-user-biz，PetService 同款幂等框架 + 本域 `AddressMapper.xml`，无 JdbcTemplate/注解 SQL/内联 SQL，过 check-persistence-style.py）；`CAddressController`（pet-boot，CPetController 同款薄边界）。C 端 `consumer/pages/address` 三页（列表 129:13141、编辑 129:13533、新增 129:13731，新增/编辑为列表之上的底部弹层形态，设计登记表 §4.14）；「我的」地址格位接通；预约半屏 BookingSheet 接送地址区接入地址簿快选（平铺 `serviceAddress`，契约与幂等链零改动）。

## 验收

真实回环 HTTP（隔离 MySQL/Redis + 真实微信登录链测试替身）：六路由全通；201/200 重放一致；异参 409；上限第 21 条 400；默认唯一与删除默认后无默认；跨用户 404；冻结 403；严格 JSON（未知字段/显式 null/尾随 token 400）；内核 MySQL 测试覆盖幂等/上限/默认/软删除；前端 tsc/build/单测全绿。
