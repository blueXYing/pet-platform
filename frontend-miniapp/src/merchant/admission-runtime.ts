import { consumerApi } from '../shared/consumer-runtime'
import { MerchantAdmissionRepository } from '../shared/merchant-repositories'
import type { AdmissionDeps } from './admission'

declare const MERCHANT_APPLICATION_ENABLED: boolean

/** Real admission wiring; fails closed when the merchant module is not enabled. */
export function realDeps(): AdmissionDeps {
  if (!MERCHANT_APPLICATION_ENABLED) {
    const unavailable = async (): Promise<never> => { throw new Error('ADMISSION_NOT_CONNECTED') }
    return { memberships: unavailable, admission: unavailable }
  }
  const repository = new MerchantAdmissionRepository(consumerApi)
  return {
    memberships: () => repository.memberships(1, 20),
    admission: (context, merchantId, storeId) => {
      if (context.workspace !== 'merchant' || context.merchantId !== merchantId) {
        return Promise.reject(new Error('WORKSPACE_PATH_MISMATCH'))
      }
      return repository.admission(merchantId, storeId)
    },
  }
}
