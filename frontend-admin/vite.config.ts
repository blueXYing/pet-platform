import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Trusted reverse proxy for real-backend integration. Same-origin browser fetch
// sends no Origin header on GET, while AdminAuthController requires an exact
// Origin match on attempt-bound calls (403 otherwise) — the browser cannot set
// Origin itself (forbidden header). This proxy sits inside the deployment trust
// boundary and injects the single configured Origin so the backend check still
// runs unchanged (CONTRACT.md §4.2; production needs the same trusted-proxy or a
// backend CCR on GET source verification).
const proxyTarget = process.env.ADMIN_API_PROXY_TARGET ?? 'http://127.0.0.1:8080';
const proxyOrigin = process.env.ADMIN_API_PROXY_ORIGIN ?? 'http://127.0.0.1:4173';
const apiProxy = {
  '/api': { target: proxyTarget, changeOrigin: false, headers: { Origin: proxyOrigin } },
};

export default defineConfig({
  plugins: [react()],
  server: { proxy: apiProxy },
  preview: { proxy: apiProxy },
});
