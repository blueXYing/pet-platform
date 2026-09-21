import { expect, test } from '@playwright/test';
import { decideProxyOrigin } from '../proxy-guard';

// Unit coverage for the trusted-proxy source-verification rules (PR60 baseline).
// The browser cannot set Origin manually, so rejection/injection behavior is
// verified at the decision layer here; end-to-end injection is a real-integration
// acceptance item (HANDOFF), not claimed from these tests.

const base = { allowedOrigin: 'https://ops.example.com', entryHost: 'ops.example.com' };

test('existing origin equal to the configured value is forwarded untouched', () => {
  expect(decideProxyOrigin({ ...base, method: 'POST', path: '/api/v1/admin/auth/login', origin: 'https://ops.example.com' })).toEqual({ action: 'forward' });
});

test('disallowed existing origin is rejected, never rewritten', () => {
  for (const origin of ['https://evil.example.com', 'null', '']) {
    expect(decideProxyOrigin({ ...base, method: 'POST', path: '/api/v1/admin/auth/login', origin })).toEqual({ action: 'reject' });
  }
});

test('attempt-bound GET without origin injects only for a verified same-origin browser context', () => {
  const input = { ...base, method: 'GET' as const, path: '/api/v1/admin/auth/attempts/801/requirements' };
  expect(decideProxyOrigin({ ...input, secFetchSite: 'same-origin', host: 'ops.example.com' })).toEqual({ action: 'inject', origin: 'https://ops.example.com' });
  expect(decideProxyOrigin({ ...input, secFetchSite: 'same-origin', host: 'ops.example.com', path: '/api/v1/admin/auth/attempts/801/result' })).toEqual({ action: 'inject', origin: 'https://ops.example.com' });
});

test('attempt-bound GET without origin fails closed outside a verified context', () => {
  const path = '/api/v1/admin/auth/attempts/801/requirements';
  expect(decideProxyOrigin({ ...base, method: 'GET', path, secFetchSite: 'cross-site', host: 'ops.example.com' })).toEqual({ action: 'reject' });
  expect(decideProxyOrigin({ ...base, method: 'GET', path, secFetchSite: 'same-origin', host: 'other.example.com' })).toEqual({ action: 'reject' });
  expect(decideProxyOrigin({ ...base, method: 'GET', path, host: 'ops.example.com' })).toEqual({ action: 'reject' });
  expect(decideProxyOrigin({ ...base, method: 'GET', path, secFetchSite: 'same-site', host: 'ops.example.com' })).toEqual({ action: 'reject' });
});

test('paths outside the attempt-bound allowlist are never injected', () => {
  const context = { ...base, secFetchSite: 'same-origin', host: 'ops.example.com', origin: undefined } as const;
  expect(decideProxyOrigin({ ...context, method: 'GET', path: '/api/v1/admin/merchant-applications' })).toEqual({ action: 'forward' });
  expect(decideProxyOrigin({ ...context, method: 'GET', path: '/api/v1/admin/private-asset-read-grants/token' })).toEqual({ action: 'forward' });
  expect(decideProxyOrigin({ ...context, method: 'GET', path: '/api/v1/admin/auth/attempts/08/requirements' })).toEqual({ action: 'forward' });
  expect(decideProxyOrigin({ ...context, method: 'GET', path: '/api/v1/admin/auth/session' })).toEqual({ action: 'forward' });
  expect(decideProxyOrigin({ ...context, method: 'POST', path: '/api/v1/admin/auth/attempts' })).toEqual({ action: 'forward' });
});
