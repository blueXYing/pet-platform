import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, Input, Select } from 'antd';
import type { TableColumnsType } from 'antd';
import { SERVICE_STATUSES, ServiceReviewFailure, serviceStatusLabel, type ServicePage, type ServiceReviewClient, type ServiceStatus, type ServiceSummary } from '../api/serviceReview';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

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
  const columns: TableColumnsType<ServiceSummary> = [
    { title: '服务编号', dataIndex: 'serviceId' },
    { title: '服务名称', dataIndex: 'serviceName' },
    { title: '类目', render: (_, item) => item.categoryName ?? item.categoryId },
    { title: '状态', render: (_, item) => serviceStatusLabel(item.status) },
    { title: '价格', render: (_, item) => item.price ?? '—' },
    { title: '提交轮次', dataIndex: 'submissionNo' },
    { title: '提交（UTC）', dataIndex: 'submittedAt' },
    { title: 'SLA 剩余（分钟）', render: (_, item) => item.slaRemainingMinutes ?? '—' },
    { title: '驳回次数', dataIndex: 'rejectCount' },
    { title: '操作', render: (_, item) => <Link to={`/services/${item.serviceId}`}>审核</Link> },
  ];
  return <PageContainer
    title="服务审核"
    hint="服务上/下架审核与强制下架。运营不代商家新增/编辑服务；审核与下架分别鉴权。">
    <form className="filters" onSubmit={event => { event.preventDefault(); if (valid) void query(1); }}>
      <label className="filter-item">状态
        <Select style={{ width: 160 }} value={status} onChange={value => { const next = value as ServiceStatus | ''; setStatus(next); void query(1, next); }}
          options={[{ value: '', label: '全部' }, ...SERVICE_STATUSES.map(value => ({ value, label: serviceStatusLabel(value) }))]} />
      </label>
      <label className="filter-item">商家编号<Input style={{ width: 160 }} inputMode="numeric" value={merchantId} onChange={event => setMerchantId(event.target.value)} /></label>
      <Button type="primary" htmlType="submit" disabled={loading || !valid}>查询服务</Button>
    </form>
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在查询服务</InlineStatus>}
    {result && <DataTable
      columns={columns}
      rows={result.items}
      rowKey={item => item.serviceId}
      loading={loading}
      page={result.page}
      pageSize={result.pageSize}
      total={result.total}
      onPageChange={page => void query(page)}
    />}
  </PageContainer>;
}
