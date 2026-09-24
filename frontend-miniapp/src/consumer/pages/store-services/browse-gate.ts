import type { Workspace } from '../../../shared/workspace'

/**
 * STR-D8 detail-page gate (2026-09-23 window-acceptance adjudication): browsing — including
 * the store detail and service detail pages — is anonymous; login only gates ACTIONS such as
 * booking (“所有用户浏览；已登录用户可进入预约”). These pure decisions are shared by both
 * store-services detail pages so the rule is testable without a Taro render harness.
 */

/**
 * Reading is never blocked on login in real mode; only the preview design-cut keeps its
 * expired scenario (a fixture state, not a product rule).
 */
export function detailReadAllowed(preview: boolean, scenario: string): boolean {
  return !(preview && scenario === 'expired')
}

/** The action gate for 预约/拨打电话: a consumer session releases the page notice path. */
export type DetailActionGate = 'login-required' | 'consumer-ready'
export function detailActionGate(context: Workspace | null | undefined): DetailActionGate {
  return context?.workspace === 'consumer' ? 'consumer-ready' : 'login-required'
}
