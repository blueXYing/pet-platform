# 商家收单开通接口契约 v0.1（CCR-PAY-ONBOARD-001）

状态：APPROVED（实现中）。依据：[44 号裁决补充](../01-prd/44-商家收单开通拉卡拉进件裁决补充-v1.0.md)、
[40 号裁决补充 §11](../01-prd/40-支付中心订单分账与提现裁决补充-v1.0.md)、[22 号运营权限](../01-prd/22-运营权限人工裁决补充-v1.0.md)、
[CCR-PAY-ONBOARD-001](../../planning/ccr/CCR-PAY-ONBOARD-001.md)。一期为运营后台代填；商家自助接口不在本契约。
本地拉卡拉权威文档目录 `C:\Users\Administrator\Desktop\拉卡拉文档`。

---

## 1. Flyway 隔离迁移编号

统一位于 `backend/pet-boot/src/main/resources/db/settlement-migration/`，与 `docs/03-database/` 的同名 Schema 一字不差。

| 版本 | 内容 | Schema 文件 | 表 |
|---|---|---|---|
| `V44` | 商家收单开通 | `84-Payment-Merchant-Onboarding-Schema-v0.1.sql` | `payment_merchant_onboarding`, `payment_merchant_onboarding_audit` |

## 2. RBAC 动作码

- `settlement.onboarding.write`：代填/修改开通资料、上传附件、发起开通、同步、重新提交、补录进件 ID、确认拉卡拉未受理。
- `settlement.onboarding.read-sensitive`：按原因查看开通资料明文（写不可变审计）。
- `settlement.report.read`（沿用）：开通列表、详情、审计记录（脱敏）。

超管默认拥有全部已部署动作码（SSOT §二十四）；其他账号按通用 RBAC 授权。

## 3. 通用规范

- 所有 ID 为十进制 `String`；日期 `yyyy-MM-dd`；时间 UTC ISO-8601 毫秒；响应信封沿用 `ApiResponse`：`{"success":true,"code":"SUCCESS","message":"OK","data":...,"traceId":"..."}`。
- 所有运营写接口必填请求头 `X-Request-Id`（完整 UUID），未知字段拒绝，`Cache-Control: no-store, private`。
- 带 `expectedVersion` 的写接口在版本不一致时返回 `409`；同 requestId 重放返回原结果，同键异参返回 `409`。
- 状态不允许的动作返回 `409`，`message` 说明当前状态与可执行动作。

## 4. 运营网页 HTTP（`/api/v1/admin/settlement/onboardings`）

仅在 `pet.auth.admin.enabled=true` 且 `pet.payment.enabled=true` 时装配。

### 4.1 平台级默认值与准入 `GET /{merchantId}/defaults`

权限：`settlement.report.read` 或 `settlement.onboarding.write`。

```json
{
  "merchantId": "7103000000000001",
  "eligible": true,
  "ineligibleReason": null,
  "merchantName": "示例宠物医院",
  "merchantTypeCode": "PET_HOSPITAL",
  "cityCode": "chengdu",
  "districtCode": "510104",
  "configReady": false,
  "missingConfig": ["posType", "feeRates", "splitLowestRatio", "platformReceiverNo", "notifyBaseUrl"],
  "platform": {
    "posType": null, "merBusiContent": "654", "settlePeriod": "T+1",
    "mccCode": "0742", "feeRates": [], "splitLowestRatio": null, "platformReceiverConfigured": false
  }
}
```

`ineligibleReason` 取值：`MERCHANT_NOT_FOUND`、`MERCHANT_NOT_ACTIVE`、`APPLICATION_NOT_APPROVED`、`AGREEMENT_NOT_SIGNED`。
`districtCode` 来自城市码映射，未配置时为 `null`，由运营手工填写。`mccCode` 取商家类型映射，无映射取默认值。

### 4.2 列表 `GET /?merchantId=&status=&page=&size=`

权限：`settlement.report.read`。返回 `{"items":[Summary],"total","page","size"}`，按 `updated_at` 倒序。

```json
{"onboardingId":"…","merchantId":"…","status":"EC_SIGNING","merCupNo":null,"termNo":null,
 "rejectReason":null,"submittedAt":"…","completedAt":null,"updatedAt":"…"}
```

### 4.3 详情 `GET /{merchantId}`

权限：`settlement.report.read`。无记录返回 `404`。

```json
{
  "onboardingId": "…", "merchantId": "…", "status": "EC_SIGNING", "version": 3,
  "merchant": {
    "merchantTypeCode": "PET_HOSPITAL", "merRegName": "示例宠物医院", "merBizName": null,
    "merRegDistCode": "510104", "merRegAddr": "天府大道1号",
    "merBlisName": "示例宠物医院", "merBlisNoMasked": "9111****XX", "merBlisStDt": "2020-01-01", "merBlisExpDt": "2040-01-01",
    "larName": "张*", "larIdType": "01", "larIdcardMasked": "3425****21", "larIdcardStDt": "1999-05-29", "larIdcardExpDt": "2039-05-29",
    "larMobileMasked": "138****1234", "contactName": "李*", "contactMobileMasked": "139****0000"
  },
  "settlement": {
    "acctTypeCode": "58", "acctName": "张*", "acctNoMasked": "6212****0000", "acctNoLast4": "0000",
    "acctIdType": null, "acctIdcardMasked": null, "acctIdExpDt": null,
    "openningBankCode": "102100000030", "openningBankName": "中国工商银行股份有限公司北京市分行营业部", "clearingBankCode": "102100099996"
  },
  "platform": {
    "posType": "…", "mccCode": "0742", "merBusiContent": "654", "settlePeriod": "T+1",
    "feeRates": [{"feeRateTypeCode":"302","feeRateTypeName":"微信","feeRatePct":"0.60"}], "splitLowestRatio": "90.00"
  },
  "attachments": {
    "FR_ID_CARD_FRONT": "…", "FR_ID_CARD_BEHIND": "…", "BANK_CARD": "…", "BUSINESS_LICENCE": "…",
    "MERCHANT_PHOTO": "…", "SHOPINNER": "…", "ID_CARD_FRONT": null, "ID_CARD_BEHIND": null, "NO_LEGAL_PERSON_SETT_AUTH_LETTER": null
  },
  "cooperationAssetId": "…",
  "steps": [
    {"step":"EC","status":"WAITING_SIGN","submittedAt":"…","finishedAt":null,"summary":"待商家法人签署","reason":null},
    {"step":"CONTRACT","status":"NONE","submittedAt":null,"finishedAt":null,"summary":null,"reason":null},
    {"step":"LEDGER","status":"NONE","submittedAt":null,"finishedAt":null,"summary":null,"reason":null},
    {"step":"BIND","status":"NONE","submittedAt":null,"finishedAt":null,"summary":null,"reason":null}
  ],
  "ecSignUrl": "https://…",
  "ecNo": null, "contractId": null, "merCupNo": null, "termNo": null,
  "rejectReason": null, "lastErrorCode": null, "lastErrorMessage": null,
  "allowedActions": ["SAVE_PROFILE"],
  "submittedAt": "…", "completedAt": null, "createdAt": "…", "updatedAt": "…"
}
```

- `steps[].status`：EC 为 `NONE | WAITING_SIGN | DONE`；其余为 `NONE | APPLYING | UNKNOWN | REJECTED | DONE`（`UNKNOWN` 仅 CONTRACT）。
- `ecSignUrl` 仅在 `EC_SIGNING` 返回；`ecNo`、`contractId`、`merCupNo`、`termNo` 有值即返回。
- `allowedActions` ⊂ `SAVE_PROFILE | SUBMIT | SYNC | RETRY | ADOPT_CONTRACT | DISCARD_CONTRACT`，由服务端按状态计算。

### 4.4 保存资料 `PUT /{merchantId}`

权限：`settlement.onboarding.write`。允许状态：无记录、`DRAFT`、`EC_SIGNING`（回到 DRAFT 并作废电子合同）、`CONTRACT_REJECTED`（回到 DRAFT）。

```json
{
  "expectedVersion": 0,
  "merchant": {
    "merRegName": "示例宠物医院", "merBizName": null, "merRegDistCode": "510104", "merRegAddr": "天府大道1号", "mccCode": "0742",
    "merBlisName": "示例宠物医院", "merBlisNo": "91110108MA2G7LK368", "merBlisStDt": "2020-01-01", "merBlisExpDt": "2040-01-01",
    "larName": "张三", "larIdcard": "342523199905298212", "larIdcardStDt": "1999-05-29", "larIdcardExpDt": "2039-05-29",
    "larMobile": "13800001234", "contactName": "李四", "contactMobile": "13900000000"
  },
  "settlement": {
    "acctTypeCode": "58", "acctName": "张三", "acctNo": "6212260200102400000",
    "acctIdType": null, "acctIdcard": null, "acctIdExpDt": null,
    "openningBankCode": "102100000030", "openningBankName": "中国工商银行股份有限公司北京市分行营业部", "clearingBankCode": "102100099996"
  },
  "attachments": {
    "FR_ID_CARD_FRONT": "…", "FR_ID_CARD_BEHIND": "…", "BANK_CARD": "…", "BUSINESS_LICENCE": "…",
    "MERCHANT_PHOTO": "…", "SHOPINNER": "…", "ID_CARD_FRONT": null, "ID_CARD_BEHIND": null, "NO_LEGAL_PERSON_SETT_AUTH_LETTER": null
  },
  "cooperationAssetId": "…",
  "reason": "商家线下提供结算资料"
}
```

校验（任一不满足 `400`）：

| 字段 | 规则 |
|---|---|
| `merRegName` | 2～80 字；须与 `merBlisName` 一致 |
| `merBizName` | 可空；非空 2～64 字 |
| `merRegDistCode` | 6～8 位数字 |
| `merRegAddr` | 6～200 字 |
| `mccCode` | 可空（取平台映射）；非空为 4 位数字 |
| `merBlisName` | 2～80 字 |
| `merBlisNo` | 8～40 位数字与大写字母 |
| `merBlisStDt` / `merBlisExpDt` | 起止有序；截止不早于当日（长期执照填拉卡拉确认的换算日期） |
| `larName` | 2～20 个汉字 |
| `larIdcard` | 15 或 18 位大陆居民身份证并通过校验位 |
| `larIdcardStDt` / `larIdcardExpDt` | 起止有序；截止不早于当日 |
| `larMobile` | 11 位手机号 |
| `contactName` | 2～20 个汉字 |
| `contactMobile` | 11 位手机号 |
| `acctTypeCode` | `57` 对公 / `58` 对私 |
| `acctName` | 2～40 字；`57` 时须等于 `merRegName` |
| `acctNo` | 8～40 位数字 |
| `acctIdType` / `acctIdcard` / `acctIdExpDt` | 三者同空（同法人）或同非空；`acctIdType` 固定 `01`；`acctIdcard` 同身份证校验且不得等于 `larIdcard` |
| `openningBankCode` / `clearingBankCode` | 6～20 位数字或字母 |
| `openningBankName` | 2～40 字 |
| `attachments` | 六个必传类型为非空素材 ID；`58` 且结算人非法人时另需 `ID_CARD_FRONT`、`ID_CARD_BEHIND`、`NO_LEGAL_PERSON_SETT_AUTH_LETTER`；其余键须为空或缺省 |
| `cooperationAssetId` | 可空；非空为素材 ID |
| `reason` | 2～256 字，写审计 |

保存不调用拉卡拉。平台级参数（posType、经营内容、结算周期、费率、最低分账比例）在"发起开通"时按当时配置固化，保存时只校验 MCC/地区码。
返回：详情（§4.3）。

### 4.5 发起开通 `POST /{merchantId}/submit`

权限：`settlement.onboarding.write`。请求体 `{"expectedVersion": 3}`。允许状态：`DRAFT`。

服务端依次：校验商家准入（同 §4.1 的 `eligible`）→ 校验平台级配置齐全 → 固化平台级参数快照 → 调拉卡拉电子合同申请 →
`EC_SIGNING` 并保存 `ecSignUrl`。配置缺失 `503`，`message` 列出缺项；商家不准入 `409`；电子合同申请被拒 `409`（附拉卡拉原因）；
申请结果未知 `503` 且状态回到 `DRAFT`。返回：详情（§4.3）。之后各步由系统自动推进，无需运营操作。

### 4.6 同步 `POST /{merchantId}/sync`

权限：`settlement.onboarding.write`。无请求体或 `{}`。允许状态：四个等待/未知态（`EC_SIGNING`、`CONTRACT_APPLYING`、`LEDGER_APPLYING`、`BIND_APPLYING`）。
立即执行一次当前步的渠道查询并按状态机推进；查询不可用返回 `503`，状态不变。返回：详情（§4.3）。

### 4.7 重新提交 `POST /{merchantId}/retry`

权限：`settlement.onboarding.write`。请求体 `{"expectedVersion": 5, "cooperationAssetId": "…", "reason": "已更换合作协议"}`（后两项可空）。
允许状态：`LEDGER_REJECTED`、`BIND_REJECTED`。`BIND_REJECTED` 可同时更换 `cooperationAssetId`。返回：详情（§4.3）。

### 4.8 补录进件 ID `POST /{merchantId}/contract/adopt`

权限：`settlement.onboarding.write`。请求体 `{"expectedVersion": 4, "contractId": "202104062502898611", "reason": "已与拉卡拉核实进件已受理"}`。
允许状态：`CONTRACT_UNKNOWN`。进入 `CONTRACT_APPLYING` 并按该进件 ID 查询。返回：详情（§4.3）。

### 4.9 确认拉卡拉未受理 `POST /{merchantId}/contract/discard`

权限：`settlement.onboarding.write`。请求体 `{"expectedVersion": 4, "reason": "拉卡拉确认无此进件"}`（原因 2～256 字，必填）。
允许状态：`CONTRACT_UNKNOWN`。回到 `DRAFT`，保留资料，清空本次进件结果。返回：详情（§4.3）。

### 4.10 查看明文 `POST /{merchantId}/reveal`

权限：`settlement.onboarding.read-sensitive`。请求体 `{"reason": "商家来电核对卡号"}`。返回：

```json
{"merchantId":"…","merBlisNo":"…","larName":"…","larIdcard":"…","larMobile":"…","contactName":"…","contactMobile":"…",
 "acctName":"…","acctNo":"…","acctIdType":null,"acctIdcard":null}
```

### 4.11 审计记录 `GET /{merchantId}/audits`

权限：`settlement.report.read`。按时间正序，最多 200 条：

```json
[{"auditId":"…","actionType":"CONTRACT_REJECTED","operatorType":"SYSTEM","operatorId":null,"reason":null,
  "detail":{"contractStatus":"INNER_CHECK_REJECTED"},"createdAt":"…"}]
```

### 4.12 运营上传附件 `POST /{merchantId}/assets`

权限：`settlement.onboarding.write`。`multipart/form-data`，仅一个文件部件 `file`；`image/jpeg`、`image/png`，≤5MB。
素材归属该商家主体用户，用途固定 `MERCHANT_ONBOARDING_ATTACHMENT`，经既有私有素材安全链（扫描、规范化）后 `READY`。
商家不存在或不准入返回 `409`。返回 `{"assetId":"…","mediaType":"image/jpeg","bytes":123456}`。

---

## 5. 拉卡拉回调（公共，1009 验签）

基址 `/api/v1/callbacks/lakala/onboarding`，仅 `pet.payment.enabled=true` 时加入回调安全链白名单（仅 POST，其余拒绝）。
上送拉卡拉的 `retUrl` = `pet.payment.onboarding.notify-base-url` + 下列路径。请求体上限 65,536 字节。

| 路径 | 来源 | 业务键 | 处理 |
|---|---|---|---|
| `/ec` | 电子合同签约结果 | `orderNo` = `ec_order_no`（或 `ecApplyId`） | `ecStatus=COMPLETED` 且 `ecNo` 非空 → 记 `ec_no`，投递进件推进任务 |
| `/contract` | 进件回调 1083 | `contractId` | `WAIT_FOR_CONTACT` 且商户号、终端号齐全 → 记录并投递开通分账推进任务；`INNER_CHECK_REJECTED`/`COMMIT_FAIL` → `CONTRACT_REJECTED` |
| `/ledger-open` | 开通分账审核 1284 | `merCupNo`（有 `applyId` 时同时比对） | `auditStatus=1` → 投递绑定推进任务；`2` → `LEDGER_REJECTED` |
| `/bind` | 绑定审核 1290 | `applyId`、`receiverNo` | `auditStatus=1` → `ACTIVE`；`2` → `BIND_REJECTED` |

报文示例（以拉卡拉文档为准；`data`/`respData`/`dataPacket.data` 包裹与平铺两种形态都解析）：

```json
{"ecApplyId":"635798487769907200","ecName":"清分结算授权委托书","ecNo":"QT20221021000157861","ecStatus":"COMPLETED","orderNo":"…","orgId":"1","version":"1.0"}
```
```json
{"retUrl":"…","dataPacket":{"code":"000000","message":"成功","data":{"orgCode":"1","orderNo":"…","contractId":"202104062502898611",
 "contractStatus":"WAIT_FOR_CONTACT","contractMemo":"","merInnerNo":"4002021040692311689","merCupNo":"82229007011009E",
 "termDatas":[{"shopId":"…","termId":"…","termNo":"B0100551","busiTypeName":"扫码","productName":"云分销","productCode":"CLOUD_DISTRIBUTION"}]}}}
```
```json
{"applyId":"681201215598657536","merCupNo":"82229007011009E","merInnerNo":"…","auditStatus":"1","auditStatusText":"审核通过","remark":"审核通过"}
```
```json
{"respData":{"optType":"ADD","applyId":"547055274822594560","merCupNo":"…","merInnerNo":"…","receiverNo":"SR2022021813005",
 "auditStatus":"1","auditStatusText":"审核通过","remark":"通过"}}
```

响应：验签或报文非法 `400 {"code":"FAIL","message":"验签或报文校验失败"}`；找不到对应记录 `404`；状态冲突 `409`；
落库成功 `200 {"code":"SUCCESS","message":"执行成功"}`；处理异常 `503`。重复、过期或业务键不匹配的事件返回 `200` 并只记审计。

---

## 6. 定时任务

| 任务类型 | 键 | 作用 |
|---|---|---|
| `MERCHANT_ONBOARDING_ADVANCE` | `…:{merchantId}` | 在事务外执行下一步渠道调用（进件、开通分账、绑定）；10s/30s/2m/10m 重试，最多 6 次 |
| `MERCHANT_ONBOARDING_POLL` | `…:{merchantId}` | 当前等待态的渠道查询；1m/5m/15m/1h/6h，最多 20 次 |

## 7. 配置

见 CCR §10。所有键位于 `pet.payment.onboarding`，仅 `application.yml` 声明并经环境变量绑定。

## 8. 关联契约变化

- 80 号 §4.1：存在开通记录的商家，`PUT /configs/{merchantId}` 的 `lakalaMerchantNo`/`lakalaTermNo` 须为空或与现值相同，否则 `409`。
- 31 号私有素材：新增用途 `MERCHANT_ONBOARDING_ATTACHMENT`，仅允许 `PLATFORM_OPERATOR` 上传、归属商家主体用户，其余安全链不变。
- 商家域内部 API：新增 `MerchantAcquiringFactsApi`（商家档案、入驻审核、协议签署、类型、城市、主体用户），不新增 HTTP。
