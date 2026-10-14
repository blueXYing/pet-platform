import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ServiceReviewFailure, serviceStatusLabel, type ServiceDetail, type ServiceReviewClient } from '../api/serviceReview';

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><dt>{label}</dt><dd>{children}</dd></div>;
}

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
        if (caught instanceof ServiceReviewFailure && ['UNAUTHENTICATED', 'FORBIDDEN'].includes(caught.code)) { onAuthLost(); return; }
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
      if (caught instanceof ServiceReviewFailure && ['UNAUTHENTICATED', 'FORBIDDEN'].includes(caught.code)) { onAuthLost(); return; }
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
      if (caught instanceof ServiceReviewFailure && ['UNAUTHENTICATED', 'FORBIDDEN'].includes(caught.code)) { onAuthLost(); return; }
      setActionError(caught instanceof ServiceReviewFailure ? `操作失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '操作失败，请重试。');
    } finally { setBusy(false); }
  }
  const versionOk = detail !== undefined;
  return <main className="aftersale-page">
    <p><Link to="/services">返回服务审核</Link></p>
    <h1>服务审核</h1>
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在加载服务详情</p>}
    {notice && <p role="status">{notice}</p>}
    {actionError && <p role="alert">{actionError}</p>}
    {detail && <>
      <dl className="snapshot">
        <Field label="服务编号">{detail.serviceId}</Field>
        <Field label="服务名称">{detail.serviceName}</Field>
        <Field label="商家 / 门店">{detail.merchantId} / {detail.storeId}</Field>
        <Field label="类目">{detail.categoryName ?? detail.categoryId}</Field>
        <Field label="状态">{serviceStatusLabel(detail.status)}</Field>
        <Field label="价格">{detail.price ?? '—'}{detail.listPrice ? `（划线 ${detail.listPrice}）` : ''}</Field>
        <Field label="时长（分钟）">{detail.durationMinutes}</Field>
        <Field label="履约方式">{detail.fulfillmentType}</Field>
        <Field label="提交轮次">{detail.submissionNo}</Field>
        <Field label="提交（UTC）">{detail.submittedAt}</Field>
        <Field label="SLA 剩余（分钟）">{detail.slaRemainingMinutes ?? '—'}</Field>
        <Field label="版本">{detail.version}</Field>
      </dl>
      <h2>服务描述</h2>
      <p className="preserve-text">{detail.description ?? '—'}</p>
      <h2>历史审核记录</h2>
      {detail.decisions.length === 0
        ? <p>暂无历史审核记录。</p>
        : <div className="table-scroll"><table>
            <thead><tr><th>决定编号</th><th>轮次</th><th>决定</th><th>意见</th><th>时间（UTC）</th></tr></thead>
            <tbody>{detail.decisions.map(row => <tr key={row.decisionId}>
              <td>{row.decisionId}</td>
              <td>{row.submissionNo}</td>
              <td>{row.decisionType}</td>
              <td>{row.opinion ?? '—'}</td>
              <td>{row.decidedAt}</td>
            </tr>)}</tbody>
          </table></div>}
      <section>
        <h2>审核决定（service.review.decide）</h2>
        {!canDecide && <p className="hint">当前账号未授予 service.review.decide，按钮停用不代表后端放行。</p>}
        <label>驳回意见（REJECT 必填，10–500 字）
          <textarea value={opinion} onChange={event => setOpinion(event.target.value)} disabled={!canDecide || busy} />
        </label>
        <div className="decision-actions">
          <button disabled={!canDecide || busy || !versionOk} onClick={() => void decide('APPROVE')}>通过（上架）</button>
          <button disabled={!canDecide || busy || !versionOk || opinion.trim().length < 10} onClick={() => void decide('REJECT')}>驳回</button>
        </div>
      </section>
      <section>
        <h2>强制下架（service.force.offline）</h2>
        {!canForce && <p className="hint">当前账号未授予 service.force.offline。仅 ACTIVE 服务可强制下架。</p>}
        <label>下架原因（必填，10–500 字）
          <textarea value={reason} onChange={event => setReason(event.target.value)} disabled={!canForce || busy} />
        </label>
        <label>
          <input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} disabled={!canForce || busy} />
          我确认对该服务执行强制下架（同一人二次确认，非第二人审批）
        </label>
        <div className="decision-actions">
          <button disabled={!canForce || busy || !versionOk || reason.trim().length < 10 || !confirmed} onClick={() => void forceOffline()}>强制下架</button>
        </div>
      </section>
      <p className="hint">决定与下架在服务内核事务内做数据库权威复核；运营不代商家新增/编辑/上下架服务。</p>
    </>}
  </main>;
}
