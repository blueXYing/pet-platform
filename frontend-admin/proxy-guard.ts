// Controlled source-verification rules for the trusted /api reverse proxy,
// implementing the baseline from planning/issues/wave-2/A-002-contract-review/
// DECISIONS-AND-HANDOFF.md. Pure decision logic, unit-tested in
// tests/proxy-guard.spec.ts; the browser cannot set Origin itself (forbidden
// header), so end-to-end injection is verified only in real integration.
//
//  1. A request that already carries an Origin must carry the ONE allowed
//     value; anything else (including "null") is rejected, never rewritten.
//  2. Origin is injected ONLY for the explicitly listed attempt-bound GETs
//     (auth requirements/result) that lack one, and only after verifying the
//     same-origin browser context: Sec-Fetch-Site=same-origin AND a Host
//     matching the public entry; anything else fails closed.
//  3. All other requests are forwarded as received — no blanket rewrite.

export const FOREIGN_FORWARDING_HEADERS = ['x-forwarded-for', 'x-forwarded-host', 'x-forwarded-proto', 'x-forwarded-server', 'forwarded'] as const;

const ATTEMPT_BOUND_GET_PATHS = [/^\/api\/v1\/admin\/auth\/attempts\/[1-9][0-9]{0,18}\/(requirements|result)$/];

export type ProxyOriginDecision =
  | { action: 'forward' }
  | { action: 'reject' }
  | { action: 'inject'; origin: string };

export function decideProxyOrigin(input: {
  method?: string;
  path: string;
  origin?: string;
  secFetchSite?: string;
  host?: string;
  allowedOrigin: string;
  entryHost: string;
}): ProxyOriginDecision {
  if (input.origin !== undefined && input.origin !== null) {
    return input.origin === input.allowedOrigin ? { action: 'forward' } : { action: 'reject' };
  }
  const attemptBoundGet = input.method === 'GET' && ATTEMPT_BOUND_GET_PATHS.some(pattern => pattern.test(input.path));
  if (!attemptBoundGet) return { action: 'forward' };
  const sameOriginBrowser = input.secFetchSite === 'same-origin' && input.host === input.entryHost;
  return sameOriginBrowser ? { action: 'inject', origin: input.allowedOrigin } : { action: 'reject' };
}
