import { expect, test } from '@playwright/test';
import { decideProxyOrigin } from '../proxy-guard';

// Unit coverage for the trusted-proxy source-verification rules (PR60 baseline).
// The browser cannot set Origin manually, so rejection/injection behavior is
// verified at the decision layer here; end-to-end injection is a real-integration
// acceptance item (HANDOFF), not claimed from these tests.

const base = { allowedOrigin: 'https://ops.example.com', entryHost: 'ops.example.com' };
const atEntry = { host: 'ops.example.com' };

test('entry Host is verified first, for every request including the allowed-Origin branch', () => {
  const allowedOrigin = { ...base, method: 'POST', path: '/api/v1/admin/auth/login', origin: 'https://ops.example.com' };
  expect(decideProxyOrigin({ ...allowedOrigin, host: 'ops.example.com' })).toEqual({ action: 'forward' });
  // An allowed Origin must not bypass the entry Host check.
  expect(decideProxyOrigin({ ...allowedOrigin, host: 'localhost:7777' })).toEqual({ action: 'reject' });
  expect(decideProxyOrigin({ ...allowedOrigin, host: undefined })).toEqual({ action: 'reject' });
  // Host gate applies to plain business GETs and to the inject allowlist alike.
  expect(decideProxyOrigin({ ...base, method: 'GET', path: '/api/v1/admin/merchant-applications', host: 'localhost:7777' })).toEqual({ action: 'reject' });
  expect(decideProxyOrigin({ ...base, method: 'GET', path: '/api/v1/admin/auth/attempts/801/requirements', secFetchSite: 'same-origin', host: 'localhost:7777' })).toEqual({ action: 'reject' });
});

test('disallowed existing origin is rejected, never rewritten', () => {
  for (const origin of ['https://evil.example.com', 'null', '']) {
    expect(decideProxyOrigin({ ...base, method: 'POST', path: '/api/v1/admin/auth/login', origin, ...atEntry })).toEqual({ action: 'reject' });
  }
});

test('attempt-bound GET without origin injects only for a verified same-origin browser context', () => {
  const input = { ...base, method: 'GET' as const, path: '/api/v1/admin/auth/attempts/801/requirements', ...atEntry };
  expect(decideProxyOrigin({ ...input, secFetchSite: 'same-origin' })).toEqual({ action: 'inject', origin: 'https://ops.example.com' });
  expect(decideProxyOrigin({ ...input, secFetchSite: 'same-origin', path: '/api/v1/admin/auth/attempts/801/result' })).toEqual({ action: 'inject', origin: 'https://ops.example.com' });
});

test('attempt-bound GET without origin fails closed outside a verified context', () => {
  const path = '/api/v1/admin/auth/attempts/801/requirements';
  expect(decideProxyOrigin({ ...base, method: 'GET', path, secFetchSite: 'cross-site', ...atEntry })).toEqual({ action: 'reject' });
  expect(decideProxyOrigin({ ...base, method: 'GET', path, secFetchSite: 'same-site', ...atEntry })).toEqual({ action: 'reject' });
  expect(decideProxyOrigin({ ...base, method: 'GET', path, ...atEntry })).toEqual({ action: 'reject' });
});

test('paths outside the attempt-bound allowlist are never injected', () => {
  const context = { ...base, ...atEntry, secFetchSite: 'same-origin', origin: undefined } as const;
  expect(decideProxyOrigin({ ...context, method: 'GET', path: '/api/v1/admin/merchant-applications' })).toEqual({ action: 'forward' });
  expect(decideProxyOrigin({ ...context, method: 'GET', path: '/api/v1/admin/private-asset-read-grants/token' })).toEqual({ action: 'forward' });
  expect(decideProxyOrigin({ ...context, method: 'GET', path: '/api/v1/admin/auth/attempts/08/requirements' })).toEqual({ action: 'forward' });
  expect(decideProxyOrigin({ ...context, method: 'GET', path: '/api/v1/admin/auth/session' })).toEqual({ action: 'forward' });
  expect(decideProxyOrigin({ ...context, method: 'POST', path: '/api/v1/admin/auth/attempts' })).toEqual({ action: 'forward' });
});
