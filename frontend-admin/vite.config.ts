import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import type { ClientRequest, IncomingMessage, ServerResponse } from 'node:http';
import { decideProxyOrigin, FOREIGN_FORWARDING_HEADERS } from './proxy-guard';

// Trusted reverse proxy implementing the controlled source-verification baseline
// from planning/issues/wave-2/A-002-contract-review/DECISIONS-AND-HANDOFF.md.
// The backend keeps every check (attemptToken, __Host cookie, Bearer, exact
// Origin match on attempt-bound calls); this proxy only adds origin information
// a same-origin browser GET cannot carry itself (Origin is a forbidden header).
//
// Explicit configuration only — no target/origin defaults; unset or partial
// configuration disables the proxy instead of forwarding to an unknown service.
// Foreign forwarding-identity headers are stripped, none are added. No header
// or body logging (credentials and one-time read tokens stay private).
// Dev/preview wiring ≠ production entry deployed or accepted (HANDOFF).
const proxyTarget = process.env.ADMIN_API_PROXY_TARGET;
const proxyOrigin = process.env.ADMIN_API_PROXY_ORIGIN;
if ((proxyTarget === undefined) !== (proxyOrigin === undefined)) {
  throw new Error('ADMIN_API_PROXY_TARGET and ADMIN_API_PROXY_ORIGIN must be configured together');
}
const entryHost = proxyOrigin === undefined ? undefined : new URL(proxyOrigin).host;
const rejectOrigin = (res: ServerResponse) => {
  res.statusCode = 403;
  res.setHeader('Content-Type', 'application/json');
  res.setHeader('Cache-Control', 'no-store');
  res.end(JSON.stringify({ success: false, code: 'PROXY_ORIGIN_REJECTED', message: '来源校验拒绝', data: null, traceId: 'proxy' }));
};

export default defineConfig({
  plugins: [react()],
  ...(proxyTarget === undefined || entryHost === undefined ? {} : {
    server: { proxy: { '/api': { target: proxyTarget, configure: configureProxy } } },
    preview: { proxy: { '/api': { target: proxyTarget, configure: configureProxy } } },
  }),
});

// http-proxy ships without type declarations; wire the event structurally.
type ProxyServerLike = {
  on(event: 'proxyReq', listener: (proxyReq: ClientRequest, incoming: IncomingMessage, res: ServerResponse) => void): unknown;
};

function configureProxy(proxy: ProxyServerLike) {
  proxy.on('proxyReq', (proxyReq, incoming, res) => {
    for (const header of FOREIGN_FORWARDING_HEADERS) proxyReq.removeHeader(header);
    const decision = decideProxyOrigin({
      method: incoming.method,
      path: (incoming.url ?? '').split('?')[0]!,
      origin: incoming.headers.origin,
      secFetchSite: incoming.headers['sec-fetch-site'] as string | undefined,
      host: incoming.headers.host,
      allowedOrigin: proxyOrigin!,
      entryHost: entryHost!,
    });
    if (decision.action === 'reject') { rejectOrigin(res); proxyReq.destroy(); return; }
    if (decision.action === 'inject') proxyReq.setHeader('Origin', decision.origin);
    // 'forward': passed through untouched — the backend's own checks decide.
  });
}
