import type { Transport } from './request'
import { assertApiOrigin } from './api-origin'
export type PlatformRequest = (options: {
  url: string; method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  data?: Record<string, unknown>; header: Record<string, string>; timeout: number;
}) => Promise<{ statusCode: number; data: unknown }>

export function createHttpAdapter(baseUrl: string, request: PlatformRequest, allowLocalHttp = false): Transport {
  assertApiOrigin(baseUrl, allowLocalHttp)
  return spec => request({ url: baseUrl + spec.path, method: spec.method,
    data: spec.data, header: spec.headers, timeout: 15000 })
}
