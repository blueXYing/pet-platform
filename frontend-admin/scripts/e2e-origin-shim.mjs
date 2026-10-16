// QA-only loopback shim for the admin main-chain live walkthrough (2026-10-16):
// the E2eCoreLoopServer fixes pet.auth.admin.origin=https://e2e.example.invalid while the
// vite preview proxy verifies the browser-facing origin (http://127.0.0.1:<port>) — the two
// source-verification baselines cannot meet without a translating hop. This shim listens on
// E2E_SHIM_PORT (default 18083), rewrites the Origin header to E2E_SHIM_ORIGIN and forwards
// everything else byte-for-byte to E2E_SHIM_TARGET (default http://127.0.0.1:18081),
// including multi-value Set-Cookie. Nothing is logged. Test infrastructure only: production
// traffic never rides this path and the shim is not part of any deployable.
import http from 'node:http';

const listenPort = Number(process.env.E2E_SHIM_PORT ?? 18083);
const origin = process.env.E2E_SHIM_ORIGIN ?? 'https://e2e.example.invalid';
const target = new URL(process.env.E2E_SHIM_TARGET ?? 'http://127.0.0.1:18081');

http.createServer(async (req, res) => {
  const headers = { ...req.headers };
  if (headers.origin !== undefined) headers.origin = origin;
  const upstream = http.request({
    hostname: target.hostname,
    port: target.port,
    path: req.url,
    method: req.method,
    headers,
  }, upstreamRes => {
    res.writeHead(upstreamRes.statusCode ?? 502, upstreamRes.headers);
    upstreamRes.pipe(res);
  });
  upstream.on('error', () => {
    if (!res.headersSent) {
      res.writeHead(502, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    }
    res.end(JSON.stringify({ success: false, code: 'E2E_SHIM_UNAVAILABLE', data: null }));
  });
  req.pipe(upstream);
}).listen(listenPort, '127.0.0.1', () => {
  console.log(`e2e-origin-shim listening on 127.0.0.1:${listenPort} -> ${target} (Origin=${origin})`);
});
