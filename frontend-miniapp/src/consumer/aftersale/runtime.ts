import { ConsumerAfterSaleController, type Pending } from './model'
import type { AfterSaleClient, CreateInput, EvidenceInput } from '../../shared/aftersale-api'
import { version } from '../../shared/aftersale-api'
import { object } from '../../shared/consumer-api'

export type AfterSaleCatalog = { types: { code: string; label: string }[]; demands: { code: string; label: string }[] }
export type AfterSaleCatalogPort = () => Promise<AfterSaleCatalog | null>
// Contract51 has no public authoritative catalog read. Production fails closed until a
// reviewed CCR supplies this port; test injection never becomes a production fallback.
export const afterSaleCatalog: AfterSaleCatalogPort = async () => null
export function catalogSelectionErrors(catalog: AfterSaleCatalog | null, draft: { typeCode: string; demandCode: string }) {
  if (!catalog || !catalog.types.length || !catalog.demands.length) return { typeCode: '申请选项暂不可用，请稍后再试', demandCode: '申请选项暂不可用，请稍后再试' }
  return {
    ...(!catalog.types.some(item => item.code === draft.typeCode) ? { typeCode: '请重新选择当前可用的问题类型' } : {}),
    ...(!catalog.demands.some(item => item.code === draft.demandCode) ? { demandCode: '请重新选择当前可用的诉求' } : {}),
  }
}

export function consumerController(client: AfterSaleClient) {
  return new ConsumerAfterSaleController({
    list: query => client.list(query), detail: id => client.detail(id), eligibility: id => client.eligibility(id),
    create: (id, input) => client.create(id, input), evidence: (id, input) => client.evidence(id, input), withdraw: (id, value) => client.withdraw(id, value),
    retireConflict: (target, action, error) => client.retireConflict(target, action, error),
  }, client.api.scope)
}
export function restorePending(client: AfterSaleClient, target: string, apply: boolean): Pending | null {
  if (apply) {
    const body = client.pending(target, 'create')
    if (body) return { kind: 'create', orderId: target, input: object(body) as CreateInput }
  } else {
    const evidence = client.pending(target, 'evidence')
    if (evidence) return { kind: 'evidence', id: target, input: object(evidence) as EvidenceInput }
    const withdraw = client.pending(target, 'withdraw')
    if (withdraw) return { kind: 'withdraw', id: target, version: version(object(withdraw).expectedVersion) }
  }
  return null
}
