# NTF-001/C-006 商家端消息通知实施记录

日期：2026-10-02。基线：origin/develop `e3b846e`（PR#98 合并）。分支 `codex/ntf-inbox-20261002`，独立 worktree `wt-ntf-inbox`；主桌面目录停在旧分支，未改动。契约：[CCR-W2-NOTIFICATION-001](../../../ccr/CCR-W2-NOTIFICATION-001.md)（2026-09-22 已批准冻结，PR64/69/71 已合入）。

## 1. 范围核实（与 BLOCKED_QUEUE 口径的差异）

BLOCKED_QUEUE「C端/商家消息列表、详情、已读与白名单跳转契约未冻结、页面未实现」一行对本 worktree 现状**已过时**。逐项核实：

| 面 | 状态 | 证据 |
|---|---|---|
| C 端消息列表/详情/已读（HTTP 三端点） | **已交付** | `CNotificationController`（pet-boot，`pet.auth.c.enabled` 门控）+ `NotificationInboxApiImpl/Service/Store`（pet-notification-biz，CAS 结果幂等已读、本人隔离）；`NotificationInboxHttpTest` 在 pet-boot 测试域 |
| C 端消息中心页面 + 白名单跳转 | **已交付** | `consumer/pages/messages` + `shared/notification-repositories.ts`（严格解码）+ `routeForNotification`（PR64）；`SERVICE_REVIEWED` 白名单扩展（PR69）；pet-boot 消费者接线（PR71） |
| 契约冻结 | **已冻结** | CCR §2–§5（三端点细节、消息类型登记表、跳转白名单）；HTTP10 §3.15 既有路径 |
| **商家端消息面** | **本切片交付** | 商家端此前无消息页面、无入口（CCR §1 明确「商家端/运营端消息路径维持未实现」） |

因此本切片的净缺口 = **商家端消息通知页（列表/详情/已读/受控跳转）**。后端 `markRead`、白名单等任务书预估缺口经核实均已存在，无需后端增量；未发明任何新契约语义。

## 2. 实现内容

### 共享层（一处实现，两端复用）
- `src/shared/notifications/messages.ts`：`MessagesController` 状态机（列表/详情/打开即标记一次/已读不重复标记/失败关闭/401 识别/释放后忽略迟到结果）与 CCR §5 白名单 `routeForNotification`/`jumpLabelFor` 从 `consumer/notifications/messages.ts` **原样迁移**；`consumer/notifications/messages.ts` 改为一行 re-export shim，消费方页面/运行时/测试导入路径零改动，白名单契约保持单一实现。

### 商家端页面（新文件）
- `src/merchant/pages/messages/index.tsx` + `index.config.ts` + `page.css`：
  - **设计源**：商家端 Figma frame `23:19357`「消息通知」402x898（docs/08-engineering/20 号登记表 §3），按 402 设计宽 unit 变量（`--mmsg-unit`）一比一落卡片区：卡片 371x92 r15.39 白底、标题 13.46/600 #5BAAE8、正文 13.46/400 #6B7280、时间 11.54/400 #9CA3AF、跳转胶囊 57.7x26.9 #F0FBFF 底 #5BAAE8 字、页头带 86 高 #FFF6E5、页面底 #F0FBFF。无原稿部分（返回钮、详情卡、未读点）沿用已验收的 C 端消息中心与商家服务页语言，不虚构设计。
  - **V1 剪裁（如实登记）**：① 原稿的商家主页头部（头像/评分/营业中钮）与底部 tab bar 属首页组合件，非本子页内容；② 原稿「交易通知/系统消息」页签——冻结的列表契约（CCR §2）无分类筛选参数，客户端过滤会因分页失真，V1 单列表呈现。
  - 数据：真实模式复用统一收件箱（`NotificationRepository`，会话即接收者；商家主账号以 C 端 USER 身份收件系 PR#69 已批准口径）；`preview=1` 本地夹具走 `merchant/notifications/preview.ts`（两种已登记类型 + 一种未登记公告），无任何网络调用。
  - 开关：真实请求沿既有 `MERCHANT_APPLICATION_ENABLED`（`PET_MERCHANT_APPLICATION_ENABLED=true` 且 `PET_C_API_ORIGIN` 非空才装配），未启用时失败关闭（`NOTIFICATIONS_NOT_CONNECTED`），与 `merchant/admission-runtime.ts` 同款。
- `src/merchant/notifications/messages-runtime.ts`：真实 deps（同上开关）；`preview.ts`：纯夹具（不引 Taro，可单测）。
- 受控跳转：详情页仅对 CCR §5 已登记类型出现跳转钮——`SERVICE_REVIEWED`→`/merchant/pages/services/index`（从商家区进入时工作台坐标更可能就位；目标页自带准入门禁，跳转不携带、不豁免授权，未从工作台进入时目标页可见提示「请从商家工作台进入服务管理」）、`MERCHANT_APPLICATION_REVIEWED`→`/consumer/pages/merchant-application/index`；未登记类型仅展示详情。消息体无 URL 字段。
- WXSS 铁律：新页面状态样式全部 className/JSX 分支变体，dist 产物 `index.wxss` 经查 0 处 `data-` 属性选择器。
- 入口与登记：`consumer/pages/shell/index.tsx` 增「商家消息通知（真实契约接口）」与视觉预览两个入口（shell 是工程验证页，非产品入口）；`app.config.ts` merchant 分包登记 `pages/messages/index`；`package-check.cjs` 同步分包清单与产物断言。

### 后端
- **零改动**。`pet-notification-biz`/`pet-notification-api`/`pet-boot` 均未触碰；读取面（列表/详情/已读）与两个消费者、开关装配沿用已合并代码。

## 3. 验证

前端（frontend-miniapp，node_modules 经 `npm ci` 重建）：
- `npm run typecheck` PASS；
- `npm test` **183/183 通过**（含新增 `merchant/tests/messages.test.ts` 5 项：状态机族、白名单路由/文案、预览夹具跳转语义、预览 markRead 本地幂等、释放后忽略迟到结果）；
- `npm run build:weapp` 编译成功（仅既有的资源体积告警，与本切片无关）；
- `npm run check:package` PASS（merchant 分包 109,586 字节，含消息页 js/json/wxml/wxss 四产物，预算内）。

后端基线复验（本切片无后端改动，验证所依赖的既有面）：
- `mvn -pl pet-notification-biz test`（JAVA_HOME=Adoptium 21.0.11，自建隔离 MySQL 8.4.9 127.0.0.1:33461，PLAT003_MYSQL_URL 指向；测试库随机建/自动 DROP）**14/14 通过**（MerchantApplicationReviewed 6 + ServiceReviewed 8，含读取/已读隔离与结果幂等回放）；
- `mvn -pl pet-architecture-test test` **31/31 通过**（biz 不互依、跨模块 Repository/DO 禁令等门禁全绿）；
- 测试后 mysqladmin shutdown 并删除 datadir，端口释放。pet-boot 的 `NotificationInboxHttpTest` 未重跑（无后端改动，CI 覆盖）。

## 4. 边界（未做/不做）

- 未做 receiver_type=MERCHANT/OPS 投影、`/api/v1/merchant|admin/notifications` 独立 HTTP 路径（CCR §1 维持排除，产品决策缺口见 PR「阻塞与待裁决」）；商家端订单/退款/核销/售后/评价类消息的**生产者**在 V1 后续切片（SSOT §16.2 所列类型目前均无事件源）。
- 未做消息分类页签/分类查询参数（契约无此参数）；未做分页加载更多（控制器固定 page1/20，与已验收 C 端行为一致）；通知偏好四端点、外部微信订阅消息按裁决不做；无 IM 私信。
- 未运行生产迁移、未合并不发布；主目录未提交文件（含 figma-map 草稿）未触碰；未改 PR#99（codex/aftersale-pages-20261001）涉及的任何文件（`merchant/pages/workspace/index.tsx` 保留未动，商家工作台内消息入口留待其合并后协调，避免冲突）。
- 真机走查与微信开发者工具人工验收未执行（与既有切片相同遗留）；preview 页面数据为本地夹具，不代表真实消息分布。

## 5. 遗留风险

- `routeForNotification` 迁移后两端共享；若后续分支也改 `consumer/notifications/messages.ts`（re-export shim）需注意合并语义。PR#99 的 8 行 workspace 改动与本切片无文件交集。
- 商家端消息页从 shell 直达时无商家工作台坐标，`SERVICE_REVIEWED` 跳转落到服务页准入提示（可见、不绕权）——这是按「跳转不豁免鉴权」规则的预期行为，非缺陷。
- 列表时间戳直接展示 ISO 字符串（与 C 端一致），未做本地化时间格式，视觉走查时如需调整属页面级小改。
