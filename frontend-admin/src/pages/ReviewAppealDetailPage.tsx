import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { appealStatusLabel, ReviewAppealFailure, type AppealDetail, type DecisionType, type ReviewAppealClient } from '../api/reviewAppeals';

function showUtc(value: string) { return value.slice(0, 16).replace('T', ' '); }

/** Contract56 admin detail: the appealed review facts plus the terminal one-shot decision. */
export default function ReviewAppealDetailPage({ client, canDecide, onAuthLost }: { client: ReviewAppealClient; canDecide: boolean; onAuthLost: () => void }) {
  const { appealId } = useParams();
  const [detail, setDetail] = useState<AppealDetail>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [reason, setReason] = useState('');
  const [notice, setNotice] = useState('');
  const sequence = useRef(0);
  useEffect(() => {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    setNotice('');
    client.get(appealId ?? '')
      .then(value => { if (call === sequence.current) { setDetail(value); setLoading(false); } })
      .catch(caught => {
        if (call !== sequence.current) return;
        setLoading(false);
        if (caught instanceof ReviewAppealFailure && ['UNAUTHENTICATED', 'STALE_CONTEXT'].includes(caught.code)) { onAuthLost(); return; }
        setError(caught instanceof ReviewAppealFailure && caught.code === 'COMMON_NOT_FOUND' ? '申诉不存在。' : '申诉读取失败，请重试。');
      });
    return () => { sequence.current++; };
  }, [appealId, client, onAuthLost]);
  // A journaled decision stays visible as the pending intent until its ACK resolves.
  useEffect(() => {
    if (!detail || !canDecide) return;
    try { const pending = client.pending(detail.appealId); if (pending) { setReason(pending.reason); setNotice('检测到未确认的裁决命令，请重试原操作（不会重复裁决）。'); } }
    catch { setNotice('本地命令日志不可用，无法恢复未确认的裁决。'); }
  }, [detail, canDecide, client]);
  async function decide(decisionType: DecisionType) {
    if (!detail || busy) return;
    const trimmed = reason.trim();
    if (trimmed.length < 1 || trimmed.length > 1000) { setNotice('请填写 1~1000 个字符的裁决理由。'); return; }
    const call = ++sequence.current;
    setBusy(true);
    setNotice('');
    try {
      await client.decide({ appealId: detail.appealId, requestId: crypto.randomUUID(), decisionType, reason: trimmed });
      const fresh = await client.get(detail.appealId);
      if (call !== sequence.current) return;
      setDetail(fresh);
      setReason('');
      setBusy(false);
    } catch (caught) {
      if (call !== sequence.current) return;
      setBusy(false);
      if (caught instanceof ReviewAppealFailure && ['UNAUTHENTICATED', 'STALE_CONTEXT'].includes(caught.code)) { onAuthLost(); return; }
      if (caught instanceof ReviewAppealFailure && caught.code === 'COMMON_CONFLICT') setNotice('该申诉已裁决，不可改判；请重新打开确认最新状态。');
      else if (caught instanceof ReviewAppealFailure && caught.code === 'UNRESOLVED_COMMAND') setNotice('存在未确认的裁决命令，请重试原操作而不是发起新裁决。');
      else setNotice(caught instanceof ReviewAppealFailure ? `裁决未确认（${caught.code}），请重试原操作。` : '裁决未确认，请重试原操作。');
    }
  }
  if (loading) return <main><h1>评价申诉详情</h1><p role="status">加载中</p></main>;
  if (error || !detail) return <main><h1>评价申诉详情</h1><p role="alert">{error || '申诉不可用。'}</p><p><Link to="/review-appeals">返回申诉列表</Link></p></main>;
  const decided = detail.status !== 'SUBMITTED';
  return <main>
    <h1>评价申诉详情</h1>
    <p><Link to="/review-appeals">返回申诉列表</Link></p>
    <section aria-labelledby="appeal-facts">
      <h2 id="appeal-facts">申诉事实</h2>
      <dl>
        <dt>申诉编号</dt><dd>{detail.appealId}</dd>
        <dt>状态</dt><dd>{appealStatusLabel(detail.status)}</dd>
        <dt>商家 / 门店 / 订单</dt><dd>{detail.merchantId} / {detail.storeId} / {detail.orderId}</dd>
        <dt>提交时间</dt><dd>{showUtc(detail.createdAt)}</dd>
        <dt>申诉理由</dt><dd className="reason">{detail.reason}</dd>
        {decided && <>
          <dt>裁决结果</dt><dd>{detail.status === 'APPROVED' ? '申诉成立（评价已隐藏）' : '申诉未成立（评价维持展示）'}</dd>
          <dt>裁决理由</dt><dd className="reason">{detail.decisionReason ?? '—'}</dd>
          <dt>裁决人 / 时间</dt><dd>{detail.decidedBy ?? '—'} · {detail.decidedAt ? showUtc(detail.decidedAt) : '—'}</dd>
        </>}
      </dl>
    </section>
    <section aria-labelledby="appealed-review">
      <h2 id="appealed-review">被诉评价</h2>
      <dl>
        <dt>评价编号</dt><dd>{detail.review.reviewId}</dd>
        <dt>评分</dt><dd>综合 {detail.review.compositeScore}（门店 {detail.review.storeScore} · 服务 {detail.review.serviceScore} · 人员 {detail.review.staffScore}）{detail.review.scoreIncluded ? '' : ' · 不计入商家评分'}</dd>
        <dt>展示状态</dt><dd>{detail.review.visibilityStatus === 'HIDDEN' ? '已隐藏' : '公开展示'}</dd>
        <dt>评价内容</dt><dd className="reason">{detail.review.content ?? '（无文字内容）'}</dd>
        <dt>评价时间</dt><dd>{showUtc(detail.review.createdAt)}</dd>
      </dl>
    </section>
    {!decided && canDecide && <section aria-labelledby="decision-form">
      <h2 id="decision-form">终局裁决（不可改判）</h2>
      <p className="hint">APPROVED = 申诉成立，评价立即停止公开展示；REJECTED = 申诉未成立，评价维持展示。</p>
      <label>裁决理由<textarea value={reason} onChange={event => setReason(event.target.value)} rows={4} maxLength={1000} placeholder="1~1000 个字符" /></label>
      <div className="decision-actions">
        <button disabled={busy} onClick={() => void decide('APPROVED')}>{busy ? '提交中…' : '申诉成立（隐藏评价）'}</button>
        <button disabled={busy} onClick={() => void decide('REJECTED')}>{busy ? '提交中…' : '申诉未成立（维持展示）'}</button>
      </div>
    </section>}
    {!decided && !canDecide && <p className="hint">当前账号仅可查看，无 review.appeal.decide 权限。</p>}
    {notice && <p role="alert">{notice}</p>}
  </main>;
}
