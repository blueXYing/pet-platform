import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { REFUND_SOURCE_TYPES, REFUND_STATUSES, TradeQueryFailure, refundSourceLabel, refundStatusLabel, type Page, type RefundFilters, type RefundSummary, type TradeQueryClient } from '../api/tradeQuery';

type Filters = { status: string; sourceType: string; refundType: string; orderId: string; createdFrom: string; createdTo: string };
const EMPTY: Filters = { status: '', sourceType: '', refundType: '', orderId: '', createdFrom: '', createdTo: '' };
const ID = /^[1-9][0-9]{0,18}$/;
const ISO = (value: string) => (value ? new Date(value).toISOString() : undefined);

/** 运营 PRD 6.3.4 退款单管理（读侧）：统一逆向资金查询；防重复退款事实以服务端为准。 */
export default function TradeRefundListPage({ client, onAuthLost }: { client: TradeQueryClient; onAuthLost: () => void }) {
  const [params] = useSearchParams();
  const initial: Filters = { ...EMPTY, orderId: params.get('orderId') ?? '' };
  const [filters, setFilters] = useState<Filters>(initial);
  const [applied, setApplied] = useState<Filters>();
  const [result, setResult] = useState<Page<RefundSummary>>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);
  useEffect(() => { void query(initial, 1); return () => { sequence.current++; }; }, []);
  async function query(next: Filters, page: number) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.listRefunds(page, {
        status: (next.status || undefined) as RefundFilters['status'],
        sourceType: (next.sourceType || undefined) as RefundFilters['sourceType'],
        refundType: (next.refundType || undefined) as RefundFilters['refundType'],
        orderId: next.orderId || undefined,
        createdFrom: ISO(next.createdFrom),
        createdTo: ISO(next.createdTo),
      });
      if (call !== sequence.current) return;
      setResult(data);
      setApplied(next);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof TradeQueryFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof TradeQueryFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
      if (caught instanceof TradeQueryFailure && caught.code === 'STALE_CONTEXT') return;
      setError(caught instanceof TradeQueryFailure ? `退款单查询失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '退款单查询失败，请重试。');
    } finally { if (call === sequence.current) setLoading(false); }
  }
  function patch(key: keyof Filters, value: string) {
    setFilters(current => ({ ...current, [key]: value }));
  }
  const valid = (filters.orderId === '' || ID.test(filters.orderId)) && (!filters.createdFrom || !filters.createdTo || filters.createdFrom <= filters.createdTo);
  const pages = result ? Math.min(10000, Math.max(1, Math.ceil(result.total / result.pageSize))) : 1;
  return <main className="aftersale-page">
    <h1>退款单管理</h1>
    <p className="hint">平台退款单统一查询。退款以支付服务商回执为最终结果；一笔订单最多一张业务退款单。渠道查单/重试不在 V1 交付范围。</p>
    <form className="filters" onSubmit={event => { event.preventDefault(); if (valid) void query(filters, 1); }}>
      <label>退款状态
        <select value={filters.status} onChange={event => patch('status', event.target.value)}>
          <option value="">全部</option>
          {REFUND_STATUSES.map(value => <option key={value} value={value}>{refundStatusLabel(value)}</option>)}
        </select>
      </label>
      <label>退款来源
        <select value={filters.sourceType} onChange={event => patch('sourceType', event.target.value)}>
          <option value="">全部</option>
          {REFUND_SOURCE_TYPES.map(value => <option key={value} value={value}>{refundSourceLabel(value)}</option>)}
        </select>
      </label>
      <label>退款类型
        <select value={filters.refundType} onChange={event => patch('refundType', event.target.value)}>
          <option value="">全部</option>
          <option value="FULL">全额退款</option>
          <option value="PARTIAL">部分退款</option>
        </select>
      </label>
      <label>订单编号<input inputMode="numeric" value={filters.orderId} onChange={event => patch('orderId', event.target.value)} /></label>
      <label>创建起<input type="datetime-local" value={filters.createdFrom} onChange={event => patch('createdFrom', event.target.value)} /></label>
      <label>创建止<input type="datetime-local" value={filters.createdTo} onChange={event => patch('createdTo', event.target.value)} /></label>
      <button disabled={loading || !valid}>查询退款单</button>
    </form>
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在查询退款单</p>}
    {result && <>
      <div className="table-scroll"><table>
        <thead><tr><th>退款单号</th><th>关联订单</th><th>类型</th><th>来源</th><th>状态</th><th>金额</th><th>发起方</th><th>创建（UTC）</th><th>完成（UTC）</th><th>操作</th></tr></thead>
        <tbody>{result.items.map(item => <tr key={item.refundOrderId}>
          <td>{item.refundNo}</td>
          <td>{item.orderId}</td>
          <td>{item.refundType === 'FULL' ? '全额' : '部分'}</td>
          <td>{refundSourceLabel(item.sourceType)}</td>
          <td>{refundStatusLabel(item.status)}</td>
          <td>{item.refundAmount}</td>
          <td>{item.initiatorType}</td>
          <td>{item.createdAt}</td>
          <td>{item.succeededAt ?? '—'}</td>
          <td><Link to={`/trade/refunds/${item.refundOrderId}`}>详情</Link></td>
        </tr>)}</tbody>
      </table></div>
      <nav className="pager" aria-label="退款单分页">
        <button disabled={loading || result.page <= 1} onClick={() => applied && void query(applied, result.page - 1)}>上一页</button>
        <span>第 {result.page} / {pages} 页 · 共 {result.total} 条</span>
        <button disabled={loading || result.page >= pages} onClick={() => applied && void query(applied, result.page + 1)}>下一页</button>
      </nav>
    </>}
  </main>;
}
