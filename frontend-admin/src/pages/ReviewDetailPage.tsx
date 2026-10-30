import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Button, Checkbox, DatePicker, Input, Select } from 'antd';
import dayjs from 'dayjs';
import type { TableColumnsType } from 'antd';
import { RequestFailure } from '../request';
import {
  decisionLabel, merchantTypeLabel, statusLabel, validateMaterialReferences, verificationLabel,
  type MaterialReference, type ReviewDetail,
} from '../api/merchantApplications';
import { DataTable } from '../ui/DataTable';
import { BackLink, PageContainer } from '../ui/PageContainer';
import { ErrorAlert, FactList, InlineStatus, Section } from '../ui/bits';

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

  if (!detail && !error) return <main><InlineStatus>加载中</InlineStatus></main>;
  if (!detail) return <PageContainer title="申请详情" back={<BackLink to="/merchant-applications">返回列表</BackLink>}><ErrorAlert>{error}</ErrorAlert></PageContainer>;

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
  // Backend date rules mirrored at the gate: validFrom required for every
  // credential; DATED requires validTo; LONG_TERM requires validTo == null.
  const draftComplete = (draft: EvidenceDraft | undefined) =>
    (draft?.subjectName ?? '') !== '' && (draft?.identifier ?? '') !== ''
    && draft?.validFrom != null && (draft.validityKind === 'LONG_TERM' ? draft.validTo == null : draft.validTo != null);
  const verificationReady = evidenceGroups !== null && verifyConfirmed && verifyReason.trim().length >= 10
    && evidenceGroups.every(group => groupViewed(group) && draftComplete(drafts[group.credentialType]));

  const materials = [
    ...snapshot.storePhotoAssetIds.map(id => ({ label: `门店照片 ${id}`, assetId: id })),
    ...(snapshot.businessLicenseAssetId ? [{ label: '营业执照', assetId: snapshot.businessLicenseAssetId }] : []),
    ...(snapshot.idCardFrontAssetId ? [{ label: '身份证（人像面）', assetId: snapshot.idCardFrontAssetId }] : []),
    ...(snapshot.idCardBackAssetId ? [{ label: '身份证（国徽面）', assetId: snapshot.idCardBackAssetId }] : []),
    ...(snapshot.industryLicenseAssetId ? [{ label: '行业许可证', assetId: snapshot.industryLicenseAssetId }] : []),
  ];

  const evidenceColumns: TableColumnsType<EvidenceGroup> = [
    { title: '证件', render: (_, group) => group.label },
    { title: '证件主体', render: (_, group) => {
      const draft = drafts[group.credentialType];
      const viewed = groupViewed(group);
      return <Input style={{ width: 160 }} aria-label={`${group.label} 证件主体`} value={draft?.subjectName ?? ''} disabled={!viewed} onChange={event => patchDraft(group.credentialType, { subjectName: event.target.value })} />;
    } },
    { title: '证号/信用代码', render: (_, group) => {
      const draft = drafts[group.credentialType];
      const viewed = groupViewed(group);
      return <Input style={{ width: 160 }} aria-label={`${group.label} 证号`} value={draft?.identifier ?? ''} disabled={!viewed} onChange={event => patchDraft(group.credentialType, { identifier: event.target.value })} />;
    } },
    { title: '有效期', render: (_, group) => {
      const draft = drafts[group.credentialType];
      const viewed = groupViewed(group);
      return <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <Select style={{ width: 160 }} aria-label={`${group.label} 有效期类型`} value={draft?.validityKind ?? 'DATED'} disabled={!viewed}
          onChange={value => patchDraft(group.credentialType, value === 'LONG_TERM'
            // Backend rules: validFrom is required for every credential and
            // LONG_TERM requires validTo == null — switching clears it.
            ? { validityKind: 'LONG_TERM', validTo: null }
            : { validityKind: 'DATED' })}
          options={[{ value: 'DATED', label: '有期限' }, { value: 'LONG_TERM', label: '长期' }]} />
        <DatePicker style={{ width: 160 }} format="YYYY-MM-DD" aria-label={`${group.label} 生效日`} value={draft?.validFrom ? dayjs(draft.validFrom) : null} disabled={!viewed}
          onChange={value => patchDraft(group.credentialType, { validFrom: value ? value.format('YYYY-MM-DD') : null })} />
        {draft?.validityKind !== 'LONG_TERM' && <DatePicker style={{ width: 160 }} format="YYYY-MM-DD" aria-label={`${group.label} 到期日`} value={draft?.validTo ? dayjs(draft.validTo) : null} disabled={!viewed}
          onChange={value => patchDraft(group.credentialType, { validTo: value ? value.format('YYYY-MM-DD') : null })} />}
      </div>;
    } },
    { title: '查看状态', render: (_, group) => groupViewed(group) ? '已查看' : '未查看' },
  ];

  return <PageContainer title={`申请 ${detail.applicationNo}`}>
    <p>
      状态：<strong>{statusLabel(detail.status)}</strong> · 类型 {merchantTypeLabel(detail.merchantTypeCode)} · 城市 {detail.cityCode} ·
      核验 <strong>{verificationLabel(detail.subjectVerificationStatus)}</strong> · 提交于 {detail.submittedAt}
    </p>
    {notice && <InlineStatus>{notice}</InlineStatus>}
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {retry && <p>
      <Button disabled={busy} onClick={() => void retry.run()}>重试原操作（复用原请求标识）</Button>
      <Button disabled={busy} onClick={() => void load()}>刷新申请状态</Button>
      <span>{retry.kind === 'grant'
        ? '材料授权结果无法由申请快照判定；'
        : '申请快照与本次命令无 requestId 关联，不能证明其已终结；'}仅可通过重试原操作以同一请求标识取得幂等回执解除未决；刷新仅更新当前展示。</span>
    </p>}

    <Section title={`提交版本（第 ${detail.submittedRevision.revisionNo} 版 · ${detail.submittedRevision.createdAt}）`}>
      <FactList items={[
        { label: '商家名称', value: snapshot.merchantName },
        { label: '地址', value: `${snapshot.address}（${snapshot.longitude ?? '—'}, ${snapshot.latitude ?? '—'}）` },
        { label: '简介', value: snapshot.introduction || '—' },
        { label: '联系人（脱敏）', value: `${snapshot.contactNameMasked} / ${snapshot.contactPhoneMasked} / ${snapshot.emailMasked}` },
      ]} />
    </Section>

    <Section title="申请材料">
      <p className="hint">查看材料会记入审计；每次授权仅可读取一次并带水印，重复查看需再次说明理由。</p>
      {canOperate && <label className="filter-item">查看理由（10～500字）<Input style={{ width: 'min(100%, 420px)' }} value={viewReason} onChange={event => setViewReason(event.target.value)} /></label>}
      <ul className="materials">
        {materials.map(item => <li key={item.assetId}>
          <span>{item.label}</span> <code>{item.assetId}</code>
          {canOperate && (previews[item.assetId]
            ? <img src={previews[item.assetId]} alt={`${item.label} 水印读取结果`} height={160} style={{ maxWidth: 'min(100%, 420px)', maxHeight: 300, objectFit: 'contain' }} />
            : <Button disabled={busy || retry !== null || viewReason.trim().length < 10} onClick={() => viewMaterial(item.assetId)}>申请一次性查看</Button>)}
          {!canOperate && <span className="hint">需审核权限</span>}
        </li>)}
      </ul>
    </Section>

    <Section title="审核任务">
      <p>任务状态：{detail.task.status === 'AVAILABLE' ? '待领取' : `已领取（任务 ${detail.task.taskId}）`} · 任务版本 {detail.task.version}</p>
      {canOperate && !taskClaimed && <Button disabled={busy || retry !== null} onClick={() => void claim()}>领取任务</Button>}
      {canOperate && taskClaimed && <Button disabled={busy || retry !== null} onClick={() => void release()}>释放任务</Button>}
      {detail.latestDecision && <p>最近决定：{decisionLabel(detail.latestDecision.decisionType)} · {detail.latestDecision.decidedAt} · {detail.latestDecision.opinion}</p>}
    </Section>

    {canOperate && taskClaimed && <Section title="人工核验（主体/证件）">
      {!references.ok || evidenceGroups === null ? <>
        <ErrorAlert>{references.issue}</ErrorAlert>
        <p className="hint">核验证据必须引用提交版本登记的材料编号与摘要；投影缺失或不合法时提交保持禁用，不以资产编号或读取字节替代。</p>
        <Button disabled aria-disabled="true">提交人工核验（材料引用不可用）</Button>
      </> : <>
        <p className="hint">身份证须正反面都查看后合并为一条主体核验；每类证件一条证据，材料编号与摘要取自权威引用，重复类型会被后端拒绝。</p>
        <DataTable columns={evidenceColumns} rows={evidenceGroups} rowKey={group => group.credentialType} />
        <label className="filter-item">核验说明（≥10字）<Input style={{ width: 'min(100%, 560px)' }} value={verifyReason} onChange={event => setVerifyReason(event.target.value)} placeholder="如：已核对证件原件与主体一致，编号与有效期无误" /></label>
        <div><Checkbox checked={verifyConfirmed} onChange={event => setVerifyConfirmed(event.target.checked)}>我已逐项核对上述证件信息与所见材料一致</Checkbox></div>
        <Button disabled={busy || retry !== null || !verificationReady} onClick={() => void submitVerification()}>提交人工核验</Button>
      </>}
    </Section>}

    {canOperate && taskClaimed && <Section title="审核决定">
      {approveBlocked && <ErrorAlert>主体核验未完成：仅“通过”需先完成人工核验；材料有问题时可正常选择拒绝或要求补正。</ErrorAlert>}
      <label className="filter-item">决定类型
        <Select style={{ width: 360 }} aria-label="决定类型" value={decisionType} onChange={value => setDecisionType(value as DecisionTypeValue)}
          options={[
            { value: 'APPROVE', label: '通过（建立商家档案）', disabled: approveBlocked },
            { value: 'REQUEST_CORRECTION', label: '要求补正（申请人修改后重新提交）' },
            { value: 'REJECT', label: '拒绝' },
          ]} />
      </label>
      <label className="filter-item">审核意见{decisionNeedsOpinion ? '（必填）' : ''}<Input.TextArea style={{ width: 'min(100%, 720px)' }} rows={3} value={opinion} onChange={event => setOpinion(event.target.value)} /></label>
      <label className="filter-item">内部备注（仅运营可见）<Input.TextArea style={{ width: 'min(100%, 720px)' }} rows={2} value={internalNote} onChange={event => setInternalNote(event.target.value)} /></label>
      <div><Checkbox checked={decisionConfirmed} onChange={event => setDecisionConfirmed(event.target.checked)}>我确认本决定基于已查看的材料与核验结果</Checkbox></div>
      <Button disabled={busy || retry !== null || !decisionReady} onClick={() => void submitDecision()}>提交审核决定</Button>
    </Section>}
  </PageContainer>;
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
