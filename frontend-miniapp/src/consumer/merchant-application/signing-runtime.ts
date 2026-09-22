import { consumerApi } from '../../shared/consumer-runtime'
import { MerchantAgreementRepository } from '../../shared/merchant-repositories'
import { unavailableSigningDependencies, type SigningDependencies } from './signing'

declare const MERCHANT_APPLICATION_ENABLED: boolean

export function signingRuntime(preview: boolean): SigningDependencies {
  if (preview || !MERCHANT_APPLICATION_ENABLED) return unavailableSigningDependencies()
  const repository = new MerchantAgreementRepository(consumerApi)
  return { read: m => repository.read(m), consent: (v, a) => repository.consent(v, a), pendingConsent: m => repository.pendingConsent(m), retryConsent: m => repository.retryConsent(m), retireConsent: (m, r) => repository.retireConsent(m, r) }
}
