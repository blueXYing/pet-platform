import type { IncomingMessage, ServerResponse } from 'node:http';
import { Readable } from 'node:stream';
import { decideProxyOrigin, FOREIGN_FORWARDING_HEADERS } from './proxy-guard';

// Trusted /api forwarder replacing vite's http-proxy wiring. The transport is
// fully under our control so that:
//  - rejections happen BEFORE any upstream request is created (PR60 baseline;
//    the default http-proxy error logging must never see one-time read tokens);
//  - nothing is ever logged — no URLs, headers or bodies, including upstream
//    connection failures (error responses carry no path or token);
//  - foreign forwarding-identity headers are stripped before forwarding.
const HOP_BY_HOP_HEADERS = new Set(['host', 'connection', 'keep-alive', 'transfer-encoding', 'upgrade', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', ...FOREIGN_FORWARDING_HEADERS]);

export type ApiProxyMiddlewareOptions = {
  target: string;
  allowedOrigin: string;
  entryHost: string;
  fetchImpl?: typeof fetch;
};

export function createApiProxyMiddleware(options: ApiProxyMiddlewareOptions) {
  const doFetch = options.fetchImpl ?? fetch;
  return async function apiProxy(req: IncomingMessage, res: ServerResponse, next: (error?: unknown) => void): Promise<void> {
    const path = (req.url ?? '').split('?')[0];
    if (!path.startsWith('/api/')) { next(); return; }
    const decision = decideProxyOrigin({
      method: req.method,
      path,
      origin: req.headers.origin,
      secFetchSite: req.headers['sec-fetch-site'] as string | undefined,
      host: req.headers.host,
      allowedOrigin: options.allowedOrigin,
      entryHost: options.entryHost,
    });
    if (decision.action === 'reject') {
      res.statusCode = 403;
      res.setHeader('Content-Type', 'application/json');
      res.setHeader('Cache-Control', 'no-store');
      res.end(JSON.stringify({ success: false, code: 'PROXY_ORIGIN_REJECTED', message: '来源校验拒绝', data: null, traceId: 'proxy' }));
      return;
    }
    const headers = new Headers();
    for (const [name, value] of Object.entries(req.headers)) {
      if (HOP_BY_HOP_HEADERS.has(name.toLowerCase())) continue;
      if (Array.isArray(value)) for (const item of value) headers.append(name, item);
      else if (value !== undefined) headers.set(name, value);
    }
    if (decision.action === 'inject') headers.set('Origin', decision.origin);
    try {
      const hasBody = req.method !== undefined && req.method !== 'GET' && req.method !== 'HEAD';
      const body = hasBody ? await readRequestBody(req) : undefined;
      const upstream = await doFetch(new URL(req.url!, options.target), {
        method: req.method,
        headers,
        body: body === undefined || body.length === 0 ? undefined : new Uint8Array(body),
        redirect: 'error',
      });
      res.statusCode = upstream.status;
      for (const [name, value] of upstream.headers) {
        if (name === 'set-cookie' || name === 'transfer-encoding' || name === 'content-length' || name === 'connection') continue;
        res.setHeader(name, value);
      }
      const cookies = upstream.headers.getSetCookie();
      if (cookies.length > 0) res.setHeader('Set-Cookie', cookies);
      // No path or token is echoed anywhere on failure paths.
      const payload = Buffer.from(await upstream.arrayBuffer());
      res.setHeader('Content-Length', String(payload.length));
      res.end(payload);
    } catch {
      res.statusCode = 502;
      res.setHeader('Content-Type', 'application/json');
      res.setHeader('Cache-Control', 'no-store');
      res.end(JSON.stringify({ success: false, code: 'PROXY_UPSTREAM_UNAVAILABLE', message: '上游暂不可用', data: null, traceId: 'proxy' }));
    }
  };
}

async function readRequestBody(req: IncomingMessage): Promise<Buffer> {
  const stream = Readable.from(req as unknown as Iterable<unknown>);
  const chunks: Buffer[] = [];
  for await (const chunk of stream) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk as string));
  return Buffer.concat(chunks);
}
