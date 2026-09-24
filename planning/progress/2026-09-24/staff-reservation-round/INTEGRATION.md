# 员工接口与服务发布集成回执

基线 develop `980a830db418c55e0c871518d2b0feaeda0bd3b5`；PR81/82已获用户批准合入，develop CI run35978542102 SUCCESS。员工读写门槛于2026-09-24获用户批准；预约六项方案在PR83获用户批准，合并/生产迁移未获授权。

## 交付

- MER列表、详情、创建、编辑、启用五操作默认关；新增内部API/DTO、SQL35审计、boot路由/安全接线、OpenAPI状态。仅真实主账号，业务和成功重放均锁内复查归属、审核、签署及经营条件；写入/版本/审计同事务。
- 编辑仅姓名和手机号，省略/null手机号清空；HTTP掩码，不把手机号变成登录身份。HMAC隔离个人资料幂等比较值；非法内部缺状态400，未知事实503。
- 停用/离职/删除无实现，disable无handler。缺保护密钥或申请事实源时只读仍可用、依赖该事实的写失败关闭。
- 服务发布三项真实联调缺陷及一项时长契约边界修正及测试模式增强见[实际回执](SERVICE-PUBLISH-ACCEPTANCE.md)。没有新增前端产品功能或生产开关。

## 验证

- 作者：MerchantStaffApiMySqlTest、MerchantStaffHttpTest（真实隔离MySQL、真实HTTP、Redis）通过。
- 独立QA：MerchantStaffAcceptanceHttpTest 2/2通过；含真实审批签约/两商家、脱敏/手机号清空、CAS、幂等/撤权重放、停用无入口、默认关闭、SQL播能力/排班→SCH002容量交集。见[QA明细](../../../issues/wave-3/MER-staff-qa/QA-PLAN.md)。
- 签名MySQL回归先红后绿（3 tests）；ServiceQueryHttpTest空说明、ServiceWriteHttpTest整分钟有效期通过。
- 前端178/178离线测试；Taro微信构建及package预算检查通过。开发者工具真实图片与服务行已观察，真机/上传平台包未执行。
- e2e115/115契约离线测试；首次因状态名单仍写“未实现”失败，同步精确五操作/disable状态后通过，未放松鉴权/字段负向断言。
- 架构：41 reactor/17 biz，无biz→biz；13项门禁负向测试及展示状态派生检查通过。
- 后端全量 `mvn -B clean verify`：PASS，445 tests / 0 failures / 0 errors / 0 skipped，耗时10分30秒。随后内部空employmentStatus的400边界修复以MerchantStaffApiMySqlTest 7/7重新验证；前端时长边界修复以178项测试/typecheck重新验证，远端CI验证最终提交。

## 剩余边界

SQL35只在临时库安装，生产未迁移；生产staff/private资产/签名开关不启用。员工管理前端、成员账号绑定、SCH004维护写接口、SCH003预约锁位和订单保护尚未实现。列表目前门店全量读后过滤分页，有线性成本；优化另行保持坏事实失败关闭。

PR83的ROC1～6虽已批准仍是待实现规范。下一实现顺序为ORDER全量指派事实与共同锁→SCH精确容量证明→hold/order原子绑定及swap/release→减员/能力版本/排班写入。已有120分钟、分钟排期、改期一次、部分关闭及退款规则不重审。
独立QA对服务签名事务、空说明和有效期修复只读复核未发现阻断；测试接缝、标题复用与脱敏HTTP附件范围已在实际回执细分。不以截图中的门店样例/评价宣称已完成对应接口。
