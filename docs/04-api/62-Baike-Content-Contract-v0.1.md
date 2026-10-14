# 宠物百科内容读侧 HTTP 契约 v0.1

2026-10-14。来源：C 端 PRD 5.1.4《宠物百科》（`docs/01-prd/02-PRD-C端用户-V1.0-最终基线.docx`）、SSOT 权限组 21「百科/品种」（`encyclopedia.read`/`breed.read`）、HTTP10 §3.2 用户域与门店目录读侧先例（STR-D8 匿名浏览）、SQL06 表风格。用户已裁决：**本切片做百科、不做社区（COM-001 零代码）**；运营端百科治理（A-005）不在本切片。

## 范围与归属

百科内容读侧归属**内容域 pet-community-api/biz**（`baike` 子域）。依据：①Maven 骨架（架构 17 号）领域清单中 `community` 即内容/社区域，pet-community-api/biz 模块已存在且已接入 pet-boot，此前为零骨架（无任何 COM-001 社区代码），百科读侧落入不触碰、不实现任何社区功能；②SSOT 权限组 21「百科/品种」与社区权限同属运营内容治理族；③PRD 将「宠物百科」与「社区动态」同列于 C 端内容能力。本切片不新增产品规则，不改任何既有域契约。

包括：启用分类字典读、品种内容分页列表（分类切换 + 关键词搜索 + 标签筛选）、品种详情（含同分类相关推荐）、62 号 Schema（`encyclopedia_category`/`encyclopedia_breed` + 种子数据）+ C 端列表/详情两页与「我的」页入口。不包括：社区动态/关注/点赞（COM-001 一行不做）、运营编辑/发布/下线写侧与权限面（A-005）、浏览计数等任何写入（无写路由 → 无幂等/Outbox 需求）、评论、收藏百科、品种库与宠物档案字段的联动写回（宠物档案品种仍为自由文本，PRD「联动」以 C 端入口互跳承载，见 §7 偏差）。

## 路由与开关

新装配开关 `pet.encyclopedia.query.enabled`（默认 false，与 `pet.store.query.enabled` 同款模块级装配；C 端安全链仍整体骑 `pet.auth.c.enabled`）。全部路由 GET-only、**匿名可浏览**（PRD「所有用户；未登录用户可浏览」，STR-D8 同款：无 Authorization 头匿名通过；携带无效/过期 Bearer 仍 401，匿名不掩盖坏凭据）；`Cache-Control: no-store`；信封 `{success,code,message,data,traceId}`；GET 不绑定 `X-Request-Id`。

```text
GET /api/v1/c/encyclopedia/categories            启用分类字典（sort_no ASC,id ASC）
GET /api/v1/c/encyclopedia/breeds                品种内容分页列表（仅已发布未下线）
      ?categoryCode=DOG&keyword=&tag=&page=1&pageSize=20
GET /api/v1/c/encyclopedia/breeds/{breedId}      品种详情（仅已发布未下线；含 related 相关推荐）
```

未知 query 参数整单 400（同 CStoreController 严格口径）；`page` 缺省 1、`pageSize` 缺省 20（10..50，与门店目录同界）。

## 字段与校验

分类字典 CategoryView：`categoryCode`（`DOG|CAT|REPTILE|BIRD|INSECT`，PRD 5.1.4 枚举 狗狗/猫咪/爬宠/鸟类/昆虫）、`categoryName`、`sortOrder`（wire 字段避开 *No 后缀的 Snowflake-String 保留规则；库列仍为 sort_no）。仅返回启用行（`status='ENABLED'`）。

列表卡 BreedCardView：`breedId`（Snowflake 十进制 String）、`categoryCode`、`categoryName`、`name`（品种名，同分类内唯一）、`aliases[]`（别名标签数组，参与搜索匹配）、`tags[]`（品种标签，≤10，用于筛选）、`summary`（列表卡简介纯文本，≤120 字）、`coverUrl`（封面图 URL）。

详情 BreedDetailView：列表卡全字段 + `bodyType`（体型签，如 小型/中型/大型）、`facts[]`（事实卡数组 `{label,value}`，种子为 原产地/平均寿命/参考体重 三卡）、`radar`（适养能力图谱五维 1..5：`companion` 陪伴需求/`shedding` 掉毛程度/`intelligence` 聪明度/`easyCare` 易养度/`exercise` 运动需求）、`descriptionHtml`（品种描述富文本，PRD ≤3000 字，安全白名单 HTML）、`careAdviceHtml`（适养建议富文本，≤2000，可空）、`healthTipsHtml`（健康提示富文本，≤2000，可空）、`status`（读侧恒 `PUBLISHED`）、`related[]`（同分类已发布品种，排除自身，`sort_no ASC,id ASC` 前 3，BreedCardView 结构）。

查询语义：
- `categoryCode` 合法性按启用字典校验，未知/已停用 → 400 `COMMON_INVALID_ARGUMENT`；缺省默认 `DOG`（PRD 默认值 狗狗）。
- `keyword`（≤40 码点，首尾空格去除）对 `name`/`aliases` 做不区分大小写包含匹配（PRD：别名参与搜索匹配）。
- `tag` 对 `tags` 做精确匹配（PRD：品种标签用于筛选）。
- 列表/详情只返回 `status='PUBLISHED'` 行；下线行在列表消失、详情 404（PRD：前端只显示运营发布且未下线内容）。
- 排序：`sort_no ASC, id ASC`（种子按设计稿陈列序）。

## 硬规则

1. **只读切片**：无写路由、无幂等键、无 Outbox/AsyncTask；内容生产在运营侧（A-005 另轮），读侧对内容只做已发布过滤。
2. **健康内容免责声明**：详情页必须展示科普免责声明（PRD：「医疗和健康内容仅用于科普，不替代兽医诊断」）——由 C 端详情页固定渲染免责声明条，后端数据不承载声明文案。
3. **防枚举**：不存在/未发布/已下线详情统一 404 `BREED_NOT_FOUND`。
4. ID String（Snowflake BIGINT，HTTP/JSON 十进制 String）；无金额字段。
5. 分类枚举五值为 PRD 原文，不新增、不改名（设计稿分类命名与 PRD 不一致，见 §7）。

## 错误映射

控制器本地映射，未识别一律 500 不泄内核：400 `COMMON_INVALID_ARGUMENT`（非法 categoryCode/超限 keyword/非法分页/未知参数）；401 `COMMON_UNAUTHORIZED`（携带无效 Bearer）；404 `BREED_NOT_FOUND`；500 `COMMON_INTERNAL_ERROR`。新增码 `BREED_NOT_FOUND` 登记 12号 §13.1。

## 实现与页面

`EncyclopediaContentService`（pet-community-biz `baike` 子域，本域 `CommunityMybatis` + `EncyclopediaQueryMapper.xml`，无 JdbcTemplate/注解 SQL/内联 SQL，过 check-persistence-style.py）；`CEncyclopediaController`（pet-boot，CStoreController 同款薄边界，匿名 GET）。C 端 `consumer/pages/baike` 两页（列表 245:5375、详情 253:953，登记表 §4.16）+「我的」页「我的服务」格位真实入口；宠物档案详情页品种行「查看百科」按关键词互跳（PRD 5.1.4 核心功能 4）。

## Schema 与种子（SQL 62，`docs/03-database/62-Baike-Content-Schema-v0.1.sql`）

`encyclopedia_category`（code 唯一、ENABLED/DISABLED、sort_no）与 `encyclopedia_breed`（Snowflake 主键、category_id、name/aliases JSON/tags JSON/summary/cover_url/body_type/facts JSON/radar JSON/description_html/care_advice_html/health_tips_html/status PUBLISHED|OFFLINE/sort_no/version/审计列；`uk_breed_category_name` 同分类同名唯一）。种子数据为**正式 V1 内容**：五分类全启用；狗狗 8 品种按设计稿 245:5375 陈列序逐字采用（贵宾犬/金毛寻回犬/比熊犬/威尔士柯基犬/拉布拉多寻回犬/博美犬/柴犬/法国斗牛犬及其别名/标签/简介文案），其余四分类按常识性科普内容补齐；富文本仅用白名单标签 `h3/h4/p/ul/li/strong`。封面图 URL 为内容治理占位地址，C 端做占位回退（见 §7）。

## 验收

真实回环 HTTP（隔离 MySQL）：分类字典/分页列表/搜索命中别名/标签筛选/翻页界/下线 404/详情全字段/related 排除自身；匿名 200、坏 Bearer 401、未知参数 400；内核 MySQL 测试覆盖种子可见性与过滤语义；前端 tsc/单测/build 全绿；check-persistence-style.py 通过。

## 偏差与披露（登记表 §4.16 同步）

1. **设计稿导航标题残留**：原稿两页标题「宠物信息」为设计残留（§4.12 偏差①同口径），按语义落「品种百科」。
2. **分类 tab 不一致**：原稿 4 枚（汪星人/喵星人/异宠/植物）与 PRD 枚举 5 枚（狗狗/猫咪/爬宠/鸟类/昆虫）冲突，**按 PRD 原文**渲染 5 枚，视觉沿原稿 tab 样式。
3. **榜单胶囊**：原稿胶囊文案为占位（三条重复「新手友好榜」），按 PRD「品种标签用于筛选」落地为标签筛选胶囊，取种子标签字典。
4. **相关推荐**：PRD 核心功能叙述有、设计帧无该区块（设计缺稿），沿现行白卡家族补最小横滑实现，数据为同分类前 3。
5. **常见问题**：PRD 叙述提及「常见问题」，但字段级详情表未定义 FAQ 字段、设计帧亦无该区块——不新增 FAQ 字段，由「健康与护理（常见健康问题）」内容承载；若后续需要走 CCR。
6. **封面图素材缺稿**：原稿为照片位，V1 种子封面为占位 URL，C 端 onError 回退「分类色渐底 + 品种名首字」占位块；正式切图待设计补稿/运营上传（A-005）。
7. **宠物档案联动**：PRD「从宠物档案进入对应品种的养护指南」以档案详情页品种行的百科搜索互跳承载（keyword=品种名），不做品种库外键联动（宠物档案品种仍为自由文本，契约零改动）。
