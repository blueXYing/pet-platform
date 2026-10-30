import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, Select } from 'antd';
import type { TableColumnsType } from 'antd';
import { appealStatusLabel, APPEAL_STATUSES, ReviewAppealFailure, validId, type AppealPage, type AppealStatus, type AppealSummary, type ReviewAppealClient } from '../api/reviewAppeals';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

const FAILURE_TEXT: Record<string, string> = {
  UNAUTHENTICATED: '登录已失效',
  FORBIDDEN: '无权访问评价申诉',
  INVALID_ARGUMENT: '筛选条件不合法',
  INVALID_RESPONSE: '服务返回异常',
  DEPENDENCY_UNAVAILABLE: '申诉数据暂时不可用',
};

/** Contract56 admin list: global appeal queue with the status filter; single ops reader. */
export default function ReviewAppealListPage({ client, canDecide, onAuthLost }: { client: ReviewAppealClient; canDecide: boolean; onAuthLost: () => void }) {
  const [status, setStatus] = useState<AppealStatus | ''>('');
  const [result, setResult] = useState<AppealPage>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const sequence = useRef(0);
  useEffect(() => { void query(1); return () => { sequence.current++; }; }, []);
  async function query(page: number, nextStatus: AppealStatus | '' = status) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.list({ page, pageSize: 20, ...(nextStatus ? { status: nextStatus } : {}) });
      if (call !== sequence.current) return;
      setResult(data);
      setLoading(false);
    } catch (caught) {
      if (call !== sequence.current) return;
      setLoading(false);
      if (caught instanceof ReviewAppealFailure && ['UNAUTHENTICATED', 'STALE_CONTEXT'].includes(caught.code)) { onAuthLost(); return; }
      setError(caught instanceof ReviewAppealFailure ? FAILURE_TEXT[caught.code] ?? `查询失败（${caught.code}）` : '查询失败');
    }
  }
  function patch(next: AppealStatus | '') { setStatus(next); void query(1, next); }
  const goto = (nextPage: number) => void query(nextPage);
  const columns: TableColumnsType<AppealSummary> = [
    { title: '申诉编号', render: (_, item) => validId(item.appealId) ? item.appealId : item.appealId },
    { title: '评价编号', dataIndex: 'reviewId' },
    { title: '订单编号', dataIndex: 'orderId' },
    { title: '商家/门店', render: (_, item) => `${item.merchantId} / ${item.storeId}` },
    { title: '状态', render: (_, item) => <StatusTag label={appealStatusLabel(item.status)} status={item.status} /> },
    { title: '提交时间', render: (_, item) => item.createdAt.slice(0, 16).replace('T', ' ') },
    { title: '裁决时间', render: (_, item) => item.decidedAt ? item.decidedAt.slice(0, 16).replace('T', ' ') : '—' },
    { title: '', render: (_, item) => <Link to={`/review-appeals/${item.appealId}`}>打开</Link> },
  ];
  return <PageContainer
    title="评价申诉"
    hint={<>评价治理：每条评价最多申诉一次；裁决 APPROVED=评价隐藏、REJECTED=维持展示，均不可改判。{canDecide ? '单人直接裁决，无双人审批。' : '当前账号仅可查看。'}</>}>
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); void goto(1); }}>
      <label className="filter-item">状态
        <Select<AppealStatus | ''> style={{ width: 160 }} aria-label="状态" value={status} onChange={patch}
          options={[{ value: '', label: '全部' }, ...APPEAL_STATUSES.map(item => ({ value: item, label: appealStatusLabel(item) }))]} />
      </label>
      <Button type="primary" htmlType="submit">查询</Button>
    </form>
    {loading && <InlineStatus>加载中</InlineStatus>}
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {result && !loading && (result.items.length === 0
      ? <p>没有符合条件的申诉。</p>
      : <DataTable
        columns={columns}
        rows={result.items}
        rowKey={item => item.appealId}
        page={result.page}
        pageSize={result.pageSize}
        total={result.total}
        onPageChange={page => goto(page)}
      />)}
  </PageContainer>;
}
