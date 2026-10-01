import { RequestFailure, type Transport } from '../request';

// Contract51/OpenAPI11: all IDs, versions and money stay strings end to end.
export const CASE_STATUSES = ['PENDING', 'PROCESSING', 'WAITING_SUPPLEMENT', 'RESOLVED', 'INVALIDATED', 'WITHDRAWN', 'CLOSED'] as const;
export type CaseStatus = typeof CASE_STATUSES[number];
export type NonRefundDecision = 'REJECT' | 'RESERVICE' | 'OTHER';
export type CaseSummary = {
  afterSaleId: string; orderId: string; merchantId: string; storeId: string; status: CaseStatus; version: string;
  sourceStage: 'VERIFIED' | 'UNVERIFIED_POST_START'; typeCode: string; demandCode: string;
  requestedAmount: string | null; createdAt: string; deadline: string;
};
export type EvidenceBatch = { batchId: string; submitterType: 'USER' | 'MERCHANT'; text: string | null; opinionCode: string | null; submittedAt: string; assetIds: string[] };
export type CaseDetail = Omit<CaseSummary, 'merchantId' | 'storeId'> & {
  description: string; supplementRequestId: string | null; supplementTarget: 'USER' | 'MERCHANT' | null;
  supplementDeadline: string | null; supplementReason: string | null; finalSetVersion: string;
  priorFinalCaseIds: string[]; newProblemStatement: string | null;
  decisionType: NonRefundDecision | 'FULL_REFUND' | 'PARTIAL_REFUND' | null;
  refundAmount: string | null; decisionReason: string | null; evidence: EvidenceBatch[];
};
export type CasePage = { page: number; pageSize: number; total: number; items: CaseSummary[] };
export type Receipt = { commandId: string; orderId: string; afterSaleId: string; status: CaseStatus; version: string; occurredAt: string; evidenceBatchId: string | null; supplementRequestId: string | null; decisionId: string | null; refundOrderId: string | null };
export type ReadGrant = { readUrl: string; expiresAt: string };
export type Action = 'accept' | 'supplement-requests' | 'close-duplicate' | 'decisions';
export type ActionBody = { expectedVersion: string } & (
  { newProblemAssessment?: string; expectedFinalSetVersion?: string } |
  { targetParty: 'USER' | 'MERCHANT'; reason: string; deadline: string } |
  { priorFinalCaseId: string; reason: string } |
  { decisionType: NonRefundDecision; reason: string; refundAmount: null }
);
export type PendingIntent = { caseId: string; action: Action | 'grant'; requestId: string; label: string; body: ActionBody | { reason: string }; batchId?: string; assetId?: string };
export class AfterSaleFailure extends RequestFailure {
  constructor(code: string, status: number, public traceId = '') { super(code, status); }
}
const MAX = '9223372036854775807';
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![\s\S])/;
const HASH = /^[0-9a-f]{64}(?![\s\S])/;
const GRANT_PATH = /^\/api\/v1\/admin\/aftersale-evidence-read-grants\/[A-Za-z0-9_-]{43}(?![\s\S])/;
const ROOT = '/api/v1/admin/aftersales';
export function validId(value: unknown, version = false): value is string {
  return typeof value === 'string' && (version ? /^(0|[1-9][0-9]{0,18})(?![\s\S])/ : /^[1-9][0-9]{0,18}(?![\s\S])/).test(value) && (value.length < MAX.length || value <= MAX);
}
export function validUtc(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z(?![\s\S])/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value;
}
const text = (v: unknown, min = 1, max = 500): v is string => typeof v === 'string' && v.length >= min && v.length <= max && /\S/.test(v);
const money = (v: unknown) => typeof v === 'string' && /^(0|[1-9][0-9]{0,15})\.[0-9]{2}(?![\s\S])/.test(v);
const optional = (v: unknown, check: (v: unknown) => boolean) => v === null || check(v);
const status = (v: unknown) => CASE_STATUSES.includes(v as CaseStatus);
const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => record(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k));
const ids = (v: unknown, max = Infinity): v is string[] => Array.isArray(v) && v.length <= max && v.every(x => validId(x)) && new Set(v).size === v.length;
const CORE = ['afterSaleId', 'orderId', 'status', 'version', 'sourceStage', 'typeCode', 'demandCode', 'requestedAmount', 'createdAt', 'deadline'];
function core(v: Record<string, unknown>) {
  return validId(v.afterSaleId) && validId(v.orderId) && status(v.status) && validId(v.version, true) && ['VERIFIED', 'UNVERIFIED_POST_START'].includes(v.sourceStage as string) && text(v.typeCode, 1, 64) && text(v.demandCode, 1, 64) && optional(v.requestedAmount, money) && validUtc(v.createdAt) && validUtc(v.deadline);
}
function summary(v: unknown): v is CaseSummary {
  return exact(v, [...CORE, 'merchantId', 'storeId']) && core(v) && validId(v.merchantId) && validId(v.storeId);
}
function batch(v: unknown) {
  return exact(v, ['batchId', 'submitterType', 'text', 'opinionCode', 'submittedAt', 'assetIds']) && validId(v.batchId) && ['USER', 'MERCHANT'].includes(v.submitterType as string) && optional(v.text, x => text(x, 10)) && optional(v.opinionCode, x => ['AGREE', 'PARTLY_AGREE', 'DISAGREE', 'NEED_USER_SUPPLEMENT'].includes(x as string)) && validUtc(v.submittedAt) && ids(v.assetIds, 6);
}
function detail(v: unknown): v is CaseDetail {
  return exact(v, [...CORE, 'description', 'supplementRequestId', 'supplementTarget', 'supplementDeadline', 'supplementReason', 'finalSetVersion', 'priorFinalCaseIds', 'newProblemStatement', 'decisionType', 'refundAmount', 'decisionReason', 'evidence']) && core(v) && text(v.description, 10) && optional(v.supplementRequestId, validId) && optional(v.supplementTarget, x => ['USER', 'MERCHANT'].includes(x as string)) && optional(v.supplementDeadline, validUtc) && optional(v.supplementReason, text) && typeof v.finalSetVersion === 'string' && HASH.test(v.finalSetVersion) && ids(v.priorFinalCaseIds) && optional(v.newProblemStatement, x => text(x, 10)) && optional(v.decisionType, x => ['REJECT', 'RESERVICE', 'OTHER', 'FULL_REFUND', 'PARTIAL_REFUND'].includes(x as string)) && optional(v.refundAmount, money) && optional(v.decisionReason, text) && Array.isArray(v.evidence) && v.evidence.every(batch);
}
function receipt(v: unknown): v is Receipt {
  return exact(v, ['commandId', 'orderId', 'afterSaleId', 'status', 'version', 'occurredAt', 'evidenceBatchId', 'supplementRequestId', 'decisionId', 'refundOrderId']) && validId(v.commandId) && validId(v.orderId) && validId(v.afterSaleId) && status(v.status) && validId(v.version, true) && validUtc(v.occurredAt) && ['evidenceBatchId', 'supplementRequestId', 'decisionId', 'refundOrderId'].every(k => optional(v[k], validId));
}
function grant(v: unknown): v is ReadGrant { return exact(v, ['readUrl', 'expiresAt']) && typeof v.readUrl === 'string' && GRANT_PATH.test(v.readUrl) && validUtc(v.expiresAt); }
function checkIntent(intent: PendingIntent) {
  const b: unknown = intent.body;
  if (!validId(intent.caseId) || !UUID.test(intent.requestId)) throw new AfterSaleFailure('INVALID_ARGUMENT', 0);
  if (intent.action === 'grant') {
    if (!validId(intent.batchId) || !validId(intent.assetId) || !exact(b, ['reason']) || !text(b.reason)) throw new AfterSaleFailure('INVALID_ARGUMENT', 0);
    return;
  }
  if (!record(b) || !validId(b.expectedVersion, true)) throw new AfterSaleFailure('INVALID_ARGUMENT', 0);
  const allowed: Record<Action, string[]> = { accept: ['expectedVersion', 'newProblemAssessment', 'expectedFinalSetVersion'], 'supplement-requests': ['expectedVersion', 'targetParty', 'reason', 'deadline'], 'close-duplicate': ['expectedVersion', 'priorFinalCaseId', 'reason'], decisions: ['expectedVersion', 'decisionType', 'reason', 'refundAmount'] };
  const action = intent.action;
  if (!(action in allowed) || Object.keys(b).some(k => !allowed[action].includes(k))) throw new AfterSaleFailure('INVALID_ARGUMENT', 0);
  let ok = false;
  switch (intent.action) {
    case 'accept': ok = (b.newProblemAssessment === undefined || text(b.newProblemAssessment)) && (b.expectedFinalSetVersion === undefined || typeof b.expectedFinalSetVersion === 'string' && HASH.test(b.expectedFinalSetVersion)); break;
    case 'supplement-requests': ok = ['USER', 'MERCHANT'].includes(b.targetParty as string) && text(b.reason) && validUtc(b.deadline) && Date.parse(b.deadline) > Date.now(); break;
    case 'close-duplicate': ok = validId(b.priorFinalCaseId) && text(b.reason); break;
    case 'decisions': ok = ['REJECT', 'RESERVICE', 'OTHER'].includes(b.decisionType as string) && text(b.reason) && b.refundAmount === null; break;
  }
  if (!ok) throw new AfterSaleFailure('INVALID_ARGUMENT', 0);
}

export function createAfterSaleClient(transport: Transport = fetch) {
  let epoch = 0;
  let token: string | undefined;
  const pending = new Map<string, PendingIntent>();
  const failStale = (started: number) => { if (started !== epoch) throw new AfterSaleFailure('STALE_CONTEXT', 0); };
  async function response(path: string, options: { body?: unknown; requestId?: string } = {}) {
    if (!token) throw new AfterSaleFailure('UNAUTHENTICATED', 401);
    const started = epoch;
    const headers = new Headers({ Accept: 'application/json', Authorization: `Bearer ${token}` });
    if (options.requestId) { headers.set('X-Request-Id', options.requestId); headers.set('Content-Type', 'application/json'); }
    const res = await transport(new Request(new URL(path, window.location.origin), { method: options.requestId ? 'POST' : 'GET', body: options.body === undefined ? undefined : JSON.stringify(options.body), headers, credentials: 'omit', redirect: 'error', cache: 'no-store' }));
    failStale(started);
    if (res.status === 401 || res.status === 403) { epoch++; token = undefined; pending.clear(); throw new AfterSaleFailure(res.status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', res.status); }
    let envelope: unknown;
    try { envelope = await res.json(); } catch { throw new AfterSaleFailure('INVALID_RESPONSE', res.status); }
    failStale(started);
    if (!exact(envelope, ['code', 'message', 'data', 'traceId']) || !text(envelope.code, 1, 100) || typeof envelope.message !== 'string' || !text(envelope.traceId, 1, 256)) throw new AfterSaleFailure('INVALID_RESPONSE', res.status);
    if (!res.ok || envelope.code !== 'SUCCESS') {
      if (envelope.data !== null) throw new AfterSaleFailure('INVALID_RESPONSE', res.status);
      throw new AfterSaleFailure(envelope.code, res.status, envelope.traceId);
    }
    if (envelope.message !== 'ok') throw new AfterSaleFailure('INVALID_RESPONSE', res.status, envelope.traceId);
    return envelope.data;
  }
  return {
    resetContext(nextToken?: string) { epoch++; token = nextToken; pending.clear(); },
    changeScope() { epoch++; pending.clear(); },
    pending: (caseId: string) => { const intent = pending.get(caseId); return intent ? structuredClone(intent) : undefined; },
    clearPending: (caseId: string) => pending.delete(caseId),
    async list(filters: { merchantId: string; storeId: string; page: number; pageSize: number; status?: CaseStatus; orderId?: string }) {
      if (!validId(filters.merchantId) || !validId(filters.storeId) || !Number.isInteger(filters.page) || filters.page < 1 || filters.page > 10000 || !Number.isInteger(filters.pageSize) || filters.pageSize < 1 || filters.pageSize > 50 || filters.status !== undefined && !status(filters.status) || filters.orderId !== undefined && !validId(filters.orderId)) throw new AfterSaleFailure('INVALID_ARGUMENT', 0);
      const query = new URLSearchParams({ merchantId: filters.merchantId, storeId: filters.storeId, page: String(filters.page), pageSize: String(filters.pageSize) });
      if (filters.status) query.set('status', filters.status);
      if (filters.orderId) query.set('orderId', filters.orderId);
      const data = await response(`${ROOT}?${query}`);
      if (!exact(data, ['page', 'pageSize', 'total', 'items']) || data.page !== filters.page || data.pageSize !== filters.pageSize || !Number.isSafeInteger(data.total) || (data.total as number) < 0 || !Array.isArray(data.items) || data.items.length > filters.pageSize || !data.items.every(summary) || !data.items.every(v => v.merchantId === filters.merchantId && v.storeId === filters.storeId)) throw new AfterSaleFailure('INVALID_RESPONSE', 200);
      return data as CasePage;
    },
    async get(caseId: string) {
      if (!validId(caseId)) throw new AfterSaleFailure('INVALID_ARGUMENT', 0);
      const data = await response(`${ROOT}/${caseId}`);
      if (!detail(data) || data.afterSaleId !== caseId) throw new AfterSaleFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async send(intent: PendingIntent) {
      // Replay input was validated on first admission; a supplement deadline may
      // pass while its ACK is lost. Replaying MUST still reach the original receipt.
      if (!pending.has(intent.caseId)) checkIntent(intent);
      const prior = pending.get(intent.caseId);
      if (prior && (prior.requestId !== intent.requestId || JSON.stringify(prior) !== JSON.stringify(intent))) throw new AfterSaleFailure('UNRESOLVED_COMMAND', 0);
      const snapshot = structuredClone(intent);
      pending.set(intent.caseId, snapshot);
      const path = intent.action === 'grant' ? `${ROOT}/${intent.caseId}/evidence-batches/${intent.batchId}/assets/${intent.assetId}/read-grants` : `${ROOT}/${intent.caseId}/${intent.action}`;
      try {
        const data = await response(path, { body: snapshot.body, requestId: snapshot.requestId });
        if (intent.action === 'grant' ? !grant(data) : !receipt(data) || data.afterSaleId !== intent.caseId) throw new AfterSaleFailure('INVALID_RESPONSE', 200);
        pending.delete(intent.caseId);
        return data as ReadGrant | Receipt;
      } catch (error) {
        if (error instanceof RequestFailure && error.code !== 'INVALID_RESPONSE' && error.status < 500 && error.status !== 0) pending.delete(intent.caseId);
        throw error;
      }
    },
    async consumeReadGrant(readUrl: string) {
      if (!GRANT_PATH.test(readUrl)) throw new AfterSaleFailure('INVALID_READ_URL', 0);
      if (!token) throw new AfterSaleFailure('UNAUTHENTICATED', 401);
      const started = epoch;
      const res = await transport(new Request(new URL(readUrl, window.location.origin), { headers: { Authorization: `Bearer ${token}`, Accept: 'image/png,image/jpeg' }, credentials: 'omit', redirect: 'error', cache: 'no-store' }));
      failStale(started);
      if (res.status === 401 || res.status === 403) { epoch++; token = undefined; pending.clear(); throw new AfterSaleFailure(res.status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', res.status); }
      if (!res.ok) throw new AfterSaleFailure(res.status === 410 ? 'READ_GRANT_EXPIRED' : 'EVIDENCE_READ_FAILED', res.status);
      const contentType = res.headers.get('Content-Type')?.split(';')[0];
      if (contentType !== 'image/png' && contentType !== 'image/jpeg') throw new AfterSaleFailure('INVALID_IMAGE_RESPONSE', res.status);
      const blob = await res.blob();
      failStale(started);
      if (!blob.size || blob.size > 20 * 1024 * 1024) throw new AfterSaleFailure('INVALID_IMAGE_RESPONSE', res.status);
      return blob;
    },
  };
}
export type AfterSaleClient = ReturnType<typeof createAfterSaleClient>;
export function caseStatusLabel(value: string) { return ({ PENDING: '待受理', PROCESSING: '处理中', WAITING_SUPPLEMENT: '等待补证', RESOLVED: '已裁决', INVALIDATED: '已失效', WITHDRAWN: '已撤回', CLOSED: '已关闭' } as Record<string, string>)[value] ?? value; }
export function decisionLabel(value: string) { return ({ REJECT: '驳回', RESERVICE: '重新服务（人工安排）', OTHER: '其他非退款处理', FULL_REFUND: '全额退款', PARTIAL_REFUND: '部分退款' } as Record<string, string>)[value] ?? value; }
