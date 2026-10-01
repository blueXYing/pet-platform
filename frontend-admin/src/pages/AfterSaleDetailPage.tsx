import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { AfterSaleFailure, caseStatusLabel, decisionLabel, validUtc, type Action, type ActionBody, type AfterSaleClient, type CaseDetail, type NonRefundDecision, type PendingIntent, type ReadGrant, type Receipt } from '../api/aftersales';

function errorText(caught: unknown, fallback: string) {
  if (!(caught instanceof AfterSaleFailure)) return fallback;
  return `${fallback}（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}`;
}
const hasText = (value: string) => /\S/.test(value) && value.length <= 500;
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
  const [decisionType, setDecisionType] = useState<NonRefundDecision>('REJECT');
  const [reason, setReason] = useState('');
  const [decisionConfirmed, setDecisionConfirmed] = useState(false);
  const [lastReceipt, setLastReceipt] = useState<Receipt>();
  function clearPreviews() { for (const url of Object.values(urls.current)) URL.revokeObjectURL(url); urls.current = {}; setPreviews({}); }
  function resetDrafts() { setAssessment(''); setAcceptConfirmed(false); setPriorFinalCaseId(''); setDuplicateReason(''); setDuplicateConfirmed(false); setSupplementReason(''); setDeadline(''); setReason(''); setDecisionConfirmed(false); }
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
  return <main className="aftersale-page">
    <div className="aftersale-title"><h1>售后工单详情</h1><Link to={back}>返回售后列表</Link><button disabled={busy} onClick={() => { setError(''); void load(); }}>刷新工单</button></div>
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {retry && <div className="aftersale-callout"><p>待确认操作：{retry.label}。保留原请求标识和原始内容，新的处理操作暂停。</p>
      {retryBody && <details><summary>查看原处理内容</summary>{typeof retryBody.expectedVersion === 'string' && <p>原工单版本：{retryBody.expectedVersion}</p>}{typeof retryBody.decisionType === 'string' && <p>原处理类型：{decisionLabel(retryBody.decisionType)}</p>}{typeof retryBody.reason === 'string' && <p className="preserve-text">原处理原因：{retryBody.reason}</p>}{typeof retryBody.newProblemAssessment === 'string' && <p className="preserve-text">原新问题评估：{retryBody.newProblemAssessment}</p>}{typeof retryBody.targetParty === 'string' && <p>原补证方：{retryBody.targetParty === 'USER' ? '用户' : '商家'}</p>}{typeof retryBody.deadline === 'string' && <p>原补证截止（UTC）：{retryBody.deadline}</p>}{typeof retryBody.priorFinalCaseId === 'string' && <p>引用原终局：{retryBody.priorFinalCaseId}</p>}</details>}
      <button disabled={busy || !retryAllowed} onClick={() => void execute(retry)}>重试原操作</button></div>}
    {busy && <p role="status">正在读取或提交</p>}
    {detail && <>
      <dl className="snapshot">
        <div><dt>工单编号</dt><dd>{detail.afterSaleId}</dd></div><div><dt>订单编号</dt><dd>{detail.orderId}</dd></div><div><dt>状态</dt><dd>{caseStatusLabel(detail.status)}</dd></div><div><dt>版本</dt><dd>{detail.version}</dd></div>
        <div><dt>来源阶段</dt><dd>{detail.sourceStage === 'VERIFIED' ? '已核销' : '未核销 · 服务已开始'}</dd></div><div><dt>问题 / 诉求代码</dt><dd>{detail.typeCode} / {detail.demandCode}</dd></div><div><dt>诉求金额</dt><dd>{detail.requestedAmount ?? '未指定'}</dd></div><div><dt>创建（UTC）</dt><dd>{detail.createdAt}</dd></div><div><dt>申请截止（UTC）</dt><dd>{detail.deadline}</dd></div>
      </dl>
      <section><h2>用户问题说明</h2><p className="preserve-text">{detail.description}</p>{detail.newProblemStatement && <><h3>终局后新问题说明</h3><p className="preserve-text">{detail.newProblemStatement}</p></>}</section>
      {detail.supplementRequestId && <section className="aftersale-callout"><h2>当前补证要求</h2><p>轮次 {detail.supplementRequestId} · 指定 {detail.supplementTarget === 'USER' ? '用户' : '商家'}</p><p className="preserve-text">{detail.supplementReason}</p><p>截止（UTC）：{detail.supplementDeadline}</p><p className="hint">达到截止后不能满足该轮补证，后台超时任务恢复处理中；页面不会自动裁决。</p></section>}
      <section><h2>双方入卷证据</h2><label>证据查看用途<textarea maxLength={500} value={viewReason} onChange={event => setViewReason(event.target.value)} /></label>
        {!detail.evidence.length && <p>尚无证据批次。</p>}
        {detail.evidence.map(batch => <article className="aftersale-card" key={batch.batchId}><h3>{batch.submitterType === 'USER' ? '用户' : '商家'}证据 · {batch.batchId}</h3><p>提交（UTC）：{batch.submittedAt}{batch.opinionCode && ` · 商家意见：${({ AGREE: '同意', PARTLY_AGREE: '部分同意', DISAGREE: '不同意', NEED_USER_SUPPLEMENT: '请求用户补充' } as Record<string, string>)[batch.opinionCode] ?? batch.opinionCode}`}</p>{batch.text && <p className="preserve-text">{batch.text}</p>}
          <ul className="materials">{batch.assetIds.map(assetId => { const key = `${batch.batchId}/${assetId}`; return <li key={assetId}><span>图片 {assetId}</span><button disabled={busy || retry !== undefined || !hasText(viewReason)} onClick={() => view(batch.batchId, assetId)}>申请查看证据</button>{previews[key] && <><img src={previews[key]} alt={`售后证据 ${assetId}`} /><button onClick={() => { URL.revokeObjectURL(urls.current[key]); delete urls.current[key]; setPreviews({ ...urls.current }); }}>隐藏图片</button></>}</li>; })}</ul>
        </article>)}
      </section>
      {p4 && <section><h2>同订单历史终局核对</h2><p>必须逐笔核对当前完整历史后受理新问题。历史工单共 {detail.priorFinalCaseIds.length} 笔。</p><button disabled={busy} onClick={() => void loadHistory()}>读取历史终局</button>
        {histories.map(old => <article className="aftersale-card" key={old.afterSaleId}><h3>历史工单 {old.afterSaleId} · {decisionLabel(old.decisionType ?? '')}</h3><p className="preserve-text">原问题：{old.description}</p><p className="preserve-text">原结论：{old.decisionReason}</p><Link to={`/aftersales/${old.afterSaleId}${params.size ? `?${params}` : ''}`}>查看历史卷宗与证据</Link><label><input type="checkbox" disabled={blocked} checked={reviewed.includes(old.afterSaleId)} onChange={event => setReviewed(current => event.target.checked ? [...current, old.afterSaleId] : current.filter(id => id !== old.afterSaleId))} />已核对这笔终局及当前新问题说明</label></article>)}
      </section>}
      {detail.status === 'PENDING' && canHandle && <section><h2>受理或重复问题关闭</h2>
        <fieldset disabled={blocked}><legend>受理</legend>{p4 && <label>新问题核对意见<textarea value={assessment} maxLength={500} onChange={event => setAssessment(event.target.value)} /></label>}<label><input type="checkbox" checked={acceptConfirmed} onChange={event => setAcceptConfirmed(event.target.checked)} />确认问题符合受理条件，已有终局时确属新问题</label><button disabled={!acceptConfirmed || !historyChecked || p4 && !hasText(assessment)} onClick={() => command('accept', { expectedVersion: detail.version, ...(p4 ? { newProblemAssessment: assessment, expectedFinalSetVersion: detail.finalSetVersion } : {}) }, '工单受理')}>受理工单</button></fieldset>
        {p4 && <fieldset disabled={blocked}><legend>重复问题关闭</legend><label>引用历史终局<select aria-label="引用历史终局" value={priorFinalCaseId} onChange={event => setPriorFinalCaseId(event.target.value)}><option value="">请选择已核对的历史结论</option>{histories.filter(old => reviewed.includes(old.afterSaleId)).map(old => <option key={old.afterSaleId} value={old.afterSaleId}>{old.afterSaleId} · {decisionLabel(old.decisionType ?? '')}</option>)}</select></label><label>重复问题关闭原因<textarea maxLength={500} value={duplicateReason} onChange={event => setDuplicateReason(event.target.value)} /></label><label><input type="checkbox" checked={duplicateConfirmed} onChange={event => setDuplicateConfirmed(event.target.checked)} />确认本次为重复问题并引用原结论</label><button disabled={!historyChecked || !priorFinalCaseId || !hasText(duplicateReason) || !duplicateConfirmed} onClick={() => command('close-duplicate', { expectedVersion: detail.version, priorFinalCaseId, reason: duplicateReason }, '重复问题关闭')}>关闭重复问题</button></fieldset>}
      </section>}
      {detail.status === 'PROCESSING' && canHandle && <section><h2>要求补证</h2><fieldset disabled={blocked}><label>补证方<select aria-label="补证方" value={targetParty} onChange={event => setTargetParty(event.target.value as 'USER' | 'MERCHANT')}><option value="USER">用户</option><option value="MERCHANT">商家</option></select></label><label>补证原因<textarea maxLength={500} value={supplementReason} onChange={event => setSupplementReason(event.target.value)} /></label><label>补证截止（UTC 毫秒）<input value={deadline} placeholder="2026-10-02T12:00:00.000Z" onChange={event => setDeadline(event.target.value)} /></label><p className="hint">请输入未来的 UTC 时间，格式 YYYY-MM-DDTHH:mm:ss.SSSZ。仅严格早于截止的补证可满足本轮。</p><button disabled={!hasText(supplementReason) || !validDeadline} onClick={() => command('supplement-requests', { expectedVersion: detail.version, targetParty, reason: supplementReason, deadline }, '补证要求')}>提交补证要求</button></fieldset></section>}
      {detail.status === 'PROCESSING' && canDecide && <section><h2>非退款终局处理</h2><fieldset disabled={blocked}><label>终局类型<select aria-label="终局类型" value={decisionType} onChange={event => setDecisionType(event.target.value as NonRefundDecision)}>{(['REJECT', 'RESERVICE', 'OTHER'] as const).map(value => <option value={value} key={value}>{decisionLabel(value)}</option>)}</select></label><label>终局原因<textarea maxLength={500} value={reason} onChange={event => setReason(event.target.value)} /></label>{decisionType === 'RESERVICE' && <p className="hint">说明人工重新服务安排。该决定不会自动创建订单、预约或再次核销。</p>}<label><input type="checkbox" checked={decisionConfirmed} onChange={event => setDecisionConfirmed(event.target.checked)} />确认基于双方证据作出最终处理</label><button disabled={!hasText(reason) || !decisionConfirmed} onClick={() => command('decisions', { expectedVersion: detail.version, decisionType, reason, refundAmount: null }, '非退款终局决定')}>提交最终决定</button></fieldset></section>}
      {detail.decisionType && <section><h2>已记录的最终决定</h2><p>{decisionLabel(detail.decisionType)}{detail.refundAmount && ` · 记录金额 ${detail.refundAmount}`}</p><p className="preserve-text">{detail.decisionReason}</p></section>}
      {!canHandle && !canDecide && <p className="hint">当前账号仅可查看售后工单与获权证据。</p>}
      {!client.canWriteCase(afterSaleId) && <p className="hint">当前页面未验证所选门店，请从指定商家与门店的售后列表进入后处理。</p>}
      <p className="hint">全额退款、部分退款终裁尚未开放。</p>
      {lastReceipt && <section className="aftersale-callout"><h2>提交回执</h2><p>命令 {lastReceipt.commandId} · 工单 {lastReceipt.afterSaleId} · {caseStatusLabel(lastReceipt.status)} · 版本 {lastReceipt.version}</p><p>提交（UTC）：{lastReceipt.occurredAt}{lastReceipt.decisionId && ` · 决定 ${lastReceipt.decisionId}`}{lastReceipt.supplementRequestId && ` · 补证轮次 ${lastReceipt.supplementRequestId}`}</p></section>}
    </>}
  </main>;
}
