# CCR-C-FAVTRACK-001：C 端"收藏/浏览足迹"读写契约提案（已批准）

状态：**APPROVED_FOR_SLICE（2026-10-13，32号裁决补充第二节批准七页全做，含收藏与浏览足迹两页；本 CCR 定稿契约细则）**。批准范围：收藏加/取消/列表/状态 + 足迹上报/列表/删除七个 C 端路由（USER/MINIAPP 会话私有）与两列表页；权威契约落 [58-Favorite-Footprint-Contract-v0.1.md](../../docs/04-api/58-Favorite-Footprint-Contract-v0.1.md)（编号 58，10号 §3.16 登记）。2026-10-13；提出方/唯一 Writer：mod-favtrack 切片（32号裁决第二节批次）。本提案不新增产品规则；不实现 V1 范围外能力（无社区动态收藏、无人物/员工收藏、无积分消费）。

## 一页结论

develop（6263f17）上收藏/足迹没有任何工程事实：`pet-user-api` 无收藏/足迹表面；`pet-boot/adapter/web/c` 无对应控制器；SQL06 无对应表；C 端「我的」页收藏/足迹格位是纯视觉占位（20号 §4.6 登记无工程页面）。32号裁决第二节把两页纳入实施，契约缺失按根 AGENTS.md 走本 CCR，并已定稿为 58 号契约。

## 事实基础（本次核实）

- 产品事实：32号裁决第二节——收藏、浏览足迹属"未建七页全做"批次；签到/拉新细则走各自 CCR；写操作 requestId 幂等照旧；持久层遵守 MyBatis 统一裁决。
- 设计事实（用户端 Figma key `bp2vpcjjA5vZbHvtKkA8wl`，本机缓存 `D:/Temp/figma-mcp/favtrack/`）：`128:3200` 我的-我的收藏（人员风格卡：头像/评分/已服务N单/标签/¥xx起/预约服务）、`129:3484` 我的-浏览足迹（今天/昨天/更早分组行：类型chip 服务/商家/动态+时间+标题+副题+删除钮）、`690:11109` 我的-浏览足迹-足迹详情（整页为门店详情形态：门店卡/门店信息/团购套餐/用户评价/拨打电话+立即预约）。
- 语义冲突（登记偏差，不自创产品规则）：V1 C 端可收藏/可浏览的目标只有**服务（service_item）与门店（merchant_store）**两目录（既有冻结契约 10号 §3.1/§3.2）；设计稿的"服务人员卡"（小李/林医生，V1 无服务人员详情页与契约）与"动态"行（宠友圈内容，V1 社区为只读广场、无动态详情契约）不在目标范围内。处置：页面按工程契约口径实现（SERVICE/STORE 两类行），视觉壳（布局/色值 #5baae8/#c0ecff/#f0fbff）按原稿，偏差在 20号登记表 §4.13 登记。

## 已定稿契约（58号，要点）

1. **目标模型**：targetType 恰为 `SERVICE|STORE`；targetId 为正整数 BIGINT String。目标准入经既有 C 目录可见性 API（隐藏/缺失统一 404 目录家族码，防枚举）；列表展示投影同目录（目标下架后行保留、targetAvailable=false、展示列 null，取消/删除仍可用）。
2. **收藏**：POST 加（首建 201/重放与业务重复 200 同行回执，行锁防重复）；DELETE 取消=硬删（缺席成功）；GET 列表（created_at DESC，targetType 可选过滤，page/pageSize 平台界）；GET 状态探针（未收藏 200 favoriteId=null，绝不 404）。
3. **足迹（D1 裁决点，本切片定为客户端埋点）**：登录态详情页加载成功后 C 端 POST 一次上报（无业务体）；(user,target) 唯一键去重、重复浏览刷新 last_viewed_at 置顶；last_viewed_at DESC 固定排序；DELETE 单条幂等。**备选（服务端在读链顺带写入）否决**：匿名目录读链（STR-D8 裁决可匿名浏览）引入写事务与幂等键，侵入最小原则不取。披露限制：匿名浏览不产生足迹；埋点失败不阻塞详情页渲染。
4. **幂等**：全部写路由 23号 §5 五元组（namespace=USER、scope=USER_SELF）requestId 终态 UUID；同 requestId 异参 409 IDEMPOTENCY_KEY_CONFLICT；写前置 user_account ACTIVE（否则 403 USER_FROZEN）。
5. **Schema**：SQL06 用户节 `user_favorite`（唯一键 user+target，取消即删）+ `user_browse_footprint`（同唯一键+last_viewed_at）两表；无 Event/任务/Flyway 变更。
6. **开关**：`pet.favtrack.http.enabled` 默认 false；[硬] pet.auth.c.enabled + pet.service.query.enabled + pet.store.query.enabled；21号清单登记。

## D 待裁决点处置

- **D1 足迹触发口径**：定为客户端埋点（登录态服务/门店详情页浏览成功后上报），理由与限制如上；如后续产品要求"匿名也记、登录后归并"，需新裁决（本切片不做）。
- **D2 足迹保留期限**：设计稿有"更早"分组但无清理规则；V1 不做自动清理（无产品裁决，58号登记遗留）。
- **D3 收藏数/足迹数统计入「我的」卡片**：设计稿统计条有数字位；58号列表 total 可支撑，但需每次进页额外请求；本切片「我的」格位跳列表页、统计位维持"—"占位（避免为两个数字加两次首屏请求），如需实数回填待后续裁决。
- **D4 设计稿语义冲突**：按"事实基础"节处置，20号登记表 §4.13 登记偏差（人员卡/动态行/足迹详情复用门店详情页）。

## 验收案例

- 正：登录态加收藏 201→同 requestId 重放 200 同回执→异参 409；已收藏目标换 requestId 再加 200 不新增行；取消后列表减一、再取消 200 removed=false；状态探针跨会话只看本人；足迹首见 201、重复浏览 200 刷新置顶、列表按时间分组；删单条幂等；列表行含目录展示投影、下架目标 targetAvailable=false。
- 反：未登录全路由 401（本家族无匿名路由）；targetType=COUPON 400；隐藏服务加收藏 404 SERVICE_NOT_FOUND；非 UUID X-Request-Id 400；写路由带查询串 400；目录 facts 故障整页 503；FROZEN 用户写 403。

影响：HTTP 10号新增 §3.16 与 11号 OpenAPI 七路由（IMPLEMENTED_DEFAULT_OFF）；SQL06 两表；21号开关清单一行；contract_smoke census 并集；C 端子包两页+详情页入口；无内部 API 变更（复用既有 *-api 表面）。

依据：根 AGENTS.md（Contract 缺失走 CCR/写幂等/MyBatis 裁决）；32号裁决补充第二节；SSOT；10号 §3.1/§3.2（目录家族与匿名浏览 STR-D8）；23号 §5；20号登记表 §4.6/§4.13。
