# CCR-W2-API-001 用户/宠物域契约提案(草案)

状态:**PROPOSAL_ACCEPTED / CONTRACT_SYNCED_IN_PR**。规范版本:0.1,日期:2026-09-15。2026-09-15人工批准"三项批准,合并PR22":归属404防枚举、user_pet补avatar_url、年龄按birthDate,及两条用户资料新路由;权威06/10/12/07同步在本分支codex/usr-001-contract-sync(待PR合并)。实现未开始,USR-001非DONE。
提出方/唯一编辑者:Backend Core(USR-001 规范阶段)。关联 Issue:USR-001 / EPIC-02 / ST-PET-01。只读审阅:Transaction、QA、C-End、AUTH协作。批准人:人工 CTO / Contract Owner **blueXYing**。

基线:develop `903225cf07a9775fa99aef7d7744275e30d5d5d2`;分支 `codex/usr-001-contract-spec`。本草案只做规范,不写实现代码、不改权威HTTP10/07/12/Schema;获批后由Owner同步权威文档再派发实现。

## 人工 CTO 一页阅读指南

**用通俗话说:小程序里"我的宠物"要能增删改查,还要能查/改用户自己的基本资料。数据库表和五条路由早就定了,但每条接口"传什么字段、什么算合法、报什么错"没写清楚——本提案把这些补齐,并发现两个需要你拍板的小缺口(宠物头像列、年龄与出生日期的换算方式)。**

| 分工 | 内容 |
|---|---|
| 我负责 | 逐操作字段/校验/错误/鉴权/幂等映射、示例与反例、Schema与错误码影响 |
| 你负责 | 审核"建议决定"三项;权威契约同步与实现派发按流程另批 |
| 必读 | 本页"建议决定";细节见下文逐操作定义 |
| 不变 | 产品规则(多宠单默认/健康仅本人可见/删除不影响订单快照)原样执行,不新增不缩水 |

## 建议决定(需人工批准的三项)

1. **归属反例统一404防枚举**:访问不属于自己的宠物(无论不存在还是他人的)一律 `COMMON_NOT_FOUND`/404,不区分"不存在/无权",防止探测他人宠物ID。会话主体取自登录态,前端不传userId。
2. **宠物头像为Schema补列项**:C端PRD §5.1.3要求宠物头像,user_pet 表无该列。建议获批后同步权威06号时补 `avatar_url VARCHAR(512) NULL`(与user_account同规格),不执行迁移。
3. **年龄以出生日期为准**:PRD填写"年龄",Schema存 `birth_date`。HTTP输入输出均为 `birthDate`(ISO日期,可空),年龄由展示端按当前日期换算,服务端不存会过期的年龄数;体重 `weightKg` 十进制字符串两位小数(0.01~999.99)。

## 1. 范围与来源

- 路由来源:HTTP10 §3.2五条宠物路由(已定);用户资料两条为**新增路由候选**(见§4)。
- 内部API来源:07 §3.1 UserQueryApi(UserBasicDTO/PetSnapshotDTO/existsEnabledUser)。
- Schema来源:06 §1 user_account/user_auth_identity/user_pet。
- 产品规则:C端PRD §5.1.3(多宠单默认/健康本人可见/删除不影响订单)、权限矩阵1835/1900行(运营pet.read不可见健康体重疫苗,除非用户授权+在途订单最小必要)。
- 幂等:23号§5~7,X-Request-Id 完整UUID,scope=(稳定宠物命令,USER,会话userId)。

## 2. 宠物DTO与字段校验

`PetView`(GET单只/列表项/创建更新响应):

```json
{
  "petId": "30001",
  "name": "豆豆",
  "petType": "DOG",
  "breedName": "柯基",
  "birthDate": "2023-05-01",
  "sex": "MALE",
  "weightKg": "12.50",
  "sterilizationStatus": "NEUTERED",
  "vaccineStatus": "COMPLETE",
  "healthNote": "对鸡肉过敏",
  "isDefault": true,
  "avatarUrl": "https://...",
  "status": "ACTIVE"
}
```

校验(违规一律 `COMMON_INVALID_ARGUMENT`/400,响应details指明字段):
- `name`:1~64字符,首尾空白拒绝(不trim)。
- `petType`:枚举 `DOG/CAT/OTHER`(V1百科联动后续扩展经CCR)。
- `breedName`:可空,≤64。
- `birthDate`:可空,ISO `yyyy-MM-dd`,不得晚于当天。
- `sex`:枚举 `MALE/FEMALE/UNKNOWN`(默认UNKNOWN)。
- `weightKg`:可空,十进制字符串两位小数,0.01~999.99(公共金额Codec校验规则复用)。
- `sterilizationStatus`:可空,枚举 `INTACT/NEUTERED/UNKNOWN`;`vaccineStatus`:可空,枚举 `NONE/PARTIAL/COMPLETE/UNKNOWN`;`healthNote`:可空,≤1000。
- `isDefault`:布尔默认false;`status`/`petId` 服务端管理,客户端只读;未知字段/重复JSON键拒绝(基座已实现400)。

## 3. 宠物五操作契约

所有操作:会话来自C端登录态(未来AUTH接入);未登录 `COMMON_UNAUTHORIZED`/401。归属反例按建议决定1统一404。

| 操作 | 幂等 | 成功 | 关键错误 |
|---|---|---|---|
| GET /api/v1/c/pets | 否 | 200 `ApiResponse<PetView[]>>` 按创建时间倒序,仅本人ACTIVE宠物 | 401 |
| POST /api/v1/c/pets | 是(requestId) | 201 `PetView`;`isDefault=true`时同请求内原子取消旧默认 | 400校验 |
| GET /api/v1/c/pets/{petId} | 否 | 200 `PetView` | 404(不存在/他人/已删除) |
| PUT /api/v1/c/pets/{petId} | 是(requestId) | 200 更新后`PetView`;`isDefault=true`同样原子换默认 | 400/404 |
| DELETE /api/v1/c/pets/{petId} | 是(requestId) | 200 `{petId,status:"DISABLED"}` 软删除 | 404 |

- **软删除语义**:`status` 枚举定案 `ACTIVE/DISABLED`(06号user_pet注释未列全,随权威同步补注释);DELETE置DISABLED后不再出现在列表/单查404;同requestId重放返回首次回执;历史 `order_pet_snapshot` 不受影响(W2-USR-003)。
- **默认宠物**:每用户至多一只;置新默认与取消旧默认同事务;删除默认宠物后**无默认直至用户再设置**(不自动顶替,PRD未定义自动规则,如实披露)。
- **冻结用户**:user_account.status≠ACTIVE时全部写操作拒绝 `USER_FROZEN`/403(新码,见§6);查询维持可见性由后续AUTH裁决细化。

## 4. 用户资料两条新路由候选(需批准加入HTTP10)

| 操作 | 说明 |
|---|---|
| GET /api/v1/c/profile | 200 `{userId, nickname, avatarUrl, phoneMasked, passwordEnabled}`;phoneMasked按07 §3.1掩码;passwordEnabled不泄露具体凭据形态 |
| PUT /api/v1/c/profile | 仅 `nickname`(1~64)/`avatarUrl`(≤512,https);幂等requestId;USER_FROZEN拒写 |

不包含手机号换绑/注销:属AUTH后续范围。运营端读取用户档案走既有运营权限矩阵(健康字段默认不可见),不在本提案扩权。

## 5. 内部API语义补全(07 §3.1既有签名不变)

- `getPetSnapshot(PetSnapshotQuery{petId, ownerUserId})`:必须携带ownerUserId做归属校验;不存在/DISABLED/归属不符→`ApiException(PET_NOT_FOUND)`;返回值为即时副本,调用方(order)须自行持久化到order_pet_snapshot,后续主数据变化不影响已存副本(W2-USR-002)。
- `getUser/existsEnabledUser`:FROZEN/CANCELED→existsEnabledUser=false;UserBasicDTO.status透传。
- pet-user-biz不暴露Repository/Mapper(ARCH-002)。

## 6. Schema与错误码影响(获批后由Owner同步权威文档)

- 06号:user_pet补 `avatar_url VARCHAR(512) NULL`;status注释补 `ACTIVE/DISABLED`(建议决定2,不执行迁移)。
- 12号新增 `## USER/PET` 候选:`USER_FROZEN`(403)、`PET_NOT_FOUND`(404,内部归属反例与HTTP同义)。
- 10号:§3.2补字段规范;新增§3.4用户资料两路由(建议决定范围)。
- 事件:本域V1无对外事件(宠物变更不发布IntegrationEvent,订单只取快照;如后续需要再走CCR)。

## 7. 示例与反例(Mock基线)

- 正:创建含全字段宠物→201;重放同requestId→201同回执;置默认→旧默认isDefault=false同响应可见。
- 反:他人petId读取→404 `COMMON_NOT_FOUND`(响应不含该宠物任何字段);weightKg="12.5"(一位小数)→400;birthDate未来日期→400;PUT携带petType变更→400(petType创建后不可改,如实披露为新约定);DELETE后GET→404;重放同requestId异参→409 `IDEMPOTENCY_KEY_CONFLICT`。

## 8. 边界

本提案不改登录/手机号校验/换绑(AUTH)、不做宠物百科联动(需品种数据CCR)、不做提醒功能、不扩运营权限。获批后先同步权威10/07/12/06,实现阶段再按W2-USR-001~004验收。
