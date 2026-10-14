import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { TradeQueryFailure, VERIFY_METHODS, type Page, type TradeQueryClient, type VerificationFilters, type VerificationSummary } from '../api/tradeQuery';

type Filters = { orderId: string; storeId: string; method: string; verifiedFrom: string; verifiedTo: string };
const EMPTY: Filters = { orderId: '', storeId: '', method: '', verifiedFrom: '', verifiedTo: '' };
const ID = /^[1-9][0-9]{0,18}$/;
const ISO = (value: string) => (value ? new Date(value).toISOString() : undefined);

/** 运营 PRD 6.3.5 平台核销记录：核销/风控锁定事实查询，争议溯源入口；运营不得手置已完成。 */
export default function TradeVerificationListPage({ client, onAuthLost }: { client: TradeQueryClient; onAuthLost: () => void }) {
  const [params] = useSearchParams();
  const initial: Filters = { ...EMPTY, orderId: params.get('orderId') ?? '' };
  const [filters, setFilters] = useState<Filters>(initial);
  const [applied, setApplied] = useState<Filters>();
  const [result, setResult] = useState<Page<VerificationSummary>>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);
  useEffect(() => { void query(initial, 1); return () => { sequence.current++; }; }, []);
  async function query(next: Filters, page: number) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.listVerifications(page, {
        orderId: next.orderId || undefined,
        storeId: next.storeId || undefined,
        method: (next.method || undefined) as VerificationFilters['method'],
        verifiedFrom: ISO(next.verifiedFrom),
        verifiedTo: ISO(next.verifiedTo),
      });
      if (call !== sequence.current) return;
      setResult(data);
      setApplied(next);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof TradeQueryFailure && ['UNAUTHENTICATED', 'FORBIDDEN'].includes(caught.code)) { onAuthLost(); return; }
      if (caught instanceof TradeQueryFailure && caught.code === 'STALE_CONTEXT') return;
      setError(caught instanceof TradeQueryFailure ? `核销记录查询失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '核销记录查询失败，请重试。');
    } finally { if (call === sequence.current) setLoading(false); }
  }
  function patch(key: keyof Filters, value: string) {
    setFilters(current => ({ ...current, [key]: value }));
  }
  const valid = (filters.orderId === '' || ID.test(filters.orderId)) && (filters.storeId === '' || ID.test(filters.storeId))
    && (!filters.verifiedFrom || !filters.verifiedTo || filters.verifiedFrom <= filters.verifiedTo);
  const pages = result ? Math.min(10000, Math.max(1, Math.ceil(result.total / result.pageSize))) : 1;
  return <main className="aftersale-page">
    <h1>平台核销记录</h1>
    <p className="hint">核销成功是订单进入已完成的唯一入口；核销与退款单互斥事实以服务端为准。凭证密文不出域。</p>
    <form className="filters" onSubmit={event => { event.preventDefault(); if (valid) void query(filters, 1); }}>
      <label>订单编号<input inputMode="numeric" value={filters.orderId} onChange={event => patch('orderId', event.target.value)} /></label>
      <label>门店编号<input inputMode="numeric" value={filters.storeId} onChange={event => patch('storeId', event.target.value)} /></label>
      <label>核销方式
        <select value={filters.method} onChange={event => patch('method', event.target.value)}>
          <option value="">全部</option>
          {VERIFY_METHODS.map(value => <option key={value} value={value}>{value === 'SCAN' ? '扫码' : '手动输入'}</option>)}
        </select>
      </label>
      <label>核销起（UTC）<input type="datetime-local" value={filters.verifiedFrom} onChange={event => patch('verifiedFrom', event.target.value)} /></label>
      <label>核销止（UTC）<input type="datetime-local" value={filters.verifiedTo} onChange={event => patch('verifiedTo', event.target.value)} /></label>
      <button disabled={loading || !valid}>查询核销记录</button>
    </form>
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在查询核销记录</p>}
    {result && <>
      <div className="table-scroll"><table>
        <thead><tr><th>核销流水号</th><th>订单编号</th><th>门店编号</th><th>核销方式</th><th>核销时间（UTC）</th><th>操作</th></tr></thead>
        <tbody>{result.items.map(item => <tr key={item.verificationId}>
          <td>{item.verificationNo}</td>
          <td>{item.orderId}</td>
          <td>{item.storeId}</td>
          <td>{item.verifyMethod === 'SCAN' ? '扫码' : '手动输入'}</td>
          <td>{item.verifiedAt}</td>
          <td><Link to={`/trade/verifications/${item.verificationId}`}>详情</Link></td>
        </tr>)}</tbody>
      </table></div>
      <nav className="pager" aria-label="核销记录分页">
        <button disabled={loading || result.page <= 1} onClick={() => applied && void query(applied, result.page - 1)}>上一页</button>
        <span>第 {result.page} / {pages} 页 · 共 {result.total} 条</span>
        <button disabled={loading || result.page >= pages} onClick={() => applied && void query(applied, result.page + 1)}>下一页</button>
      </nav>
    </>}
  </main>;
}
