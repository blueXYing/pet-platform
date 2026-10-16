import { useEffect, useRef, useState } from 'react';
import {
  isCouponConflict, INSTANCE_STATUSES, type AdminInstanceDetail, type AdminInstanceSummary,
  type CouponAdminClient, type InstanceStatus, type VoidReceipt,
} from '../api/couponAdmin';

const STATUS_TEXT: Record<string, string> = {
  AVAILABLE: '可领取', FROZEN: '已冻结', USED: '已使用', EXPIRED: '已过期', RISK_FROZEN: '风控冻结',
  VOID: '已作废',
};

/**
 * Contract-65 券实例台账页 (PRD 6.4.3, A-005): instance ledger with filters, the append-only
 * coupon_ledger state timeline and the §4.4 void channel (user ruling 2026-10-16 doc 36
 * minimal authorization, CCR-CPN-VOID-001 minimum): AVAILABLE rows carry a 作废 entry that is
 * double-confirmed before the wire; every non-AVAILABLE state answers 409 server-side and
 * renders no entry. Batch void / return / risk-freeze stay unauthorized (CCR-ADM-A005-OPS-001)
 * and the page states that honestly.
 */
export default function CouponInstanceLedgerPage({ client, canVoid, onAuthLost }: {
  client: CouponAdminClient;
  canVoid: boolean;
  onAuthLost: () => void;
}) {
  const [rows, setRows] = useState<AdminInstanceSummary[]>();
  const [total, setTotal] = useState(0);
  const [status, setStatus] = useState<InstanceStatus | ''>('');
  const [templateId, setTemplateId] = useState('');
  const [detail, setDetail] = useState<AdminInstanceDetail>();
  const [voiding, setVoiding] = useState<AdminInstanceSummary>();
  const [voidConfirm, setVoidConfirm] = useState(false);
  const [voidReason, setVoidReason] = useState('');
  const [voidReceipt, setVoidReceipt] = useState<VoidReceipt>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const sequence = useRef(0);

  async function load(nextStatus: InstanceStatus | '', nextTemplateId: string) {
    const call = ++sequence.current;
    try {
      const page = await client.listInstances({
        status: nextStatus || undefined,
        templateId: /^\d+$/.test(nextTemplateId) ? nextTemplateId : undefined,
      });
      if (call !== sequence.current) return;
      setRows(page.items);
      setTotal(page.total);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof Error && caught.message === 'UNAUTHENTICATED') { onAuthLost(); return; }
      setError(caught instanceof Error ? `读取失败（${caught.message}）` : '读取失败');
    }
  }

  useEffect(() => { void load('', ''); return () => { sequence.current++; }; }, [client]); // eslint-disable-line react-hooks/exhaustive-deps

  async function openDetail(instanceId: string) {
    try {
      setDetail(await client.getInstance(instanceId));
    } catch (caught) {
      setError(caught instanceof Error ? `读取失败（${caught.message}）` : '读取失败');
    }
  }

  const voidGuard = (row: AdminInstanceSummary) => () => {
    if (!canVoid) { setError('当前账号未授予 coupon.instance.void'); return; }
    setVoiding(row); setVoidConfirm(false); setVoidReason(''); setVoidReceipt(undefined);
  };

  /** §4.4 void: reason + explicit double confirm → one terminal request id per void. */
  async function submitVoid() {
    if (!voiding) return;
    const trimmed = voidReason.trim();
    const codePoints = [...trimmed].length;
    if (codePoints < 10 || codePoints > 200) { setError('作废原因必填（10–200 字）'); return; }
    if (!voidConfirm) { setError('请先勾选二次确认'); return; }
    setBusy(true);
    try {
      const receipt = await client.voidInstance(voiding.instanceId, trimmed, crypto.randomUUID());
      setVoidReceipt(receipt);
      setVoiding(undefined);
      setVoidConfirm(false);
      setVoidReason('');
      setNotice(`已作废：券实例 ${receipt.instanceId}（已作废，不可恢复，无补偿）`);
      setError('');
      await load(status, templateId);
    } catch (caught) {
      if (caught instanceof Error && caught.message === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (isCouponConflict(caught)) setError('作废冲突：券不在可作废状态（仅未使用且未被订单冻结的券可作废），或请求编号冲突');
      else setError(caught instanceof Error ? `作废失败（${caught.message}）` : '作废失败');
    } finally {
      setBusy(false);
    }
  }

  return <main className="page">
    <h2>优惠券 · 券实例台账</h2>
    <p className="hint">券资产全生命周期记录与状态时间轴。作废渠道为最小实现：仅"可领取"状态的实例可作废（已使用/被订单冻结/已过期的券一律拒绝，被订单锁着的券不许作废），作废即时生效、不可恢复、无补偿（CCR-CPN-VOID-001）；批量作废/返还/风控冻结在 V1 无授权（CCR-ADM-A005-OPS-001）。</p>
    <form className="filters" onSubmit={event => { event.preventDefault(); void load(status, templateId); }}>
      <label>状态
        <select value={status} onChange={event => { setStatus(event.target.value as InstanceStatus | ''); void load(event.target.value as InstanceStatus | '', templateId); }}>
          <option value="">全部</option>
          {INSTANCE_STATUSES.map(value => <option key={value} value={value}>{STATUS_TEXT[value]}</option>)}
        </select>
      </label>
      <label>模板 ID
        <input value={templateId} onChange={event => setTemplateId(event.target.value)} placeholder="Snowflake ID" inputMode="numeric" />
      </label>
      <button type="submit">查询</button>
      {error && <span role="alert">{error}</span>}
      {notice && <output role="status">{notice}</output>}
    </form>
    {rows === undefined ? <p role="status">加载中</p> : <table>
      <thead><tr>
        <th>实例 ID</th><th>券名称</th><th>状态</th><th>面额</th><th>门槛</th><th>用户</th><th>到期</th><th>订单</th><th>操作</th>
      </tr></thead>
      <tbody>
        {rows.map(row => <tr key={row.instanceId}>
          <td>{row.instanceId}</td>
          <td>{row.templateName}</td>
          <td>{STATUS_TEXT[row.status]}</td>
          <td>{row.amountOff}</td>
          <td>{row.thresholdAmount ?? '无门槛'}</td>
          <td>{row.userId}</td>
          <td>{row.expireAt.slice(0, 10)}</td>
          <td>{row.orderId ?? '—'}</td>
          <td>
            {row.status === 'AVAILABLE' && <button disabled={busy} onClick={voidGuard(row)}>作废</button>}
            <button onClick={() => void openDetail(row.instanceId)}>时间轴</button>
          </td>
        </tr>)}
        {rows.length === 0 && <tr><td colSpan={9}>无实例</td></tr>}
      </tbody>
    </table>}
    <p className="hint">共 {total} 行</p>
    {voiding && <section className="detail-card" aria-label="作废确认">
      <h3>作废：{voiding.instanceId}（{voiding.templateName}，面额 {voiding.amountOff}，用户 {voiding.userId}）</h3>
      <p className="hint">作废后该券对用户立即不可见且不可恢复，V1 无补偿路径（CCR-CPN-VOID-001）。</p>
      <form onSubmit={event => { event.preventDefault(); void submitVoid(); }}>
        <label>作废原因（必填，10–200 字）
          <textarea value={voidReason} onChange={event => setVoidReason(event.target.value)} disabled={busy} />
        </label>
        <label>
          <input type="checkbox" checked={voidConfirm}
            onChange={event => setVoidConfirm(event.target.checked)} disabled={busy} />
          我确认作废该券实例（同一人二次确认，非第二人审批）
        </label>
        <button type="submit" disabled={busy || !voidConfirm}>确认作废</button>
        <button type="button" disabled={busy} onClick={() => { setVoiding(undefined); setVoidConfirm(false); }}>取消</button>
      </form>
    </section>}
    {voidReceipt && <section className="detail-card" aria-label="作废回执">
      <h3>作废回执</h3>
      <dl className="facts">
        <dt>实例 ID</dt><dd>{voidReceipt.instanceId}</dd>
        <dt>用户</dt><dd>{voidReceipt.userId}</dd>
        <dt>状态</dt><dd>{STATUS_TEXT[voidReceipt.status]}</dd>
        <dt>时间（UTC）</dt><dd>{voidReceipt.occurredAt}</dd>
      </dl>
      <button onClick={() => setVoidReceipt(undefined)}>关闭</button>
    </section>}
    {detail && <section className="detail-card" aria-label="实例时间轴">
      <h3>状态时间轴：{detail.instanceId}（{STATUS_TEXT[detail.status]}）</h3>
      <table>
        <thead><tr><th>动作</th><th>前状态</th><th>后状态</th><th>订单</th><th>时间</th></tr></thead>
        <tbody>
          {detail.ledger.map(row => <tr key={row.ledgerId}>
            <td>{row.action}</td>
            <td>{row.fromStatus ?? '—'}</td>
            <td>{row.toStatus}</td>
            <td>{row.orderId ?? '—'}</td>
            <td>{row.createdAt}</td>
          </tr>)}
        </tbody>
      </table>
      <button onClick={() => setDetail(undefined)}>关闭</button>
    </section>}
  </main>;
}
