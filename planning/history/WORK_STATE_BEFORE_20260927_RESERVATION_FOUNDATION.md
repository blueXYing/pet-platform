# Work State

PROJECT: 宠物平台 V1.0
STATE_VERSION: 10.0
UPDATED_AT: 2026-09-24
CURRENT_PHASE: W3_INTEGRATION_REVIEW
CURRENT_STATUS: STAFF_FIVE_OPERATIONS_AND_SERVICE_PUBLISH_INTEGRATION_REVIEW
VERIFIED_BASELINE: develop 980a830db418c55e0c871518d2b0feaeda0bd3b5（PR81/82用户批准合入，合并CI成功）
NEXT_PHASE: PR83/84审阅；按已批准ROC1～6接续ORDER指派完整性与SCH预约写入保护
NEXT_PHASE_APPROVED: YES（用户批准GPT-6 Sol/xhigh多角色及员工读写门槛、预约六项方案；新PR合并/生产迁移未授权）

## 当前事实

- PR80/81/82已合入，不再待合并。SCH-002真实可约读模型已完成，默认关；SCH-004四项保护契约已批，不能将读容量视为预约并发保障。
- 本轮已实现员工列表、详情、新增、编辑、启用：真实OWNER；读取与经营写入分离；写入APPROVED+SIGNED+merchant/store ACTIVE；幂等/CAS、锁内复核与SQL35独立审计。停用/离职/删除/成员绑定不在实现范围。
- 服务发布已在开发者工具完成真实封面上传→保存→提交→运营HTTP批准→C真实图片显示；事件PUBLISHED且SERVICE消息一条。已修签名与只读事务冲突、可空说明和整分钟到期格式三个实际集成问题。详细接缝、环境异常和图片清理记录见回执，不等同生产或真机验收。
- [PR83](https://github.com/blueXYing/pet-platform/pull/83)预约保护六项方案获用户批准：全店完整人员证明、接送同人双段、到店单窗、hold/订单原子绑定、ORDER当前指派完整性、改期保留原指派失败留旧。契约分支负责同步批准回执/36号/SSOT§31；未实现SCH-003，也未执行迁移。
- 各角色独立worktree，作者测试与独立HTTP验收分开；完整集成门禁见本轮验证回执。默认开关保持关闭；新PR等待人工审阅与合并授权。

## 下一步

1. 审阅本轮员工/服务实现PR及已批预约契约PR，CI通过也不自动合并。
2. 按36号及34号先实现ORDER全量当前指派完整性查询、同店共同锁与SCH权威事实/精确容量证明；补真实MySQL并发/回滚证据。
3. 接续SCH-003 hold/confirm/release/swap与ORDER创建原子绑定，再解锁SCH-004减员、独立能力版本CAS和双方向完整窗口维护；旧GENERAL隔离继续保留。
4. MER员工页面/M-002排期维护页面仍未交付；服务发布手机真机、旧ETag处理及生产部署/密钥/迁移/HTTPS门禁另行验收。

## 证据

- [本轮分工](planning/progress/2026-09-24/staff-reservation-round/DISPATCH.md)
- [独立员工QA](planning/issues/wave-3/MER-staff-qa/QA-PLAN.md)
- [服务发布联调](planning/progress/2026-09-24/staff-reservation-round/SERVICE-PUBLISH-ACCEPTANCE.md)
- [本轮集成回执](planning/progress/2026-09-24/staff-reservation-round/INTEGRATION.md)
- [上一状态](planning/history/WORK_STATE_BEFORE_20260924_STAFF_RESERVATION_ROUND.md)

主目录原分支及用户三个改动保持；临时测试AppID恢复；不push main/develop。
本轮实现PR：[PR84](https://github.com/blueXYing/pet-platform/pull/84)。功能分支已纳入PR83文档基线并保留SSOT §30/31及07号双方补充；建议先合入83再84，预合并无冲突，未替用户执行PR合并。
