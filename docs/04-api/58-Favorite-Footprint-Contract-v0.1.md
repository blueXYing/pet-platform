# 用户收藏与浏览足迹契约 v0.1

2026-10-13；候选随切片实施（[CCR-C-FAVTRACK-001](../../planning/ccr/CCR-C-FAVTRACK-001.md)）。来源：SSOT、32号裁决补充第二节（未建七页批次：收藏、浏览足迹）、10号 HTTP 契约 C 端家族、23号公共幂等补充 §5、SQL06 用户节。本契约把 32 号裁决批准的收藏/足迹两页装配为默认关闭的 C 端用户私有读写切片；不新增交易产品规则，不扩大 V1 范围（无社区动态收藏、无人物收藏）。

## 范围与归属

USER 拥有收藏关系与浏览足迹的全部写入、幂等、去重与读侧规则；本 HTTP 面是薄边界（MINIAPP 会话解析、参数校验、查询串拒绝、错误映射），不做业务判断。目标准入与列表展示投影经既有冻结 C 目录 *-api（`ServiceQueryApi.getVisibleService`、`MerchantStoreDisplayApi.getDisplayStore`）；user-biz 不依赖任何其他 biz、不跨模块访问持久层（架构规则不变）。

包括：收藏加/取消/列表/状态探针四路由 + 足迹上报/列表/单条删除三路由 + C 端两列表页与详情页入口的服务。不包括：社区动态（宠友圈内容）收藏/足迹、服务人员（staff）收藏、足迹自动清理任务、运营侧查询、批量操作、生产启用。

## 足迹口径（侵入最小方案，CCR 披露）

浏览足迹采用**客户端埋点**方案：C 端小程序在登录态浏览服务/门店详情页加载成功后，调用一次 `POST /footprints`（无业务体）；匿名目录读链（10号 §3.1/§3.2 匿名浏览裁决 STR-D8）完全不动。备选方案（服务端在读链里顺带写入）被否决：读路径引入写事务与幂等键，违反侵入最小原则。披露限制：匿名浏览不产生足迹（登录后才记录）；同一目标重复浏览刷新 `last_viewed_at` 置顶，不新增行。

## 路由与开关

`pet.favtrack.http.enabled`，默认 false；[硬] 前置 `pet.auth.c.enabled`（MINIAPP 会话）+ `pet.service.query.enabled` + `pet.store.query.enabled`（目标准入与展示投影经两目录切片；缺 bean 即装配失败关闭）。全部路由 USER/MINIAPP、真实 bearer 会话（本家族无匿名路由）、`Cache-Control: no-store`、信封 `{success,code,message,data,traceId}`、ID String、时间 ISO-8601 UTC 毫秒。targetType 恰为 `SERVICE|STORE`（其他一律 400）；targetId 正整数 BIGINT String。

- `POST /api/v1/c/favorites/{targetType}/{targetId}`：加收藏，无业务体；Header `X-Request-Id` 必填（终态 UUID）。首建 **201**，requestId 重放与业务重复（该目标已收藏）**200** 同首建/当前行回执。
- `DELETE /api/v1/c/favorites/{targetType}/{targetId}`：取消收藏=当前关系行硬删除；未收藏仍 200（`removed=false`）。Header `X-Request-Id` 必填。
- `GET /api/v1/c/favorites?targetType&page&pageSize`：本人收藏列表。targetType 可选（缺省 SERVICE+STORE 全量）；page 1..10000 默认 1；pageSize 1..50 默认 20；固定排序 created_at DESC, id DESC；PageResult 信封 items/page/pageSize/total。未知/重复查询参数 400。GET 不绑 X-Request-Id。
- `GET /api/v1/c/favorites/{targetType}/{targetId}`：收藏状态探针；未收藏 200 `favoriteId=null`（状态探针，绝不 404）；仅查当前会话主体（防枚举：他人收藏不可见）。
- `POST /api/v1/c/footprints/{targetType}/{targetId}`：上报一次浏览（埋点），无业务体；Header `X-Request-Id` 必填。首见 **201**，重放与重复浏览 **200**。
- `GET /api/v1/c/footprints?page&pageSize`：本人足迹列表；固定排序 last_viewed_at DESC, id DESC；分页同上；无 targetType 过滤（V1 足迹只有两类型，客户端分组自行投影）。
- `DELETE /api/v1/c/footprints/{footprintId}`：删单条足迹；不存在仍 200（`deleted=false`）。Header `X-Request-Id` 必填。

## 写语义与幂等（内核）

- 收藏加：目标准入（可见性经目录 *-api，隐藏/缺失统一 404 目标家族码，防枚举）→ 23号 §5 帧执行（admit→lock→execute→succeed，scope=`favorite.add`，五元组绑定 requestId）：行锁下先查 (user,target)，已存在即塌缩到既有行（`created=false`，200，**防重复**不产生第二行）；否则 Snowflake 新行插入。同 requestId 同参重放回首次回执；同 requestId 异参 `IDEMPOTENCY_KEY_CONFLICT`。
- 收藏取消：硬删当前关系行；缺席成功。再次收藏生成新行（新 created_at 排序位置），不保留历史。
- 足迹上报：目标准入同上 → 帧执行（scope=`footprint.record`）：行锁下 upsert，(user,target) 唯一键，已存在即刷新 `last_viewed_at`（置顶），否则新行。
- 足迹删除：按 footprintId 且本人 user_id 过滤硬删；不存在/非本人 200 `deleted=false`（非本人行不泄漏存在性）。
- 全部写命令前置 `user_account.status=ACTIVE` 校验（非 ACTIVE 403 `USER_FROZEN`）。

## 回执与展示投影

- 加收藏回执：`favoriteId/targetType/targetId/favoritedAt/favorited/created`。
- 取消回执：`targetType/targetId/removed`。足迹上报回执：`footprintId/targetType/targetId/lastViewedAt/created`。删除回执：`footprintId/deleted`。
- 列表行（两家族同形）：`favoriteId|footprintId`、`targetType`、`targetId`、`favoritedAt|lastViewedAt`，加目录展示投影 `targetAvailable/title/categoryLabel/salePrice/storeId/coverUrl`（SERVICE 行带 storeId/coverUrl/salePrice 两位小数字符串；STORE 行仅 title=门店名，其余 null）。目标当前隐藏/下架时行保留、`targetAvailable=false` 且展示列 null（取消/删除仍可用）；目录 facts 故障整页 503 fail-closed。

## Schema

SQL06 用户节新增两表（本切片唯一 Schema 变化，无 Flyway 迁移、无 Event、无任务变更）：
`user_favorite`（唯一键 user_id+target_type+target_id；取消即删）、`user_browse_footprint`（同唯一键去重 + last_viewed_at 刷新）。SQL 全部在本模块 MyBatis XML（FavoriteMapper.xml/FootprintMapper.xml，含 user_account 守卫读；过 check-persistence-style.py）。

## 错误映射

400 `COMMON_INVALID_ARGUMENT`（targetType 非 SERVICE/STORE、targetId 非正整数、分页越界、未知/重复查询参数、写路由带查询串、缺失/非 UUID X-Request-Id）；401 `COMMON_UNAUTHORIZED`（无/失效会话）；403 `COMMON_FORBIDDEN`/`USER_FROZEN`；404 `SERVICE_NOT_FOUND`/`STORE_NOT_FOUND`（目标准入，与目录家族同码防枚举）；409 `IDEMPOTENCY_KEY_CONFLICT`；503 `COMMON_DEPENDENCY_UNAVAILABLE`（目录 facts 故障）。未识别一律 500 不泄内核。

## C 端页面

C 端子包新增收藏列表/足迹列表两页（入口接「我的」统计条收藏/足迹格与"我的收藏"格位；设计源 128:3200/129:3484，20号登记表 §4.13 登记偏差：原稿人员卡/动态行不在 V1 目标范围）。"详情"不新造页：列表行落既有服务详情/门店详情页。服务/门店详情页加收藏入口（心形钮，登录门槛+状态探针）与登录态足迹埋点；足迹详情页设计稿（690:11109）即门店详情形态，直接复用。

## 交付与限制

全部增量默认关闭（开关见 21号清单）。验收以真实回环 HTTP（隔离 MySQL、SQL06+SQL14）覆盖：首建/重放/异参冲突/业务重复塌缩、取消与删除幂等、足迹去重置顶排序、会话/冻结/目标准入/防枚举、严格查询参数、默认关装配、前端 tsc/单测/build 与 contract_smoke census。遗留：生产启用开关待部署门；足迹自动清理（如 90 天）无产品裁决，V1 不做。
