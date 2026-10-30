import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Button, Input } from 'antd';
import { appealStatusLabel, ReviewAppealFailure, type AppealDetail, type DecisionType, type ReviewAppealClient } from '../api/reviewAppeals';
import { BackLink, PageContainer } from '../ui/PageContainer';
import { ErrorAlert, FactList, InlineStatus, PreserveText, Section } from '../ui/bits';

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
  if (loading) return <PageContainer title="评价申诉详情"><InlineStatus>加载中</InlineStatus></PageContainer>;
  if (error || !detail) return <PageContainer
    title="评价申诉详情"
    back={<BackLink to="/review-appeals">返回申诉列表</BackLink>}>
    <ErrorAlert>{error || '申诉不可用。'}</ErrorAlert>
  </PageContainer>;
  const decided = detail.status !== 'SUBMITTED';
  return <PageContainer
    title="评价申诉详情"
    back={<BackLink to="/review-appeals">返回申诉列表</BackLink>}>
    <Section title="申诉事实">
      <FactList items={[
        { label: '申诉编号', value: detail.appealId },
        { label: '状态', value: appealStatusLabel(detail.status) },
        { label: '商家 / 门店 / 订单', value: `${detail.merchantId} / ${detail.storeId} / ${detail.orderId}` },
        { label: '提交时间', value: showUtc(detail.createdAt) },
        { label: '申诉理由', value: <PreserveText>{detail.reason}</PreserveText> },
        ...(decided ? [
          { label: '裁决结果', value: detail.status === 'APPROVED' ? '申诉成立（评价已隐藏）' : '申诉未成立（评价维持展示）' },
          { label: '裁决理由', value: <PreserveText>{detail.decisionReason ?? '—'}</PreserveText> },
          { label: '裁决人 / 时间', value: `${detail.decidedBy ?? '—'} · ${detail.decidedAt ? showUtc(detail.decidedAt) : '—'}` },
        ] : []),
      ]} />
    </Section>
    <Section title="被诉评价">
      <FactList items={[
        { label: '评价编号', value: detail.review.reviewId },
        { label: '评分', value: `综合 ${detail.review.compositeScore}（门店 ${detail.review.storeScore} · 服务 ${detail.review.serviceScore} · 人员 ${detail.review.staffScore}）${detail.review.scoreIncluded ? '' : ' · 不计入商家评分'}` },
        { label: '展示状态', value: detail.review.visibilityStatus === 'HIDDEN' ? '已隐藏' : '公开展示' },
        { label: '评价内容', value: <PreserveText>{detail.review.content ?? '（无文字内容）'}</PreserveText> },
        { label: '评价时间', value: showUtc(detail.review.createdAt) },
      ]} />
    </Section>
    {!decided && canDecide && <Section title="终局裁决（不可改判）">
      <p className="hint">APPROVED = 申诉成立，评价立即停止公开展示；REJECTED = 申诉未成立，评价维持展示。</p>
      <label className="filter-item">裁决理由
        <Input.TextArea style={{ width: 'min(100%, 720px)' }} rows={4} maxLength={1000} placeholder="1~1000 个字符" value={reason} onChange={event => setReason(event.target.value)} />
      </label>
      <div className="decision-actions">
        <Button type="primary" disabled={busy} onClick={() => void decide('APPROVED')}>{busy ? '提交中…' : '申诉成立（隐藏评价）'}</Button>
        <Button danger type="primary" disabled={busy} onClick={() => void decide('REJECTED')}>{busy ? '提交中…' : '申诉未成立（维持展示）'}</Button>
      </div>
    </Section>}
    {!decided && !canDecide && <p className="hint">当前账号仅可查看，无 review.appeal.decide 权限。</p>}
    {notice && <ErrorAlert>{notice}</ErrorAlert>}
  </PageContainer>;
}
