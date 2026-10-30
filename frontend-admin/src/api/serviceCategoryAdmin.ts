import { RequestFailure, type Transport } from '../request';

// Contract-74 §4 admin service category dictionary face (CCR-SVC-CAT-001 B-SVC-02).
// Wire facts from AdminServiceCategoryController: `categoryId` is the decimal public id
// string; `version` is a JSON number (the long is written as-is, no id coercion); nullable
// patch fields distinguish absent (keep stored value) from explicit null/"" (clear) — the
// client preserves that distinction by never stringifying `undefined` keys into the body.
export const CATEGORY_STATUSES = ['ENABLED', 'DISABLED'] as const;
export type CategoryStatus = typeof CATEGORY_STATUSES[number];

export class ServiceCategoryFailure extends RequestFailure {
  constructor(code: string, status: number) { super(code, status); }
}

const ROOT = '/api/v1/admin/service-categories';
const UTC = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})(?![\s\S])/;
const DECIMAL_ID = /^[1-9][0-9]{0,18}(?![\s\S])/;
const CODE = /^[A-Z][A-Z0-9_]{1,31}$/;

export type ServiceCategoryRow = {
  categoryId: string; categoryCode: string; categoryName: string;
  parentCode: string | null; iconUrl: string | null; description: string | null;
  sortNo: number; status: CategoryStatus; version: number;
  createdAt: string; updatedAt: string;
};
export type ServiceCategoryReceipt = {
  categoryId?: string; categoryCode: string; status: CategoryStatus; version: number;
};
export type ServiceCategoryPage = {
  page: number; pageSize: number; total: number; items: ServiceCategoryRow[];
};
export type CategoryDraft = {
  categoryCode: string; categoryName: string;
  parentCode?: string; iconUrl?: string; description?: string; sortNo?: number;
};
// Absent key = keep stored value; explicit null = clear; string = set (contract 74 §5).
export type CategoryPatch = {
  categoryName?: string;
  parentCode?: string | null;
  iconUrl?: string | null;
  description?: string | null;
  sortNo?: number;
};

function isRow(v: unknown): v is ServiceCategoryRow {
  if (typeof v !== 'object' || v === null) return false;
  const r = v as Record<string, unknown>;
  return typeof r.categoryId === 'string' && DECIMAL_ID.test(r.categoryId)
    && typeof r.categoryCode === 'string' && CODE.test(r.categoryCode)
    && typeof r.categoryName === 'string'
    && (r.parentCode === null || (typeof r.parentCode === 'string' && CODE.test(r.parentCode)))
    && (r.iconUrl === null || typeof r.iconUrl === 'string')
    && (r.description === null || typeof r.description === 'string')
    && typeof r.sortNo === 'number' && Number.isInteger(r.sortNo)
    && CATEGORY_STATUSES.includes(r.status as CategoryStatus)
    && typeof r.version === 'number' && Number.isInteger(r.version) && r.version >= 0
    && typeof r.createdAt === 'string' && UTC.test(r.createdAt)
    && typeof r.updatedAt === 'string' && UTC.test(r.updatedAt);
}

function isPage(v: unknown): v is ServiceCategoryPage {
  if (typeof v !== 'object' || v === null) return false;
  const p = v as Record<string, unknown>;
  return typeof p.page === 'number' && typeof p.pageSize === 'number'
    && typeof p.total === 'number' && Array.isArray(p.items) && p.items.every(isRow);
}

function isReceipt(v: unknown): v is ServiceCategoryReceipt {
  if (typeof v !== 'object' || v === null) return false;
  const r = v as Record<string, unknown>;
  return (r.categoryId === undefined || (typeof r.categoryId === 'string' && DECIMAL_ID.test(r.categoryId)))
    && typeof r.categoryCode === 'string' && CODE.test(r.categoryCode)
    && CATEGORY_STATUSES.includes(r.status as CategoryStatus)
    && typeof r.version === 'number' && Number.isInteger(r.version) && r.version >= 0;
}

export function createServiceCategoryAdminClient(transport: Transport = fetch) {
  let epoch = 0;
  let token: string | undefined;
  const failStale = (started: number) => { if (started !== epoch) throw new ServiceCategoryFailure('STALE_CONTEXT', 0); };

  async function envelope<T>(path: string, init: { method?: 'GET' | 'POST' | 'PUT'; body?: unknown; requestId?: string } = {}, shape: (v: unknown) => v is T): Promise<T> {
    if (!token) throw new ServiceCategoryFailure('UNAUTHENTICATED', 401);
    const started = epoch;
    const headers = new Headers({ Accept: 'application/json', Authorization: `Bearer ${token}` });
    if (init.requestId !== undefined) {
      // Terminal writes require UUID-syntax X-Request-Id (idempotency + audit).
      headers.set('X-Request-Id', init.requestId);
      headers.set('Content-Type', 'application/json');
    }
    const res = await transport(new Request(new URL(path, window.location.origin), {
      method: init.method ?? 'GET',
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      headers, credentials: 'omit', redirect: 'error', cache: 'no-store',
    }));
    failStale(started);
    if (res.status === 401) { epoch++; token = undefined; throw new ServiceCategoryFailure('UNAUTHENTICATED', 401); }
    let payload: unknown;
    try { payload = await res.json(); } catch { throw new ServiceCategoryFailure('INVALID_RESPONSE', res.status); }
    failStale(started);
    const body = payload as Record<string, unknown>;
    if (typeof body?.code !== 'string') throw new ServiceCategoryFailure('INVALID_RESPONSE', res.status);
    if (!res.ok || body.code !== 'SUCCESS') throw new ServiceCategoryFailure(body.code, res.status);
    if (!shape(body.data)) throw new ServiceCategoryFailure('INVALID_RESPONSE', 200);
    return body.data;
  }

  return {
    resetContext(nextToken?: string) { epoch++; token = nextToken; },

    async list(filters: { status?: CategoryStatus; keyword?: string } = {}, page = 1, pageSize = 50) {
      const query = new URLSearchParams();
      if (filters.status) query.set('status', filters.status);
      if (filters.keyword) query.set('keyword', filters.keyword);
      query.set('page', String(page));
      query.set('pageSize', String(pageSize));
      return envelope<ServiceCategoryPage>(`${ROOT}?${query.toString()}`, {}, isPage);
    },

    async create(draft: CategoryDraft, requestId: string) {
      return envelope<ServiceCategoryReceipt>(ROOT, {
        method: 'POST',
        body: draft,
        requestId,
      }, isReceipt);
    },

    async update(categoryCode: string, patch: CategoryPatch, expectedVersion: number, requestId: string) {
      return envelope<ServiceCategoryReceipt>(`${ROOT}/${encodeURIComponent(categoryCode)}`, {
        method: 'PUT',
        body: { ...patch, expectedVersion },
        requestId,
      }, isReceipt);
    },

    async setEnabled(categoryCode: string, expectedVersion: number, enabled: boolean, requestId: string) {
      return envelope<ServiceCategoryReceipt>(`${ROOT}/${encodeURIComponent(categoryCode)}/${enabled ? 'enable' : 'disable'}`, {
        method: 'POST',
        body: { expectedVersion },
        requestId,
      }, isReceipt);
    },
  };
}
export type ServiceCategoryAdminClient = ReturnType<typeof createServiceCategoryAdminClient>;

// Readable operator-facing text per stable error code — no raw jargon dumps in the page.
const FAILURE_TEXT: Record<string, string> = {
  CATEGORY_VERSION_CONFLICT: '版本冲突：该分类刚被其他人修改过，已为你刷新列表，请重试',
  CATEGORY_CODE_CONFLICT: '保存失败：该分类编码已被占用，请更换编码后重试',
  CATEGORY_NOT_FOUND: '该分类不存在或已被删除，已为你刷新列表',
  COMMON_CONFLICT: '操作冲突：数据已被其他人修改，已为你刷新列表，请重试',
  IDEMPOTENCY_KEY_CONFLICT: '相同请求正在处理中，请稍后重试',
  COMMON_INVALID_ARGUMENT: '保存失败：填写内容不符合要求。分类编码需为大写字母开头（仅大写字母/数字/下划线，2-32 位），名称 1-20 字，排序 0-9999，图标地址须为 https:// 开头（512 字内），描述不超过 200 字',
  COMMON_FORBIDDEN: '权限不足：当前账号未授予该操作所需的 service.category.manage 权限',
  FORBIDDEN: '权限不足：当前账号未授予该操作所需的权限',
  UNAUTHENTICATED: '登录已失效，请重新登录',
  COMMON_RATE_LIMITED: '操作过于频繁，请稍等片刻后重试',
  COMMON_DEPENDENCY_UNAVAILABLE: '分类服务暂不可用（功能未开放或后端繁忙），请稍后重试',
  COMMON_INTERNAL_ERROR: '服务器开小差了，请稍后重试',
  INVALID_RESPONSE: '服务器响应格式异常，请稍后重试',
  STALE_CONTEXT: '页面状态已过期，请刷新后重试',
};

export function describeCategoryFailure(error: unknown): string {
  if (error instanceof RequestFailure) return FAILURE_TEXT[error.code] ?? `操作失败（${error.code}），请稍后重试`;
  return error instanceof Error && error.message ? `操作失败（${error.message}）` : '操作失败';
}

/** Codes whose readable text promises a list refresh — the page reloads rows after them. */
export function isCategoryConflict(error: unknown): boolean {
  return error instanceof RequestFailure
    && (error.code === 'CATEGORY_VERSION_CONFLICT'
      || error.code === 'CATEGORY_NOT_FOUND'
      || error.code === 'COMMON_CONFLICT');
}
