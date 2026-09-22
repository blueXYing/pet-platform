import { consumerApi } from '../../shared/consumer-runtime'
import { NotificationRepository } from '../../shared/notification-repositories'
import type { NotificationDeps } from './messages'

declare const MERCHANT_APPLICATION_ENABLED: boolean

export function realNotificationDeps(): NotificationDeps {
  if (!MERCHANT_APPLICATION_ENABLED) {
    const unavailable = async (): Promise<never> => { throw new Error('NOTIFICATIONS_NOT_CONNECTED') }
    return { list: unavailable, detail: unavailable, markRead: unavailable }
  }
  const repository = new NotificationRepository(consumerApi)
  return {
    list: () => repository.list(1, 20),
    detail: id => repository.detail(id),
    markRead: id => repository.markRead(id),
  }
}
