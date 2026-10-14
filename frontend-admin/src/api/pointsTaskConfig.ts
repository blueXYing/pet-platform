import { RequestFailure, type Transport } from '../request';

// Contract58 (32号 ruling second batch): the ops-configured earn amounts for sign-in and
// referral. V1 points only accrue — this page can only tune grant amounts, never open a
// spend/exchange surface. Full-PUT semantics with supplement-23 idempotency: the same
// X-Request-Id replays the same reply; a different payload under the same id is 409.
export type PointsTaskConfig = {
  signInDailyPoints: string;
  signInStreakBonusPoints: string;
  invitePerInviteePoints: string;
  inviteMonthlyRewardCap: string;
  inviteMilestoneBonusPoints: string;
  firstOrderPoints: string;
  profileCompletePoints: string;
  version: string;
  updatedAt: string | null;
};
export type PointsTaskConfigDraft = Omit<PointsTaskConfig, 'version' | 'updatedAt'>;

export class PointsTaskConfigFailure extends RequestFailure {
  constructor(code: string, status: number) { super(code, status); }
}

const ROOT = '/api/v1/admin/points-task-config';
const UTC_MILLI = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z(?![\s\S])/;
const DECIMAL_ID = /^(0|[1-9][0-9]{0,18})(?![\s\S])/;

/** Contract 58 §4 bounds; the kernel re-validates, the form mirrors them for early 400s. */
export const CONFIG_FIELDS = [
  { key: 'signInDailyPoints', label: '每日签到积分', min: 1, max: 1000, hint: '每次签到固定发放（无连签递增曲线）' },
  { key: 'signInStreakBonusPoints', label: '连签7天里程碑积分', min: 0, max: 1000, hint: '一次性里程碑（当前连签 ≥7 天可领）' },
  { key: 'invitePerInviteePoints', label: '每邀请 1 人积分', min: 0, max: 1000, hint: '被邀请人绑定后完成首笔支付的人头奖励' },
  { key: 'inviteMonthlyRewardCap', label: '拉新月上限（人）', min: 0, max: 100, hint: '0 = 关闭人头奖励（里程碑独立计量）' },
  { key: 'inviteMilestoneBonusPoints', label: '邀请 3 人里程碑积分', min: 0, max: 1000, hint: '按已领取人头数 ≥3 计量，终身一次' },
  { key: 'firstOrderPoints', label: '首次下单任务积分', min: 0, max: 1000, hint: '本人首笔成功支付后可领取，终身一次' },
  { key: 'profileCompletePoints', label: '完善资料任务积分', min: 0, max: 1000, hint: '昵称非空即可领取（内部 API 无头像字段），终身一次' },
] as const;
export type ConfigFieldKey = typeof CONFIG_FIELDS[number]['key'];

function isConfigShape(value: unknown): value is PointsTaskConfig {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  const v = value as Record<string, unknown>;
  const keys = CONFIG_FIELDS.map(field => field.key);
  if (Object.keys(v).length !== keys.length + 2) return false;
  if (!keys.every(key => typeof v[key] === 'string' && DECIMAL_ID.test(v[key] as string))) return false;
  if (typeof v.version !== 'string' || !DECIMAL_ID.test(v.version)) return false;
  return v.updatedAt === null || typeof v.updatedAt === 'string' && UTC_MILLI.test(v.updatedAt);
}

export function draftFrom(config: PointsTaskConfig): PointsTaskConfigDraft {
  const { version: _version, updatedAt: _updatedAt, ...draft } = config;
  return draft;
}

export function parseDraft(draft: PointsTaskConfigDraft): Record<ConfigFieldKey, number> {
  const parsed = {} as Record<ConfigFieldKey, number>;
  for (const field of CONFIG_FIELDS) {
    const raw = draft[field.key];
    if (!/^-?[0-9]{1,4}(?![\s\S])/.test(raw)) throw new PointsTaskConfigFailure('INVALID_ARGUMENT', 0);
    const value = Number(raw);
    if (!Number.isSafeInteger(value) || value < field.min || value > field.max) {
      throw new PointsTaskConfigFailure('INVALID_ARGUMENT', 0);
    }
    parsed[field.key] = value;
  }
  return parsed;
}

export function createPointsTaskConfigClient(transport: Transport = fetch) {
  let epoch = 0;
  let token: string | undefined;
  const failStale = (started: number) => { if (started !== epoch) throw new PointsTaskConfigFailure('STALE_CONTEXT', 0); };
  async function envelope(path: string, options: { body?: unknown; requestId?: string } = {}) {
    if (!token) throw new PointsTaskConfigFailure('UNAUTHENTICATED', 401);
    const started = epoch;
    const headers = new Headers({ Accept: 'application/json', Authorization: `Bearer ${token}` });
    if (options.requestId) { headers.set('X-Request-Id', options.requestId); headers.set('Content-Type', 'application/json'); }
    const res = await transport(new Request(new URL(path, window.location.origin), {
      method: options.requestId ? 'PUT' : 'GET',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      headers, credentials: 'omit', redirect: 'error', cache: 'no-store',
    }));
    failStale(started);
    if (res.status === 401 || res.status === 403) {
      epoch++; token = undefined;
      throw new PointsTaskConfigFailure(res.status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN', res.status);
    }
    let payload: unknown;
    try { payload = await res.json(); } catch { throw new PointsTaskConfigFailure('INVALID_RESPONSE', res.status); }
    failStale(started);
    const body = payload as Record<string, unknown>;
    if (typeof body?.code !== 'string' || typeof body.message !== 'string' || typeof body.traceId !== 'string') {
      throw new PointsTaskConfigFailure('INVALID_RESPONSE', res.status);
    }
    if (!res.ok || body.code !== 'SUCCESS') {
      if (body.data !== null) throw new PointsTaskConfigFailure('INVALID_RESPONSE', res.status);
      throw new PointsTaskConfigFailure(body.code, res.status);
    }
    if (body.message !== 'ok') throw new PointsTaskConfigFailure('INVALID_RESPONSE', res.status);
    return body.data;
  }
  return {
    resetContext(nextToken?: string) { epoch++; token = nextToken; },
    async load() {
      const data = await envelope(ROOT);
      if (!isConfigShape(data)) throw new PointsTaskConfigFailure('INVALID_RESPONSE', 200);
      return data;
    },
    async save(draft: PointsTaskConfigDraft, requestId: string) {
      const parsed = parseDraft(draft);
      if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![\s\S])/.test(requestId)) {
        throw new PointsTaskConfigFailure('INVALID_ARGUMENT', 0);
      }
      const data = await envelope(ROOT, { body: parsed, requestId });
      if (!isConfigShape(data)) throw new PointsTaskConfigFailure('INVALID_RESPONSE', 200);
      return data;
    },
  };
}
export type PointsTaskConfigClient = ReturnType<typeof createPointsTaskConfigClient>;

/** 409 IDEMPOTENCY_KEY_CONFLICT = 同 requestId 异参（旧命令还在），换新 requestId 才可提交。 */
export function isIdempotencyConflict(error: unknown): boolean {
  return error instanceof PointsTaskConfigFailure && error.status === 409 && error.code === 'IDEMPOTENCY_KEY_CONFLICT';
}

export const FIELD_LABELS: Record<ConfigFieldKey, string> = Object.fromEntries(
  CONFIG_FIELDS.map(field => [field.key, field.label])) as Record<ConfigFieldKey, string>;
