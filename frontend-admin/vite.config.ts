import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { createApiProxyMiddleware } from './api-proxy-middleware';

// Trusted reverse proxy for real-backend integration, implementing the
// controlled source-verification baseline from planning/issues/wave-2/
// A-002-contract-review/DECISIONS-AND-HANDOFF.md. Decisions live in
// proxy-guard.ts (unit-tested); transport lives in api-proxy-middleware.ts:
// rejections happen before any upstream request exists and nothing — URLs,
// headers, bodies, one-time read tokens included — is ever logged.
//
// Explicit configuration only — no target/origin defaults; unset or partial
// configuration disables the proxy instead of forwarding to an unknown service.
// Dev/preview wiring ≠ production entry deployed or accepted (HANDOFF).
const proxyTarget = process.env.ADMIN_API_PROXY_TARGET;
const proxyOrigin = process.env.ADMIN_API_PROXY_ORIGIN;
if ((proxyTarget === undefined) !== (proxyOrigin === undefined)) {
  throw new Error('ADMIN_API_PROXY_TARGET and ADMIN_API_PROXY_ORIGIN must be configured together');
}
const entryHost = proxyOrigin === undefined ? undefined : new URL(proxyOrigin).host;

const proxyPlugin = proxyTarget === undefined || entryHost === undefined ? undefined : {
  name: 'controlled-api-proxy',
  configureServer(server: import('vite').ViteDevServer) {
    server.middlewares.use(createApiProxyMiddleware({ target: proxyTarget, allowedOrigin: proxyOrigin!, entryHost: entryHost! }));
  },
  configurePreviewServer(server: import('vite').PreviewServer) {
    server.middlewares.use(createApiProxyMiddleware({ target: proxyTarget, allowedOrigin: proxyOrigin!, entryHost: entryHost! }));
  },
};

export default defineConfig({
  plugins: [react(), ...(proxyPlugin ? [proxyPlugin] : [])],
});
