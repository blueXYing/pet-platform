import { useEffect, useRef, useState } from 'react';
import {
  employmentLabel, invitationStatusLabel, memberStatusLabel,
  type StaffGovernanceClient, type StaffInvitationRow, type StaffMemberRow,
  type StaffPage, type StaffProfileRow,
} from '../api/staffGovernance';
import { GovernanceFailure, governanceFailureText } from '../api/governanceHttp';

const ID = /^[1-9][0-9]{0,18}$/;
type Tab = 'staff' | 'members' | 'invitations';

/** 运营 PRD 6.2.6 员工监管（只读）：主账号/子账号授权状态与归属，核销争议溯源入口。
 *  SSOT §30：运营侧无员工写动作；成员停用/恢复与邀请是 54 号商家 OWNER 面，不在本页。 */
export default function StaffGovernancePage({ client, onAuthLost }: { client: StaffGovernanceClient; onAuthLost: () => void }) {
  const [merchantId, setMerchantId] = useState('');
  const [tab, setTab] = useState<Tab>('staff');
  const [staff, setStaff] = useState<StaffPage<StaffProfileRow>>();
  const [members, setMembers] = useState<StaffPage<StaffMemberRow>>();
  const [invitations, setInvitations] = useState<StaffPage<StaffInvitationRow>>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [queried, setQueried] = useState('');
  const sequence = useRef(0);
  useEffect(() => { setStaff(undefined); setMembers(undefined); setInvitations(undefined); }, []);
  async function load(which: Tab, merchant: string, page: number) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      if (which === 'staff') {
        const data = await client.listStaff(merchant, page);
        if (call === sequence.current) setStaff(data);
      } else if (which === 'members') {
        const data = await client.listMembers(merchant, page);
        if (call === sequence.current) setMembers(data);
      } else {
        const data = await client.listInvitations(merchant, page);
        if (call === sequence.current) setInvitations(data);
      }
      if (call === sequence.current) setQueried(merchant);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof GovernanceFailure && ['UNAUTHENTICATED', 'FORBIDDEN'].includes(caught.code)) { onAuthLost(); return; }
      if (caught instanceof GovernanceFailure && caught.code === 'STALE_CONTEXT') return;
      setError(caught instanceof GovernanceFailure && caught.code === 'COMMON_NOT_FOUND'
        ? '商家不存在（或不在授权范围内）' : governanceFailureText(caught, '员工治理查询失败'));
    } finally { if (call === sequence.current) setLoading(false); }
  }
  function switchTab(next: Tab) {
    setTab(next);
    if (queried) void load(next, queried, 1);
  }
  const current = tab === 'staff' ? staff : tab === 'members' ? members : invitations;
  const pages = current ? Math.min(10000, Math.max(1, Math.ceil(current.total / current.pageSize))) : 1;
  return <main className="aftersale-page">
    <h1>员工治理</h1>
    <p className="hint">运营 PRD 6.2.6：员工档案、子账号授权与邀请记录只读监管；手机号仅掩码；核销操作人溯源经 63 号核销记录面按订单关联查询。</p>
    <form className="filters" onSubmit={event => { event.preventDefault(); if (ID.test(merchantId)) void load(tab, merchantId, 1); }}>
      <label>商家编号<input inputMode="numeric" value={merchantId} onChange={event => setMerchantId(event.target.value)} /></label>
      <button disabled={loading || !ID.test(merchantId)}>查询员工</button>
    </form>
    {queried && <nav className="workbench-nav" aria-label="员工治理切换">
      <button onClick={() => switchTab('staff')} disabled={tab === 'staff'}>员工档案</button>
      <button onClick={() => switchTab('members')} disabled={tab === 'members'}>成员绑定</button>
      <button onClick={() => switchTab('invitations')} disabled={tab === 'invitations'}>邀请记录</button>
    </nav>}
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在查询员工治理事实</p>}
    {tab === 'staff' && staff && <>
      <div className="table-scroll"><table>
        <thead><tr><th>员工</th><th>门店</th><th>姓名</th><th>手机号（脱敏）</th><th>在职状态</th><th>可服务</th><th>版本</th></tr></thead>
        <tbody>{staff.items.map(row => <tr key={row.staffId}>
          <td>{row.staffId}</td><td>{row.storeId}</td><td>{row.staffName}</td>
          <td>{row.phoneMasked ?? '—'}</td><td>{employmentLabel(row.employmentStatus)}</td>
          <td>{row.serviceEnabled ? '是' : '否'}</td><td>{row.version}</td>
        </tr>)}</tbody>
      </table></div>
      <nav className="pager" aria-label="员工档案分页">
        <button disabled={loading || staff.page <= 1} onClick={() => void load(tab, queried, staff.page - 1)}>上一页</button>
        <span>第 {staff.page} / {pages} 页 · 共 {staff.total} 条</span>
        <button disabled={loading || staff.page >= pages} onClick={() => void load(tab, queried, staff.page + 1)}>下一页</button>
      </nav>
    </>}
    {tab === 'members' && members && <>
      <div className="table-scroll"><table>
        <thead><tr><th>成员</th><th>门店</th><th>状态</th><th>姓名</th><th>手机号（脱敏）</th><th>已授动作</th><th>整店撤权</th><th>成员/授予版本</th></tr></thead>
        <tbody>{members.items.map(row => <tr key={row.memberId}>
          <td>{row.memberId}</td><td>{row.storeId}</td><td>{memberStatusLabel(row.status)}</td>
          <td>{row.memberName ?? '—'}</td><td>{row.phoneMasked ?? '—'}</td>
          <td>{row.grantedActions.join('、') || '—'}</td>
          <td>{row.grantRevoked ? '是（终局）' : '否'}</td>
          <td>{row.memberVersion} / {row.grantVersion ?? '—'}</td>
        </tr>)}</tbody>
      </table></div>
      <nav className="pager" aria-label="成员绑定分页">
        <button disabled={loading || members.page <= 1} onClick={() => void load(tab, queried, members.page - 1)}>上一页</button>
        <span>第 {members.page} / {pages} 页 · 共 {members.total} 条</span>
        <button disabled={loading || members.page >= pages} onClick={() => void load(tab, queried, members.page + 1)}>下一页</button>
      </nav>
    </>}
    {tab === 'invitations' && invitations && <>
      <div className="table-scroll"><table>
        <thead><tr><th>邀请</th><th>门店</th><th>被邀姓名</th><th>手机号（脱敏）</th><th>状态</th><th>动作集</th><th>邀请人</th><th>邀请（UTC）</th></tr></thead>
        <tbody>{invitations.items.map(row => <tr key={row.invitationId}>
          <td>{row.invitationId}</td><td>{row.storeId}</td><td>{row.memberName}</td>
          <td>{row.phoneMasked}</td><td>{invitationStatusLabel(row.status)}</td>
          <td>{row.grantedActions.join('、')}</td><td>{row.invitedBy}</td><td>{row.invitedAt}</td>
        </tr>)}</tbody>
      </table></div>
      <nav className="pager" aria-label="邀请记录分页">
        <button disabled={loading || invitations.page <= 1} onClick={() => void load(tab, queried, invitations.page - 1)}>上一页</button>
        <span>第 {invitations.page} / {pages} 页 · 共 {invitations.total} 条</span>
        <button disabled={loading || invitations.page >= pages} onClick={() => void load(tab, queried, invitations.page + 1)}>下一页</button>
      </nav>
    </>}
  </main>;
}
