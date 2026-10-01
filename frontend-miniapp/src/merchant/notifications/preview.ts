import type { InboxNotification } from '../../shared/notification-repositories'
import type { NotificationDeps } from '../../shared/notifications/messages'

const stamp = (hoursAgo: number): string =>
  new Date(Date.parse('2026-10-02T04:00:00.000Z') - hoursAgo * 3_600_000).toISOString()

// Visual-preview fixtures for the merchant message page (no live calls). Shapes follow
// decodeNotification exactly: the two registered CCR §5 message types plus one unregistered
// system notice that must render detail-only with no jump entry.
const previewItems: InboxNotification[] = [
  {
    id: '701', category: 'SYSTEM', messageType: 'SERVICE_REVIEWED',
    bizType: 'SERVICE', bizId: '9001',
    title: '服务审核结果',
    content: '服务《宠物基础洗护》：审核未通过。服务图片与门类不符，请补充真实拍摄图片后重新提交',
    readAt: null, createdAt: stamp(1),
  },
  {
    id: '702', category: 'SYSTEM', messageType: 'MERCHANT_APPLICATION_REVIEWED',
    bizType: 'MERCHANT_APPLICATION', bizId: '801',
    title: '商家入驻审核结果',
    content: '申请 SQ20260922abcdefgh：审核通过',
    readAt: stamp(5), createdAt: stamp(5),
  },
  {
    id: '703', category: 'SYSTEM', messageType: 'PLATFORM_ANNOUNCEMENT',
    bizType: null, bizId: null,
    title: '平台公告',
    content: '平台将于维护时段进行系统升级，期间服务暂不可用。',
    readAt: null, createdAt: stamp(26),
  },
]

/** Preview deps: deterministic fixtures, markRead flips readAt locally; no network. */
export function previewNotificationDeps(): NotificationDeps {
  const items = previewItems.map(item => ({ ...item }))
  const find = (id: string): InboxNotification => {
    const found = items.find(item => item.id === id)
    if (!found) throw new Error('PREVIEW_NOT_FOUND')
    return found
  }
  return {
    list: async () => ({ items: items.map(item => ({ ...item })), total: items.length }),
    detail: async id => ({ ...find(id) }),
    markRead: async id => {
      const found = find(id)
      if (found.readAt === null) items[items.indexOf(found)] = { ...found, readAt: stamp(0) }
      const readAt = find(id).readAt
      if (readAt === null) throw new Error('PREVIEW_MARK_FAILED')
      return { readAt }
    },
  }
}
