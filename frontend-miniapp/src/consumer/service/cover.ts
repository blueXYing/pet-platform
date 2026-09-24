import type { ServiceCoverView } from './model'

/** Never synthesize a public object URL or keep using an expired signature. */
export function usableCoverUrl(cover: ServiceCoverView | null, now = Date.now()): string | null {
  if (!cover || !/^https:\/\/[^/\s?#]+(?:\/|$)/.test(cover.coverUrl)) return null
  const expiry = Date.parse(cover.coverUrlExpiresAt)
  return Number.isFinite(expiry) && expiry > now ? cover.coverUrl : null
}
