import { id, object, type ConsumerApi } from '../../shared/consumer-api'

export type MerchantCampaign = Readonly<{
  campaignId: string; name: string; title: string; subtitle: string | null
  tagName: string | null; coverUrl: string | null; status: string
  startAt: string; endAt: string; enrollStartAt: string | null; enrollEndAt: string | null
  storeCount: number; enrolled: boolean; enrollmentStatus: string | null; version: string
}>
export type MerchantEnrollment = Readonly<{
  enrollmentId: string; campaignId: string; campaignTitle: string
  status: string; offerTag: string | null; offerTitle: string | null; offerCopy: string | null
  rejectReason: string | null; submittedAt: string; version: string
}>
export type MyCampaign = Readonly<{
  campaignId: string; name: string; title: string; subtitle: string | null
  status: string; startAt: string; endAt: string; version: string
}>
export type Receipt = Readonly<{ id: string; status: string; version: string; occurredAt: string }>
export type EnrollInput = Readonly<{ offerTag?: string; offerTitle?: string; offerCopy?: string }>
export type CampaignInput = Readonly<{ name: string; title: string; subtitle?: string; startAt: string; endAt: string }>

const fail = (): never => { throw new Error('INVALID_RESPONSE') }
const text = (value: unknown, max: number): string =>
  typeof value === 'string' && value.length > 0 && Array.from(value).length <= max ? value : fail()
const nullableText = (value: unknown, max: number): string | null =>
  value === null || value === undefined ? null : typeof value === 'string' && Array.from(value).length <= max ? value : fail()
const instant = (value: unknown): string => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})(?![\s\S])/.test(value) || !Number.isFinite(Date.parse(value))) fail()
  return value as string
}
const nullableInstant = (value: unknown): string | null => value === null || value === undefined ? null : instant(value)
const version = (value: unknown): string => typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value) ? value : fail()
const integer = (value: unknown, min: number, max: number): number =>
  typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max ? value : fail()

function campaignOf(value: unknown): MerchantCampaign {
  const v = object(value)
  return {
    campaignId: id(v.campaignId), name: text(v.name, 100), title: text(v.title, 100),
    subtitle: nullableText(v.subtitle, 200), tagName: nullableText(v.tagName, 32),
    coverUrl: nullableText(v.coverUrl, 512), status: text(v.status, 24),
    startAt: instant(v.startAt), endAt: instant(v.endAt),
    enrollStartAt: nullableInstant(v.enrollStartAt), enrollEndAt: nullableInstant(v.enrollEndAt),
    storeCount: integer(v.storeCount, 0, 100000), enrolled: v.enrolled === true,
    enrollmentStatus: nullableText(v.enrollmentStatus, 24), version: version(v.version),
  }
}
function enrollmentOf(value: unknown): MerchantEnrollment {
  const v = object(value)
  return {
    enrollmentId: id(v.enrollmentId), campaignId: id(v.campaignId),
    campaignTitle: text(v.campaignTitle, 100), status: text(v.status, 24),
    offerTag: nullableText(v.offerTag, 32), offerTitle: nullableText(v.offerTitle, 100),
    offerCopy: nullableText(v.offerCopy, 100), rejectReason: nullableText(v.rejectReason, 500),
    submittedAt: instant(v.submittedAt), version: version(v.version),
  }
}
function myCampaignOf(value: unknown): MyCampaign {
  const v = object(value)
  return {
    campaignId: id(v.campaignId), name: text(v.name, 100), title: text(v.title, 100),
    subtitle: nullableText(v.subtitle, 200), status: text(v.status, 24),
    startAt: instant(v.startAt), endAt: instant(v.endAt), version: version(v.version),
  }
}
function receiptOf(value: unknown): Receipt {
  const v = object(value)
  return { id: id(v.id), status: text(v.status, 24), version: version(v.version), occurredAt: instant(v.occurredAt) }
}
function pageOf<T>(value: unknown, row: (item: unknown) => T): readonly T[] {
  const v = object(value)
  if (!Array.isArray(v.items) || v.items.length > 50) fail()
  return v.items.map(row)
}

/** Merchant marketing face; coordinates are re-proved server-side per call. */
export class MerchantMarketingApi {
  constructor(private api: ConsumerApi) {}

  openCampaigns(merchantId: string, storeId: string) {
    return this.api.request({ method: 'GET', path: '/api/v1/merchant/marketing/campaigns', data: { merchantId, storeId, page: 1, pageSize: 50 } }, value => pageOf(value, campaignOf))
  }

  enroll(merchantId: string, storeId: string, campaignId: string, input: EnrollInput) {
    return this.command({ method: 'POST', path: `/api/v1/merchant/marketing/campaigns/${encodeURIComponent(campaignId)}/enrollments`, data: { merchantId, storeId, ...input } }, receiptOf)
  }

  myEnrollments(merchantId: string, storeId: string) {
    return this.api.request({ method: 'GET', path: '/api/v1/merchant/marketing/enrollments', data: { merchantId, storeId, page: 1, pageSize: 50 } }, value => pageOf(value, enrollmentOf))
  }

  withdraw(merchantId: string, storeId: string, enrollmentId: string, expectedVersion: string) {
    return this.command({ method: 'POST', path: `/api/v1/merchant/marketing/enrollments/${encodeURIComponent(enrollmentId)}/withdraw`, data: { merchantId, storeId, expectedVersion } }, receiptOf)
  }

  myCampaigns(merchantId: string, storeId: string) {
    return this.api.request({ method: 'GET', path: '/api/v1/merchant/marketing/my-campaigns', data: { merchantId, storeId, page: 1, pageSize: 50 } }, value => pageOf(value, myCampaignOf))
  }

  createCampaign(merchantId: string, storeId: string, input: CampaignInput) {
    return this.command({ method: 'POST', path: '/api/v1/merchant/marketing/my-campaigns', data: { merchantId, storeId, ...input } }, receiptOf)
  }

  updateCampaign(merchantId: string, storeId: string, campaignId: string, input: CampaignInput, expectedVersion: string) {
    return this.command({ method: 'PUT', path: `/api/v1/merchant/marketing/my-campaigns/${encodeURIComponent(campaignId)}`, data: { merchantId, storeId, ...input, expectedVersion } }, receiptOf)
  }

  submitCampaign(merchantId: string, storeId: string, campaignId: string, expectedVersion: string) {
    return this.command({ method: 'POST', path: `/api/v1/merchant/marketing/my-campaigns/${encodeURIComponent(campaignId)}/submit`, data: { merchantId, storeId, expectedVersion } }, receiptOf)
  }

  cancelCampaign(merchantId: string, storeId: string, campaignId: string, expectedVersion: string) {
    return this.command({ method: 'POST', path: `/api/v1/merchant/marketing/my-campaigns/${encodeURIComponent(campaignId)}/cancel`, data: { merchantId, storeId, expectedVersion } }, receiptOf)
  }

  private async command<T>(spec: { method: 'POST' | 'PUT'; path: string; data: Record<string, unknown> }, decode: (value: unknown) => T) {
    const requestId = await this.api.uuid()
    return this.api.request({ ...spec, requestId }, decode)
  }
}
