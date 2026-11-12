# CCR-M-VERIFY-RECORD-001 商家端核销记录读侧（P-13 方案 B）

- 状态：APPROVED（2026-11-12 用户按推荐项批准；裁决记录见 §9）
- 日期：2026-11-11
- 提出方：商家端待修清单修复轮（P-13，用户已拍板方案 B：核销执行挪员工工作台、
  staff-verify 改纯核销记录列表）
- 关联：Figma `12:7426`（核销记录列表页，截图
  `planning/progress/2026-11-04/merchant-figma-restore/evidence/kpi-links/figma-12-7426.png`）；
  Contract 48（核销完成，写侧身份四元组/落库口径）、47（凭证）、63（运营核销读侧先例）、
  54（成员绑定，成员姓名可见性）、10 §4.1 + CCR-M-ORDER-CARD-001（商家订单读侧客户投影口径）；
  `planning/progress/2026-11-04/merchant-figma-restore/pending-issues.md` P-13
- 当前 Contract：无商家端核销记录读侧路由（48 号仅写侧 HTTP 面
  `POST /api/v1/merchant/orders/{orderId}/verification`；63 号为 ADMIN_WEB 运营面）
- 发现的问题：商家端「核销」tab 当前是核销执行表单；用户拍板改为核销记录列表
  （Figma 12:7426），但后端无商家侧记录查询路由——Contract 缺失，走本 CCR
- 是否涉及产品裁决：**YES**（核销人/客户信息在商家读侧的展示口径）

## 1. 背景与现状

- 48 号已将核销身份四元组（operatorType/operatorId/membershipKind/operatorStaffId）
  落库 `verification_record`（含 store_id、verified_at、order_id）；运营侧 63 号
  `GET /api/v1/admin/trade/verifications` 已有平台级读侧先例（含尝试链/风控溯源）。
- 商家端需要的只是**本店**记录列表（服务/客户/金额/时间/核销人，Figma 12:7426），
  无尝试链/风控溯源需求（那是运营争议面）。
- P-13 方案 B 已拍板的完整变更：①本 CCR 新增商家读侧；②前端 staff-verify 重写为
  记录列表；③核销执行表单挪员工工作台（调既有 48 号端点，后端无变化）。

## 2. 数据可用性核实

| 提议字段 | 数据来源 | 现状 |
|---|---|---|
| verificationId / verifiedAt | `verification_record` | 已有数据 |
| orderId / storeId | `verification_record`（store_id 有归属列） | 已有数据 |
| 核销人四元组 | `verification_record`（operator_type/operator_id/membership_kind/operator_staff_id） | 已有数据 |
| serviceName / payAmount | `order_service_snapshot` / `pet_order`（订单域既有读侧投影，同 CCR-M-ORDER-CARD-001 来源） | 已有数据，订单读侧已返回 |
| customerNickname / customerAvatarUrl | `user_account` 经订单投影（CCR-M-ORDER-CARD-001 已批口径） | 已有数据 |
| 核销人显示名 | STAFF：54 号 grant/member 展示姓名（本店）；OWNER：固定标签「店长」 | 已有数据（54 号侧） |

VER 只读自身所有权表（verification_record）；订单摘要由 boot 装配页经 ORDER 既有
读侧 API 组装（63 号订单详情含核销链接同为组装先例）；不新增 Schema/Event。

## 3. 最小变更提议

新增 `GET /api/v1/merchant/verifications`（记录列表，PageResult 信封）：

- 开关 `pet.verification.merchant-read.http.enabled` 默认 false（家族惯例，开启前置
  completion 内核）。
- 鉴权：MINIAPP 会话 + 商家工作台坐标（merchantId+storeId）门店 guard + OWNER
  重验，与 `GET /api/v1/merchant/orders` 同款；跨店行不可见。
- 过滤器（全部可选）：`orderId` 精确、`verifiedFrom`/`verifiedTo`（UTC，跨度 ≤366 天）；
  未知查询参数 400（同 63 号纪律）。分页 page/pageSize 同家族；固定排序
  `verified_at DESC, id DESC`。
- item 字段：`verificationId/orderId/serviceName/payAmount/customerNickname/
  customerAvatarUrl/verifiedAt/operatorKind`（OWNER|STAFF）`/operatorName`
  （STAFF=成员姓名；OWNER=「店长」固定文案）。
- 时间 UTC 毫秒字符串；ID String；金额两位小数字符串（同 63 号）。
- `Cache-Control: no-store`；GET 不绑 X-Request-Id；错误映射沿商家读侧家族
  （400/401/403/404/503，未识别 500 不泄内核）。

前端（无需新 CCR，随本 P-13 实施）：staff-verify 重写为记录列表页（Figma 12:7426）；
核销执行表单迁入 staff-workbench（既有 48 号端点不动）。

## 4. 隐私与安全

- 不返回：凭证密文/lookup/code（48/63 号红线）、客户手机号（CCR-M-ORDER-CARD-001
  §9.3 裁决）、地址/健康备注、宠物字段（记录卡不需要）、operatorId 真实 userId
  （展示层用 operatorKind+operatorName，审计留痕仍在库内）。
- STAFF 姓名来源 54 号本店成员数据，OWNER 对本店成员姓名已可见（54 号列表），
  不跨店。
- 日志/审计不记录客户/成员信息；前端不落本地持久化。

## 5. 兼容性与影响

- API：纯新增路由，无既有面变更；Schema/Event 无变化。
- 模块：pet-verification-api 增 query 接口 + biz 实现（Mapper XML，过
  check-persistence-style.py）+ pet-boot 装配 controller；订单摘要复用 ORDER 既有
  读侧 API（boot 组装，无 biz→biz 依赖）。
- 前端：staff-verify 重写、staff-workbench 增核销执行入口；导航家族遵守「进入
  工作台 reLaunch」约定。
- 测试：后端列表/跨店 403/空值/脱敏断言；前端解码回归 + e2e 契约冒烟。

## 6. 验收标准

1. 真实核销后商家「核销」tab 显示记录列表（服务/客户昵称/金额/时间/核销人）。
2. 跨店/非 OWNER 403；响应不含 phone/密文/operatorId。
3. 员工工作台可完成核销执行（既有端点），执行后记录列表可见新记录。
4. 后端测试 + check-persistence-style.py + e2e 契约冒烟通过。

## 7. 备选方案

- 备选 A：不新增读侧，核销 tab 维持执行表单（用户已否决——拍板方案 B）。
- 备选 B：扩 63 号运营面给商家用（ADMIN_WEB 受众不匹配 MINIAPP 商家会话，鉴权链
  不同，不成立）。
- 备选 C：前端用既有订单列表 displayStatus=COMPLETED 自行推导「已核销」——缺
  verifiedAt/核销人事实，语义不等价，不推荐。

## 8. 待裁决项

1. 核销人展示：STAFF 显示成员姓名、OWNER 显示「店长」固定文案（推荐，54 号 OWNER
   已可见本店成员姓名）；或仅显示类别不显姓名；或脱敏 staffId。
2. 客户块沿 CCR-M-ORDER-CARD-001 口径（昵称+头像、无手机号）直接沿用（推荐）。
3. 记录列表可见范围：仅商家 OWNER（推荐，员工工作台只做核销执行）；或员工
   （有 verify 动作 grant）也可看本店记录。

## 9. 裁决记录（2026-11-12，用户"按推荐继续"）

1. 核销人显示：**STAFF=成员真名、OWNER=「店长」固定标签**（推荐项 1）。
2. 客户块：**沿 CCR-M-ORDER-CARD-001 口径**——昵称+头像、无手机号（推荐项 2）。
3. 可见范围：**仅商家 OWNER**；员工工作台只保留核销执行入口（推荐项 3）。

实施随 P-13 方案 B：staff-verify 重写为记录列表、核销执行表单迁入员工工作台；
本 CCR 的路由/开关/字段按 §3 执行。
