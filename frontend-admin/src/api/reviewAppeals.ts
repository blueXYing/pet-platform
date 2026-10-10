import { RequestFailure, type Transport } from '../request';

// Contract56 (REV-002): all IDs stay decimal strings end to end; the decided terminal is
// APPROVED (review hidden) / REJECTED (stays published) — no re-decision, no second appeal.
export const APPEAL_STATUSES = ['SUBMITTED', 'PROCESSING', 'APPROVED', 'REJECTED'] as const;
export type AppealStatus = typeof APPEAL_STATUSES[number];
export type DecisionType = 'APPROVED' | 'REJECTED';
export type AppealSummary = {
  appealId: string; reviewId: string; merchantId: string; storeId: string; orderId: string;
  status: AppealStatus; reason: string; createdAt: string; decidedAt: string | null;
};
export type AppealedReview = {
  reviewId: string; orderId: string; storeScore: string; serviceScore: string; staffScore: string;
  compositeScore: string; scoreIncluded: boolean; visibilityStatus: 'PUBLISHED' | 'HIDDEN';
  content: string | null; createdAt: string; appealStatus: AppealStatus | null; appealId: string | null;
};
export type AppealDetail = AppealSummary & { decisionReason: string | null; decidedBy: string | null; review: AppealedReview };
export type AppealPage = { page: number; pageSize: number; total: number; items: AppealSummary[] };
export type DecisionReceipt = {
  appealId: string; reviewId: string; status: AppealStatus; decisionType: DecisionType;
  decisionReason: string; decidedAt: string; reviewVisibility: 'PUBLISHED' | 'HIDDEN';
};
export class ReviewAppealFailure extends RequestFailure {
  constructor(code: string, status: number, public traceId = '') { super(code, status); }
}
const MAX = '9223372036854775807';
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![\s\S])/;
const ROOT = '/api/v1/admin/review-appeals';
export function validId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}(?![\s\S])/.test(value) && (value.length < MAX.length || value <= MAX);
}
function validUtc(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z(?![\s\S])/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value;
}
const score = (v: unknown) => typeof v === 'string' && /^[1-5]\.[0-9]$/.test(v);
const text = (v: unknown, min = 1, max = 1000): v is string => typeof v === 'string' && v.length >= min && v.length <= max && /\S/.test(v);
const optional = (v: unknown, check: (v: unknown) => boolean) => v === null || check(v);
const status = (v: unknown) => APPEAL_STATUSES.includes(v as AppealStatus);
const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => record(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k));
const SUMMARY_KEYS = ['appealId', 'reviewId', 'merchantId', 'storeId', 'orderId', 'status', 'reason', 'createdAt', 'decidedAt'];
const REVIEW_KEYS = ['reviewId', 'orderId', 'storeScore', 'serviceScore', 'staffScore', 'compositeScore', 'scoreIncluded', 'visibilityStatus', 'content', 'createdAt', 'appealStatus', 'appealId'];
function coreFields(v: Record<string, unknown>) {
  return validId(v.appealId) && validId(v.reviewId) && validId(v.merchantId) && validId(v.storeId) && validId(v.orderId)
    && status(v.status) && text(v.reason) && validUtc(v.createdAt) && optional(v.decidedAt, validUtc);
}
function reviewFields(v: Record<string, unknown>) {
  return validId(v.reviewId) && validId(v.orderId) && score(v.storeScore) && score(v.serviceScore) && score(v.staffScore) && score(v.compositeScore)
    && typeof v.scoreIncluded === 'boolean' && ['PUBLISHED', 'HIDDEN'].includes(v.visibilityStatus as string)
    && optional(v.content, x => text(x, 1, 2000)) && validUtc(v.createdAt)
    && optional(v.appealStatus, status) && optional(v.appealId, validId);
}
function summary(v: unknown): v is AppealSummary {
  return exact(v, SUMMARY_KEYS) && coreFields(v);
}
function detail(v: unknown): v is AppealDetail {
  return exact(v, [...SUMMARY_KEYS, 'decisionReason', 'decidedBy', 'review']) && coreFields(v)
    && optional(v.decisionReason, text) && optional(v.decidedBy, validId)
    && record(v.review) && exact(v.review, REVIEW_KEYS) && reviewFields(v.review);
}
function receipt(v: unknown): v is DecisionReceipt {
  return exact(v, ['appealId', 'reviewId', 'status', 'decisionType', 'decisionReason', 'decidedAt', 'reviewVisibility'])
    && validId(v.appealId) && validId(v.reviewId) && status(v.status) && ['APPROVED', 'REJECTED'].includes(v.decisionType as string)
    && text(v.decisionReason) && validUtc(v.decidedAt) && ['PUBLISHED', 'HIDDEN'].includes(v.reviewVisibility as string);
}
function checkIntent(intent: DecisionIntent) {
  if (!validId(intent.appealId) || !UUID.test(intent.requestId)) throw new ReviewAppealFailure('INVALID_ARGUMENT', 0);
  if (!['APPROVED', 'REJECTED'].includes(intent.decisionType) || !text(intent.reason)) throw new ReviewAppealFailure('INVALID_ARGUMENT', 0);
}
export type DecisionIntent = { appealId: string; requestId: string; decisionType: DecisionType; reason: string };
// Business commands survive session expiry; they carry only the original payload/UUID.
const JOURNAL_PREFIX = 'pet.admin.review-appeal.command.v1:';
type Journal = { revision: 1; operatorId: string; appealId: string; intent: DecisionIntent };
/** 终局业务拒绝：一次性申诉已用（REVIEW_APPEAL_ALREADY_USED）或参数终拒；可安全退役命令。
 *  COMMON_CONFLICT 不在其中（23号 §5.7：请求锁忙结局未知，只能按原 UUID 重试）。 */
export function definiteAppealRejection(error: unknown): boolean {
  return error instanceof ReviewAppealFailure
    && (error.status === 400 || error.status === 409 && error.code === 'REVIEW_APPEAL_ALREADY_USED');
}

export function createReviewAppealClient(transport: Transport = fetch) {
  let epoch = 0;
  let token: string | undefined;
  let operatorId: string | undefined;
  const readAppeals = new Set<string>();
  function clearLive() { readAppeals.clear(); }
  const failStale = (started: number) => { if (started !== epoch) throw new ReviewAppealFailure('STALE_CONTEXT', 0); };
  function journalKey(appealId: string) {
    if (!operatorId || !readAppeals.has(appealId)) throw new ReviewAppealFailure('RESOURCE_SCOPE_UNVERIFIED', 0);
    return `${JOURNAL_PREFIX}${operatorId}:${appealId}`;
  }
  function pending(appealId: string): DecisionIntent | undefined {
    const key = journalKey(appealId);
    let raw: string | null;
    try { raw = window.localStorage.getItem(key); } catch { throw new ReviewAppealFailure('JOURNAL_UNAVAILABLE', 0); }
    if (raw === null) return undefined;
    try {
      const entry: unknown = JSON.parse(raw);
      if (!exact(entry, ['revision', 'operatorId', 'appealId', 'intent']) || (entry as Journal).revision !== 1
        || (entry as Journal).operatorId !== operatorId || (entry as Journal).appealId !== appealId) throw new Error('bad journal');
      const intent = (entry as Journal).intent;
      checkIntent(intent);
      if (intent.appealId !== appealId) throw new Error('bad journal');
      return structuredClone(intent);
    } catch { throw new ReviewAppealFailure('JOURNAL_CORRUPTED', 0); }
  }
  function save(intent: DecisionIntent) {
    const key = journalKey(intent.appealId);
    const entry: Journal = { revision: 1, operatorId: operatorId!, appealId: intent.appealId, intent };
    const serialized = JSON.stringify(entry);
    try {
      window.localStorage.setItem(key, serialized);
      if (window.localStorage.getItem(key) !== serialized) throw new Error('journal write failed');
    } catch { throw new ReviewAppealFailure('JOURNAL_UNAVAILABLE', 0); }
    return { key, serialized };
  }
  function retire(saved: { key: string; serialized: string }) {
    try { if (window.localStorage.getItem(saved.key) === saved.serialized) window.localStorage.removeItem(saved.key); }
    catch { throw new ReviewAppealFailure('JOURNAL_UNAVAILABLE', 0); }
  }
  async function response(path: string, options: { body?: unknown; requestId?: string } = {}) {
    if (!token) throw new ReviewAppealFailure('UNAUTHENTICATED', 401);
    const started = epoch;
    const headers = new Headers({ Accept: 'application/json', Authorization: `Bearer ${token}` });
    if (options.requestId) { headers.set('X-Request-Id', options.requestId); headers.set('Content-Type', 'application/json'); }
    const res = await transport(new Request(new URL(path, window.location.origin), { method: options.requestId ? 'POST' : 'GET', body: options.body === undefined ? undefined : JSON.stringify(options.body), headers, credentials: 'omit', redirect: 'error', cache: 'no-store' }));
    failStale(started);
    if (res.status === 401 || res.status === 403) { epoch++; token = undefined; clearLive(); throw new ReviewAppealFailure(res.status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', res.status); }
    let envelope: unknown;
    try { envelope = await res.json(); } catch { throw new ReviewAppealFailure('INVALID_RESPONSE', res.status); }
    failStale(started);
    if (!exact(envelope, ['code', 'message', 'data', 'traceId']) || !text(envelope.code, 1, 100) || typeof envelope.message !== 'string' || !text(envelope.traceId, 1, 256)) throw new ReviewAppealFailure('INVALID_RESPONSE', res.status);
    if (!res.ok || envelope.code !== 'SUCCESS') {
      if (envelope.data !== null) throw new ReviewAppealFailure('INVALID_RESPONSE', res.status);
      throw new ReviewAppealFailure(envelope.code, res.status, envelope.traceId);
    }
    if (envelope.message !== 'ok') throw new ReviewAppealFailure('INVALID_RESPONSE', res.status, envelope.traceId);
    return envelope.data;
  }
  async function list(filters: { page: number; pageSize: number; status?: AppealStatus }) {
    if (!Number.isInteger(filters.page) || filters.page < 1 || filters.page > 10000 || !Number.isInteger(filters.pageSize) || filters.pageSize < 1 || filters.pageSize > 50 || filters.status !== undefined && !status(filters.status)) throw new ReviewAppealFailure('INVALID_ARGUMENT', 0);
    const query = new URLSearchParams({ page: String(filters.page), pageSize: String(filters.pageSize) });
    if (filters.status) query.set('status', filters.status);
    const data = await response(`${ROOT}?${query}`);
    if (!exact(data, ['page', 'pageSize', 'total', 'items']) || data.page !== filters.page || data.pageSize !== filters.pageSize
      || !Number.isSafeInteger(data.total) || (data.total as number) < 0 || !Array.isArray(data.items)
      || data.items.length > filters.pageSize || !data.items.every(summary)) throw new ReviewAppealFailure('INVALID_RESPONSE', 200);
    return data as AppealPage;
  }
  async function get(appealId: string) {
    if (!validId(appealId)) throw new ReviewAppealFailure('INVALID_ARGUMENT', 0);
    const data = await response(`${ROOT}/${appealId}`);
    if (!detail(data) || data.appealId !== appealId) throw new ReviewAppealFailure('INVALID_RESPONSE', 200);
    readAppeals.add(appealId);
    return data;
  }
  return {
    resetContext(nextToken?: string) { epoch++; token = nextToken; clearLive(); },
    bindIdentity(currentOperatorId: string) { if (!validId(currentOperatorId)) throw new ReviewAppealFailure('INVALID_AUTHORITY', 0); operatorId = currentOperatorId; },
    pending, list, get,
    async decide(intent: DecisionIntent) {
      checkIntent(intent);
      const prior = pending(intent.appealId);
      if (prior && (prior.requestId !== intent.requestId || JSON.stringify(prior) !== JSON.stringify(intent))) throw new ReviewAppealFailure('UNRESOLVED_COMMAND', 0);
      const snapshot = structuredClone(intent);
      const journal = save(snapshot);
      try {
        const data = await response(`${ROOT}/${snapshot.appealId}/decision`,
          { body: { decisionType: snapshot.decisionType, reason: snapshot.reason }, requestId: snapshot.requestId });
        if (!receipt(data) || data.appealId !== snapshot.appealId) throw new ReviewAppealFailure('INVALID_RESPONSE', 200);
        retire(journal);
        return data;
      } catch (error) {
        if (definiteAppealRejection(error)) retire(journal);
        throw error;
      }
    },
  };
}
export type ReviewAppealClient = ReturnType<typeof createReviewAppealClient>;
export function appealStatusLabel(value: string) { return ({ SUBMITTED: '待裁决', PROCESSING: '处理中', APPROVED: '申诉成立', REJECTED: '申诉未成立' } as Record<string, string>)[value] ?? value; }
export function decisionHint(value: DecisionType) { return value === 'APPROVED' ? '申诉成立：评价将停止公开展示（HIDDEN），不可改判。' : '申诉未成立：评价维持公开展示，不可改判。'; }
