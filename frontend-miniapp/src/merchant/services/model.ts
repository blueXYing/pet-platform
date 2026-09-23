// M-002 service-management view state. Wire shapes follow the service-write contract as
// finalized by role A (10号 §4.10.1 错误码适用面定稿, relayed 2026-09-22) on top of the
// principle-approved v0.2 basis; role A is implementing the backend. Decoders stay strict
// exact-key against the shapes below and the real repository is the single swap point.
// Mock never claims real integration.
//
// Contract anchors (A-side finalization 2026-09-22 + proposal §2/§3/§5.1):
// - status machine DRAFT/REVIEWING/ACTIVE/OFFLINE/REJECTED; editing only DRAFT/REJECTED/OFFLINE;
// - POST /{id}/online means submit-for-review (success status=REVIEWING); offline ACTIVE→OFFLINE;
// - 409 SERVICE_STATE_NOT_ALLOWED for editing ACTIVE/REVIEWING and for non-operable stores;
//   a merchant submit missing required fields (incl. cover) answers 400 COMMON_INVALID_ARGUMENT
//   with details naming the fields — SERVICE_REVIEW_REASON_REQUIRED is admin-side only;
// - latestRejection {decisionId, submissionNo, decisionType, opinion, decidedAt} carries only
//   the most recent REJECT; list/detail also expose submissionNo/submittedAt/updatedAt and a
//   cover object {coverAssetId, coverUrl?, coverUrlExpiresAt?} (signed URL when visible);
// - X-Request-Id idempotency + expectedVersion optimistic concurrency; IDs/prices are Strings;
//   online/offline carry merchantId/storeId/expectedVersion in the BODY (27号 alignment).
export type ServiceManageStatus = 'DRAFT' | 'REVIEWING' | 'ACTIVE' | 'OFFLINE' | 'REJECTED'
export const serviceManageStatuses = ['DRAFT', 'REVIEWING', 'ACTIVE', 'OFFLINE', 'REJECTED'] as const
export const manageStatusText: Record<ServiceManageStatus, string> = {
  DRAFT: '草稿', REVIEWING: '待审核', ACTIVE: '已上架', OFFLINE: '已下架', REJECTED: '审核驳回',
}
/** Editable statuses per the approved state machine (SVCW-D1). */
export const editableStatuses: readonly ServiceManageStatus[] = ['DRAFT', 'REJECTED', 'OFFLINE']
export type FulfillmentType = 'IN_STORE' | 'PICKUP_DELIVERY'
export const fulfillmentText: Record<FulfillmentType, string> = { IN_STORE: '到店型', PICKUP_DELIVERY: '上门接送型' }
export const fulfillments: readonly FulfillmentType[] = ['IN_STORE', 'PICKUP_DELIVERY']
export type ApplicablePetType = 'DOG' | 'CAT' | 'EXOTIC' | 'ALL'
export const petTypeText: Record<ApplicablePetType, string> = { DOG: '狗', CAT: '猫', EXOTIC: '异宠', ALL: '全部' }
export const petTypes: readonly ApplicablePetType[] = ['CAT', 'DOG', 'EXOTIC', 'ALL']
const petTypeSet = new Set<ApplicablePetType>(petTypes)

/** GET /api/v1/merchant/service-categories item (proposal §5.1: ENABLED id/name/sortNo). */
export type ServiceCategoryView = Readonly<{ id: string; name: string; sortNo: number }>

/** Cover projection (A-side finalization): asset anchor plus a signed display URL that is
 *  only returned while visible and expires at coverUrlExpiresAt (refresh placeholder). */
export type ManagedServiceCover = Readonly<{
  coverAssetId: string | null
  coverUrl: string | null
  coverUrlExpiresAt: string | null
}>

/** List projection of GET /api/v1/merchant/services (all statuses, this store only). */
export type ManagedServiceItem = Readonly<{
  serviceId: string; serviceName: string; categoryName: string | null
  price: string | null; status: ServiceManageStatus; version: string
  submissionNo: number; submittedAt: string | null; updatedAt: string
  cover: ManagedServiceCover
}>
/** Most recent REJECT decision only (A-side finalization): opinion is mandatory 10-500. */
export type ManagedServiceRejection = Readonly<{
  decisionId: string; submissionNo: number; decisionType: 'REJECT'
  opinion: string; decidedAt: string
}>
export type ManagedServiceDetail = Readonly<{
  serviceId: string; merchantId: string; storeId: string
  serviceName: string; categoryId: string | null; categoryName: string | null
  fulfillmentType: FulfillmentType | null
  price: string | null; listPrice: string | null
  durationMinutes: number | null
  cover: ManagedServiceCover
  applicablePetTypes: readonly ApplicablePetType[]
  staffRequirement: string | null
  verificationRequired: boolean
  description: string | null
  aftersaleNote: string | null
  remark: string | null
  status: ServiceManageStatus; version: string
  submissionNo: number; submittedAt: string | null; updatedAt: string
  latestRejection: ManagedServiceRejection | null
}>
export type ManagedServicePage = Readonly<{ items: ManagedServiceItem[]; page: number; pageSize: number; total: number }>
/** Success data of POST/PUT/online/offline: {serviceId,status,version} (proposal §5.1). */
export type ServiceCommandReceipt = Readonly<{ serviceId: string; status: ServiceManageStatus; version: string }>

/** Form payload for create (POST) and update (PUT); DRAFT save is loose, submit validates.
 *  The write command keeps a flat coverAssetId (the cover object is the read projection). */
export type ServiceDraftInput = Readonly<{
  serviceName: string
  categoryId: string | null
  fulfillmentType: FulfillmentType | null
  price: string | null
  listPrice: string | null
  durationMinutes: number | null
  coverAssetId: string | null
  applicablePetTypes: readonly ApplicablePetType[]
  staffRequirement: string | null
  verificationRequired: boolean
  description: string | null
  aftersaleNote: string | null
  remark: string | null
}>

export function emptyDraft(): ServiceDraftInput {
  return {
    serviceName: '', categoryId: null, fulfillmentType: null, price: null, listPrice: null,
    durationMinutes: null, coverAssetId: null, applicablePetTypes: [], staffRequirement: null,
    verificationRequired: true, description: null, aftersaleNote: null, remark: null,
  }
}
export function draftFromDetail(detail: ManagedServiceDetail): ServiceDraftInput {
  return {
    serviceName: detail.serviceName, categoryId: detail.categoryId, fulfillmentType: detail.fulfillmentType,
    price: detail.price, listPrice: detail.listPrice, durationMinutes: detail.durationMinutes,
    coverAssetId: detail.cover.coverAssetId, applicablePetTypes: detail.applicablePetTypes,
    staffRequirement: detail.staffRequirement, verificationRequired: detail.verificationRequired,
    description: detail.description, aftersaleNote: detail.aftersaleNote, remark: detail.remark,
  }
}

const invalid = (): never => { throw new Error('INVALID_RESPONSE') }
function exact(value: unknown, keys: readonly string[]): Record<string, any> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid()
  const record = value as Record<string, unknown>
  if (Object.keys(record).some(key => !keys.includes(key))) invalid()
  return value as Record<string, any>
}
const isId = (value: unknown): value is string =>
  typeof value === 'string' && /^[1-9][0-9]{0,18}(?![\s\S])/.test(value) && BigInt(value) <= 9223372036854775807n
export const isVersion = (value: unknown): value is string =>
  typeof value === 'string' && /^(0|[1-9][0-9]{0,18})(?![\s\S])/.test(value) && BigInt(value) <= 9223372036854775807n
const money = (value: any): string => {
  if (typeof value !== 'string' || !/^\d+\.\d{2}$/.test(value)) invalid()
  return value
}
const moneyOrNull = (value: unknown): string | null => value === null ? null : money(value)
/** Two-decimal money strings compare numerically via integer cents (string compare would
 *  order "9.00" > "10.00"). */
const cents = (value: string): bigint => BigInt(value.replace('.', ''))
const textOrNull = (value: any, max: number): string | null => {
  if (value === null) return null
  if (typeof value !== 'string' || [...value].length > max) invalid()
  return value
}
const timestamp = (value: any): string => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})(?![\s\S])/.test(value) || !Number.isFinite(Date.parse(value))) invalid()
  return value
}
const timestampOrNull = (value: any): string | null => value === null ? null : timestamp(value)
const statusOf = (value: any): ServiceManageStatus =>
  serviceManageStatuses.includes(value) ? value as ServiceManageStatus : invalid()
const fulfillmentOf = (value: any): FulfillmentType =>
  value === 'IN_STORE' || value === 'PICKUP_DELIVERY' ? value : invalid()
function petTypesOf(value: any): ApplicablePetType[] {
  if (!Array.isArray(value) || value.length > 4) invalid()
  const types = value.map((item: any) => typeof item === 'string' && petTypeSet.has(item as ApplicablePetType) ? item as ApplicablePetType : invalid())
  if (new Set(types).size !== types.length) invalid()
  if (types.includes('ALL') && types.length > 1) invalid() // ALL is exclusive per proposal §2
  return types
}
const submissionNoOf = (value: any): number => {
  if (!Number.isSafeInteger(value) || value < 0 || value > 4294967295) invalid()
  return value
}
function coverOf(value: any): ManagedServiceCover {
  const v = exact(value, ['coverAssetId', 'coverUrl', 'coverUrlExpiresAt'])
  const coverAssetId = v.coverAssetId === null ? null : isId(v.coverAssetId) ? v.coverAssetId : invalid()
  const coverUrl = textOrNull(v.coverUrl, 2048)
  const coverUrlExpiresAt = timestampOrNull(v.coverUrlExpiresAt)
  // A signed display URL always comes with its asset anchor and its own expiry.
  if (coverUrl !== null && (coverAssetId === null || coverUrlExpiresAt === null)) invalid()
  return { coverAssetId, coverUrl, coverUrlExpiresAt }
}
function rejectionOf(value: any): ManagedServiceRejection | null {
  if (value === null) return null
  const v = exact(value, ['decisionId', 'submissionNo', 'decisionType', 'opinion', 'decidedAt'])
  if (v.decisionType !== 'REJECT') invalid() // only the most recent REJECT is carried
  // REJECT opinion is a mandatory 10-500 text (the edit-page banner shows it verbatim).
  const opinion = textOrNull(v.opinion, 500) ?? invalid()
  if ([...opinion].length < 10) invalid()
  return { decisionId: isId(v.decisionId) ? v.decisionId : invalid(), submissionNo: submissionNoOf(v.submissionNo),
    decisionType: 'REJECT', opinion, decidedAt: timestamp(v.decidedAt) }
}

export function decodeCategory(value: unknown): ServiceCategoryView {
  const v = exact(value, ['id', 'name', 'sortNo'])
  if (!isId(v.id)) invalid()
  if (typeof v.name !== 'string' || [...v.name].length < 1 || [...v.name].length > 50) invalid()
  if (!Number.isSafeInteger(v.sortNo) || v.sortNo < 1 || v.sortNo > 10000) invalid()
  return { id: v.id, name: v.name, sortNo: v.sortNo }
}
export function decodeCategoryList(value: any): ServiceCategoryView[] {
  if (!Array.isArray(value) || value.length < 1 || value.length > 100) invalid()
  const categories = value.map(decodeCategory)
  if (new Set(categories.map((category: ServiceCategoryView) => category.id)).size !== categories.length) invalid()
  if (JSON.stringify(categories.map((category: ServiceCategoryView) => category.sortNo)) !== JSON.stringify([...categories].sort((a: ServiceCategoryView, b: ServiceCategoryView) => a.sortNo - b.sortNo).map((category: ServiceCategoryView) => category.sortNo))) invalid()
  return categories
}
const itemKeys = ['serviceId', 'serviceName', 'categoryName', 'price', 'status', 'version',
  'submissionNo', 'submittedAt', 'updatedAt', 'cover'] as const
export function decodeManagedServiceItem(value: unknown): ManagedServiceItem {
  const v = exact(value, itemKeys)
  if (!isId(v.serviceId)) invalid()
  if (typeof v.serviceName !== 'string' || [...v.serviceName].length < 1 || [...v.serviceName].length > 50) invalid()
  if (v.categoryName !== null && (typeof v.categoryName !== 'string' || [...v.categoryName].length > 50)) invalid()
  return {
    serviceId: v.serviceId, serviceName: v.serviceName, categoryName: v.categoryName,
    price: moneyOrNull(v.price), status: statusOf(v.status), version: isVersion(v.version) ? v.version : invalid(),
    submissionNo: submissionNoOf(v.submissionNo), submittedAt: timestampOrNull(v.submittedAt),
    updatedAt: timestamp(v.updatedAt), cover: coverOf(v.cover),
  }
}
export function decodeManagedServicePage(value: unknown): ManagedServicePage {
  const v = exact(value, ['items', 'page', 'pageSize', 'total'])
  if (!Array.isArray(v.items) || v.items.length > 100) invalid()
  const page = Number(v.page), pageSize = Number(v.pageSize), total = Number(v.total)
  if (!Number.isInteger(page) || page < 1 || page > 10000) invalid()
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100) invalid()
  if (!Number.isInteger(total) || total < 0) invalid()
  return { items: v.items.map(decodeManagedServiceItem), page, pageSize, total }
}
const detailKeys = ['serviceId', 'merchantId', 'storeId', 'serviceName', 'categoryId', 'categoryName', 'fulfillmentType',
  'price', 'listPrice', 'durationMinutes', 'cover', 'applicablePetTypes',
  'staffRequirement', 'verificationRequired', 'description', 'aftersaleNote', 'remark',
  'status', 'version', 'submissionNo', 'submittedAt', 'updatedAt', 'latestRejection'] as const
export function decodeManagedServiceDetail(value: unknown): ManagedServiceDetail {
  const v = exact(value, detailKeys)
  if (!isId(v.serviceId) || !isId(v.merchantId) || !isId(v.storeId)) invalid()
  if (v.categoryId !== null && !isId(v.categoryId)) invalid()
  if (v.categoryName !== null && (typeof v.categoryName !== 'string' || [...v.categoryName].length > 50)) invalid()
  if (typeof v.serviceName !== 'string' || [...v.serviceName].length < 1 || [...v.serviceName].length > 50) invalid()
  if (v.durationMinutes !== null && (!Number.isSafeInteger(v.durationMinutes) || v.durationMinutes < 1 || v.durationMinutes > 24 * 60)) invalid()
  if (typeof v.verificationRequired !== 'boolean') invalid()
  const latestRejection = rejectionOf(v.latestRejection)
  // A REJECTED service must expose its most recent rejection (the banner data source).
  if (v.status === 'REJECTED' && latestRejection === null) invalid()
  const price = moneyOrNull(v.price), listPrice = moneyOrNull(v.listPrice)
  if (listPrice !== null && price !== null && cents(listPrice) < cents(price)) invalid()
  return {
    serviceId: v.serviceId, merchantId: v.merchantId, storeId: v.storeId,
    serviceName: v.serviceName, categoryId: v.categoryId, categoryName: v.categoryName,
    fulfillmentType: v.fulfillmentType === null ? null : fulfillmentOf(v.fulfillmentType),
    price, listPrice, durationMinutes: v.durationMinutes, cover: coverOf(v.cover),
    applicablePetTypes: petTypesOf(v.applicablePetTypes),
    staffRequirement: textOrNull(v.staffRequirement, 200), verificationRequired: v.verificationRequired,
    description: textOrNull(v.description, 1000), aftersaleNote: textOrNull(v.aftersaleNote, 500),
    remark: textOrNull(v.remark, 500), status: statusOf(v.status),
    version: isVersion(v.version) ? v.version : invalid(),
    submissionNo: submissionNoOf(v.submissionNo), submittedAt: timestampOrNull(v.submittedAt),
    updatedAt: timestamp(v.updatedAt), latestRejection,
  }
}
export function decodeCommandReceipt(value: unknown): ServiceCommandReceipt {
  const v = exact(value, ['serviceId', 'status', 'version'])
  if (!isId(v.serviceId)) invalid()
  return { serviceId: v.serviceId, status: statusOf(v.status), version: isVersion(v.version) ? v.version : invalid() }
}

/** Client-side submit validation (proposal §2 submit precheck + PRD §5.5 required fields).
 *  Mirrors the finalized server answer: 400 COMMON_INVALID_ARGUMENT with these fields. */
export function missingSubmitFields(draft: ServiceDraftInput, categories: readonly ServiceCategoryView[]): string[] {
  const missing: string[] = []
  const name = [...draft.serviceName.trim()]
  if (name.length < 2 || name.length > 50) missing.push('服务名称（2-50字）')
  if (!draft.categoryId || !categories.some(category => category.id === draft.categoryId)) missing.push('服务分类')
  if (!draft.fulfillmentType) missing.push('履约方式')
  if (draft.price === null || !/^\d+\.\d{2}$/.test(draft.price) || draft.price === '0.00') missing.push('销售价格')
  if (draft.durationMinutes === null || draft.durationMinutes < 1) missing.push('服务时长')
  if (draft.coverAssetId === null) missing.push('封面图')
  if (draft.applicablePetTypes.length < 1) missing.push('适用宠物类型')
  return missing
}
/** Loose DRAFT validation: any provided value must be well-formed (400 shape on the server). */
export function draftInputProblems(draft: ServiceDraftInput, categories: readonly ServiceCategoryView[]): string[] {
  const problems: string[] = []
  const name = [...draft.serviceName.trim()]
  if (draft.serviceName !== '' && (name.length < 2 || name.length > 50)) problems.push('服务名称需2-50字')
  if (draft.categoryId !== null && !categories.some(category => category.id === draft.categoryId)) problems.push('服务分类无效')
  if (draft.price !== null && !/^\d+\.\d{2}$/.test(draft.price)) problems.push('销售价格需为两位小数（如128.00）')
  if (draft.price !== null && draft.listPrice !== null && cents(draft.listPrice) < cents(draft.price)) problems.push('划线价不能低于销售价格')
  if (draft.durationMinutes !== null && (draft.durationMinutes < 1 || draft.durationMinutes > 1440)) problems.push('服务时长需1-1440分钟')
  if (draft.staffRequirement !== null && [...draft.staffRequirement].length > 200) problems.push('服务人员要求最多200字')
  if (draft.description !== null && [...draft.description].length > 1000) problems.push('服务说明最多1000字')
  if (draft.aftersaleNote !== null && [...draft.aftersaleNote].length > 500) problems.push('售后说明最多500字')
  if (draft.remark !== null && [...draft.remark].length > 500) problems.push('备注最多500字')
  return problems
}

export type ServiceManageScenario = 'normal' | 'empty' | 'load-error' | 'not-operable' | 'expired'
export const isServiceManageScenario = (value?: string): value is ServiceManageScenario =>
  ['normal', 'empty', 'load-error', 'not-operable', 'expired'].includes(value || '')

/** Page-facing dependency surface; preview and real implementations are interchangeable. */
export type ServiceManageDeps = {
  categories(): Promise<ServiceCategoryView[]>
  list(page: number, pageSize: number): Promise<ManagedServicePage>
  detail(serviceId: string): Promise<ManagedServiceDetail>
  create(slot: string, input: ServiceDraftInput): Promise<ServiceCommandReceipt>
  update(slot: string, serviceId: string, expectedVersion: string, input: ServiceDraftInput): Promise<ServiceCommandReceipt>
  submitOnline(slot: string, serviceId: string, expectedVersion: string): Promise<ServiceCommandReceipt>
  takeOffline(slot: string, serviceId: string, expectedVersion: string): Promise<ServiceCommandReceipt>
}

export class ServiceManageMockError extends Error {
  constructor(readonly code: string, readonly statusCode: number) { super(code) }
}

// CONTRACT MOCK (finalized-contract preview): no network, no session, no durable data.
// It enforces the command semantics the backend will enforce — status-machine guards
// (409 SERVICE_STATE_NOT_ALLOWED), expectedVersion CAS (409 COMMON_CONFLICT), submit precheck
// (400 COMMON_INVALID_ARGUMENT per the A-side error-code finalization), non-operable stores
// rejecting writes (409), per-slot requestId idempotency with replay and payload locking —
// but it is NOT a real backend integration and never authorizes anyone.
export class PreviewServiceManageRepository implements ServiceManageDeps {
  private services: Map<string, ManagedServiceDetail>
  private nextId = 30100
  private journal = new Map<string, { requestId: string; fingerprint: string; receipt: ServiceCommandReceipt }>()
  private uuidSeed = 0
  private clock = 0
  private failNextRead = false
  constructor(
    services: ManagedServiceDetail[] = fixtureManagedServices,
    private scenario: ServiceManageScenario = 'normal',
    private pause: () => Promise<void> = async () => {},
  ) {
    this.services = new Map(services.map(service => [service.serviceId, { ...service, cover: { ...service.cover }, applicablePetTypes: [...service.applicablePetTypes] }]))
    if (scenario === 'empty') this.services.clear()
    this.failNextRead = scenario === 'load-error'
  }
  private uuid(): string {
    const value = (this.uuidSeed++).toString(16).padStart(12, '0')
    return `${value.slice(0, 8)}-${value.slice(8, 12)}-4000-8000-${value}`
  }
  /** Deterministic updatedAt for snapshot realism; the real value comes from the backend. */
  private now(): string {
    return `2026-09-22T10:00:${String(this.clock++ % 60).padStart(2, '0')}.000Z`
  }
  private async gate(read: boolean): Promise<void> {
    await this.pause()
    if (this.failNextRead && read) { this.failNextRead = false; throw new ServiceManageMockError('COMMON_DEPENDENCY_UNAVAILABLE', 503) }
  }
  private operable(): void {
    if (this.scenario === 'not-operable') throw new ServiceManageMockError('SERVICE_STATE_NOT_ALLOWED', 409)
  }
  /** Same slot retried with the same payload replays the journaled receipt; changed payload locks. */
  private command(slot: string, serviceId: string, fingerprint: string, apply: (requestId: string) => ServiceCommandReceipt): ServiceCommandReceipt {
    const saved = this.journal.get(slot)
    if (saved) {
      if (saved.fingerprint !== fingerprint) throw new Error('PENDING_WRITE_CHANGED')
      return { ...saved.receipt }
    }
    const requestId = this.uuid()
    const receipt = apply(requestId)
    this.journal.set(slot, { requestId, fingerprint, receipt: { ...receipt } })
    return { ...receipt }
  }
  async categories(): Promise<ServiceCategoryView[]> {
    await this.gate(true)
    return fixtureCategories.map(category => ({ ...category }))
  }
  async list(page = 1, pageSize = 20): Promise<ManagedServicePage> {
    await this.gate(true)
    const bounded = Math.min(Math.max(1, Math.trunc(page)), 10000)
    const size = Math.min(Math.max(1, Math.trunc(pageSize)), 100)
    // Newest first (numeric serviceId desc); list carries all statuses for the workbench.
    const items = [...this.services.values()]
      .sort((a, b) => (BigInt(b.serviceId) > BigInt(a.serviceId) ? 1 : -1))
      .map(({ serviceId, serviceName, categoryName, price, status, version, submissionNo, submittedAt, updatedAt, cover }) =>
        ({ serviceId, serviceName, categoryName, price, status, version, submissionNo, submittedAt, updatedAt, cover: { ...cover } }))
    const start = (bounded - 1) * size
    return { items: items.slice(start, start + size), page: bounded, pageSize: size, total: items.length }
  }
  async detail(serviceId: string): Promise<ManagedServiceDetail> {
    await this.gate(true)
    if (!isId(serviceId)) throw new ServiceManageMockError('COMMON_INVALID_ARGUMENT', 400)
    const found = this.services.get(serviceId)
    if (!found) throw new ServiceManageMockError('SERVICE_NOT_FOUND', 404)
    return { ...found, cover: { ...found.cover }, applicablePetTypes: [...found.applicablePetTypes],
      latestRejection: found.latestRejection === null ? null : { ...found.latestRejection } }
  }
  private looseCheck(input: ServiceDraftInput): void {
    if (draftInputProblems(input, fixtureCategories).length) throw new ServiceManageMockError('COMMON_INVALID_ARGUMENT', 400)
  }
  /** PUT/POST replace the full business field set (the form always carries every field). */
  private store(input: ServiceDraftInput, base: Pick<ManagedServiceDetail, 'serviceId' | 'status' | 'version' | 'submissionNo' | 'submittedAt' | 'latestRejection'>): ManagedServiceDetail {
    return {
      serviceId: base.serviceId, merchantId: fixtureMerchantId, storeId: fixtureStoreId,
      serviceName: input.serviceName.trim(), categoryId: input.categoryId,
      categoryName: input.categoryId === null ? null : fixtureCategories.find(category => category.id === input.categoryId)?.name ?? null,
      fulfillmentType: input.fulfillmentType, price: input.price, listPrice: input.listPrice,
      durationMinutes: input.durationMinutes,
      cover: input.coverAssetId === null
        ? { coverAssetId: null, coverUrl: null, coverUrlExpiresAt: null }
        // Mock signed URL placeholder (fixed expiry) until real integration.
        : { coverAssetId: input.coverAssetId, coverUrl: fixtureCoverUrl, coverUrlExpiresAt: fixtureCoverUrlExpiresAt },
      applicablePetTypes: [...input.applicablePetTypes], staffRequirement: input.staffRequirement,
      verificationRequired: input.verificationRequired, description: input.description,
      aftersaleNote: input.aftersaleNote, remark: input.remark,
      status: base.status, version: base.version, submissionNo: base.submissionNo,
      submittedAt: base.submittedAt, updatedAt: this.now(), latestRejection: base.latestRejection,
    }
  }
  async create(slot: string, input: ServiceDraftInput): Promise<ServiceCommandReceipt> {
    await this.gate(false)
    this.operable()
    return this.command(slot, '', JSON.stringify(input), () => {
      this.looseCheck(input)
      const serviceId = String(this.nextId++)
      const created = this.store(input, { serviceId, status: 'DRAFT', version: '1', submissionNo: 0, submittedAt: null, latestRejection: null })
      this.services.set(serviceId, created)
      return { serviceId, status: 'DRAFT', version: created.version }
    })
  }
  async update(slot: string, serviceId: string, expectedVersion: string, input: ServiceDraftInput): Promise<ServiceCommandReceipt> {
    await this.gate(false)
    this.operable()
    return this.command(slot, serviceId, JSON.stringify([serviceId, expectedVersion, input]), () => {
      const found = this.services.get(serviceId)
      if (!found) throw new ServiceManageMockError('SERVICE_NOT_FOUND', 404)
      if (!editableStatuses.includes(found.status)) throw new ServiceManageMockError('SERVICE_STATE_NOT_ALLOWED', 409)
      if (found.version !== expectedVersion) throw new ServiceManageMockError('COMMON_CONFLICT', 409)
      this.looseCheck(input)
      const updated = this.store(input, { serviceId, status: found.status, version: String(Number(found.version) + 1),
        submissionNo: found.submissionNo, submittedAt: found.submittedAt, latestRejection: found.latestRejection })
      this.services.set(serviceId, updated)
      return { serviceId, status: updated.status, version: updated.version }
    })
  }
  async submitOnline(slot: string, serviceId: string, expectedVersion: string): Promise<ServiceCommandReceipt> {
    await this.gate(false)
    this.operable()
    return this.command(slot, serviceId, JSON.stringify([serviceId, expectedVersion, 'online']), () => {
      const found = this.services.get(serviceId)
      if (!found) throw new ServiceManageMockError('SERVICE_NOT_FOUND', 404)
      if (!editableStatuses.includes(found.status)) throw new ServiceManageMockError('SERVICE_STATE_NOT_ALLOWED', 409)
      if (found.version !== expectedVersion) throw new ServiceManageMockError('COMMON_CONFLICT', 409)
      const draft: ServiceDraftInput = {
        serviceName: found.serviceName, categoryId: found.categoryId, fulfillmentType: found.fulfillmentType,
        price: found.price, listPrice: found.listPrice, durationMinutes: found.durationMinutes,
        coverAssetId: found.cover.coverAssetId, applicablePetTypes: found.applicablePetTypes,
        staffRequirement: found.staffRequirement, verificationRequired: found.verificationRequired,
        description: found.description, aftersaleNote: found.aftersaleNote, remark: found.remark,
      }
      // A-side finalization: missing required fields (incl. cover) answer COMMON_INVALID_ARGUMENT.
      if (missingSubmitFields(draft, fixtureCategories).length) throw new ServiceManageMockError('COMMON_INVALID_ARGUMENT', 400)
      const version = String(Number(found.version) + 1)
      const submissionNo = found.submissionNo + 1
      const submitted = { ...found, status: 'REVIEWING' as const, version, submissionNo,
        submittedAt: this.now(), updatedAt: this.now() }
      this.services.set(serviceId, submitted)
      return { serviceId, status: 'REVIEWING', version }
    })
  }
  async takeOffline(slot: string, serviceId: string, expectedVersion: string): Promise<ServiceCommandReceipt> {
    await this.gate(false)
    this.operable()
    return this.command(slot, serviceId, JSON.stringify([serviceId, expectedVersion, 'offline']), () => {
      const found = this.services.get(serviceId)
      if (!found) throw new ServiceManageMockError('SERVICE_NOT_FOUND', 404)
      if (found.status !== 'ACTIVE') throw new ServiceManageMockError('SERVICE_STATE_NOT_ALLOWED', 409)
      if (found.version !== expectedVersion) throw new ServiceManageMockError('COMMON_CONFLICT', 409)
      const version = String(Number(found.version) + 1)
      this.services.set(serviceId, { ...found, status: 'OFFLINE', version, updatedAt: this.now() })
      return { serviceId, status: 'OFFLINE', version }
    })
  }
}

// Design samples of frame 10:5255 (8 services of the original artwork) spread across the
// approved status machine so every list state is previewable; prices keep the design values.
export const fixtureCoverUrl = 'https://design.example/covers/mock-cover.png'
/** Mock signed-URL expiry placeholder — the real value refreshes per backend response. */
export const fixtureCoverUrlExpiresAt = '2026-09-22T11:00:00.000Z'
export const fixtureMerchantId = '957001'
export const fixtureStoreId = '957002'
const cover = (coverAssetId: string): ManagedServiceCover => ({ coverAssetId, coverUrl: fixtureCoverUrl, coverUrlExpiresAt: fixtureCoverUrlExpiresAt })
const noCover: ManagedServiceCover = { coverAssetId: null, coverUrl: null, coverUrlExpiresAt: null }
/** Unified service dictionary (11 categories, PRD §5.1.13/商家端第8章 alignment; ids are fixtures). */
export const fixtureCategories: ServiceCategoryView[] = [
  { id: '957003', name: '宠物美容', sortNo: 1 },
  { id: '957004', name: '宠物寄养', sortNo: 2 },
  { id: '957005', name: '遛狗陪护', sortNo: 3 },
  { id: '957006', name: '宠物训练', sortNo: 4 },
  { id: '957007', name: '上门喂养', sortNo: 5 },
  { id: '957008', name: '兽医助理', sortNo: 6 },
  { id: '957009', name: '小宠寄养', sortNo: 7 },
  { id: '957010', name: '异宠上门喂养', sortNo: 8 },
  { id: '957011', name: '异宠健康检查', sortNo: 9 },
  { id: '957012', name: '绿植养护', sortNo: 10 },
  { id: '957013', name: '植物代养', sortNo: 11 },
]
export const fixtureManagedServices: ManagedServiceDetail[] = [
  { serviceId: '30001', merchantId: fixtureMerchantId, storeId: fixtureStoreId, serviceName: '猫咪洗澡+基础护理',
    categoryId: '957003', categoryName: '宠物美容', fulfillmentType: 'IN_STORE', price: '128.00', listPrice: '158.00', durationMinutes: 60,
    cover: cover('40001'), applicablePetTypes: ['CAT'], staffRequirement: '持证宠物美容师',
    verificationRequired: true, description: '含洗护、基础护理、吹干造型', aftersaleNote: '服务开始前可全额退款', remark: null,
    status: 'ACTIVE', version: '3', submissionNo: 1, submittedAt: '2026-09-20T08:00:00.000Z', updatedAt: '2026-09-21T08:00:00.000Z',
    latestRejection: null },
  { serviceId: '30002', merchantId: fixtureMerchantId, storeId: fixtureStoreId, serviceName: '狗狗美容造型',
    categoryId: '957003', categoryName: '宠物美容', fulfillmentType: 'IN_STORE', price: '198.00', listPrice: null, durationMinutes: 90,
    cover: cover('40002'), applicablePetTypes: ['DOG'], staffRequirement: null,
    verificationRequired: true, description: '造型修剪、洗护、指甲护理', aftersaleNote: null, remark: null,
    status: 'REVIEWING', version: '2', submissionNo: 1, submittedAt: '2026-09-22T06:30:00.000Z', updatedAt: '2026-09-22T06:30:00.000Z',
    latestRejection: null },
  { serviceId: '30003', merchantId: fixtureMerchantId, storeId: fixtureStoreId, serviceName: '宠物疫苗接种（狂犬）',
    categoryId: '957008', categoryName: '兽医助理', fulfillmentType: 'IN_STORE', price: '120.00', listPrice: null, durationMinutes: 30,
    cover: cover('40003'), applicablePetTypes: ['ALL'], staffRequirement: '执业兽医',
    verificationRequired: true, description: '狂犬疫苗接种，含接种证明', aftersaleNote: null, remark: null,
    status: 'REJECTED', version: '4', submissionNo: 2, submittedAt: '2026-09-20T09:00:00.000Z', updatedAt: '2026-09-21T10:00:00.000Z',
    latestRejection: { decisionId: '50003', submissionNo: 2, decisionType: 'REJECT',
      opinion: '封面图与接种环境说明不完整，请补充接种台照片后重新提交审核。', decidedAt: '2026-09-21T10:00:00.000Z' } },
  { serviceId: '30004', merchantId: fixtureMerchantId, storeId: fixtureStoreId, serviceName: '猫咪绝育套餐',
    categoryId: '957008', categoryName: '兽医助理', fulfillmentType: 'IN_STORE', price: '1280.00', listPrice: '1580.00', durationMinutes: 120,
    cover: cover('40004'), applicablePetTypes: ['CAT'], staffRequirement: '执业兽医团队',
    verificationRequired: true, description: '术前体检、麻醉、手术与术后观察', aftersaleNote: '术后问题24小时内联系门店', remark: null,
    status: 'OFFLINE', version: '6', submissionNo: 3, submittedAt: '2026-09-15T08:00:00.000Z', updatedAt: '2026-09-18T09:00:00.000Z',
    // Only the most recent REJECT is carried; this service's last decision was an approval.
    latestRejection: null },
  { serviceId: '30005', merchantId: fixtureMerchantId, storeId: fixtureStoreId, serviceName: '狗狗寄养（每日）',
    categoryId: '957004', categoryName: '宠物寄养', fulfillmentType: 'IN_STORE', price: '150.00', listPrice: null, durationMinutes: 480,
    cover: noCover, applicablePetTypes: ['DOG'], staffRequirement: null,
    verificationRequired: true, description: null, aftersaleNote: null, remark: null,
    status: 'DRAFT', version: '1', submissionNo: 0, submittedAt: null, updatedAt: '2026-09-22T05:00:00.000Z',
    latestRejection: null },
  { serviceId: '30006', merchantId: fixtureMerchantId, storeId: fixtureStoreId, serviceName: '宠物体检基础套餐',
    categoryId: '957011', categoryName: '异宠健康检查', fulfillmentType: 'IN_STORE', price: '380.00', listPrice: null, durationMinutes: 60,
    cover: cover('40006'), applicablePetTypes: ['ALL'], staffRequirement: '执业兽医',
    verificationRequired: true, description: '基础体检八项', aftersaleNote: null, remark: null,
    status: 'ACTIVE', version: '2', submissionNo: 1, submittedAt: '2026-09-18T08:00:00.000Z', updatedAt: '2026-09-19T08:00:00.000Z',
    latestRejection: null },
  { serviceId: '30007', merchantId: fixtureMerchantId, storeId: fixtureStoreId, serviceName: '猫咪上门喂养',
    categoryId: '957007', categoryName: '上门喂养', fulfillmentType: 'PICKUP_DELIVERY', price: '80.00', listPrice: null, durationMinutes: 45,
    cover: cover('40007'), applicablePetTypes: ['CAT'], staffRequirement: null,
    verificationRequired: true, description: '上门喂食、铲砂、陪伴', aftersaleNote: null, remark: null,
    status: 'ACTIVE', version: '2', submissionNo: 1, submittedAt: '2026-09-17T08:00:00.000Z', updatedAt: '2026-09-18T08:00:00.000Z',
    latestRejection: null },
  { serviceId: '30008', merchantId: fixtureMerchantId, storeId: fixtureStoreId, serviceName: '狗狗训练课程',
    categoryId: '957006', categoryName: '宠物训练', fulfillmentType: 'IN_STORE', price: '500.00', listPrice: null, durationMinutes: 60,
    cover: cover('40008'), applicablePetTypes: ['DOG'], staffRequirement: '持证训犬师',
    verificationRequired: true, description: '基础服从训练', aftersaleNote: null, remark: null,
    status: 'ACTIVE', version: '2', submissionNo: 1, submittedAt: '2026-09-16T08:00:00.000Z', updatedAt: '2026-09-17T08:00:00.000Z',
    latestRejection: null },
]

// Display helper: the design list shows integer prices ("¥128"); wire keeps two decimals.
export function formatPrice(price: string): string {
  return price.replace(/\.?0+$/, '') || price
}
