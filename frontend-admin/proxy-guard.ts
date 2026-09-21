// Controlled source-verification rules for the trusted /api reverse proxy,
// implementing the baseline from planning/issues/wave-2/A-002-contract-review/
// DECISIONS-AND-HANDOFF.md. Pure decision logic, unit-tested in
// tests/proxy-guard.spec.ts; the browser cannot set Origin itself (forbidden
// header), so end-to-end injection is verified only in real integration.
//
//  1. The public entry Host must match the configured entry host EXACTLY for
//     every request — checked before any forwarding or injection decision.
//  2. A request that already carries an Origin must carry the ONE allowed
//     value; anything else (including "null") is rejected, never rewritten.
//  3. Origin is injected ONLY for the explicitly listed attempt-bound GETs
//     (auth requirements/result) that lack one, and only after verifying the
//     same-origin browser context (Sec-Fetch-Site=same-origin); anything else
//     fails closed.
//  4. All other requests are forwarded as received — no blanket rewrite.

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
  // Rule 1 first: the entry Host is verified for every request, including the
  // allowed-Origin branch — an allowed Origin must not bypass the entry check.
  if (input.host !== input.entryHost) return { action: 'reject' };
  if (input.origin !== undefined && input.origin !== null) {
    return input.origin === input.allowedOrigin ? { action: 'forward' } : { action: 'reject' };
  }
  const attemptBoundGet = input.method === 'GET' && ATTEMPT_BOUND_GET_PATHS.some(pattern => pattern.test(input.path));
  if (!attemptBoundGet) return { action: 'forward' };
  return input.secFetchSite === 'same-origin' ? { action: 'inject', origin: input.allowedOrigin } : { action: 'reject' };
}
