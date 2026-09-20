import type { PrivateUploadTransport } from './consumer-api'
import { assertApiOrigin } from './api-origin'
export type MultipartSender = (options: { url: string; filePath: string; name: string; formData: Record<string, string>; header: Record<string, string>; timeout: number }) => Promise<{ statusCode: number; data: unknown }>
export function createPrivateUploadTransport(origin: string, send: MultipartSender, allowLocalHttp = false): PrivateUploadTransport {
  assertApiOrigin(origin, allowLocalHttp)
  return input => send({ url: `${origin}/api/v1/c/private-assets`, filePath: input.filePath, name: 'file', formData: { purpose: 'MERCHANT_APPLICATION_MATERIAL' }, header: { Authorization: input.authorization, 'X-Request-Id': input.requestId }, timeout: 60000 })
}
