# CCR-M-STAFF-POS-001 成员岗位可编辑 + 核销权限默认授予

- 状态：APPROVED（2026-11-16 用户拍板）
- 关联：54号（员工绑定）；PRD29；members/detail 页

## 1. 裁决
1. 岗位从写死"核销员"改为**老板可编辑的自由文本标签**（如"美容师""前台"），不关联权限
2. 核销权限（merchant.order.verify）**在成员确认邀请时自动授予**，不需要手动授权

## 2. 实现
- `merchant_member` 表加 `position VARCHAR(64)` 列（默认空串）
- 确认邀请（confirm）时自动 INSERT `merchant_member_store_action(action_code='merchant.order.verify')`
- M端 PUT `/api/v1/merchant/staff-members/{memberId}/position` 修改岗位
- 前端 detail.tsx 岗位变可编辑 Input + 保存按钮；权限 chips 区删除；授权/撤权按钮删除
