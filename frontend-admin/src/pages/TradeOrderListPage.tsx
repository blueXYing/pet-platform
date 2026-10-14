import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { DISPLAY_STATUSES, PAYMENT_STATUSES, TradeQueryFailure, displayStatusLabel, type OrderFilters, type OrderSummary, type Page, type TradeQueryClient } from '../api/tradeQuery';

type Filters = {
  displayStatus: string; paymentStatus: string; merchantId: string; storeId: string;
  orderNo: string; keyword: string; createdFrom: string; createdTo: string; rescheduledOnly: boolean;
};
const EMPTY: Filters = { displayStatus: '', paymentStatus: '', merchantId: '', storeId: '', orderNo: '', keyword: '', createdFrom: '', createdTo: '', rescheduledOnly: false };
const ID = /^[1-9][0-9]{0,18}$/;
const ISO = (value: string) => (value ? new Date(value).toISOString() : undefined);

/** 运营 PRD 6.3.1 订单总览：平台统一查询视图；金额/状态只读，服务端投影展示态。 */
export default function TradeOrderListPage({ client, onAuthLost }: { client: TradeQueryClient; onAuthLost: () => void }) {
  const [filters, setFilters] = useState<Filters>(EMPTY);
  const [result, setResult] = useState<Page<OrderSummary>>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);
  useEffect(() => { void query(EMPTY, 1); return () => { sequence.current++; }; }, []);
  function toApi(next: Filters) {
    return {
      displayStatus: (next.displayStatus || undefined) as OrderFilters['displayStatus'],
      paymentStatus: (next.paymentStatus || undefined) as OrderFilters['paymentStatus'],
      merchantId: next.merchantId || undefined,
      storeId: next.storeId || undefined,
      orderNo: next.orderNo || undefined,
      keyword: next.keyword || undefined,
      createdFrom: ISO(next.createdFrom),
      createdTo: ISO(next.createdTo),
      rescheduledOnly: next.rescheduledOnly || undefined,
    };
  }
  async function query(next: Filters, page: number) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.listOrders(page, toApi(next));
      if (call !== sequence.current) return;
      setResult(data);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof TradeQueryFailure && ['UNAUTHENTICATED', 'FORBIDDEN'].includes(caught.code)) { onAuthLost(); return; }
      if (caught instanceof TradeQueryFailure && caught.code === 'STALE_CONTEXT') return;
      setError(caught instanceof TradeQueryFailure ? `订单查询失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '订单查询失败，请重试。');
    } finally { if (call === sequence.current) setLoading(false); }
  }
  function patch(key: keyof Filters, value: string | boolean) {
    setFilters(current => ({ ...current, [key]: value }));
  }
  const idOk = (value: string) => value === '' || ID.test(value);
  const keywordOk = filters.keyword === '' || (filters.keyword.length >= 2 && filters.keyword.length <= 64 && !/[%_\\]/.test(filters.keyword));
  const valid = idOk(filters.merchantId) && idOk(filters.storeId) && idOk(filters.orderNo) && keywordOk
    && (!filters.createdFrom || !filters.createdTo || filters.createdFrom <= filters.createdTo);
  const pages = result ? Math.min(10000, Math.max(1, Math.ceil(result.total / result.pageSize))) : 1;
  return <main className="aftersale-page">
    <h1>订单总览</h1>
    <p className="hint">平台订单统一查询。展示状态由服务端统一计算；金额只读；不回传用户手机号等敏感明文。</p>
    <form className="filters" onSubmit={event => { event.preventDefault(); if (valid) void query(filters, 1); }}>
      <label>展示状态
        <select value={filters.displayStatus} onChange={event => patch('displayStatus', event.target.value)}>
          <option value="">全部</option>
          {DISPLAY_STATUSES.map(value => <option key={value} value={value}>{displayStatusLabel(value)}</option>)}
        </select>
      </label>
      <label>支付状态
        <select value={filters.paymentStatus} onChange={event => patch('paymentStatus', event.target.value)}>
          <option value="">全部</option>
          {PAYMENT_STATUSES.map(value => <option key={value} value={value}>{value === 'INIT' ? '未支付' : '已支付'}</option>)}
        </select>
      </label>
      <label>商家编号<input inputMode="numeric" value={filters.merchantId} onChange={event => patch('merchantId', event.target.value)} /></label>
      <label>门店编号<input inputMode="numeric" value={filters.storeId} onChange={event => patch('storeId', event.target.value)} /></label>
      <label>订单号<input inputMode="numeric" value={filters.orderNo} onChange={event => patch('orderNo', event.target.value)} /></label>
      <label>关键词（商家名）<input value={filters.keyword} onChange={event => patch('keyword', event.target.value)} /></label>
      <label>创建起<input type="datetime-local" value={filters.createdFrom} onChange={event => patch('createdFrom', event.target.value)} /></label>
      <label>创建止<input type="datetime-local" value={filters.createdTo} onChange={event => patch('createdTo', event.target.value)} /></label>
      <label>仅看已改期<input type="checkbox" checked={filters.rescheduledOnly} onChange={event => patch('rescheduledOnly', event.target.checked)} /></label>
      <button disabled={loading || !valid}>查询订单</button>
    </form>
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在查询订单</p>}
    {result && <>
      <div className="table-scroll"><table>
        <thead><tr><th>订单号</th><th>商家</th><th>服务</th><th>展示状态</th><th>支付</th><th>实付</th><th>已退</th><th>预约开始（UTC）</th><th>创建（UTC）</th><th>改期</th><th>操作</th></tr></thead>
        <tbody>{result.items.map(item => <tr key={item.orderId}>
          <td>{item.orderNo}</td>
          <td>{item.merchantName ?? item.merchantId}</td>
          <td>{item.serviceName ?? item.serviceId}</td>
          <td>{displayStatusLabel(item.displayStatus)}</td>
          <td>{item.paymentStatus === 'PAID' ? '已支付' : '未支付'}</td>
          <td>{item.payAmount}</td>
          <td>{item.refundedAmount}</td>
          <td>{item.appointmentStart}</td>
          <td>{item.createdAt}</td>
          <td>{item.rescheduleCount}</td>
          <td><Link to={`/trade/orders/${item.orderId}`}>详情</Link></td>
        </tr>)}</tbody>
      </table></div>
      <nav className="pager" aria-label="订单分页">
        <button disabled={loading || result.page <= 1} onClick={() => void query(filters, result.page - 1)}>上一页</button>
        <span>第 {result.page} / {pages} 页 · 共 {result.total} 条</span>
        <button disabled={loading || result.page >= pages} onClick={() => void query(filters, result.page + 1)}>下一页</button>
      </nav>
    </>}
  </main>;
}
