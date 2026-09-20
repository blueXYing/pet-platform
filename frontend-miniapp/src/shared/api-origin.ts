/** Explicit development exception only; no DNS resolution, credentials, paths or URL coercion. */
export function assertApiOrigin(origin: string, allowLocalHttp = false) {
  const match = /^(https?):\/\/(\[[0-9a-f:]+\]|[a-zA-Z0-9.-]+)(?::([0-9]{1,5}))?$/.exec(origin)
  if (!match || (match[3] && (+match[3] < 1 || +match[3] > 65535))) throw new Error('HTTPS_ORIGIN_REQUIRED')
  if (match[1] === 'https') return
  if (!allowLocalHttp) throw new Error('HTTPS_ORIGIN_REQUIRED')
  const host = match[2]
  if (host === 'localhost' || host === '[::1]') return
  const parts = host.split('.')
  if (parts.length !== 4 || parts.some(part => !/^(0|[1-9][0-9]{0,2})$/.test(part) || +part > 255)) throw new Error('HTTPS_ORIGIN_REQUIRED')
  const [a, b] = parts.map(Number)
  if (a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168)) return
  throw new Error('HTTPS_ORIGIN_REQUIRED')
}
