import { ConsumerApi, object, id } from '../../shared/consumer-api'
import { type AddressDraft, type AddressStatus, type AddressView } from './model'

// 地址管理真实仓库（契约 59 via HTTP10 §3.2.2）。六条路由都落在 ConsumerApi 既有
// /c/ 白名单内，不改 consumer-api.ts。写操作走 23号 X-Request-Id 幂等槽：
// - create：槽 address:create（首次 201 / 同键重放 200，回执同一投影）；
// - update：槽 address:{addressId}（PUT 全量替换五字段）；
// - set-default：槽 address-default:{addressId}（POST 无业务体，决策路由）；
// - delete：槽 address-delete:{addressId}（软删回执 {addressId,status}）。
// 读侧（列表/详情）走 request 匿名白名单外的登录态只读；404/401 走常规错误路径。

export function decodeAddress(value: unknown): AddressView {
  const v = object(value)
  id(v.addressId)
  for (const key of ['contactName', 'contactPhone', 'region', 'detail']) {
    if (typeof v[key] !== 'string') throw new Error('INVALID_RESPONSE')
  }
  if (typeof v.isDefault !== 'boolean' || !['ACTIVE', 'DELETED'].includes(v.status)) throw new Error('INVALID_RESPONSE')
  return v as AddressView
}

function decodeAddressReceipt(value: unknown, addressId: string): { addressId: string; status: AddressStatus } {
  const receipt = object(value)
  if (receipt.addressId !== addressId || receipt.status !== 'DELETED') throw new Error('INVALID_RESPONSE')
  return { addressId, status: 'DELETED' as const }
}

function draftToData(draft: AddressDraft): Record<string, unknown> {
  return { contactName: draft.contactName.trim(), contactPhone: draft.contactPhone, region: draft.region.trim(), detail: draft.detail.trim(), isDefault: draft.isDefault }
}

export class RealAddressRepository {
  constructor(private api: ConsumerApi) {}
  async list(): Promise<AddressView[]> {
    return this.api.request({ method: 'GET', path: '/api/v1/c/addresses' }, value => {
      if (!Array.isArray(value)) throw new Error('INVALID_RESPONSE')
      return value.map(decodeAddress)
    })
  }
  async get(addressId: string): Promise<AddressView> {
    const target = id(addressId)
    return this.api.request({ method: 'GET', path: `/api/v1/c/addresses/${target}` }, value => {
      const address = decodeAddress(value)
      if (address.addressId !== target) throw new Error('INVALID_RESPONSE')
      return address
    })
  }
  async save(addressId: string | null, draft: AddressDraft, _previewId?: string): Promise<AddressView> {
    const data = draftToData(draft)
    return addressId
      ? this.api.write(`address:${id(addressId)}`, { method: 'PUT', path: `/api/v1/c/addresses/${addressId}`, data }, decodeAddress)
      : this.api.write('address:create', { method: 'POST', path: '/api/v1/c/addresses', data }, decodeAddress)
  }
  async setDefault(addressId: string, _previewId?: string): Promise<AddressView> {
    const target = id(addressId)
    // set-default 无业务体（契约 59 §17 决策路由）：POST 不带 data，X-Request-Id 仍由 write 注入。
    return this.api.write(`address-default:${target}`, { method: 'POST', path: `/api/v1/c/addresses/${target}/set-default` }, decodeAddress)
  }
  async remove(addressId: string, _previewId?: string): Promise<{ addressId: string; status: AddressStatus }> {
    const target = id(addressId)
    return this.api.write(`address-delete:${target}`, { method: 'DELETE', path: `/api/v1/c/addresses/${target}` }, value => decodeAddressReceipt(value, target))
  }
}
