import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { TradeQueryFailure, refundSourceLabel, refundStatusLabel, type RefundDetail, type TradeQueryClient } from '../api/tradeQuery';

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><dt>{label}</dt><dd>{children}</dd></div>;
}

/** 退款单详情：退款单 + 关联申请投影（不含密文理由）+ 执行事实；运营只读不改金额。
 *  63号 v0.2 处置（33号裁决）：渠道查单/重试只重推既有任务，不创建第二张业务退款单。 */
export default function TradeRefundDetailPage({ client, onAuthLost }: { client: TradeQueryClient; onAuthLost: () => void }) {
  const params = useParams();
  const refundOrderId = params.refundOrderId ?? '';
  const [detail, setDetail] = useState<RefundDetail>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState('');
  const [actionError, setActionError] = useState('');
  const [queryConfirmed, setQueryConfirmed] = useState(false);
  const [retryConfirmed, setRetryConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const sequence = useRef(0);
  useEffect(() => {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    client.getRefund(refundOrderId)
      .then(data => { if (call === sequence.current) setDetail(data); })
      .catch((caught: unknown) => {
        if (call !== sequence.current) return;
        if (caught instanceof TradeQueryFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
        if (caught instanceof TradeQueryFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
        setError(caught instanceof TradeQueryFailure ? `退款单详情加载失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '退款单详情加载失败，请重试。');
      })
      .finally(() => { if (call === sequence.current) setLoading(false); });
    return () => { sequence.current++; };
  }, [refundOrderId]);
  async function run(action: () => Promise<string>) {
    setBusy(true);
    setActionError('');
    setNotice('');
    try {
      setNotice(await action());
      const call = ++sequence.current;
      const data = await client.getRefund(refundOrderId);
      if (call === sequence.current) setDetail(data);
    } catch (caught) {
      if (caught instanceof TradeQueryFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof TradeQueryFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
      setActionError(caught instanceof TradeQueryFailure ? `操作失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '操作失败，请重试。');
    } finally { setBusy(false); }
  }
  const status = detail?.status;
  return <main className="aftersale-page">
    <p><Link to="/trade/refunds">返回退款单管理</Link></p>
    <h1>退款单详情</h1>
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在加载退款单详情</p>}
    {notice && <p role="status">{notice}</p>}
    {actionError && <p role="alert">{actionError}</p>}
    {detail && <>
      <dl className="snapshot">
        <Field label="退款单号">{detail.refundNo}</Field>
        <Field label="退款单编号">{detail.refundOrderId}</Field>
        <Field label="关联订单">{detail.orderId} · <Link to={`/trade/orders/${detail.orderId}`}>订单详情</Link></Field>
        <Field label="退款类型">{detail.refundType === 'FULL' ? '全额退款' : '部分退款'}</Field>
        <Field label="退款来源">{refundSourceLabel(detail.sourceType)}</Field>
        <Field label="退款状态">{refundStatusLabel(detail.status)}</Field>
        <Field label="退款金额">{detail.refundAmount}</Field>
        <Field label="发起方">{detail.initiatorType}</Field>
        <Field label="渠道退款号">{detail.channelRefundNo ?? '—'}</Field>
        <Field label="创建（UTC）">{detail.createdAt}</Field>
        <Field label="完成（UTC）">{detail.succeededAt ?? '—'}</Field>
      </dl>
      <section>
        <h2>渠道处置（refund.channel.query / refund.retry）</h2>
        <p className="hint">只对既有业务退款单重推渠道动作（结果以渠道回执为最终事实，本页不提供手工改写）；
          不创建第二张业务退款单（SSOT §22）。已完成退款或尚未提交过渠道的退款单不能查单/重试。</p>
        <label>
          <input type="checkbox" checked={queryConfirmed} onChange={event => setQueryConfirmed(event.target.checked)} disabled={busy} />
          我确认对该退款单发起渠道查单（同一人二次确认，非第二人审批）
        </label>
        <div className="decision-actions">
          <button disabled={busy || status !== 'PROCESSING' && status !== 'UNKNOWN' && status !== 'FAILED' || !queryConfirmed}
            onClick={() => void run(async () => {
              const receipt = await client.channelQueryRefund(refundOrderId, crypto.randomUUID());
              setQueryConfirmed(false);
              return `已发起渠道查单：任务 ${receipt.taskKey}（${receipt.taskState}）`;
            })}>渠道查单</button>
        </div>
        <label>
          <input type="checkbox" checked={retryConfirmed} onChange={event => setRetryConfirmed(event.target.checked)} disabled={busy} />
          我确认对该退款单重试渠道提交（同一人二次确认，非第二人审批）
        </label>
        <div className="decision-actions">
          <button disabled={busy || status !== 'PROCESSING' && status !== 'UNKNOWN' && status !== 'FAILED' || !retryConfirmed}
            onClick={() => void run(async () => {
              const receipt = await client.retryRefund(refundOrderId, crypto.randomUUID());
              setRetryConfirmed(false);
              return `已重试渠道提交：任务 ${receipt.taskKey}（${receipt.taskState}）`;
            })}>渠道重试</button>
        </div>
      </section>
      <h2>关联申请</h2>
      {detail.application === null
        ? <p>该退款单没有关联的退款申请（系统/裁决来源）。</p>
        : <dl className="snapshot">
            <Field label="申请编号">{detail.application.applicationId}</Field>
            <Field label="申请状态">{detail.application.status}</Field>
            <Field label="申请金额">{detail.application.requestedAmount}</Field>
            <Field label="商家处理截止（UTC）">{detail.application.merchantDeadline ?? '—'}</Field>
            <Field label="决定时间（UTC）">{detail.application.decidedAt ?? '—'}</Field>
          </dl>}
      <p className="hint">申请理由与商家决定理由按 49 号契约密文留存，运营面板不展示明文。</p>
      <h2>渠道执行事实</h2>
      {detail.execution === null
        ? <p>尚未产生渠道执行事实（退款未进入执行阶段或非执行来源）。</p>
        : <dl className="snapshot">
            <Field label="渠道交易号">{detail.execution.channelTradeNo}</Field>
            <Field label="退款金额">{detail.execution.refundAmount}</Field>
            <Field label="渠道支付时间（UTC）">{detail.execution.channelPaidAt}</Field>
            <Field label="币种">{detail.execution.currency}</Field>
          </dl>}
      <p className="hint">退款以渠道回执为最终结果；结果未知（UNKNOWN）时按渠道真实状态持续对账，本页不提供手工改写。</p>
    </>}
  </main>;
}
