import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  Alert, Button, Checkbox, DatePicker, Input, Progress, Select, Space, Steps, Tag, Tooltip, Typography,
} from 'antd';
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  EnvironmentOutlined,
  ExpandOutlined,
  EyeOutlined,
  FileProtectOutlined,
  IdcardOutlined,
  LockOutlined,
  PictureOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  ShopOutlined,
  UnlockOutlined,
  UserOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { RequestFailure } from '../request';
import {
  decisionLabel, merchantTypeLabel, statusLabel, validateMaterialReferences, verificationLabel,
  type ApplicationSnapshot, type MaterialReference, type ReviewDetail,
} from '../api/merchantApplications';
import { BackLink, PageContainer } from '../ui/PageContainer';
import { ErrorAlert, FactList, InlineStatus, Section, StatusTag } from '../ui/bits';
import { MaterialLightboxModal, type LightboxMaterial } from './review/MaterialLightboxModal';

type Client = ReturnType<typeof import('../api/merchantApplications').createMerchantApplicationClient>;
type DecisionTypeValue = 'APPROVE' | 'REJECT' | 'REQUEST_CORRECTION';

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
type EvidenceDraft = {
  subjectName: string;
  identifier: string;
  validityKind: 'DATED' | 'LONG_TERM';
  validFrom: string | null;
  validTo: string | null;
};
const EMPTY_DRAFT: EvidenceDraft = { subjectName: '', identifier: '', validityKind: 'DATED', validFrom: null, validTo: null };

type MaterialEntry = {
  label: string;
  assetId: string;
  category: 'credential' | 'store_photo';
  shortHint: string;
};

const VERIFY_REASON_PRESETS = [
  { label: '标准核验一致', text: '已逐项核对证件原件、主体名称、证件编号与有效期，信息真实一致' },
  { label: '长期证件核验一致', text: '已逐项核对营业执照、法人身份证及行业资质原件，长期有效证件生效日无误' },
];

const DECISION_OPINION_PRESETS: Record<DecisionTypeValue, { label: string; text: string }[]> = {
  APPROVE: [
    { label: '材料齐全通过', text: '入驻材料齐全清晰，主体证件核验一致，同意审核通过。' },
  ],
  REQUEST_CORRECTION: [
    { label: '营业执照模糊', text: '营业执照照片模糊或反光，无法清晰辨认统一社会信用代码与主体信息，请重新上传清晰完整原件。' },
    { label: '身份证不清晰/过期', text: '法人身份证正反面照片不清晰或已临近/超过有效期，请补充上传清晰有效证件照片。' },
    { label: '门店照不规范', text: '门店实景照片未能清晰展示门头招牌及室内经营环境，请按要求补充清晰的门店实景照片。' },
    { label: '地址信息不一致', text: '申报经营地址与资质材料或定位信息不一致，请核对后修正并重新提交申请。' },
  ],
  REJECT: [
    { label: '主体资质不符', text: '申请人主体资质不符合平台入驻要求，本轮入驻申请不予通过。' },
    { label: '行业许可缺失/无效', text: '必需行业经营许可证缺失或已失效，暂不符合该类目商家准入条件。' },
    { label: '材料涉嫌不实', text: '提交的入驻资质材料经核验存在信息不实或明显涂改痕迹，审核不予通过。' },
  ],
};

function failureText(error: unknown, fallback: string) {
  if (!(error instanceof RequestFailure)) return fallback;
  if (error.code === 'UNAUTHENTICATED' || error.code === 'STALE_CONTEXT') return 'SESSION_LOST';
  if (error.status === 409 || error.code.includes('CONFLICT')) return '状态已变化（版本冲突），请刷新后重试';
  if (error.code === 'FORBIDDEN') return '当前账号无权执行该操作';
  // 2026-11-20 用户裁决：参数校验等确定性失败必须透出服务器给出的具体原因
  //（如"统一社会信用代码校验位不正确"），而不是笼统的"操作失败（COMMON_INVALID_ARGUMENT）"。
  if (error.serverMessage) return `${error.serverMessage}（${error.code}）`;
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

function formatDateTime(value: string | null | undefined) {
  if (!value) return '—';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value;
}

function credentialMeta(type: EvidenceGroup['credentialType']) {
  if (type === 'CREDIT_CODE') {
    return {
      subjectLabel: '证件主体（营业执照名称）',
      subjectPlaceholder: '如：星河宠物医院有限公司',
      identifierLabel: '统一社会信用代码',
      identifierPlaceholder: '18位统一社会信用代码',
      icon: <FileProtectOutlined />,
    };
  }
  if (type === 'IDENTITY_NUMBER') {
    return {
      subjectLabel: '证件主体（法人姓名）',
      subjectPlaceholder: '如：张三',
      identifierLabel: '居民身份证号',
      identifierPlaceholder: '15位或18位中国大陆身份证号',
      icon: <IdcardOutlined />,
    };
  }
  return {
    subjectLabel: '证件主体（许可单位名称）',
    subjectPlaceholder: '如：星河宠物医院有限公司',
    identifierLabel: '行业许可证号',
    identifierPlaceholder: '如：动物诊疗许可证编号',
    icon: <SafetyCertificateOutlined />,
  };
}

function dateValidityWarning(draft: EvidenceDraft | undefined): string | null {
  if (!draft || !draft.validFrom) return null;
  const today = dayjs().format('YYYY-MM-DD');
  if (draft.validFrom > today) return '生效日晚于今天，审核通过时后端将校验有效期覆盖当日';
  if (draft.validityKind === 'DATED' && draft.validTo) {
    if (draft.validTo < draft.validFrom) return '到期日早于生效日，请核对证件日期';
    if (draft.validTo < today) return '证件已过有效期，审核通过时后端将拦截过期证件';
  }
  return null;
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
  const previewUrlsRef = useRef<Record<string, string>>({});
  const [lightboxAssetId, setLightboxAssetId] = useState<string | null>(null);
  const [highlightedAssetId, setHighlightedAssetId] = useState<string | null>(null);
  const [decisionType, setDecisionType] = useState<DecisionTypeValue>('REQUEST_CORRECTION');
  const [opinion, setOpinion] = useState('');
  const [internalNote, setInternalNote] = useState('');
  const [decisionConfirmed, setDecisionConfirmed] = useState(false);
  const [verifyReason, setVerifyReason] = useState('');
  const [verifyConfirmed, setVerifyConfirmed] = useState(false);
  const [reVerifyOpen, setReVerifyOpen] = useState(false);
  const [drafts, setDrafts] = useState<Record<string, EvidenceDraft>>({});
  const prevVerificationStatus = useRef<string | undefined>(undefined);
  // Survives an unknown-outcome write so retry reuses the SAME requestId (idempotent receipt).
  const [retry, setRetry] = useState<{ kind: PendingKind; label: string; run: () => Promise<void> } | null>(null);
  const retryRef = useRef<{ kind: PendingKind; label: string; run: () => Promise<void> } | null>(null);
  const updateRetry = (value: { kind: PendingKind; label: string; run: () => Promise<void> } | null) => {
    retryRef.current = value;
    setRetry(value);
  };

  useEffect(() => () => {
    for (const url of Object.values(previewUrlsRef.current)) URL.revokeObjectURL(url);
    previewUrlsRef.current = {};
  }, []);

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

  // Synchronize default decision option with verification status so a disabled
  // APPROVE option is never pre-selected while verification is still pending,
  // and auto-select APPROVE once manual verification transitions to VERIFIED.
  useEffect(() => {
    if (!detail) return;
    const current = detail.subjectVerificationStatus;
    const prev = prevVerificationStatus.current;
    prevVerificationStatus.current = current;
    if (current === 'PENDING') {
      setDecisionType(prevType => (prevType === 'APPROVE' ? 'REQUEST_CORRECTION' : prevType));
    } else if (current === 'VERIFIED' && prev !== 'VERIFIED') {
      setDecisionType('APPROVE');
      setReVerifyOpen(false);
    }
  }, [detail]);

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

  // 2026-11-20 用户裁决：材料随时可看（不限审核期、无"一次性查看"门槛）——每次点击自动
  // 申请授权并自动填写合规理由；读取仍带水印且逐次记入审计（安全红线不变）。
  const viewMaterial = (assetId: string) => {
    if (!detail) return;
    void runWrite('grant', async requestId => {
      const grant = await client.issueReadGrant(applicationId, assetId, {
        submissionRevisionId: detail.submittedRevisionId,
        purposeCode: 'MERCHANT_APPLICATION_REVIEW',
        reason: '运营查阅商家入驻申请材料（随时查看）',
        confirmed: true,
      }, requestId);
      const content = await client.consumeReadGrant(grant.readUrl);
      const objectUrl = URL.createObjectURL(content);
      if (previewUrlsRef.current[assetId]) URL.revokeObjectURL(previewUrlsRef.current[assetId]);
      previewUrlsRef.current[assetId] = objectUrl;
      setPreviews(current => ({ ...current, [assetId]: objectUrl }));
    }, '材料已展示（读取带水印并已记入审计）', false);
  };

  const focusMaterial = (assetId: string) => {
    const element = document.getElementById(`material-${assetId}`);
    if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'center' });
      setHighlightedAssetId(assetId);
      window.setTimeout(() => {
        setHighlightedAssetId(current => (current === assetId ? null : current));
      }, 1800);
    }
  };

  const claim = () => detail && void runWrite('claim', id => client.claim(applicationId, detail.task.version, id), '任务已领取');
  const release = () => detail && void runWrite('release', id => client.release(applicationId, detail.task.version, id), '任务已释放，回到待领取');

  const materials: MaterialEntry[] = useMemo(() => {
    if (!detail) return [];
    const snapshot = detail.submittedRevision.snapshot;
    return [
      ...(snapshot.businessLicenseAssetId ? [{ label: '营业执照', assetId: snapshot.businessLicenseAssetId, category: 'credential' as const, shortHint: '核验统一社会信用代码与主体' }] : []),
      ...(snapshot.idCardFrontAssetId ? [{ label: '身份证（人像面）', assetId: snapshot.idCardFrontAssetId, category: 'credential' as const, shortHint: '需与国徽面共同查看' }] : []),
      ...(snapshot.idCardBackAssetId ? [{ label: '身份证（国徽面）', assetId: snapshot.idCardBackAssetId, category: 'credential' as const, shortHint: '核验身份证有效期' }] : []),
      ...(snapshot.industryLicenseAssetId ? [{ label: '行业许可证', assetId: snapshot.industryLicenseAssetId, category: 'credential' as const, shortHint: '核验行业准入资质' }] : []),
      ...snapshot.storePhotoAssetIds.map((id, index) => ({
        label: `门店照片 ${index + 1}`,
        assetId: id,
        category: 'store_photo' as const,
        shortHint: '核验门头与经营环境',
      })),
    ];
  }, [detail]);

  const materialLabelByAssetId = useMemo(() => {
    const map: Record<string, string> = {};
    for (const item of materials) map[item.assetId] = item.label;
    return map;
  }, [materials]);

  const lightboxItems: LightboxMaterial[] = useMemo(
    () => materials
      .filter(item => previews[item.assetId] !== undefined)
      .map(item => ({ assetId: item.assetId, label: item.label, category: item.category, url: previews[item.assetId] })),
    [materials, previews],
  );

  if (!detail && !error) return <main className="page"><InlineStatus>加载中</InlineStatus></main>;
  if (!detail) return <PageContainer title="申请详情" back={<BackLink to="/merchant-applications">返回申请列表</BackLink>}><ErrorAlert>{error}</ErrorAlert></PageContainer>;

  const snapshot = detail.submittedRevision.snapshot;
  const isReviewing = detail.status === 'REVIEWING' && detail.task.status !== 'CLOSED';
  const taskClaimed = detail.task.status === 'CLAIMED';
  const taskAvailable = detail.task.status === 'AVAILABLE';
  const approveBlocked = detail.subjectVerificationStatus !== 'VERIFIED';
  const decisionNeedsOpinion = decisionType !== 'APPROVE';
  const opinionTrimmed = opinion.trim();
  const opinionValid = !decisionNeedsOpinion || (opinionTrimmed.length >= 10 && opinionTrimmed.length <= 500);
  // Only APPROVE requires completed subject verification (26号裁决); REJECT/REQUEST_CORRECTION stay available.
  const decisionReady = decisionConfirmed && opinionValid && (decisionType !== 'APPROVE' || !approveBlocked);

  // Evidence rows come exclusively from the authoritative material references;
  // identity requires BOTH sides viewed, evidence quotes materialId+sha verbatim.
  const references = validateMaterialReferences(detail.submittedRevision.materialReferences);
  const referenceByType = (type: string) => detail.submittedRevision.materialReferences?.find(item => item.materialType === type);
  const evidenceGroups: EvidenceGroup[] | null = references.ok ? buildEvidenceGroups(snapshot, referenceByType) : null;
  const groupViewed = (group: EvidenceGroup) => group.viewAssetIds.length > 0 && group.viewAssetIds.every(assetId => previews[assetId] !== undefined);
  // Backend date rules mirrored at the gate: validFrom required for every
  // credential; DATED requires validTo; LONG_TERM requires validTo == null.
  const draftComplete = (draft: EvidenceDraft | undefined) =>
    (draft?.subjectName ?? '').trim() !== '' && (draft?.identifier ?? '').trim() !== ''
    && draft?.validFrom != null && (draft.validityKind === 'LONG_TERM' ? draft.validTo == null : draft.validTo != null);
  const verificationReady = evidenceGroups !== null && verifyConfirmed && verifyReason.trim().length >= 10
    && evidenceGroups.every(group => groupViewed(group) && draftComplete(drafts[group.credentialType]));

  const submitVerification = () => evidenceGroups && void runWrite('verify', id => client.recordManualVerification(applicationId, {
    submissionRevisionId: detail.submittedRevisionId,
    expectedVersion: detail.version,
    expectedTaskVersion: detail.task.version,
    evidenceItems: evidenceGroups.map(group => ({
      materialId: group.reference.materialId,
      materialSha256: group.reference.materialSha256,
      credentialType: group.credentialType,
      ...(drafts[group.credentialType] ?? EMPTY_DRAFT),
    })),
    reason: verifyReason.trim(),
    confirmed: verifyConfirmed,
  }, id), '人工核验已提交');

  const submitDecision = () => void runWrite('decide', id => client.decide(applicationId, {
    decisionType,
    submissionRevisionId: detail.submittedRevisionId,
    expectedVersion: detail.version,
    expectedTaskVersion: detail.task.version,
    opinion: opinionTrimmed,
    internalNote: internalNote.trim(),
    confirmed: decisionConfirmed,
  }, id), '审核决定已提交');

  const credentialMaterials = materials.filter(item => item.category === 'credential');
  const viewedCredentialCount = credentialMaterials.filter(item => previews[item.assetId] !== undefined).length;
  const viewedTotalCount = materials.filter(item => previews[item.assetId] !== undefined).length;
  const completedEvidenceCount = evidenceGroups
    ? evidenceGroups.filter(group => groupViewed(group) && draftComplete(drafts[group.credentialType])).length
    : 0;
  const unviewedEvidenceCount = evidenceGroups
    ? evidenceGroups.filter(group => !groupViewed(group)).length
    : 0;

  const taskStatusText = detail.task.status === 'AVAILABLE'
    ? '待领取'
    : detail.task.status === 'CLOSED'
      ? `已关闭（任务 ${detail.task.taskId}）`
      : `已领取（任务 ${detail.task.taskId}）`;

  const currentStep = !isReviewing
    ? 3
    : !taskClaimed
      ? 0
      : detail.subjectVerificationStatus === 'VERIFIED'
        ? 3
        : viewedCredentialCount < credentialMaterials.length
          ? 1
          : 2;

  const verificationMissingItems: string[] = [];
  if (evidenceGroups) {
    if (unviewedEvidenceCount > 0) verificationMissingItems.push(`查阅剩余 ${unviewedEvidenceCount} 组证件原件`);
    const incompleteCount = evidenceGroups.filter(group => groupViewed(group) && !draftComplete(drafts[group.credentialType])).length;
    if (incompleteCount > 0) verificationMissingItems.push(`完善 ${incompleteCount} 组证件主体/证号/有效期`);
    if (verifyReason.trim().length < 10) verificationMissingItems.push(`核验说明需至少 10 字（当前 ${verifyReason.trim().length} 字）`);
    if (!verifyConfirmed) verificationMissingItems.push('勾选逐项核对确认框');
  }

  const decisionMissingItems: string[] = [];
  if (decisionType === 'APPROVE' && approveBlocked) decisionMissingItems.push('先完成主体人工核验');
  if (decisionNeedsOpinion && opinionTrimmed.length < 10) decisionMissingItems.push(`填写至少 10 字审核意见（当前 ${opinionTrimmed.length} 字）`);
  if (!decisionConfirmed) decisionMissingItems.push('勾选决定确认框');

  const headerActions = (
    <Space size={8} wrap>
      <Button icon={<ReloadOutlined aria-hidden="true" />} disabled={busy} onClick={() => void load()}>刷新</Button>
      {isReviewing && canOperate && taskAvailable && (
        <Button type="primary" icon={<UnlockOutlined aria-hidden="true" />} disabled={busy || retry !== null} onClick={() => void claim()}>领取任务</Button>
      )}
      {isReviewing && canOperate && taskClaimed && (
        <Button icon={<LockOutlined aria-hidden="true" />} disabled={busy || retry !== null} onClick={() => void release()}>释放任务</Button>
      )}
    </Space>
  );

  return (
    <div className="review-detail-page">
    <PageContainer
      title={`申请 ${detail.applicationNo}`}
      back={<BackLink to="/merchant-applications">返回申请列表</BackLink>}
      extra={headerActions}
    >
      {/* 顶部案件概览与企业级审核流程进度条 */}
      <div className="review-hero-card">
        <div className="review-hero-top">
          <div className="review-hero-identity">
            <div className="review-hero-merchant">
              <ShopOutlined className="review-hero-icon" />
              <span className="review-hero-name">{snapshot.merchantName}</span>
              <Tag color="geekblue">{merchantTypeLabel(detail.merchantTypeCode)}</Tag>
              <Tag icon={<EnvironmentOutlined />}>{detail.cityCode}</Tag>
            </div>
            <Space size={8} wrap className="review-hero-meta">
              <span>状态：<StatusTag label={statusLabel(detail.status)} status={detail.status} /></span>
              <span>主体核验：<StatusTag label={verificationLabel(detail.subjectVerificationStatus)} status={detail.subjectVerificationStatus} /></span>
              <Tooltip title={`提交时间（原始 UTC）：${detail.submittedAt}`}>
                <span className="review-meta-item"><ClockCircleOutlined /> 提交于 {formatDateTime(detail.submittedAt)}</span>
              </Tooltip>
              <span className="review-meta-item">版本 v{detail.version}</span>
            </Space>
          </div>
        </div>

        <Steps
          size="small"
          className="review-workflow-steps"
          current={currentStep}
          status={!isReviewing && detail.status === 'REJECTED' ? 'error' : undefined}
          items={[
            {
              title: '领取审核任务',
              description: taskAvailable ? '待运营领取' : taskClaimed ? '已锁定处理中' : '任务已闭环',
              status: !isReviewing || taskClaimed ? 'finish' : 'process',
            },
            {
              title: '查阅资质材料',
              // 2026-11-20 用户裁决：已审结的单子不再显示"0/3 份已看"这类进行中口吻的计数——
              // 未看全时显示"已审结归档"，看全了保留完整记录。
              description: !isReviewing && viewedCredentialCount < credentialMaterials.length
                ? '已审结归档'
                : `${viewedCredentialCount}/${credentialMaterials.length} 份核心证件已看`,
              status: !isReviewing || detail.subjectVerificationStatus === 'VERIFIED' || (credentialMaterials.length > 0 && viewedCredentialCount === credentialMaterials.length)
                ? 'finish'
                : taskClaimed ? 'process' : 'wait',
            },
            {
              title: '人工核验',
              description: detail.subjectVerificationStatus === 'VERIFIED' ? '已核验入库' : '核对主体与证号',
              status: detail.subjectVerificationStatus === 'VERIFIED'
                ? 'finish'
                : !isReviewing ? 'wait' : taskClaimed && viewedCredentialCount === credentialMaterials.length ? 'process' : 'wait',
            },
            {
              title: '审核裁决',
              description: detail.latestDecision ? `${decisionLabel(detail.latestDecision.decisionType)}` : '通过 / 补正 / 拒绝',
              status: !isReviewing ? (detail.status === 'APPROVED' ? 'finish' : 'error') : detail.subjectVerificationStatus === 'VERIFIED' ? 'process' : 'wait',
            },
          ]}
        />
      </div>

      {/* 全局回执、错误与未知结果幂等恢复区 */}
      {notice && <InlineStatus>{notice}</InlineStatus>}
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {retry && (
        <Alert
          type="warning"
          showIcon
          className="review-retry-alert"
          title={`存在结果未知的操作：${retry.label}`}
          description={
            <div className="review-retry-body">
              <p style={{ margin: '0 0 10px' }}>
                {retry.kind === 'grant'
                  ? '材料授权结果无法由申请快照判定；'
                  : '申请快照与本次命令无 requestId 关联，不能证明其已终结；'}
                仅可通过重试原操作以同一请求标识取得幂等回执解除未决；刷新仅更新当前展示。
              </p>
              <Space size={8} wrap>
                <Button type="primary" disabled={busy} onClick={() => void retry.run()}>重试原操作（复用原请求标识）</Button>
                <Button disabled={busy} onClick={() => void load()}>刷新申请状态</Button>
              </Space>
            </div>
          }
        />
      )}

      {/* 终态审结横幅 / 权限提示 / 待领取引导 */}
      {!isReviewing && (
        <Alert
          role="status"
          type={detail.status === 'APPROVED' ? 'success' : 'warning'}
          showIcon
          className="review-state-banner"
          title={`本申请已审结：${statusLabel(detail.status)}${detail.latestDecision ? `（${decisionLabel(detail.latestDecision.decisionType)}）` : ''}`}
          description={detail.latestDecision
            ? `裁决时间：${formatDateTime(detail.latestDecision.decidedAt)} · 审核意见：${detail.latestDecision.opinion || '无附加意见'}`
            : '当前审核任务已关闭，仅支持查看申报快照与历史记录。'}
        />
      )}
      {isReviewing && !canOperate && (
        <Alert
          role="status"
          type="info"
          showIcon
          className="review-state-banner"
          title="只读模式"
          description="当前账号未同时具备审核决定与敏感信息查看权限，仅可查阅脱敏申报档案，无法查阅水印材料或执行审核操作。"
        />
      )}
      {isReviewing && canOperate && taskAvailable && (
        <Alert
          role="status"
          type="info"
          showIcon
          className="review-state-banner"
          title="审核任务尚未领取"
          description="点击右上角「领取任务」锁定本申请单后，即可展开人工核验与审核裁决工作区；左侧申请材料也可随时查阅。"
        />
      )}

      {/* 企业级双栏审核工作台：左侧卷宗+材料查验，右侧任务+核验+裁决 */}
      <div className="review-workbench-grid">
        {/* 左栏：申报信息与材料查验 */}
        <div className="review-workbench-main">
          <Section
            title={`提交版本（第 ${detail.submittedRevision.revisionNo} 版 · ${detail.submittedRevision.createdAt}）`}
            extra={
              <Typography.Text copyable={{ text: detail.reservedMerchantId }} type="secondary" style={{ fontSize: 13 }}>
                预留商家ID：{detail.reservedMerchantId}
              </Typography.Text>
            }
          >
            <FactList
              column={{ xs: 1, sm: 2 }}
              items={[
                {
                  label: '商家名称',
                  value: <Typography.Text strong copyable={{ text: snapshot.merchantName }}>{snapshot.merchantName}</Typography.Text>,
                },
                { label: '类型 / 城市', value: `${merchantTypeLabel(detail.merchantTypeCode)} · ${detail.cityCode}` },
                {
                  label: '地址',
                  value: `${snapshot.address}（${snapshot.longitude ?? '—'}, ${snapshot.latitude ?? '—'}）`,
                },
                {
                  label: '联系人（脱敏）',
                  value: `${snapshot.contactNameMasked} / ${snapshot.contactPhoneMasked} / ${snapshot.emailMasked}`,
                },
                { label: '简介', value: snapshot.introduction || '—' },
                {
                  label: '申请编号',
                  value: <Typography.Text copyable={{ text: detail.applicationNo }}>{detail.applicationNo}</Typography.Text>,
                },
              ]}
            />
          </Section>

          <Section
            title="申请材料"
            extra={
              <Space size={8}>
                <Tag color={
                  !isReviewing ? 'default'
                    : viewedCredentialCount === credentialMaterials.length && credentialMaterials.length > 0 ? 'green' : 'blue'
                }>
                  核心证件 {viewedCredentialCount}/{credentialMaterials.length}
                </Tag>
                <Tag>全部材料 {viewedTotalCount}/{materials.length}</Tag>
              </Space>
            }
          >
            <div className="review-audit-reason-bar">
              <p className="hint" style={{ margin: '0 0 8px' }}>
                点击材料卡片上的「查看」即可核对原件，审核期与审结后均可随时查看；每次读取带水印并记入审计。点击已加载图片可放大/旋转核对细节。
              </p>
            </div>

            <ul className="materials">
              {materials.map(item => {
                const previewUrl = previews[item.assetId];
                const isHighlighted = highlightedAssetId === item.assetId;
                return (
                  <li
                    key={item.assetId}
                    id={`material-${item.assetId}`}
                    className={`review-material-card${previewUrl ? ' is-viewed' : ''}${isHighlighted ? ' is-highlighted' : ''}`}
                  >
                    <div className="review-material-head">
                      <div className="review-material-title">
                        {item.category === 'credential'
                          ? <FileProtectOutlined aria-hidden="true" />
                          : <PictureOutlined aria-hidden="true" />}
                        <span>{item.label}</span>
                      </div>
                      <Space size={4}>
                        <Tag color={item.category === 'credential' ? 'blue' : 'default'} style={{ marginInlineEnd: 0 }}>
                          {item.category === 'credential' ? '必核证件' : '门店照片'}
                        </Tag>
                        {isReviewing && (
                          <Tag color={previewUrl ? 'green' : 'default'} style={{ marginInlineEnd: 0 }}>
                            {previewUrl ? '已查看' : '未查看'}
                          </Tag>
                        )}
                      </Space>
                    </div>

                    <div className="review-material-body">
                      {canOperate ? (
                        previewUrl ? (
                          <div className="review-material-preview-wrap">
                            <img
                              src={previewUrl}
                              alt={`${item.label} 水印读取结果`}
                              className="review-material-img"
                              onClick={() => setLightboxAssetId(item.assetId)}
                            />
                            <div className="review-material-preview-actions">
                              <Button
                                size="small"
                                icon={<ExpandOutlined aria-hidden="true" />}
                                onClick={() => setLightboxAssetId(item.assetId)}
                              >
                                放大/旋转核对
                              </Button>
                            </div>
                          </div>
                        ) : (
                          <div className="review-material-placeholder">
                            <span className="review-material-subhint">{item.shortHint}</span>
                            <Button
                              type="primary"
                              ghost
                              icon={<EyeOutlined aria-hidden="true" />}
                              disabled={busy || retry !== null}
                              onClick={() => viewMaterial(item.assetId)}
                            >
                              查看
                            </Button>
                          </div>
                        )
                      ) : (
                        <div className="review-material-placeholder">
                          <span className="hint" style={{ margin: 0 }}>需审核权限</span>
                        </div>
                      )}
                    </div>

                    <div className="review-material-foot">
                      <span>资产编号</span>
                      <code>{item.assetId}</code>
                    </div>
                  </li>
                );
              })}
            </ul>
          </Section>
        </div>

        {/* 右栏：审核任务、人工核验与审核裁决 */}
        <div className="review-workbench-side">
          <Section
            title="审核任务"
            extra={
              <Tag color={taskAvailable ? 'orange' : taskClaimed ? 'blue' : 'default'}>
                {taskAvailable ? '待领取' : taskClaimed ? '处理中' : '已关闭'}
              </Tag>
            }
          >
            <div className="review-task-summary">
              <p className="review-task-line">
                任务状态：<strong>{taskStatusText}</strong> · 任务版本 {detail.task.version}
              </p>
              {detail.task.claimedByOperatorId && (
                <p className="review-task-sub">
                  <UserOutlined /> 当前领取人：运营 {detail.task.claimedByOperatorId}
                </p>
              )}
              {detail.latestDecision && (
                <div className="review-latest-decision">
                  <div className="review-latest-decision-head">
                    <span>最近决定</span>
                    <StatusTag label={decisionLabel(detail.latestDecision.decisionType)} status={detail.latestDecision.decisionType} />
                    <span className="review-meta-item">{formatDateTime(detail.latestDecision.decidedAt)}</span>
                  </div>
                  <p style={{ margin: '6px 0 0', color: 'rgba(0,0,0,0.78)' }}>
                    最近决定：{decisionLabel(detail.latestDecision.decisionType)} · {detail.latestDecision.decidedAt} · {detail.latestDecision.opinion || '无附加意见'}
                  </p>
                </div>
              )}
            </div>
          </Section>

          {canOperate && taskClaimed && isReviewing && (
            <Section
              title="人工核验（主体/证件）"
              extra={<StatusTag label={verificationLabel(detail.subjectVerificationStatus)} status={detail.subjectVerificationStatus} />}
            >
              {!references.ok || evidenceGroups === null ? (
                <>
                  <ErrorAlert>{references.issue}</ErrorAlert>
                  <p className="hint">核验证据必须引用提交版本登记的材料编号与摘要；投影缺失或不合法时提交保持禁用，不以资产编号或读取字节替代。</p>
                  <Button disabled aria-disabled="true" block>提交人工核验（材料引用不可用）</Button>
                </>
              ) : detail.subjectVerificationStatus === 'VERIFIED' && !reVerifyOpen ? (
                <div className="review-verified-box">
                  <Alert
                    role="status"
                    type="success"
                    showIcon
                    icon={<CheckCircleOutlined />}
                    title="主体与证件核验已通过"
                    description="本版本提交的营业执照与法人身份证已核验入库，现可在下方「审核决定」中直接选择通过建立商家档案。"
                  />
                  <Button type="link" size="small" style={{ paddingInline: 0, marginTop: 8 }} onClick={() => setReVerifyOpen(true)}>
                    需要修正证件记录？展开重新核验
                  </Button>
                </div>
              ) : (
                <>
                  <div className="review-verify-progress">
                    <div className="review-verify-progress-head">
                      <span>证件核验录入进度</span>
                      <strong>{completedEvidenceCount} / {evidenceGroups.length} 项已完成</strong>
                    </div>
                    <Progress
                      percent={evidenceGroups.length > 0 ? Math.round((completedEvidenceCount / evidenceGroups.length) * 100) : 0}
                      size="small"
                      showInfo={false}
                      strokeColor="#2459a8"
                    />
                  </div>
                  <p className="hint" style={{ marginTop: 8 }}>
                    身份证须正反面都查看后合并为一条主体核验；每类证件一条证据，材料编号与摘要取自权威引用。
                  </p>

                  <div className="review-evidence-list">
                    {evidenceGroups.map(group => {
                      const draft = drafts[group.credentialType];
                      const viewed = groupViewed(group);
                      const complete = viewed && draftComplete(draft);
                      const meta = credentialMeta(group.credentialType);
                      const missingAssets = group.viewAssetIds.filter(assetId => previews[assetId] === undefined);
                      const dateWarning = viewed ? dateValidityWarning(draft) : null;

                      return (
                        <div key={group.credentialType} className={`review-evidence-card${complete ? ' is-complete' : viewed ? ' is-unlocked' : ' is-locked'}`}>
                          <div className="review-evidence-head">
                            <Space size={6}>
                              {meta.icon}
                              <strong>{group.label}</strong>
                            </Space>
                            <Tag color={complete ? 'green' : viewed ? 'blue' : 'default'}>
                              {complete ? '已填妥' : viewed ? '已查看 · 待填写' : '未查看'}
                            </Tag>
                          </div>

                          {!viewed ? (
                            <div className="review-evidence-lock-tip">
                              <span>
                                需先查阅左侧材料解锁填写：{missingAssets.map(id => materialLabelByAssetId[id] ?? id).join('、')}
                              </span>
                              {missingAssets[0] && (
                                <Button size="small" type="link" onClick={() => focusMaterial(missingAssets[0])}>
                                  定位去查看
                                </Button>
                              )}
                            </div>
                          ) : (
                            <div className="review-evidence-thumbs">
                              {group.viewAssetIds.map(assetId => (
                                <button
                                  key={assetId}
                                  type="button"
                                  className="review-evidence-thumb-btn"
                                  onClick={() => setLightboxAssetId(assetId)}
                                  title="点击放大/旋转核对原件"
                                >
                                  <img src={previews[assetId]} alt={`核验参考缩略图：${materialLabelByAssetId[assetId] ?? group.label}`} />
                                  <span>{materialLabelByAssetId[assetId] ?? '查看原件'}</span>
                                </button>
                              ))}
                            </div>
                          )}

                          <div className="review-evidence-fields">
                            <div className="review-field">
                              <div className="review-field-header">
                                <span className="review-field-name">{meta.subjectLabel}</span>
                                {group.credentialType === 'CREDIT_CODE' && viewed && snapshot.merchantName && (
                                  <button
                                    type="button"
                                    className="review-quick-link"
                                    onClick={() => patchDraft(group.credentialType, { subjectName: snapshot.merchantName })}
                                  >
                                    填入商家名称
                                  </button>
                                )}
                              </div>
                              <Input
                                aria-label={`${group.label} 证件主体`}
                                placeholder={meta.subjectPlaceholder}
                                value={draft?.subjectName ?? ''}
                                disabled={!viewed}
                                onChange={event => patchDraft(group.credentialType, { subjectName: event.target.value })}
                              />
                            </div>

                            <div className="review-field">
                              <div className="review-field-header">
                                <span className="review-field-name">{meta.identifierLabel}</span>
                              </div>
                              <Input
                                aria-label={`${group.label} 证号`}
                                placeholder={meta.identifierPlaceholder}
                                value={draft?.identifier ?? ''}
                                disabled={!viewed}
                                onChange={event => patchDraft(group.credentialType, { identifier: event.target.value })}
                              />
                            </div>

                            <div className="review-field-row-3">
                              <div className="review-field">
                                <span className="review-field-name">有效期类型</span>
                                <Select
                                  style={{ width: '100%' }}
                                  aria-label={`${group.label} 有效期类型`}
                                  value={draft?.validityKind ?? 'DATED'}
                                  disabled={!viewed}
                                  onChange={value => patchDraft(group.credentialType, value === 'LONG_TERM'
                                    // Backend rules: validFrom is required for every credential and
                                    // LONG_TERM requires validTo == null — switching clears it.
                                    ? { validityKind: 'LONG_TERM', validTo: null }
                                    : { validityKind: 'DATED' })}
                                  options={[{ value: 'DATED', label: '有期限' }, { value: 'LONG_TERM', label: '长期' }]}
                                />
                              </div>

                              <div className="review-field">
                                <span className="review-field-name">生效日</span>
                                <DatePicker
                                  style={{ width: '100%' }}
                                  format="YYYY-MM-DD"
                                  placeholder="YYYY-MM-DD"
                                  aria-label={`${group.label} 生效日`}
                                  value={draft?.validFrom ? dayjs(draft.validFrom) : null}
                                  disabled={!viewed}
                                  onChange={value => patchDraft(group.credentialType, { validFrom: value ? value.format('YYYY-MM-DD') : null })}
                                />
                              </div>

                              {draft?.validityKind !== 'LONG_TERM' && (
                                <div className="review-field">
                                  <span className="review-field-name">到期日</span>
                                  <DatePicker
                                    style={{ width: '100%' }}
                                    format="YYYY-MM-DD"
                                    placeholder="YYYY-MM-DD"
                                    aria-label={`${group.label} 到期日`}
                                    value={draft?.validTo ? dayjs(draft.validTo) : null}
                                    disabled={!viewed}
                                    onChange={value => patchDraft(group.credentialType, { validTo: value ? value.format('YYYY-MM-DD') : null })}
                                  />
                                </div>
                              )}
                            </div>

                            {dateWarning && (
                              <div className="review-inline-warning">{dateWarning}</div>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>

                  <div className="review-form-block">
                    <label className="filter-item" style={{ marginBottom: 6 }}>
                      <span className="review-field-header">
                        <span>核验说明（≥10字）</span>
                        <span className={`review-char-count${verifyReason.trim().length > 0 && verifyReason.trim().length < 10 ? ' is-invalid' : ''}`}>
                          {verifyReason.trim().length} / 500
                        </span>
                      </span>
                      <Input
                        maxLength={500}
                        value={verifyReason}
                        onChange={event => setVerifyReason(event.target.value)}
                        placeholder="如：已核对证件原件与主体一致，编号与有效期无误"
                      />
                    </label>
                    <div className="review-preset-chips">
                      <span className="review-preset-label">常用说明：</span>
                      {VERIFY_REASON_PRESETS.map(preset => (
                        <button
                          key={preset.label}
                          type="button"
                          className="review-preset-chip"
                          onClick={() => setVerifyReason(preset.text)}
                        >
                          {preset.label}
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className="review-confirm-row">
                    <Checkbox checked={verifyConfirmed} onChange={event => setVerifyConfirmed(event.target.checked)}>
                      我已逐项核对上述证件信息与所见材料一致
                    </Checkbox>
                  </div>

                  <Button
                    type="primary"
                    block
                    disabled={busy || retry !== null || !verificationReady}
                    onClick={() => void submitVerification()}
                  >
                    提交人工核验
                  </Button>

                  {!verificationReady && verificationMissingItems.length > 0 && (
                    <p className="review-readiness-hint">提交前还需：{verificationMissingItems.join('；')}</p>
                  )}
                  {detail.subjectVerificationStatus === 'VERIFIED' && (
                    <Button type="link" size="small" style={{ paddingInline: 0, marginTop: 4 }} onClick={() => setReVerifyOpen(false)}>
                      收起重新核验表单
                    </Button>
                  )}
                </>
              )}
            </Section>
          )}

          {canOperate && taskClaimed && isReviewing && (
            <Section title="审核决定">
              {approveBlocked && (
                <Alert
                  role="alert"
                  type="warning"
                  showIcon
                  style={{ marginBottom: 12 }}
                  title="主体核验未完成：仅“通过”需先完成人工核验；材料有问题时可正常选择拒绝或要求补正。"
                />
              )}

              <label className="filter-item">
                决定类型
                <Select
                  style={{ width: '100%' }}
                  aria-label="决定类型"
                  value={decisionType}
                  onChange={value => setDecisionType(value as DecisionTypeValue)}
                  options={[
                    { value: 'APPROVE', label: '通过（建立商家档案）', disabled: approveBlocked },
                    { value: 'REQUEST_CORRECTION', label: '要求补正（申请人修改后重新提交）' },
                    { value: 'REJECT', label: '拒绝' },
                  ]}
                />
              </label>

              <div className={`review-decision-effect is-${decisionType.toLowerCase()}`}>
                {decisionType === 'APPROVE' && '通过后将自动建立商家与门店档案（ACTIVE）；商家完成电子协议签署后方可开展经营。'}
                {decisionType === 'REQUEST_CORRECTION' && '状态将置为「未通过（需补充资料）」，审核意见将通过站内通知发送给申请人，修改后可重新提交。'}
                {decisionType === 'REJECT' && '本轮申请将判定为「未通过」，审核意见将通过站内通知发送给申请人并留痕。'}
              </div>

              <div className="review-form-block">
                <label className="filter-item" style={{ marginBottom: 6 }}>
                  <span className="review-field-header">
                    <span>审核意见{decisionNeedsOpinion ? '（必填，10–500字）' : '（选填）'}</span>
                    <span className={`review-char-count${decisionNeedsOpinion && opinionTrimmed.length > 0 && opinionTrimmed.length < 10 ? ' is-invalid' : ''}`}>
                      {opinionTrimmed.length} / 500
                    </span>
                  </span>
                  <Input.TextArea
                    rows={3}
                    maxLength={500}
                    value={opinion}
                    onChange={event => setOpinion(event.target.value)}
                    placeholder={decisionNeedsOpinion ? '请清晰说明驳回或需补正的具体材料与修改要求（10–500字，将发送给申请人）' : '可选填通过说明'}
                  />
                </label>
                <div className="review-preset-chips">
                  <span className="review-preset-label">快捷意见：</span>
                  {DECISION_OPINION_PRESETS[decisionType].map(preset => (
                    <button
                      key={preset.label}
                      type="button"
                      className="review-preset-chip"
                      onClick={() => setOpinion(preset.text)}
                    >
                      {preset.label}
                    </button>
                  ))}
                </div>
              </div>

              <label className="filter-item">
                <span className="review-field-header">
                  <span>内部备注（仅运营可见）</span>
                  <span className="review-char-count">{internalNote.trim().length} / 500</span>
                </span>
                <Input.TextArea
                  rows={2}
                  maxLength={500}
                  value={internalNote}
                  onChange={event => setInternalNote(event.target.value)}
                  placeholder="仅平台运营内部留痕，不向申请人展示（选填）"
                />
              </label>

              <div className="review-confirm-row">
                <Checkbox checked={decisionConfirmed} onChange={event => setDecisionConfirmed(event.target.checked)}>
                  我确认本决定基于已查看的材料与核验结果
                </Checkbox>
              </div>

              <Button
                type="primary"
                danger={decisionType === 'REJECT'}
                block
                disabled={busy || retry !== null || !decisionReady}
                onClick={() => void submitDecision()}
              >
                提交审核决定
              </Button>

              {!decisionReady && decisionMissingItems.length > 0 && (
                <p className="review-readiness-hint">提交前还需：{decisionMissingItems.join('；')}</p>
              )}
            </Section>
          )}
        </div>
      </div>

      <MaterialLightboxModal
        open={lightboxAssetId !== null}
        activeAssetId={lightboxAssetId}
        items={lightboxItems}
        onSelect={setLightboxAssetId}
        onClose={() => setLightboxAssetId(null)}
      />
    </PageContainer>
    </div>
  );
}

function buildEvidenceGroups(
  snapshot: ApplicationSnapshot,
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
