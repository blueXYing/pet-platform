import { ConsumerApi } from '../../shared/consumer-api'
import { ApiError } from '../../shared/request'
import {
  decodeCategoryList, decodeCommandReceipt, decodeManagedServiceDetail, decodeManagedServicePage,
  type ServiceCategoryView, type ServiceCommandReceipt, type ServiceDraftInput, type ServiceManageDeps,
  type ManagedServiceDetail, type ManagedServicePage,
} from './model'

function toBody(input: ServiceDraftInput, merchantId: string, storeId: string): Record<string, unknown> {
  // Omit-empty optional text fields as the C HTTP parser rejects explicit nulls (pet PUT precedent);
  // the server treats omitted optional fields as null.
  const data: Record<string, unknown> = {
    merchantId, storeId,
    serviceName: input.serviceName.trim(),
    categoryId: input.categoryId ?? '',
    fulfillmentType: input.fulfillmentType ?? '',
    price: input.price ?? '',
    durationMinutes: input.durationMinutes ?? 0,
    coverAssetId: input.coverAssetId ?? '',
    applicablePetTypes: [...input.applicablePetTypes],
    verificationRequired: input.verificationRequired,
  }
  if (input.listPrice !== null) data.listPrice = input.listPrice
  if (input.staffRequirement !== null) data.staffRequirement = input.staffRequirement
  if (input.description !== null) data.description = input.description
  if (input.aftersaleNote !== null) data.aftersaleNote = input.aftersaleNote
  if (input.remark !== null) data.remark = input.remark
  return data
}

/**
 * Real wiring for the M-002 service-management routes on top of the principle-approved
 * service-write contract (role A implementing; not yet frozen in the authoritative docs).
 * Until the backend ships, requests fail closed through the normal error paths — the pages
 * never pretend a real integration succeeded. Writes journal per-slot X-Request-Id commands
 * through ConsumerApi.write so retries replay the same requestId after a lost response.
 */
export class RealServiceManageRepository implements ServiceManageDeps {
  constructor(private api: ConsumerApi, private merchantId: () => string, private storeId: () => string) {}

  private target(): { merchantId: string; storeId: string } {
    const merchantId = this.merchantId(), storeId = this.storeId()
    if (!merchantId || !storeId) throw new Error('WORKSPACE_PATH_MISMATCH')
    return { merchantId, storeId }
  }

  categories(): Promise<ServiceCategoryView[]> {
    this.target() // merchant workspace coordinates required even for the dictionary read
    return this.api.request({ method: 'GET', path: '/api/v1/merchant/service-categories' }, decodeCategoryList)
  }

  list(page = 1, pageSize = 20): Promise<ManagedServicePage> {
    const { merchantId, storeId } = this.target()
    return this.api.request({ method: 'GET', path: '/api/v1/merchant/services', data: { merchantId, storeId, page, pageSize } }, decodeManagedServicePage)
  }

  detail(serviceId: string): Promise<ManagedServiceDetail> {
    const { merchantId, storeId } = this.target()
    return this.api.request({ method: 'GET', path: `/api/v1/merchant/services/${serviceId}`, data: { merchantId, storeId } }, value => {
      const detail = decodeManagedServiceDetail(value)
      if (detail.serviceId !== serviceId) throw new Error('INVALID_RESPONSE')
      return detail
    })
  }

  create(slot: string, input: ServiceDraftInput): Promise<ServiceCommandReceipt> {
    const { merchantId, storeId } = this.target()
    return this.api.write(slot, { method: 'POST', path: '/api/v1/merchant/services', data: toBody(input, merchantId, storeId) }, decodeCommandReceipt)
  }

  update(slot: string, serviceId: string, expectedVersion: string, input: ServiceDraftInput): Promise<ServiceCommandReceipt> {
    const { merchantId, storeId } = this.target()
    return this.api.write(slot, {
      method: 'PUT', path: `/api/v1/merchant/services/${serviceId}`,
      data: { ...toBody(input, merchantId, storeId), expectedVersion },
    }, value => {
      const receipt = decodeCommandReceipt(value)
      if (receipt.serviceId !== serviceId) throw new Error('INVALID_RESPONSE')
      return receipt
    })
  }

  submitOnline(slot: string, serviceId: string, expectedVersion: string): Promise<ServiceCommandReceipt> {
    const { merchantId, storeId } = this.target()
    return this.api.write(slot, {
      method: 'POST', path: `/api/v1/merchant/services/${serviceId}/online`,
      query: { merchantId, storeId }, data: { expectedVersion },
    }, value => {
      const receipt = decodeCommandReceipt(value)
      if (receipt.serviceId !== serviceId || receipt.status !== 'REVIEWING') throw new Error('INVALID_RESPONSE')
      return receipt
    })
  }

  takeOffline(slot: string, serviceId: string, expectedVersion: string): Promise<ServiceCommandReceipt> {
    const { merchantId, storeId } = this.target()
    return this.api.write(slot, {
      method: 'POST', path: `/api/v1/merchant/services/${serviceId}/offline`,
      query: { merchantId, storeId }, data: { expectedVersion },
    }, value => {
      const receipt = decodeCommandReceipt(value)
      if (receipt.serviceId !== serviceId || receipt.status !== 'OFFLINE') throw new Error('INVALID_RESPONSE')
      return receipt
    })
  }
}

export function serviceManageMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'SERVICE_STATE_NOT_ALLOWED') return '当前服务状态不允许该操作；上架中的服务需先下架才能编辑。'
    if (error.code === 'SERVICE_REVIEW_REASON_REQUIRED') return '提交审核前请补齐必填信息（名称、分类、履约方式、价格、时长、封面、适用宠物类型）。'
    if (error.code === 'COMMON_CONFLICT') return '内容已被修改，请刷新后重试；请勿更换请求盲目重试。'
    if (error.code === 'IDEMPOTENCY_KEY_CONFLICT') return '同一请求编号已被其他内容使用，请刷新后重试。'
    if (error.statusCode === 401) return '登录已失效，请重新登录。'
    if (error.statusCode === 404) return '服务不存在或已删除，请返回列表刷新。'
    if (error.statusCode === 503) return '服务暂不可用，操作结果未确认；请用原按钮重试。'
  }
  if (error instanceof Error && error.message === 'PENDING_WRITE_CHANGED') return '上次操作结果尚未确认，请先重试原操作。'
  if (error instanceof Error && error.message === 'WORKSPACE_PATH_MISMATCH') return '请从商家工作台进入服务管理。'
  return '操作未确认成功，请重试；不会重复创建。'
}
