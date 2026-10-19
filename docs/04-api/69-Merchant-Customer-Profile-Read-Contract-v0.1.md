# 商家客户档案读侧契约 v0.1（69号）

状态：ACCEPTED_CONTRACT / IMPLEMENTED_DEFAULT_OFF（`pet.order.customer.http.enabled`，2026-10-19 用户裁决"全做"范围内按最小实现铁律执行）。**2026-10-19 二次扩展（用户裁决"继续扩展CCR"）落地 §6 原登记项 2/4(订单号部分)/5**：订单号尾号搜索（仅本店订单命中）、商家内部备注（单客户一条 ≤200 字，OWNER 写+幂等+版本+审计）、客户详情页订单/评价只读跳转入口（本店整体视图）。来源：M 端 PRD 5.12 客户档案信息 + 审计 C1 #11/#12（帧 23:12738 客户管理 / 23:13478 客户详情）+ 2026-10-19 用户隐私边界裁决。

## 1. 隐私红线与最小实现边界（本契约的先决裁决）

用户 2026-10-19 裁决明确"从本店订单派生的客户列表，只读"为本切片基础交付面；二次扩展仅开放下列**非隐私面**写/搜能力。**基础只读面**：

- 列表/详情数据 100% 从本店（merchantId+storeId 双键）已存在订单事实派生，零新事实源、零爬取、零跨店聚合；
- 手机号仅脱敏形态（`138****0100`，掩码由 user 域既有 UserBasicDTO 口径给出），**无明文查看接口**（PRD 5.12"按角色权限申请查看明文"不在 V1）；
- 宠物仅名字 + 类别/品种级信息（来自本店订单的 order_pet_snapshot 投影），**不外显**体重、疫苗、驱虫、健康备注、性别、年龄（PRD 5.12 字段表中授权后可见的健康字段全部不做）；
- 无导出、无批量营销触达、无授权有效期管理、无跨店聚合。

**二次扩展（2026-10-19 裁决，均非隐私面）**：

- 订单号尾号搜索：仅当该客户在本店自己的订单中存在 order_no 十进制尾匹配（含前导零）才命中；明文手机号不进查询路径的红线不变；**按完整手机号/明文搜索仍不做**；
- 商家内部备注：每 (store, 派生客户) 一条，0..200 码点，空串语义=清空；仅商家端可见，不进 C 端、不进事件；OWNER 写 + supplement-23 幂等（requestId）+ 乐观版本 + 同事务审计（仅字段名清单）；
- 客户详情页订单/评价只读跳转入口：M 端交互面，跳转目标为本店订单列表/评价列表既有只读页（该客户维度过滤不在 V1，见 §6 登记）。

## 2. 数据口径

| 字段 | 口径（全部从既有事实派生） |
|---|---|
| 客户（userId 分组） | 本店 `pet_order.user_id` 去重；无任何订单的客户不存在 |
| 昵称/脱敏手机号 | user 域既有 `UserQueryApi.getUser`（07号 §3.1，仅掩码）逐户读取；用户不存在/事实不可读为 503 |
| 历史订单数 orderCount | 该客户在本店全部订单数，**含已取消**（PRD 5.12 字段表口径） |
| 累计消费 totalSpend | Σ max(pay_amount − refunded_amount, 0)，金额 BigDecimal 两位小数字符串；不含第三方团购（V1 无此事实源） |
| 客单价 avgTicket | totalSpend / orderCount（orderCount=0 时为 null），向下保留两位 |
| 最近到店 lastVisitAt | max(verified_at)（PRD：最近一次核销完成时间）；从无核销为 null |
| 常用宠物 | 该客户本店订单的 order_pet_snapshot 去重（pet_id 取最近一单快照），仅 petName/petType/breedName |
| 搜索 | 仅昵称包含匹配（服务端 contains，大小写敏感按 collation）。**按手机号搜索不做**：明文手机号不可进查询路径（脱敏红线）。**订单号尾号搜索（2026-10-19 扩展）**：`orderNoSuffix` 2..10 位数字（含前导零），仅命中该客户在本店自己的订单存在 order_no 十进制尾匹配的派生客户；与昵称搜索互斥（同时给出 400）；扫描恒以本店 store_id 前导索引界定，不触他店 |
| 商家内部备注（2026-10-19 扩展） | 每 (store, 派生客户) 一条，0..200 码点，NULL=未设置、空串=清空；乐观版本（首次保存前 version=0）；仅商家端可见，不进 C 端/事件/审计值 |

## 3. HTTP（10号 §4.8 同步）

全部路径带 `/api/v1`；MINIAPP Bearer 会话 + OWNER 主账号坐标复验（同 10号 §4.1 商家订单列表：`MerchantOrderAuthorityApi.requireOwnerRead` 在门店 guard 事务内重验；ACTIVE/OFFLINE/FROZEN 存量读取家族；非本店坐标/不存在/非 OWNER 一律 403 `COMMON_FORBIDDEN` 防枚举）；`Cache-Control: no-store`；读侧无 X-Request-Id；PageResult 信封 `items/page/pageSize/total`；固定排序 totalSpend 降序、userId 数值升序为稳定次序（分页边界 page>=1、pageSize 1..100 默认 20）。

| 方法/路径 | 请求 | data |
|---|---|---|
| GET `/merchant/customers` | query merchantId、storeId 必填；nickname? 搜索词（1..64 码点）；orderNoSuffix? 尾号（2..10 位数字，2026-10-19 扩展；与 nickname 互斥，同时给出 400）；page/pageSize | CustomerRow 分页 |
| GET `/merchant/customers/{userId}` | query merchantId、storeId | CustomerDetail |
| PUT `/merchant/customers/{userId}/note` | body `{merchantId, storeId, note, expectedVersion}`（JSON 严格读：显式 null 非法、未知字段非法、note 0..200 码点、空串=清空）；X-Request-Id | `{userId, note:{content, version, updatedAt}}` |

CustomerRow = `{userId, nickname, phoneMasked, orderCount, totalSpend, avgTicket, lastVisitAt, topPet:{petName,petType,breedName}|null}`。CustomerDetail = CustomerRow + `pets:[{petName,petType,breedName}]`（该客户本店订单去重宠物，按最近订单倒序，最多 20）+ `note:{content,version,updatedAt}|null`（2026-10-19 扩展：本店内部备注，未设置为 null）。时间毫秒精度带偏移 ISO 字符串；金额恒两位小数字符串；ID 正十进制 String。

**备注写面纪律（2026-10-19 扩展）**：命令名 `merchant.customer.note.update`；写门禁同订单命令族——真实 OWNER 写权（`requireOwner`，FROZEN 拒绝）于门店 guard 事务内复验；目标必须是本店派生客户（无本店订单的 userId 404 同形防枚举）；supplement-23 幂等绑定（requestId，canonical-v1 参数指纹，SUCCEEDED 重放原回执不再写）；乐观版本 expectedVersion（首存为 0、行内 version 自增），冲突 409 `COMMON_CONFLICT`；同事务审计（69 伴随表 `merchant_customer_note_audit`，仅字段名清单不存值，命令行不变式 `action_code='merchant.customer.note.update'`）。

错误映射（无新增错误码，同 63/64号只读切片惯例）：400 坐标/分页/搜索词/尾号/备注体非法；401 会话缺失；403 非 OWNER/越权（备注写含 FROZEN 态）；404 客户在本店无任何订单（与越权同形防枚举——本店派生集内的 userId 才可读/可备注）；409 版本冲突/幂等键冲突；503 用户档案事实/门店事实不可读。数据更新：实时读侧聚合，无 T+1 承诺。

## 4. 架构归属

ORDER 域拥有派生投影（订单事实属 order 模块，聚合 SQL 在 pet-order-biz 自有 Mapper XML）；昵称/手机号经 user-api `UserQueryApi` 既有读侧逐户获取（pet-order-biz 既有依赖）；OWNER 复验经 merchant-api `MerchantOrderAuthorityApi` 既有端口。**备注写面（2026-10-19 扩展）**：伴随表 [69-Merchant-Customer-Note-Schema-v0.1.sql](../03-database/69-Merchant-Customer-Note-Schema-v0.1.sql)（`merchant_customer_note` + `merchant_customer_note_audit`，SQL06 不动，同 27号 §6.4 伴随表先例），SQL 在 pet-order-biz 自有 `OrderCustomerNoteMapper.xml`；supplement-23 幂等绑定复用 45号 `order_merchant_command` 行（order 域自有表，专有命令命名空间 `merchant.customer.note`）。无跨模块持久层访问（ARCH-002）、biz 不依赖 biz。

## 5. 验收要点

- 脱敏断言：响应含 `phoneMasked` 键且匹配 `1[0-9]{2}\*{4}[0-9]{4}`；响应体不出现明文手机号。
- 跨店隔离断言：他店订单的客户不出现在列表；他店 userId 详情/备注写 404/403。
- 净额断言：部分退款订单按 pay−refunded 计入；全额退款订单计 0（订单数仍计入）。
- 只读断言：读侧无写路由、GET 不绑定幂等；写侧仅备注一条命令。
- 尾号搜索断言（2026-10-19 扩展）：本店 order_no 尾匹配（含前导零如 `0160`）才命中；他店同尾号订单不 widen 命中集；1 位/超 10 位/非数字 400；与昵称同时给出 400。
- 备注断言（2026-10-19 扩展）：首存 version 0→1；stale expectedVersion 409；同 requestId 同参重放返回原回执且审计不增行；同 requestId 异参 409 IDEMPOTENCY_KEY_CONFLICT；空串清空存储 NULL；审计仅字段名。

## 6. CCR 登记待裁（2026-10-19 二次扩展后余量）

2026-10-19 用户裁决"继续扩展CCR"已落地本契约原登记项：

- ~~2. 商家内部备注~~ → **已落地**（§1/§2/§3 扩展口径：单客户一条 ≤200 字、OWNER 写、幂等+版本+审计）；
- ~~4. 按订单号搜索~~ → **已落地为订单号尾号搜索**（仅本店订单尾匹配；按完整手机号/明文搜索仍不做）；
- ~~5. 从订单详情/评价列表跳转客户档案的入口联动~~ → **已落地为客户详情页订单/评价只读跳转入口**（跳转目标为本店整体只读视图；"该客户维度过滤"见下 4）。

仍未裁/不做（维持登记）：

1. 明文手机号查看（PRD 5.12"按角色权限申请查看明文"）——需授权模型 + 审计面裁决；
2. 宠物健康字段外显（体重/疫苗/驱虫/健康备注/性别/年龄）与"授权有效期"门控；
3. 按手机号/完整订单号搜索、客户导出、批量营销触达、跨店数据聚合；
4. 跳转目标的"该客户维度"订单/评价过滤（需 10号 §4.1 列表增加客户过滤参数，交互面后续切片）；
5. 备注的富文本/多条/时间线形态（V1 为单条纯文本 ≤200 字）。
