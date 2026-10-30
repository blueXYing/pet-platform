import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, Input, Select } from 'antd';
import type { TableColumnsType } from 'antd';
import { RequestFailure } from '../request';
import { MERCHANT_TYPES, merchantTypeLabel, statusLabel, verificationLabel, type ApplicationStatus, type ApplicationSummary, type ListFilters } from '../api/merchantApplications';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

type Client = ReturnType<typeof import('../api/merchantApplications').createMerchantApplicationClient>;
type FilterState = { status: string; merchantTypeCode: string; cityCode: string; keyword: string };

const FAILURE_TEXT: Record<string, string> = {
  UNAUTHENTICATED: '登录已失效',
  FORBIDDEN: '无权访问审核列表',
  INVALID_ARGUMENT: '筛选条件不合法',
  DEPENDENCY_UNAVAILABLE: '审核数据暂时不可用',
};

const STATUS_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'REVIEWING', label: '审核中' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '未通过' },
];

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

  const goto = (nextPage: number) => query({ ...filters, page: nextPage, pageSize: result?.pageSize ?? 20 });
  const columns: TableColumnsType<ApplicationSummary> = [
    { title: '申请编号', dataIndex: 'applicationNo' },
    { title: '商家名称', dataIndex: 'merchantName' },
    { title: '类型', render: (_, item) => merchantTypeLabel(item.merchantTypeCode) },
    { title: '城市', dataIndex: 'cityCode' },
    { title: '状态', render: (_, item) => statusLabel(item.status) },
    { title: '核验', render: (_, item) => verificationLabel(item.subjectVerificationStatus) },
    { title: '提交时间', dataIndex: 'submittedAt' },
    { title: '', render: (_, item) => <Link to={`/merchant-applications/${item.applicationId}`}>打开</Link> },
  ];
  return <PageContainer title="商家申请审核">
    <form className="filters" onSubmit={event => { event.preventDefault(); void goto(1); }}>
      <label className="filter-item">状态
        <Select style={{ width: 160 }} value={filters.status} onChange={value => setFilters({ ...filters, status: value })} options={STATUS_OPTIONS} />
      </label>
      <label className="filter-item">商家类型
        <Select style={{ width: 160 }} value={filters.merchantTypeCode ?? ''} onChange={value => setFilters({ ...filters, merchantTypeCode: value })}
          options={[{ value: '', label: '全部' }, ...MERCHANT_TYPES.map(type => ({ value: type.code, label: type.label }))]} />
      </label>
      <label className="filter-item">城市编码<Input style={{ width: 160 }} value={filters.cityCode ?? ''} placeholder="如 CHENGDU" onChange={event => setFilters({ ...filters, cityCode: event.target.value })} /></label>
      <label className="filter-item">关键词<Input style={{ width: 200 }} value={filters.keyword ?? ''} placeholder="商家名称/编号" onChange={event => setFilters({ ...filters, keyword: event.target.value })} /></label>
      <Button type="primary" htmlType="submit">查询</Button>
    </form>
    {canDecide ? <p className="hint">领取任务后审核；单人直接裁决，无双人审批。</p> : <p className="hint">当前账号仅可查看。</p>}
    {loading && <InlineStatus>加载中</InlineStatus>}
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {result && !loading && (result.items.length === 0
      ? <p>没有符合条件的申请。</p>
      : <DataTable
        columns={columns}
        rows={result.items}
        rowKey={item => item.applicationId}
        page={result.page}
        pageSize={result.pageSize}
        total={result.total}
        onPageChange={page => goto(page)}
      />)}
  </PageContainer>;
}
