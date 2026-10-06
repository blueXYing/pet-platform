import { ConsumerAfterSaleController, type Pending } from './model'
import type { AfterSaleClient, CreateInput, EvidenceInput } from '../../shared/aftersale-api'
import { version } from '../../shared/aftersale-api'
import { object } from '../../shared/consumer-api'
import type { AfterSaleCatalog } from './catalog'
export { catalogSelectionErrors } from './catalog'
export type { AfterSaleCatalog, AfterSaleCatalogPort } from './catalog'

export async function afterSaleCatalog(client: Pick<AfterSaleClient, 'options'>): Promise<AfterSaleCatalog> {
  const options = await client.options()
  return { types: options.typeOptions, demands: options.demandOptions }
}

export function consumerController(client: AfterSaleClient) {
  return new ConsumerAfterSaleController({
    list: query => client.list(query), detail: id => client.detail(id), eligibility: id => client.eligibility(id), catalog: () => afterSaleCatalog(client),
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
