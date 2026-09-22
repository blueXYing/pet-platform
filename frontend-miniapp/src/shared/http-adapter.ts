import type { Transport } from './request'
import { assertApiOrigin } from './api-origin'
export type PlatformRequest = (options: {
  url: string; method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  data?: Record<string, unknown>; header: Record<string, string>; timeout: number;
}) => Promise<{ statusCode: number; data: unknown }>

export function createHttpAdapter(baseUrl: string, request: PlatformRequest, allowLocalHttp = false): Transport {
  assertApiOrigin(baseUrl, allowLocalHttp)
  const withQuery = (spec: { path: string; query?: Record<string, string> }) => {
    if (!spec.query || Object.keys(spec.query).length === 0) return spec.path
    const entries = Object.entries(spec.query)
    if (entries.some(([, value]) => typeof value !== 'string')) throw new Error('INVALID_QUERY')
    return `${spec.path}?${entries.map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`).join('&')}`
  }
  return spec => request({ url: baseUrl + withQuery(spec), method: spec.method,
    data: spec.data, header: spec.headers, timeout: 15000 })
}
