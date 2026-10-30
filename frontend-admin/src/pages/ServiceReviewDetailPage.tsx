import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Button, Checkbox, Input } from 'antd';
import type { TableColumnsType } from 'antd';
import { ServiceReviewFailure, serviceStatusLabel, type ServiceDecisionRow, type ServiceDetail, type ServiceReviewClient } from '../api/serviceReview';
import { DataTable } from '../ui/DataTable';
import { BackLink, PageContainer } from '../ui/PageContainer';
import { ErrorAlert, FactList, InlineStatus, PreserveText, Section } from '../ui/bits';

/** 服务审核详情：卷宗 + 历史驳回记录；APPROVE→ACTIVE、REJECT→REJECTED、ACTIVE 可强制下架。 */
export default function ServiceReviewDetailPage({ client, canDecide, canForce, onAuthLost }: {
  client: ServiceReviewClient; canDecide: boolean; canForce: boolean; onAuthLost: () => void;
}) {
  const params = useParams();
  const serviceId = params.serviceId ?? '';
  const [detail, setDetail] = useState<ServiceDetail>();
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(true);
  const [opinion, setOpinion] = useState('');
  const [reason, setReason] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const sequence = useRef(0);
  useEffect(() => {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    client.get(serviceId)
      .then(data => { if (call === sequence.current) setDetail(data); })
      .catch((caught: unknown) => {
        if (call !== sequence.current) return;
        if (caught instanceof ServiceReviewFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
        if (caught instanceof ServiceReviewFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
        setError(caught instanceof ServiceReviewFailure ? `服务详情加载失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '服务详情加载失败，请重试。');
      })
      .finally(() => { if (call === sequence.current) setLoading(false); });
    return () => { sequence.current++; };
  }, [serviceId]);
  async function decide(decisionType: 'APPROVE' | 'REJECT') {
    if (!detail) return;
    setBusy(true);
    setActionError('');
    setNotice('');
    try {
      const receipt = await client.decide(detail.serviceId, detail.version, decisionType,
        decisionType === 'APPROVE' ? null : opinion, crypto.randomUUID());
      setNotice(`${decisionType === 'APPROVE' ? '已通过' : '已驳回'}：服务 ${receipt.serviceId} → ${serviceStatusLabel(receipt.status)}`);
      setDetail(await client.get(serviceId));
    } catch (caught) {
      if (caught instanceof ServiceReviewFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof ServiceReviewFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
      setActionError(caught instanceof ServiceReviewFailure ? `操作失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '操作失败，请重试。');
    } finally { setBusy(false); }
  }
  async function forceOffline() {
    if (!detail) return;
    setBusy(true);
    setActionError('');
    setNotice('');
    try {
      const receipt = await client.forceOffline(detail.serviceId, detail.version, reason, crypto.randomUUID());
      setNotice(`已强制下架：服务 ${receipt.serviceId} → ${serviceStatusLabel(receipt.status)}`);
      setDetail(await client.get(serviceId));
    } catch (caught) {
      if (caught instanceof ServiceReviewFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof ServiceReviewFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
      setActionError(caught instanceof ServiceReviewFailure ? `操作失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '操作失败，请重试。');
    } finally { setBusy(false); }
  }
  const versionOk = detail !== undefined;
  const decisionColumns: TableColumnsType<ServiceDecisionRow> = [
    { title: '决定编号', dataIndex: 'decisionId' },
    { title: '轮次', dataIndex: 'submissionNo' },
    { title: '决定', dataIndex: 'decisionType' },
    { title: '意见', render: (_, row) => row.opinion ?? '—' },
    { title: '时间（UTC）', dataIndex: 'decidedAt' },
  ];
  return <PageContainer
    title="服务审核"
    back={<BackLink to="/services">返回服务审核</BackLink>}>
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在加载服务详情</InlineStatus>}
    {notice && <InlineStatus>{notice}</InlineStatus>}
    {actionError && <ErrorAlert>{actionError}</ErrorAlert>}
    {detail && <>
      <FactList items={[
        { label: '服务编号', value: detail.serviceId },
        { label: '服务名称', value: detail.serviceName },
        { label: '商家 / 门店', value: `${detail.merchantId} / ${detail.storeId}` },
        { label: '类目', value: detail.categoryName ?? detail.categoryId },
        { label: '状态', value: serviceStatusLabel(detail.status) },
        { label: '价格', value: `${detail.price ?? '—'}${detail.listPrice ? `（划线 ${detail.listPrice}）` : ''}` },
        { label: '时长（分钟）', value: detail.durationMinutes },
        { label: '履约方式', value: detail.fulfillmentType },
        { label: '提交轮次', value: detail.submissionNo },
        { label: '提交（UTC）', value: detail.submittedAt },
        { label: 'SLA 剩余（分钟）', value: detail.slaRemainingMinutes ?? '—' },
        { label: '版本', value: detail.version },
      ]} />
      <Section title="服务描述">
        <PreserveText>{detail.description ?? '—'}</PreserveText>
      </Section>
      <Section title="历史审核记录">
        {detail.decisions.length === 0
          ? <p>暂无历史审核记录。</p>
          : <DataTable columns={decisionColumns} rows={detail.decisions} rowKey={row => row.decisionId} />}
      </Section>
      <Section title="审核决定（service.review.decide）">
        {!canDecide && <p className="hint">当前账号未授予 service.review.decide，按钮停用不代表后端放行。</p>}
        <label className="filter-item">驳回意见（REJECT 必填，10–500 字）
          <Input.TextArea style={{ width: 'min(100%, 720px)' }} rows={3} value={opinion} onChange={event => setOpinion(event.target.value)} disabled={!canDecide || busy} />
        </label>
        <div className="decision-actions">
          <Button type="primary" disabled={!canDecide || busy || !versionOk} onClick={() => void decide('APPROVE')}>通过（上架）</Button>
          <Button danger disabled={!canDecide || busy || !versionOk || opinion.trim().length < 10} onClick={() => void decide('REJECT')}>驳回</Button>
        </div>
      </Section>
      <Section title="强制下架（service.force.offline）">
        {!canForce && <p className="hint">当前账号未授予 service.force.offline。仅 ACTIVE 服务可强制下架。</p>}
        <label className="filter-item">下架原因（必填，10–500 字）
          <Input.TextArea style={{ width: 'min(100%, 720px)' }} rows={3} value={reason} onChange={event => setReason(event.target.value)} disabled={!canForce || busy} />
        </label>
        <div><Checkbox checked={confirmed} onChange={event => setConfirmed(event.target.checked)} disabled={!canForce || busy}>我确认对该服务执行强制下架（同一人二次确认，非第二人审批）</Checkbox></div>
        <div className="decision-actions">
          <Button danger disabled={!canForce || busy || !versionOk || reason.trim().length < 10 || !confirmed} onClick={() => void forceOffline()}>强制下架</Button>
        </div>
      </Section>
      <p className="hint">决定与下架在服务内核事务内做数据库权威复核；运营不代商家新增/编辑/上下架服务。</p>
    </>}
  </PageContainer>;
}
