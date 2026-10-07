import type { StaffInvitationListDeps, StaffInvitationPage, StaffInvitationSummary } from './model'

// Visual-preview fixtures for the staff workbench invitation list (54号 §7 slice, no live
// calls): shapes follow decodeStaffInvitationPage exactly — one pending invitation (the
// highlighted confirm entry), one confirmed and one canceled history row, plus a second page
// so the load-more control can be reviewed. Strictly design-acceptance data.
const stamp = (daysAgo: number): string =>
  new Date(Date.parse('2026-10-07T02:00:00.000Z') - daysAgo * 86_400_000).toISOString()

const rows: StaffInvitationSummary[] = [
  {
    invitationId: '930000000000301', merchantId: '910000000000101', merchantName: '小李宠物店',
    storeId: '910000000000102', storeName: '小李宠物店·总店', memberName: '李小美',
    grantedActions: ['merchant.order.verify'], status: 'INVITED',
    invitedAt: stamp(0.2), updatedAt: stamp(0.2),
  },
  {
    invitationId: '930000000000291', merchantId: '910000000000201', merchantName: '汪汪 grooming 工作室',
    storeId: '910000000000202', storeName: '汪汪·滨江店', memberName: '李小美',
    grantedActions: ['merchant.order.verify'], status: 'CONFIRMED',
    invitedAt: stamp(9), updatedAt: stamp(8.5),
  },
  {
    invitationId: '930000000000281', merchantId: '910000000000301', merchantName: '喵呜寄养公寓',
    storeId: '910000000000302', storeName: '喵呜·城西店', memberName: '李小美',
    grantedActions: ['merchant.order.verify'], status: 'CANCELED',
    invitedAt: stamp(30), updatedAt: stamp(28),
  },
  {
    invitationId: '930000000000271', merchantId: '910000000000401', merchantName: '爪爪上门洗护',
    storeId: '910000000000402', storeName: '爪爪·高新区店', memberName: '李小美',
    grantedActions: ['merchant.order.verify'], status: 'CONFIRMED',
    invitedAt: stamp(60), updatedAt: stamp(60),
  },
]

/** Preview-only deps: two pages over the same fixture rows; never issues a request. */
export function previewStaffInvitationListDeps(): StaffInvitationListDeps {
  return {
    async list(page, pageSize) {
      const start = (page - 1) * pageSize
      const items = rows.slice(start, start + pageSize)
      const result: StaffInvitationPage = { items, page, pageSize, total: rows.length }
      return result
    },
  }
}
