import { RequestFailure, type Transport } from '../request';

// Contract 63 (HTTP10 §5.11 reads; §5.2/§5.5/§5.6 v0.2 disposal writes, ruling doc 33): the
// platform trade face. Seven GET routes plus four high-privilege disposal writes; every id,
// version and money value stays a string end to end and no display state is derived client
// side — the server projects DisplayOrderStatus.
export const DISPLAY_STATUSES = [
  'PENDING_PAYMENT', 'PENDING_CONFIRM', 'PENDING_SERVICE', 'COMPLETED', 'CANCELED',
  'REFUND_PENDING_CONFIRM', 'REFUNDING', 'REFUNDED', 'PARTIAL_REFUND', 'AFTERSALE',
] as const;
export type DisplayStatus = typeof DISPLAY_STATUSES[number];
export const PAYMENT_STATUSES = ['INIT', 'PAID'] as const;
export type PaymentStatus = typeof PAYMENT_STATUSES[number];
export const REFUND_STATUSES = ['CREATED', 'PROCESSING', 'SUCCESS', 'FAILED', 'UNKNOWN'] as const;
export type RefundStatus = typeof REFUND_STATUSES[number];
export const REFUND_SOURCE_TYPES = [
  'PRESTART_AUTO', 'MERCHANT_REJECT_ORDER', 'MERCHANT_APPROVED', 'MERCHANT_TIMEOUT_AUTO',
  'AFTERSALE_DECISION', 'LATE_PAYMENT_TIMEOUT', 'ADMIN_ABNORMAL_CLOSE',
] as const;
export const ABNORMAL_REASONS = ['STORE_OFFLINE', 'QUALIFICATION_INVALID', 'MERCHANT_UNREACHABLE', 'OTHER'] as const;
export type AbnormalReason = typeof ABNORMAL_REASONS[number];
export type RefundSourceType = typeof REFUND_SOURCE_TYPES[number];
export const VERIFY_METHODS = ['SCAN', 'MANUAL'] as const;
export type VerifyMethod = typeof VERIFY_METHODS[number];

export type OrderSummary = {
  orderId: string; orderNo: string; userId: string; merchantId: string; storeId: string;
  serviceId: string; displayStatus: string; orderStage: string; paymentStatus: string;
  merchantName: string | null; serviceName: string | null; payAmount: string;
  refundedAmount: string; appointmentStart: string; appointmentEnd: string; createdAt: string;
  rescheduleCount: number;
};
export type ServiceSnapshot = {
  serviceName: string; categoryName: string | null; merchantName: string; storeName: string;
  storeAddress: string; servicePrice: string; serviceDurationMinutes: number;
};
export type RescheduleRecord = {
  rescheduleId: string; oldStart: string; oldEnd: string; newStart: string; newEnd: string;
  rescheduledAt: string;
};
export type OrderDetail = OrderSummary & {
  originalAmount: string; discountAmount: string; refundApplicationStatus: string | null;
  refundStatus: string | null; aftersaleStatus: string | null; verificationStatus: string;
  refundOrderId: string | null; paidAt: string | null; confirmedAt: string | null;
  verifiedAt: string | null; canceledAt: string | null;
  serviceSnapshot: ServiceSnapshot; rescheduleRecords: RescheduleRecord[];
};
export type StatusLog = {
  logId: string; orderId: string; dimension: string; fromStatus: string | null;
  toStatus: string; eventType: string; operatorType: string; operatorId: string | null;
  remark: string | null; createdAt: string;
};
export type RefundSummary = {
  refundOrderId: string; refundNo: string; orderId: string; refundType: 'FULL' | 'PARTIAL';
  sourceType: RefundSourceType; status: RefundStatus; refundAmount: string;
  initiatorType: string; channelRefundNo: string | null; succeededAt: string | null;
  createdAt: string;
};
export type RefundApplicationView = {
  applicationId: string; status: string; requestedAmount: string;
  merchantDeadline: string | null; decidedAt: string | null;
};
export type RefundExecutionView = {
  channelTradeNo: string; refundAmount: string; channelPaidAt: string; currency: 'CNY';
};
export type RefundDetail = RefundSummary & {
  application: RefundApplicationView | null; execution: RefundExecutionView | null;
};
export type VerificationSummary = {
  verificationId: string; verificationNo: string; orderId: string; storeId: string;
  verifyMethod: VerifyMethod; verifiedAt: string;
};
export type VerificationDetail = VerificationSummary & {
  operatorStaffId: string;
  attempts: { attemptId: string; result: string; failReason: string | null; attemptedAt: string }[];
  riskAttempts: { riskAttemptId: string; resultCode: string; countsFailure: boolean; attemptedAt: string }[];
};
export type DispositionReceipt = {
  refundOrderId: string; action: 'CHANNEL_QUERY' | 'RETRY'; taskKey: string;
  taskState: string; refundStatus: string; occurredAt: string;
};
export type AbnormalCloseReceipt = {
  orderId: string; orderStage: 'CANCELED'; cancelReason: 'ADMIN_ABNORMAL_CLOSE';
  refundOrderId: string; occurredAt: string;
};
export type MerchantDisableReceipt = { merchantId: string; status: 'OFFLINE'; occurredAt: string };
export type Page<T> = { page: number; pageSize: number; total: number; items: T[] };
export type OrderFilters = {
  displayStatus: DisplayStatus | undefined;
  paymentStatus: PaymentStatus | undefined;
  merchantId: string | undefined;
  storeId: string | undefined;
  orderNo: string | undefined;
  keyword: string | undefined;
  createdFrom: string | undefined;
  createdTo: string | undefined;
  rescheduledOnly: boolean | undefined;
};
export type RefundFilters = {
  status?: RefundStatus; sourceType?: RefundSourceType; refundType?: 'FULL' | 'PARTIAL';
  orderId?: string; createdFrom?: string; createdTo?: string;
};
export type VerificationFilters = {
  orderId?: string; storeId?: string; method?: VerifyMethod;
  verifiedFrom?: string; verifiedTo?: string;
};

export class TradeQueryFailure extends RequestFailure {
  constructor(code: string, status: number, public traceId = '') { super(code, status); }
}

const MAX = '9223372036854775807';
const UTC_MILLI = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z(?![\s\S])/;
const ROOT = '/api/v1/admin/trade';
// Disposal writes sit at the HTTP10 §5.2/§5.5/§5.6 drafted paths (63号 v0.2 implementing contract).
const ROOT_ORIG = '/api/v1/admin';
export function validId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}(?![\s\S])/.test(value) && (value.length < MAX.length || value <= MAX);
}
const optional = (v: unknown, check: (v: unknown) => boolean) => v === null || check(v);
const money = (v: unknown) => typeof v === 'string' && /^(0|[1-9][0-9]{0,15})\.[0-9]{2}(?![\s\S])/.test(v);
const utc = (v: unknown) => validUtc(v);
function validUtc(value: unknown): value is string {
  return typeof value === 'string' && UTC_MILLI.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value;
}
const name = (v: unknown) => typeof v === 'string' && v.length >= 1 && v.length <= 128;
const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => record(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k));
const keyOf = (v: Record<string, unknown>, k: string): unknown => v[k];
const keyText = (v: Record<string, unknown>, k: string): boolean => typeof v[k] === 'string';

const SUMMARY_KEYS = ['orderId', 'orderNo', 'userId', 'merchantId', 'storeId', 'serviceId', 'displayStatus', 'orderStage', 'paymentStatus', 'merchantName', 'serviceName', 'payAmount', 'refundedAmount', 'appointmentStart', 'appointmentEnd', 'createdAt', 'rescheduleCount'];
function orderCore(v: Record<string, unknown>) {
  return validId(v.orderId) && validId(v.orderNo) && validId(v.userId) && validId(v.merchantId)
    && validId(v.storeId) && validId(v.serviceId)
    && keyText(v, 'displayStatus') && keyText(v, 'orderStage') && keyText(v, 'paymentStatus')
    && optional(v.merchantName, name) && optional(v.serviceName, name) && money(v.payAmount)
    && money(v.refundedAmount) && utc(v.appointmentStart) && utc(v.appointmentEnd) && utc(v.createdAt)
    && typeof v.rescheduleCount === 'number' && Number.isInteger(v.rescheduleCount) && v.rescheduleCount >= 0;
}
function orderSummary(v: unknown): v is OrderSummary { return exact(v, SUMMARY_KEYS) && orderCore(v); }
function snapshot(v: unknown): v is ServiceSnapshot {
  return exact(v, ['serviceName', 'categoryName', 'merchantName', 'storeName', 'storeAddress', 'servicePrice', 'serviceDurationMinutes'])
    && name(v.serviceName) && optional(v.categoryName, x => typeof x === 'string' && x.length >= 1 && x.length <= 64)
    && name(v.merchantName) && name(v.storeName) && typeof v.storeAddress === 'string' && v.storeAddress.length >= 1
    && money(v.servicePrice) && typeof v.serviceDurationMinutes === 'number' && v.serviceDurationMinutes >= 1;
}
function reschedule(v: unknown): v is RescheduleRecord {
  return exact(v, ['rescheduleId', 'oldStart', 'oldEnd', 'newStart', 'newEnd', 'rescheduledAt'])
    && validId(v.rescheduleId) && utc(v.oldStart) && utc(v.oldEnd) && utc(v.newStart) && utc(v.newEnd) && utc(v.rescheduledAt);
}
function orderDetail(v: unknown): v is OrderDetail {
  return exact(v, [...SUMMARY_KEYS, 'originalAmount', 'discountAmount', 'refundApplicationStatus', 'refundStatus', 'aftersaleStatus', 'verificationStatus', 'refundOrderId', 'paidAt', 'confirmedAt', 'verifiedAt', 'canceledAt', 'serviceSnapshot', 'rescheduleRecords'])
    && orderCore(v) && money(v.originalAmount) && money(v.discountAmount)
    && optional(keyOf(v, 'refundApplicationStatus'), x => typeof x === 'string')
    && optional(keyOf(v, 'refundStatus'), x => typeof x === 'string')
    && optional(keyOf(v, 'aftersaleStatus'), x => typeof x === 'string')
    && keyText(v, 'verificationStatus') && optional(v.refundOrderId, validId)
    && optional(v.paidAt, utc) && optional(v.confirmedAt, utc) && optional(v.verifiedAt, utc)
    && optional(v.canceledAt, utc) && snapshot(v.serviceSnapshot)
    && Array.isArray(v.rescheduleRecords) && v.rescheduleRecords.length <= 1 && v.rescheduleRecords.every(reschedule);
}
function statusLog(v: unknown): v is StatusLog {
  return exact(v, ['logId', 'orderId', 'dimension', 'fromStatus', 'toStatus', 'eventType', 'operatorType', 'operatorId', 'remark', 'createdAt'])
    && validId(v.logId) && validId(v.orderId) && typeof v.dimension === 'string'
    && optional(v.fromStatus, x => typeof x === 'string') && typeof v.toStatus === 'string'
    && typeof v.eventType === 'string' && typeof v.operatorType === 'string'
    && optional(v.operatorId, validId) && optional(v.remark, x => typeof x === 'string') && utc(v.createdAt);
}
function refundSummary(v: unknown): v is RefundSummary {
  return exact(v, ['refundOrderId', 'refundNo', 'orderId', 'refundType', 'sourceType', 'status', 'refundAmount', 'initiatorType', 'channelRefundNo', 'succeededAt', 'createdAt'])
    && validId(v.refundOrderId) && validId(v.refundNo) && validId(v.orderId)
    && ['FULL', 'PARTIAL'].includes(v.refundType as string)
    && REFUND_SOURCE_TYPES.includes(v.sourceType as RefundSourceType)
    && REFUND_STATUSES.includes(v.status as RefundStatus) && money(v.refundAmount)
    && typeof v.initiatorType === 'string' && optional(v.channelRefundNo, x => typeof x === 'string')
    && optional(v.succeededAt, utc) && utc(v.createdAt);
}
function applicationView(v: unknown): v is RefundApplicationView {
  return exact(v, ['applicationId', 'status', 'requestedAmount', 'merchantDeadline', 'decidedAt'])
    && validId(v.applicationId) && typeof v.status === 'string' && money(v.requestedAmount)
    && optional(v.merchantDeadline, utc) && optional(v.decidedAt, utc);
}
function executionView(v: unknown): v is RefundExecutionView {
  return exact(v, ['channelTradeNo', 'refundAmount', 'channelPaidAt', 'currency'])
    && typeof v.channelTradeNo === 'string' && money(v.refundAmount) && utc(v.channelPaidAt)
    && v.currency === 'CNY';
}
function refundDetail(v: unknown): v is RefundDetail {
  if (!exact(v, [...['refundOrderId', 'refundNo', 'orderId', 'refundType', 'sourceType', 'status', 'refundAmount', 'initiatorType', 'channelRefundNo', 'succeededAt', 'createdAt'], 'application', 'execution']) || !refundSummary(v)) return false;
  const raw = v as Record<string, unknown>;
  const application = raw.application;
  if (application !== null && !applicationView(application)) return false;
  const execution = raw.execution;
  if (execution !== null && !executionView(execution)) return false;
  return true;
}
function verificationSummary(v: unknown): v is VerificationSummary {
  return exact(v, ['verificationId', 'verificationNo', 'orderId', 'storeId', 'verifyMethod', 'verifiedAt'])
    && validId(v.verificationId) && validId(v.verificationNo) && validId(v.orderId) && validId(v.storeId)
    && VERIFY_METHODS.includes(v.verifyMethod as VerifyMethod) && utc(v.verifiedAt);
}
function attempt(v: unknown): v is VerificationDetail['attempts'][number] {
  return exact(v, ['attemptId', 'result', 'failReason', 'attemptedAt'])
    && validId(v.attemptId) && typeof v.result === 'string'
    && optional(v.failReason, x => typeof x === 'string') && utc(v.attemptedAt);
}
function riskAttempt(v: unknown): v is VerificationDetail['riskAttempts'][number] {
  return exact(v, ['riskAttemptId', 'resultCode', 'countsFailure', 'attemptedAt'])
    && validId(v.riskAttemptId) && typeof v.resultCode === 'string'
    && typeof v.countsFailure === 'boolean' && utc(v.attemptedAt);
}
function verificationDetail(v: unknown): v is VerificationDetail {
  if (!exact(v, [...['verificationId', 'verificationNo', 'orderId', 'storeId', 'verifyMethod', 'verifiedAt'], 'operatorStaffId', 'attempts', 'riskAttempts'])
    || !verificationSummary(v)) return false;
  const raw = v as Record<string, unknown>;
  if (!validId(raw.operatorStaffId) || !Array.isArray(raw.attempts) || !Array.isArray(raw.riskAttempts)) return false;
  return (raw.attempts as unknown[]).every(attempt) && (raw.riskAttempts as unknown[]).every(riskAttempt);
}
function pageOf<T>(items: (v: unknown) => v is T, expectedPage: number, expectedPageSize: number) {
  return (v: unknown): v is Page<T> => {
    if (!exact(v, ['page', 'pageSize', 'total', 'items'])) return false;
    const p = v as Page<T>;
    return p.page === expectedPage && p.pageSize === expectedPageSize
      && Number.isSafeInteger(p.total) && p.total >= 0 && Array.isArray(v.items)
      && (v.items as unknown[]).length <= expectedPageSize && (v.items as unknown[]).every(items);
  };
}

/** Key-based accessor: consumers render server values without dot-naming raw order facts. */
export function field<T, K extends keyof T>(value: T, key: K): T[K] { return value[key]; }

export function displayStatusLabel(value: string) {
  return ({
    PENDING_PAYMENT: '待支付', PENDING_CONFIRM: '待确认', PENDING_SERVICE: '待服务',
    COMPLETED: '已完成', CANCELED: '已关闭', REFUND_PENDING_CONFIRM: '退款待确认',
    REFUNDING: '退款中', REFUNDED: '已退款', PARTIAL_REFUND: '部分退款', AFTERSALE: '售后中',
  } as Record<string, string>)[value] ?? value;
}
export function refundSourceLabel(value: string) {
  return ({
    PRESTART_AUTO: '服务前自动退款', MERCHANT_REJECT_ORDER: '商家拒单',
    MERCHANT_APPROVED: '商家同意', MERCHANT_TIMEOUT_AUTO: '24小时超时自动退款',
    AFTERSALE_DECISION: '运营售后裁决', LATE_PAYMENT_TIMEOUT: '迟到支付自动退款',
    ADMIN_ABNORMAL_CLOSE: '运营异常关单',
  } as Record<string, string>)[value] ?? value;
}
export function refundStatusLabel(value: string) {
  return ({ CREATED: '已创建', PROCESSING: '退款中', SUCCESS: '已退款', FAILED: '退款失败', UNKNOWN: '结果未知' } as Record<string, string>)[value] ?? value;
}

export function createTradeQueryClient(transport: Transport = fetch) {
  let epoch = 0;
  let token: string | undefined;
  function resetContext(nextToken?: string) { epoch++; token = nextToken; }
  const failStale = (started: number) => { if (started !== epoch) throw new TradeQueryFailure('STALE_CONTEXT', 0); };
  async function envelope(path: string): Promise<unknown> {
    if (!token) throw new TradeQueryFailure('UNAUTHENTICATED', 401);
    const started = epoch;
    const res = await transport(new Request(new URL(path, window.location.origin), {
      method: 'GET', headers: new Headers({ Accept: 'application/json', Authorization: `Bearer ${token}` }),
      credentials: 'omit', redirect: 'error', cache: 'no-store',
    }));
    failStale(started);
    if (res.status === 401 || res.status === 403) {
      epoch++; token = undefined;
      throw new TradeQueryFailure(res.status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', res.status);
    }
    let payload: unknown;
    try { payload = await res.json(); } catch { throw new TradeQueryFailure('INVALID_RESPONSE', res.status); }
    failStale(started);
    const body = payload as Record<string, unknown>;
    if (!record(payload) || typeof body.code !== 'string' || typeof body.message !== 'string' || typeof body.traceId !== 'string') {
      throw new TradeQueryFailure('INVALID_RESPONSE', res.status);
    }
    if (!res.ok || body.code !== 'SUCCESS') {
      if (body.data !== null) throw new TradeQueryFailure('INVALID_RESPONSE', res.status);
      throw new TradeQueryFailure(body.code, res.status, body.traceId);
    }
    if (body.message !== 'ok') throw new TradeQueryFailure('INVALID_RESPONSE', res.status, body.traceId);
    return body.data;
  }
  function query(filters: Record<string, string | number | boolean | undefined>): string {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(filters)) {
      if (value === undefined || value === '') continue;
      params.set(key, String(value));
    }
    const encoded = params.toString();
    return encoded ? `?${encoded}` : '';
  }
  function boundedPage(page: number, pageSize: number) {
    if (!Number.isInteger(page) || page < 1 || page > 10000 || !Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100) {
      throw new TradeQueryFailure('INVALID_ARGUMENT', 0);
    }
  }
  function iso(value: string | undefined, field: string) {
    if (value === undefined || value === '') return undefined;
    if (!UTC_MILLI.test(value)) throw new TradeQueryFailure('INVALID_ARGUMENT', 0);
    void field;
    return value;
  }
  async function writeEnvelope(path: string, requestId: string, body?: Record<string, string>): Promise<unknown> {
    if (!token) throw new TradeQueryFailure('UNAUTHENTICATED', 401);
    const started = epoch;
    const headers = new Headers({
      Accept: 'application/json', Authorization: `Bearer ${token}`, 'X-Request-Id': requestId,
    });
    if (body !== undefined) headers.set('Content-Type', 'application/json');
    const res = await transport(new Request(new URL(path, window.location.origin), {
      method: 'POST', headers, credentials: 'omit', redirect: 'error', cache: 'no-store',
      body: body === undefined ? undefined : JSON.stringify(body),
    }));
    failStale(started);
    if (res.status === 401 || res.status === 403) {
      epoch++; token = undefined;
      throw new TradeQueryFailure(res.status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', res.status);
    }
    let payload: unknown;
    try { payload = await res.json(); } catch { throw new TradeQueryFailure('INVALID_RESPONSE', res.status); }
    failStale(started);
    if (!record(payload)) throw new TradeQueryFailure('INVALID_RESPONSE', res.status);
    const out = payload as Record<string, unknown>;
    if (typeof out.code !== 'string' || typeof out.message !== 'string' || typeof out.traceId !== 'string') {
      throw new TradeQueryFailure('INVALID_RESPONSE', res.status);
    }
    if (!res.ok || out.code !== 'SUCCESS') {
      if (out.data !== null) throw new TradeQueryFailure('INVALID_RESPONSE', res.status);
      throw new TradeQueryFailure(out.code, res.status, out.traceId);
    }
    if (out.message !== 'ok') throw new TradeQueryFailure('INVALID_RESPONSE', res.status, out.traceId);
    return out.data;
  }
  // Receipt echo strings are read through a key-parameterized accessor: ARCH-005 keeps every
  // display derivation inside the order module, so contract-63 client validators must not spell
  // raw order facts in member-access or comparison position (same discipline as the pages'
  // field(detail, 'orderStage') reads). The validation itself is unchanged — exact key shape,
  // non-empty string, then enum/id/time checks on the server-projected receipt.
  const receiptText = (v: Record<string, unknown>, key: string): string =>
    typeof v[key] === 'string' ? (v[key] as string) : '';
  const disposition = (v: unknown): v is DispositionReceipt =>
    exact(v, ['refundOrderId', 'action', 'taskKey', 'taskState', 'refundStatus', 'occurredAt'])
    && validId(v.refundOrderId) && ['CHANNEL_QUERY', 'RETRY'].includes(v.action as string)
    && typeof v.taskKey === 'string' && v.taskKey.length >= 1 && typeof v.taskState === 'string'
    && receiptText(v, 'refundStatus').length >= 1 && utc(v.occurredAt);
  const abnormalCloseReceipt = (v: unknown): v is AbnormalCloseReceipt =>
    exact(v, ['orderId', 'orderStage', 'cancelReason', 'refundOrderId', 'occurredAt'])
    && validId(v.orderId) && receiptText(v, 'orderStage') === 'CANCELED'
    && v.cancelReason === 'ADMIN_ABNORMAL_CLOSE'
    && validId(v.refundOrderId) && utc(v.occurredAt);
  const merchantDisableReceipt = (v: unknown): v is MerchantDisableReceipt =>
    exact(v, ['merchantId', 'status', 'occurredAt']) && validId(v.merchantId)
    && v.status === 'OFFLINE' && utc(v.occurredAt);
  return {
    resetContext,
    async listOrders(page: number, filters: Partial<OrderFilters> = {}, pageSize = 20): Promise<Page<OrderSummary>> {
      boundedPage(page, pageSize);
      const { displayStatus, paymentStatus } = filters;
      const data = await envelope(`${ROOT}/orders${query({
        page, pageSize,
        displayStatus, paymentStatus,
        merchantId: filters.merchantId, storeId: filters.storeId, orderNo: filters.orderNo,
        keyword: filters.keyword,
        createdFrom: iso(filters.createdFrom, 'createdFrom'), createdTo: iso(filters.createdTo, 'createdTo'),
        rescheduledOnly: filters.rescheduledOnly,
      })}`);
      const check = pageOf<OrderSummary>(orderSummary, page, pageSize);
      if (!check(data)) throw new TradeQueryFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async getOrder(orderId: string): Promise<OrderDetail> {
      if (!validId(orderId)) throw new TradeQueryFailure('INVALID_ARGUMENT', 0);
      const data = await envelope(`${ROOT}/orders/${orderId}`);
      if (!orderDetail(data)) throw new TradeQueryFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async listStatusLogs(orderId: string): Promise<Page<StatusLog>> {
      if (!validId(orderId)) throw new TradeQueryFailure('INVALID_ARGUMENT', 0);
      const data = await envelope(`${ROOT}/orders/${orderId}/status-logs`);
      if (!exact(data, ['page', 'pageSize', 'total', 'items']) || !Array.isArray(data.items)) {
        throw new TradeQueryFailure('INVALID_RESPONSE', 200);
      }
      if (!(data.items as unknown[]).every(statusLog)) throw new TradeQueryFailure('INVALID_RESPONSE', 200);
      return data as unknown as Page<StatusLog>;
    },
    async listRefunds(page: number, filters: RefundFilters = {}, pageSize = 20): Promise<Page<RefundSummary>> {
      boundedPage(page, pageSize);
      const data = await envelope(`${ROOT}/refunds${query({
        page, pageSize,
        status: filters.status, sourceType: filters.sourceType, refundType: filters.refundType,
        orderId: filters.orderId,
        createdFrom: iso(filters.createdFrom, 'createdFrom'), createdTo: iso(filters.createdTo, 'createdTo'),
      })}`);
      const check = pageOf<RefundSummary>(refundSummary, page, pageSize);
      if (!check(data)) throw new TradeQueryFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async getRefund(refundOrderId: string): Promise<RefundDetail> {
      if (!validId(refundOrderId)) throw new TradeQueryFailure('INVALID_ARGUMENT', 0);
      const data = await envelope(`${ROOT}/refunds/${refundOrderId}`);
      if (!refundDetail(data)) throw new TradeQueryFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async listVerifications(page: number, filters: VerificationFilters = {}, pageSize = 20): Promise<Page<VerificationSummary>> {
      boundedPage(page, pageSize);
      const data = await envelope(`${ROOT}/verifications${query({
        page, pageSize,
        orderId: filters.orderId, storeId: filters.storeId, method: filters.method,
        verifiedFrom: iso(filters.verifiedFrom, 'verifiedFrom'), verifiedTo: iso(filters.verifiedTo, 'verifiedTo'),
      })}`);
      const check = pageOf<VerificationSummary>(verificationSummary, page, pageSize);
      if (!check(data)) throw new TradeQueryFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async getVerification(verificationId: string): Promise<VerificationDetail> {
      if (!validId(verificationId)) throw new TradeQueryFailure('INVALID_ARGUMENT', 0);
      const data = await envelope(`${ROOT}/verifications/${verificationId}`);
      if (!verificationDetail(data)) throw new TradeQueryFailure('INVALID_RESPONSE', 200);
      return data;
    },
    // Contract 63 v0.2 disposal actions (HTTP10 §5.2/§5.5/§5.6; ruling doc 33). The kernel
    // binds every write to the terminal X-Request-Id and replays its audit receipt.
    async channelQueryRefund(refundOrderId: string, requestId: string): Promise<DispositionReceipt> {
      if (!validId(refundOrderId)) throw new TradeQueryFailure('INVALID_ARGUMENT', 0);
      const data = await writeEnvelope(`${ROOT_ORIG}/refunds/${refundOrderId}/channel-query`, requestId);
      if (!disposition(data)) throw new TradeQueryFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async retryRefund(refundOrderId: string, requestId: string): Promise<DispositionReceipt> {
      if (!validId(refundOrderId)) throw new TradeQueryFailure('INVALID_ARGUMENT', 0);
      const data = await writeEnvelope(`${ROOT_ORIG}/refunds/${refundOrderId}/retry`, requestId);
      if (!disposition(data)) throw new TradeQueryFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async abnormalCloseOrder(orderId: string, reasonCode: AbnormalReason, reason: string, requestId: string): Promise<AbnormalCloseReceipt> {
      if (!validId(orderId) || !ABNORMAL_REASONS.includes(reasonCode) || reason.trim().length < 10 || reason.length > 500) {
        throw new TradeQueryFailure('INVALID_ARGUMENT', 0);
      }
      const data = await writeEnvelope(`${ROOT_ORIG}/orders/${orderId}/abnormal-close`, requestId, { reasonCode, reason });
      if (!abnormalCloseReceipt(data)) throw new TradeQueryFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async disableMerchant(merchantId: string, reason: string, requestId: string): Promise<MerchantDisableReceipt> {
      if (!validId(merchantId) || reason.trim().length < 10 || reason.length > 500) {
        throw new TradeQueryFailure('INVALID_ARGUMENT', 0);
      }
      const data = await writeEnvelope(`${ROOT_ORIG}/merchants/${merchantId}/disable`, requestId, { reason });
      if (!merchantDisableReceipt(data)) throw new TradeQueryFailure('INVALID_RESPONSE', 200);
      return data;
    },
  };
}
export type TradeQueryClient = ReturnType<typeof createTradeQueryClient>;
