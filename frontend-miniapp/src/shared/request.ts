import { WorkspaceScope } from './workspace'

export type RequestSpec = Readonly<{
  path: string; method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  data?: Record<string, unknown>; requestId?: string;
}>
export type WireRequest = RequestSpec & { headers: Record<string, string> }
export type Transport = (request: WireRequest) => Promise<{ statusCode: number; data: unknown }>
export class ApiError extends Error {
  constructor(readonly code: string, readonly statusCode: number) { super(code) }
}
export function createClient(scope: WorkspaceScope, transport: Transport) {
  return {
    request<T>(spec: RequestSpec, decode: (data: unknown) => T): Promise<T> {
      const context = scope.current
      const prefix = context?.workspace === 'merchant' ? '/api/v1/merchant/' : '/api/v1/c/'
      if (!spec.path.startsWith(prefix) || /[?#\\]|\.\.|%/i.test(spec.path)) throw new Error('WORKSPACE_PATH_MISMATCH')
      if (spec.method !== 'GET' && !spec.requestId?.trim()) throw new Error('REQUEST_ID_REQUIRED')
      const headers: Record<string, string> = { 'Content-Type': 'application/json' }
      if (spec.method !== 'GET') headers['X-Request-Id'] = spec.requestId!
      // No implicit retries: callers preserve a write's requestId across explicit retries.
      return scope.run(undefined, async () => {
        const response = await transport({ ...spec, headers })
        const body = response.data as { success?: boolean; code?: string; data?: unknown } | null
        if (response.statusCode < 200 || response.statusCode >= 300 || body?.success !== true) {
          throw new ApiError(typeof body?.code === 'string' ? body.code : 'INVALID_RESPONSE', response.statusCode)
        }
        return decode(body.data)
      })
    },
  }
}
export function selectTransport(mode: 'fixture' | 'real', fixture: Transport): Transport {
  if (mode === 'real') throw new Error('CCR_ACR_001_NOT_APPROVED')
  return fixture
}
