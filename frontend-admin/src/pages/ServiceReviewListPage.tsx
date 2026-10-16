import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { SERVICE_STATUSES, ServiceReviewFailure, serviceStatusLabel, type ServicePage, type ServiceReviewClient, type ServiceStatus } from '../api/serviceReview';

/** 运营 PRD 6.2.4 服务项目审核与管理：审核列表（24h SLA 剩余、驳回次数）。 */
export default function ServiceReviewListPage({ client, onAuthLost }: { client: ServiceReviewClient; onAuthLost: () => void }) {
  const [status, setStatus] = useState<ServiceStatus | ''>('REVIEWING');
  const [merchantId, setMerchantId] = useState('');
  const [result, setResult] = useState<ServicePage>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const sequence = useRef(0);
  useEffect(() => { void query(1); return () => { sequence.current++; }; }, []);
  async function query(page: number, nextStatus: ServiceStatus | '' = status, nextMerchant = merchantId) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.list(page, {
        ...(nextStatus ? { status: nextStatus } : {}),
        ...(nextMerchant ? { merchantId: nextMerchant } : {}),
      });
      if (call !== sequence.current) return;
      setResult(data);
      setLoading(false);
    } catch (caught) {
      if (call !== sequence.current) return;
      setLoading(false);
      if (caught instanceof ServiceReviewFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof ServiceReviewFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
      if (caught instanceof ServiceReviewFailure && caught.code === 'STALE_CONTEXT') return;
      setError(caught instanceof ServiceReviewFailure ? `服务查询失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '服务查询失败，请重试。');
    }
  }
  const ID = /^[1-9][0-9]{0,18}$/;
  const valid = merchantId === '' || ID.test(merchantId);
  const pages = result ? Math.min(10000, Math.max(1, Math.ceil(result.total / result.pageSize))) : 1;
  return <main className="aftersale-page">
    <h1>服务审核</h1>
    <p className="hint">服务上/下架审核与强制下架。运营不代商家新增/编辑服务；审核与下架分别鉴权。</p>
    <form className="filters" onSubmit={event => { event.preventDefault(); if (valid) void query(1); }}>
      <label>状态
        <select value={status} onChange={event => { const next = event.target.value as ServiceStatus | ''; setStatus(next); void query(1, next); }}>
          <option value="">全部</option>
          {SERVICE_STATUSES.map(value => <option key={value} value={value}>{serviceStatusLabel(value)}</option>)}
        </select>
      </label>
      <label>商家编号<input inputMode="numeric" value={merchantId} onChange={event => setMerchantId(event.target.value)} /></label>
      <button disabled={loading || !valid}>查询服务</button>
    </form>
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在查询服务</p>}
    {result && <>
      <div className="table-scroll"><table>
        <thead><tr><th>服务编号</th><th>服务名称</th><th>类目</th><th>状态</th><th>价格</th><th>提交轮次</th><th>提交（UTC）</th><th>SLA 剩余（分钟）</th><th>驳回次数</th><th>操作</th></tr></thead>
        <tbody>{result.items.map(item => <tr key={item.serviceId}>
          <td>{item.serviceId}</td>
          <td>{item.serviceName}</td>
          <td>{item.categoryName ?? item.categoryId}</td>
          <td>{serviceStatusLabel(item.status)}</td>
          <td>{item.price ?? '—'}</td>
          <td>{item.submissionNo}</td>
          <td>{item.submittedAt}</td>
          <td>{item.slaRemainingMinutes ?? '—'}</td>
          <td>{item.rejectCount}</td>
          <td><Link to={`/services/${item.serviceId}`}>审核</Link></td>
        </tr>)}</tbody>
      </table></div>
      <nav className="pager" aria-label="服务分页">
        <button disabled={loading || result.page <= 1} onClick={() => void query(result.page - 1)}>上一页</button>
        <span>第 {result.page} / {pages} 页 · 共 {result.total} 条</span>
        <button disabled={loading || result.page >= pages} onClick={() => void query(result.page + 1)}>下一页</button>
      </nav>
    </>}
  </main>;
}
