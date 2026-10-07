import { consumerApi } from '../../shared/consumer-runtime'
import { NotificationPreferenceRepository } from '../../shared/notification-repositories'
import type { PreferenceDeps } from './preferences'

declare const MERCHANT_APPLICATION_ENABLED: boolean

// Same real-connection gate as the inbox runtime: the preference surface assembles with the
// C-end session slice (pet.auth.c.enabled). With the build gate off, every dependency fails
// closed through the page's error path instead of issuing doomed requests.
export function realPreferenceDeps(): PreferenceDeps {
  if (!MERCHANT_APPLICATION_ENABLED) {
    const unavailable = async (): Promise<never> => { throw new Error('PREFERENCES_NOT_CONNECTED') }
    return { load: unavailable, save: unavailable }
  }
  const repository = new NotificationPreferenceRepository(consumerApi)
  return {
    load: () => repository.load(),
    save: input => repository.save(input),
  }
}
