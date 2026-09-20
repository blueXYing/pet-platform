import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { RequestFailure } from '../request';
import {
  decisionLabel, merchantTypeLabel, statusLabel, verificationLabel,
  type DecisionType, type ManualEvidence, type ReviewDetail,
} from '../api/merchantApplications';

type Client = ReturnType<typeof import('../api/merchantApplications').createMerchantApplicationClient>;

type MaterialRef = { kind: string; label: string; assetId: string; credentialType?: ManualEvidence['credentialType'] };
type EvidenceDraft = Omit<ManualEvidence, 'materialId' | 'materialSha256' | 'credentialType'>;

function failureText(error: unknown, fallback: string) {
  if (!(error instanceof RequestFailure)) return fallback;
  if (error.code === 'UNAUTHENTICATED' || error.code === 'STALE_CONTEXT') return 'SESSION_LOST';
  if (error.status === 409 || error.code.includes('CONFLICT')) return '状态已变化（版本冲突），请刷新后重试';
  if (error.code === 'FORBIDDEN') return '当前账号无权执行该操作';
  return `${fallback}（${error.code}）`;
}

export default function ReviewDetailPage({ client, canOperate, onAuthLost }: { client: Client; canOperate: boolean; onAuthLost: () => void }) {
  const { applicationId = '' } = useParams();
  const [detail, setDetail] = useState<ReviewDetail>();
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [digests, setDigests] = useState<Record<string, string>>({});
  const [previews, setPreviews] = useState<Record<string, string>>({});
  const [viewReason, setViewReason] = useState('商家入驻申请人工审核，逐项核对申请材料内容');
  const [decisionType, setDecisionType] = useState<DecisionType>('APPROVE');
  const [opinion, setOpinion] = useState('');
  const [internalNote, setInternalNote] = useState('');
  const [decisionConfirmed, setDecisionConfirmed] = useState(false);
  const [verifyReason, setVerifyReason] = useState('');
  const [verifyConfirmed, setVerifyConfirmed] = useState(false);
  const [drafts, setDrafts] = useState<Record<string, EvidenceDraft>>({});

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

  const materials = useMemo<MaterialRef[]>(() => {
    const snapshot = detail?.submittedRevision.snapshot;
    if (!snapshot) return [];
    const list: MaterialRef[] = snapshot.storePhotoAssetIds.map(id => ({ kind: 'storePhoto', label: `门店照片 ${id}`, assetId: id }));
    const singles: MaterialRef[] = [
      { kind: 'businessLicense', label: '营业执照', assetId: snapshot.businessLicenseAssetId ?? '', credentialType: 'CREDIT_CODE' },
      { kind: 'idCardFront', label: '身份证（人像面）', assetId: snapshot.idCardFrontAssetId ?? '', credentialType: 'IDENTITY_NUMBER' },
      { kind: 'idCardBack', label: '身份证（国徽面）', assetId: snapshot.idCardBackAssetId ?? '', credentialType: 'IDENTITY_NUMBER' },
      { kind: 'industryLicense', label: '行业许可证', assetId: snapshot.industryLicenseAssetId ?? '', credentialType: 'INDUSTRY_LICENSE' },
    ];
    return [...list, ...singles.filter(item => item.assetId)];
  }, [detail]);

  const evidenceMaterials = materials.filter(item => item.credentialType !== undefined);

  const EMPTY_DRAFT: EvidenceDraft = { subjectName: '', identifier: '', validityKind: 'DATED', validFrom: null, validTo: null };
  function patchDraft(assetId: string, patch: Partial<EvidenceDraft>) {
    setDrafts(current => ({ ...current, [assetId]: { ...(current[assetId] ?? EMPTY_DRAFT), ...patch } }));
  }

  async function run(action: () => Promise<unknown>, successText: string, reload = true) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await action();
      setNotice(successText);
      if (reload) load();
    } catch (caught) {
      const text = failureText(caught, '操作失败');
      if (text === 'SESSION_LOST') { onAuthLost(); return; }
      setError(text);
    } finally { setBusy(false); }
  }

  const viewMaterial = (assetId: string) => run(async () => {
    if (!detail || !viewReason || viewReason.trim().length < 10) throw new RequestFailure('REASON_REQUIRED', 0);
    const grant = await client.issueReadGrant(applicationId, assetId, {
      submissionRevisionId: detail.submittedRevisionId,
      purposeCode: 'MERCHANT_APPLICATION_REVIEW',
      reason: viewReason.trim(),
      confirmed: true,
    });
    const content = await client.consumeReadGrant(grant.readUrl);
    setDigests(current => ({ ...current, [assetId]: content.sha256 }));
    setPreviews(current => ({ ...current, [assetId]: URL.createObjectURL(content.blob) }));
  }, '材料已通过一次性水印读取展示；如需再次查看须重新申请', false);

  const claim = () => detail && run(() => client.claim(applicationId, detail.task.version), '任务已领取');
  const release = () => detail && run(() => client.release(applicationId, detail.task.version), '任务已释放，回到待领取');
  const submitVerification = () => detail && run(() => {
    const evidenceItems = evidenceMaterials.map(item => {
      const draft = drafts[item.assetId];
      const sha256 = digests[item.assetId];
      if (!draft || !sha256) throw new RequestFailure('EVIDENCE_INCOMPLETE', 0);
      return { ...draft, materialId: item.assetId, materialSha256: sha256, credentialType: item.credentialType! };
    });
    return client.recordManualVerification(applicationId, {
      submissionRevisionId: detail.submittedRevisionId,
      expectedVersion: detail.version,
      expectedTaskVersion: detail.task.version,
      evidenceItems,
      reason: verifyReason.trim(),
      confirmed: verifyConfirmed,
    });
  }, '人工核验已记录');
  const submitDecision = () => detail && run(() => client.decide(applicationId, {
    decisionType,
    submissionRevisionId: detail.submittedRevisionId,
    expectedVersion: detail.version,
    expectedTaskVersion: detail.task.version,
    opinion: opinion.trim(),
    internalNote: internalNote.trim(),
    confirmed: decisionConfirmed,
  }), '审核决定已提交');

  if (!detail && !error) return <main><p role="status">加载中</p></main>;
  if (!detail) return <main><h1>申请详情</h1><p role="alert">{error}</p><p><Link to="/merchant-applications">返回列表</Link></p></main>;

  const snapshot = detail.submittedRevision.snapshot;
  const taskClaimed = detail.task.status === 'CLAIMED';
  const approveBlocked = detail.subjectVerificationStatus !== 'VERIFIED';
  const decisionNeedsOpinion = decisionType !== 'APPROVE';
  const verificationReady = verifyConfirmed && verifyReason.trim().length >= 10
    && evidenceMaterials.every(item => digests[item.assetId] && drafts[item.assetId]?.subjectName && drafts[item.assetId]?.identifier);
  const decisionReady = decisionConfirmed && (!decisionNeedsOpinion || opinion.trim().length > 0) && !approveBlocked;

  return <main>
    <h1>申请 {detail.applicationNo}</h1>
    <p>
      状态：<strong>{statusLabel(detail.status)}</strong> · 类型 {merchantTypeLabel(detail.merchantTypeCode)} · 城市 {detail.cityCode} ·
      核验 <strong>{verificationLabel(detail.subjectVerificationStatus)}</strong> · 提交于 {detail.submittedAt}
    </p>
    {notice && <p role="status">{notice}</p>}
    {error && <p role="alert">{error}</p>}

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
            : <button disabled={busy || viewReason.trim().length < 10} onClick={() => void viewMaterial(item.assetId)}>申请一次性查看</button>)}
          {!canOperate && <span className="hint">需审核权限</span>}
        </li>)}
      </ul>
    </section>

    <section aria-labelledby="task-heading">
      <h2 id="task-heading">审核任务</h2>
      <p>任务状态：{detail.task.status === 'AVAILABLE' ? '待领取' : `已领取（任务 ${detail.task.taskId}）`} · 任务版本 {detail.task.version}</p>
      {canOperate && !taskClaimed && <button disabled={busy} onClick={() => void claim()}>领取任务</button>}
      {canOperate && taskClaimed && <button disabled={busy} onClick={() => void release()}>释放任务</button>}
      {detail.latestDecision && <p>最近决定：{decisionLabel(detail.latestDecision.decisionType)} · {detail.latestDecision.decidedAt} · {detail.latestDecision.opinion}</p>}
    </section>

    {canOperate && taskClaimed && <section aria-labelledby="verify-heading">
      <h2 id="verify-heading">人工核验（主体/证件）</h2>
      <p className="hint">须先完成对应材料的一次性查看，核验内容与所见材料绑定；全部证件核验完成前不能通过审核。</p>
      <table className="evidence">
        <thead><tr><th>材料</th><th>证件主体</th><th>证号/信用代码</th><th>有效期</th><th>状态</th></tr></thead>
        <tbody>
          {evidenceMaterials.map(item => {
            const draft = drafts[item.assetId];
            const viewed = Boolean(digests[item.assetId]);
            return <tr key={item.assetId}>
              <th>{item.label}</th>
              <td><input aria-label={`${item.label} 证件主体`} value={draft?.subjectName ?? ''} disabled={!viewed} onChange={event => patchDraft(item.assetId, { subjectName: event.target.value })} /></td>
              <td><input aria-label={`${item.label} 证号`} value={draft?.identifier ?? ''} disabled={!viewed} onChange={event => patchDraft(item.assetId, { identifier: event.target.value })} /></td>
              <td>
                <select aria-label={`${item.label} 有效期类型`} value={draft?.validityKind ?? 'DATED'} disabled={!viewed} onChange={event => patchDraft(item.assetId, { validityKind: event.target.value as EvidenceDraft['validityKind'] })}>
                  <option value="DATED">有期限</option>
                  <option value="LONG_TERM">长期</option>
                </select>
                {draft?.validityKind !== 'LONG_TERM' && <>
                  <input type="date" aria-label={`${item.label} 生效日`} value={draft?.validFrom ?? ''} disabled={!viewed} onChange={event => patchDraft(item.assetId, { validFrom: event.target.value || null })} />
                  <input type="date" aria-label={`${item.label} 到期日`} value={draft?.validTo ?? ''} disabled={!viewed} onChange={event => patchDraft(item.assetId, { validTo: event.target.value || null })} />
                </>}
              </td>
              <td>{viewed ? '已查看' : '未查看'}</td>
            </tr>;
          })}
        </tbody>
      </table>
      <label>核验说明（≥10字）<input value={verifyReason} onChange={event => setVerifyReason(event.target.value)} placeholder="如：已核对证件原件与主体一致，编号与有效期无误" /></label>
      <label><input type="checkbox" checked={verifyConfirmed} onChange={event => setVerifyConfirmed(event.target.checked)} /> 我已逐项核对上述证件信息与所见材料一致</label>
      <button disabled={busy || !verificationReady} onClick={() => void submitVerification()}>提交人工核验</button>
    </section>}

    {canOperate && taskClaimed && <section aria-labelledby="decision-heading">
      <h2 id="decision-heading">审核决定</h2>
      {approveBlocked && <p role="alert">主体核验未完成：核验完成前不能选择“通过”以外的决定提交（通过按钮已禁用）。</p>}
      <label>决定类型
        <select value={decisionType} onChange={event => setDecisionType(event.target.value as DecisionType)}>
          <option value="APPROVE" disabled={approveBlocked}>通过（建立商家档案）</option>
          <option value="REQUEST_CORRECTION">要求补正（申请人修改后重新提交）</option>
          <option value="REJECT">拒绝</option>
        </select>
      </label>
      <label>审核意见{decisionNeedsOpinion ? '（必填）' : ''}<textarea value={opinion} onChange={event => setOpinion(event.target.value)} rows={3} /></label>
      <label>内部备注（仅运营可见）<textarea value={internalNote} onChange={event => setInternalNote(event.target.value)} rows={2} /></label>
      <label><input type="checkbox" checked={decisionConfirmed} onChange={event => setDecisionConfirmed(event.target.checked)} /> 我确认本决定基于已查看的材料与核验结果</label>
      <button disabled={busy || !decisionReady} onClick={() => void submitDecision()}>提交审核决定</button>
    </section>}
  </main>;
}
