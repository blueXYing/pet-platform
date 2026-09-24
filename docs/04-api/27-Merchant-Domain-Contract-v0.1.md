# 商家域接口契约补充 v0.1

状态：ACCEPTED_CONTRACT / NOT_IMPLEMENTED。2026-09-17用户依次确认协议换版、下线语义及“技术契约 确认”。来源为PR50提案及[批准回执](../../planning/ccr/CCR-W2-API-001/merchant-product-decisions.md)。

本补充将获批字段/接口/身份映射与存储语义纳入权威契约，优先于07/10中相关未细化或旧外部签约描述；产品规则以SSOT为准。不是生产实现或完整MER-001验收。OWNER使用USER+实际userId只用于本域普通管理和签约；不解除核销staff身份的独立缺口。

OpenAPI11同步员工六操作与协议两操作。成员事实沿用既有AUTH接口；完整申请/审核、成员绑定、员工停用的订单守卫及治理范围未完整冻结处继续保留依赖。存储映射是已批准逻辑/物理字段设计，不是可直接执行的DDL或生产迁移。Event08载荷与Scheduler不变。

已确认产品规则：版本内容不可覆盖；新版本供首次签署，已签商家保持原效力，V1不新增强制重签；下线停止新经营并保留存量履约、退款售后，冻结写动作另待裁决。

## 1. 本阶段范围与后续交付

| 阶段 | 可以交付的范围 | 进入实现前条件 |
|---|---|---|
| S1 当前 | 字段/内部 API/HTTP 已批准、事实来源、存储映射、错误/幂等/验收矩阵 | 当前用户推进与技术契约授权已满足；批准回执保留planning，正式规范同步docs |
| S2 领域基础 | merchant/store/staff 查询、归属、新单资格；已有事实的 MyBatis XML 持久化及隔离 MySQL 测试 | 本契约已批准并同步；真实资格事实完整；不使用 `ACTIVE=审核已过且已签` 推断 |
| S3 成员与签约接入 | 主账号同意协议、成员事实供 AUTH 消费、已批员工管理写操作 | 已批准协议换版规则、申请审核事实和成员持久化契约同步、AUTH 当前会话及动作鉴权接入 |
| S4 下线与完整验收 | 获权治理命令、同事务 MerchantDisabledEvent、员工停用/存量例外联动 | ADM 权限与商家归属交接、Outbox接入、订单指派/在途检查契约及并发方案、已批准下线存量例外 |

阶段顺序不新增 Issue，不把 S1/S2 视为 MER-001 DONE。C/M/A 页面、完整入驻材料/OCR、运营审核页面、服务/排期/订单实现仍由原 Issue 负责。SVC 可消费获批 MER API，不能读 merchant Repository。

## 2. 已有事实与责任边界

- SSOT §13：用户不能选人；服务人员由商家指派。§15：下线停止新订单，不自动取消存量订单。§26与25号补充：电子协议、主账号同意、四态准入、无外部签约通道。
- Schema06 的 `merchant`、`merchant_store`、`merchant_staff` 是已有实体；现有列不足以证明审核、协议同意或登录成员关系。`provider_merchant_no` 不作签约成功依据，不为电子协议写入虚构渠道号。
- `merchant_staff` 表示服务人员岗位；即使有相同手机号，也不代表其有登录、核销或管理员权限。成员 userId 必须来自 AUTH 验证和获批的绑定流程。
- 商家域拥有申请/签约/成员/门店/服务人员事实；AUTH拥有会话、可信用户与授权组合；order拥有订单快照、指派与存量责任，schedule拥有时间冲突和可用人数；admin拥有运营身份和动作/数据范围。
- 无跨 biz 依赖和跨模块持久层访问。MER 不查询订单表，也不从服务人员总人数推算可预约容量。

## 3. 通用字段、错误与安全

复用23号：所有 API/JSON ID为正Long十进制String（含大于2^53的ID），无前导零；Java领域/存储使用Long。金额不在本切片新增。时间为带偏移、毫秒精度的ISO字符串，持久化UTC DATETIME(3)。

已批准业务 `version` 为非负Long十进制String，写请求携带 `expectedVersion`；不暴露Long为JS Number。稳定命令名和参数规范版本 v1 参与公共幂等，路径目标ID也进入规范参数。

列表：page默认1、pageSize默认20且1～100；只接受已列过滤字段；先数据范围过滤再计数和分页。按ID数值升序，不按String词典序；不支持未定义的任意排序。多页之间数据可能变化，不承诺快照游标。

| 情况 | 已批准映射 |
|---|---|
| 字段/ID/枚举/分页/未知字段非法 | 400 COMMON_INVALID_ARGUMENT |
| 无有效会话 | 401 COMMON_UNAUTHORIZED |
| 缺动作权、子账号签署协议 | 403 COMMON_FORBIDDEN |
| 商家/门店/人员不存在或超出授权归属 | 404 COMMON_NOT_FOUND，不区分存在与越权 |
| 有权读取但状态/版本/未完成订单冲突 | 409 COMMON_CONFLICT；data=null，不一概自动重试 |
| 同requestId异参 | 409 IDEMPOTENCY_KEY_CONFLICT |
| 事实来源/授权/回执读取不可用或未知 | 503 COMMON_DEPENDENCY_UNAVAILABLE；不返回默认允许 |

内部 `getStore/getStaff` 已批准沿用防枚举语义；已有 `MERCHANT_NOT_FOUND/MERCHANT_DISABLED` 留给已声明消费者，不全局替换既有交易错误。新单资格接口成功返回false表示权威事实明确不满足；事实不可读必须报依赖错误，不能伪装成正常false。下游是否转 MERCHANT_DISABLED 由其合同规定。

所有管理响应 `Cache-Control: no-store`。联系方式默认掩码，不出完整证件、协议审计账号或跨店信息；没有引入明文查看接口。请求中的merchantId/storeId是目标，不是授权证明。每次读写及成功重放都重新验证当前会话、动作和资源范围。

## 4. 内部查询已批准（保持07号既有方法和资格DTO）

| 方法/类型 | 明确字段与含义 |
|---|---|
| `getStore(StoreIdQuery)` | query：storeId、既有QueryContext；以门店实际merchant_id解析归属。返回MerchantStoreDTO |
| MerchantStoreDTO | merchantId、storeId、merchantName(1～128)、storeName(1～128)、address(1～255)、longitude/latitude(可空十进制String，最多7位小数；分别[-180,180]/[-90,90])、phoneMasked(可空)、merchantStatus、storeStatus、version |
| `checkOrderEligibility(MerchantOrderEligibilityQuery)` | query：merchantId、storeId、QueryContext。必须验证store属于merchant；读取同一一致性快照的商家/门店/审核/签约事实 |
| MerchantOrderEligibilityDTO | **保持原五字段** merchantId、storeId、merchantEnabled、storeEnabled、acceptsNewOrders，不增加存量履约allowed标志 |
| `getStaff(MerchantStaffQuery)` | query：merchantId、storeId、staffId、QueryContext；三者归属必须一致。返回MerchantStaffDTO，不披露成员登录信息 |
| MerchantStaffDTO | merchantId、storeId、staffId、staffName(1～64)、phoneMasked(可空)、employmentStatus=ACTIVE/INACTIVE、serviceEnabled(Boolean)、version；返回独立不可变副本 |
| `checkDisplayEligibility(MerchantDisplayEligibilityQuery)` | **第四查询（CCR-W2-API-001 服务域 SVC-D5，2026-09-22 已批）**：query：merchantId、storeId、QueryContext；无所有者前提，仅供 C 端展示聚合，不授予商家操作权限。确认不存在→NOT_FOUND；事实源故障/读取失败/状态未知→503；不得混同。形状见 07 号 §4.2 |
| `pageDisplayStores(MerchantStoreDisplayPageQuery)` / `getDisplayStore(MerchantStoreDisplayQuery)` | **第五查询（CCR-W2-API-001 门店读侧 STR-D6，2026-09-22 已批）**：page query：cityCodes（服务端开放城市集合，biz 不判断"开放"）、page、pageSize、QueryContext；store query：storeId、QueryContext；无所有者前提，仅供 C 端展示聚合，不授予商家操作权限，匿名浏览时主体字段为空且可见性与主体无关。返回 MerchantStoreDisplayDTO/MerchantStoreDisplayPageDTO（MerchantStoreDTO 档案投影 + cityCode；无 merchantStatus/storeStatus/version 恒真字段）。可见性=三条件合取（不含"服务 ACTIVE"），同一 `MerchantOrderEligibilityPolicy`、同一 repeatable-read 快照、整页一次资格判定（按页内去重 merchant/store 对）；确认无匹配可见门店=正常空页、单店不存在/无资格→NOT_FOUND；事实源故障/状态未知/compat 城市事实损坏（缺行或 city_code 词法非法）→503；不得混同。形状见 07 号 §4.3 |
| `listActiveStoreStaffFacts(StoreStaffFactsQuery)` | **第六查询（SCH2-D2，2026-09-24已批）**：独立MerchantStoreStaffFactsApi；query=storeId+QueryContext，DTO=storeId+activeStaffIds（不可变、去重、按Long数值升序String）。MER同一只读RR查询先确认店存在、校验该store_id员工原始在职/在岗值及商家归属，再筛ACTIVE∧enabled=1；未知/坏归属/依赖故障503，门店确认不存在NOT_FOUND，空列表=0。无owner前置，不授予操作权限，不返回个人资料。能力/时间覆盖由SCH自有事实计算，MER不推算容量；形状与全部边界见07号§4.4。 |

`merchantEnabled = merchant.status==ACTIVE`；`storeEnabled = store.status==ACTIVE`。
`acceptsNewOrders = merchantEnabled && storeEnabled && application.status==APPROVED && signing.status==SIGNED`。
查询对象存在但审核未过/未签/冻结/下线则false；数据库缺审核记录、不认识的枚举或签约来源损坏返回503，不能从商家状态或空值推断成功。该判断只说明商家域新单资格，不代替服务资格、营业/排期、用户资格或订单的最终校验。

新单检查与下线并发的跨域最终提交协调由TX/SCH契约承接；本 API 的一次快照不是预约授权租约。W2-MER-002 的模块验证不能声称消除了完整订单并发窗口。

## 5. 成员与准入事实已批准

复用HTTP10“商家工作台选择与准入”已有 `/c/auth/merchant-memberships` 与 `/merchant/auth/admission`；不新增第二套会话或客户端切换身份写命令。

MerchantMembershipQueryApi 提供：

- `listForUser(userId, page, pageSize)`：仅受信任AUTH适配器调用，userId来自会话，不作为公开任意用户查询。返回现有Membership投影，按merchantId/storeId数值升序；仅真实OWNER/STAFF关系。禁用/撤销成员不进入可选择列表；显式访问仍拒绝。
- `getFacts(userId, merchantId, storeId)`：返回membershipKind、membershipEnabled、applicationStatus、signingStatus、merchantStatus、storeStatus、authzVersion、checkedAt及获授动作。OWNER依merchant.owner_user_id；STAFF依显式成员表和门店授予。无关系404，依赖失败503。

技术已批准 `authzVersion` 由merchant/store/member/grant版本按固定字段顺序组成标签；OWNER没有member版本用明确 `OWNER` 标记，不以0假装实体。授权撤回/店状态/成员变化均改变标签。标签供缓存失效，不是执行许可证。

主账号普通MER档案/协议命令映射 `OperatorType.USER`、operatorId=会话userId；有真实员工绑定的子账号才可映射MERCHANT_STAFF。主账号核销要求 `operator_staff_id` 的既有缺口继续交由AUTH/订单核销契约确认，实现不得创建一个虚拟服务人员来凑字段。

| 权威事实 | S1已批准判定/边界 |
|---|---|
| 无关系/成员撤销 | 404，不泄露商家事实 |
| 子账号停用 | DENIED；个人C端会话不自动注销 |
| 草稿/待审/驳回/未签 | 不开放正常工作台；主账号可进入本人申请/签约引导 |
| 审核通过、已签、商家门店ACTIVE、成员有效 | ALLOWED，但业务命令仍按动作和资源检查 |
| FROZEN/OFFLINE、成员有效 | LIMITED，明确可读历史/处罚；新经营动作关闭；OFFLINE按SSOT §15保留存量履约/退款售后；FROZEN写动作仍待裁决 |
| 事实未知/依赖失败 | 503，不使用旧的allowed缓存 |

员工管理仅主账号（PRD §5.6明确）；店长/核销员/排期角色不因名称获得员工管理权限。子账号签约详情和同意均403。角色细分和成员开通流程留AUTH/MER专项契约，不把已批准staff CRUD等同子账号管理。

## 6. HTTP 操作已批准

全部路径带 `/api/v1`，MINIAPP Bearer取自既有会话。响应使用既有success/code/message/data/traceId包裹：首次创建员工或协议同意记录201，查询/更新/同意既存记录及幂等成功重放200，遵循23号§6。管理列表必须提供目标merchantId/storeId并校验当前主账号范围。

### 6.1 已列员工六接口的精化

| 方法/路径 | 请求（除列出字段外拒绝） | data |
|---|---|---|
| GET `/merchant/staff` | query merchantId、storeId、page/pageSize、employmentStatus?、serviceEnabled? | MerchantStaffDTO分页 |
| GET `/merchant/staff/{staffId}` | query merchantId、storeId | MerchantStaffDTO |
| POST `/merchant/staff` | body merchantId、storeId、staffName、phone?、employmentStatus、serviceEnabled；X-Request-Id | MerchantStaffDTO |
| PUT `/merchant/staff/{staffId}` | body merchantId、storeId、staffName、phone?、expectedVersion；X-Request-Id | MerchantStaffDTO |
| POST `/merchant/staff/{staffId}/enable` | body merchantId、storeId、expectedVersion；X-Request-Id | MerchantStaffDTO |
| POST `/merchant/staff/{staffId}/disable` | body merchantId、storeId、expectedVersion；X-Request-Id | MerchantStaffDTO |

staffName以提交值保存，非空白1～64字符；phone可空，已批准沿用AUTH的11位ASCII手机号模式 `^1[0-9]{10}$`（数据库VARCHAR(32)不表示接受任意电话号码），不能据此自动绑定账号。PUT为档案替换，phone省略/显式null均清空；客户端必须明确展示这一语义，其他字段null非法。
enable/disable仅改变serviceEnabled，不改变账号状态、不办理离职；enable要求employmentStatus=ACTIVE。创建明确提供employmentStatus/serviceEnabled，不依表默认自动上岗；INACTIVE且serviceEnabled=true非法。

离职、软删除、岗位/资质/擅长项扩展、成员开通/授予不是这六接口已自动覆盖的能力：对应新增状态列、动作和材料契约需在原Issue后续补齐。岗位筛选因现有Schema缺字段保留缺口，不假称完整员工页已满足。

**disable实现前置**：通过order-api查询当前在途指派，存在未完成订单则409并引导完成或在订单域改派；不得清空原staffId。当前没有该查询/与新指派互斥契约，因此该写端点保留IMPLEMENTATION_BLOCKED；即使未来有一次查询，也需与指派建立受审查的并发协调，不能“先查无订单再停用”宣称安全。服务人员软删除和离职同样受此约束。启用动作仍校验商家/门店可经营，不恢复已撤销子账号。

### 6.2 电子协议新增两条已批准路由

| 方法/路径 | 请求 | data |
|---|---|---|
| GET `/merchant/agreement` | query merchantId；仅本人主账号 | `{merchantId,agreementVersion,content,contentSha256,signingStatus,acceptedVersion?,acceptedAt?}` |
| POST `/merchant/agreement/consent` | body merchantId、agreementVersion、contentSha256、accepted=true；X-Request-Id；仅主账号 | `{merchantId,agreementVersion,acceptedAt,signingStatus:"SIGNED"}` |

agreementVersion为运营发布的非空ASCII标识1～64（已批准词法 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`），按不透明版本比较，不按版本号大小排序。content为非空UTF-8纯文本，已批准上限65535字节；不返回可执行HTML或外链签约URL。contentSha256为该版本存储的原始UTF-8内容SHA-256小写64位十六进制；同一版本不允许覆盖内容。此hash证明提交版本对应所见文本，不伪称证明用户实际阅读。

首次签署：审核APPROVED、真实owner、当前已发布版本匹配，签署时间取服务端时钟；同事务写不可变同意记录和幂等回执。**未签不要求先获得ALLOWED工作台准入**，否则将形成必须签约才能签约的死锁。
accepted=false/null、未知字段和非法hash为400；未知/非当前版本或审核未过为409；发布事实查询不可用503。内容/hash不符409，重新读取并确认后才以新意图提交。冻结/下线的后续重签不在首次路径开放。

同requestId同参成功重放返回原acceptedAt/版本；重放前检查当前身份、owner范围和敏感权限，不重跑已经消费的“未签约”资格。新requestId重复同一已签版本返回同一业务签署记录，不新增签约时间。新key对已签商家提交不同版本返回409（V1不新增重签流程），不自动失效原签约。读取对未签者展示当前发布文本，对已签者展示其已同意版本及原签署时间；子账号不读取协议同意审计详情。

运营协议维护仅提出不可变版本与当前发布指针的存储需求；不在本切片新增后台发布页面/接口。获批运营编辑直接发布的规则保持，不引入第二人审批。

### 6.3 既有下线路由的域交接

HTTP10 §5.6 `POST /admin/merchants/{merchantId}/disable`，body `{reason}`及X-Request-Id；动作 `merchant.disable`，PLATFORM_OPERATOR来自真实admin会话，范围与目标交集在适配层和命令内校验。
reason沿用权威字段，其长度等未明确校验须在ADM协同契约冻结时确认，不套用无来源产品规则。
MER更新商家状态并持久化既有 `MerchantDisabledEvent.v1`，payload保持merchantId/storeIds/disabledAt。storeIds为目标商家实际所属门店的去重数值升序String列表；不写别域业务表，不自动取消订单，也不假装本阶段已实现消费者。
下线不以在途订单为拒绝理由。首次有效转换同事务产生一条事件，重放不重复发布；已经OFFLINE的新命令返回已下线的稳定最小结果，不触发第二次状态转换事件。CANCELED等未定义转换不可自动复活，交ADM契约确认。

## 7. 写入、版本与持久化

已批准稳定命令名：merchant.staff.create/update/enable/disable、merchant.agreement.consent、merchant.disable；幂等scope使用23号规定的命令名+operatorType+operatorId，包含目标ID的规范参数，不把requestId当业务实体ID。

依23号执行：当前权限与静态校验→独立短事务绑定RESERVED→业务事务锁原绑定/当前读→复核权限/状态/expectedVersion→写本域事实、审计、最小结果以及必要Outbox→同一次提交。错误回滚业务，保留绑定。同key异参即使首次失败也409；成功重放不做第二次修改，撤权后不泄露旧回执。版本冲突不能自动用新key覆盖别人修改。

已批准物理表与约束见[存储映射](../03-database/27-Merchant-Domain-Storage-v0.1.md)。只使用merchant模块自有Mapper XML与同DataSource事务；不得依赖user/admin的幂等Store实现。不能仅复用Redis或只以数据库version声称完整requestId幂等。迁移编号、正式库启用、Snowflake宿主门禁不在本阶段执行。

## 8. 验收与交接

[验收矩阵](../../planning/ccr/CCR-W2-API-001/merchant-acceptance-matrix.md)逐项区分规范检查、未来真实MySQL/HTTP/Outbox验证和跨域未具备项。协议换版与下线存量语义已批准；成员绑定/主账号核销映射、员工在途检查不会以测试替身自动解除。

本轮已同步07/10/11/12与商家域存储映射；后续按S2/S3实施，实际DDL与迁移仍需完整实现交接；不要求重新决定已经批准的电子协议或取消MFA。完整MER-001 AC1～4、W2-MER-001～003、架构检查、实际PR/CI和风险披露全部满足后才DONE。


## 2026-09-24 员工基础五操作实现补充

用户已确认读写门槛及独立审计/内部命令补充，见[35号契约](35-Merchant-Staff-Management-Contract-v0.1.md)与[批准回执](../../planning/ccr/CCR-W2-API-001/merchant-staff-implementation-decisions.md)。列表/详情为真实主账号范围只读；新增/编辑/启用要求真实APPROVED+SIGNED+merchant/store ACTIVE。disable原门禁保持。既有手机号清空/脱敏与外部字段不变；本条不等于实现已交付或生产开关已开启。
