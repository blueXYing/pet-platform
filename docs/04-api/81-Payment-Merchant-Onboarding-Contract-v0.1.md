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
    "merBlisName": "示例宠物医院", "merBlisNoMasked": "9111****68", "merBlisStDt": "2020-01-01", "merBlisExpDt": "2040-01-01",
    "larName": "*三", "larIdType": "01", "larIdcardMasked": "342****8212", "larIdcardStDt": "1999-05-29", "larIdcardExpDt": "2039-05-29",
    "larMobileMasked": "138****1234", "contactName": "*四", "contactMobileMasked": "139****0000"
  },
  "settlement": {
    "acctTypeCode": "58", "acctName": "*三", "acctNoMasked": "6212****0000", "acctNoLast4": "0000",
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
- `allowedActions` ⊂ `SAVE_PROFILE | SUBMIT | SYNC | RETRY | ADOPT_CONTRACT | DISCARD_CONTRACT`，由服务端按状态计算：
  `DRAFT → SAVE_PROFILE, SUBMIT`；`EC_SIGNING → SAVE_PROFILE, SYNC`；`CONTRACT_APPLYING / LEDGER_APPLYING / BIND_APPLYING → SYNC`；
  `CONTRACT_UNKNOWN → ADOPT_CONTRACT, DISCARD_CONTRACT`；`CONTRACT_REJECTED → SAVE_PROFILE`；`LEDGER_REJECTED / BIND_REJECTED → RETRY`；`ACTIVE → []`。
- `lastErrorCode` 取值：`EC_REJECTED`、`EC_OUTCOME_UNKNOWN`、`EC_SIGN_TIMEOUT`、`ADD_MER_OUTCOME_UNKNOWN`、`ADD_MER_REJECTED`、
  `LEDGER_OUTCOME_UNKNOWN`、`BIND_OUTCOME_UNKNOWN`、`UPLOAD_REJECTED`、`ADVANCE_FAILED`、`POLL_REJECTED`。

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

校验（任一不满足 `400`；长度上限取拉卡拉 1020/1208 的字段上限）：

| 字段 | 规则 |
|---|---|
| `merRegName` | 4～80 字；须与 `merBlisName` 一致 |
| `merBizName` | 可空；非空 4～64 字 |
| `merRegDistCode` | 6～8 位数字 |
| `merRegAddr` | 6～80 字 |
| `mccCode` | 可空（发起时取平台映射）；非空为 4 位数字 |
| `merBlisName` | 4～80 字 |
| `merBlisNo` | 8～40 位数字或字母，服务端统一转大写 |
| `merBlisStDt` / `merBlisExpDt` | 起止有序；截止不早于当日（长期执照填拉卡拉确认的换算日期） |
| `larName` | 2～20 个汉字（可含间隔号 `·`） |
| `larIdcard` | 15 或 18 位大陆居民身份证；18 位须出生日期真实且校验位正确（尾号 `x` 小写可） |
| `larIdcardStDt` / `larIdcardExpDt` | 起止有序；截止不早于当日 |
| `larMobile` | 11 位手机号（电子合同的签约手机号） |
| `contactName` | 2～20 个汉字（可含间隔号 `·`） |
| `contactMobile` | 11 位手机号 |
| `acctTypeCode` | `57` 对公 / `58` 对私 |
| `acctName` | 2～40 字；`57` 时须等于 `merRegName` |
| `acctNo` | 8～40 位数字 |
| `acctIdType` / `acctIdcard` / `acctIdExpDt` | 三者同空（结算人为法人本人）或同非空（非法人结算，仅 `58` 可用）；`acctIdType` 固定 `01`；`acctIdcard` 同身份证校验、不得等于 `larIdcard` 且未过期 |
| `openningBankCode` / `clearingBankCode` | 6～20 位数字或字母 |
| `openningBankName` | 2～40 字 |
| `attachments` | 键限九种类型；六个必传类型为非空素材 ID；`58` 且结算人非法人时另需 `ID_CARD_FRONT`、`ID_CARD_BEHIND`、`NO_LEGAL_PERSON_SETT_AUTH_LETTER`；结算人为法人本人时这三键必须为空或缺省 |
| `cooperationAssetId` | 可空；非空为 `^[1-9][0-9]{0,18}$` 的素材 ID |
| `reason` | 2～256 字，写审计 |

素材 ID 一律为 `^[1-9][0-9]{0,18}$`（十进制字符串）。附件是整体替换：请求里为空或缺失的类型即清除。

**留空保持原值**：编辑已有记录时（请求的 `expectedVersion` 对应一条已存在的记录），下列敏感字段传 `null`、空串或缺省表示保持库中原值，填写则覆盖：
`merBlisNo`、`larName`、`larIdcard`、`larMobile`、`contactName`、`contactMobile`、`acctName`、`acctNo`、`acctIdcard`、`acctIdExpDt`
（后两项仅当库中已有结算人资料且请求带 `acctIdType="01"` 时生效）。新开通（尚无记录）时这些字段全部必填。
服务端先用库中原值补齐再校验，所以保持原值的字段同样受上表约束。

保存不调用拉卡拉。平台级参数（posType、经营内容、结算周期、费率、最低分账比例）在"发起开通"时按当时配置固化，保存时只校验 MCC/地区码。
保存后记录回到 `DRAFT`，此前已申请的电子合同与进件结果全部作废。返回：详情（§4.3）。

### 4.5 发起开通 `POST /{merchantId}/submit`

权限：`settlement.onboarding.write`。请求体 `{"expectedVersion": 3}`。允许状态：`DRAFT`。

前置：商家准入（同 §4.1 的 `eligible`）、平台级配置齐全、记录已保存《分账合作协议》附件（`cooperationAssetId` 非空）。
服务端依次：固化平台级参数快照（MCC 取商家类型映射或记录上的覆盖值）→ 调拉卡拉电子合同申请 → `EC_SIGNING` 并保存 `ecSignUrl`。

| 结果 | HTTP | 记录状态 |
|---|---|---|
| 申请受理 | 200 | `EC_SIGNING` |
| 配置缺失 / 合作协议未上传 | `400` / `503`，`message` 列出缺项 | `DRAFT` |
| 商家不准入 | 409 | `DRAFT` |
| 拉卡拉拒绝申请 | 409，`message` 附拉卡拉原因 | `DRAFT`，`lastErrorCode=EC_REJECTED` |
| 申请结果未知（超时/断连） | 503 | `DRAFT`，`lastErrorCode=EC_OUTCOME_UNKNOWN` |

返回：详情（§4.3）。之后各步由系统自动推进，无需运营操作：

- 法人签署完成（回调或查询）→ 系统上传附件并提交进件 → `CONTRACT_APPLYING`；进件被拉卡拉同步拒绝 → 回 `DRAFT`（`lastErrorCode=ADD_MER_REJECTED`）；
  进件结果未知 → `CONTRACT_UNKNOWN`（系统绝不自动重发进件，避免在拉卡拉重复建商户）。
- 进件审核通过并返回银联商户号与终端号 → 系统提交开通分账 → 开通通过 → 系统上传合作协议并提交绑定 → `ACTIVE`。
- 开通分账或绑定的提交结果未知时状态保持 `*_APPLYING`（`lastErrorCode=LEDGER_OUTCOME_UNKNOWN` / `BIND_OUTCOME_UNKNOWN`），
  由定时查询按商户号向拉卡拉核实，系统不自动重发；运营「同步」只触发查询。
- 每个等待态的查询用尽（约 5 天）仍未有结论：签署超时回 `DRAFT`（`EC_SIGN_TIMEOUT`），其余进入对应的 `*_REJECTED`（原因"等待…超时"）。

### 4.6 同步 `POST /{merchantId}/sync`

权限：`settlement.onboarding.write`。无请求体或 `{}` 均可（不要求 JSON 内容类型）。允许状态：`EC_SIGNING`、`CONTRACT_APPLYING`、`LEDGER_APPLYING`、`BIND_APPLYING`。
当前步已提交的，立即向拉卡拉查询一次并按状态机推进；当前步尚待提交的（上一次推进失败），重新执行一次提交。
渠道不可用返回 `503`，状态不变。返回：详情（§4.3）。

### 4.7 重新提交 `POST /{merchantId}/retry`

权限：`settlement.onboarding.write`。请求体 `{"expectedVersion": 5, "cooperationAssetId": "…", "reason": "已更换合作协议"}`（后两项可空）。
允许状态：`LEDGER_REJECTED`、`BIND_REJECTED`。返回后记录立即回到对应的 `*_APPLYING`，由推进任务提交一次新申请（新流水号）；
`BIND_REJECTED` 可同时更换 `cooperationAssetId`。返回：详情（§4.3）。

### 4.8 补录进件 ID `POST /{merchantId}/contract/adopt`

权限：`settlement.onboarding.write`。请求体 `{"expectedVersion": 4, "contractId": "202104062502898611", "reason": "已与拉卡拉核实进件已受理"}`。
`contractId` 为 1～32 位数字或字母；`reason` 必填 2～256 字。允许状态：`CONTRACT_UNKNOWN`。进入 `CONTRACT_APPLYING` 并按该进件 ID 查询。返回：详情（§4.3）。

### 4.9 确认拉卡拉未受理 `POST /{merchantId}/contract/discard`

权限：`settlement.onboarding.write`。请求体 `{"expectedVersion": 4, "reason": "拉卡拉确认无此进件"}`（原因 2～256 字，必填）。
允许状态：`CONTRACT_UNKNOWN`。回到 `DRAFT`，保留资料，清空本次进件与电子合同结果。返回：详情（§4.3）。

### 4.10 查看明文 `POST /{merchantId}/reveal`

权限：`settlement.onboarding.read-sensitive`。请求体 `{"reason": "商家来电核对卡号"}`（2～256 字，必填）。
同一 `X-Request-Id` 不可重复查看（`409 IDEMPOTENCY_KEY_CONFLICT`），每次查看写一条不可变审计。返回：

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

基址 `/api/v1/callbacks/lakala/onboard`，仅 `pet.payment.enabled=true` 时加入回调安全链白名单（仅 POST，其余拒绝）。
上送拉卡拉的 `retUrl` = `pet.payment.onboarding.notify-base-url` + 下表路径；进件回调打在基址本身（1020 的 `retUrl` 上限 64 字符）。
请求体上限 65,536 字节。服务未装配时一律 `503`（失败关闭）。

| 路径 | 来源 | 定位记录 | 处理 |
|---|---|---|---|
| （基址本身） | 进件回调 1083 | `contractId` | `WAIT_FOR_CONTACT` 且商户号、终端号齐全 → 记录并投递开通分账推进任务；`COMMIT_FAIL`/`INNER_CHECK_REJECTED` → `CONTRACT_REJECTED`；其它状态只更新 `contract_status` |
| `/ec` | 电子合同签约结果 1208 | `ecApplyId`，或 `orderNo` = 电子合同流水号 | `ecStatus=COMPLETED` 且 `ecNo` 非空 → 记 `ec_no` 并投递进件推进任务；未完成只记审计 |
| `/ledger-open` | 开通分账审核 1284 | `applyId`，或 `merCupNo` | `auditStatus=1` → `BIND_APPLYING` 并投递绑定推进任务；`2` → `LEDGER_REJECTED` |
| `/bind` | 绑定审核 1290 | `applyId`，或 `merCupNo`；`receiverNo` 必须等于平台接收方编号 | `auditStatus=1` → `ACTIVE`（写结算配置）；`2` → `BIND_REJECTED` |

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

响应：验签或报文非法 `400 {"code":"FAIL","message":"验签或报文校验失败"}`；找不到对应记录（或绑定回调的接收方不是本平台的）`404`；
落库成功 `200 {"code":"SUCCESS","message":"执行成功"}`；处理异常 `503`。
重复、过期（记录已不在对应状态）或与本次申请不匹配的事件返回 `200`，只写一条审计（`*_NOTIFY_IGNORED` / `*_NOTIFY_PENDING`），不改状态。

回调只负责"改状态 + 记审计 + 投递推进任务"；下一步的拉卡拉调用由推进任务在请求线程和数据库事务之外执行。
进件回调到达时若记录已被运营改回 `DRAFT`，仍以拉卡拉结果为准进入 `CONTRACT_APPLYING`（商户可能已在拉卡拉创建，不能丢失）。

---

## 6. 定时任务

| 任务类型 | 键 | 作用 |
|---|---|---|
| `MERCHANT_ONBOARDING_ADVANCE` | `…:{merchantId}:v{记录版本}` | 在事务外执行下一步渠道调用（进件、开通分账、绑定）；10s/30s/2m/10m 重试，最多 6 次 |
| `MERCHANT_ONBOARDING_POLL` | `…:{merchantId}:v{记录版本}` | 当前等待态的渠道查询（电子合同 1212、进件 1082、分账 1286、绑定 1872）；1m/5m/15m/1h/6h，最多 20 次 |

任务键带记录版本号（每次状态流转都会先保存并递增版本），因此新一轮申请不会与上一轮已结束的任务冲突。
推进任务重试耗尽后记录保留 `lastErrorCode=ADVANCE_FAILED` / `UPLOAD_REJECTED`，运营可点「同步」重新驱动；
查询任务耗尽即 §4.5 的超时处理。

## 7. 配置

统一前缀 `pet.payment.onboarding`，仅在 `application.yml` 声明并经环境变量绑定（CCR §10）：

| 键 | 环境变量 | 说明 |
|---|---|---|
| `notify-base-url` | `LAKALA_ONBOARDING_NOTIFY_BASE_URL` | 回调基址；基址本身须 ≤64 字符 |
| `pos-type` / `mer-busi-content` / `settle-period` | `LAKALA_ONBOARDING_POS_TYPE` / `_MER_BUSI_CONTENT` / `_SETTLE_PERIOD` | 立项字典值 |
| `fee-rates-json` | `LAKALA_ONBOARDING_FEE_RATES_JSON` | 进件费率集合 JSON 数组 |
| `mcc-default` / `mcc-by-merchant-type-json` | `LAKALA_ONBOARDING_MCC_DEFAULT` / `_MCC_BY_TYPE_JSON` | MCC 与按商家类型映射 |
| `district-by-city-json` | `LAKALA_ONBOARDING_DISTRICT_BY_CITY_JSON` | 平台城市码 → 拉卡拉地区码 |
| `lowest-ratio-percent` | `LAKALA_ONBOARDING_LOWEST_RATIO` | 委托书"本商户最低需分得"比例，百分数（如 `90` 或 `90.50`），0～100 且最多两位小数 |
| `cooperation-agreement-name` | `LAKALA_ONBOARDING_COOPERATION_AGREEMENT_NAME` | 电子合同 E2：平台与商家合作协议名称 |
| `platform-company-name` | `LAKALA_ONBOARDING_PLATFORM_NAME` | 电子合同 E1/E9：平台公司全称 |
| `ec-org-id` | `LAKALA_ONBOARDING_EC_ORG_ID` | 电子合同的数字机构号（1208 的 `org_id` 为整数）；留空时复用数字形式的 `LAKALA_ORG_CODE` |

复用 `LAKALA_ORG_CODE`（进件与分账原样使用，不做数字校验）与 `LAKALA_PLATFORM_RECEIVER_NO`。
电子合同要求的整数机构号取 `ec-org-id`，未配置时仅当 `LAKALA_ORG_CODE` 为纯数字才可复用，否则「发起开通」缺项列为 `ecOrgId`；
这一点绝不在启动时校验，不影响其它功能启动。最低分账比例不沿用 `LAKALA_SPLIT_LOWEST_RATIO`：
后者是 [0,1) 小数、服务于平台券差额口径，而 1284 与电子合同要的是百分数。任一项缺失或格式非法时「发起开通」失败关闭并提示具体缺项，不影响启动；
JSON 或比例格式错误则启动失败。

## 8. 关联契约变化与部署前提

- 80 号 §4.1：存在开通记录的商家，`PUT /configs/{merchantId}` 不得把 `lakalaMerchantNo`/`lakalaTermNo` 改成其它值（`409`）；未传时保留系统写入的值。
- 支付前置：存在开通记录且状态不是 `ACTIVE` 的商家，订单支付准备失败关闭（`COMMON_DEPENDENCY_UNAVAILABLE`，"商家收单开通尚未完成，暂不能支付"）。
  没有开通记录的商家沿用既有行为；V44 尚未执行时不存在开通记录，门禁不生效、不影响既有商家支付
  （此时运营后台的开通接口会报错，直到执行 V44）。
- 31 号私有素材：新增用途 `MERCHANT_ONBOARDING_ATTACHMENT`，仅允许 `PLATFORM_OPERATOR` 上传、归属商家主体用户，其余安全链不变。
- 商家域内部 API：新增 `MerchantAcquiringFactsApi`（商家档案、入驻审核、协议签署、类型、城市、主体用户），不新增 HTTP。
- RBAC：新增动作码 `settlement.onboarding.write`、`settlement.onboarding.read-sensitive`；超管默认拥有。
- 部署：先执行 `78`～`84` 号 Schema（Runbook §3.2），再部署本版本；回调基址须外网可达并加入既有网关白名单。
- 未同步项（如实披露）：11 号 OpenAPI 未登记本契约与 80 号契约的任何接口（既有缺口）；二期商家自助接口另行增补。
