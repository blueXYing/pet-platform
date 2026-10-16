import { useEffect, useRef, useState } from 'react';
import {
  INSTANCE_STATUSES, type AdminInstanceDetail, type AdminInstanceSummary,
  type CouponAdminClient, type InstanceStatus,
} from '../api/couponAdmin';

const STATUS_TEXT: Record<string, string> = {
  AVAILABLE: '可领取', FROZEN: '已冻结', USED: '已使用', EXPIRED: '已过期', RISK_FROZEN: '风控冻结',
};

/**
 * Contract-65 券实例台账页 (PRD 6.4.3, A-005): read-only instance ledger with filters and the
 * append-only coupon_ledger state timeline. The page deliberately renders no instance action:
 * 作废/返还/风控冻结 are not authorized in V1 (CCR-ADM-A005-OPS-001) and no such route exists.
 */
export default function CouponInstanceLedgerPage({ client, onAuthLost }: {
  client: CouponAdminClient;
  onAuthLost: () => void;
}) {
  const [rows, setRows] = useState<AdminInstanceSummary[]>();
  const [total, setTotal] = useState(0);
  const [status, setStatus] = useState<InstanceStatus | ''>('');
  const [templateId, setTemplateId] = useState('');
  const [detail, setDetail] = useState<AdminInstanceDetail>();
  const [error, setError] = useState('');
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

  return <main className="page">
    <h2>优惠券 · 券实例台账</h2>
    <p className="hint">只读台账：券资产全生命周期记录与状态时间轴。作废/返还/风控冻结在 V1 无授权路由（SSOT §9 未授权直改用户券状态，CCR-ADM-A005-OPS-001）。</p>
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
          <td><button onClick={() => void openDetail(row.instanceId)}>时间轴</button></td>
        </tr>)}
        {rows.length === 0 && <tr><td colSpan={9}>无实例</td></tr>}
      </tbody>
    </table>}
    <p className="hint">共 {total} 行</p>
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
