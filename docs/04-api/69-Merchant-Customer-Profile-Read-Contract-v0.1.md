# 商家客户档案读侧契约 v0.1（69号）

状态：ACCEPTED_CONTRACT / IMPLEMENTED_DEFAULT_OFF（`pet.order.customer.http.enabled`，2026-10-19 用户裁决"全做"范围内按最小实现铁律执行）。来源：M 端 PRD 5.12 客户档案信息 + 审计 C1 #11/#12（帧 23:12738 客户管理 / 23:13478 客户详情）+ 2026-10-19 用户隐私边界裁决。

## 1. 隐私红线与最小实现边界（本契约的先决裁决）

用户 2026-10-19 裁决明确"从本店订单派生的客户列表，只读"为本切片唯一交付面。**只做**：

- 列表/详情数据 100% 从本店（merchantId+storeId 双键）已存在订单事实派生，零新事实源、零爬取、零跨店聚合；
- 手机号仅脱敏形态（`138****0100`，掩码由 user 域既有 UserBasicDTO 口径给出），**无明文查看接口**（PRD 5.12"按角色权限申请查看明文"不在 V1）；
- 宠物仅名字 + 类别/品种级信息（来自本店订单的 order_pet_snapshot 投影），**不外显**体重、疫苗、驱虫、健康备注、性别、年龄（PRD 5.12 字段表中授权后可见的健康字段全部不做）；
- 只读：无商家内部备注（PRD 5.12"添加商家内部备注"为写能力，不做）、无导出、无批量营销触达、无授权有效期管理。

**以上未做项全部登记 CCR 待裁**（见 §6），不得以本契约为依据自行实现。

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
| 搜索 | 仅昵称包含匹配（服务端 contains，大小写敏感按 collation）。**按手机号/订单号搜索不做**：明文手机号不可进查询路径（脱敏红线），订单号搜索属订单列表既有能力 |

## 3. HTTP（10号 §4.8 同步）

全部路径带 `/api/v1`；MINIAPP Bearer 会话 + OWNER 主账号坐标复验（同 10号 §4.1 商家订单列表：`MerchantOrderAuthorityApi.requireOwnerRead` 在门店 guard 事务内重验；ACTIVE/OFFLINE/FROZEN 存量读取家族；非本店坐标/不存在/非 OWNER 一律 403 `COMMON_FORBIDDEN` 防枚举）；`Cache-Control: no-store`；只读无 X-Request-Id；PageResult 信封 `items/page/pageSize/total`；固定排序 totalSpend 降序、userId 数值升序为稳定次序（分页边界 page>=1、pageSize 1..100 默认 20）。

| 方法/路径 | 请求 | data |
|---|---|---|
| GET `/merchant/customers` | query merchantId、storeId 必填；nickname? 搜索词（1..64 码点）；page/pageSize | CustomerRow 分页 |
| GET `/merchant/customers/{userId}` | query merchantId、storeId | CustomerDetail |

CustomerRow = `{userId, nickname, phoneMasked, orderCount, totalSpend, avgTicket, lastVisitAt, topPet:{petName,petType,breedName}|null}`。CustomerDetail = CustomerRow + `pets:[{petName,petType,breedName}]`（该客户本店订单去重宠物，按最近订单倒序，最多 20）。时间毫秒精度带偏移 ISO 字符串；金额恒两位小数字符串；ID 正十进制 String。

错误映射（无新增错误码，同 63/64号只读切片惯例）：400 坐标/分页/搜索词非法；401 会话缺失；403 非 OWNER/越权；404 客户在本店无任何订单（与越权同形防枚举——本店派生集内的 userId 才可读）；503 用户档案事实/门店事实不可读。数据更新：实时读侧聚合，无 T+1 承诺。

## 4. 架构归属

ORDER 域拥有派生投影（订单事实属 order 模块，聚合 SQL 在 pet-order-biz 自有 Mapper XML）；昵称/手机号经 user-api `UserQueryApi` 既有读侧逐户获取（pet-order-biz 既有依赖）；OWNER 复验经 merchant-api `MerchantOrderAuthorityApi` 既有端口。无跨模块持久层访问（ARCH-002）、biz 不依赖 biz。

## 5. 验收要点

- 脱敏断言：响应含 `phoneMasked` 键且匹配 `1[0-9]{2}\*{4}[0-9]{4}`；响应体不出现明文手机号。
- 跨店隔离断言：他店订单的客户不出现在列表；他店 userId 详情 404。
- 净额断言：部分退款订单按 pay−refunded 计入；全额退款订单计 0（订单数仍计入）。
- 只读断言：无任何写路由；GET 不绑定幂等。

## 6. CCR 登记待裁（本切片不做）

1. 明文手机号查看（PRD 5.12"按角色权限申请查看明文"）——需授权模型 + 审计面裁决；
2. 商家内部备注（PRD 5.12 写能力，仅本店可见）；
3. 宠物健康字段外显（体重/疫苗/驱虫/健康备注/性别/年龄）与"授权有效期"门控；
4. 按手机号/订单号搜索、客户导出、批量营销触达、跨店数据聚合；
5. 从订单详情/评价列表跳转客户档案的入口联动（交互面后续切片）。
