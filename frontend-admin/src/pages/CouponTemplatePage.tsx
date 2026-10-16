import { useEffect, useRef, useState } from 'react';
import {
  isCouponConflict, INSTANCE_STATUSES, TEMPLATE_STATUSES,
  type AdminTemplateDetail, type AdminTemplateSummary, type CouponAdminClient,
  type TemplateStatus,
} from '../api/couponAdmin';

const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', ACTIVE: '启用', OFFLINE: '停用',
  AVAILABLE: '可领取', FROZEN: '已冻结', USED: '已使用', EXPIRED: '已过期', RISK_FROZEN: '风控冻结',
};

/**
 * Contract-65 券模板管理页 (PRD 6.4.1, A-005): template ledger, create-draft form, edit
 * guard (发放过的模板不可编辑，需停用后新建), enable/offline transitions and the freeze-rule
 * trial calculation. No issuance channel exists in V1 (CCR-ADM-A005-OPS-001); the page states
 * that honestly instead of inventing one.
 */
export default function CouponTemplatePage({ client, canWrite, onAuthLost }: {
  client: CouponAdminClient;
  canWrite: boolean;
  onAuthLost: () => void;
}) {
  const [rows, setRows] = useState<AdminTemplateSummary[]>();
  const [status, setStatus] = useState<TemplateStatus | ''>('');
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AdminTemplateDetail>();
  const [trial, setTrial] = useState('');
  const [trialResult, setTrialResult] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const sequence = useRef(0);

  async function load(nextStatus: TemplateStatus | '') {
    const call = ++sequence.current;
    try {
      const page = await client.listTemplates(nextStatus ? { status: nextStatus } : {});
      if (call !== sequence.current) return;
      setRows(page.items);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof Error && caught.message === 'UNAUTHENTICATED') { onAuthLost(); return; }
      setError(caught instanceof Error ? `读取失败（${caught.message}）` : '读取失败');
    }
  }

  useEffect(() => { void load(''); return () => { sequence.current++; }; }, [client]); // eslint-disable-line react-hooks/exhaustive-deps

  async function act(action: () => Promise<unknown>, okText: string) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await action();
      setNotice(okText);
      setEditing(undefined);
      await load(status);
    } catch (caught) {
      if (isCouponConflict(caught)) setError('状态或版本冲突：模板已被其他人修改，请刷新后重试');
      else setError(caught instanceof Error ? `操作失败（${caught.message}）` : '操作失败');
    } finally {
      setBusy(false);
    }
  }

  const guard = (action: () => void) => () => {
    if (!canWrite) { setError('当前账号未授予 coupon.template.manage'); return; }
    action();
  };

  async function runTrial(summary: AdminTemplateSummary) {
    setEditing(undefined);
    if (!trial || Number(trial) <= 0) { setError('请输入有效的试算订单金额'); return; }
    try {
      const result = await client.trial(summary.templateId, Number(trial).toFixed(2));
      setTrialResult(result.eligible
        ? `可抵扣 ${result.discountAmount} 元，应付 ${result.payAmount} 元`
        : `不可用：${result.ineligibleReason}`);
    } catch (caught) {
      setError(caught instanceof Error ? `试算失败（${caught.message}）` : '试算失败');
    }
  }

  return <main className="page">
    <h2>优惠券 · 券模板</h2>
    <p className="hint">模板一经发放（引用数 &gt; 0）关键字段不可修改，需停用后新建；停用不影响已发放券实例。发放渠道与“领取后 N 天”模式 V1 不做（CCR-ADM-A005-OPS-001）。</p>
    <form className="filters" onSubmit={event => event.preventDefault()}>
      <label>状态
        <select value={status} onChange={event => { setStatus(event.target.value as TemplateStatus | ''); void load(event.target.value as TemplateStatus | ''); }}>
          <option value="">全部</option>
          {TEMPLATE_STATUSES.map(value => <option key={value} value={value}>{STATUS_TEXT[value]}</option>)}
        </select>
      </label>
      <button type="button" disabled={busy} onClick={guard(() => setCreating(true))}>新增模板</button>
      {notice && <output role="status">{notice}</output>}
      {error && <span role="alert">{error}</span>}
    </form>
    {rows === undefined ? <p role="status">加载中</p> : <table>
      <thead><tr>
        <th>模板 ID</th><th>名称</th><th>状态</th><th>面额</th><th>门槛</th><th>有效期</th><th>已发放</th><th>版本</th><th>操作</th>
      </tr></thead>
      <tbody>
        {rows.map(row => <tr key={row.templateId}>
          <td>{row.templateId}</td>
          <td>{row.name}</td>
          <td>{STATUS_TEXT[row.status]}</td>
          <td>{row.amountOff}</td>
          <td>{row.thresholdAmount ?? '无门槛'}</td>
          <td>{row.validStartAt.slice(0, 10)} ~ {row.validEndAt.slice(0, 10)}</td>
          <td>{row.issuedCount}</td>
          <td>{row.version}</td>
          <td>
            {row.status === 'DRAFT' && <button disabled={busy} onClick={guard(() => void act(
              () => client.setTemplateEnabled(row.templateId, row.version, true, crypto.randomUUID()), '已启用'))}>启用</button>}
            {row.status === 'ACTIVE' && <button disabled={busy} onClick={guard(() => void act(
              () => client.setTemplateEnabled(row.templateId, row.version, false, crypto.randomUUID()), '已停用'))}>停用</button>}
            {row.status !== 'OFFLINE' && row.issuedCount === '0' && <button disabled={busy} onClick={guard(() => void client.getTemplate(row.templateId).then(setEditing).catch(cause => setError(cause instanceof Error ? `读取失败（${cause.message}）` : '读取失败')))}>编辑</button>}
          </td>
        </tr>)}
        {rows.length === 0 && <tr><td colSpan={9}>无模板</td></tr>}
      </tbody>
    </table>}

    {(creating || editing) && <section className="detail-card" aria-label="模板表单">
      <h3>{creating ? '新增模板（草稿）' : `编辑：${editing?.name}（版本 ${editing?.version}）`}</h3>
      <form onSubmit={event => {
        event.preventDefault();
        const form = new FormData(event.currentTarget);
        const thresholdRaw = String(form.get('thresholdAmount') ?? '').trim();
        const draft = {
          name: String(form.get('name') ?? '').trim(),
          amountOff: Number(form.get('amountOff') ?? '0').toFixed(2),
          thresholdAmount: thresholdRaw ? Number(thresholdRaw).toFixed(2) : null,
          validStartAt: `${String(form.get('validStartDate') ?? '')}T00:00:00.000Z`,
          validEndAt: `${String(form.get('validEndDate') ?? '')}T23:59:59.000Z`.replace('T23:59:59.000Z', 'T15:59:59.000Z'),
          scopeSummary: (String(form.get('scopeSummary') ?? '').trim() || null),
          typeLabel: null,
        };
        if (creating) void act(() => client.createTemplate(draft, crypto.randomUUID()), '已创建（草稿）');
        else if (editing) void act(() => client.updateTemplate(editing.templateId, draft, editing.version, crypto.randomUUID()), '已保存');
      }}>
        <label>券名称（2..50 字）<input name="name" defaultValue={editing?.name} minLength={2} maxLength={50} required /></label>
        <label>面额（元，&gt;0）<input name="amountOff" type="number" step="0.01" min="0.01" defaultValue={editing?.amountOff} required /></label>
        <label>使用门槛（元，留空为无门槛券）<input name="thresholdAmount" type="number" step="0.01" min="0" defaultValue={editing?.thresholdAmount ?? ''} /></label>
        <label>生效日期<input name="validStartDate" type="date" defaultValue={editing?.validStartAt.slice(0, 10)} required /></label>
        <label>失效日期<input name="validEndDate" type="date" defaultValue={editing?.validEndAt.slice(0, 10)} required /></label>
        <label>适用范围说明（展示用，≤64 字）<input name="scopeSummary" defaultValue={editing?.scopeSummary ?? ''} maxLength={64} /></label>
        <button type="submit" disabled={busy}>保存</button>
        <button type="button" onClick={() => { setCreating(false); setEditing(undefined); }}>关闭</button>
      </form>
      {editing && <form className="filters" onSubmit={event => { event.preventDefault(); void runTrial(editing); }}>
        <label>试算订单金额（元）<input value={trial} onChange={event => setTrial(event.target.value)} inputMode="decimal" /></label>
        <button type="submit" disabled={busy}>试算</button>
        {trialResult && <output role="status">{trialResult}</output>}
      </form>}
    </section>}
  </main>;
}

export const COUPON_INSTANCE_STATUS_LABELS = Object.fromEntries(
  INSTANCE_STATUSES.map(value => [value, STATUS_TEXT[value]]));
