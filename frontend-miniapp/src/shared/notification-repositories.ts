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

// 通知偏好（SSOT §16.4 + SQL06 §11 notification_preference）：仅两个服务端定义的开关——
// interactionEnabled（普通互动提醒）与 externalPushEnabled（微信外部推送偏好）。订单/退款/
// 核销/售后/审核站内消息必须保留，任何偏好不可关闭（服务端事实，页面只作说明呈现）。
// version 为 BIGINT 计数器，按技术基线以字符串传输；updatedAt 在从未保存过（读默认值）时为 null。
export function decodeNotificationPreference(value: unknown) {
  const v = exact(value, ['interactionEnabled', 'externalPushEnabled', 'version', 'updatedAt'])
  if (typeof v.interactionEnabled !== 'boolean' || typeof v.externalPushEnabled !== 'boolean') invalid()
  if (typeof v.version !== 'string' || !/^(0|[1-9][0-9]{0,18})(?![\s\S])/.test(v.version)) invalid()
  return {
    interactionEnabled: v.interactionEnabled,
    externalPushEnabled: v.externalPushEnabled,
    version: v.version,
    updatedAt: v.updatedAt === null ? null : timestamp(v.updatedAt),
  }
}
export type NotificationPreference = ReturnType<typeof decodeNotificationPreference>

/** C 端通知偏好客户端：GET 读取当前偏好，PUT 全量保存（requestId 幂等由 ConsumerApi.write 保障）。 */
export class NotificationPreferenceRepository {
  constructor(private api: ConsumerApi) {}
  load(): Promise<NotificationPreference> {
    return this.api.request({ path: '/api/v1/c/notification-preferences', method: 'GET' }, decodeNotificationPreference)
  }
  save(input: { interactionEnabled: boolean; externalPushEnabled: boolean }): Promise<NotificationPreference> {
    return this.api.write('notification-preferences:update',
      { path: '/api/v1/c/notification-preferences', method: 'PUT', data: { ...input } },
      decodeNotificationPreference)
  }
}
