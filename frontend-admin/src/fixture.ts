import type { Transport } from './request';

// PRIVATE demonstration vocabulary. Never publish as permission codes or public DTOs.
const known = ['sample.read', 'sample.execute'] as const;
export type FixtureAction = typeof known[number];
export type FixtureSession = { label: string; authenticated: boolean; failed?: boolean; superuser?: boolean; grants: FixtureAction[] };
export const profiles: Record<string, FixtureSession> = {
  anonymous: { label: '未登录', authenticated: false, grants: [] },
  denied: { label: '运营管理员（未授予）', authenticated: true, grants: [] },
  reader: { label: '普通账号（仅查看）', authenticated: true, grants: ['sample.read'] },
  editor: { label: '运营编辑（已授予）', authenticated: true, grants: [...known] },
  finance: { label: '财务只读（仅查看）', authenticated: true, grants: ['sample.read'] },
  financeGranted: { label: '财务只读（显式授予动作）', authenticated: true, grants: [...known] },
  superuser: { label: '平台超级管理员', authenticated: true, superuser: true, grants: [] },
  failed: { label: '权限查询失败', authenticated: true, failed: true, grants: [...known] },
};
export function can(session: FixtureSession, action: string) {
  return session.authenticated && !session.failed && known.includes(action as FixtureAction)
    && (session.superuser === true || session.grants.includes(action as FixtureAction));
}
// An injected in-memory transport: this URL is NEVER registered or sent to a server.
export const fixturePath = '/api/v1/admin/__private_fixture__/sample';
export function createFixtureTransport(session: FixtureSession): Transport {
  return async request => {
    if (new URL(request.url).pathname !== fixturePath) return new Response(null, { status: 404 });
    const action = request.method === 'GET' ? 'sample.read' : 'sample.execute';
    const status = !session.authenticated ? 401 : !can(session, action) ? 403 : 200;
    if (request.method !== 'GET' && !request.headers.get('X-Request-Id')) return new Response(null, { status: 400 });
    return Response.json({ success: status === 200, code: status === 200 ? 'SUCCESS' : 'FIXTURE_DENIED', message: 'Private fixture', traceId: 'fixture-only', data: status === 200 ? { id: '2019267812367810561', amount: '128.00', displayStatus: '示例完成', actions: can(session, 'sample.execute') ? ['sample.execute'] : [] } : null }, { status });
  };
}
