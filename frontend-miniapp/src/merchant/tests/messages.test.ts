import test from 'node:test'
import assert from 'node:assert/strict'
import { MessagesController, jumpLabelFor, routeForNotification, type NotificationDeps } from '../../shared/notifications/messages'
import { ApiError } from '../../shared/request'
import { previewNotificationDeps } from '../notifications/preview'
import type { InboxNotification } from '../../shared/notification-repositories'

const time = '2026-10-02T04:00:00.000Z'
const item = (over: Partial<InboxNotification> = {}): InboxNotification => ({
  id: '701', category: 'SYSTEM', messageType: 'SERVICE_REVIEWED',
  bizType: 'SERVICE', bizId: '9001',
  title: '服务审核结果', content: '服务《宠物基础洗护》：审核未通过。请补充真实拍摄图片后重新提交',
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

test('merchant page state machine: ready/empty/error/unauthorized and open-marks-once', async () => {
  let marks = 0
  let current = item()
  const controller = new MessagesController(depsWith({
    detail: async () => current,
    markRead: async () => { marks++; current = item({ readAt: time }); return { readAt: time } },
  }))
  await controller.load()
  assert.equal(controller.getSnapshot().status, 'ready')
  await controller.open('701')
  const ready = controller.getSnapshot()
  assert.equal(ready.detail?.readAt, time)
  assert.equal(ready.items[0]!.readAt, time)
  assert.equal(marks, 1)
  // Already-read detail never marks again.
  await controller.open('701')
  assert.equal(marks, 1)
  const empty = new MessagesController(depsWith({ list: async () => ({ items: [], total: 0 }) }))
  await empty.load()
  assert.equal(empty.getSnapshot().status, 'empty')
  const failing = new MessagesController(depsWith({ list: async () => { throw new Error('network') } }))
  await failing.load()
  assert.equal(failing.getSnapshot().status, 'error')
  const unauthorized = new MessagesController(depsWith({
    list: async () => { throw new ApiError('COMMON_UNAUTHORIZED', 401) },
  }))
  await unauthorized.load()
  assert.equal(unauthorized.getSnapshot().status, 'unauthorized')
})

test('whitelist jump from the merchant page: services page for reviews, application page for admission', () => {
  // The merchant-area page consumes the same CCR-W2-NOTIFICATION-001 §5 whitelist.
  assert.equal(routeForNotification(item()), '/merchant/pages/services/index')
  assert.equal(jumpLabelFor(item()), '查看服务')
  const admission = item({
    messageType: 'MERCHANT_APPLICATION_REVIEWED', bizType: 'MERCHANT_APPLICATION', bizId: '801',
    title: '商家入驻审核结果',
  })
  assert.equal(routeForNotification(admission), '/consumer/pages/merchant-application/index')
  assert.equal(jumpLabelFor(admission), '查看入驻申请')
  // Unregistered types stay detail-only; wrong biz shape never routes anywhere.
  assert.equal(routeForNotification(item({ messageType: 'PLATFORM_ANNOUNCEMENT', bizType: null, bizId: null })), null)
  assert.equal(jumpLabelFor(item({ messageType: 'PLATFORM_ANNOUNCEMENT', bizType: null, bizId: null })), null)
  assert.equal(routeForNotification(item({ bizType: 'ORDER' })), null)
  assert.equal(routeForNotification(item({ bizId: null })), null)
})

test('preview fixtures: registered types carry jumps, unregistered type stays detail-only', async () => {
  const controller = new MessagesController(previewNotificationDeps())
  await controller.load()
  const state = controller.getSnapshot()
  assert.equal(state.status, 'ready')
  assert.equal(state.items.length, 3)
  assert.equal(routeForNotification(state.items[0]!), '/merchant/pages/services/index')
  assert.equal(routeForNotification(state.items[1]!), '/consumer/pages/merchant-application/index')
  assert.equal(jumpLabelFor(state.items[2]!), null)
  // Opening the unread review marks it read once; the already-read one is never re-marked.
  await controller.open('701')
  assert.ok(controller.getSnapshot().detail?.readAt)
  await controller.open('702')
  assert.ok(controller.getSnapshot().detail?.readAt)
  controller.closeDetail()
  assert.equal(controller.getSnapshot().detail, null)
})

test('preview markRead flips readAt locally and stays idempotent', async () => {
  const deps = previewNotificationDeps()
  const first = await deps.markRead('701')
  assert.equal(first.readAt, time)
  const second = await deps.markRead('701')
  assert.equal(second.readAt, first.readAt)
  const detail = await deps.detail('701')
  assert.equal(detail.readAt, first.readAt)
})

test('disposed merchant controller ignores late list results', async () => {
  let resolve!: (value: { items: InboxNotification[]; total: number }) => void
  const late = new Promise<{ items: InboxNotification[]; total: number }>(ok => { resolve = ok })
  const controller = new MessagesController(depsWith({ list: () => late }))
  const loading = controller.load()
  controller.dispose()
  resolve({ items: [item()], total: 1 })
  await loading
  assert.equal(controller.getSnapshot().status, 'loading')
})
