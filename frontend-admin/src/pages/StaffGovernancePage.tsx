import { useEffect, useRef, useState } from 'react';
import { Button, Input } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  employmentLabel, invitationStatusLabel, memberStatusLabel,
  type StaffGovernanceClient, type StaffInvitationRow, type StaffMemberRow,
  type StaffPage, type StaffProfileRow,
} from '../api/staffGovernance';
import { GovernanceFailure, governanceFailureText } from '../api/governanceHttp';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

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
      if (caught instanceof GovernanceFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof GovernanceFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
      if (caught instanceof GovernanceFailure && caught.code === 'STALE_CONTEXT') return;
      setError(caught instanceof GovernanceFailure && caught.code === 'COMMON_NOT_FOUND'
        ? '商家不存在（或不在授权范围内）' : governanceFailureText(caught, '员工治理查询失败'));
    } finally { if (call === sequence.current) setLoading(false); }
  }
  function switchTab(next: Tab) {
    setTab(next);
    if (queried) void load(next, queried, 1);
  }
  const staffColumns: TableColumnsType<StaffProfileRow> = [
    { title: '员工', dataIndex: 'staffId' },
    { title: '门店', dataIndex: 'storeId' },
    { title: '姓名', dataIndex: 'staffName' },
    { title: '手机号（脱敏）', render: (_, row) => row.phoneMasked ?? '—' },
    { title: '在职状态', render: (_, row) => employmentLabel(row.employmentStatus) },
    { title: '可服务', render: (_, row) => row.serviceEnabled ? '是' : '否' },
    { title: '版本', dataIndex: 'version' },
  ];
  const memberColumns: TableColumnsType<StaffMemberRow> = [
    { title: '成员', dataIndex: 'memberId' },
    { title: '门店', dataIndex: 'storeId' },
    { title: '状态', render: (_, row) => <StatusTag label={memberStatusLabel(row.status)} status={row.status} /> },
    { title: '姓名', render: (_, row) => row.memberName ?? '—' },
    { title: '手机号（脱敏）', render: (_, row) => row.phoneMasked ?? '—' },
    { title: '已授动作', render: (_, row) => row.grantedActions.join('、') || '—' },
    { title: '整店撤权', render: (_, row) => row.grantRevoked ? '是（终局）' : '否' },
    { title: '成员/授予版本', render: (_, row) => `${row.memberVersion} / ${row.grantVersion ?? '—'}` },
  ];
  const invitationColumns: TableColumnsType<StaffInvitationRow> = [
    { title: '邀请', dataIndex: 'invitationId' },
    { title: '门店', dataIndex: 'storeId' },
    { title: '被邀姓名', dataIndex: 'memberName' },
    { title: '手机号（脱敏）', dataIndex: 'phoneMasked' },
    { title: '状态', render: (_, row) => <StatusTag label={invitationStatusLabel(row.status)} status={row.status} /> },
    { title: '动作集', render: (_, row) => row.grantedActions.join('、') },
    { title: '邀请人', dataIndex: 'invitedBy' },
    { title: '邀请（UTC）', dataIndex: 'invitedAt' },
  ];
  return <PageContainer
    title="员工治理"
    hint="运营 PRD 6.2.6：员工档案、子账号授权与邀请记录只读监管；手机号仅掩码；核销操作人溯源经 63 号核销记录面按订单关联查询。">
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); if (ID.test(merchantId)) void load(tab, merchantId, 1); }}>
      <label className="filter-item">商家编号<Input inputMode="numeric" value={merchantId} onChange={event => setMerchantId(event.target.value)} /></label>
      <Button type="primary" htmlType="submit" disabled={loading || !ID.test(merchantId)}>查询员工</Button>
    </form>
    {queried && <nav className="workbench-nav" aria-label="员工治理切换">
      <Button onClick={() => switchTab('staff')} disabled={tab === 'staff'}>员工档案</Button>
      <Button onClick={() => switchTab('members')} disabled={tab === 'members'}>成员绑定</Button>
      <Button onClick={() => switchTab('invitations')} disabled={tab === 'invitations'}>邀请记录</Button>
    </nav>}
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在查询员工治理事实</InlineStatus>}
    {tab === 'staff' && staff && <DataTable
      columns={staffColumns}
      rows={staff.items}
      rowKey={row => row.staffId}
      page={staff.page}
      pageSize={staff.pageSize}
      total={staff.total}
      onPageChange={page => void load('staff', queried, page)}
    />}
    {tab === 'members' && members && <DataTable
      columns={memberColumns}
      rows={members.items}
      rowKey={row => row.memberId}
      page={members.page}
      pageSize={members.pageSize}
      total={members.total}
      onPageChange={page => void load('members', queried, page)}
    />}
    {tab === 'invitations' && invitations && <DataTable
      columns={invitationColumns}
      rows={invitations.items}
      rowKey={row => row.invitationId}
      page={invitations.page}
      pageSize={invitations.pageSize}
      total={invitations.total}
      onPageChange={page => void load('invitations', queried, page)}
    />}
  </PageContainer>;
}
