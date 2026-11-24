import { createWebClient, RequestFailure, type Transport } from '../request';

// Contract 77 v0.1 §4 operator face for service flash prices (P2): review
// (approve/reject) plus pause/resume/end. Reads need `service.flash-price.read`,
// writes need `service.flash-price.review`. Operators never edit price — a bad
// price is rejected so the merchant fixes it (§2.2).
const ROOT = '/api/v1/admin/service-flash-prices';

export type Page<T> = { page: number; pageSize: number; total: number; items: T[] };

// Wire shape per contract 77 §2.1/§4.1 and the WP-04 wire sample: IDs are decimal
// strings, money (flashPrice/listPriceSnapshot) and discountRate are strings,
// counts and version are numbers. Keys must match the contract — no invented names.
// (§4.1 also mentions a merchant name projection; the agreed wire sample omits it,
// so it is not typed here.)
export type FlashPriceItem = {
  flashPriceId: string;
  serviceId: string;
  merchantId: string;
  storeId: string;
  serviceName: string;
  storeName: string;
  title: string;
  description: string | null;
  priceMode: string; // P2 fixed 'DISCOUNT'; 限时折扣/限时秒杀 are merchant-side labels only
  discountRate: string;
  flashPrice: string;
  listPriceSnapshot: string;
  startAt: string;
  endAt: string;
  totalQuota: number | null; // null = 不限总量
  perUserLimit: number | null; // null = 不限每人
  soldCount: number;
  status: string;
  rejectReason: string | null;
  version: number;
  participantCount: number;
  createdAt: string;
  updatedAt: string;
};

export type FlashPriceActionReceipt = { flashPriceId: string; status: string; version: number };

const query = (params: Record<string, string | number | undefined>) => {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) if (value !== undefined && value !== '') search.set(key, String(value));
  return search.toString();
};

export function createServiceFlashPriceAdminClient(transport?: Transport) {
  const client = createWebClient(transport);
  const request = <T>(path: string, options: { method?: 'GET' | 'POST'; body?: unknown; requestId?: string } = {}) => client.request<T>(path, options);
  // §3/§4: every write carries expectedVersion (backend accepts string or int; the
  // frontend convention here is always a string) and an X-Request-Id header, which
  // createWebClient fills with a UUID when no explicit requestId is passed.
  const act = (id: string, action: 'approve' | 'reject' | 'pause' | 'end', expectedVersion: number, extra: Record<string, unknown> = {}) =>
    request<FlashPriceActionReceipt>(`${ROOT}/${id}/${action}`, {
      method: 'POST', body: { expectedVersion: String(expectedVersion), ...extra },
    });
  return {
    resetContext(token?: string) { client.resetContext(token); },

    list(status?: string, page = 1, pageSize = 20) {
      return request<Page<FlashPriceItem>>(`${ROOT}?${query({ status, page, pageSize })}`);
    },
    approve(id: string, expectedVersion: number) { return act(id, 'approve', expectedVersion); },
    reject(id: string, expectedVersion: number, reason: string) { return act(id, 'reject', expectedVersion, { reason }); },
    pause(id: string, expectedVersion: number) { return act(id, 'pause', expectedVersion); },
    end(id: string, expectedVersion: number) { return act(id, 'end', expectedVersion); },
  };
}

export type ServiceFlashPriceAdminClient = ReturnType<typeof createServiceFlashPriceAdminClient>;

// Readable operator-facing text per stable error code (contract 77 §7 flash batch +
// common codes from the 12号 registry). The page never dumps raw code jargon.
const FAILURE_TEXT: Record<string, string> = {
  FLASH_PRICE_NOT_FOUND: '该秒杀场次不存在或已被删除，已为你刷新列表',
  FLASH_PRICE_STATE_NOT_ALLOWED: '操作失败：当前状态不允许该操作，已为你刷新最新数据，请重试',
  FLASH_PRICE_WINDOW_OVERLAP: '审核未通过：该服务已有时间窗口重叠的活跃秒杀。请驳回本场次，由商家调整时间后重新提交',
  FLASH_PRICE_INVALID: '审核未通过：折扣后价格不低于服务当前价。请驳回本场次，由商家调整折扣后重新提交',
  FLASH_PRICE_DISABLED: '秒杀功能当前未开放（服务开关关闭），请稍后重试',
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

export function describeFlashPriceFailure(error: unknown, fallback = '操作失败'): string {
  if (error instanceof RequestFailure) {
    if (error.code === 'UNAUTHENTICATED') return '登录已失效，请重新登录';
    return FAILURE_TEXT[error.code] ?? `${fallback}（${error.code}）`;
  }
  return error instanceof Error && error.message ? `${fallback}（${error.message}）` : fallback;
}

/** Codes whose readable text promises a data refresh — run() reloads the list after them. */
export function isFlashPriceStale(error: unknown): boolean {
  return error instanceof RequestFailure
    && (error.code === 'COMMON_CONFLICT' || error.code === 'FLASH_PRICE_STATE_NOT_ALLOWED'
      || error.code === 'FLASH_PRICE_NOT_FOUND' || error.code === 'COMMON_NOT_FOUND');
}
