import { RequestFailure, type Transport } from '../request';

// Contract-65 coupon governance face (PRD 6.4, A-005): template management (create DRAFT /
// white-listed edit / enable / offline / trial calculation) and the read-only instance
// ledger. No instance-status write exists on the wire (作废/返还 CCR-ADM-A005-OPS-001) — the
// client cannot express one.
export const TEMPLATE_STATUSES = ['DRAFT', 'ACTIVE', 'OFFLINE'] as const;
export type TemplateStatus = typeof TEMPLATE_STATUSES[number];
export const INSTANCE_STATUSES = ['AVAILABLE', 'FROZEN', 'USED', 'EXPIRED', 'RISK_FROZEN'] as const;
export type InstanceStatus = typeof INSTANCE_STATUSES[number];

export class CouponAdminFailure extends RequestFailure {
  constructor(code: string, status: number) { super(code, status); }
}

const ROOT = '/api/v1/admin';
const UTC = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})(?![\s\S])/;
const DECIMAL_ID = /^[1-9][0-9]{0,18}(?![\s\S])/;
const VERSION = /^[0-9]{1,20}(?![\s\S])/;
const MONEY = /^(0|[1-9][0-9]{0,15})\.[0-9]{2}(?![\s\S])/;
const COUNT = /^[0-9]{1,19}(?![\s\S])/;

export type AdminTemplateSummary = {
  templateId: string; name: string; status: TemplateStatus;
  amountOff: string; thresholdAmount: string | null;
  validStartAt: string; validEndAt: string;
  totalStock: string | null; issuedCount: string; version: string;
  createdAt: string; updatedAt: string;
};
export type AdminTemplateDetail = AdminTemplateSummary & {
  scopeSummary: string | null; typeLabel: string | null;
};
export type AdminTemplateMutation = {
  templateId: string; name: string; status: TemplateStatus;
  version: string; occurredAt: string;
};
export type TrialCalculation = {
  templateId: string; orderAmount: string; thresholdAmount: string | null;
  discountAmount: string | null; payAmount: string | null; eligible: boolean;
  ineligibleReason: string | null;
};
export type AdminInstanceSummary = {
  instanceId: string; templateId: string; templateName: string; userId: string;
  status: InstanceStatus; amountOff: string; thresholdAmount: string | null;
  originalExpireAt: string; expireAt: string; orderId: string | null;
  frozenAt: string | null; usedAt: string | null; createdAt: string;
};
export type LedgerRow = {
  ledgerId: string; action: string; fromStatus: string | null; toStatus: string;
  orderId: string | null; requestId: string; remark: string | null; createdAt: string;
};
export type AdminInstanceDetail = AdminInstanceSummary & {
  freezeExpireAt: string | null; updatedAt: string; ledger: LedgerRow[];
};
export type Page<T> = { page: number; pageSize: number; total: number; items: T[] };

const isPage = <T,>(value: unknown, row: (v: unknown) => v is T): value is Page<T> => {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return typeof v.page === 'number' && typeof v.pageSize === 'number'
    && typeof v.total === 'number' && Array.isArray(v.items) && v.items.every(row);
};
const moneyOrNull = (v: unknown): v is string | null =>
  v === null || (typeof v === 'string' && MONEY.test(v));
const utcOrNull = (v: unknown): v is string | null =>
  v === null || (typeof v === 'string' && UTC.test(v));

const asPage = <T,>(row: (v: unknown) => v is T) =>
  (value: unknown): value is Page<T> => isPage(value, row);

function isTemplateSummary(v: unknown): v is AdminTemplateSummary {
  if (typeof v !== 'object' || v === null) return false;
  const r = v as Record<string, unknown>;
  return typeof r.templateId === 'string' && DECIMAL_ID.test(r.templateId)
    && typeof r.name === 'string' && TEMPLATE_STATUSES.includes(r.status as TemplateStatus)
    && typeof r.amountOff === 'string' && MONEY.test(r.amountOff)
    && moneyOrNull(r.thresholdAmount)
    && typeof r.validStartAt === 'string' && UTC.test(r.validStartAt)
    && typeof r.validEndAt === 'string' && UTC.test(r.validEndAt)
    && (r.totalStock === null || typeof r.totalStock === 'string' && COUNT.test(r.totalStock))
    && typeof r.issuedCount === 'string' && COUNT.test(r.issuedCount)
    && typeof r.version === 'string' && VERSION.test(r.version)
    && typeof r.createdAt === 'string' && UTC.test(r.createdAt)
    && typeof r.updatedAt === 'string' && UTC.test(r.updatedAt);
}

function isTemplateDetail(v: unknown): v is AdminTemplateDetail {
  if (!isTemplateSummary(v)) return false;
  const r = v as Record<string, unknown>;
  return (r.scopeSummary === null || typeof r.scopeSummary === 'string')
    && (r.typeLabel === null || typeof r.typeLabel === 'string');
}

function isMutation(v: unknown): v is AdminTemplateMutation {
  if (typeof v !== 'object' || v === null) return false;
  const r = v as Record<string, unknown>;
  return typeof r.templateId === 'string' && DECIMAL_ID.test(r.templateId)
    && typeof r.name === 'string' && TEMPLATE_STATUSES.includes(r.status as TemplateStatus)
    && typeof r.version === 'string' && VERSION.test(r.version)
    && typeof r.occurredAt === 'string' && UTC.test(r.occurredAt);
}

function isTrial(v: unknown): v is TrialCalculation {
  if (typeof v !== 'object' || v === null) return false;
  const r = v as Record<string, unknown>;
  return typeof r.templateId === 'string' && DECIMAL_ID.test(r.templateId)
    && typeof r.orderAmount === 'string' && MONEY.test(r.orderAmount)
    && moneyOrNull(r.thresholdAmount) && moneyOrNull(r.discountAmount) && moneyOrNull(r.payAmount)
    && typeof r.eligible === 'boolean'
    && (r.ineligibleReason === null || typeof r.ineligibleReason === 'string');
}

function isInstanceSummary(v: unknown): v is AdminInstanceSummary {
  if (typeof v !== 'object' || v === null) return false;
  const r = v as Record<string, unknown>;
  return typeof r.instanceId === 'string' && DECIMAL_ID.test(r.instanceId)
    && typeof r.templateId === 'string' && DECIMAL_ID.test(r.templateId)
    && typeof r.templateName === 'string'
    && typeof r.userId === 'string' && DECIMAL_ID.test(r.userId)
    && INSTANCE_STATUSES.includes(r.status as InstanceStatus)
    && typeof r.amountOff === 'string' && MONEY.test(r.amountOff)
    && moneyOrNull(r.thresholdAmount)
    && typeof r.originalExpireAt === 'string' && UTC.test(r.originalExpireAt)
    && typeof r.expireAt === 'string' && UTC.test(r.expireAt)
    && (r.orderId === null || typeof r.orderId === 'string' && DECIMAL_ID.test(r.orderId))
    && utcOrNull(r.frozenAt) && utcOrNull(r.usedAt)
    && typeof r.createdAt === 'string' && UTC.test(r.createdAt);
}

function isInstanceDetail(v: unknown): v is AdminInstanceDetail {
  if (!isInstanceSummary(v)) return false;
  const r = v as Record<string, unknown>;
  if (!utcOrNull(r.freezeExpireAt) || typeof r.updatedAt !== 'string' || !UTC.test(r.updatedAt)
    || !Array.isArray(r.ledger)) return false;
  return (r.ledger as unknown[]).every(item => {
    const row = item as Record<string, unknown>;
    return typeof row.ledgerId === 'string' && DECIMAL_ID.test(row.ledgerId)
      && typeof row.action === 'string'
      && typeof row.toStatus === 'string'
      && typeof row.requestId === 'string'
      && (row.remark === null || typeof row.remark === 'string')
      && typeof row.createdAt === 'string' && UTC.test(row.createdAt);
  });
}

export type TemplateDraft = {
  name: string; amountOff: string; thresholdAmount: string | null;
  validStartAt: string; validEndAt: string; scopeSummary: string | null; typeLabel: string | null;
};

export function createCouponAdminClient(transport: Transport = fetch) {
  let epoch = 0;
  let token: string | undefined;
  const failStale = (started: number) => { if (started !== epoch) throw new CouponAdminFailure('STALE_CONTEXT', 0); };

  async function envelope<T>(path: string, init: { method?: 'GET' | 'PUT' | 'POST'; body?: unknown; requestId?: string } = {}, shape: (v: unknown) => v is T): Promise<T> {
    if (!token) throw new CouponAdminFailure('UNAUTHENTICATED', 401);
    const started = epoch;
    const headers = new Headers({ Accept: 'application/json', Authorization: `Bearer ${token}` });
    if (init.requestId) { headers.set('X-Request-Id', init.requestId); headers.set('Content-Type', 'application/json'); }
    const res = await transport(new Request(new URL(path, window.location.origin), {
      method: init.method ?? 'GET',
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      headers, credentials: 'omit', redirect: 'error', cache: 'no-store',
    }));
    failStale(started);
    if (res.status === 401 || res.status === 403) {
      epoch++; token = undefined;
      throw new CouponAdminFailure(res.status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', res.status);
    }
    let payload: unknown;
    try { payload = await res.json(); } catch { throw new CouponAdminFailure('INVALID_RESPONSE', res.status); }
    failStale(started);
    const body = payload as Record<string, unknown>;
    if (typeof body?.code !== 'string') throw new CouponAdminFailure('INVALID_RESPONSE', res.status);
    if (!res.ok || body.code !== 'SUCCESS') throw new CouponAdminFailure(body.code, res.status);
    if (!shape(body.data)) throw new CouponAdminFailure('INVALID_RESPONSE', 200);
    return body.data;
  }

  return {
    resetContext(nextToken?: string) { epoch++; token = nextToken; },

    async listTemplates(filters: { status?: TemplateStatus } = {}, page = 1, pageSize = 20) {
      const query = new URLSearchParams();
      if (filters.status) query.set('status', filters.status);
      query.set('page', String(page));
      query.set('pageSize', String(pageSize));
      return envelope<Page<AdminTemplateSummary>>(`${ROOT}/coupon-templates?${query.toString()}`, {}, asPage(isTemplateSummary));
    },

    async getTemplate(templateId: string) {
      if (!DECIMAL_ID.test(templateId)) throw new CouponAdminFailure('INVALID_ARGUMENT', 0);
      return envelope<AdminTemplateDetail>(`${ROOT}/coupon-templates/${templateId}`, {}, isTemplateDetail);
    },

    async trial(templateId: string, orderAmount: string) {
      return envelope<TrialCalculation>(
        `${ROOT}/coupon-templates/${templateId}/trial-calculation?orderAmount=${encodeURIComponent(orderAmount)}`,
        {}, isTrial);
    },

    async createTemplate(draft: TemplateDraft, requestId: string) {
      return envelope<AdminTemplateMutation>(`${ROOT}/coupon-templates`, {
        method: 'POST', body: draft, requestId,
      }, isMutation);
    },

    async updateTemplate(templateId: string, draft: TemplateDraft, expectedVersion: string, requestId: string) {
      return envelope<AdminTemplateMutation>(`${ROOT}/coupon-templates/${templateId}`, {
        method: 'PUT', body: { ...draft, expectedVersion }, requestId,
      }, isMutation);
    },

    async setTemplateEnabled(templateId: string, expectedVersion: string, enabled: boolean, requestId: string) {
      return envelope<AdminTemplateMutation>(
        `${ROOT}/coupon-templates/${templateId}/${enabled ? 'enable' : 'offline'}`, {
        method: 'POST', body: { expectedVersion }, requestId,
      }, isMutation);
    },

    async listInstances(filters: { templateId?: string; status?: InstanceStatus; orderId?: string } = {}, page = 1, pageSize = 20) {
      const query = new URLSearchParams();
      if (filters.templateId) query.set('templateId', filters.templateId);
      if (filters.status) query.set('status', filters.status);
      if (filters.orderId) query.set('orderId', filters.orderId);
      query.set('page', String(page));
      query.set('pageSize', String(pageSize));
      return envelope<Page<AdminInstanceSummary>>(`${ROOT}/coupon-instances?${query.toString()}`, {}, asPage(isInstanceSummary));
    },

    async getInstance(instanceId: string) {
      if (!DECIMAL_ID.test(instanceId)) throw new CouponAdminFailure('INVALID_ARGUMENT', 0);
      return envelope<AdminInstanceDetail>(`${ROOT}/coupon-instances/${instanceId}`, {}, isInstanceDetail);
    },
  };
}
export type CouponAdminClient = ReturnType<typeof createCouponAdminClient>;

export function isCouponConflict(error: unknown): boolean {
  return error instanceof CouponAdminFailure && error.status === 409;
}
