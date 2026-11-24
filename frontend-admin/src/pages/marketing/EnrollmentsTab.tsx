import { useEffect, useState } from 'react';
import { Button, ConfigProvider, Drawer, Empty, Popconfirm, Select, Space, Tag } from 'antd';
import { CheckCircleFilled, ExclamationCircleOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import type { Campaign, Enrollment, MarketingAdminClient } from '../../api/marketingAdmin';
import { DataTable } from '../../ui/DataTable';
import { FactList, StatusTag } from '../../ui/bits';
import {
  DrawerHead, Field, fmtDT, LoadFailed, PAGE_SIZE, renderMaterials, RejectModal, STATUS_TEXT, useCampaignDirectory,
  useCloseOnLeave, usePagedList, type FiltersOf, type TabProps,
} from './shared';

type EnrollmentFilters = FiltersOf<MarketingAdminClient['listEnrollments']>;
const LINK_STYLE = { padding: 0, height: 'auto' } as const;
/** 还可以审核的状态（后端 reviewEnrollment 只接受这两个状态）。 */
const REVIEWABLE = ['SUBMITTED', 'UNDER_REVIEW'];
const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'SUBMITTED', label: '已提交（待我审核）' },
  { value: 'UNDER_REVIEW', label: '审核中' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'WITHDRAWN', label: '已撤回' },
];
const STATUS_GUIDANCE: Record<string, string> = {
  SUBMITTED: '商家已提交报名，等待你审核。',
  UNDER_REVIEW: '报名正在审核中，可以继续处理。',
  APPROVED: '报名已通过，该门店已加入活动。',
  REJECTED: '报名已驳回。商家可以在报名期内修改后重新提交。',
  WITHDRAWN: '商家已撤回这次报名。',
};

/** 通过前自检：对应后端 reviewEnrollment(APPROVE) 的放行条件——活动须已发布、名额未满、门店归属一致。只是提示，最终以后端校验为准。 */
function ApproveReadiness({ enrollment, campaigns }: { enrollment: Enrollment; campaigns: Campaign[] | undefined }) {
  const campaign = campaigns?.find(item => item.campaignId === enrollment.campaignId);
  const rows = campaign
    ? [
      {
        ok: ['SCHEDULED', 'ONLINE'].includes(campaign.status),
        text: ['SCHEDULED', 'ONLINE'].includes(campaign.status)
          ? `所属活动「${campaign.name}」已发布，可以审核通过`
          : `所属活动「${campaign.name}」尚未发布（当前：${STATUS_TEXT[campaign.status] ?? campaign.status}），需先到「活动」分区发布后才能通过报名`,
      },
      {
        ok: true,
        text: campaign.enrollmentQuota ? `活动报名名额 ${campaign.enrollmentQuota} 个，名额已满时无法再通过` : '活动未设置报名名额上限',
      },
    ]
    : [{ ok: true, text: '所属活动的发布状态与剩余名额会在提交时由后端校验。' }];
  return <ul className="mkt-checklist">
    {rows.map(row => <li key={row.text} className={row.ok ? 'is-ok' : 'is-warn'}>
      {row.ok ? <CheckCircleFilled /> : <ExclamationCircleOutlined />}
      <span>{row.text}</span>
    </li>)}
  </ul>;
}

export function EnrollmentsTab({ client, active, canReview, busy, run, authOrError, onAuthLost }: TabProps & { canReview: boolean }) {
  const list = usePagedList<Enrollment, EnrollmentFilters>(
    (filters, page) => client.listEnrollments(filters, page, PAGE_SIZE), authOrError, '读取报名失败');
  const [filters, setFilters] = useState<EnrollmentFilters>({});
  // 只有「商家报名」类活动会有报名。
  const campaigns = useCampaignDirectory(client, active, onAuthLost)?.filter(item => item.participationMode === 'MERCHANT_ENROLL');
  const campaignOptions = (campaigns ?? []).map(item => ({
    value: item.campaignId, label: `${item.name}（${STATUS_TEXT[item.status] ?? item.status}）`,
  }));
  const [viewing, setViewing] = useState<Enrollment>();
  const [rejecting, setRejecting] = useState<Enrollment>();

  useEffect(() => { if (active) void list.ensure({}); }, [active, list.ensure]);
  useCloseOnLeave(active, () => { setViewing(undefined); setRejecting(undefined); });

  function apply(patch: Partial<EnrollmentFilters>) {
    const next: EnrollmentFilters = { ...filters, ...patch };
    setFilters(next);
    void list.load(next, 1);
  }
  const approve = (enrollment: Enrollment) => void run(
    () => client.reviewEnrollment(enrollment.enrollmentId, 'APPROVE', undefined, enrollment.version), '报名已通过',
    async () => { setViewing(undefined); await list.reload(); });

  // 列宽预算合计 ≤ 1000px（见活动分区），操作列固定在右侧；驳回原因单行省略，完整内容悬停可见。
  const columns: TableColumnsType<Enrollment> = [
    { title: '活动', key: 'campaign', width: 160, render: (_, enrollment) => <div className="mkt-entity-text">
      <div className="mkt-entity-name" title={enrollment.campaignTitle}>{enrollment.campaignTitle}</div>
      <div className="mkt-entity-id">ID {enrollment.campaignId}</div>
    </div> },
    { title: '报名门店', key: 'store', width: 160, render: (_, enrollment) => <div className="mkt-entity-text">
      <div className="mkt-entity-name" title={enrollment.storeName ?? undefined}>{enrollment.storeName ?? `门店 ${enrollment.storeId}`}</div>
      <div className="mkt-entity-sub">商家 {enrollment.merchantId}</div>
    </div> },
    { title: '报名优惠', key: 'offer', width: 170, render: (_, enrollment) => <div className="mkt-entity-text">
      <div title={enrollment.offerTitle ?? undefined}>{enrollment.offerTag && <Tag>{enrollment.offerTag}</Tag>}{enrollment.offerTitle ?? '--'}</div>
      <div className="mkt-entity-sub" title={enrollment.offerCopy ?? undefined}>{enrollment.offerCopy}</div>
    </div> },
    { title: '材料', width: 84, render: (_, enrollment) => enrollment.materials ? <Tag color="blue">有材料</Tag> : '--' },
    { title: '状态', width: 130, render: (_, enrollment) => <div className="mkt-stack">
      <StatusTag label={STATUS_TEXT[enrollment.status] ?? enrollment.status} status={enrollment.status} />
      {enrollment.rejectReason && <span className="mkt-entity-sub" title={enrollment.rejectReason}>原因：{enrollment.rejectReason}</span>}
    </div> },
    { title: '提交时间', width: 130, render: (_, enrollment) => <span className="mkt-entity-sub">{fmtDT(enrollment.submittedAt)}</span> },
    { title: '操作', key: 'actions', width: 156, fixed: 'right', render: (_, enrollment) => <Space size={12} wrap>
      <Button type="link" size="small" style={LINK_STYLE} onClick={() => setViewing(enrollment)}>查看</Button>
      {canReview && REVIEWABLE.includes(enrollment.status) && <>
        <Popconfirm title="通过后该门店正式加入本活动并展示其报名优惠。确认通过？" okText="确认通过" cancelText="再想想"
          onConfirm={() => approve(enrollment)}>
          <Button type="link" size="small" style={LINK_STYLE} disabled={busy}>通过</Button>
        </Popconfirm>
        <Button type="link" size="small" danger style={LINK_STYLE} disabled={busy} onClick={() => setRejecting(enrollment)}>驳回</Button>
      </>}
    </Space> },
  ];

  return <>
    <p className="hint">商家在商家端报名参加「商家报名」类活动后在这里审核。建议先点「查看」核对门店提交的优惠与材料，再通过或驳回；驳回须填写原因，商家可在报名期内修改后重新提交。</p>
    <div className="filter-bar" aria-label="商家报名筛选">
      <Field label="状态"><Select aria-label="状态" style={{ width: 180 }} value={filters.status ?? ''} options={STATUS_OPTIONS}
        onChange={value => apply({ status: value || undefined })} /></Field>
      <Field label="活动"><Select aria-label="活动" style={{ width: 280 }} showSearch optionFilterProp="label" allowClear
        placeholder="全部活动" value={filters.campaignId} options={campaignOptions}
        onChange={value => apply({ campaignId: value || undefined })} /></Field>
      <Button disabled={busy || list.loading} onClick={() => void list.reload()}>刷新</Button>
    </div>

    {list.failed && !list.rows ? <LoadFailed onRetry={() => void list.reload()} />
      : <ConfigProvider renderEmpty={() => <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={filters.status || filters.campaignId ? '没有符合当前筛选条件的报名' : '暂无商家报名'} />}>
        <DataTable tableLayout="fixed" columns={columns} rows={list.rows} rowKey={enrollment => enrollment.enrollmentId}
          loading={list.loading} page={list.page} pageSize={PAGE_SIZE} total={list.total}
          onPageChange={page => void list.load(filters, page)} />
      </ConfigProvider>}

    <Drawer rootClassName="mkt-drawer" open={!!viewing} onClose={() => setViewing(undefined)} width={760} destroyOnHidden closable={false}>
      <DrawerHead title={viewing ? (viewing.storeName ?? `门店 ${viewing.storeId}`) : '报名详情'}
        tag={viewing && <StatusTag label={STATUS_TEXT[viewing.status] ?? viewing.status} status={viewing.status} />}
        description={viewing ? STATUS_GUIDANCE[viewing.status] : undefined} onClose={() => setViewing(undefined)} />
      {viewing && <section className="mkt-detail" aria-label="报名详情">
        <FactList column={2} items={[
          { label: '报名 ID', value: viewing.enrollmentId },
          { label: '提交时间', value: fmtDT(viewing.submittedAt) },
          { label: '活动', value: `${viewing.campaignTitle}（${viewing.campaignId}）` },
          { label: '门店', value: `${viewing.storeName ?? '--'}（${viewing.storeId}）` },
          { label: '商家 ID', value: viewing.merchantId },
          { label: '优惠角标', value: viewing.offerTag ?? '--' },
          { label: '优惠标题', value: viewing.offerTitle ?? '--' },
          { label: '优惠说明', value: viewing.offerCopy ?? '--' },
          { label: '审核时间', value: viewing.decidedAt ? fmtDT(viewing.decidedAt) : '--' },
          { label: '审核人', value: viewing.decidedBy ?? '--' },
          { label: '驳回原因', value: viewing.rejectReason ?? '--' },
        ]} />
        <div className="mkt-panel">
          <h4>报名材料</h4>
          {renderMaterials(viewing.materials)}
        </div>
        {canReview && REVIEWABLE.includes(viewing.status) && <div className="mkt-panel">
          <h4>审核</h4>
          <ApproveReadiness enrollment={viewing} campaigns={campaigns} />
          <Space>
            <Popconfirm title="通过后该门店正式加入本活动并展示其报名优惠。确认通过？" okText="确认通过" cancelText="再想想"
              onConfirm={() => approve(viewing)}>
              <Button type="primary" disabled={busy}>通过</Button>
            </Popconfirm>
            <Button danger disabled={busy} onClick={() => setRejecting(viewing)}>驳回</Button>
          </Space>
        </div>}
      </section>}
    </Drawer>

    <RejectModal open={!!rejecting} busy={busy}
      title={rejecting ? `驳回报名：${rejecting.storeName ?? rejecting.storeId}` : ''}
      description="驳回原因会同步给商家；商家可以在报名期内修改后重新提交。"
      onCancel={() => setRejecting(undefined)}
      onSubmit={reason => {
        const target = rejecting;
        if (!target) return;
        void run(() => client.reviewEnrollment(target.enrollmentId, 'REJECT', reason, target.version), '报名已驳回',
          async () => { setRejecting(undefined); setViewing(undefined); await list.reload(); });
      }} />
  </>;
}
