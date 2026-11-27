import { createWebClient, RequestFailure, type Transport } from '../request';

// Contract 79 v0.1 §4 operator face for merchant-owned coupon campaigns (P3):
// review (approve/reject) plus pause/end. Reads need `coupon.campaign.read`,
// writes need `coupon.campaign.publish` (CCR §3.4: no new action codes).
// Operators never edit any field — a bad campaign is rejected so the merchant
// fixes it (§2.4: PENDING_REVIEW 之后商家不可编辑，运营只做裁决)。
const ROOT = '/api/v1/admin/coupon-campaigns/merchant';

export type Page<T> = { page: number; pageSize: number; total: number; items: T[] };

// Wire shape per contract 79 §2.3/§4. IDs are decimal strings, money
// (amountOff/thresholdAmount/budgetLimit/issuedAmount/budgetUsedAmount) stays a
// two-decimal string — the page renders it verbatim, no float math. Counts and
// version are numbers. Keys must match the contract — no invented names.
// List items add the §4 projection: merchant/store names plus the stats triple
// (claimedCount/usedCount/drivenOrderCount/budgetUsedAmount, same names as §3).
// createdAt/updatedAt follow the §3 item naming; the §4 table text only pins the
// updated_at DESC ordering, so they are typed optional.
export type MerchantCouponItem = {
  campaignId: string;
  merchantId: string;
  storeId: string | null;
  merchantName: string;
  storeName: string | null;
  name: string;
  couponType: string; // 'THRESHOLD' | 'NO_THRESHOLD'
  typeLabel: string; // '满减券' | '无门槛券'
  amountOff: string;
  thresholdAmount: string | null;
  totalLimit: number;
  perUserLimit: number;
  budgetLimit: string;
  issuedCount: number;
  issuedAmount: string;
  claimedCount: number;
  usedCount: number;
  drivenOrderCount: number;
  budgetUsedAmount: string;
  startAt: string;
  endAt: string;
  description: string | null;
  status: string;
  rejectReason: string | null;
  version: number;
  createdAt?: string;
  updatedAt?: string;
};

// Detail = full item fields + review info (§2.1 columns submit/review timestamps;
// rejectReason is already part of §2.3).
export type MerchantCouponDetail = MerchantCouponItem & {
  submittedAt: string | null;
  reviewedAt: string | null;
};

export type MerchantCouponActionReceipt = { campaignId: string; status: string; version: number };

const query = (params: Record<string, string | number | undefined>) => {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) if (value !== undefined && value !== '') search.set(key, String(value));
  return search.toString();
};

export function createMerchantCouponAdminClient(transport?: Transport) {
  const client = createWebClient(transport);
  const request = <T>(path: string, options: { method?: 'GET' | 'POST'; body?: unknown; requestId?: string } = {}) => client.request<T>(path, options);
  // §4: every write carries expectedVersion (backend accepts string or int; the
  // frontend convention here is always a string) and an X-Request-Id header,
  // which createWebClient fills with a UUID when no explicit requestId is passed.
  const act = (id: string, action: 'approve' | 'reject' | 'pause' | 'end', expectedVersion: number, extra: Record<string, unknown> = {}) =>
    request<MerchantCouponActionReceipt>(`${ROOT}/${id}/${action}`, {
      method: 'POST', body: { expectedVersion: String(expectedVersion), ...extra },
    });
  return {
    resetContext(token?: string) { client.resetContext(token); },

    list(status?: string, merchantId?: string, page = 1, pageSize = 20) {
      return request<Page<MerchantCouponItem>>(`${ROOT}?${query({ status, merchantId, page, pageSize })}`);
    },
    get(id: string) {
      return request<MerchantCouponDetail>(`${ROOT}/${id}`);
    },
    approve(id: string, expectedVersion: number) { return act(id, 'approve', expectedVersion); },
    reject(id: string, expectedVersion: number, reason: string) { return act(id, 'reject', expectedVersion, { reason }); },
    pause(id: string, expectedVersion: number) { return act(id, 'pause', expectedVersion); },
    end(id: string, expectedVersion: number) { return act(id, 'end', expectedVersion); },
  };
}

export type MerchantCouponAdminClient = ReturnType<typeof createMerchantCouponAdminClient>;

// Readable operator-facing text per stable error code (contract 79 §7 + common
// codes from the 12号 registry). The page never dumps raw code jargon.
const FAILURE_TEXT: Record<string, string> = {
  MERCHANT_COUPON_NOT_FOUND: '该商家券活动不存在或已被删除，已为你刷新列表',
  MERCHANT_COUPON_STATE_NOT_ALLOWED: '操作失败：当前状态不允许该操作，已为你刷新最新数据，请重试',
  MERCHANT_COUPON_INVALID: '操作失败：活动信息不合法或审核前置条件不满足（如活动已过有效期），已为你刷新最新数据，请重试',
  MERCHANT_COUPON_NAME_DUPLICATE: '同一商家的活动名称重复，请由商家调整后重新提交',
  MERCHANT_COUPON_NOT_ELIGIBLE: '权限不足：该操作需要商家 OWNER 或运营审核权限',
  COUPON_SCOPE_MISMATCH: '商家券只能用于归属商家的订单（数据异常），已为你刷新最新数据',
  COMMON_CONFLICT: '操作冲突：数据已被他人修改（版本不一致），已为你刷新最新数据，请重试',
  COMMON_NOT_FOUND: '数据不存在或已被删除，已为你刷新列表',
  COMMON_INVALID_ARGUMENT: '操作失败：填写内容不符合要求（如驳回原因 1-500 字），请检查后重试',
  IDEMPOTENCY_KEY_CONFLICT: '相同请求正在处理中，请稍后重试',
  COMMON_RATE_LIMITED: '操作过于频繁，请稍等片刻后重试',
  COMMON_DEPENDENCY_UNAVAILABLE: '依赖服务暂不可用（功能未开放或后端繁忙），请稍后重试',
  COMMON_INTERNAL_ERROR: '服务器开小差了，请稍后重试',
  FORBIDDEN: '权限不足：当前账号未授予该操作所需权限',
  INVALID_RESPONSE: '服务器响应格式异常，请稍后重试',
  STALE_CONTEXT: '页面状态已过期，请刷新后重试',
  INVALID_ADMIN_PATH: '请求地址非法，已阻止发送',
};

export function describeMerchantCouponFailure(error: unknown, fallback = '操作失败'): string {
  if (error instanceof RequestFailure) {
    if (error.code === 'UNAUTHENTICATED') return '登录已失效，请重新登录';
    return FAILURE_TEXT[error.code] ?? `${fallback}（${error.code}）`;
  }
  return error instanceof Error && error.message ? `${fallback}（${error.message}）` : fallback;
}

/** Codes whose readable text promises a data refresh — run() reloads the list after them. */
export function isMerchantCouponStale(error: unknown): boolean {
  return error instanceof RequestFailure
    && (error.code === 'COMMON_CONFLICT' || error.code === 'MERCHANT_COUPON_STATE_NOT_ALLOWED'
      || error.code === 'MERCHANT_COUPON_NOT_FOUND' || error.code === 'MERCHANT_COUPON_INVALID'
      || error.code === 'COMMON_NOT_FOUND');
}
