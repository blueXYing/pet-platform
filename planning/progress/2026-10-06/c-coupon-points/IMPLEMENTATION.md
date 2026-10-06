# C-006 切片（C端 我的优惠券/我的积分 只读页）实施记录

日期：2026-10-06。分支 `codex/c-coupon-points-20261006`（基于 origin/develop `02abe89`，#104 合并点）。工作树 `C:\Users\Administrator\Desktop\wt-c-coupon-points`；主目录与并行 M 端 worktree 未动。draft PR：`feat(c-end): 我的优惠券与积分只读页（C-006 切片）`。

## 契约现状核实结论（任务第 1 步，决定 PR 形态）

**结论：形态 c —— 域骨架存在但无任何可用查询，PR 按"阻塞分析 + 可行子集"交付。** 逐项核实（develop 02abe89）：

1. `pet-points-api`：仅 `ApiModuleMarker` + 全部 package-info 占位（command/dto/enums/error/query 六包全空）。07 号内部契约 §13.1 `PointsQueryApi`（getBalance/queryLedger）**文档存在、实现不存在**。
2. `pet-coupon-api`：仅 `BookingCouponExposureApi.requireNoCoupon`（无支付/无券预订切片的守卫证明，未知事实抛 503）；biz 侧实现仅 `BookingCouponExposureApiImpl` + `CouponBookingMapper`（`lockInstanceByOrder/lockLedgerByOrder` 两把锁，服务于迟到退款/过期路径）。§12.1 `CouponQueryApi`（listAvailable/getCoupon）**无实现**。
3. `pet-boot/adapter/web/c`：13 个 C 端控制器/支撑文件中**无 coupon/points Controller**（现有 Account/Auth/MerchantMembership/Notification/Pet/Profile/Schedule/Service/Store）。→ 形态 a（C 端 HTTP 已存在）与形态 b（有内部 API 仅缺 HTTP）均不成立。
4. `docs/04-api`：无券/积分专属 HTTP 契约文档；券/积分内部契约为 07 号 §12/§13；无对应 OpenAPI 11 号路径。
5. `docs/03-database`：06 号 §8/§9 定义 coupon_template/coupon_instance/coupon_ledger/points_account/points_ledger；**pet-boot Flyway 目录为空**（README 明示迁移待整合），仅测试路径按需建表。
6. `planning/ISSUE_CATALOG.csv`：CPN-001、CPN-002、PTS-001、PTS-002（后端券/积分全域）与 C-006（本切片所属 C 端 Issue）**全部 BLOCKED**。
7. Figma：两页均有原稿（128:2078、129:8174），结构可从本地缓存全量 JSON 提取（无需消耗在线配额）；无"优惠券详情"帧 → 设计缺稿，沿现行规范实现并登记。

因此：不硬造内部 API、不实现后端（避免在 CPN/PTS 未裁决时发明券发放与积分规则），页面仅 preview=1 本地夹具，契约缺口登记 CCR 待裁。

## 交付物

前端（frontend-miniapp，consumer 侧唯一改动域）：

- `src/consumer/coupon-points/model.ts`：只读视图类型（券：id/名称/面额/门槛/适用范围/类型标签/有效期至/状态/使用时间；积分：余额/流水五类 biz_type/delta/balance_after/时间）+ 夹具自检校验 + `deltaLabel`/`formatLedgerTime`/`thresholdLabel` 展示函数 + `PreviewCouponPointsRepository`（normal/empty 两场景）+ `couponsByStatus` 分桶。字段口径全部锚定 schema 事实与 CCR 提案，文件头注释写明事实/提案边界。
- `src/consumer/pages/coupon-points/`（普通分包，3 页）：
  - `coupons`：优惠券列表——返回导航 + 可用/已使用/已过期三 tab（className 模板字符串变体 `is-active`，WXSS 铁律：不用动态 data-* 做状态样式）+ 券卡片（已使用/已过期置灰变体 `is-used`/`is-expired`）+ 空/加载/阻塞三态。卡片点击进详情（仅透传 preview 与 couponId）。共享底部导航（section=mine），顶层 tab 未接入按既有提示。
  - `coupon-detail`：只读详情（设计缺稿沿现行规范）：卡片复现 + 事实行（状态/适用范围/有效期至/使用时间）+ 无任何动作入口。
  - `points`：我的积分——余额卡（含"V1 不支持积分消费、兑换或抵现"文案）+ 积分明细列表（+蓝/-橙 delta、今天/昨天/M月D日 HH:mm）+ 空/加载/阻塞三态。无签到/邀请/任务等任何赚取入口。
  - **fail-closed**：非 preview=1 进入三页一律显示"查询契约尚未裁决接入（CCR-C006-COUPON-POINTS-READ-001），仅提供只读预览"，不渲染任何夹具数据。
- `src/app.config.ts`：登记普通分包 `{ root: 'consumer/pages/coupon-points', pages: ['coupons','coupon-detail','points'] }`。
- `src/consumer/components/navigation/model.ts`：`consumerPageSections` 增加 `couponList/couponDetail/pointsPage`（均 'mine'）。
- `src/consumer/pages/shell/index.tsx`：工程壳新增两个 preview 入口（`c-coupons-preview`、`c-points-preview`），与既有预览入口同款式。
- `package-check.cjs`：分包清单与三页构建产物校验按先例同步（该脚本为共享根配置，C-006 messages 页曾同口径更新）。
- 测试 `src/consumer/tests/coupon-points.test.ts`（8 例）：夹具自检（状态/枚举/金额格式/ISO 格式/USED⇔usedAt 一致）、余额=流水链 balanceAfter 链式一致、三态分桶、门槛/符号/时间格式化（UTC 确定性）、场景白名单、**V1 硬规则防御断言（不得出现折扣面额与兑换流水，delta 恒非零）**。

契约/治理文档：

- `planning/ccr/CCR-C006-COUPON-POINTS-READ-001.md`：C 端只读查询契约提案（P1 四个 GET + 待裁点 D1~D6：rule_json 派生展示字段、FROZEN 呈现、订单跳转、金额格式、签到入口、相对时区口径），状态 PENDING_OWNER_REVIEW，未批不实现。
- `planning/issues/wave-2/C-006-coupon-points/INVENTORY.md`：设计输入盘点（帧归属、字段映射、差异 D1~D8、夹具事实边界），沿 C-003-design-inputs 先例。
- 本文档。

范围纪律：只动 consumer 侧与上述共享注册点；未改 merchant/**、未改后端、未改权威契约文档；未实现 V1 明确不做的积分商城/兑换/抵扣、券发放与核销；不扩大 C-006 Issue Scope（消息/社区/百科未动）。

## 设计还原口径

- 128:2078 布局/文本/字号从缓存结构 JSON 逐节点提取（402 设计 px，`--cpn-unit = windowWidth/402` 运行时像素单位，C-002/C-003 先例）。
- 取色沿用 C 端既有 token（#f8f5ee/#f0fbff/#c0ecff/#5baae8/#577683/#1d0d07/#ff9500）；缓存无 fillPaints，未消耗在线图像配额，1:1 叠图 VIS 验收留待真实契约轮（INVENTORY D6/D7 已登记 TabBar 差异与取色近似）。
- 范围外设计元素明确不实现：5折折扣券、积分"兑换 -500"流水、签到日历/立即签到按钮、拉新任务/奖励领取、收藏/足迹统计、积分商城（128:2614）、画布外溢出的宠友圈卡片（INVENTORY §3）。

## 验收证据（本机实测，2026-10-06）

门禁全部在 worktree `frontend-miniapp` 执行（npm ci 以 `npm_config_cache=D:\npm-cache` D 盘缓存装依赖，C 盘余量全程 >2.0G，构建产物无异常胀大）：

1. `npm run typecheck`：**PASS**（0 错误；修复 2 处：目标库无 replaceAll 改 `replace(/-/g,'.')`、Taro Button 不接受 role 属性已移除）。
2. `npm test`：**192/192 PASS**（既有 184 + 本切片 8，0 失败）。
3. `npm run build:weapp`：**PASS**（Taro 4.1.5 weapp 构建，无 error）。
4. `npm run check:package`：**PASS**——main 包与各分包均在 2MB 内部预算内；本切片分包 `consumer/pages/coupon-points` **26,827 字节**，totalBytes 4,044,698 < 20MB 预算，ordinarySubpackageCount=5。
5. 复制主目录 `project.config.json`（真实 appid wx1a64646b1d75306e）供开发者工具本地验证；**该文件不入库**（提交前 restore，仓库保持 touristappid 版本）。

后端门禁：本 PR 无后端改动（形态 c），mvn/架构测试不适用；pet-architecture-test 无需重跑。

## 验收边界（本切片"是/否"）

是：两个只读页的 preview=1 视觉与交互骨架；状态 tab 分桶；只读详情；余额/明细只读呈现；契约缺口登记（CCR）与设计盘点（INVENTORY）。

否（阻塞清单，均在 CCR D1~D6 与 ISSUE_CATALOG 登记）：
- C 端券/积分真实 HTTP 查询（待 CCR 批准 + CPN-001/PTS-001 解阻塞后由 Backend Owner 落地）；
- rule_json 派生字段（面额/门槛/范围/类型）的权威 schema（结构未冻结）；
- FROZEN/RISK_FROZEN 券的 C 端呈现（D2）；
- 任何券领取/使用、签到/邀请/任务赚取、积分消费/兑换/抵现入口（硬规则禁止或未裁决）；
- 1:1 VIS 叠图验收（取色近似已登记，待原稿截图配额与真实契约轮）；
- 消息/社区/百科（C-006 其余子集，不扩大本轮 Scope）。

## 遗留风险

- 提案契约（P1）未经批准即在前端以类型+夹具形式预演，冻结时若字段口径变化，`coupon-points/model.ts` 为唯一替换点（文件头已声明）。
- `formatLedgerTime` 以 UTC 日历日判定"今天/昨天"，与用户本地时体感可能差 8 小时（D6 已登记待裁；前端实现可随裁决一行切换）。
- 并行 M 端切片同样编辑 `src/app.config.ts` / `package-check.cjs` / shell 的可能冲突：本分支改动均为纯增量行，合并序无关语义，冲突按行合并即可。
