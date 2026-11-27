# CCR-PAY-ONBOARD-001 商家收单开通：拉卡拉进件、开通分账与绑定平台接收方

状态：APPROVED（规则依据：[44 号裁决补充](../../docs/01-prd/44-商家收单开通拉卡拉进件裁决补充-v1.0.md)、
[40 号裁决补充 §11](../../docs/01-prd/40-支付中心订单分账与提现裁决补充-v1.0.md)）。
接口与存储口径：[81 号契约](../../docs/04-api/81-Payment-Merchant-Onboarding-Contract-v0.1.md)。
关联：[CCR-PAY-CENTER-001](./CCR-PAY-CENTER-001.md) §14（模式 A）。

## 1. 背景

模式 A（40 号 §10）要求每个入驻商家有自己的拉卡拉银联商户号与终端号。现状是运营在结算配置页手输，系统与拉卡拉之间没有任何开通链路：
商家可以审核通过、签署协议、上架接单，但下单支付必然失败，且没有任何提示告诉运营该做什么。
产品负责人 2026-11-27 裁决改为系统经拉卡拉接口完成开通（进件 → 开通分账 → 绑定平台接收方），并分两期提供资料录入入口。

## 2. 范围

### 2.1 本 CCR 做

1. 商家收单开通状态机与持久化（每商家一条开通记录 + 不可变审计）。
2. 拉卡拉协议层：进件（1020）、进件查询（1082）、开通分账（1284）、分账信息查询（1286）、
   绑定（1290，新增开通路径复用）、电子合同申请（1208）与签约状态查询（1212）；附件上传（1084）复用。
3. 四个已验签回调入口与定时查询兜底任务。
4. 运营后台接口与页面：代填资料、上传附件、发起开通、进度时间线、同步、重新提交、补录进件 ID、按原因查看明文。
5. 开通完成后由系统写入结算配置的商户号/终端号；支付前置门禁。
6. 权限码注册、部署配置项、Runbook 迁移登记。

### 2.2 本 CCR 不做

商家小程序自助填写与查看（二期，另行 CCR 增补）、纸质盖章委托书上送、进件复议（1089）、进件前校验（1087）、
商家资料变更（1080/1081/1285/1288）、解绑（1291）、平台分账模式（`platformId`，3005）、
余额分账/规则分账、为商家单独创建 SR 接收方（1287，既有台账保留但不用于新开通）、新增任何 `.enabled` 子开关、新增对外事件。

## 3. 模块与依赖

| 位置 | 内容 |
|---|---|
| `pet-payment-api` | `MerchantOnboardingApi`（命令）、`MerchantOnboardingQueryApi`、`OnboardingTypes` |
| `pet-payment-biz` `settlement` 包 | `MerchantOnboardingService`（状态机、命令、回调、推进与查询）、`MerchantOnboardingChannel` 端口、`MerchantOnboardingEligibilityPort`、`PaymentOnboardingStore` + `PaymentOnboardingMapper(.xml)`、`MerchantOnboardingAdvanceTask`、`MerchantOnboardingPollTask` |
| `pet-payment-biz` `infrastructure/provider` | `LakalaOnboardingProtocol`（离线报文构造与解析）、`LakalaMerchantOnboardingChannel`；`LakalaV2Protocol`/`LakalaV2HttpClient` 增补 |
| `pet-boot` | `PaymentOnboardingConfiguration`、`AdminMerchantOnboardingController`、`LakalaCallbackController` 增补端点、权限码注册、`DbMerchantPaymentBindings` 门禁 |
| `frontend-admin` | 「商家收单开通」页 |

依赖方向不变：`pet-payment-biz` 只依赖 `*-api`。不跨模块访问 Mapper/Entity。

两处必要的上游增量（均登记在 81 号契约 §8）：

- `pet-merchant-api` 新增 `MerchantAcquiringFactsApi`（商家档案状态、入驻审核、平台协议签署、商家类型、城市、主体用户），由 `pet-merchant-biz` 基于既有读取实现；boot 适配器把它接到 `MerchantOnboardingEligibilityPort`。
- `pet-thirdparty` 私有素材新增用途 `MERCHANT_ONBOARDING_ATTACHMENT`：既有上传通道只接受"消费者本人上传"，而一期的银行卡照片、合作协议只能由运营代传。
  新增 `PrivateAssetApi.uploadOperatorAttachment`，仅允许 `PLATFORM_OPERATOR`、归属商家主体用户，扫描/规范化/就绪安全链不变，仅图片。
  读取仍走既有 `PrivateAssetApi.readReadyForSystem`。

## 4. 状态机

每商家一条记录，`status` 取值：

```
DRAFT                资料录入中
EC_SIGNING           电子合同已申请，待商家法人签署
CONTRACT_APPLYING    进件已提交，待拉卡拉审核
CONTRACT_UNKNOWN     进件请求结果未知（超时/断连），需运营核实
CONTRACT_REJECTED    进件被驳回
LEDGER_APPLYING      开通分账审核中
LEDGER_REJECTED      开通分账被驳回
BIND_APPLYING        绑定审核中
BIND_REJECTED        绑定被驳回
ACTIVE               开通完成，商户号/终端号已写入结算配置
```

| 当前状态 | 触发 | 动作与结果 |
|---|---|---|
| 无 / DRAFT | 运营保存资料 | DRAFT |
| DRAFT | 运营「发起开通」 | 校验商家准入（档案正常、入驻审核通过、平台协议已签）与平台级参数齐全 → 申请电子合同 → EC_SIGNING（返回签约链接供运营转发商家法人） |
| EC_SIGNING | 运营保存资料 | DRAFT（作废已申请的电子合同） |
| EC_SIGNING | 回调/查询：已签署且有合同编号 | 自动进件：先上传附件再提交进件 → CONTRACT_APPLYING；进件请求结果未知 → CONTRACT_UNKNOWN |
| CONTRACT_APPLYING | 回调/查询：审核通过且商户号、终端号齐全 | 自动开通分账 → LEDGER_APPLYING |
| CONTRACT_APPLYING | 回调/查询：审核驳回 | CONTRACT_REJECTED（保存驳回原因） |
| CONTRACT_UNKNOWN | 运营补录进件 ID | CONTRACT_APPLYING（按补录的进件 ID 继续查询） |
| CONTRACT_UNKNOWN | 运营确认拉卡拉未受理（原因必填） | DRAFT（保留资料，重新发起） |
| CONTRACT_REJECTED | 运营保存资料 | DRAFT（清空本次进件与电子合同结果） |
| LEDGER_APPLYING | 回调/查询：通过 | 自动绑定（上传《分账合作协议》后提交）→ BIND_APPLYING |
| LEDGER_APPLYING | 回调/查询：拒绝，或等待超时 | LEDGER_REJECTED |
| LEDGER_REJECTED | 运营「重新提交」 | LEDGER_APPLYING（新流水） |
| BIND_APPLYING | 回调/查询：通过 | ACTIVE：同事务写结算配置商户号/终端号 |
| BIND_APPLYING | 回调/查询：拒绝，或等待超时 | BIND_REJECTED |
| BIND_REJECTED | 运营「重新提交」（可更换合作协议） | BIND_APPLYING（新流水） |
| 任意等待态 | 运营「同步」 | 当前步已提交则立即查询并按上表推进；当前步待提交则重新执行一次提交 |
| 任意等待态 | 轮询用尽（约 5 天） | 签署超时回 `DRAFT`（`EC_SIGN_TIMEOUT`）；其余进入对应 `*_REJECTED`（原因"等待…超时"） |

约束：

1. 网络调用一律在数据库事务之外；每个推进动作是"事务内校验版本并固化流水号 → 事务外调渠道 → 事务内按版本落结果"。
2. **提交类接口结果未知时不自动重发**。电子合同结果未知 → 回 DRAFT；进件结果未知 → CONTRACT_UNKNOWN（避免在拉卡拉重复建商户）；
   开通分账/绑定结果未知时保留 APPLYING（`*_OUTCOME_UNKNOWN`），由按商户号的查询确认结果。
3. 进件通过后资料不可修改（修改结算账户属拉卡拉商户资料变更，不在范围）；仅 LEDGER/BIND 驳回可重新提交当前步。
4. 回调与查询按"当前状态 + 业务键（进件 ID/流水号/申请编号/商户号）"幂等：过期或重复事件只记审计，不改状态。

## 5. 渠道报文要点

| 步骤 | 接口 | 关键上送 | 结果来源 |
|---|---|---|---|
| 电子合同 | `/api/v3/mms/open_api/ec/apply` | `ec_type_code=EC011`、法人证件/姓名/手机、执照、结算账户、`ec_content_parameters`（E1 合作方=平台、E2 合作协议名称、E3/E4 按订单、E7 指令清分、E8 最低分得比例、E9 平台、E10 日期、E11 法人）、`sign_auth_type=WX_APPLET_FACE`、`ret_url` | 同步返回 `result_url`；回调或 `ec/q_status` 得 `ec_status=COMPLETED` 与 `ec_no` |
| 进件 | 1084 + `/api/v2/mms/openApi/addMer` | 主体、法人、结算账户、`posType`/`mccCode`/`merBusiContent`/`settlePeriod`、`feeData`、`fileData`、`contractNo=ec_no`、`retUrl` | 同步返回 `contractId`；回调 1083 或 `queryContract` 得 `contractStatus`、`merCupNo`、`termDatas[].termNo` |
| 开通分账 | `/api/v2/mms/openApi/ledger/applyLedgerMer` | `merCupNo`、`contactMobile`、`splitLowestRatio`、`eleContractNo=ec_no`、`splitRange=ALL`、`sepFundSource=TR`、`splitLaunchMode=MANUAL`、`settleType=01`、`retUrl` | 同步返回 `applyId`；回调 `auditStatus` 或 `queryLedgerMer` 的 `splitStatus` |
| 绑定 | 1084 + `/api/v2/mms/openApi/ledger/applyBind` | `merCupNo`=商家银联商户号、`receiverNo`=平台接收方、`entrustFile`=《分账合作协议》、`retUrl` | 同步返回 `applyId`；回调 `auditStatus` 或 `listRelationApply`（1872） |

- 附件必传集合（1084 备注列）：`FR_ID_CARD_FRONT`、`FR_ID_CARD_BEHIND`、`BANK_CARD`、`BUSINESS_LICENCE`、`MERCHANT_PHOTO`、`SHOPINNER`；
  非法人结算另需 `ID_CARD_FRONT`、`ID_CARD_BEHIND`、`NO_LEGAL_PERSON_SETT_AUTH_LETTER`。附件须在进件前 24 小时内上传，`attFileId` 不落库。
- 报文外壳按接口家族区分：进件/查询/附件上传用 `ver`+`timestamp`+`reqId`；分账家族（`/api/v2/mms/openApi/ledger/**`）按官方示例用
  `version:"2.0"`+`reqTime`+`reqId`；电子合同用 `ver`+`timestamp`+`req_id`+`req_data`。外壳选择集中在 `LakalaV2Protocol` 一处，便于联调后一键修正。
- 分账家族外壳与既有接收方链路的差异见 §13.2，本 CCR 一并按官方文档对齐并在 PR 中披露。
- `splitRange`/`sepFundSource`/`splitLaunchMode`/`settleType`/`ec_type_code`/`sign_auth_type` 是规则常量，不做成配置。

## 6. 结果确认与可靠性

- 回调入口四个：`/api/v1/callbacks/lakala/onboarding/{ec,contract,ledger-open,bind}`，一律先按 1009 三行报文验签再解析；
  验签失败返回 400，不改状态。落库成功后才返回 `{"code":"SUCCESS","message":"执行成功"}`。
- 回调只负责"改状态 + 记审计 + 投递推进任务"，下一步的渠道调用由 `MERCHANT_ONBOARDING_ADVANCE` 任务在事务外执行。
- 每个等待态投递 `MERCHANT_ONBOARDING_POLL:{merchantId}` 任务，按 1m/5m/15m/1h/6h 退避查询，最多 20 次（约 5 天）；
  用尽后当前步转为 REJECTED（原因"等待超时"），运营可重新提交。查询不依赖回调是否送达。
- 推进任务失败按 10s/30s/2m/10m 重试 6 次；仍失败则保留状态并记录 `last_error_code/message`，运营可点「同步」或「重新提交」。
- 并发：记录 `version` 乐观锁；定时任务键为"任务类型:商家:记录版本"（每次状态流转都先保存并递增版本），
  所以同一版本只会有一个同类任务，新一轮申请也不会撞上上一轮已结束的任务（集成测试曾复现：键用渠道受理编号时第三轮进件推进静默失败）。
- 进件结果落库不看版本：回调或成功响应到达时，即使运营已把记录改回 DRAFT，也以拉卡拉结果为准进入 CONTRACT_APPLYING。
- 幂等：运营写操作带 `X-Request-Id`，`request_key` 唯一、同键异参（含操作原因不同）返回 409。
- 编辑已有资料时敏感字段留空保持原值（服务端先回填库中明文再校验），附件整体替换。

## 7. Schema 影响

新迁移 `V44__payment_merchant_onboarding.sql`（`settlement-migration` 目录，与
[84 号 Schema](../../docs/03-database/84-Payment-Merchant-Onboarding-Schema-v0.1.sql) 一字不差）：

- `payment_merchant_onboarding`：每商家唯一；资料、平台级参数快照、电子合同/进件/开通分账/绑定的渠道事实、状态与错误。
  敏感字段（执照号、法人证件号、法人手机号、联系人手机号、结算卡号、结算人证件号）AES-256-GCM 加密并附脱敏列，复用接收方密钥与加密实现。
- `payment_merchant_onboarding_audit`：不可变审计，`request_key` 唯一。

不改既有表。

## 8. API / Event 影响

- 运营网页 `/api/v1/admin/settlement/onboardings/**`，新增权限码 `settlement.onboarding.write`、`settlement.onboarding.read-sensitive`；
  读取沿用 `settlement.report.read`。超管默认拥有全部已部署动作码（SSOT §二十四）。
- 公共回调四个（仅 `pet.payment.enabled=true` 时装配，加入回调安全链白名单）。
- 结算配置 `PUT /configs/{merchantId}`：存在开通记录的商家，其收单商户号/终端号归开通链路所有——请求不得改成其它值（409），未传时保留系统写入的值。
  （集成测试曾复现：只改账期、不带商户号的请求会把已开通商家的商户号清空。）
- 支付前置：存在开通记录且非 ACTIVE 的商家，`PaymentMerchantBindings.require` 失败关闭；开通台账表不存在（V44 未执行）时视为没有开通记录，
  门禁不生效——表不存在就不可能有开通进行中的商家，拦掉只会让一个可选功能的迁移缺失拖垮全站支付。
  结算配置保存里的"收单对归开通流程所有"判断读同一入口（`PaymentOnboardingStore.findIfApplied`），同样按无记录处理：
  第一版只放行了支付门禁，结算配置保存仍会因台账缺失整条报错，等于一个可选功能的迁移缺失拖垮既有的结算配置编辑。
  该入口只把 MySQL 1146 / SQLSTATE 42S02（表不存在）当作无记录，其余数据库异常照常抛出——数据库真坏了仍须失败关闭。
- 不新增对外事件。

## 9. 前端影响

- 运营网页新增「商家收单开通」：按商家 ID 打开；资料表单（平台级参数只读展示、可覆盖 MCC 与地区码）、附件上传、
  进度时间线（电子合同 → 进件 → 开通分账 → 绑定）、当前步操作按钮、签约链接复制、审计记录。敏感字段只显示脱敏值，查看明文须填原因。
- 小程序本期不改。

## 10. 配置（遵守五层配置与单开关红线）

统一前缀 `pet.payment.onboarding`，仅在 `application.yml` 声明并以环境变量绑定，不新增开关：

| 键 | 环境变量 | 说明 |
|---|---|---|
| `notify-base-url` | `LAKALA_ONBOARDING_NOTIFY_BASE_URL` | 回调基址，代码追加 `/ec` `/contract` `/ledger-open` `/bind` |
| `pos-type` | `LAKALA_ONBOARDING_POS_TYPE` | 进件 POS 类型（立项确认） |
| `mer-busi-content` | `LAKALA_ONBOARDING_MER_BUSI_CONTENT` | 经营内容字典值 |
| `settle-period` | `LAKALA_ONBOARDING_SETTLE_PERIOD` | 结算周期字典值 |
| `fee-rates-json` | `LAKALA_ONBOARDING_FEE_RATES_JSON` | 进件费率集合 JSON |
| `mcc-default` / `mcc-by-merchant-type-json` | `LAKALA_ONBOARDING_MCC_DEFAULT` / `LAKALA_ONBOARDING_MCC_BY_TYPE_JSON` | MCC 及按商家类型映射 |
| `district-by-city-json` | `LAKALA_ONBOARDING_DISTRICT_BY_CITY_JSON` | 平台城市码 → 拉卡拉地区码 |
| `lowest-ratio-percent` | `LAKALA_ONBOARDING_LOWEST_RATIO` | 委托书"本商户最低需分得"比例，百分数（如 `90`、`90.50`） |
| `cooperation-agreement-name` | `LAKALA_ONBOARDING_COOPERATION_AGREEMENT_NAME` | 电子合同 E2：平台与商家合作协议名称 |
| `platform-company-name` | `LAKALA_ONBOARDING_PLATFORM_NAME` | 电子合同 E1/E9：平台公司全称 |
| `ec-org-id` | `LAKALA_ONBOARDING_EC_ORG_ID` | 电子合同的整数机构号；留空时复用数字形式的 `LAKALA_ORG_CODE` |

复用 `LAKALA_ORG_CODE`（进件/分账原样使用，不在启动时校验）与 `LAKALA_PLATFORM_RECEIVER_NO`；电子合同的整数 `org_id`
取 `ec-org-id`，未配置时仅当共享机构代码为纯数字才复用，否则列为缺项 `ecOrgId`。曾把"共享机构代码必须是数字"放在启动期，
结果让沿用任意机构代码的既有验收上下文整体起不来——凡是共享配置，只在本功能发起时校验。
最低分账比例**不**沿用 `LAKALA_SPLIT_LOWEST_RATIO`：它是 [0,1) 小数、服务于平台券差额口径，而 1284 与电子合同 E8 要的是百分数，
两者语义不同，故单列一键。任一项缺失时「发起开通」失败关闭并提示具体缺项，不影响启动；JSON 或比例格式非法则启动失败。

## 11. 测试影响

- 协议层：各接口请求报文黄金比对（字段、附件 BASE64、费率、合同参数）、响应与回调解析、两种外壳、未知/驳回分类。
- 服务：MySQL 集成测试覆盖全部状态迁移、幂等、版本冲突、回调验签失败与过期事件、轮询用尽、推进任务重试、ACTIVE 写结算配置。
- 支付门禁：存在开通记录未 ACTIVE 时下单支付失败关闭；ACTIVE 后按写入的商户号/终端号路由。
- HTTP：权限码、`X-Request-Id`、未知字段拒绝、回调白名单。
- 架构门禁、持久层风格检查、契约冒烟、运营网页构建与用例。

## 12. 交付阶段

1. 本 PR：文档、Schema、配置、协议层、状态机、回调、轮询、运营后台接口与页面、测试。
2. 二期 PR：小程序商家自助「开通收款」与法人人脸签入口（另行 CCR 增补契约）。
3. 联调：按 §13.2 逐项验证后修正，未验证前不得在生产发起进件。

## 13. 渠道文档核实结果（2026-11-27，o.lakala.com 在线文档 + 本地归档 PDF）

### 13.1 已核实（文档明示）

- 1020 必传字段与条件必传；附件上传后 24 小时内必须进件；1083 的 `contractStatus` 七个取值及审核通过时的 `merCupNo`/`merInnerNo`/`termDatas`。
- 1284 为人工审核，需 `eleContractNo` 或 `splitEntrustFile`；回调 `auditStatus` 1 通过 / 2 拒绝。1286 可按 `merCupNo` 查 `splitStatus` 与 `bindRelations`。
- 1290 需合作协议附件，回调 `auditStatus` 1/2。订单分账要求商户或接收方至少一方对公结算（分账通流程说明）。
- 1208/1212：`EC011` 清分结算授权委托书为当前最新版本；文本域 E1～E11 见官方填表说明图；`sign_auth_type=WX_APPLET_FACE` 建议必传。
- 未开通分账的交易不得带 `settle_type=1`/`2`，否则不能结算；`splitRange` 默认 `MARK`，需交易再打分账标识。

### 13.2 待核实（联调验证项，未验证前按文档实现）

1. 分账家族请求外壳：官方示例 `version:"2.0"`+`reqTime`，公共参数页写 `version "1.0.0"`；既有接收方链路（#235）用的是 `ver:"1.0.0"`+`timestamp`，从未实测。
2. 进件查询 1082 的"审核通过"示例是 `{code,message,data}` 外壳，与标准 `{retCode,retMsg,respData}` 不同；实现两种都解析。
3. 电子合同申请示例未带 `ver/timestamp/req_id`，签约状态查询示例带；按带实现。`org_id` 与 `org_code` 两处写法不一，按示例 `org_id`。
4. `ec_content_parameters` 的键名是否即文本域编号 `E1`～`E11`；电子合同回调是否带 1009 签名（若不带，仅靠查询兜底）。
5. 进件是否必须携带 `contractNo`（商户入网协议 EC015/EC012）。
6. `splitRange=ALL` 是否适用本平台立项；`settleType=01` 与订单分账并存。
7. 长期执照的 `merBlisExpDt` 写法；对公账户的 `clearingBankCode` 来源（卡 BIN 查询仅支持对私）。
8. 各回调 `retUrl` 的重试策略与超时。

### 13.3 待产品负责人向拉卡拉确认的立项参数

posType、各商家类型 MCC、经营内容、结算周期、费率、最低分账比例、平台接收方是否对公、地区码表。
确认前对应配置留空，「发起开通」失败关闭。

## 14. 风险

- 渠道行为未经实测，联调阶段可能需要调整报文；外壳与解析集中在协议层。
- 拉卡拉人工审核时长不可控，轮询上限约 5 天，超时转人工重新提交。
- 《分账合作协议》模板尚未取得（40 号 §9），一期由运营上传；取得模板前该步无法在生产走通。
- 生产进件会在拉卡拉真实创建商户，联调须用真实测试商家并由产品负责人在场确认。
- 11 号 OpenAPI 未登记本契约与 80 号契约的任何接口（既有缺口，未在本次扩大范围）。
- 运营后台一期按商家 ID 打开开通页，未做开通列表入口（列表接口已有）。
- 部署前提：`78`～`84` 号 Schema 须先于使用开通功能执行（Runbook §3.2 已补）；V44 缺失时只有运营后台开通接口报错，既有商家支付与结算配置编辑不受影响。
