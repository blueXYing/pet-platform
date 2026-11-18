import { useEffect, useRef, useState } from 'react';
import { Button } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  describeFlashPriceFailure, isFlashPriceStale,
  type FlashPriceItem, type ServiceFlashPriceAdminClient,
} from '../api/serviceFlashPriceAdmin';
import { RequestFailure } from '../request';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

// 运营秒杀审核（契约 77 §4）：列表 + 通过/驳回/暂停/恢复/结束。
// 运营不可改价——价格不合格只能驳回由商家调整（§2.2）。列出的统计字段
// （已售/总量/参与人数）均为后端事实数据，页面不做任何推导。

const PAGE_SIZE = 20;

const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', PENDING_REVIEW: '待审核', SCHEDULED: '待开始', ACTIVE: '进行中',
  PAUSED: '已暂停', ENDED: '已结束', REJECTED: '已驳回', CANCELED: '已取消',
};

// 筛选 chips：全部 + 六个运营可见的审核/生效状态。DRAFT/CANCELED 只能经「全部」看到。
const STATUS_FILTERS: [string, string][] = [
  ['', '全部'],
  ['PENDING_REVIEW', '待审核'],
  ['SCHEDULED', '待开始'],
  ['ACTIVE', '进行中'],
  ['PAUSED', '已暂停'],
  ['ENDED', '已结束'],
  ['REJECTED', '已驳回'],
];

const localMinute = (value: string) => {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
};

export default function ServiceFlashPricePage({ client, canReview, onAuthLost }: {
  client: ServiceFlashPriceAdminClient;
  canReview: boolean;
  onAuthLost: () => void;
}) {
  const [statusFilter, setStatusFilter] = useState('');
  const [result, setResult] = useState<{ page: number; pageSize: number; total: number; items: FlashPriceItem[] }>();
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const sequence = useRef(0);

  useEffect(() => { void load(1); return () => { sequence.current++; }; }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function load(page: number, nextStatus = statusFilter) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.list(nextStatus || undefined, page, PAGE_SIZE);
      if (call !== sequence.current) return;
      setResult(data);
      setLoading(false);
    } catch (caught) {
      if (call !== sequence.current) return;
      setLoading(false);
      authOrError(caught, '读取秒杀列表失败');
    }
  }

  function authOrError(caught: unknown, fallback: string) {
    if (caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
    setError(describeFlashPriceFailure(caught, fallback));
  }

  // 操作后刷新当前页；版本/状态冲突的错误文案承诺了刷新，同样照做。
  async function run(action: () => Promise<unknown>, ok: string) {
    setBusy(true); setError(''); setNotice('');
    try {
      await action();
      setNotice(ok);
      await load(result?.page ?? 1);
    } catch (caught) {
      authOrError(caught, '操作失败');
      if (isFlashPriceStale(caught)) void load(result?.page ?? 1);
    } finally {
      setBusy(false);
    }
  }

  function reject(item: FlashPriceItem) {
    const reason = window.prompt('驳回原因（1-500 字）')?.trim();
    if (!reason) return;
    if (reason.length > 500) { setError('驳回原因不能超过 500 字，请精简后重试'); return; }
    void run(() => client.reject(item.flashPriceId, item.version, reason), '秒杀场次已驳回');
  }

  const columns: TableColumnsType<FlashPriceItem> = [
    { title: '服务 / 门店', render: (_, item) => <>{item.serviceName}<br /><small>{item.storeName}</small></> },
    {
      title: '标题', render: (_, item) => item.description
        ? <>{item.title}<br /><span title={item.description} style={{ display: 'inline-block', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', verticalAlign: 'bottom' }}>{item.description}</span></>
        : item.title,
    },
    {
      title: '折扣 / 闪价',
      render: (_, item) => <>{item.discountRate}折<br />¥{item.flashPrice} <s>¥{item.listPriceSnapshot}</s></>,
    },
    { title: '窗口（本地时间）', render: (_, item) => <>{localMinute(item.startAt)}<br />至 {localMinute(item.endAt)}</> },
    { title: '已售 / 总量', render: (_, item) => `${item.soldCount} / ${item.totalQuota ?? '不限'}` },
    { title: '每人限领', render: (_, item) => item.perUserLimit ?? '不限' },
    { title: '参与人数', dataIndex: 'participantCount' },
    { title: '状态', render: (_, item) => <StatusTag label={STATUS_TEXT[item.status] ?? item.status} status={item.status} /> },
    { title: '提交时间', render: (_, item) => localMinute(item.createdAt) },
    {
      title: '驳回原因', render: (_, item) => item.status === 'REJECTED' && item.rejectReason
        ? <span title={item.rejectReason} style={{ display: 'inline-block', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', verticalAlign: 'bottom' }}>{item.rejectReason}</span>
        : '—',
    },
    {
      title: '操作', render: (_, item) => canReview && <>
        {item.status === 'PENDING_REVIEW' && <>
          <Button type="primary" disabled={busy} onClick={() => void run(() => client.approve(item.flashPriceId, item.version), '秒杀场次已通过')}>通过</Button>{' '}
          <Button danger disabled={busy} onClick={() => reject(item)}>驳回</Button>
        </>}
        {item.status === 'PAUSED' && <>
          <Button type="primary" disabled={busy} onClick={() => void run(() => client.approve(item.flashPriceId, item.version), '秒杀场次已恢复')}>恢复</Button>{' '}
        </>}
        {(item.status === 'SCHEDULED' || item.status === 'ACTIVE') && <>
          <Button disabled={busy} onClick={() => void run(() => client.pause(item.flashPriceId, item.version), '秒杀场次已暂停')}>暂停</Button>{' '}
        </>}
        {(item.status === 'SCHEDULED' || item.status === 'ACTIVE' || item.status === 'PAUSED') && <>
          <Button danger disabled={busy} onClick={() => void run(() => client.end(item.flashPriceId, item.version), '秒杀场次已结束')}>结束</Button>
        </>}
      </>,
    },
  ];

  return <PageContainer
    title="秒杀审核"
    hint="商家提交的服务限时秒杀（P2）：待审核场次可通过/驳回；待开始/进行中可暂停、结束；已暂停经「恢复」走通过动作回到原状态。运营不可改价——价格或窗口不合格请驳回，由商家调整后重新提交。列表中的已售/总量/参与人数均为后端事实数据。">
    <nav className="filter-bar" aria-label="状态筛选">
      {STATUS_FILTERS.map(([value, label]) => <Button
        key={value || 'ALL'}
        disabled={statusFilter === value}
        onClick={() => { setStatusFilter(value); void load(1, value); }}
      >{label}</Button>)}
      {notice && <InlineStatus>{notice}</InlineStatus>}
      {error && <ErrorAlert>{error}</ErrorAlert>}
    </nav>
    {loading && <InlineStatus>正在加载秒杀列表</InlineStatus>}
    {result && <DataTable
      columns={columns}
      rows={result.items}
      rowKey={item => item.flashPriceId}
      loading={loading}
      page={result.page}
      pageSize={result.pageSize}
      total={result.total}
      onPageChange={page => void load(page)}
    />}
  </PageContainer>;
}
