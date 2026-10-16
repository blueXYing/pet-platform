import { RequestFailure, type Transport } from '../request';

// Contract-65 encyclopedia governance face (PRD 6.10, A-005): all-status category ledger with
// counters, all-status breed page, white-listed maintenance writes and the publish/offline
// transition. Server facts only; category creation/deletion and breed creation do not exist
// in the contract (PRD five-code enum is closed — CCR-ADM-A005-OPS-001).
export const CATEGORY_CODES = ['DOG', 'CAT', 'REPTILE', 'BIRD', 'INSECT'] as const;
export type CategoryCode = typeof CATEGORY_CODES[number];
export const CATEGORY_NAMES: Record<CategoryCode, string> = {
  DOG: '狗狗', CAT: '猫咪', REPTILE: '爬宠', BIRD: '鸟类', INSECT: '昆虫',
};
export const BREED_STATUSES = ['PUBLISHED', 'OFFLINE'] as const;
export type BreedStatus = typeof BREED_STATUSES[number];

export class EncyclopediaFailure extends RequestFailure {
  constructor(code: string, status: number) { super(code, status); }
}

const ROOT = '/api/v1/admin/encyclopedia';
const UTC = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})(?![\s\S])/;
const DECIMAL_ID = /^[1-9][0-9]{0,18}(?![\s\S])/;
const VERSION = /^[0-9]{1,20}(?![\s\S])/;

export type AdminCategoryRow = {
  categoryCode: CategoryCode; categoryName: string; status: 'ENABLED' | 'DISABLED';
  sortOrder: number; breedCount: number; publishedBreedCount: number;
  version: string; createdAt: string; updatedAt: string;
};
export type AdminBreedCard = {
  breedId: string; categoryCode: CategoryCode; categoryName: string; name: string;
  aliases: string[]; tags: string[]; summary: string; coverUrl: string | null;
  status: BreedStatus; sortOrder: number; version: string; updatedAt: string;
};
export type AdminBreedDetail = AdminBreedCard & {
  bodyType: string | null; descriptionHtml: string | null; careAdviceHtml: string | null;
  healthTipsHtml: string | null; createdAt: string;
};
export type CategoryMutation = {
  categoryCode: CategoryCode; categoryName: string; status: 'ENABLED' | 'DISABLED';
  sortOrder: number; version: string; occurredAt: string;
};
export type BreedMutation = {
  breedId: string; name: string; status: BreedStatus; sortOrder: number;
  version: string; occurredAt: string;
};

export type Page<T> = { page: number; pageSize: number; total: number; items: T[] };

function isPage<T>(value: unknown, row: (v: unknown) => v is T): value is Page<T> {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return typeof v.page === 'number' && typeof v.pageSize === 'number'
    && typeof v.total === 'number' && Array.isArray(v.items) && v.items.every(row);
}

function asPage<T>(row: (v: unknown) => v is T): (v: unknown) => v is Page<T> {
  return (value: unknown): value is Page<T> => isPage(value, row);
}

function isCategoryRow(v: unknown): v is AdminCategoryRow {
  if (typeof v !== 'object' || v === null) return false;
  const r = v as Record<string, unknown>;
  return CATEGORY_CODES.includes(r.categoryCode as CategoryCode)
    && typeof r.categoryName === 'string'
    && (r.status === 'ENABLED' || r.status === 'DISABLED')
    && typeof r.sortOrder === 'number' && typeof r.breedCount === 'number'
    && typeof r.publishedBreedCount === 'number'
    && typeof r.version === 'string' && VERSION.test(r.version)
    && typeof r.createdAt === 'string' && UTC.test(r.createdAt)
    && typeof r.updatedAt === 'string' && UTC.test(r.updatedAt);
}

function isBreedCard(v: unknown): v is AdminBreedCard {
  if (typeof v !== 'object' || v === null) return false;
  const r = v as Record<string, unknown>;
  return typeof r.breedId === 'string' && DECIMAL_ID.test(r.breedId)
    && CATEGORY_CODES.includes(r.categoryCode as CategoryCode)
    && typeof r.categoryName === 'string' && typeof r.name === 'string'
    && Array.isArray(r.aliases) && r.aliases.every(x => typeof x === 'string')
    && Array.isArray(r.tags) && r.tags.every(x => typeof x === 'string')
    && typeof r.summary === 'string'
    && (r.coverUrl === null || typeof r.coverUrl === 'string')
    && BREED_STATUSES.includes(r.status as BreedStatus)
    && typeof r.sortOrder === 'number'
    && typeof r.version === 'string' && VERSION.test(r.version)
    && typeof r.updatedAt === 'string' && UTC.test(r.updatedAt);
}

function isBreedDetail(v: unknown): v is AdminBreedDetail {
  if (!isBreedCard(v)) return false;
  const r = v as Record<string, unknown>;
  return (r.bodyType === null || typeof r.bodyType === 'string')
    && (r.descriptionHtml === null || typeof r.descriptionHtml === 'string')
    && (r.careAdviceHtml === null || typeof r.careAdviceHtml === 'string')
    && (r.healthTipsHtml === null || typeof r.healthTipsHtml === 'string')
    && typeof r.createdAt === 'string' && UTC.test(r.createdAt);
}

function isCategoryMutation(v: unknown): v is CategoryMutation {
  if (!isCategoryRow(v)) return false;
  return typeof (v as Record<string, unknown>).occurredAt === 'string';
}

function isBreedMutation(v: unknown): v is BreedMutation {
  if (!isBreedCard(v)) return false;
  return typeof (v as Record<string, unknown>).occurredAt === 'string';
}

export function createEncyclopediaAdminClient(transport: Transport = fetch) {
  let epoch = 0;
  let token: string | undefined;
  const failStale = (started: number) => { if (started !== epoch) throw new EncyclopediaFailure('STALE_CONTEXT', 0); };

  async function envelope<T>(path: string, init: { method?: 'GET' | 'PUT' | 'POST'; body?: unknown; requestId?: string } = {}, shape: (v: unknown) => v is T): Promise<T> {
    if (!token) throw new EncyclopediaFailure('UNAUTHENTICATED', 401);
    const started = epoch;
    const headers = new Headers({ Accept: 'application/json', Authorization: `Bearer ${token}` });
    if (init.requestId) { headers.set('X-Request-Id', init.requestId); headers.set('Content-Type', 'application/json'); }
    const res = await transport(new Request(new URL(path + (init.requestId && !init.body ? '' : ''), window.location.origin), {
      method: init.method ?? 'GET',
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      headers, credentials: 'omit', redirect: 'error', cache: 'no-store',
    }));
    failStale(started);
    if (res.status === 401 || res.status === 403) {
      epoch++; token = undefined;
      throw new EncyclopediaFailure(res.status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', res.status);
    }
    let payload: unknown;
    try { payload = await res.json(); } catch { throw new EncyclopediaFailure('INVALID_RESPONSE', res.status); }
    failStale(started);
    const body = payload as Record<string, unknown>;
    if (typeof body?.code !== 'string') throw new EncyclopediaFailure('INVALID_RESPONSE', res.status);
    if (!res.ok || body.code !== 'SUCCESS') throw new EncyclopediaFailure(body.code, res.status);
    if (!shape(body.data)) throw new EncyclopediaFailure('INVALID_RESPONSE', 200);
    return body.data;
  }

  const pageQuery = (page: number, pageSize: number) =>
    `?page=${encodeURIComponent(String(page))}&pageSize=${encodeURIComponent(String(pageSize))}`;

  return {
    resetContext(nextToken?: string) { epoch++; token = nextToken; },

    async listCategories(page = 1, pageSize = 20) {
      const data = await envelope<Page<AdminCategoryRow>>(`${ROOT}/categories${pageQuery(page, pageSize)}`, {}, asPage(isCategoryRow));
      return data;
    },

    async updateCategory(categoryCode: CategoryCode, categoryName: string, sortOrder: number, expectedVersion: string, requestId: string) {
      return envelope<CategoryMutation>(`${ROOT}/categories/${categoryCode}`, {
        method: 'PUT',
        body: { categoryName, sortOrder, expectedVersion },
        requestId,
      }, isCategoryMutation);
    },

    async setCategoryEnabled(categoryCode: CategoryCode, expectedVersion: string, enabled: boolean, requestId: string) {
      return envelope<CategoryMutation>(`${ROOT}/categories/${categoryCode}/${enabled ? 'enable' : 'disable'}`, {
        method: 'POST',
        body: { expectedVersion },
        requestId,
      }, isCategoryMutation);
    },

    async listBreeds(filters: { status?: BreedStatus; categoryCode?: CategoryCode; keyword?: string } = {}, page = 1, pageSize = 20) {
      const query = new URLSearchParams();
      if (filters.status) query.set('status', filters.status);
      if (filters.categoryCode) query.set('categoryCode', filters.categoryCode);
      if (filters.keyword) query.set('keyword', filters.keyword);
      query.set('page', String(page));
      query.set('pageSize', String(pageSize));
      const data = await envelope<Page<AdminBreedCard>>(`${ROOT}/breeds?${query.toString()}`, {}, asPage(isBreedCard));
      return data;
    },

    async getBreed(breedId: string) {
      if (!DECIMAL_ID.test(breedId)) throw new EncyclopediaFailure('INVALID_ARGUMENT', 0);
      return envelope<AdminBreedDetail>(`${ROOT}/breeds/${breedId}`, {}, isBreedDetail);
    },

    async updateBreed(breedId: string, draft: { summary: string; coverUrl: string | null; bodyType: string | null; aliases: string[]; tags: string[]; sortOrder: number }, expectedVersion: string, requestId: string) {
      return envelope<BreedMutation>(`${ROOT}/breeds/${breedId}`, {
        method: 'PUT',
        body: { ...draft, expectedVersion },
        requestId,
      }, isBreedMutation);
    },

    async setBreedPublished(breedId: string, expectedVersion: string, published: boolean, requestId: string) {
      return envelope<BreedMutation>(`${ROOT}/breeds/${breedId}/${published ? 'publish' : 'offline'}`, {
        method: 'POST',
        body: { expectedVersion },
        requestId,
      }, isBreedMutation);
    },
  };
}
export type EncyclopediaAdminClient = ReturnType<typeof createEncyclopediaAdminClient>;

export function isConflict(error: unknown): boolean {
  return error instanceof EncyclopediaFailure && error.status === 409;
}
