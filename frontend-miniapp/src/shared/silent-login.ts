import { ApiError } from './request'
import type { ConsumerApi } from './consumer-api'

// 用户 2026-10-08 裁决的登录体验层：app 启动静默登录 + 401 自动恢复共用这一状态机。
// 纯依赖注入（api + login provider），不 import Taro/consumer-runtime，node 测试可直接驱动。
export type SilentLoginOutcome = 'authenticated' | 'phone-required' | 'unavailable'

export type SilentLogin = {
  outcome(): SilentLoginOutcome
  /** Single-flight silent login: returns the outcome, never throws (launch/recovery callers). */
  ensure(): Promise<SilentLoginOutcome>
  /** App-launch bootstrap: validate a persisted grant first, silent-login only without one. */
  launch(): Promise<SilentLoginOutcome>
}

export function silentLoginOutcome(api: ConsumerApi): SilentLoginOutcome {
  // A server-verified session implies a bound phone (wechat-login only grants with one), so
  // `authenticated` is the only state that carries the masked phone for the profile card.
  if (api.currentSession) return 'authenticated'
  if (api.authStep === 'phone') return 'phone-required'
  return 'unavailable'
}

export function createSilentLogin(api: ConsumerApi, login: () => Promise<{ code: string }>): SilentLogin {
  let inflight: Promise<SilentLoginOutcome> | null = null
  const ensure = (): Promise<SilentLoginOutcome> => {
    if (silentLoginOutcome(api) === 'authenticated') return Promise.resolve('authenticated')
    if (inflight) return inflight
    const running = (async () => {
      try {
        // startLogin itself is single-flight and refuses while a logout is pending/running —
        // both properties are exactly what silent retries need, so no local guard here.
        await api.startLogin(login)
      } catch {
        // Silent means silent: launch-time and recovery-time failures leave the original
        // error paths (401 guides, page error states) untouched.
      }
      return silentLoginOutcome(api)
    })()
    inflight = running.finally(() => { if (inflight === running) inflight = null })
    return inflight
  }
  return {
    outcome: () => silentLoginOutcome(api),
    ensure,
    async launch() {
      try {
        // Never mint a fresh identity over a stored grant: restore validates it server-side
        // (persisted session is the existing mechanism, pet.c.session.v1) and only a 401
        // says "no valid grant left" → silent login.
        await api.restore()
        return 'authenticated'
      } catch (error) {
        if (error instanceof ApiError && error.statusCode === 401) return ensure()
        // Network/5xx: keep the original behaviour — no session, pages fail through their
        // normal paths; a silent login attempt would hit the same outage anyway.
        return silentLoginOutcome(api)
      }
    },
  }
}
