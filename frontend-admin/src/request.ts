// Web transport only. No permission/session DTO is defined here (CCR-PERM-001).
export type Transport = (input: Request) => Promise<Response>;
// /auth/* predates the unified wrapper and omits `success`; see A-002-review-page/CONTRACT.md §4.1.
type Envelope<T> = { success?: boolean; code: string; message: string; data: T; traceId: string };
export class RequestFailure extends Error {
  constructor(public code: string, public status: number) { super(code); }
}
export function createWebClient(transport: Transport = fetch) {
  let epoch = 0;
  let token: string | undefined;
  return {
    // Call on logout, permission revocation, or identity/data-scope changes.
    resetContext(nextToken?: string) { epoch++; token = nextToken; },
    async request<T>(path: string, options: { method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'; body?: unknown; requestId?: string; withCredentials?: boolean; headers?: Record<string, string> } = {}): Promise<T> {
      if (!path.startsWith('/api/v1/admin/') || /[\\#]|(?:%2e|%2f|%5c)/i.test(path)) throw new RequestFailure('INVALID_ADMIN_PATH', 0);
      const url = new URL(path, window.location.origin);
      if (!url.pathname.startsWith('/api/v1/admin/')) throw new RequestFailure('INVALID_ADMIN_PATH', 0);
      // The attempt cookie binds the login flow only; business calls stay token-only.
      const credentials = options.withCredentials === true && url.pathname.startsWith('/api/v1/admin/auth/') ? 'include' : 'omit';
      const started = epoch;
      const headers = new Headers({ Accept: 'application/json', ...(options.headers ?? {}) });
      if (token) headers.set('Authorization', `Bearer ${token}`);
      const method = options.method ?? 'GET';
      if (method !== 'GET') {
        headers.set('X-Request-Id', options.requestId ?? crypto.randomUUID());
        headers.set('Content-Type', 'application/json');
      }
      const response = await transport(new Request(url, { method, headers, credentials, redirect: 'error', body: options.body === undefined ? undefined : JSON.stringify(options.body) }));
      if (started !== epoch) throw new RequestFailure('STALE_CONTEXT', 0);
      if (response.status === 401 || response.status === 403) {
        epoch++; token = undefined;
        throw new RequestFailure(response.status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', response.status);
      }
      let payload: Envelope<T>;
      try { payload = await response.json() as Envelope<T>; }
      catch { throw new RequestFailure('INVALID_RESPONSE', response.status); }
      if (started !== epoch) throw new RequestFailure('STALE_CONTEXT', 0);
      if (!payload || (typeof payload.success !== 'boolean' && typeof payload.code !== 'string')) throw new RequestFailure('INVALID_RESPONSE', response.status);
      const ok = typeof payload.success === 'boolean' ? payload.success : payload.code === 'SUCCESS';
      if (!response.ok || !ok) throw new RequestFailure(payload.code || 'REQUEST_FAILED', response.status);
      return payload.data; // No ID/amount coercion or client-side display-state derivation.
    },
  };
}
