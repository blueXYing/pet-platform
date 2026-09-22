import type { MerchantAdmission, MerchantMembership } from '../shared/merchant-repositories'
import type { Workspace } from '../shared/workspace'

/**
 * Admission dependencies injected into the workspace controller (CCR-W2-ADMISSION-001):
 * memberships run on consumer coordinates; admission runs on the selected merchant
 * coordinates and is re-checked on every entry — an old allowed view is never a cache hit.
 */
export type AdmissionDeps = {
  memberships(): Promise<{ items: MerchantMembership[] }>
  admission(context: Workspace, merchantId: string, storeId: string): Promise<MerchantAdmission>
}

export type AdmissionFixture =
  | 'no-stores' | 'multi-store' | 'allowed' | 'limited' | 'denied' | 'error' | 'store-error'

const sampleView = (admission: 'ALLOWED' | 'LIMITED' | 'DENIED'): MerchantAdmission => ({
  merchantId: '9007199254740995',
  storeId: '9007199254740996',
  membershipKind: 'OWNER',
  admission,
  checkedAt: '2026-09-22T00:00:00.000Z',
  authzVersion: '0123456789abcdef',
  facts: {
    application: { status: 'APPROVED' },
    signing: { status: admission === 'DENIED' ? 'NOT_SIGNED' : 'SIGNED' },
    storeStatus: admission === 'LIMITED' ? 'FROZEN' : 'ACTIVE',
    merchantStatus: 'ACTIVE',
    staffEnabled: null,
  },
  allowedActions: admission === 'ALLOWED'
    ? ['merchant.aftersale.read', 'merchant.order.read', 'merchant.penalty.read', 'merchant.schedule.manage', 'merchant.service.manage', 'merchant.staff.manage']
    : admission === 'LIMITED'
      ? ['merchant.aftersale.read', 'merchant.order.read', 'merchant.penalty.appeal', 'merchant.penalty.read']
      : [],
  reasonCodes: admission === 'LIMITED' ? ['STORE_FROZEN'] : admission === 'DENIED' ? ['SIGNING_REQUIRED'] : [],
  nextSteps: admission === 'LIMITED'
    ? [{ type: 'APPEAL' }, { type: 'VIEW_AFTERSALES' }, { type: 'VIEW_EXISTING_ORDERS' }]
    : admission === 'DENIED' ? [{ type: 'COMPLETE_SIGNING' }] : [],
})

/** Engineering double for tests; never a product path. */
export function fixtureDeps(scenario: AdmissionFixture): AdmissionDeps {
  return {
    memberships: async () => {
      if (scenario === 'error') throw new Error('INTERNAL_QUERY_FAILURE')
      if (scenario === 'no-stores') return { items: [] }
      if (scenario === 'multi-store')
        return {
          items: [
            { merchantId: '9007199254740995', merchantName: '样本商家一', storeId: '9007199254740996', storeName: '样本门店一', membershipKind: 'OWNER' },
            { merchantId: '9007199254740995', merchantName: '样本商家一', storeId: '9007199254740997', storeName: '样本门店二', membershipKind: 'OWNER' },
          ],
        }
      return { items: [{ merchantId: '9007199254740995', merchantName: '样本商家一', storeId: '9007199254740996', storeName: '样本门店一', membershipKind: 'OWNER' }] }
    },
    admission: async () => {
      if (scenario === 'store-error') throw new Error('INTERNAL_QUERY_FAILURE')
      return sampleView(scenario === 'allowed' ? 'ALLOWED' : scenario === 'limited' ? 'LIMITED' : 'DENIED')
    },
  }
}
