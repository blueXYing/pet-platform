import { RequestFailure, type Transport } from '../request';

// ADM-001 service write slice (contract 10 §5.9 / proposal SVCW-D7): the admin service
// review face. The operator never creates/edits merchant services; the two decisions are
// APPROVE/REJECT on REVIEWING rows and force-offline on ACTIVE rows. All ids stay decimal
// strings; the kernel re-validates version and status inside its own transaction.
export const SERVICE_STATUSES = ['DRAFT', 'REVIEWING', 'ACTIVE', 'OFFLINE', 'REJECTED'] as const;
export type ServiceStatus = typeof SERVICE_STATUSES[number];
export type ServiceSummary = {
  serviceId: string; merchantId: string; storeId: string; serviceName: string;
  categoryId: string; categoryName: string | null; status: ServiceStatus;
  price: string | null; listPrice: string | null; submissionNo: number; submittedAt: string;
  slaRemainingMinutes: number | null; rejectCount: number; version: string;
};
export type ServiceDecisionRow = {
  decisionId: string; submissionNo: number; decisionType: string; opinion: string | null;
  decidedAt: string;
};
export type ServiceDetail = Omit<ServiceSummary, 'rejectCount'> & {
  durationMinutes: number; fulfillmentType: string; coverAssetId: string | null;
  description: string | null; updatedAt: string; decisions: ServiceDecisionRow[];
};
export type ServicePage = { page: number; pageSize: number; total: number; items: ServiceSummary[] };
export type DecisionReceipt = { serviceId: string; status: string; version: string; decisionId?: string; actionId?: string };

export class ServiceReviewFailure extends RequestFailure {
  constructor(code: string, status: number, public traceId = '') { super(code, status); }
}

const ROOT = '/api/v1/admin/services';
const MAX = '9223372036854775807';
const UTC_MILLI = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z(?![\s\S])/;
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![\s\S])/;
export function validId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}(?![\s\S])/.test(value) && (value.length < MAX.length || value <= MAX);
}
const optional = (v: unknown, check: (v: unknown) => boolean) => v === null || check(v);
const money = (v: unknown) => typeof v === 'string' && /^(0|[1-9][0-9]{0,15})\.[0-9]{2}(?![\s\S])/.test(v);
const utc = (v: unknown) => typeof v === 'string' && UTC_MILLI.test(v) && Number.isFinite(Date.parse(v)) && new Date(v).toISOString() === v;
const text = (v: unknown, min = 1, max = 500): v is string => typeof v === 'string' && v.length >= min && v.length <= max && /\S/.test(v);
const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => record(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k));

function summary(v: unknown): v is ServiceSummary {
  return exact(v, ['serviceId', 'merchantId', 'storeId', 'serviceName', 'categoryId', 'categoryName', 'status', 'price', 'listPrice', 'submissionNo', 'submittedAt', 'slaRemainingMinutes', 'rejectCount', 'version'])
    && validId(v.serviceId) && validId(v.merchantId) && validId(v.storeId) && text(v.serviceName, 1, 128)
    && validId(v.categoryId) && optional(v.categoryName, x => text(x, 1, 64))
    && SERVICE_STATUSES.includes(v.status as ServiceStatus) && optional(v.price, money)
    && optional(v.listPrice, money) && typeof v.submissionNo === 'number' && utc(v.submittedAt)
    && optional(v.slaRemainingMinutes, x => typeof x === 'number')
    && typeof v.rejectCount === 'number' && typeof v.version === 'string' && /^[0-9]+$/.test(v.version);
}
function detail(v: unknown): v is ServiceDetail {
  if (!exact(v, ['serviceId', 'merchantId', 'storeId', 'serviceName', 'categoryId', 'categoryName', 'status', 'price', 'listPrice', 'durationMinutes', 'fulfillmentType', 'coverAssetId', 'description', 'submissionNo', 'submittedAt', 'slaRemainingMinutes', 'version', 'updatedAt', 'decisions'])) return false;
  const base = summary({ ...v, rejectCount: 0 });
  if (!base) return false;
  if (typeof v.durationMinutes !== 'number' || typeof v.fulfillmentType !== 'string'
    || optional(v.coverAssetId, validId) || optional(v.description, x => text(x, 1, 5000))
    || !optional(v.updatedAt, utc) || !Array.isArray(v.decisions)) return false;
  return (v.decisions as unknown[]).every((row: unknown) => {
    const d = row as Record<string, unknown>;
    return record(row) && exact(row, ['decisionId', 'submissionNo', 'decisionType', 'opinion', 'decidedAt'])
      && validId(d.decisionId) && typeof d.submissionNo === 'number'
      && typeof d.decisionType === 'string' && optional(d.opinion, x => text(x, 10, 500)) && utc(d.decidedAt);
  });
}
function receipt(v: unknown): v is DecisionReceipt {
  return record(v) && exact(v, ['serviceId', 'status', 'version', 'decisionId'])
    && validId(v.serviceId) && typeof v.status === 'string'
    && typeof v.version === 'string' && /^[0-9]+$/.test(v.version)
    && validId(v.decisionId);
}
function forceReceipt(v: unknown): v is DecisionReceipt {
  return record(v) && exact(v, ['serviceId', 'status', 'version', 'actionId'])
    && validId(v.serviceId) && typeof v.status === 'string'
    && typeof v.version === 'string' && /^[0-9]+$/.test(v.version)
    && validId(v.actionId);
}

/** 409 SERVICE_STATE_NOT_ALLOWED / version mismatch = definite business rejection. */
export function definiteServiceRejection(error: unknown): boolean {
  return error instanceof ServiceReviewFailure
    && (error.status === 400 || error.status === 409);
}

export function createServiceReviewClient(transport: Transport = fetch) {
  let epoch = 0;
  let token: string | undefined;
  let readServices = new Set<string>();
  function resetContext(nextToken?: string) { epoch++; token = nextToken; readServices = new Set(); }
  const failStale = (started: number) => { if (started !== epoch) throw new ServiceReviewFailure('STALE_CONTEXT', 0); };
  async function request(path: string, options: { method?: 'GET' | 'POST'; body?: unknown; requestId?: string } = {}): Promise<unknown> {
    if (!token) throw new ServiceReviewFailure('UNAUTHENTICATED', 401);
    const started = epoch;
    const headers = new Headers({ Accept: 'application/json', Authorization: `Bearer ${token}` });
    if (options.requestId) { headers.set('X-Request-Id', options.requestId); headers.set('Content-Type', 'application/json'); }
    const res = await transport(new Request(new URL(path, window.location.origin), {
      method: options.method ?? 'GET',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      headers, credentials: 'omit', redirect: 'error', cache: 'no-store',
    }));
    failStale(started);
    if (res.status === 401 || res.status === 403) {
      epoch++; token = undefined; readServices.clear();
      throw new ServiceReviewFailure(res.status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', res.status);
    }
    let payload: unknown;
    try { payload = await res.json(); } catch { throw new ServiceReviewFailure('INVALID_RESPONSE', res.status); }
    failStale(started);
    const body = payload as Record<string, unknown>;
    if (!record(payload) || typeof body.code !== 'string' || typeof body.message !== 'string' || typeof body.traceId !== 'string') {
      throw new ServiceReviewFailure('INVALID_RESPONSE', res.status);
    }
    if (!res.ok || body.code !== 'SUCCESS') {
      if (body.data !== null) throw new ServiceReviewFailure('INVALID_RESPONSE', res.status);
      throw new ServiceReviewFailure(body.code, res.status, body.traceId);
    }
    if (body.message !== 'ok') throw new ServiceReviewFailure('INVALID_RESPONSE', res.status, body.traceId);
    return body.data;
  }
  return {
    resetContext,
    async list(page: number, filters: { status?: ServiceStatus; categoryId?: string; merchantId?: string } = {}, pageSize = 20): Promise<ServicePage> {
      const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
      if (filters.status) params.set('status', filters.status);
      if (filters.categoryId) params.set('categoryId', filters.categoryId);
      if (filters.merchantId) params.set('merchantId', filters.merchantId);
      const data = await request(`${ROOT}?${params}`);
      if (!record(data) || !exact(data, ['page', 'pageSize', 'total', 'items']) || !Array.isArray(data.items)
        || (data.items as unknown[]).length > pageSize) throw new ServiceReviewFailure('INVALID_RESPONSE', 200);
      if (!(data.items as unknown[]).every(summary)) throw new ServiceReviewFailure('INVALID_RESPONSE', 200);
      return data as unknown as ServicePage;
    },
    async get(serviceId: string): Promise<ServiceDetail> {
      if (!validId(serviceId)) throw new ServiceReviewFailure('INVALID_ARGUMENT', 0);
      const data = await request(`${ROOT}/${serviceId}`);
      if (!detail(data)) throw new ServiceReviewFailure('INVALID_RESPONSE', 200);
      readServices.add(serviceId);
      return data;
    },
    canDecide(serviceId: string): boolean {
      return readServices.has(serviceId);
    },
    async decide(serviceId: string, expectedVersion: string, decisionType: 'APPROVE' | 'REJECT', opinion: string | null, requestId: string): Promise<DecisionReceipt> {
      if (!validId(serviceId) || !/^[0-9]+$/.test(expectedVersion) || !UUID.test(requestId)
        || !['APPROVE', 'REJECT'].includes(decisionType)) throw new ServiceReviewFailure('INVALID_ARGUMENT', 0);
      if (decisionType === 'REJECT' && !text(opinion, 10, 500)) throw new ServiceReviewFailure('INVALID_ARGUMENT', 0);
      const data = await request(`${ROOT}/${serviceId}/decision`, {
        method: 'POST', requestId,
        body: opinion === null ? { decisionType, expectedVersion } : { decisionType, opinion, expectedVersion },
      });
      if (!receipt(data) || data.serviceId !== serviceId) throw new ServiceReviewFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async forceOffline(serviceId: string, expectedVersion: string, reason: string, requestId: string): Promise<DecisionReceipt> {
      if (!validId(serviceId) || !/^[0-9]+$/.test(expectedVersion) || !UUID.test(requestId)
        || !text(reason, 10, 500)) throw new ServiceReviewFailure('INVALID_ARGUMENT', 0);
      const data = await request(`${ROOT}/${serviceId}/force-offline`, {
        method: 'POST', requestId, body: { reason, expectedVersion },
      });
      if (!forceReceipt(data) && !receipt(data)) throw new ServiceReviewFailure('INVALID_RESPONSE', 200);
      return data as DecisionReceipt;
    },
  };
}
export type ServiceReviewClient = ReturnType<typeof createServiceReviewClient>;

export function serviceStatusLabel(value: string) {
  return ({ DRAFT: '草稿', REVIEWING: '审核中', ACTIVE: '已上架', OFFLINE: '已下架', REJECTED: '已驳回' } as Record<string, string>)[value] ?? value;
}
