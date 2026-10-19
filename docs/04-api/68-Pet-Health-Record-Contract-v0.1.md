# 宠物健康记录（疫苗/驱虫）HTTP 契约 v0.1

2026-10-19。来源：**用户 2026-10-19 裁决"全做"**（回补宠物档案-疫苗/驱虫记录添加两帧 96:2580/96:3006，**推翻登记表 §4.11"范围排除"中原"疫苗驱虫记录添加"一项**，本契约即该推翻的登记载体）、PRD 5.1.3 疫苗/驱虫记录字段表、SQL68（`user_pet_health_record` + `user_pet_health_record_audit`）、HTTP10 §3.2 用户域先例、23号公共幂等。本契约只新增 C 端本人宠物健康记录子资源（疫苗/驱虫各一：列表 + 添加）；不新增编辑/删除单条路由（V1 不做）、不改宠物档案既有语义。

## 范围与归属

USER 域拥有健康记录全部读写、幂等、上限、防枚举与审计规则；HTTP 面是薄边界（会话主体解析、严格 JSON、字段校验、错误映射）。健康信息默认仅用户本人可见（PRD 5.1.3 业务规则）：无任何商家/运营/匿名读路由，运营侧查看走既有用户档案只读口径（本契约零改动）。

包括：疫苗记录列表/添加、驱虫记录列表/添加共四路由 + C 端两表单页（96:2580/96:3006）与档案详情页记录分区接线。**不包括**：编辑/删除既有记录（V1 不做，PR 借 59号先例登记保守上限披露）、附件图片（PRD 字段表有，V1 无 C 端宠物记录图片上传管线，偏差登记 96:2580 无上传控件、96:3006 同）、"接种状态"入库字段（设计稿 chips 为读侧日期派生展示态，不入库，见"字段与校验"）、接种/驱虫提醒推送（PRD 交互联动列"到期前推送养护提醒"属通知域，V1 不做）。

## 路由与开关

沿 `pet.auth.c.enabled`（与宠物档案/地址簿同一用户域装配，无新开关；默认 false）。全部路由 USER/MINIAPP、真实 Bearer 会话、`Cache-Control: no-store`、信封 `{success,code,message,data,traceId}`。

```text
GET  /api/v1/c/pets/{petId}/vaccination-records   本人宠物疫苗记录列表（record_date ASC, id ASC；仅 ACTIVE）
POST /api/v1/c/pets/{petId}/vaccination-records   添加疫苗记录；201 首次 / 200 同 requestId 重放
GET  /api/v1/c/pets/{petId}/deworming-records     本人宠物驱虫记录列表（同排序；仅 ACTIVE）
POST /api/v1/c/pets/{petId}/deworming-records     添加驱虫记录；201 首次 / 200 同 requestId 重放
```

写路由 Header `X-Request-Id` 必填（终端 UUID，23号）；GET 不绑定 X-Request-Id；GET query 全禁止；body 未知字段/显式 null/重复键/尾随内容一律 400。

## 字段与校验（PRD 5.1.3 字段表口径）

**疫苗记录**（VaccinationRecordView）：

```text
recordId      Snowflake 十进制 String
recordDate    接种日期，必填，yyyy-MM-dd，不得晚于当天
vaccineName   疫苗名称，必填，1..50 Unicode 码点、非全空白
doseNumber    接种针次，可选，整数 1..20（PRD"展示第几针"）
nextRecordDate 下次接种日期，可选，yyyy-MM-dd，不得早于 recordDate
note          备注，可选，≤200 码点
recordType    恒 "VACCINATION"
```

**驱虫记录**（DewormingRecordView）：

```text
recordId      Snowflake 十进制 String
recordDate    驱虫日期，必填，yyyy-MM-dd，不得晚于当天
dewormingType 驱虫类型，必填，EXTERNAL(体外)/INTERNAL(体内)/INTERNAL_EXTERNAL(内外同驱)（PRD 三枚举；设计稿 96:3006 仅两枚举，按 PRD 全集，PR 登记偏差）
productName   产品名称，可选，≤50 码点
nextRecordDate 下次驱虫日期，可选，yyyy-MM-dd，不得早于 recordDate
note          备注，可选，≤200 码点
recordType    恒 "DEWORMING"
```

**"接种状态"（已接种/待接种/即将到期）不入库**：设计稿 96:2580 表单 chips 与详情行状态为日期派生展示态，读侧只返回原始事实字段（已批用户域口径：事实→展示推导不进契约）；C 端列表行状态胶囊由前端按 `nextRecordDate` 派生（无下次日期=已接种；未来 30 天内=即将到期；已过=待接续），PR 登记该派生规则为展示口径。

## 硬规则

1. **归属与防枚举**：记录必须挂在本人的 ACTIVE 宠物下；宠物不存在/他人/已删除统一 404 `PET_NOT_FOUND`（沿用既有语义，不区分）。
2. **上限（保守披露，59号先例）**：每只宠物每个 record_type 的 ACTIVE 记录至多 **200** 条，超出 400 `PET_HEALTH_RECORD_LIMIT_EXCEEDED`；V1 无删除路由，达上限即不可再添加（PR 披露，后续随编辑/删除切片重估）。
3. **幂等（23号五元组）**：scope `USER_SELF`，键 `pet.health-record.create|USER|<userId>|USER_SELF|<requestId>`（疫苗/驱虫同命名空间、params 含 petId 与全字段，天然互斥）；同 requestId 同参重放返回首回执（201→200）；同 key 异参 409 `IDEMPOTENCY_KEY_CONFLICT`。
4. **写审计**：每次成功添加（含幂等重放不重复记账，首交记账）同事务写 `user_pet_health_record_audit`（动作/对象/请求元数据，**不含健康内容明文**，35号同款风格）；审计插入失败整体回滚。
5. **冻结用户**：写操作 403 `USER_FROZEN`（读不受限）。
6. **软删除位**：本契约无删除路由；`status` 列为后续编辑/删除切片预留，列表恒只出 ACTIVE。
7. **无下游反应**：健康记录不触发事件/outbox/任务（无订阅方）；写路径 = 幂等锁 + 记录插入 + 审计 + 幂等回执同事务。
8. ID String（Snowflake BIGINT）；无金额字段。

## 错误映射

400 `COMMON_INVALID_ARGUMENT`/`PET_HEALTH_RECORD_LIMIT_EXCEEDED`；401 `COMMON_UNAUTHORIZED`；403 `USER_FROZEN`（写）；404 `PET_NOT_FOUND`；409 `IDEMPOTENCY_KEY_CONFLICT`/`COMMON_CONFLICT`；500 `COMMON_INTERNAL_ERROR`；503 `COMMON_DEPENDENCY_UNAVAILABLE`。新增码 `PET_HEALTH_RECORD_LIMIT_EXCEEDED` 登记 12号 §13.1。

## 实现与页面

`PetHealthRecordService`（pet-user-biz，PetService/AddressService 同款幂等框架 + 本域 `PetHealthRecordMapper.xml`，无 JdbcTemplate/注解 SQL/内联 SQL，过 check-persistence-style.py）；`CPetController` 增四路由（pet-boot，CPetController 同款薄边界）。C 端：`consumer/pages/pet-archive` 分包新增 `vaccine-form`/`deworm-form` 两页（96:2580/96:3006 表单形态：名称/类型 INPUT + 日期选择器 + 取消/保存，POST 幂等槽）；档案详情页（`pet-archive/detail`）`pet-add-vaccine`/`pet-add-deworm` 占位按钮接通转表单页，并按 GET 列表渲染既有记录分区（设计稿 78:3076 族已有记录行版式）。

## 验收

真实回环 HTTP（隔离 MySQL 33440/Redis 16379 + 真实微信登录链测试替身）：四路由全通；201/200 重放一致；异参 409；上限第 201 条 400；跨用户 404；冻结 403；严格 JSON 400；审计行同事务落库且重放不重复；内核 MySQL 测试覆盖幂等/上限/归属/审计；前端 tsc/build/单测全绿、package-check 分包清单同步。
