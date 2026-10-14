// 地址管理视图模型（契约 59 / CCR-USR-ADDRESS-001）：类型、校验、展示层脱敏与
// 订单链接平铺（region + 空格 + detail 进既有 serviceAddress 快照，订单契约零改动）。
// 纯函数、无平台依赖；页面与 BookingSheet 共用，node 测试直接驱动本模块。
export type AddressStatus = 'ACTIVE' | 'DELETED'
export type AddressView = Readonly<{
  addressId: string; contactName: string; contactPhone: string
  region: string; detail: string; isDefault: boolean; status: AddressStatus
}>
export type AddressDraft = Readonly<{
  contactName: string; contactPhone: string; region: string; detail: string; isDefault: boolean
}>
export type AddressPhase = 'loading' | 'ready' | 'saving' | 'load-error' | 'save-error' | 'expired'

export const codePointLength = (value: string) => Array.from(value).length

// 契约 59 §28：大陆手机号 1[3-9] 开头 11 位数字（与后端 CAddressController 同口径）。
export const CONTACT_PHONE = /^1[3-9][0-9]{9}$/

/** 列表页展示层脱敏（设计稿 138****5678 形态）；编辑表单回填明文。 */
export function maskPhone(phone: string): string {
  return /^[1][0-9]{10}$/.test(phone) ? `${phone.slice(0, 3)}****${phone.slice(7)}` : phone
}

/** 订单链接法：地址簿条目平铺进既有 serviceAddress 文本快照（region + 空格 + detail）。 */
export function addressFlat(address: Pick<AddressView, 'region' | 'detail'>): string {
  return `${address.region} ${address.detail}`.trim()
}

export type AddressDraftErrors = Partial<Record<'contactName' | 'contactPhone' | 'region' | 'detail', string>>
export function validateDraft(draft: AddressDraft): AddressDraftErrors {
  const errors: AddressDraftErrors = {}
  const name = draft.contactName.trim()
  if (!name) errors.contactName = '请填写收货人'
  else if (codePointLength(name) > 32) errors.contactName = '收货人最多32字'
  if (!CONTACT_PHONE.test(draft.contactPhone)) errors.contactPhone = '请填写正确的手机号'
  const region = draft.region.trim()
  if (!region) errors.region = '请填写所在地区'
  else if (codePointLength(region) > 64) errors.region = '所在地区最多64字'
  const detail = draft.detail.trim()
  if (!detail) errors.detail = '请填写详细地址'
  else if (codePointLength(detail) > 256) errors.detail = '详细地址最多256字'
  return errors
}
export function sameDraft(a: AddressDraft, b: AddressDraft) {
  return a.contactName === b.contactName && a.contactPhone === b.contactPhone
    && a.region === b.region && a.detail === b.detail && a.isDefault === b.isDefault
}
export const emptyDraft: AddressDraft = { contactName: '', contactPhone: '', region: '', detail: '', isDefault: false }

export function addressWriteMessage(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'code' in error) {
    const code = String((error as { code?: unknown }).code)
    if (code === 'ADDRESS_LIMIT_EXCEEDED') return '地址已达上限（20条），请先删除不再使用的地址'
    if (code === 'IDEMPOTENCY_KEY_CONFLICT') return '操作已在进行中，请稍后重试'
    if (code === 'ADDRESS_NOT_FOUND') return '地址不存在或已删除'
    if (code === 'USER_FROZEN') return '账号已被冻结，无法修改地址'
    if (code === 'REQUEST_CONFLICT' || code === 'PENDING_WRITE_CHANGED') return '操作内容已变化，请重新提交'
  }
  return '保存失败，请稍后重试'
}

export type AddressScenario = 'normal' | 'empty' | 'load-error' | 'save-error' | 'expired'
export const isAddressScenario = (value?: string): value is AddressScenario =>
  ['normal', 'empty', 'load-error', 'save-error', 'expired'].includes(value || '')

// 设计稿（129:13141）样例：李小白/138****5678/默认胶囊 + 浦东、徐汇两址。
export const fixtureAddresses: AddressView[] = [
  {
    addressId: '40001', contactName: '李小白', contactPhone: '13800005678',
    region: '上海市 浦东新区', detail: '张江高科技园区祖冲之路887弄12号3楼', isDefault: true, status: 'ACTIVE',
  },
  {
    addressId: '40002', contactName: '李小白', contactPhone: '13800005678',
    region: '上海市 徐汇区', detail: '漕溪北路88号5楼501室', isDefault: false, status: 'ACTIVE',
  },
]
export const formFixture: AddressDraft = {
  contactName: '李小白', contactPhone: '13800005678', region: '上海市 浦东新区',
  detail: '张江高科技园区祖冲之路887弄12号3楼', isDefault: false,
}

// preview 仓库跨页共享但绝不跨工作区修订（同 pet 模式）。
const previewStores = new WeakMap<object, { revision: number; repositories: Map<AddressScenario, PreviewAddressRepository> }>()
export function previewRepository(scope: { revision: number }, scenario: AddressScenario): PreviewAddressRepository {
  let entry = previewStores.get(scope)
  if (!entry || entry.revision !== scope.revision) {
    entry = { revision: scope.revision, repositories: new Map() }
    previewStores.set(scope, entry)
  }
  let repository = entry.repositories.get(scenario)
  if (!repository) {
    repository = new PreviewAddressRepository(scenario === 'empty' ? [] : fixtureAddresses, scenario)
    entry.repositories.set(scenario, repository)
  }
  return repository
}

// 显式 preview 仓库：无网络、无会话、无持久用户数据；幂等回执与真实链同语义。
export class PreviewAddressRepository {
  private addresses: AddressView[]
  private failLoad: boolean
  private failSave: boolean
  private receipts = new Map<string, { request: object; value: object }>()
  private sequence = 0
  constructor(initial: AddressView[] = fixtureAddresses, scenario: AddressScenario = 'normal') {
    this.addresses = initial.map(address => ({ ...address }))
    this.failLoad = scenario === 'load-error'
    this.failSave = scenario === 'save-error'
  }
  async list(): Promise<AddressView[]> {
    if (this.failLoad) { this.failLoad = false; throw new Error('PREVIEW_LOAD_FAILED') }
    return this.addresses.filter(address => address.status === 'ACTIVE').map(address => ({ ...address }))
  }
  async get(addressId: string): Promise<AddressView> {
    const found = (await this.list()).find(address => address.addressId === addressId)
    if (!found) throw new Error('ADDRESS_NOT_FOUND')
    return found
  }
  private flipDefault(addressId: string) {
    this.addresses = this.addresses.map(address =>
      address.addressId === addressId ? { ...address, isDefault: true }
        : address.isDefault ? { ...address, isDefault: false } : address)
  }
  async save(addressId: string | null, draft: AddressDraft, requestId: string): Promise<AddressView> {
    if (Object.keys(validateDraft(draft)).length) throw new Error('INVALID_DRAFT')
    const request = { addressId, draft: { ...draft } }
    const receipt = this.receipts.get(requestId)
    if (receipt) {
      if (JSON.stringify(receipt.request) !== JSON.stringify(request)) throw new Error('REQUEST_CONFLICT')
      return receipt.value as AddressView
    }
    if (this.failSave) { this.failSave = false; throw new Error('PREVIEW_SAVE_FAILED') }
    let value: AddressView
    if (addressId) {
      const index = this.addresses.findIndex(address => address.addressId === addressId && address.status === 'ACTIVE')
      if (index < 0) throw new Error('ADDRESS_NOT_FOUND')
      value = { ...this.addresses[index], contactName: draft.contactName, contactPhone: draft.contactPhone, region: draft.region, detail: draft.detail, isDefault: draft.isDefault }
      this.addresses[index] = value
    } else {
      if (this.addresses.filter(address => address.status === 'ACTIVE').length >= 20) throw new Error('ADDRESS_LIMIT_EXCEEDED')
      value = { addressId: `preview-${++this.sequence}`, contactName: draft.contactName, contactPhone: draft.contactPhone, region: draft.region, detail: draft.detail, isDefault: draft.isDefault, status: 'ACTIVE' }
      this.addresses.push(value)
    }
    if (draft.isDefault) this.flipDefault(value.addressId)
    this.receipts.set(requestId, { request, value: { ...value } })
    return { ...value }
  }
  async setDefault(addressId: string, requestId: string): Promise<AddressView> {
    const request = { addressId }
    const receipt = this.receipts.get(requestId)
    if (receipt) {
      if (JSON.stringify(receipt.request) !== JSON.stringify(request)) throw new Error('REQUEST_CONFLICT')
      return receipt.value as AddressView
    }
    const target = this.addresses.find(address => address.addressId === addressId && address.status === 'ACTIVE')
    if (!target) throw new Error('ADDRESS_NOT_FOUND')
    this.flipDefault(addressId)
    // flipDefault 以映射拷贝替换数组，须重取翻转后的行再回执。
    const updated = this.addresses.find(address => address.addressId === addressId)
    if (!updated) throw new Error('ADDRESS_NOT_FOUND')
    this.receipts.set(requestId, { request, value: { ...updated } })
    return { ...updated }
  }
  async remove(addressId: string, requestId: string): Promise<{ addressId: string; status: AddressStatus }> {
    const request = { addressId }
    const receipt = this.receipts.get(requestId)
    if (receipt) {
      if (JSON.stringify(receipt.request) !== JSON.stringify(request)) throw new Error('REQUEST_CONFLICT')
      return receipt.value as { addressId: string; status: AddressStatus }
    }
    const index = this.addresses.findIndex(address => address.addressId === addressId && address.status === 'ACTIVE')
    if (index < 0) throw new Error('ADDRESS_NOT_FOUND')
    this.addresses[index] = { ...this.addresses[index], status: 'DELETED' }
    const value = { addressId, status: 'DELETED' as AddressStatus }
    this.receipts.set(requestId, { request, value })
    return value
  }
}

// 设计采样核对辅助：渲染层卡片文案聚合（姓名+脱敏手机号），供测试与 aria 复用。
export function cardHeadline(address: AddressView): string {
  return `${address.contactName} ${maskPhone(address.contactPhone)}`
}
