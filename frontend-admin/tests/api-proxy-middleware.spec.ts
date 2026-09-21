import { expect, test } from '@playwright/test';
import { Readable } from 'node:stream';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { createApiProxyMiddleware } from '../api-proxy-middleware';

// Wiring tests for the trusted /api forwarder (PR60 baseline): rejections must
// happen before any upstream request is created, one-time read tokens must never
// reach any log output, foreign forwarding headers must be stripped, and the
// allowlisted attempt-bound GETs must receive the injected Origin.

const TOKEN_PATH = '/api/v1/admin/private-asset-read-grants/tok_SECRETNEVERSEEN';

function mockRequest(options: { method: string; url: string; headers: Record<string, string>; body?: Buffer }) {
  const stream = Readable.from(options.body ? [options.body] : []) as unknown as IncomingMessage;
  stream.method = options.method;
  stream.url = options.url;
  stream.headers = options.headers;
  return stream;
}

function mockResponse() {
  const res = {
    statusCode: 200,
    headers: {} as Record<string, string | string[]>,
    chunks: [] as Buffer[],
    setHeader(name: string, value: string | string[]) { this.headers[name.toLowerCase()] = value; },
    end(payload?: Buffer | string) { if (payload !== undefined) this.chunks.push(Buffer.isBuffer(payload) ? payload : Buffer.from(payload)); },
  };
  return res as unknown as ServerResponse & typeof res;
}

function hookConsole() {
  const captured: string[] = [];
  const original = { log: console.log, error: console.error, warn: console.warn, info: console.info };
  for (const key of Object.keys(original) as (keyof typeof original)[]) {
    console[key] = (...args: unknown[]) => { captured.push(args.map(String).join(' ')); };
  }
  return () => { for (const key of Object.keys(original) as (keyof typeof original)[]) console[key] = original[key]; return captured; };
}

const options = { target: 'http://192.168.1.44:18082', allowedOrigin: 'http://127.0.0.1:18082', entryHost: '127.0.0.1:18082' };

test('rejection happens before any upstream request and never logs the token path', async () => {
  const fetchCalls: unknown[] = [];
  const fetchImpl = (async (...args: unknown[]) => { fetchCalls.push(args); throw new Error('must not be called'); }) as unknown as typeof fetch;
  const middleware = createApiProxyMiddleware({ ...options, fetchImpl });
  const restore = hookConsole();
  try {
    // Allowed Origin but wrong entry Host — must reject.
    const res1 = mockResponse();
    await middleware(mockRequest({ method: 'GET', url: TOKEN_PATH, headers: { host: 'localhost:7777', origin: options.allowedOrigin } }), res1, () => { throw new Error('next must not run'); });
    expect(res1.statusCode).toBe(403);
    // Disallowed Origin at the right Host — must reject.
    const res2 = mockResponse();
    await middleware(mockRequest({ method: 'POST', url: '/api/v1/admin/auth/login', headers: { host: options.entryHost, origin: 'https://evil.example.com' } }), res2, () => { throw new Error('next must not run'); });
    expect(res2.statusCode).toBe(403);
  } finally {
    const logged = restore();
    expect(fetchCalls).toHaveLength(0);
    for (const line of logged) expect(line.includes('tok_SECRETNEVERSEEN')).toBe(false);
  }
});

test('forwarding preserves headers and body, strips foreign forwarding headers, copies set-cookie', async () => {
  let seen: { url?: string | URL; init?: RequestInit } = {};
  const fetchImpl = (async (url: URL | string, init: RequestInit) => {
    seen = { url, init };
    return new Response('{"success":true}', {
      status: 200,
      headers: { 'Content-Type': 'application/json', 'Set-Cookie': '__Host-pet-admin-attempt=abc; Path=/; Secure; HttpOnly; SameSite=Strict' },
    });
  }) as unknown as typeof fetch;
  const middleware = createApiProxyMiddleware({ ...options, fetchImpl });
  const res = mockResponse();
  await middleware(
    mockRequest({
      method: 'POST',
      url: '/api/v1/admin/auth/login',
      headers: { host: options.entryHost, origin: options.allowedOrigin, 'content-type': 'application/json', 'x-forwarded-for': '1.2.3.4', 'x-request-id': 'rq-1' },
      body: Buffer.from('{}'),
    }),
    res,
    () => { throw new Error('next must not run'); },
  );
  expect(String(seen.url)).toBe('http://192.168.1.44:18082/api/v1/admin/auth/login');
  expect(seen.init?.method).toBe('POST');
  const headers = new Headers(seen.init?.headers);
  expect(headers.get('origin')).toBe(options.allowedOrigin);
  expect(headers.get('x-request-id')).toBe('rq-1');
  expect(headers.get('x-forwarded-for')).toBeNull();
  expect(res.statusCode).toBe(200);
  expect(res.headers['set-cookie']).toEqual(['__Host-pet-admin-attempt=abc; Path=/; Secure; HttpOnly; SameSite=Strict']);
  expect(Buffer.concat((res as unknown as { chunks: Buffer[] }).chunks).toString()).toBe('{"success":true}');
});

test('allowlisted attempt-bound GET gets the injected Origin; upstream failures return sanitized 502', async () => {
  const calls: { url?: string | URL; init?: RequestInit }[] = [];
  const fetchImpl = (async (url: URL | string, init: RequestInit) => {
    calls.push({ url, init });
    if (calls.length === 1) return new Response('{}', { status: 200 });
    throw new Error('connection refused');
  }) as unknown as typeof fetch;
  const middleware = createApiProxyMiddleware({ ...options, fetchImpl });

  const res1 = mockResponse();
  await middleware(
    mockRequest({ method: 'GET', url: '/api/v1/admin/auth/attempts/801/requirements', headers: { host: options.entryHost, 'sec-fetch-site': 'same-origin' } }),
    res1,
    () => { throw new Error('next must not run'); },
  );
  expect(res1.statusCode).toBe(200);
  expect(new Headers(calls[0].init?.headers).get('origin')).toBe(options.allowedOrigin);

  const restore = hookConsole();
  let res2: ServerResponse & { chunks: Buffer[] };
  try {
    res2 = mockResponse() as ServerResponse & { chunks: Buffer[] };
    await middleware(
      mockRequest({ method: 'GET', url: TOKEN_PATH, headers: { host: options.entryHost, origin: options.allowedOrigin, authorization: 'Bearer t' } }),
      res2,
      () => { throw new Error('next must not run'); },
    );
  } finally {
    const logged = restore();
    for (const line of logged) expect(line.includes('tok_SECRETNEVERSEEN')).toBe(false);
  }
  expect(res2!.statusCode).toBe(502);
  expect(Buffer.concat(res2!.chunks).toString()).not.toContain('tok_SECRETNEVERSEEN');
});
