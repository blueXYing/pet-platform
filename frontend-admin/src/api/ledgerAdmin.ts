import { RequestFailure, type Transport } from '../request';

// Contract-65 ledger + settings faces (A-005): the PRD 6.5 points ledger read view and the
// read-only runtime switch view (21号 deployment switch registry). Both are read-only by
// contract — no write method exists in this client.
export const BIZ_TYPES = ['SIGN_IN', 'INVITE', 'TASK', 'ORDER_REWARD', 'REFUND_CLAWBACK'] as const;
export type BizType = typeof BIZ_TYPES[number];
export const BIZ_TYPE_LABELS: Record<BizType, string> = {
  SIGN_IN: '签到', INVITE: '邀请', TASK: '任务',
  ORDER_REWARD: '订单奖励', REFUND_CLAWBACK: '退款扣回',
};

export class LedgerFailure extends RequestFailure {
  constructor(code: string, status: number) { super(code, status); }
}

const UTC = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})(?![\s\S])/;
const DECIMAL_ID = /^[1-9][0-9]{0,18}(?![\s\S])/;
const SIGNED_INT = /^-[1-9][0-9]{0,17}(?![\s\S])|^[1-9][0-9]{0,17}(?![\s\S])/;
const UNSIGNED_INT = /^[0-9]{1,19}(?![\s\S])/;

export type PointsLedgerRow = {
  ledgerId: string; userId: string; bizType: BizType; delta: string;
  balanceAfter: string; orderId: string | null; createdAt: string;
};
export type Page<T> = { page: number; pageSize: number; total: number; items: T[] };
export type RuntimeSettings = {
  switches: { key: string; enabled: boolean; phase: string }[];
  readOnly: boolean;
};

const isPage = (value: unknown): value is Page<PointsLedgerRow> => {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  if (typeof v.page !== 'number' || typeof v.pageSize !== 'number'
    || typeof v.total !== 'number' || !Array.isArray(v.items)) return false;
  return (v.items as unknown[]).every(item => {
    const r = item as Record<string, unknown>;
    return typeof r.ledgerId === 'string' && DECIMAL_ID.test(r.ledgerId)
      && typeof r.userId === 'string' && DECIMAL_ID.test(r.userId)
      && BIZ_TYPES.includes(r.bizType as BizType)
      && typeof r.delta === 'string' && SIGNED_INT.test(r.delta)
      && typeof r.balanceAfter === 'string' && UNSIGNED_INT.test(r.balanceAfter)
      && (r.orderId === null || typeof r.orderId === 'string' && DECIMAL_ID.test(r.orderId))
      && typeof r.createdAt === 'string' && UTC.test(r.createdAt);
  });
};

const isSettings = (value: unknown): value is RuntimeSettings => {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  if (v.readOnly !== true || !Array.isArray(v.switches)) return false;
  return (v.switches as unknown[]).every(item => {
    const r = item as Record<string, unknown>;
    return typeof r.key === 'string' && r.key.startsWith('pet.')
      && typeof r.enabled === 'boolean' && typeof r.phase === 'string';
  });
};

export function createLedgerAdminClient(transport: Transport = fetch) {
  let epoch = 0;
  let token: string | undefined;
  const failStale = (started: number) => { if (started !== epoch) throw new LedgerFailure('STALE_CONTEXT', 0); };

  async function envelope<T>(path: string, shape: (v: unknown) => v is T): Promise<T> {
    if (!token) throw new LedgerFailure('UNAUTHENTICATED', 401);
    const started = epoch;
    const res = await transport(new Request(new URL(path, window.location.origin), {
      method: 'GET',
      headers: new Headers({ Accept: 'application/json', Authorization: `Bearer ${token}` }),
      credentials: 'omit', redirect: 'error', cache: 'no-store',
    }));
    failStale(started);
    if (res.status === 401 || res.status === 403) {
      epoch++; token = undefined;
      throw new LedgerFailure(res.status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', res.status);
    }
    let payload: unknown;
    try { payload = await res.json(); } catch { throw new LedgerFailure('INVALID_RESPONSE', res.status); }
    failStale(started);
    const body = payload as Record<string, unknown>;
    if (typeof body?.code !== 'string') throw new LedgerFailure('INVALID_RESPONSE', res.status);
    if (!res.ok || body.code !== 'SUCCESS') throw new LedgerFailure(body.code, res.status);
    if (!shape(body.data)) throw new LedgerFailure('INVALID_RESPONSE', 200);
    return body.data;
  }

  return {
    resetContext(nextToken?: string) { epoch++; token = nextToken; },

    async listPointsLedger(filters: { userId?: string; bizType?: BizType } = {}, page = 1, pageSize = 20) {
      const query = new URLSearchParams();
      if (filters.userId) query.set('userId', filters.userId);
      if (filters.bizType) query.set('bizType', filters.bizType);
      query.set('page', String(page));
      query.set('pageSize', String(pageSize));
      return envelope<Page<PointsLedgerRow>>(`/api/v1/admin/points/ledger?${query.toString()}`, isPage);
    },

    async runtimeSettings() {
      return envelope<RuntimeSettings>('/api/v1/admin/settings/runtime', isSettings);
    },
  };
}
export type LedgerAdminClient = ReturnType<typeof createLedgerAdminClient>;
