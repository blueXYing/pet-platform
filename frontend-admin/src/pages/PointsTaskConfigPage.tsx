import { useEffect, useRef, useState } from 'react';
import {
  CONFIG_FIELDS, isIdempotencyConflict, parseDraft, PointsTaskConfigFailure, draftFrom,
  type PointsTaskConfig, type PointsTaskConfigClient, type PointsTaskConfigDraft,
} from '../api/pointsTaskConfig';

const FAILURE_TEXT: Record<string, string> = {
  UNAUTHENTICATED: '登录已失效',
  FORBIDDEN: '无积分任务配置权限',
  INVALID_ARGUMENT: '取值超出范围，请按每项范围修改',
  IDEMPOTENCY_KEY_CONFLICT: '同一请求编号已提交过其他数值，请重新点保存生成新编号',
  INVALID_RESPONSE: '服务返回异常',
  DEPENDENCY_UNAVAILABLE: '配置数据暂时不可用',
};

/**
 * Contract58 admin config (32号 ruling: 签到/拉新积分量后台可配). Single operator edits and
 * publishes directly (SSOT §24, no second approver). Full-PUT of the seven grant amounts with
 * supplement-23 idempotency: a save keeps its X-Request-Id while the payload is unchanged, so
 * an unknown-outcome retry replays the same command instead of double-writing.
 */
export default function PointsTaskConfigPage({ client, canWrite, onAuthLost }: {
  client: PointsTaskConfigClient;
  canWrite: boolean;
  onAuthLost: () => void;
}) {
  const [config, setConfig] = useState<PointsTaskConfig>();
  const [draft, setDraft] = useState<PointsTaskConfigDraft>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [saving, setSaving] = useState(false);
  const sequence = useRef(0);
  // Same requestId replays the same payload; editing any field retires it (fresh command).
  const requestId = useRef(crypto.randomUUID());
  const payloadKey = (value: PointsTaskConfigDraft) => CONFIG_FIELDS.map(field => value[field.key]).join(',');

  useEffect(() => {
    const call = ++sequence.current;
    void (async () => {
      try {
        const data = await client.load();
        if (call !== sequence.current) return;
        setConfig(data);
        setDraft(draftFrom(data));
        setLoading(false);
      } catch (caught) {
        if (call !== sequence.current) return;
        setLoading(false);
        if (caught instanceof PointsTaskConfigFailure && ['UNAUTHENTICATED', 'STALE_CONTEXT'].includes(caught.code)) { onAuthLost(); return; }
        setError(caught instanceof PointsTaskConfigFailure ? FAILURE_TEXT[caught.code] ?? `读取失败（${caught.code}）` : '读取失败');
      }
    })();
    return () => { sequence.current++; };
  }, [client]);

  function edit(key: keyof PointsTaskConfigDraft, value: string) {
    if (!draft) return;
    setDraft({ ...draft, [key]: value });
    // Editing always starts a fresh command id; retrying a save without editing replays the
    // original X-Request-Id (supplement-23: same id + same payload = replay, not a second write).
    requestId.current = crypto.randomUUID();
    setNotice('');
    setError('');
  }

  async function save() {
    if (!draft || saving) return;
    const call = sequence.current;
    setSaving(true);
    setError('');
    setNotice('');
    try {
      parseDraft(draft); // mirror the kernel bounds before any network call
    } catch {
      setSaving(false);
      setError(FAILURE_TEXT.INVALID_ARGUMENT);
      return;
    }
    try {
      const saved = await client.save(draft, requestId.current);
      if (call !== sequence.current) return;
      setConfig(saved);
      setDraft(draftFrom(saved));
      setNotice(`已发布（版本 ${saved.version}）。新领取按新数值发放，既有流水不变。`);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof PointsTaskConfigFailure && ['UNAUTHENTICATED', 'STALE_CONTEXT'].includes(caught.code)) { onAuthLost(); return; }
      if (isIdempotencyConflict(caught)) requestId.current = crypto.randomUUID();
      setError(caught instanceof PointsTaskConfigFailure ? FAILURE_TEXT[caught.code] ?? `保存失败（${caught.code}）` : '保存失败');
    } finally {
      if (call === sequence.current) setSaving(false);
    }
  }

  const dirty = config !== undefined && draft !== undefined && payloadKey(draft) !== payloadKey(draftFrom(config));
  return <main>
    <h1>积分任务配置</h1>
    <p className="hint">签到与拉新的积分发放量配置（58 号契约）。V1 积分只赚取/扣回、不消费：本页只调发放量，不涉及任何兑换或扣减路径。单运营编辑直接发布（24 号裁决），发布即刻生效于后续领取。</p>
    {loading && <p role="status">加载中</p>}
    {error && <p role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {config && draft && <form className="points-config" onSubmit={event => { event.preventDefault(); void save(); }}>
      <table>
        <thead><tr><th>配置项</th><th>修改为</th><th>范围</th><th>说明</th></tr></thead>
        <tbody>
          {CONFIG_FIELDS.map(field => <tr key={field.key}>
            <th scope="row">{field.label}</th>
            <td>
              <label className="sr-only" htmlFor={field.key}>{field.label}</label>
              <input id={field.key} name={field.key} type="number" inputMode="numeric" min={field.min} max={field.max}
                step={1} value={draft[field.key]} disabled={!canWrite || saving}
                aria-describedby={`${field.key}-hint`} onChange={event => edit(field.key, event.target.value)} />
            </td>
            <td>{field.min} ~ {field.max}</td>
            <td id={`${field.key}-hint`}>{field.hint}</td>
          </tr>)}
        </tbody>
      </table>
      <p>版本 {config.version} · 上次更新 {config.updatedAt ? config.updatedAt.slice(0, 19).replace('T', ' ') : '尚未配置（内置缺省值生效）'}</p>
      {canWrite
        ? <button type="submit" disabled={saving || !dirty}>{saving ? '保存中…' : dirty ? '保存并发布' : '无改动'}</button>
        : <p>当前账号仅可查看。</p>}
    </form>}
  </main>;
}
