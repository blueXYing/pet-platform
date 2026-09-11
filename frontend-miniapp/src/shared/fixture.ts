import type { Workspace } from './workspace'
// INTERNAL ONLY: no endpoint, no session DTO, no permissions or merchant admission service.
export const consumerFixture: Workspace = Object.freeze({
  userId: '9007199254740993', workspace: 'consumer', merchantId: null, storeId: null,
})
export type EngineeringFixture = { id: string; amount: string; displayStatus: string; actions: Readonly<Record<string, boolean>> }
export function decodeEngineeringFixture(data: unknown): EngineeringFixture {
  const value = data as Partial<EngineeringFixture> | null
  if (!value || typeof value.id !== 'string' || typeof value.amount !== 'string'
      || !/^\d+\.\d{2}$/.test(value.amount) || typeof value.displayStatus !== 'string'
      || !value.actions || typeof value.actions !== 'object'
      || Object.values(value.actions).some(v => typeof v !== 'boolean')) throw new Error('INVALID_FIXTURE')
  return value as EngineeringFixture
}
export async function loadEngineeringFixture(): Promise<EngineeringFixture> {
  return decodeEngineeringFixture({ id: '9007199254740993', amount: '128.00',
    displayStatus: 'INTERNAL_SAMPLE', actions: { inspect: true } })
}
