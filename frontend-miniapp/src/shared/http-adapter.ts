import type { Transport } from './request'
export type PlatformRequest = (options: {
  url: string; method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  data?: Record<string, unknown>; header: Record<string, string>; timeout: number;
}) => Promise<{ statusCode: number; data: unknown }>

export function createHttpAdapter(baseUrl: string, request: PlatformRequest): Transport {
  if (!/^https:\/\/[^/?#]+$/.test(baseUrl)) throw new Error('HTTPS_ORIGIN_REQUIRED')
  return spec => request({ url: baseUrl + spec.path, method: spec.method,
    data: spec.data, header: spec.headers, timeout: 15000 })
}
