import { ConsumerApi, id, object } from './consumer-api'
const invalid = (): never => { throw new Error('INVALID_RESPONSE') }
function exact(value: unknown, keys: readonly string[]) {
  const v = object(value)
  if (Object.keys(v).some(key => !keys.includes(key))) invalid()
  return v
}
function text(value: unknown, max: number, min = 1): string {
  if (typeof value !== 'string' || [...value].length < min || [...value].length > max) invalid()
  return value as string
}
function oneOf<const T extends readonly string[]>(value: unknown, values: T): T[number] {
  if (typeof value !== 'string' || !values.includes(value)) invalid()
  return value as T[number]
}

function timestamp(value: unknown): string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})(?![\s\S])/.test(value) || !Number.isFinite(Date.parse(value))) invalid()
  return value as string
}
const bigWord = (value: unknown): string => {
  if (typeof value !== 'string' || !/^[A-Z][A-Z0-9_]{2,63}$/.test(value)) invalid()
  return value as string
}
export function decodeNotification(value: unknown) {
  const v = exact(value, ['id', 'category', 'messageType', 'bizType', 'bizId', 'title', 'content', 'readAt', 'createdAt'])
  const result = {
    id: id(v.id),
    category: oneOf(v.category, ['INTERACTION', 'SERVICE', 'SYSTEM'] as const),
    messageType: bigWord(v.messageType),
    bizType: v.bizType === null ? null : bigWord(v.bizType),
    bizId: v.bizId === null ? null : id(v.bizId),
    title: text(v.title, 128),
    content: text(v.content, 1000),
    readAt: v.readAt === null ? null : timestamp(v.readAt),
    createdAt: timestamp(v.createdAt),
  }
  return result
}
export type InboxNotification = ReturnType<typeof decodeNotification>
export function decodeNotificationPage(value: unknown) {
  const v = exact(value, ['items', 'page', 'pageSize', 'total'])
  if (!Array.isArray(v.items) || v.items.length > 50) invalid()
  const page = Number(v.page), pageSize = Number(v.pageSize), total = Number(v.total)
  if (!Number.isInteger(page) || page < 1 || page > 10000) invalid()
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50) invalid()
  if (!Number.isInteger(total) || total < 0) invalid()
  return { items: v.items.map(decodeNotification), page, pageSize, total }
}
export function decodeReadReceipt(value: unknown) {
  const v = exact(value, ['id', 'readAt'])
  return { id: id(v.id), readAt: timestamp(v.readAt) }
}
/** CCR-W2-NOTIFICATION-001 client for the owner-scoped USER inbox. */
export class NotificationRepository {
  constructor(private api: ConsumerApi) {}
  list(page = 1, pageSize = 20) {
    return this.api.request({ path: '/api/v1/c/notifications', method: 'GET', data: { page, pageSize } }, decodeNotificationPage)
  }
  detail(notificationId: string) {
    notificationId = id(notificationId)
    return this.api.request({ path: `/api/v1/c/notifications/${notificationId}`, method: 'GET' }, value => {
      const result = decodeNotification(value)
      if (result.id !== notificationId) invalid()
      return result
    })
  }
  markRead(notificationId: string) {
    notificationId = id(notificationId)
    return this.api.write(`notification:${notificationId}:read`, { path: `/api/v1/c/notifications/${notificationId}/read`, method: 'POST', data: {} }, value => {
      const result = decodeReadReceipt(value)
      if (result.id !== notificationId) invalid()
      return result
    })
  }
}
