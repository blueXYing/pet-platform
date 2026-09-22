import { id, object, ConsumerApi } from './consumer-api'
const invalid = (): never => { throw new Error('INVALID_RESPONSE') }
function exact(value: unknown, keys: readonly string[]) {
  const v = object(value)
  if (Object.keys(v).some(key => !keys.includes(key))) invalid()
  return v
}
function text(value: unknown, max: number, min = 0): string {
  if (typeof value !== 'string' || [...value].length < min || [...value].length > max) invalid()
  return value as string
}
function oneOf<const T extends readonly string[]>(value: unknown, values: T): T[number] {
  if (typeof value !== 'string' || !values.includes(value)) invalid()
  return value as T[number]
}
export function decodeVersion(value: unknown): string {
  if (typeof value !== 'string' || !/^(0|[1-9][0-9]{0,18})(?![\s\S])/.test(value) || BigInt(value) > 9223372036854775807n) invalid()
  return value as string
}
function timestamp(value: unknown): string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})(?![\s\S])/.test(value) || !Number.isFinite(Date.parse(value))) invalid()
  const [year, month, day] = (value as string).slice(0, 10).split('-').map(Number)
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  if (month < 1 || month > 12 || day < 1 || day > [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]) invalid()
  return value as string
}
const nullable = <T>(value: unknown, decode: (v: unknown) => T): T | null => value === null ? null : decode(value)
export const merchantTypes = ['PET_LIFE_STORE', 'PET_HOSPITAL', 'PET_GROOMING', 'PET_BOARDING', 'PET_TRAINING', 'OTHER'] as const
export type DraftInput = Partial<{
  merchantName: string | null; contactName: string | null; contactPhone: string | null; email: string | null
  merchantTypeCode: typeof merchantTypes[number] | null; cityCode: string | null; address: string | null
  longitude: string | null; latitude: string | null; introduction: string | null; storePhotoAssetIds: string[]
  businessLicenseAssetId: string | null; idCardFrontAssetId: string | null; idCardBackAssetId: string | null; industryLicenseAssetId: string | null
}>
const lengths = { merchantName: 50, contactName: 20, contactPhone: 11, email: 254, cityCode: 32, address: 255, introduction: 500 }
const assets = ['businessLicenseAssetId', 'idCardFrontAssetId', 'idCardBackAssetId', 'industryLicenseAssetId'] as const
export function decodeDraft(value: unknown): DraftInput {
  const v = exact(value, [...Object.keys(lengths), ...assets, 'merchantTypeCode', 'longitude', 'latitude', 'storePhotoAssetIds'])
  const result: Record<string, unknown> = {}
  for (const [key, max] of Object.entries(lengths)) if (key in v) result[key] = nullable(v[key], x => text(x, max))
  for (const key of assets) if (key in v) result[key] = nullable(v[key], id)
  if ('merchantTypeCode' in v) result.merchantTypeCode = nullable(v.merchantTypeCode, x => oneOf(x, merchantTypes))
  for (const key of ['longitude', 'latitude']) if (key in v) result[key] = nullable(v[key], x => {
    if (typeof x !== 'string' || !/^-?(0|[1-9][0-9]{0,2})(\.[0-9]{1,7})?(?![\s\S])/.test(x) || Math.abs(Number(x)) > (key === 'longitude' ? 180 : 90)) invalid()
    return x
  })
  if ('storePhotoAssetIds' in v) {
    if (!Array.isArray(v.storePhotoAssetIds) || v.storePhotoAssetIds.length > 6) invalid()
    const photos = v.storePhotoAssetIds.map(id) as string[]
    if (new Set(photos).size !== photos.length) invalid()
    result.storePhotoAssetIds = photos
  }
  return result as DraftInput
}
const resultKeys = ['applicationId', 'applicationNo', 'reservedMerchantId', 'status', 'version', 'currentRevisionId']
function resultFields(v: Record<string, unknown>) {
  const status = oneOf(v.status, ['DRAFT', 'REVIEWING', 'APPROVED', 'REJECTED'] as const)
  const applicationNo = nullable(v.applicationNo, x => {
    if (typeof x !== 'string' || !/^SQ[0-9]{8}[A-Za-z0-9]{8}(?![\s\S])/.test(x)) invalid()
    return x as string
  })
  if ((status === 'DRAFT') !== (applicationNo === null)) invalid()
  return { applicationId: id(v.applicationId), applicationNo, reservedMerchantId: id(v.reservedMerchantId), status, version: decodeVersion(v.version), currentRevisionId: id(v.currentRevisionId) }
}
export function decodeApplicationResult(value: unknown) { return resultFields(exact(value, resultKeys)) }
export type ApplicationResult = ReturnType<typeof decodeApplicationResult>
export function decodeApplicationDetail(value: unknown) {
  const v = exact(value, [...resultKeys, 'currentRevision', 'submittedAt', 'reviewedAt', 'latestDecision', 'subjectVerificationStatus'])
  const base = resultFields(v)
  const r = exact(v.currentRevision, ['revisionId', 'revisionNo', 'draft', 'createdAt'])
  const revisionNo = id(r.revisionNo)
  if (BigInt(revisionNo) > 4294967295n || id(r.revisionId) !== base.currentRevisionId) invalid()
  const currentRevision = { revisionId: id(r.revisionId), revisionNo, draft: decodeDraft(r.draft), createdAt: timestamp(r.createdAt) }
  const submittedAt = nullable(v.submittedAt, timestamp), reviewedAt = nullable(v.reviewedAt, timestamp)
  const latestDecision = nullable(v.latestDecision, value => {
    const d = exact(value, ['reviewDecisionId', 'submittedRevisionId', 'decisionType', 'opinion', 'decidedAt'])
    const decisionType = oneOf(d.decisionType, ['APPROVE', 'REJECT', 'REQUEST_CORRECTION'] as const)
    const opinion = nullable(d.opinion, x => text(x, 500, decisionType === 'APPROVE' ? 0 : 10))
    if (decisionType !== 'APPROVE' && opinion === null) invalid()
    return { reviewDecisionId: id(d.reviewDecisionId), submittedRevisionId: id(d.submittedRevisionId), decisionType, opinion, decidedAt: timestamp(d.decidedAt) }
  })
  const subjectVerificationStatus = oneOf(v.subjectVerificationStatus, ['PENDING', 'VERIFIED'] as const)
  if (base.status === 'DRAFT' && (submittedAt !== null || reviewedAt !== null || latestDecision !== null)) invalid()
  if (base.status !== 'DRAFT' && submittedAt === null) invalid()
  if (base.status === 'REVIEWING' && reviewedAt !== null) invalid()
  if (base.status === 'APPROVED' && (!reviewedAt || latestDecision?.decisionType !== 'APPROVE')) invalid()
  if (base.status === 'REJECTED' && (!reviewedAt || !latestDecision || latestDecision.decisionType === 'APPROVE')) invalid()
  return { ...base, currentRevision, submittedAt, reviewedAt, latestDecision, subjectVerificationStatus }
}
export type ApplicationDetail = ReturnType<typeof decodeApplicationDetail>
export function decodeApplicationCities(value: unknown) {
  const v = exact(value, ['items'])
  if (!Array.isArray(v.items) || v.items.length < 1 || v.items.length > 100) invalid()
  const codes = new Set<string>(), names = new Set<string>()
  return v.items.map((item: unknown) => {
    const city = exact(item, ['cityCode', 'cityName'])
    const cityCode = text(city.cityCode, 32, 1), cityName = text(city.cityName, 64, 1)
    if (!/^[a-z][a-z0-9_-]{0,31}(?![\s\S])/.test(cityCode) || !cityName.trim() || codes.has(cityCode) || names.has(cityName)) invalid()
    codes.add(cityCode); names.add(cityName)
    return { cityCode, cityName }
  }) as { cityCode: string; cityName: string }[]
}
export type ApplicationCheckpoint = { slot: string; value: (result: ApplicationResult) => unknown }
/** Approved OAS30 client; enabled explicitly by deployment capability. */
export class MerchantApplicationRepository {
  constructor(private api: ConsumerApi) {}
  pending() { return this.api.pendingCommands('merchant-application:') }
  intent() { return this.api.intent('merchant-application') }
  saveIntent(value: unknown) { this.api.saveIntent('merchant-application', value) }
  cities() { return this.api.request({ path: '/api/v1/c/merchant-application-cities', method: 'GET' }, decodeApplicationCities) }
  current(): Promise<ApplicationDetail> { return this.api.request({ path: '/api/v1/c/merchant-applications/current', method: 'GET' }, decodeApplicationDetail) }
  create(draft: DraftInput = {}, checkpoint?: ApplicationCheckpoint): Promise<ApplicationResult> {
    return this.api.write('merchant-application:create', { path: '/api/v1/c/merchant-applications', method: 'POST', data: decodeDraft(draft) }, value => {
      const result = decodeApplicationResult(value)
      if (result.status !== 'DRAFT') invalid()
      return result
    }, checkpoint)
  }
  save(applicationId: string, expectedVersion: string, draft: DraftInput, checkpoint?: ApplicationCheckpoint): Promise<ApplicationResult> {
    return this.api.write(`merchant-application:${id(applicationId)}:save`, { path: `/api/v1/c/merchant-applications/${id(applicationId)}/draft`, method: 'PUT', data: { expectedVersion: decodeVersion(expectedVersion), draft: decodeDraft(draft) } }, value => {
      const result = decodeApplicationResult(value)
      if (result.applicationId !== applicationId || !['DRAFT', 'REJECTED'].includes(result.status)) invalid()
      return result
    }, checkpoint)
  }
  submit(applicationId: string, expectedVersion: string, revisionId: string, checkpoint?: ApplicationCheckpoint): Promise<ApplicationResult> {
    return this.api.write(`merchant-application:${id(applicationId)}:submit`, { path: `/api/v1/c/merchant-applications/${id(applicationId)}/submit`, method: 'POST', data: { expectedVersion: decodeVersion(expectedVersion), revisionId: id(revisionId) } }, value => {
      const result = decodeApplicationResult(value)
      if (result.applicationId !== applicationId || result.currentRevisionId !== revisionId || result.status !== 'REVIEWING') invalid()
      return result
    }, checkpoint)
  }
}
function agreementVersion(value: unknown): string {
  if (typeof value !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}(?![\s\S])/.test(value)) invalid()
  return value as string
}
function contentHash(value: unknown): string {
  if (typeof value !== 'string' || !/^[a-f0-9]{64}(?![\s\S])/.test(value)) invalid()
  return value as string
}
export function decodeAgreement(value: unknown) {
  const v = exact(value, ['merchantId', 'agreementVersion', 'content', 'contentSha256', 'signingStatus', 'acceptedVersion', 'acceptedAt'])
  const base = { merchantId: id(v.merchantId), agreementVersion: agreementVersion(v.agreementVersion), content: text(v.content, 65535, 1), contentSha256: contentHash(v.contentSha256) }
  // Count UTF-8 bytes without relying on browser-only TextEncoder.
  if (encodeURIComponent(base.content).replace(/%[A-F0-9]{2}/g, 'x').length > 65535) invalid()
  const signingStatus = oneOf(v.signingStatus, ['NOT_SIGNED', 'SIGNED'] as const)
  if (signingStatus === 'SIGNED') {
    const acceptedVersion = agreementVersion(v.acceptedVersion)
    if (acceptedVersion !== base.agreementVersion) invalid()
    return { ...base, signingStatus, acceptedVersion, acceptedAt: timestamp(v.acceptedAt) }
  }
  if ('acceptedVersion' in v || 'acceptedAt' in v) invalid()
  return { ...base, signingStatus }
}
export type Agreement = ReturnType<typeof decodeAgreement>
export function decodeConsent(value: unknown) {
  const v = exact(value, ['merchantId', 'agreementVersion', 'acceptedAt', 'signingStatus'])
  return { merchantId: id(v.merchantId), agreementVersion: agreementVersion(v.agreementVersion), acceptedAt: timestamp(v.acceptedAt), signingStatus: oneOf(v.signingStatus, ['SIGNED'] as const) }
}
export type ConsentIntent = { merchantId: string; agreementVersion: string; contentSha256: string; accepted: true }
export class MerchantAgreementRepository {
  constructor(private api: ConsumerApi) {}
  pendingConsent(merchantId: string): ConsentIntent | null {
    merchantId = id(merchantId)
    const command = this.api.pendingCommand(`merchant-agreement:${merchantId}:consent`)
    if (!command) return null
    const data = exact(command.data, ['merchantId', 'agreementVersion', 'contentSha256', 'accepted'])
    if (command.path !== '/api/v1/merchant/agreement/consent' || command.method !== 'POST' || data.merchantId !== merchantId || data.accepted !== true) invalid()
    return { merchantId, agreementVersion: agreementVersion(data.agreementVersion), contentSha256: contentHash(data.contentSha256), accepted: true }
  }
  retryConsent(merchantId: string) {
    const pending = this.pendingConsent(merchantId)
    if (!pending) throw new Error('NO_PENDING_CONSENT')
    return this.api.write(`merchant-agreement:${pending.merchantId}:consent`, { path: '/api/v1/merchant/agreement/consent', method: 'POST', data: pending }, value => {
      const result = decodeConsent(value)
      if (result.merchantId !== pending.merchantId || result.agreementVersion !== pending.agreementVersion) invalid()
      return result
    })
  }
  read(merchantId: string): Promise<Agreement> {
    merchantId = id(merchantId)
    return this.api.request({ path: '/api/v1/merchant/agreement', method: 'GET', data: { merchantId } }, value => {
      const result = decodeAgreement(value)
      if (result.merchantId !== merchantId) invalid()
      return result
    })
  }
  consent(view: Agreement, accepted: boolean) {
    const reviewed = decodeAgreement(view)
    if (!accepted || reviewed.signingStatus !== 'NOT_SIGNED') throw new Error('EXPLICIT_AGREEMENT_REQUIRED')
    const { merchantId, agreementVersion, contentSha256 } = reviewed
    return this.api.write(`merchant-agreement:${merchantId}:consent`, { path: '/api/v1/merchant/agreement/consent', method: 'POST', data: { merchantId, agreementVersion, contentSha256, accepted: true } }, value => {
      const result = decodeConsent(value)
      if (result.merchantId !== merchantId || result.agreementVersion !== agreementVersion) invalid()
      return result
    })
  }
  /** Only for a consent the server answered with a definitive CONFLICT; unknown results stay journaled. */
  retireConsent(merchantId: string, rejected: { agreementVersion: string; contentSha256: string }) {
    merchantId = id(merchantId)
    const pending = this.pendingConsent(merchantId)
    if (!pending || pending.agreementVersion !== rejected.agreementVersion || pending.contentSha256 !== rejected.contentSha256) throw new Error('PENDING_WRITE_CHANGED')
    this.api.retireRejectedCommand(`merchant-agreement:${merchantId}:consent`, { path: '/api/v1/merchant/agreement/consent', method: 'POST', data: { merchantId, agreementVersion: pending.agreementVersion, contentSha256: pending.contentSha256, accepted: true } })
  }
}
