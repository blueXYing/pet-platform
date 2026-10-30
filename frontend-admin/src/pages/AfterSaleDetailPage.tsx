import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { Button, Checkbox, Input, Select } from 'antd';
import { AfterSaleFailure, caseStatusLabel, decisionLabel, validUtc, REFUND_DECISIONS, type Action, type ActionBody, type AfterSaleClient, type CaseDetail, type DecisionType, type PendingIntent, type ReadGrant, type Receipt } from '../api/aftersales';
import { BackLink, PageContainer } from '../ui/PageContainer';
import { ErrorAlert, FactList, InlineStatus, PreserveText, Section, StatusTag } from '../ui/bits';

function errorText(caught: unknown, fallback: string) {
  if (!(caught instanceof AfterSaleFailure)) return fallback;
  return `${fallback}（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}`;
}
const hasText = (value: string) => /\S/.test(value) && value.length <= 500;
const isRefundDecision = (value: DecisionType) => REFUND_DECISIONS.includes(value);
// Exact two-decimal positive amount string; FULL/PARTIAL bounds against the real paid
// amount are adjudicated by the kernel (400 AFTERSALE_AMOUNT_INVALID otherwise).
const isAmountDraft = (value: string) => /^(0|[1-9][0-9]{0,15})\.[0-9]{2}$/.test(value) && !/^0\.00$/.test(value);
export default function AfterSaleDetailPage({ client, canHandle, canDecide, onAuthLost }: { client: AfterSaleClient; canHandle: boolean; canDecide: boolean; onAuthLost: () => void }) {
  const { afterSaleId = '' } = useParams();
  const [params] = useSearchParams();
  const back = `/aftersales${params.has('merchantId') && params.has('storeId') ? `?${new URLSearchParams({ merchantId: params.get('merchantId')!, storeId: params.get('storeId')! })}` : ''}`;
  const [detail, setDetail] = useState<CaseDetail>();
  const [histories, setHistories] = useState<CaseDetail[]>([]);
  const [reviewed, setReviewed] = useState<string[]>([]);
  const [historyLoaded, setHistoryLoaded] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [retry, setRetry] = useState<PendingIntent>();
  const [previews, setPreviews] = useState<Record<string, string>>({});
  const urls = useRef<Record<string, string>>({});
  const generation = useRef(0);
  const [viewReason, setViewReason] = useState('售后工单处理，核对双方提交的图片证据');
  const [assessment, setAssessment] = useState('');
  const [acceptConfirmed, setAcceptConfirmed] = useState(false);
  const [priorFinalCaseId, setPriorFinalCaseId] = useState('');
  const [duplicateReason, setDuplicateReason] = useState('');
  const [duplicateConfirmed, setDuplicateConfirmed] = useState(false);
  const [targetParty, setTargetParty] = useState<'USER' | 'MERCHANT'>('USER');
  const [supplementReason, setSupplementReason] = useState('');
  const [deadline, setDeadline] = useState('');
  const [decisionType, setDecisionType] = useState<DecisionType>('REJECT');
  const [refundAmountDraft, setRefundAmountDraft] = useState('');
  const [reason, setReason] = useState('');
  const [decisionConfirmed, setDecisionConfirmed] = useState(false);
  const [lastReceipt, setLastReceipt] = useState<Receipt>();
  function clearPreviews() { for (const url of Object.values(urls.current)) URL.revokeObjectURL(url); urls.current = {}; setPreviews({}); }
  function resetDrafts() { setAssessment(''); setAcceptConfirmed(false); setPriorFinalCaseId(''); setDuplicateReason(''); setDuplicateConfirmed(false); setSupplementReason(''); setDeadline(''); setReason(''); setDecisionConfirmed(false); setRefundAmountDraft(''); }
  function readPending(): { ok: true; intent: PendingIntent | undefined } | { ok: false } {
    try { return { ok: true, intent: client.pending(afterSaleId) }; }
    catch (caught) {
      generation.current++; clearPreviews(); resetDrafts(); setRetry(undefined); setDetail(undefined); setBusy(false);
      setError(errorText(caught, '原操作日志无法读取，处理已暂停。请恢复浏览器存储后刷新工单'));
      return { ok: false };
    }
  }
  function authLost(caught: unknown) {
    if (caught instanceof AfterSaleFailure && ['UNAUTHENTICATED', 'FORBIDDEN'].includes(caught.code)) { clearPreviews(); setDetail(undefined); onAuthLost(); return true; }
    return false;
  }
  async function load() {
    const call = ++generation.current;
    clearPreviews(); resetDrafts(); setRetry(undefined); setDetail(undefined); setHistories([]); setReviewed([]); setHistoryLoaded(false); setBusy(true);
    try {
      const data = await client.getScoped(afterSaleId, params.get('merchantId'), params.get('storeId'));
      if (call !== generation.current) return;
      setRetry(client.pending(afterSaleId));
      setDetail(data);
    } catch (caught) {
      if (call !== generation.current || authLost(caught)) return;
      setError(errorText(caught, '工单读取失败，请刷新'));
    } finally { if (call === generation.current) setBusy(false); }
  }
  useEffect(() => {
    void load();
    function hidden() {
      generation.current++;
      clearPreviews(); resetDrafts(); setDetail(undefined); setBusy(false);
      const pending = readPending(); if (pending.ok) setRetry(pending.intent);
      setNotice('页面离开前台，图片与处理确认已清除；返回后请刷新工单，再显式读取证据。');
    }
    const visibility = () => { if (document.hidden) hidden(); };
    document.addEventListener('visibilitychange', visibility);
    window.addEventListener('pagehide', hidden);
    return () => { generation.current++; document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', hidden); for (const url of Object.values(urls.current)) URL.revokeObjectURL(url); urls.current = {}; };
  }, [afterSaleId]);
  async function loadHistory() {
    if (!detail || busy) return;
    const call = generation.current;
    setBusy(true); setError(''); setHistories([]); setReviewed([]); setHistoryLoaded(false);
    try {
      const values: CaseDetail[] = [];
      // Every prior final is read; P4 must not use a truncated first page.
      for (const id of detail.priorFinalCaseIds) {
        const old = await client.get(id);
        if (call !== generation.current) return;
        if (old.orderId !== detail.orderId || old.status !== 'RESOLVED' || !['REJECT', 'RESERVICE', 'OTHER'].includes(old.decisionType ?? '')) throw new AfterSaleFailure('INVALID_PRIOR_FINAL', 200);
        values.push(old);
      }
      setHistories(values); setHistoryLoaded(true);
    } catch (caught) { if (call === generation.current && !authLost(caught)) setError(errorText(caught, '历史终局读取失败，受理和重复关闭保持禁用')); }
    finally { if (call === generation.current) setBusy(false); }
  }
  async function execute(intent: PendingIntent) {
    if (busy) return;
    const call = generation.current;
    setBusy(true); setError(''); setNotice('');
    let grant: ReadGrant | undefined;
    try {
      const result = await client.send(intent);
      if (call !== generation.current) return;
      setRetry(undefined);
      if (intent.action === 'grant') grant = result as ReadGrant;
      else { setLastReceipt(result as Receipt); resetDrafts(); setNotice(`${intent.label}已提交，以工单刷新结果为准。`); }
    } catch (caught) {
      if (call !== generation.current || authLost(caught)) return;
      const stored = readPending(); if (!stored.ok) return;
      const pending = stored.intent;
      setRetry(pending);
      if (caught instanceof AfterSaleFailure && caught.status === 409 && !pending) {
        resetDrafts(); setLastReceipt(undefined);
        setError('工单或历史终局版本已变化，请重新核对刷新后的内容并手动提交。');
        await load();
      } else if (pending) setError(caught instanceof AfterSaleFailure && caught.status === 429 ? '请求受到频率限制，原操作保留。请稍后显式重试原操作，使用相同请求标识和内容。' : '结果未知：该操作可能已提交或仍在处理。请重试原操作取得幂等回执；刷新不会确认或清除此操作。');
      else setError(errorText(caught, '操作失败'));
      return;
    } finally { if (call === generation.current) setBusy(false); }
    if (grant) {
      // The grant command is acknowledged before consumption. A lost binary read
      // may consume the token; a fresh, explicit grant is then required.
      setBusy(true);
      try {
        const blob = await client.consumeReadGrant(grant.readUrl);
        if (call !== generation.current) return;
        const key = `${intent.batchId}/${intent.assetId}`;
        if (urls.current[key]) URL.revokeObjectURL(urls.current[key]);
        const url = URL.createObjectURL(blob);
        urls.current[key] = url; setPreviews({ ...urls.current });
        setNotice('图片已通过单次授权读取；离开页面、刷新或隐藏时会清除。');
      } catch (caught) {
        if (call === generation.current && !authLost(caught)) setError(errorText(caught, '图片读取未完成，授权可能已消费或过期，请重新申请查看'));
      } finally { if (call === generation.current) setBusy(false); }
    } else await load();
  }
  function command(action: Action, body: ActionBody, label: string) {
    if (!detail || busy || !client.canWriteCase(afterSaleId)) return;
    const stored = readPending(); if (!stored.ok || stored.intent) return;
    void execute({ caseId: afterSaleId, action, body: structuredClone(body), requestId: crypto.randomUUID(), label });
  }
  function view(batchId: string, assetId: string) {
    if (!detail || busy || !hasText(viewReason)) return;
    const stored = readPending(); if (!stored.ok || stored.intent) return;
    void execute({ caseId: afterSaleId, action: 'grant', requestId: crypto.randomUUID(), label: '证据授权', body: { reason: viewReason }, batchId, assetId });
  }
  const blocked = busy || retry !== undefined || !client.canWriteCase(afterSaleId);
  const p4 = detail !== undefined && detail.priorFinalCaseIds.length > 0;
  const historyChecked = !p4 || historyLoaded && reviewed.length === detail?.priorFinalCaseIds.length;
  const validDeadline = validUtc(deadline) && Date.parse(deadline) > Date.now();
  const retryAllowed = retry?.action === 'grant' || (retry?.action === 'decisions' ? canDecide : canHandle) && client.canWriteCase(afterSaleId);
  const retryBody = retry?.body as Record<string, unknown> | undefined;
  return <PageContainer
    title="售后工单详情"
    back={<BackLink to={back}>返回售后列表</BackLink>}
    extra={<Button disabled={busy} onClick={() => { setError(''); void load(); }}>刷新工单</Button>}>
    {error && <ErrorAlert>{error}</ErrorAlert>}{notice && <InlineStatus>{notice}</InlineStatus>}
    {retry && <div className="aftersale-callout"><p>待确认操作：{retry.label}。保留原请求标识和原始内容，新的处理操作暂停。</p>
      {retryBody && <details><summary>查看原处理内容</summary>{typeof retryBody.expectedVersion === 'string' && <p>原工单版本：{retryBody.expectedVersion}</p>}{typeof retryBody.decisionType === 'string' && <p>原处理类型：{decisionLabel(retryBody.decisionType)}</p>}{typeof retryBody.refundAmount === 'string' && <p>原退款金额：{retryBody.refundAmount}</p>}{typeof retryBody.reason === 'string' && <PreserveText>原处理原因：{retryBody.reason}</PreserveText>}{typeof retryBody.newProblemAssessment === 'string' && <PreserveText>原新问题评估：{retryBody.newProblemAssessment}</PreserveText>}{typeof retryBody.targetParty === 'string' && <p>原补证方：{retryBody.targetParty === 'USER' ? '用户' : '商家'}</p>}{typeof retryBody.deadline === 'string' && <p>原补证截止（UTC）：{retryBody.deadline}</p>}{typeof retryBody.priorFinalCaseId === 'string' && <p>引用原终局：{retryBody.priorFinalCaseId}</p>}</details>}
      <Button type="primary" disabled={busy || !retryAllowed} onClick={() => void execute(retry)}>重试原操作</Button></div>}
    {busy && <InlineStatus>正在读取或提交</InlineStatus>}
    {detail && <>
      <FactList items={[
        { label: '工单编号', value: detail.afterSaleId },
        { label: '订单编号', value: detail.orderId },
        { label: '状态', value: <StatusTag label={caseStatusLabel(detail.status)} status={detail.status} /> },
        { label: '版本', value: detail.version },
        { label: '来源阶段', value: detail.sourceStage === 'VERIFIED' ? '已核销' : '未核销 · 服务已开始' },
        { label: '问题 / 诉求代码', value: `${detail.typeCode} / ${detail.demandCode}` },
        { label: '诉求金额', value: detail.requestedAmount ?? '未指定' },
        { label: '创建（UTC）', value: detail.createdAt },
        { label: '申请截止（UTC）', value: detail.deadline },
      ]} />
      <Section title="用户问题说明">
        <PreserveText>{detail.description}</PreserveText>
        {detail.newProblemStatement && <><h3>终局后新问题说明</h3><PreserveText>{detail.newProblemStatement}</PreserveText></>}
      </Section>
      {detail.supplementRequestId && <Section title="当前补证要求">
        <p>轮次 {detail.supplementRequestId} · 指定 {detail.supplementTarget === 'USER' ? '用户' : '商家'}</p>
        <PreserveText>{detail.supplementReason}</PreserveText>
        <p>截止（UTC）：{detail.supplementDeadline}</p>
        <p className="hint">达到截止后不能满足该轮补证，后台超时任务恢复处理中；页面不会自动裁决。</p>
      </Section>}
      <Section title="双方入卷证据">
        <label className="filter-item">证据查看用途
          <Input.TextArea style={{ width: 'min(100%, 720px)' }} rows={4} maxLength={500} value={viewReason} onChange={event => setViewReason(event.target.value)} />
        </label>
        {!detail.evidence.length && <p>尚无证据批次。</p>}
        {detail.evidence.map(batch => <article className="aftersale-card" key={batch.batchId}><h3>{batch.submitterType === 'USER' ? '用户' : '商家'}证据 · {batch.batchId}</h3><p>提交（UTC）：{batch.submittedAt}{batch.opinionCode && ` · 商家意见：${({ AGREE: '同意', PARTLY_AGREE: '部分同意', DISAGREE: '不同意', NEED_USER_SUPPLEMENT: '请求用户补充' } as Record<string, string>)[batch.opinionCode] ?? batch.opinionCode}`}</p>{batch.text && <PreserveText>{batch.text}</PreserveText>}
          <ul className="materials">{batch.assetIds.map(assetId => { const key = `${batch.batchId}/${assetId}`; return <li key={assetId}><span>图片 {assetId}</span><Button type="primary" disabled={busy || retry !== undefined || !hasText(viewReason)} onClick={() => view(batch.batchId, assetId)}>申请查看证据</Button>{previews[key] && <><img src={previews[key]} alt={`售后证据 ${assetId}`} /><Button onClick={() => { URL.revokeObjectURL(urls.current[key]); delete urls.current[key]; setPreviews({ ...urls.current }); }}>隐藏图片</Button></>}</li>; })}</ul>
        </article>)}
      </Section>
      {p4 && <Section title="同订单历史终局核对">
        <p>必须逐笔核对当前完整历史后受理新问题。历史工单共 {detail.priorFinalCaseIds.length} 笔。</p>
        <Button disabled={busy} onClick={() => void loadHistory()}>读取历史终局</Button>
        {histories.map(old => <article className="aftersale-card" key={old.afterSaleId}><h3>历史工单 {old.afterSaleId} · {decisionLabel(old.decisionType ?? '')}</h3><PreserveText>原问题：{old.description}</PreserveText><PreserveText>原结论：{old.decisionReason}</PreserveText><Link to={`/aftersales/${old.afterSaleId}${params.size ? `?${params}` : ''}`}>查看历史卷宗与证据</Link><div><Checkbox disabled={blocked} checked={reviewed.includes(old.afterSaleId)} onChange={event => setReviewed(current => event.target.checked ? [...current, old.afterSaleId] : current.filter(id => id !== old.afterSaleId))}>已核对这笔终局及当前新问题说明</Checkbox></div></article>)}
      </Section>}
      {detail.status === 'PENDING' && canHandle && <Section title="受理或重复问题关闭">
        <Section title="受理">
          {p4 && <label className="filter-item">新问题核对意见
            <Input.TextArea style={{ width: 'min(100%, 720px)' }} rows={4} value={assessment} maxLength={500} disabled={blocked} onChange={event => setAssessment(event.target.value)} />
          </label>}
          <div><Checkbox checked={acceptConfirmed} disabled={blocked} onChange={event => setAcceptConfirmed(event.target.checked)}>确认问题符合受理条件，已有终局时确属新问题</Checkbox></div>
          <div className="decision-actions">
            <Button type="primary" disabled={blocked || !acceptConfirmed || !historyChecked || p4 && !hasText(assessment)}
              onClick={() => command('accept', { expectedVersion: detail.version, ...(p4 ? { newProblemAssessment: assessment, expectedFinalSetVersion: detail.finalSetVersion } : {}) }, '工单受理')}>受理工单</Button>
          </div>
        </Section>
        {p4 && <Section title="重复问题关闭">
          <label className="filter-item">引用历史终局
            <Select style={{ width: 320 }} aria-label="引用历史终局" value={priorFinalCaseId} disabled={blocked} onChange={value => setPriorFinalCaseId(value)}
              options={[{ value: '', label: '请选择已核对的历史结论' }, ...histories.filter(old => reviewed.includes(old.afterSaleId)).map(old => ({ value: old.afterSaleId, label: `${old.afterSaleId} · ${decisionLabel(old.decisionType ?? '')}` }))]} />
          </label>
          <label className="filter-item">重复问题关闭原因
            <Input.TextArea style={{ width: 'min(100%, 720px)' }} rows={4} maxLength={500} value={duplicateReason} disabled={blocked} onChange={event => setDuplicateReason(event.target.value)} />
          </label>
          <div><Checkbox checked={duplicateConfirmed} disabled={blocked} onChange={event => setDuplicateConfirmed(event.target.checked)}>确认本次为重复问题并引用原结论</Checkbox></div>
          <div className="decision-actions">
            <Button danger type="primary" disabled={blocked || !historyChecked || !priorFinalCaseId || !hasText(duplicateReason) || !duplicateConfirmed}
              onClick={() => command('close-duplicate', { expectedVersion: detail.version, priorFinalCaseId, reason: duplicateReason }, '重复问题关闭')}>关闭重复问题</Button>
          </div>
        </Section>}
      </Section>}
      {detail.status === 'PROCESSING' && canHandle && <Section title="要求补证">
        <label className="filter-item">补证方
          <Select style={{ width: 320 }} aria-label="补证方" value={targetParty} disabled={blocked} onChange={value => setTargetParty(value as 'USER' | 'MERCHANT')}
            options={[{ value: 'USER', label: '用户' }, { value: 'MERCHANT', label: '商家' }]} />
        </label>
        <label className="filter-item">补证原因
          <Input.TextArea style={{ width: 'min(100%, 720px)' }} rows={4} maxLength={500} value={supplementReason} disabled={blocked} onChange={event => setSupplementReason(event.target.value)} />
        </label>
        <label className="filter-item">补证截止（UTC 毫秒）
          <Input value={deadline} placeholder="2026-10-02T12:00:00.000Z" disabled={blocked} onChange={event => setDeadline(event.target.value)} />
        </label>
        <p className="hint">请输入未来的 UTC 时间，格式 YYYY-MM-DDTHH:mm:ss.SSSZ。仅严格早于截止的补证可满足本轮。</p>
        <div className="decision-actions">
          <Button type="primary" disabled={blocked || !hasText(supplementReason) || !validDeadline}
            onClick={() => command('supplement-requests', { expectedVersion: detail.version, targetParty, reason: supplementReason, deadline }, '补证要求')}>提交补证要求</Button>
        </div>
      </Section>}
      {detail.status === 'PROCESSING' && canDecide && <Section title="最终裁决">
        <label className="filter-item">终局类型
          <Select style={{ width: 320 }} aria-label="终局类型" value={decisionType} disabled={blocked} onChange={value => setDecisionType(value as DecisionType)}
            options={(['REJECT', 'RESERVICE', 'OTHER', 'FULL_REFUND', 'PARTIAL_REFUND'] as const).map(value => ({ value, label: decisionLabel(value) }))} />
        </label>
        {isRefundDecision(decisionType) && <>
          <label className="filter-item">退款金额（元）
            <Input inputMode="decimal" value={refundAmountDraft} placeholder="例如 32.00" disabled={blocked} onChange={event => setRefundAmountDraft(event.target.value.trim())} />
          </label>
          <p className="hint">精确两位小数。全额退款必须等于订单真实实付金额，部分退款必须大于 0 且小于实付金额；以系统核算为准，不符将被拒绝。裁决成功后自动创建退款单，按微信支付原路退回异步执行。</p>
        </>}
        <label className="filter-item">终局原因
          <Input.TextArea style={{ width: 'min(100%, 720px)' }} rows={4} maxLength={500} value={reason} disabled={blocked} onChange={event => setReason(event.target.value)} />
        </label>
        {decisionType === 'RESERVICE' && <p className="hint">说明人工重新服务安排。该决定不会自动创建订单、预约或再次核销。</p>}
        <div><Checkbox checked={decisionConfirmed} disabled={blocked} onChange={event => setDecisionConfirmed(event.target.checked)}>确认基于双方证据作出最终处理</Checkbox></div>
        <div className="decision-actions">
          <Button type="primary" disabled={blocked || !hasText(reason) || !decisionConfirmed || isRefundDecision(decisionType) && !isAmountDraft(refundAmountDraft)}
            onClick={() => command('decisions', { expectedVersion: detail.version, decisionType, reason, refundAmount: isRefundDecision(decisionType) ? refundAmountDraft : null }, '最终裁决')}>提交最终决定</Button>
        </div>
        <p className="hint">裁决一次终局，不支持复审；提交前请核对双方证据与金额。</p>
      </Section>}
      {detail.decisionType && <Section title="已记录的最终决定">
        <p>{decisionLabel(detail.decisionType)}{detail.refundAmount && ` · 记录金额 ${detail.refundAmount}`}</p>
        <PreserveText>{detail.decisionReason}</PreserveText>
        {detail.decisionType && isRefundDecision(detail.decisionType) && <p className="hint">退款单已创建，按微信支付原路退回执行；执行进度以退款渠道回执为准，工单状态不再变化。</p>}
      </Section>}
      {!canHandle && !canDecide && <p className="hint">当前账号仅可查看售后工单与获权证据。</p>}
      {!client.canWriteCase(afterSaleId) && <p className="hint">当前页面未验证所选门店，请从指定商家与门店的售后列表进入后处理。</p>}
      {lastReceipt && <Section title="提交回执">
        <p>命令 {lastReceipt.commandId} · 工单 {lastReceipt.afterSaleId} · {caseStatusLabel(lastReceipt.status)} · 版本 {lastReceipt.version}</p>
        <p>提交（UTC）：{lastReceipt.occurredAt}{lastReceipt.decisionId && ` · 决定 ${lastReceipt.decisionId}`}{lastReceipt.supplementRequestId && ` · 补证轮次 ${lastReceipt.supplementRequestId}`}{lastReceipt.refundOrderId && ` · 退款单 ${lastReceipt.refundOrderId}（原路退回执行中）`}</p>
      </Section>}
    </>}
  </PageContainer>;
}
