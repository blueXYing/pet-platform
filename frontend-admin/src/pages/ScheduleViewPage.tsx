import { useEffect, useRef, useState } from 'react';
import { Button, DatePicker, Input } from 'antd';
import dayjs from 'dayjs';
import type { TableColumnsType } from 'antd';
import {
  reservationStatusLabel, windowKindLabel, windowStatusLabel,
  type ReservationRow, type SchedulePage, type ScheduleViewClient,
  type StaffWindowRow, type ScheduleWindowRow,
} from '../api/scheduleView';
import { GovernanceFailure, governanceFailureText } from '../api/governanceHttp';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

const ID = /^[1-9][0-9]{0,18}$/;
type Tab = 'windows' | 'staff' | 'reservations';
type Range = { startFrom: string; startTo: string };
const EMPTY: Range = { startFrom: '', startTo: '' };
const LOCAL = 'YYYY-MM-DDTHH:mm';
const UTC_LOCAL = (value: string) => (value ? new Date(value).toISOString() : undefined);

/** 运营 PRD 6.2.5 门店排期监管（只读）：时段分布/占用、员工排班、预约与临时锁定记录。
 *  排期由商家维护（53 号），运营不代改；利用率为前端以 confirmedCount/configuredCapacity 呈现。 */
export default function ScheduleViewPage({ client, onAuthLost }: { client: ScheduleViewClient; onAuthLost: () => void }) {
  const [tab, setTab] = useState<Tab>('windows');
  const [range, setRange] = useState<Range>(EMPTY);
  const [merchantId, setMerchantId] = useState('');
  const [storeId, setStoreId] = useState('');
  const [windows, setWindows] = useState<SchedulePage<ScheduleWindowRow>>();
  const [staff, setStaff] = useState<SchedulePage<StaffWindowRow>>();
  const [reservations, setReservations] = useState<SchedulePage<ReservationRow>>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);
  useEffect(() => { void load('windows', EMPTY, '', '', 1); return () => { sequence.current++; }; }, []);
  async function load(which: Tab, nextRange: Range, merchant: string, store: string, page: number) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      if (which === 'windows') {
        const data = await client.listWindows(page, {
          merchantId: merchant || undefined, storeId: store || undefined,
          startFrom: UTC_LOCAL(nextRange.startFrom), startTo: UTC_LOCAL(nextRange.startTo),
        });
        if (call === sequence.current) setWindows(data);
      } else if (which === 'staff') {
        const data = await client.listStaffWindows(page, {
          storeId: store || undefined,
          startFrom: UTC_LOCAL(nextRange.startFrom), startTo: UTC_LOCAL(nextRange.startTo),
        });
        if (call === sequence.current) setStaff(data);
      } else {
        const data = await client.listReservations(page, {
          merchantId: merchant || undefined, storeId: store || undefined,
          startFrom: UTC_LOCAL(nextRange.startFrom), startTo: UTC_LOCAL(nextRange.startTo),
        });
        if (call === sequence.current) setReservations(data);
      }
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof GovernanceFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof GovernanceFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
      if (caught instanceof GovernanceFailure && caught.code === 'STALE_CONTEXT') return;
      setError(governanceFailureText(caught, '排期查询失败'));
    } finally { if (call === sequence.current) setLoading(false); }
  }
  function switchTab(next: Tab) {
    setTab(next);
    void load(next, range, merchantId, storeId, 1);
  }
  const rangeOk = (!range.startFrom && !range.startTo)
    || (range.startFrom && range.startTo && range.startFrom <= range.startTo);
  const idOk = (value: string) => value === '' || ID.test(value);
  const valid = rangeOk && idOk(merchantId) && idOk(storeId);
  const windowColumns: TableColumnsType<ScheduleWindowRow> = [
    { title: '窗口', dataIndex: 'windowId' },
    { title: '商家/门店', render: (_, row) => `${row.merchantId} / ${row.storeId}` },
    { title: '服务', dataIndex: 'serviceId' },
    { title: '类型', render: (_, row) => windowKindLabel(row.windowKind) },
    { title: '开始（UTC）', dataIndex: 'startAt' },
    { title: '结束（UTC）', dataIndex: 'endAt' },
    { title: '配置容量', dataIndex: 'configuredCapacity' },
    { title: '已确认', dataIndex: 'confirmedCount' },
    { title: '临时锁定', dataIndex: 'tempLockedCount' },
    { title: '利用率', render: (_, row) => row.configuredCapacity > 0 ? `${row.confirmedCount}/${row.configuredCapacity}` : '—' },
    { title: '状态', render: (_, row) => <StatusTag label={windowStatusLabel(row.status)} status={row.status} /> },
  ];
  const staffColumns: TableColumnsType<StaffWindowRow> = [
    { title: '排班窗口', dataIndex: 'windowId' },
    { title: '门店', dataIndex: 'storeId' },
    { title: '员工', dataIndex: 'staffId' },
    { title: '开始（UTC）', dataIndex: 'startAt' },
    { title: '结束（UTC）', dataIndex: 'endAt' },
    { title: '状态', render: (_, row) => row.status === 'AVAILABLE' ? '可用' : '已关闭' },
  ];
  const reservationColumns: TableColumnsType<ReservationRow> = [
    { title: '预约', dataIndex: 'reservationId' },
    { title: '订单', dataIndex: 'orderId' },
    { title: '门店/服务', render: (_, row) => `${row.storeId} / ${row.serviceId}` },
    { title: '履约', render: (_, row) => row.fulfillmentType === 'PICKUP_DELIVERY' ? '上门接送' : '到店' },
    { title: '开始（UTC）', dataIndex: 'startAt' },
    { title: '结束（UTC）', dataIndex: 'endAt' },
    { title: '上门', render: (_, row) => row.pickupStartAt ?? '—' },
    { title: '送回', render: (_, row) => row.returnStartAt ?? '—' },
    { title: '状态', render: (_, row) => <StatusTag label={reservationStatusLabel(row.status)} status={row.status} /> },
    { title: '锁定到期', render: (_, row) => row.lockExpireAt ?? '—' },
  ];
  return <PageContainer
    title="排期查看"
    hint="运营 PRD 6.2.5：排期只读监管（SSOT §29）。商家自主维护排期（53 号）；占用口径与 SOLD_OUT 同源；返程约束由原始事实人工核对，服务端不派生。">
    <nav className="filter-bar" aria-label="排期视图切换">
      <Button onClick={() => switchTab('windows')} disabled={tab === 'windows'}>服务时段分布</Button>
      <Button onClick={() => switchTab('staff')} disabled={tab === 'staff'}>员工排班</Button>
      <Button onClick={() => switchTab('reservations')} disabled={tab === 'reservations'}>预约占用/锁定</Button>
    </nav>
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); if (valid) void load(tab, range, merchantId, storeId, 1); }}>
      <label className="filter-item">商家编号<Input style={{ width: 160 }} inputMode="numeric" value={merchantId} onChange={event => setMerchantId(event.target.value)} /></label>
      <label className="filter-item">门店编号<Input style={{ width: 160 }} inputMode="numeric" value={storeId} onChange={event => setStoreId(event.target.value)} /></label>
      <label className="filter-item">时段起<DatePicker showTime format="YYYY-MM-DD HH:mm" value={range.startFrom ? dayjs(range.startFrom) : null} onChange={value => setRange({ ...range, startFrom: value ? value.format(LOCAL) : '' })} /></label>
      <label className="filter-item">时段止<DatePicker showTime format="YYYY-MM-DD HH:mm" value={range.startTo ? dayjs(range.startTo) : null} onChange={value => setRange({ ...range, startTo: value ? value.format(LOCAL) : '' })} /></label>
      <Button type="primary" htmlType="submit" disabled={loading || !valid}>查询</Button>
    </form>
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在查询排期事实</InlineStatus>}
    {tab === 'windows' && windows && <DataTable
      columns={windowColumns}
      rows={windows.items}
      rowKey={row => row.windowId}
      page={windows.page}
      pageSize={windows.pageSize}
      total={windows.total}
      onPageChange={next => void load(tab, range, merchantId, storeId, next)}
    />}
    {tab === 'staff' && staff && <DataTable
      columns={staffColumns}
      rows={staff.items}
      rowKey={row => row.windowId}
      page={staff.page}
      pageSize={staff.pageSize}
      total={staff.total}
      onPageChange={next => void load(tab, range, merchantId, storeId, next)}
    />}
    {tab === 'reservations' && reservations && <DataTable
      columns={reservationColumns}
      rows={reservations.items}
      rowKey={row => row.reservationId}
      page={reservations.page}
      pageSize={reservations.pageSize}
      total={reservations.total}
      onPageChange={next => void load(tab, range, merchantId, storeId, next)}
    />}
  </PageContainer>;
}
