import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { RequestFailure } from '../request';
import { MERCHANT_TYPES, merchantTypeLabel, statusLabel, verificationLabel, type ApplicationStatus, type ApplicationSummary, type ListFilters } from '../api/merchantApplications';

type Client = ReturnType<typeof import('../api/merchantApplications').createMerchantApplicationClient>;
type FilterState = { status: string; merchantTypeCode: string; cityCode: string; keyword: string };

const FAILURE_TEXT: Record<string, string> = {
  UNAUTHENTICATED: '登录已失效',
  FORBIDDEN: '无权访问审核列表',
  INVALID_ARGUMENT: '筛选条件不合法',
  DEPENDENCY_UNAVAILABLE: '审核数据暂时不可用',
};

export default function ReviewListPage({ client, canDecide, onAuthLost }: { client: Client; canDecide: boolean; onAuthLost: () => void }) {
  const [filters, setFilters] = useState<FilterState>({ status: '', merchantTypeCode: '', cityCode: '', keyword: '' });
  const [result, setResult] = useState<{ items: ApplicationSummary[]; page: number; pageSize: number; total: number }>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const query = useCallback((next: FilterState & { page?: number; pageSize?: number }) => {
    setLoading(true);
    setError('');
    const clean: ListFilters = {
      page: next.page,
      pageSize: next.pageSize,
      status: (next.status || undefined) as ApplicationStatus | undefined,
      merchantTypeCode: next.merchantTypeCode || undefined,
      cityCode: next.cityCode || undefined,
      keyword: next.keyword || undefined,
    };
    client.list(clean)
      .then(data => { setResult(data); setLoading(false); })
      .catch((caught: unknown) => {
        setLoading(false);
        if (caught instanceof RequestFailure && (caught.code === 'UNAUTHENTICATED' || caught.code === 'STALE_CONTEXT')) { onAuthLost(); return; }
        setError(caught instanceof RequestFailure ? FAILURE_TEXT[caught.code] ?? `查询失败（${caught.code}）` : '查询失败');
      });
  }, [client, onAuthLost]);

  useEffect(() => { query({ ...filters, page: 1, pageSize: 20 }); /* initial load */ }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const totalPages = result ? Math.max(1, Math.ceil(result.total / result.pageSize)) : 1;
  const page = result?.page ?? 1;
  const goto = (nextPage: number) => query({ ...filters, page: nextPage, pageSize: result?.pageSize ?? 20 });
  return <main>
    <h1>商家申请审核</h1>
    <form className="filters" onSubmit={event => { event.preventDefault(); void goto(1); }}>
      <label>状态
        <select value={filters.status} onChange={event => setFilters({ ...filters, status: event.target.value })}>
          <option value="">全部</option>
          <option value="REVIEWING">审核中</option>
          <option value="APPROVED">已通过</option>
          <option value="REJECTED">未通过</option>
        </select>
      </label>
      <label>商家类型
        <select value={filters.merchantTypeCode ?? ''} onChange={event => setFilters({ ...filters, merchantTypeCode: event.target.value })}>
          <option value="">全部</option>
          {MERCHANT_TYPES.map(type => <option key={type.code} value={type.code}>{type.label}</option>)}
        </select>
      </label>
      <label>城市编码<input value={filters.cityCode ?? ''} placeholder="如 CHENGDU" onChange={event => setFilters({ ...filters, cityCode: event.target.value })} /></label>
      <label>关键词<input value={filters.keyword ?? ''} placeholder="商家名称/编号" onChange={event => setFilters({ ...filters, keyword: event.target.value })} /></label>
      <button type="submit">查询</button>
    </form>
    {canDecide ? <p className="hint">领取任务后审核；单人直接裁决，无双人审批。</p> : <p className="hint">当前账号仅可查看。</p>}
    {loading && <p role="status">加载中</p>}
    {error && <p role="alert">{error}</p>}
    {result && !loading && (result.items.length === 0
      ? <p>没有符合条件的申请。</p>
      : <table>
        <thead><tr><th>申请编号</th><th>商家名称</th><th>类型</th><th>城市</th><th>状态</th><th>核验</th><th>提交时间</th><th></th></tr></thead>
        <tbody>
          {result.items.map(item => <tr key={item.applicationId}>
            <td>{item.applicationNo}</td>
            <td>{item.merchantName}</td>
            <td>{merchantTypeLabel(item.merchantTypeCode)}</td>
            <td>{item.cityCode}</td>
            <td>{statusLabel(item.status)}</td>
            <td>{verificationLabel(item.subjectVerificationStatus)}</td>
            <td>{item.submittedAt}</td>
            <td><Link to={`/merchant-applications/${item.applicationId}`}>打开</Link></td>
          </tr>)}
        </tbody>
      </table>)}
    {result && !loading && <nav className="pager" aria-label="分页">
      <button disabled={page <= 1} onClick={() => goto(page - 1)}>上一页</button>
      <span>第 {page} / {totalPages} 页 · 共 {result.total} 条</span>
      <button disabled={page >= totalPages} onClick={() => goto(page + 1)}>下一页</button>
    </nav>}
  </main>;
}
