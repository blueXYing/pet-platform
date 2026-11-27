import { useEffect, useRef, useState } from 'react';
import { Button, Drawer, Input, Space } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  describeMerchantCouponFailure, isMerchantCouponStale,
  type MerchantCouponAdminClient, type MerchantCouponDetail, type MerchantCouponItem,
} from '../api/merchantCouponAdmin';
import { RequestFailure } from '../request';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, FactList, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

// 运营商家券审核（契约 79 §4）：列表 + 详情抽屉 + 通过/驳回/暂停/结束。
// 运营不可修改活动的任何字段（含金额）——活动不合格只能驳回由商家调整
// （§2.4：PENDING_REVIEW 后商家不可编辑）。列表与抽屉里的金额、统计均为
// 后端事实数据，金额按两位小数字符串原样展示，页面不做任何推导。

const PAGE_SIZE = 20;

const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', PENDING_REVIEW: '待审核', REJECTED: '已驳回', CANCELED: '已取消',
  SCHEDULED: '待开始', ACTIVE: '进行中', PAUSED: '已暂停', ENDED: '已结束',
};

// 筛选 chips：默认「待审核」，可切换全部状态（含 DRAFT/CANCELED）。
const STATUS_FILTERS: [string, string][] = [
  ['PENDING_REVIEW', '待审核'],
  ['', '全部'],
  ['DRAFT', '草稿'],
  ['REJECTED', '已驳回'],
  ['CANCELED', '已取消'],
  ['SCHEDULED', '待开始'],
  ['ACTIVE', '进行中'],
  ['PAUSED', '已暂停'],
  ['ENDED', '已结束'],
];

const localMinute = (value: string) => {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
};

// 满 X 减 Y / 无门槛减 Y。金额是后端返回的两位小数字符串，原样拼接。
const discountText = (item: { couponType: string; amountOff: string; thresholdAmount: string | null }) =>
  item.couponType === 'THRESHOLD' && item.thresholdAmount
    ? `满${item.thresholdAmount}减${item.amountOff}`
    : `无门槛减${item.amountOff}`;

export default function MerchantCouponPage({ client, canReview, onAuthLost }: {
  client: MerchantCouponAdminClient;
  canReview: boolean;
  onAuthLost: () => void;
}) {
  const [statusFilter, setStatusFilter] = useState('PENDING_REVIEW');
  const [merchantId, setMerchantId] = useState('');
  const [result, setResult] = useState<{ page: number; pageSize: number; total: number; items: MerchantCouponItem[] }>();
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const sequence = useRef(0);
  const [detailId, setDetailId] = useState<string>();
  const [detail, setDetail] = useState<MerchantCouponDetail>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const detailSequence = useRef(0);

  useEffect(() => { void load(1); return () => { sequence.current++; detailSequence.current++; }; }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function load(page: number, nextStatus = statusFilter, nextMerchantId = merchantId) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.list(nextStatus || undefined, nextMerchantId.trim() || undefined, page, PAGE_SIZE);
      if (call !== sequence.current) return;
      setResult(data);
      setLoading(false);
    } catch (caught) {
      if (call !== sequence.current) return;
      setLoading(false);
      authOrError(caught, '读取商家券活动列表失败');
    }
  }

  async function loadDetail(id: string) {
    const call = ++detailSequence.current;
    setDetailLoading(true);
    setDetailError('');
    try {
      const data = await client.get(id);
      if (call !== detailSequence.current) return;
      setDetail(data);
      setDetailLoading(false);
    } catch (caught) {
      if (call !== detailSequence.current) return;
      setDetail(undefined);
      setDetailLoading(false);
      if (caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      setDetailError(describeMerchantCouponFailure(caught, '读取活动详情失败'));
    }
  }

  function authOrError(caught: unknown, fallback: string) {
    if (caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
    setError(describeMerchantCouponFailure(caught, fallback));
  }

  // 操作后刷新当前页；版本/状态冲突的错误文案承诺了刷新，同样照做；抽屉开着时一并刷新详情。
  async function run(action: () => Promise<unknown>, ok: string) {
    setBusy(true); setError(''); setNotice('');
    try {
      await action();
      setNotice(ok);
      await load(result?.page ?? 1);
      if (detailId) void loadDetail(detailId);
    } catch (caught) {
      authOrError(caught, '操作失败');
      if (isMerchantCouponStale(caught)) void load(result?.page ?? 1);
    } finally {
      setBusy(false);
    }
  }

  function reject(item: MerchantCouponItem) {
    const reason = window.prompt('驳回原因（1-500 字，必填）')?.trim();
    if (!reason) return;
    if (reason.length > 500) { setError('驳回原因不能超过 500 字，请精简后重试'); return; }
    void run(() => client.reject(item.campaignId, item.version, reason), '商家券活动已驳回');
  }

  function openDetail(item: MerchantCouponItem) {
    setDetailId(item.campaignId);
    setDetail(undefined);
    void loadDetail(item.campaignId);
  }

  function closeDetail() {
    detailSequence.current++;
    setDetailId(undefined);
    setDetail(undefined);
    setDetailLoading(false);
    setDetailError('');
  }

  const columns: TableColumnsType<MerchantCouponItem> = [
    {
      title: '活动名称', width: 220, render: (_, item) => <>
        <span title={item.name} style={{ display: 'inline-block', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', verticalAlign: 'bottom' }}>{item.name}</span>
        {item.description && <><br /><small title={item.description} style={{ display: 'inline-block', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', verticalAlign: 'bottom' }}>{item.description}</small></>}
        <br /><small>ID {item.campaignId}</small>
      </>,
    },
    {
      title: '商家 / 门店', width: 170, render: (_, item) => <>
        {item.merchantName}
        <br /><small>{item.storeName ?? '—'}</small>
      </>,
    },
    { title: '优惠', width: 150, render: (_, item) => <>{discountText(item)}<br /><small>{item.typeLabel}</small></> },
    // issuedAmount = 已领取面额（占用预算）；budgetUsedAmount = 已核销面额（商家实际出资），见详情。
    { title: '预算（已领 / 预算）', width: 150, render: (_, item) => <>¥{item.issuedAmount} / ¥{item.budgetLimit}</> },
    { title: '领取数 / 总量', width: 110, render: (_, item) => `${item.claimedCount} / ${item.totalLimit}` },
    { title: '状态', width: 90, render: (_, item) => <StatusTag label={STATUS_TEXT[item.status] ?? item.status} status={item.status} /> },
    {
      title: '有效期（本地时间）', width: 170, render: (_, item) => <>
        {localMinute(item.startAt)}
        <br />至 {localMinute(item.endAt)}
      </>,
    },
    {
      title: '操作', width: 220, fixed: 'right', render: (_, item) => <Space size={4} wrap>
        <Button type="link" size="small" style={{ padding: 0 }} onClick={() => openDetail(item)}>详情</Button>
        {canReview && item.status === 'PENDING_REVIEW' && <>
          <Button type="primary" size="small" disabled={busy} onClick={() => void run(() => client.approve(item.campaignId, item.version), '商家券活动已通过')}>通过</Button>
          <Button danger size="small" disabled={busy} onClick={() => reject(item)}>驳回</Button>
        </>}
        {canReview && (item.status === 'SCHEDULED' || item.status === 'ACTIVE') && <>
          <Button size="small" disabled={busy} onClick={() => void run(() => client.pause(item.campaignId, item.version), '商家券活动已暂停')}>暂停</Button>
        </>}
        {canReview && (item.status === 'SCHEDULED' || item.status === 'ACTIVE' || item.status === 'PAUSED') && <>
          <Button danger size="small" disabled={busy} onClick={() => void run(() => client.end(item.campaignId, item.version), '商家券活动已结束')}>结束</Button>
        </>}
      </Space>,
    },
  ];

  const detailFacts = detail ? [
    { label: '活动名称', value: detail.name },
    { label: '状态', value: <StatusTag label={STATUS_TEXT[detail.status] ?? detail.status} status={detail.status} /> },
    { label: '商家', value: <>{detail.merchantName}（ID {detail.merchantId}）</> },
    { label: '门店', value: detail.storeName ?? '—' },
    { label: '优惠', value: <>{detail.typeLabel} · {discountText(detail)}</> },
    { label: '面额', value: `¥${detail.amountOff}` },
    { label: '使用门槛', value: detail.thresholdAmount ? `满 ¥${detail.thresholdAmount} 可用` : '无门槛' },
    { label: '预算（已领 / 上限）', value: `¥${detail.issuedAmount} / ¥${detail.budgetLimit}` },
    { label: '已核销面额（商家实际出资）', value: `¥${detail.budgetUsedAmount}` },
    { label: '领取数 / 总量', value: `${detail.claimedCount} / ${detail.totalLimit} 张` },
    { label: '每人限领', value: `${detail.perUserLimit} 张` },
    { label: '核销数', value: `${detail.usedCount} 张` },
    { label: '带动订单数', value: `${detail.drivenOrderCount} 单` },
    { label: '有效期（本地时间）', value: <>{localMinute(detail.startAt)} 至 {localMinute(detail.endAt)}</> },
    { label: '提交时间', value: detail.submittedAt ? localMinute(detail.submittedAt) : '—' },
    { label: '审核时间', value: detail.reviewedAt ? localMinute(detail.reviewedAt) : '—' },
    { label: '驳回原因', value: detail.status === 'REJECTED' && detail.rejectReason ? detail.rejectReason : '—' },
    { label: '活动说明', value: detail.description ?? '—' },
    { label: '版本', value: `v${detail.version}` },
  ] : [];

  return <PageContainer
    title="商家券审核"
    hint="商家提交的自建优惠券活动（商家出资的无门槛券/满减券）：待审核可「通过 / 驳回」（驳回原因必填 1-500 字）；待开始 / 进行中可「暂停」；待开始 / 进行中 / 已暂停可「结束」。运营不可修改活动任何字段——活动不合格请驳回，由商家调整后重新提交。暂停 / 结束不作废已发放的券。列表与详情中的金额、领取 / 核销 / 订单统计均为后端事实数据。">
    <nav className="filter-bar" aria-label="商家券活动筛选">
      <Space size={8} wrap>
        {STATUS_FILTERS.map(([value, label]) => <Button
          key={value || 'ALL'}
          disabled={statusFilter === value}
          onClick={() => { setStatusFilter(value); void load(1, value); }}
        >{label}</Button>)}
        <Input.Search
          aria-label="商家 ID 筛选"
          placeholder="商家 ID（可选）"
          allowClear
          style={{ width: 200 }}
          onSearch={value => { setMerchantId(value); void load(1, statusFilter, value); }}
        />
      </Space>
      {notice && <InlineStatus>{notice}</InlineStatus>}
      {error && <ErrorAlert>{error}</ErrorAlert>}
    </nav>
    {loading && <InlineStatus>正在加载商家券活动列表</InlineStatus>}
    <DataTable
      tableLayout="fixed"
      columns={columns}
      rows={result?.items}
      rowKey={item => item.campaignId}
      loading={loading}
      page={result?.page}
      pageSize={result?.pageSize}
      total={result?.total}
      onPageChange={page => void load(page)}
    />

    <Drawer
      rootClassName="merchant-coupon-drawer"
      open={detailId !== undefined}
      onClose={closeDetail}
      width={720}
      destroyOnHidden
      title={detail ? `商家券活动详情：${detail.name}` : '商家券活动详情'}
    >
      {detailLoading && <InlineStatus>正在加载活动详情</InlineStatus>}
      {detailError && <>
        <ErrorAlert>{detailError}</ErrorAlert>
        <Button size="small" onClick={() => detailId && void loadDetail(detailId)}>重试</Button>
      </>}
      {detail && <FactList column={2} items={detailFacts} />}
    </Drawer>
  </PageContainer>;
}
