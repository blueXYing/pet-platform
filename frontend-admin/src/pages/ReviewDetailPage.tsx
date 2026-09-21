import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { RequestFailure } from '../request';
import {
  decisionLabel, merchantTypeLabel, statusLabel, validateMaterialReferences, verificationLabel,
  type MaterialReference, type ReviewDetail,
} from '../api/merchantApplications';

type Client = ReturnType<typeof import('../api/merchantApplications').createMerchantApplicationClient>;

// Backend manual-verification semantics: one evidence row per credential type,
// IDENTITY_NUMBER is bound to ID_CARD_BACK only, and every row quotes the
// revision's registered merchant_material id+sha256 verbatim
// (MerchantApplicationService.verify; CCR-A002-MATERIAL-REF-001).
type EvidenceGroup = {
  credentialType: 'CREDIT_CODE' | 'IDENTITY_NUMBER' | 'INDUSTRY_LICENSE';
  label: string;
  reference: MaterialReference;
  viewAssetIds: string[];
};
type EvidenceDraft = { subjectName: string; identifier: string; validityKind: 'DATED' | 'LONG_TERM'; validFrom: string | null; validTo: string | null };
const EMPTY_DRAFT: EvidenceDraft = { subjectName: '', identifier: '', validityKind: 'DATED', validFrom: null, validTo: null };

function failureText(error: unknown, fallback: string) {
  if (!(error instanceof RequestFailure)) return fallback;
  if (error.code === 'UNAUTHENTICATED' || error.code === 'STALE_CONTEXT') return 'SESSION_LOST';
  if (error.status === 409 || error.code.includes('CONFLICT')) return '状态已变化（版本冲突），请刷新后重试';
  if (error.code === 'FORBIDDEN') return '当前账号无权执行该操作';
  return `${fallback}（${error.code}）`;
}

// Unknown outcome = the request may or may not have been applied server-side.
// 5xx (incl. 503 COMMON_DEPENDENCY_UNAVAILABLE returned on unknown commit) and
// unparseable responses are unknown; client-side rejections (INVALID_ADMIN_PATH)
// and definitive 4xx contract answers are not.
function unknownOutcome(error: unknown) {
  if (error instanceof RequestFailure) {
    if (error.code === 'INVALID_ADMIN_PATH') return false;
    if (error.code === 'INVALID_RESPONSE') return true;
    return error.status >= 500;
  }
  return true;
}

type PendingKind = 'claim' | 'release' | 'decide' | 'grant' | 'verify';

// A pending intent is resolved ONLY by replaying the SAME requestId: the
// idempotent receipt is the authoritative command outcome. The application
// snapshot carries no per-command correlation (an observed state may predate
// the in-flight request, may still land afterwards, or may belong to another
// operation), so a refresh never clears the pending intent — it only updates
// what is displayed.

export default function ReviewDetailPage({ client, canOperate, onAuthLost }: { client: Client; canOperate: boolean; onAuthLost: () => void }) {
  const { applicationId = '' } = useParams();
  const [detail, setDetail] = useState<ReviewDetail>();
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [previews, setPreviews] = useState<Record<string, string>>({});
  const [viewReason, setViewReason] = useState('商家入驻申请人工审核，逐项核对申请材料内容');
  const [decisionType, setDecisionType] = useState<DecisionTypeValue>('APPROVE');
  const [opinion, setOpinion] = useState('');
  const [internalNote, setInternalNote] = useState('');
  const [decisionConfirmed, setDecisionConfirmed] = useState(false);
  const [verifyReason, setVerifyReason] = useState('');
  const [verifyConfirmed, setVerifyConfirmed] = useState(false);
  const [drafts, setDrafts] = useState<Record<string, EvidenceDraft>>({});
  // Survives an unknown-outcome write so retry reuses the SAME requestId (idempotent receipt).
  const [retry, setRetry] = useState<{ kind: PendingKind; label: string; run: () => Promise<void> } | null>(null);
  const retryRef = useRef<{ kind: PendingKind; label: string; run: () => Promise<void> } | null>(null);
  const updateRetry = (value: { kind: PendingKind; label: string; run: () => Promise<void> } | null) => {
    retryRef.current = value;
    setRetry(value);
  };

  const load = useCallback(() => {
    setBusy(true);
    setError('');
    client.get(applicationId)
      .then(data => { setDetail(data); setBusy(false); })
      .catch((caught: unknown) => {
        setBusy(false);
        const text = failureText(caught, '加载申请失败');
        if (text === 'SESSION_LOST') { onAuthLost(); return; }
        setError(text);
      });
  }, [client, applicationId, onAuthLost]);
  useEffect(() => { void load(); }, [load]);

  function patchDraft(group: string, patch: Partial<EvidenceDraft>) {
    setDrafts(current => ({ ...current, [group]: { ...(current[group] ?? EMPTY_DRAFT), ...patch } }));
  }

  async function attemptWrite(kind: PendingKind, requestId: string, invoke: (id: string) => Promise<unknown>, successText: string, reload: boolean) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await invoke(requestId);
      updateRetry(null);
      setNotice(successText);
      if (reload) load(); else setBusy(false);
    } catch (caught) {
      setBusy(false);
      if (caught instanceof RequestFailure && (caught.code === 'UNAUTHENTICATED' || caught.code === 'STALE_CONTEXT')) { onAuthLost(); return; }
      if (!unknownOutcome(caught)) {
        updateRetry(null);
        setError(failureText(caught, '操作失败'));
        return;
      }
      setError('结果未知：后台可能已执行该操作；重试将复用原请求标识以取得幂等回执，刷新后将按权威状态判定终态。');
      updateRetry({
        kind,
        label: successText,
        run: () => attemptWrite(kind, requestId, invoke, successText, reload),
      });
    }
  }

  function runWrite(kind: PendingKind, invoke: (requestId: string) => Promise<unknown>, successText: string, reload = true) {
    // An unresolved write keeps its original requestId; conflicting new writes are
    // refused until the operator retries the original or a machine-verifiable
    // terminal verdict clears it via an authoritative read.
    if (retryRef.current) {
      setError('存在结果未知的操作：请先重试原操作（复用原请求标识），或刷新状态取得权威终态判定。');
      return Promise.resolve();
    }
    return attemptWrite(kind, crypto.randomUUID(), invoke, successText, reload);
  }

  const viewMaterial = (assetId: string) => {
    if (!detail) return;
    void runWrite('grant', async requestId => {
      if (viewReason.trim().length < 10) throw new RequestFailure('REASON_REQUIRED', 0);
      const grant = await client.issueReadGrant(applicationId, assetId, {
        submissionRevisionId: detail.submittedRevisionId,
        purposeCode: 'MERCHANT_APPLICATION_REVIEW',
        reason: viewReason.trim(),
        confirmed: true,
      }, requestId);
      const content = await client.consumeReadGrant(grant.readUrl);
      setPreviews(current => ({ ...current, [assetId]: URL.createObjectURL(content) }));
    }, '材料已通过一次性水印读取展示；如需再次查看须重新申请', false);
  };

  const claim = () => detail && void runWrite('claim', id => client.claim(applicationId, detail.task.version, id), '任务已领取');
  const release = () => detail && void runWrite('release', id => client.release(applicationId, detail.task.version, id), '任务已释放，回到待领取');

  const submitVerification = () => detail && evidenceGroups && void runWrite('verify', id => client.recordManualVerification(applicationId, {
    submissionRevisionId: detail.submittedRevisionId,
    expectedVersion: detail.version,
    expectedTaskVersion: detail.task.version,
    evidenceItems: evidenceGroups.map(group => ({
      materialId: group.reference.materialId,
      materialSha256: group.reference.materialSha256,
      credentialType: group.credentialType,
      ...drafts[group.credentialType] ?? EMPTY_DRAFT,
    })),
    reason: verifyReason.trim(),
    confirmed: verifyConfirmed,
  }, id), '人工核验已提交');
  const submitDecision = () => detail && void runWrite('decide', id => client.decide(applicationId, {
    decisionType,
    submissionRevisionId: detail.submittedRevisionId,
    expectedVersion: detail.version,
    expectedTaskVersion: detail.task.version,
    opinion: opinion.trim(),
    internalNote: internalNote.trim(),
    confirmed: decisionConfirmed,
  }, id), '审核决定已提交');

  if (!detail && !error) return <main><p role="status">加载中</p></main>;
  if (!detail) return <main><h1>申请详情</h1><p role="alert">{error}</p><p><Link to="/merchant-applications">返回列表</Link></p></main>;

  const snapshot = detail.submittedRevision.snapshot;
  const taskClaimed = detail.task.status === 'CLAIMED';
  const approveBlocked = detail.subjectVerificationStatus !== 'VERIFIED';
  const decisionNeedsOpinion = decisionType !== 'APPROVE';
  // Only APPROVE requires completed subject verification (26号裁决); REJECT/REQUEST_CORRECTION stay available.
  const decisionReady = decisionConfirmed && (!decisionNeedsOpinion || opinion.trim().length > 0) && (decisionType !== 'APPROVE' || !approveBlocked);

  // Evidence rows come exclusively from the authoritative material references;
  // identity requires BOTH sides viewed, evidence quotes materialId+sha verbatim.
  const references = validateMaterialReferences(detail.submittedRevision.materialReferences);
  const referenceByType = (type: string) => detail.submittedRevision.materialReferences?.find(item => item.materialType === type);
  const evidenceGroups: EvidenceGroup[] | null = references.ok ? buildEvidenceGroups(snapshot, referenceByType) : null;
  const groupViewed = (group: EvidenceGroup) => group.viewAssetIds.length > 0 && group.viewAssetIds.every(assetId => previews[assetId] !== undefined);
  const verificationReady = evidenceGroups !== null && verifyConfirmed && verifyReason.trim().length >= 10
    && evidenceGroups.every(group => groupViewed(group)
      && (drafts[group.credentialType]?.subjectName ?? '') !== '' && (drafts[group.credentialType]?.identifier ?? '') !== '');

  const materials = [
    ...snapshot.storePhotoAssetIds.map(id => ({ label: `门店照片 ${id}`, assetId: id })),
    ...(snapshot.businessLicenseAssetId ? [{ label: '营业执照', assetId: snapshot.businessLicenseAssetId }] : []),
    ...(snapshot.idCardFrontAssetId ? [{ label: '身份证（人像面）', assetId: snapshot.idCardFrontAssetId }] : []),
    ...(snapshot.idCardBackAssetId ? [{ label: '身份证（国徽面）', assetId: snapshot.idCardBackAssetId }] : []),
    ...(snapshot.industryLicenseAssetId ? [{ label: '行业许可证', assetId: snapshot.industryLicenseAssetId }] : []),
  ];

  return <main>
    <h1>申请 {detail.applicationNo}</h1>
    <p>
      状态：<strong>{statusLabel(detail.status)}</strong> · 类型 {merchantTypeLabel(detail.merchantTypeCode)} · 城市 {detail.cityCode} ·
      核验 <strong>{verificationLabel(detail.subjectVerificationStatus)}</strong> · 提交于 {detail.submittedAt}
    </p>
    {notice && <p role="status">{notice}</p>}
    {error && <p role="alert">{error}</p>}
    {retry && <p>
      <button onClick={() => void retry.run()} disabled={busy}>重试原操作（复用原请求标识）</button>
      <button onClick={() => void load()} disabled={busy}>刷新申请状态</button>
      <span>{retry.kind === 'grant'
        ? '材料授权结果无法由申请快照判定；'
        : '申请快照与本次命令无 requestId 关联，不能证明其已终结；'}仅可通过重试原操作以同一请求标识取得幂等回执解除未决；刷新仅更新当前展示。</span>
    </p>}

    <section aria-labelledby="snapshot-heading">
      <h2 id="snapshot-heading">提交版本（第 {detail.submittedRevision.revisionNo} 版 · {detail.submittedRevision.createdAt}）</h2>
      <dl className="snapshot">
        <div><dt>商家名称</dt><dd>{snapshot.merchantName}</dd></div>
        <div><dt>地址</dt><dd>{snapshot.address}（{snapshot.longitude ?? '—'}, {snapshot.latitude ?? '—'}）</dd></div>
        <div><dt>简介</dt><dd>{snapshot.introduction || '—'}</dd></div>
        <div><dt>联系人（脱敏）</dt><dd>{snapshot.contactNameMasked} / {snapshot.contactPhoneMasked} / {snapshot.emailMasked}</dd></div>
      </dl>
    </section>

    <section aria-labelledby="materials-heading">
      <h2 id="materials-heading">申请材料</h2>
      <p className="hint">查看材料会记入审计；每次授权仅可读取一次并带水印，重复查看需再次说明理由。</p>
      {canOperate && <label>查看理由（10～500字）<input value={viewReason} onChange={event => setViewReason(event.target.value)} /></label>}
      <ul className="materials">
        {materials.map(item => <li key={item.assetId}>
          <span>{item.label}</span> <code>{item.assetId}</code>
          {canOperate && (previews[item.assetId]
            ? <img src={previews[item.assetId]} alt={`${item.label} 水印读取结果`} height={160} />
            : <button disabled={busy || retry !== null || viewReason.trim().length < 10} onClick={() => viewMaterial(item.assetId)}>申请一次性查看</button>)}
          {!canOperate && <span className="hint">需审核权限</span>}
        </li>)}
      </ul>
    </section>

    <section aria-labelledby="task-heading">
      <h2 id="task-heading">审核任务</h2>
      <p>任务状态：{detail.task.status === 'AVAILABLE' ? '待领取' : `已领取（任务 ${detail.task.taskId}）`} · 任务版本 {detail.task.version}</p>
      {canOperate && !taskClaimed && <button disabled={busy || retry !== null} onClick={() => void claim()}>领取任务</button>}
      {canOperate && taskClaimed && <button disabled={busy || retry !== null} onClick={() => void release()}>释放任务</button>}
      {detail.latestDecision && <p>最近决定：{decisionLabel(detail.latestDecision.decisionType)} · {detail.latestDecision.decidedAt} · {detail.latestDecision.opinion}</p>}
    </section>

    {canOperate && taskClaimed && <section aria-labelledby="verify-heading">
      <h2 id="verify-heading">人工核验（主体/证件）</h2>
      {!references.ok || evidenceGroups === null ? <>
        <p role="alert">{references.issue}</p>
        <p className="hint">核验证据必须引用提交版本登记的材料编号与摘要；投影缺失或不合法时提交保持禁用，不以资产编号或读取字节替代。</p>
        <button disabled aria-disabled="true">提交人工核验（材料引用不可用）</button>
      </> : <>
        <p className="hint">身份证须正反面都查看后合并为一条主体核验；每类证件一条证据，材料编号与摘要取自权威引用，重复类型会被后端拒绝。</p>
        <table className="evidence">
          <thead><tr><th>证件</th><th>证件主体</th><th>证号/信用代码</th><th>有效期</th><th>查看状态</th></tr></thead>
          <tbody>
            {evidenceGroups.map(group => {
              const draft = drafts[group.credentialType];
              const viewed = groupViewed(group);
              return <tr key={group.credentialType}>
                <th>{group.label}</th>
                <td><input aria-label={`${group.label} 证件主体`} value={draft?.subjectName ?? ''} disabled={!viewed} onChange={event => patchDraft(group.credentialType, { subjectName: event.target.value })} /></td>
                <td><input aria-label={`${group.label} 证号`} value={draft?.identifier ?? ''} disabled={!viewed} onChange={event => patchDraft(group.credentialType, { identifier: event.target.value })} /></td>
                <td>
                  <select aria-label={`${group.label} 有效期类型`} value={draft?.validityKind ?? 'DATED'} disabled={!viewed} onChange={event => patchDraft(group.credentialType, { validityKind: event.target.value as EvidenceDraft['validityKind'] })}>
                    <option value="DATED">有期限</option>
                    <option value="LONG_TERM">长期</option>
                  </select>
                  {draft?.validityKind !== 'LONG_TERM' && <>
                    <input type="date" aria-label={`${group.label} 生效日`} value={draft?.validFrom ?? ''} disabled={!viewed} onChange={event => patchDraft(group.credentialType, { validFrom: event.target.value || null })} />
                    <input type="date" aria-label={`${group.label} 到期日`} value={draft?.validTo ?? ''} disabled={!viewed} onChange={event => patchDraft(group.credentialType, { validTo: event.target.value || null })} />
                  </>}
                </td>
                <td>{viewed ? '已查看' : '未查看'}</td>
              </tr>;
            })}
          </tbody>
        </table>
        <label>核验说明（≥10字）<input value={verifyReason} onChange={event => setVerifyReason(event.target.value)} placeholder="如：已核对证件原件与主体一致，编号与有效期无误" /></label>
        <label><input type="checkbox" checked={verifyConfirmed} onChange={event => setVerifyConfirmed(event.target.checked)} /> 我已逐项核对上述证件信息与所见材料一致</label>
        <button disabled={busy || retry !== null || !verificationReady} onClick={() => void submitVerification()}>提交人工核验</button>
      </>}
    </section>}

    {canOperate && taskClaimed && <section aria-labelledby="decision-heading">
      <h2 id="decision-heading">审核决定</h2>
      {approveBlocked && <p role="alert">主体核验未完成：仅“通过”需先完成人工核验；材料有问题时可正常选择拒绝或要求补正。</p>}
      <label>决定类型
        <select value={decisionType} onChange={event => setDecisionType(event.target.value as DecisionTypeValue)}>
          <option value="APPROVE" disabled={approveBlocked}>通过（建立商家档案）</option>
          <option value="REQUEST_CORRECTION">要求补正（申请人修改后重新提交）</option>
          <option value="REJECT">拒绝</option>
        </select>
      </label>
      <label>审核意见{decisionNeedsOpinion ? '（必填）' : ''}<textarea value={opinion} onChange={event => setOpinion(event.target.value)} rows={3} /></label>
      <label>内部备注（仅运营可见）<textarea value={internalNote} onChange={event => setInternalNote(event.target.value)} rows={2} /></label>
      <label><input type="checkbox" checked={decisionConfirmed} onChange={event => setDecisionConfirmed(event.target.checked)} /> 我确认本决定基于已查看的材料与核验结果</label>
      <button disabled={busy || retry !== null || !decisionReady} onClick={() => void submitDecision()}>提交审核决定</button>
    </section>}
  </main>;
}

type DecisionTypeValue = 'APPROVE' | 'REJECT' | 'REQUEST_CORRECTION';

function buildEvidenceGroups(
  snapshot: import('../api/merchantApplications').ApplicationSnapshot,
  referenceByType: (type: string) => MaterialReference | undefined,
): EvidenceGroup[] {
  const groups: EvidenceGroup[] = [];
  const businessLicense = referenceByType('BUSINESS_LICENSE');
  if (businessLicense) groups.push({ credentialType: 'CREDIT_CODE', label: '营业执照', reference: businessLicense, viewAssetIds: [businessLicense.assetId] });
  const idCardBack = referenceByType('ID_CARD_BACK');
  if (idCardBack) groups.push({
    credentialType: 'IDENTITY_NUMBER', label: '身份证（正反面合并核验）', reference: idCardBack,
    viewAssetIds: [snapshot.idCardFrontAssetId, snapshot.idCardBackAssetId].filter((id): id is string => id !== null),
  });
  const industryLicense = referenceByType('INDUSTRY_LICENSE');
  if (industryLicense) groups.push({ credentialType: 'INDUSTRY_LICENSE', label: '行业许可证', reference: industryLicense, viewAssetIds: [industryLicense.assetId] });
  return groups;
}
