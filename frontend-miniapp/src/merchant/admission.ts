import type { Workspace } from '../shared/workspace'

// Private engineering injection, not an HTTP/session/permission DTO. No business status mapping.
export type AdmissionFixture = 'allow' | 'deny' | 'error' | 'other-store'
export type AdmissionResult = { allowed: false } | { allowed: true; merchantId: string; storeId: string }
export type AdmissionAdapter = (context: Workspace) => Promise<AdmissionResult>

export function fixtureAdmission(scenario: AdmissionFixture): AdmissionAdapter {
  return async () => {
    if (scenario === 'error') throw new Error('INTERNAL_QUERY_FAILURE')
    if (scenario === 'deny') return { allowed: false }
    return { allowed: true, merchantId: '9007199254740995',
      storeId: scenario === 'other-store' ? '9007199254740997' : '9007199254740996' }
  }
}

export function realAdmission(): AdmissionAdapter {
  return async () => { throw new Error('CCR_ACR_001_NOT_APPROVED') }
}
