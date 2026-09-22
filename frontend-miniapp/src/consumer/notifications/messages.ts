import type { InboxNotification } from '../../shared/notification-repositories'
import { ApiError } from '../../shared/request'

export type NotificationDeps = {
  list(): Promise<{ items: InboxNotification[]; total: number }>
  detail(id: string): Promise<InboxNotification>
  markRead(id: string): Promise<{ readAt: string }>
}
export type MessagesState = Readonly<{
  status: 'loading' | 'ready' | 'empty' | 'error' | 'unauthorized'
  items: readonly InboxNotification[]
  total: number
  detail: InboxNotification | null
  notice: string
}>

// One controller per mounted page. Opening an item marks it read and reveals the whitelist
// jump; navigation targets re-authenticate on their own pages.
export class MessagesController {
  private state: MessagesState = { status: 'loading', items: [], total: 0, detail: null, notice: '' }
  private listeners = new Set<() => void>()
  private active = true
  private run = 0
  constructor(private deps: NotificationDeps) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  private publish(next: MessagesState) {
    this.state = Object.freeze(next)
    this.listeners.forEach(listener => listener())
  }
  private fail(error: unknown) {
    if (!this.active) return
    this.publish({
      status: error instanceof ApiError && error.statusCode === 401 ? 'unauthorized' : 'error',
      items: [], total: 0, detail: null,
      notice: error instanceof ApiError && error.statusCode === 401
        ? '登录已失效，请重新登录。'
        : '消息读取失败，请稍后重试。',
    })
  }
  async load() {
    if (!this.active) return
    const run = ++this.run
    this.publish({ status: 'loading', items: [], total: 0, detail: null, notice: '' })
    try {
      const page = await this.deps.list()
      if (!this.active || run !== this.run) return
      this.publish(page.items.length
        ? { status: 'ready', items: page.items, total: page.total, detail: null, notice: '' }
        : { status: 'empty', items: [], total: 0, detail: null, notice: '暂无消息。' })
    } catch (error) {
      if (run === this.run) this.fail(error)
    }
  }
  async open(notificationId: string) {
    if (!this.active) return
    const run = ++this.run
    try {
      const item = await this.deps.detail(notificationId)
      if (!this.active || run !== this.run) return
      // Marking read is result-idempotent; a lost response still leaves it read server-side.
      let readAt = item.readAt
      if (readAt === null) {
        try { readAt = (await this.deps.markRead(notificationId)).readAt } catch { readAt = item.readAt }
      }
      if (!this.active || run !== this.run) return
      const marked = readAt === null ? item : { ...item, readAt }
      this.publish({ status: 'ready', items: this.state.items.map(existing => existing.id === notificationId ? marked : existing), total: this.state.total, detail: marked, notice: '' })
    } catch (error) {
      if (run === this.run) this.fail(error)
    }
  }
  closeDetail() {
    if (!this.active || this.state.detail === null) return
    this.publish({ ...this.state, detail: null })
  }
  dispose() {
    this.active = false
    this.run++
    this.listeners.clear()
  }
}

// Whitelist routing only (CCR-W2-NOTIFICATION-001 section 5); the payload never carries URLs
// and the target page performs its own owner-scoped query.
export function routeForNotification(item: InboxNotification): string | null {
  if (item.messageType === 'MERCHANT_APPLICATION_REVIEWED' && item.bizType === 'MERCHANT_APPLICATION' && item.bizId !== null) {
    return '/consumer/pages/merchant-application/index'
  }
  return null
}
