import test from 'node:test'
import assert from 'node:assert/strict'
import { MessagesController, jumpLabelFor, routeForNotification, type NotificationDeps } from '../notifications/messages'
import { ApiError } from '../../shared/request'
import type { InboxNotification } from '../../shared/notification-repositories'

const time = '2026-09-22T00:00:00.000Z'
const item = (over: Partial<InboxNotification> = {}): InboxNotification => ({
  id: '701', category: 'SYSTEM', messageType: 'MERCHANT_APPLICATION_REVIEWED',
  bizType: 'MERCHANT_APPLICATION', bizId: '801',
  title: '商家入驻审核结果', content: '申请 SQ12345678abcdefgh：审核通过',
  readAt: null, createdAt: time, ...over,
})
function depsWith(overrides: Partial<NotificationDeps>): NotificationDeps {
  const base: NotificationDeps = {
    list: async () => ({ items: [item()], total: 1 }),
    detail: async () => item(),
    markRead: async () => ({ readAt: time }),
  }
  return { ...base, ...overrides }
}

test('list renders items; empty maps to guidance; failures stay closed', async () => {
  const empty = new MessagesController(depsWith({ list: async () => ({ items: [], total: 0 }) }))
  await empty.load()
  assert.equal(empty.getSnapshot().status, 'empty')
  const failing = new MessagesController(depsWith({ list: async () => { throw new Error('network') } }))
  await failing.load()
  assert.equal(failing.getSnapshot().status, 'error')
  const unauthorized = new MessagesController(depsWith({ list: async () => { throw new ApiError('COMMON_UNAUTHORIZED', 401) } }))
  await unauthorized.load()
  assert.equal(unauthorized.getSnapshot().status, 'unauthorized')
})

test('opening an item marks it read once and keeps the list in sync', async () => {
  let marks = 0
  const controller = new MessagesController(depsWith({
    markRead: async () => { marks++; return { readAt: time } },
  }))
  await controller.load()
  await controller.open('701')
  const state = controller.getSnapshot()
  assert.equal(state.detail?.readAt, time)
  assert.equal(state.items[0]!.readAt, time)
  assert.equal(marks, 1)
  controller.closeDetail()
  assert.equal(controller.getSnapshot().detail, null)
})

test('already-read items do not mark again; lost mark response keeps the item read', async () => {
  let marks = 0
  const controller = new MessagesController(depsWith({
    list: async () => ({ items: [item({ readAt: time })], total: 1 }),
    detail: async () => item({ readAt: time }),
    markRead: async () => { marks++; return { readAt: time } },
  }))
  await controller.load()
  await controller.open('701')
  assert.equal(marks, 0)
})

test('whitelist jump only for the registered type; payload never carries a URL', async () => {
  assert.equal(routeForNotification(item()), '/consumer/pages/merchant-application/index')
  assert.equal(routeForNotification(item({ messageType: 'SOMETHING_ELSE', bizType: null, bizId: null })), null)
  assert.equal(routeForNotification(item({ bizId: null })), null)
})

test('service review notification jumps to the merchant services page with its own label', async () => {
  const serviceReviewed = item({
    messageType: 'SERVICE_REVIEWED', bizType: 'SERVICE', bizId: '9001',
    title: '服务审核结果', content: '服务《宠物基础洗护》：审核未通过。服务图片与门类不符，请补充真实拍摄图片后重新提交',
  })
  // Route per role C's M-002 NAVIGATION-BASIS; the target page re-authenticates by itself.
  assert.equal(routeForNotification(serviceReviewed), '/merchant/pages/services/index')
  assert.equal(jumpLabelFor(serviceReviewed), '查看服务')
  assert.equal(jumpLabelFor(item()), '查看入驻申请')
  // Unregistered shapes keep detail-only rendering with no jump entry.
  assert.equal(routeForNotification(item({ messageType: 'SERVICE_REVIEWED', bizType: 'SERVICE', bizId: null })), null)
  assert.equal(jumpLabelFor(item({ messageType: 'SOMETHING_ELSE', bizType: null, bizId: null })), null)
})

test('disposed controller ignores late results', async () => {
  let resolve!: (value: { items: InboxNotification[]; total: number }) => void
  const late = new Promise<{ items: InboxNotification[]; total: number }>(ok => { resolve = ok })
  const controller = new MessagesController(depsWith({ list: () => late }))
  const loading = controller.load()
  controller.dispose()
  resolve({ items: [item()], total: 1 })
  await loading
  assert.equal(controller.getSnapshot().status, 'loading')
})
