import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { AfterSaleFailure, CASE_STATUSES, caseStatusLabel, validId, type AfterSaleClient, type CasePage, type CaseStatus } from '../api/aftersales';

type Filters = { merchantId: string; storeId: string; status: string; orderId: string };
export default function AfterSaleListPage({ client, onAuthLost }: { client: AfterSaleClient; onAuthLost: () => void }) {
  const [params, setParams] = useSearchParams();
  const initial: Filters = { merchantId: params.get('merchantId') ?? '', storeId: params.get('storeId') ?? '', status: '', orderId: '' };
  const [filters, setFilters] = useState(initial);
  const [applied, setApplied] = useState<Filters>();
  const [result, setResult] = useState<CasePage>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);
  useEffect(() => {
    if (validId(initial.merchantId) && validId(initial.storeId)) void query(initial, 1);
    return () => { sequence.current++; };
    // Only the explicit submit selects a new store; partial input never reads.
  }, []);
  async function query(next: Filters, page: number) {
    const call = ++sequence.current;
    setResult(undefined);
    setError('');
    if (!validId(next.merchantId) || !validId(next.storeId) || next.orderId !== '' && !validId(next.orderId)) { setError('请填写有效的商家编号、门店编号及可选订单编号。'); setLoading(false); return; }
    if (applied && (next.merchantId !== applied.merchantId || next.storeId !== applied.storeId)) client.changeScope();
    setApplied({ ...next });
    setParams({ merchantId: next.merchantId, storeId: next.storeId }, { replace: true });
    setLoading(true);
    try {
      const data = await client.list({ merchantId: next.merchantId, storeId: next.storeId, page, pageSize: 20, ...(next.status ? { status: next.status as CaseStatus } : {}), ...(next.orderId ? { orderId: next.orderId } : {}) });
      if (call !== sequence.current) return;
      setResult(data);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof AfterSaleFailure && ['UNAUTHENTICATED', 'FORBIDDEN'].includes(caught.code)) { onAuthLost(); return; }
      if (caught instanceof AfterSaleFailure && caught.code === 'STALE_CONTEXT') return;
      setError(caught instanceof AfterSaleFailure ? `售后查询失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '售后查询失败，请重试。');
    } finally { if (call === sequence.current) setLoading(false); }
  }
  function patch(key: keyof Filters, value: string) {
    setFilters(current => ({ ...current, [key]: value }));
    if (key === 'merchantId' || key === 'storeId') {
      // Hide the previous store immediately, including during partially typed changes.
      sequence.current++; setResult(undefined); setLoading(false); setError('');
      client.changeScope();
    }
  }
  const pages = result ? Math.min(10000, Math.max(1, Math.ceil(result.total / result.pageSize))) : 1;
  return <main className="aftersale-page">
    <h1>售后工单</h1>
    <p className="hint">指定商家与门店后查询当前获权范围。输入编号仅缩小查询，服务端会复核门店归属及资源权限。</p>
    <form className="filters" onSubmit={event => { event.preventDefault(); void query(filters, 1); }}>
      <label>商家编号<input inputMode="numeric" value={filters.merchantId} onChange={event => patch('merchantId', event.target.value)} /></label>
      <label>门店编号<input inputMode="numeric" value={filters.storeId} onChange={event => patch('storeId', event.target.value)} /></label>
      <label>工单状态<select value={filters.status} onChange={event => patch('status', event.target.value)}><option value="">全部</option>{CASE_STATUSES.map(value => <option key={value} value={value}>{caseStatusLabel(value)}</option>)}</select></label>
      <label>订单编号<input inputMode="numeric" value={filters.orderId} onChange={event => patch('orderId', event.target.value)} /></label>
      <button disabled={loading}>查询售后</button>
    </form>
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在查询售后工单</p>}
    {!applied && <p>请先指定商家和门店。</p>}
    {result && <>
      <p>商家 {applied?.merchantId} · 门店 {applied?.storeId}</p>
      {result.items.length === 0 ? <p>该门店没有符合条件的售后工单。</p> : <div className="table-scroll"><table>
        <thead><tr><th>工单编号</th><th>订单编号</th><th>状态</th><th>问题 / 诉求代码</th><th>诉求金额</th><th>创建时间（UTC）</th><th>申请截止（UTC）</th><th>操作</th></tr></thead>
        <tbody>{result.items.map(item => <tr key={item.afterSaleId}><td>{item.afterSaleId}</td><td>{item.orderId}</td><td>{caseStatusLabel(item.status)}</td><td>{item.typeCode} / {item.demandCode}</td><td>{item.requestedAmount ?? '未指定'}</td><td>{item.createdAt}</td><td>{item.deadline}</td><td><Link to={`/aftersales/${item.afterSaleId}?merchantId=${item.merchantId}&storeId=${item.storeId}`}>查看工单</Link></td></tr>)}</tbody>
      </table></div>}
      <nav className="pager" aria-label="售后分页"><button disabled={loading || result.page <= 1} onClick={() => applied && void query(applied, result.page - 1)}>上一页</button><span>第 {result.page} / {pages} 页 · 共 {result.total} 条</span><button disabled={loading || result.page >= pages} onClick={() => applied && void query(applied, result.page + 1)}>下一页</button></nav>
    </>}
  </main>;
}
