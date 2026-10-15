import { RequestFailure, type Transport } from '../request';

// Contract 64 (HTTP10 §5.12) shared transport discipline for the three governance read
// clients: single Authorization header, GET-only routes, strict envelope shape, string ids
// and UTC milli timestamps end to end, no client-side state derivation. Same failure
// semantics as the contract-63 trade client: 401/403 drop the token (auth lost).
export class GovernanceFailure extends RequestFailure {
  constructor(code: string, status: number, public traceId = '') { super(code, status); }
}

export const MAX_ID = '9223372036854775807';
export const UTC_MILLI = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z(?![\s\S])/;
export const ROOT = '/api/v1/admin/governance';

export type Page<T> = { page: number; pageSize: number; total: number; items: T[] };

export function validId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}(?![\s\S])/.test(value)
    && (value.length < MAX_ID.length || value <= MAX_ID);
}
export function validUtc(value: unknown): value is string {
  return typeof value === 'string' && UTC_MILLI.test(value)
    && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value;
}
export const optional = (v: unknown, check: (v: unknown) => boolean) => v === null || check(v);
export const name = (v: unknown) => typeof v === 'string' && v.length >= 1 && v.length <= 128;
export const text = (v: unknown) => typeof v === 'string';
export const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
export const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => record(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k));
export const enumOf = <T extends string>(allowed: readonly string[]) => (v: unknown): v is T => typeof v === 'string' && (allowed as readonly string[]).includes(v);

export function pageOf<T>(items: (v: unknown) => v is T, expectedPage: number, expectedPageSize: number) {
  return (v: unknown): v is Page<T> => {
    if (!exact(v, ['page', 'pageSize', 'total', 'items'])) return false;
    const p = v as Page<T>;
    return p.page === expectedPage && p.pageSize === expectedPageSize
      && Number.isSafeInteger(p.total) && p.total >= 0 && Array.isArray(v.items)
      && (v.items as unknown[]).length <= expectedPageSize && (v.items as unknown[]).every(items);
  };
}

export function createGovernanceEnvelope(transport: Transport) {
  let epoch = 0;
  let token: string | undefined;
  function resetContext(nextToken?: string) { epoch++; token = nextToken; }
  return {
    resetContext,
    async envelope(path: string): Promise<unknown> {
      if (!token) throw new GovernanceFailure('UNAUTHENTICATED', 401);
      const started = epoch;
      const res = await transport(new Request(new URL(path, window.location.origin), {
        method: 'GET', headers: new Headers({ Accept: 'application/json', Authorization: `Bearer ${token}` }),
        credentials: 'omit', redirect: 'error', cache: 'no-store',
      }));
      if (started !== epoch) throw new GovernanceFailure('STALE_CONTEXT', 0);
      if (res.status === 401 || res.status === 403) {
        epoch++; token = undefined;
        throw new GovernanceFailure(res.status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', res.status);
      }
      let payload: unknown;
      try { payload = await res.json(); } catch { throw new GovernanceFailure('INVALID_RESPONSE', res.status); }
      if (started !== epoch) throw new GovernanceFailure('STALE_CONTEXT', 0);
      const body = payload as Record<string, unknown>;
      if (!record(payload) || typeof body.code !== 'string' || typeof body.message !== 'string' || typeof body.traceId !== 'string') {
        throw new GovernanceFailure('INVALID_RESPONSE', res.status);
      }
      if (!res.ok || body.code !== 'SUCCESS') {
        if (body.data !== null) throw new GovernanceFailure('INVALID_RESPONSE', res.status);
        throw new GovernanceFailure(body.code, res.status, body.traceId);
      }
      if (body.message !== 'ok') throw new GovernanceFailure('INVALID_RESPONSE', res.status, body.traceId);
      return body.data;
    },
  };
}

export function queryString(filters: Record<string, string | number | boolean | undefined>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value === undefined || value === '') continue;
    params.set(key, String(value));
  }
  const encoded = params.toString();
  return encoded ? `?${encoded}` : '';
}

export function boundedPage(page: number, pageSize: number) {
  if (!Number.isInteger(page) || page < 1 || page > 10000 || !Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100) {
    throw new GovernanceFailure('INVALID_ARGUMENT', 0);
  }
}

export function isoFilter(value: string | undefined): string | undefined {
  if (value === undefined || value === '') return undefined;
  if (!UTC_MILLI.test(value)) throw new GovernanceFailure('INVALID_ARGUMENT', 0);
  return value;
}

/** Shared failure renderer: error code plus the trace id the server stamped on the envelope. */
export function governanceFailureText(caught: unknown, fallback: string): string {
  if (caught instanceof GovernanceFailure) {
    return `${fallback}（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}`;
  }
  return `${fallback}，请重试。`;
}
