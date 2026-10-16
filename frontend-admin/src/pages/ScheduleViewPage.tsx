import { useEffect, useRef, useState } from 'react';
import {
  reservationStatusLabel, windowKindLabel, windowStatusLabel,
  type ReservationRow, type SchedulePage, type ScheduleViewClient,
  type StaffWindowRow, type ScheduleWindowRow,
} from '../api/scheduleView';
import { GovernanceFailure, governanceFailureText } from '../api/governanceHttp';

const ID = /^[1-9][0-9]{0,18}$/;
type Tab = 'windows' | 'staff' | 'reservations';
type Range = { startFrom: string; startTo: string };
const EMPTY: Range = { startFrom: '', startTo: '' };
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
  const current = tab === 'windows' ? windows : tab === 'staff' ? staff : reservations;
  const pages = current ? Math.min(10000, Math.max(1, Math.ceil(current.total / current.pageSize))) : 1;
  return <main className="aftersale-page">
    <h1>排期查看</h1>
    <p className="hint">运营 PRD 6.2.5：排期只读监管（SSOT §29）。商家自主维护排期（53 号）；占用口径与 SOLD_OUT 同源；返程约束由原始事实人工核对，服务端不派生。</p>
    <nav className="workbench-nav" aria-label="排期视图切换">
      <button onClick={() => switchTab('windows')} disabled={tab === 'windows'}>服务时段分布</button>
      <button onClick={() => switchTab('staff')} disabled={tab === 'staff'}>员工排班</button>
      <button onClick={() => switchTab('reservations')} disabled={tab === 'reservations'}>预约占用/锁定</button>
    </nav>
    <form className="filters" onSubmit={event => { event.preventDefault(); if (valid) void load(tab, range, merchantId, storeId, 1); }}>
      <label>商家编号<input inputMode="numeric" value={merchantId} onChange={event => setMerchantId(event.target.value)} /></label>
      <label>门店编号<input inputMode="numeric" value={storeId} onChange={event => setStoreId(event.target.value)} /></label>
      <label>时段起<input type="datetime-local" value={range.startFrom} onChange={event => setRange({ ...range, startFrom: event.target.value })} /></label>
      <label>时段止<input type="datetime-local" value={range.startTo} onChange={event => setRange({ ...range, startTo: event.target.value })} /></label>
      <button disabled={loading || !valid}>查询</button>
    </form>
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在查询排期事实</p>}
    {tab === 'windows' && windows && <>
      <div className="table-scroll"><table>
        <thead><tr><th>窗口</th><th>商家/门店</th><th>服务</th><th>类型</th><th>开始（UTC）</th><th>结束（UTC）</th><th>配置容量</th><th>已确认</th><th>临时锁定</th><th>利用率</th><th>状态</th></tr></thead>
        <tbody>{windows.items.map(row => <tr key={row.windowId}>
          <td>{row.windowId}</td>
          <td>{row.merchantId} / {row.storeId}</td>
          <td>{row.serviceId}</td>
          <td>{windowKindLabel(row.windowKind)}</td>
          <td>{row.startAt}</td>
          <td>{row.endAt}</td>
          <td>{row.configuredCapacity}</td>
          <td>{row.confirmedCount}</td>
          <td>{row.tempLockedCount}</td>
          <td>{row.configuredCapacity > 0 ? `${row.confirmedCount}/${row.configuredCapacity}` : '—'}</td>
          <td>{windowStatusLabel(row.status)}</td>
        </tr>)}</tbody>
      </table></div>
      <Pagination page={windows} disabled={loading} onPage={next => void load(tab, range, merchantId, storeId, next)} label="时段分布分页" pages={pages} />
    </>}
    {tab === 'staff' && staff && <>
      <div className="table-scroll"><table>
        <thead><tr><th>排班窗口</th><th>门店</th><th>员工</th><th>开始（UTC）</th><th>结束（UTC）</th><th>状态</th></tr></thead>
        <tbody>{staff.items.map(row => <tr key={row.windowId}>
          <td>{row.windowId}</td><td>{row.storeId}</td><td>{row.staffId}</td>
          <td>{row.startAt}</td><td>{row.endAt}</td>
          <td>{row.status === 'AVAILABLE' ? '可用' : '已关闭'}</td>
        </tr>)}</tbody>
      </table></div>
      <Pagination page={staff} disabled={loading} onPage={next => void load(tab, range, merchantId, storeId, next)} label="员工排班分页" pages={pages} />
    </>}
    {tab === 'reservations' && reservations && <>
      <div className="table-scroll"><table>
        <thead><tr><th>预约</th><th>订单</th><th>门店/服务</th><th>履约</th><th>开始（UTC）</th><th>结束（UTC）</th><th>上门</th><th>送回</th><th>状态</th><th>锁定到期</th></tr></thead>
        <tbody>{reservations.items.map(row => <tr key={row.reservationId}>
          <td>{row.reservationId}</td>
          <td>{row.orderId}</td>
          <td>{row.storeId} / {row.serviceId}</td>
          <td>{row.fulfillmentType === 'PICKUP_DELIVERY' ? '上门接送' : '到店'}</td>
          <td>{row.startAt}</td>
          <td>{row.endAt}</td>
          <td>{row.pickupStartAt ?? '—'}</td>
          <td>{row.returnStartAt ?? '—'}</td>
          <td>{reservationStatusLabel(row.status)}</td>
          <td>{row.lockExpireAt ?? '—'}</td>
        </tr>)}</tbody>
      </table></div>
      <Pagination page={reservations} disabled={loading} onPage={next => void load(tab, range, merchantId, storeId, next)} label="占用记录分页" pages={pages} />
    </>}
  </main>;
}

function Pagination({ page, pages, disabled, onPage, label }: {
  page: SchedulePage<ScheduleWindowRow | StaffWindowRow | ReservationRow>;
  pages: number; disabled: boolean; onPage: (next: number) => void; label: string;
}) {
  return <nav className="pager" aria-label={label}>
    <button disabled={disabled || page.page <= 1} onClick={() => onPage(page.page - 1)}>上一页</button>
    <span>第 {page.page} / {pages} 页 · 共 {page.total} 条</span>
    <button disabled={disabled || page.page >= pages} onClick={() => onPage(page.page + 1)}>下一页</button>
  </nav>;
}
