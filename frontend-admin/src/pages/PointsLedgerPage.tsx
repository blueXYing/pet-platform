import { useEffect, useRef, useState } from 'react';
import { BIZ_TYPES, BIZ_TYPE_LABELS, type BizType, type LedgerAdminClient, type PointsLedgerRow } from '../api/ledgerAdmin';

/**
 * Contract-65 积分台账页 (PRD 6.5 积分流水, HTTP10 §5.8 drafted route, A-005): read-only
 * operator ledger with user/type filters. V1 points only accrue and get clawed back
 * (SSOT §10) — the page offers no adjustment/freeze/clawback action because the wire has none.
 */
export default function PointsLedgerPage({ client, onAuthLost }: {
  client: LedgerAdminClient;
  onAuthLost: () => void;
}) {
  const [rows, setRows] = useState<PointsLedgerRow[]>();
  const [total, setTotal] = useState(0);
  const [userId, setUserId] = useState('');
  const [bizType, setBizType] = useState<BizType | ''>('');
  const [error, setError] = useState('');
  const sequence = useRef(0);

  async function load(nextUserId: string, nextBizType: BizType | '') {
    const call = ++sequence.current;
    try {
      const page = await client.listPointsLedger({
        userId: /^\d+$/.test(nextUserId) ? nextUserId : undefined,
        bizType: nextBizType || undefined,
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

  return <main className="page">
    <h2>台账 · 积分流水</h2>
    <p className="hint">只读台账：用户、来源、数量、余额、时间（PRD 6.5）。积分只赚取/扣回，不消费（SSOT §10）；运营手工调整在 V1 无授权路由。</p>
    <form className="filters" onSubmit={event => { event.preventDefault(); void load(userId, bizType); }}>
      <label>用户 ID
        <input value={userId} onChange={event => setUserId(event.target.value)} placeholder="Snowflake ID" inputMode="numeric" />
      </label>
      <label>来源
        <select value={bizType} onChange={event => { setBizType(event.target.value as BizType | ''); void load(userId, event.target.value as BizType | ''); }}>
          <option value="">全部</option>
          {BIZ_TYPES.map(value => <option key={value} value={value}>{BIZ_TYPE_LABELS[value]}</option>)}
        </select>
      </label>
      <button type="submit">查询</button>
      {error && <span role="alert">{error}</span>}
    </form>
    {rows === undefined ? <p role="status">加载中</p> : <table>
      <thead><tr>
        <th>流水 ID</th><th>用户</th><th>来源</th><th>变动</th><th>余额</th><th>订单</th><th>时间</th>
      </tr></thead>
      <tbody>
        {rows.map(row => <tr key={row.ledgerId}>
          <td>{row.ledgerId}</td>
          <td>{row.userId}</td>
          <td>{BIZ_TYPE_LABELS[row.bizType]}</td>
          <td>{row.delta}</td>
          <td>{row.balanceAfter}</td>
          <td>{row.orderId ?? '—'}</td>
          <td>{row.createdAt}</td>
        </tr>)}
        {rows.length === 0 && <tr><td colSpan={7}>无流水</td></tr>}
      </tbody>
    </table>}
    <p className="hint">共 {total} 行</p>
  </main>;
}
