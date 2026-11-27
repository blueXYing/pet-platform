import { useRef, useState } from 'react';
import { Alert, Button, Collapse, Input, Modal, Popconfirm, Select, Space, Steps, Switch, Table, Tooltip, Upload } from 'antd';
import type { TableColumnsType } from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import {
  INELIGIBLE_REASON_LABELS, MISSING_CONFIG_LABELS,
  ONBOARDING_ATTACHMENT_LABELS, ONBOARDING_ATTACHMENT_TYPES, ONBOARDING_STATUS_LABELS,
  ONBOARDING_STEP_LABELS, ONBOARDING_STEP_STATUS_LABELS, REQUIRED_ATTACHMENT_TYPES,
  SETTLER_ATTACHMENT_TYPES, describeOnboardingFailure,
  type OnboardingAllowedAction, type OnboardingAttachmentType, type OnboardingAuditView,
  type OnboardingDefaultsView, type OnboardingDetailView, type OnboardingRevealView,
  type OnboardingSaveRequest, type OnboardingAdminClient,
} from '../api/onboardingAdmin';
import { formatUtcDateTime, isValidDecimalId } from '../api/settlementAdmin';
import { RequestFailure } from '../request';
import { ErrorAlert, FactList, InlineStatus, Section, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

// 商家收单开通（Contract 81 §2～§4 / CCR-PAY-ONBOARD-001 / 44 号裁决补充）：
// - 运营代填进件资料并上传附件，按「电子合同 → 进件 → 开通分账 → 绑定」推进状态机；
// - 操作按钮严格按服务端 allowedActions 渲染，前端不得自行推导可执行动作；
// - 详情/审计默认脱敏展示；查看明文须填原因（写不可变审计），明文仅存于组件内存，关闭/切换/刷新即清除；
// - 平台级参数（posType/经营内容/结算周期/费率/最低分账比例/平台接收方）只读展示，发起开通时由服务端固化。

type OnboardingFormState = {
  merRegName: string;
  merBizName: string;
  merRegDistCode: string;
  merRegAddr: string;
  mccCode: string;
  merBlisName: string;
  merBlisNo: string;
  merBlisStDt: string;
  merBlisExpDt: string;
  larName: string;
  larIdcard: string;
  larIdcardStDt: string;
  larIdcardExpDt: string;
  larMobile: string;
  contactName: string;
  contactMobile: string;
  acctTypeCode: '57' | '58';
  acctName: string;
  acctNo: string;
  settlePersonDiffers: boolean;
  acctIdcard: string;
  acctIdExpDt: string;
  openningBankCode: string;
  openningBankName: string;
  clearingBankCode: string;
  attachments: Record<OnboardingAttachmentType, string>;
  cooperationAssetId: string;
  reason: string;
};

const COOPERATION_KEY = 'cooperation';

const emptyAttachments = (): Record<OnboardingAttachmentType, string> => {
  const record = {} as Record<OnboardingAttachmentType, string>;
  for (const type of ONBOARDING_ATTACHMENT_TYPES) record[type] = '';
  return record;
};

// 详情只回脱敏值：证号、手机号、卡号、户名、法人/联系人姓名、执照号一律不回填表单。编辑已有资料时这些字段
// 留空表示保持原值（契约 81 §4.4，服务端回填库中明文），填写则覆盖；新开通时必须完整填写明文。
function formFromDetail(detail: OnboardingDetailView): OnboardingFormState {
  return {
    merRegName: detail.merchant.merRegName ?? '',
    merBizName: detail.merchant.merBizName ?? '',
    merRegDistCode: detail.merchant.merRegDistCode ?? '',
    merRegAddr: detail.merchant.merRegAddr ?? '',
    mccCode: detail.platform.mccCode ?? '',
    merBlisName: detail.merchant.merBlisName ?? '',
    merBlisNo: '',
    merBlisStDt: detail.merchant.merBlisStDt ?? '',
    merBlisExpDt: detail.merchant.merBlisExpDt ?? '',
    larName: '',
    larIdcard: '',
    larIdcardStDt: detail.merchant.larIdcardStDt ?? '',
    larIdcardExpDt: detail.merchant.larIdcardExpDt ?? '',
    larMobile: '',
    contactName: '',
    contactMobile: '',
    acctTypeCode: detail.settlement.acctTypeCode === '57' ? '57' : '58',
    acctName: '',
    acctNo: '',
    settlePersonDiffers: detail.settlement.acctIdType !== null && detail.settlement.acctIdType !== undefined,
    acctIdcard: '',
    acctIdExpDt: detail.settlement.acctIdExpDt ?? '',
    openningBankCode: detail.settlement.openningBankCode ?? '',
    openningBankName: detail.settlement.openningBankName ?? '',
    clearingBankCode: detail.settlement.clearingBankCode ?? '',
    attachments: (() => {
      const record = emptyAttachments();
      for (const type of ONBOARDING_ATTACHMENT_TYPES) record[type] = detail.attachments[type] ?? '';
      return record;
    })(),
    cooperationAssetId: detail.cooperationAssetId ?? '',
    reason: '运营更新开通资料',
  };
}

function emptyFormFromDefaults(defaults: OnboardingDefaultsView): OnboardingFormState {
  const seededName = defaults.merchantName ?? '';
  return {
    merRegName: seededName,
    merBizName: '',
    merRegDistCode: defaults.districtCode ?? '',
    merRegAddr: '',
    mccCode: defaults.platform.mccCode ?? '',
    merBlisName: seededName,
    merBlisNo: '',
    merBlisStDt: '',
    merBlisExpDt: '',
    larName: '',
    larIdcard: '',
    larIdcardStDt: '',
    larIdcardExpDt: '',
    larMobile: '',
    contactName: '',
    contactMobile: '',
    acctTypeCode: '58',
    acctName: '',
    acctNo: '',
    settlePersonDiffers: false,
    acctIdcard: '',
    acctIdExpDt: '',
    openningBankCode: '',
    openningBankName: '',
    clearingBankCode: '',
    attachments: emptyAttachments(),
    cooperationAssetId: '',
    reason: '运营代填开通资料',
  };
}

// ---- §4.4 校验规则的前端镜像（任一不满足提示运营；服务端仍为准） ----

function isValidYmd(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(new Date(`${value}T00:00:00`).getTime());
}

function todayYmd(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

function isValidMainlandId(value: string): boolean {
  if (/^\d{15}$/.test(value)) return true;
  if (!/^\d{17}[\dXx]$/.test(value)) return false;
  const weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2];
  const checkCodes = '10X98765432';
  let sum = 0;
  for (let i = 0; i < 17; i++) sum += Number(value[i]) * weights[i];
  return checkCodes[sum % 11] === value[17].toUpperCase();
}

function validateDateRange(label: string, start: string, end: string): string {
  if (!isValidYmd(start)) return `${label}开始日期须为合法的 yyyy-MM-dd 日期`;
  if (!isValidYmd(end)) return `${label}截止日期须为合法的 yyyy-MM-dd 日期`;
  if (end < start) return `${label}截止日期不得早于开始日期`;
  if (end < todayYmd()) return `${label}截止日期不得早于当日`;
  return '';
}

// 已有资料里哪些敏感字段可以"留空保持原值"（由详情决定：库里确实存有该值才行）。
type KeepableSecrets = {
  licenseNo: boolean;
  larName: boolean;
  larIdcard: boolean;
  larMobile: boolean;
  contactName: boolean;
  contactMobile: boolean;
  acctName: boolean;
  acctNo: boolean;
  settler: boolean;
};

const keepNothing: KeepableSecrets = {
  licenseNo: false,
  larName: false,
  larIdcard: false,
  larMobile: false,
  contactName: false,
  contactMobile: false,
  acctName: false,
  acctNo: false,
  settler: false,
};

// 契约 §4.4 校验规则的前端镜像（任一不满足提示运营；服务端仍为准）。keep 为 true 的敏感字段允许留空。
function validateOnboardingForm(form: OnboardingFormState, keep: KeepableSecrets = keepNothing): string {
  const merRegName = form.merRegName.trim();
  const merBlisName = form.merBlisName.trim();
  // 拉卡拉 1020：注册名称不少于 4 个汉字，地址与名称上限 80 字。
  if (merRegName.length < 4 || merRegName.length > 80) return '注册名称须为 4～80 字';
  const merBizName = form.merBizName.trim();
  if (merBizName && (merBizName.length < 4 || merBizName.length > 64)) return '经营名称非空时须为 4～64 字';
  if (!/^[0-9]{6,8}$/.test(form.merRegDistCode.trim())) return '地区码须为 6～8 位数字';
  const merRegAddr = form.merRegAddr.trim();
  if (merRegAddr.length < 6 || merRegAddr.length > 80) return '注册地址须为 6～80 字';
  const mccCode = form.mccCode.trim();
  if (mccCode && !/^[0-9]{4}$/.test(mccCode)) return 'MCC 编码留空时取平台映射，填写时须为 4 位数字';
  if (merBlisName.length < 4 || merBlisName.length > 80) return '营业执照名称须为 4～80 字';
  if (merRegName !== merBlisName) return '注册名称须与营业执照名称一致';
  const merBlisNo = form.merBlisNo.trim().toUpperCase();
  if (!(keep.licenseNo && merBlisNo === '') && !/^[0-9A-Z]{8,40}$/.test(merBlisNo)) return '营业执照编号须为 8～40 位数字与字母';
  const licenceRangeError = validateDateRange('营业执照有效期', form.merBlisStDt.trim(), form.merBlisExpDt.trim());
  if (licenceRangeError) return licenceRangeError;
  if (!(keep.larName && form.larName.trim() === '') && !/^[\u4e00-\u9fa5]{2,20}$/.test(form.larName.trim())) return '法人姓名须为 2～20 个汉字';
  if (!(keep.larIdcard && form.larIdcard.trim() === '') && !isValidMainlandId(form.larIdcard.trim())) return '法人身份证号须为 15 或 18 位大陆居民身份证并通过校验位';
  const larRangeError = validateDateRange('法人证件有效期', form.larIdcardStDt.trim(), form.larIdcardExpDt.trim());
  if (larRangeError) return larRangeError;
  if (!(keep.larMobile && form.larMobile.trim() === '') && !/^1\d{10}$/.test(form.larMobile.trim())) return '法人手机号须为 11 位手机号';
  if (!(keep.contactName && form.contactName.trim() === '') && !/^[\u4e00-\u9fa5]{2,20}$/.test(form.contactName.trim())) return '联系人姓名须为 2～20 个汉字';
  if (!(keep.contactMobile && form.contactMobile.trim() === '') && !/^1\d{10}$/.test(form.contactMobile.trim())) return '联系人手机号须为 11 位手机号';
  if (form.acctTypeCode !== '57' && form.acctTypeCode !== '58') return '结算账户类型须为对公（57）或对私（58）';
  const acctName = form.acctName.trim();
  if (!(keep.acctName && acctName === '') && (acctName.length < 2 || acctName.length > 40)) return '结算户名须为 2～40 字';
  if (form.acctTypeCode === '57' && acctName !== '' && acctName !== merRegName) return '对公账户的结算户名须与注册名称一致';
  if (!(keep.acctNo && form.acctNo.trim() === '') && !/^[0-9]{8,40}$/.test(form.acctNo.trim())) return '结算账号须为 8～40 位数字';
  const settlerRequired = form.acctTypeCode === '58' && form.settlePersonDiffers;
  if (settlerRequired) {
    if (!(keep.settler && form.acctIdcard.trim() === '') && !isValidMainlandId(form.acctIdcard.trim())) return '结算人证件号须为 15 或 18 位大陆居民身份证并通过校验位';
    if (form.acctIdcard.trim() !== '' && form.acctIdcard.trim() === form.larIdcard.trim()) return '结算人证件号与法人身份证号相同，应改按「结算人为法人本人」填写';
    if (!(keep.settler && form.acctIdExpDt.trim() === '') && !isValidYmd(form.acctIdExpDt.trim())) return '结算人证件有效期须为合法的 yyyy-MM-dd 日期';
  }
  if (!/^[0-9A-Za-z]{6,20}$/.test(form.openningBankCode.trim())) return '开户行行号须为 6～20 位数字或字母';
  const openningBankName = form.openningBankName.trim();
  if (openningBankName.length < 2 || openningBankName.length > 40) return '开户行名称须为 2～40 字';
  if (!/^[0-9A-Za-z]{6,20}$/.test(form.clearingBankCode.trim())) return '清算行行号须为 6～20 位数字或字母';
  const missingRequired = REQUIRED_ATTACHMENT_TYPES.filter(type => !form.attachments[type].trim());
  if (missingRequired.length > 0) {
    return `以下必传附件缺失：${missingRequired.map(type => ONBOARDING_ATTACHMENT_LABELS[type]).join('、')}`;
  }
  for (const type of SETTLER_ATTACHMENT_TYPES) {
    const filled = Boolean(form.attachments[type].trim());
    if (settlerRequired && !filled) return `非法人结算时须上传「${ONBOARDING_ATTACHMENT_LABELS[type]}」`;
    if (!settlerRequired && filled) return `结算人为法人本人时「${ONBOARDING_ATTACHMENT_LABELS[type]}」必须留空`;
  }
  if (form.cooperationAssetId.trim() && !/^[1-9][0-9]{0,18}$/.test(form.cooperationAssetId.trim())) return '《分账合作协议》附件须为有效的私有素材 ID（十进制数字）';
  const reason = form.reason.trim();
  if (reason.length < 2 || reason.length > 256) return '操作原因须为 2～256 字（写入审计日志）';
  return '';
}

const stepTimelineStatus = (status: string): 'wait' | 'process' | 'finish' | 'error' => {
  if (status === 'DONE') return 'finish';
  if (status === 'REJECTED') return 'error';
  if (status === 'NONE') return 'wait';
  return 'process';
};

export default function OnboardingPage({ client, canWrite, canReadSensitive, onAuthLost }: {
  client: OnboardingAdminClient;
  canWrite: boolean;
  canReadSensitive: boolean;
  onAuthLost: () => void;
}) {
  const [merchantIdInput, setMerchantIdInput] = useState('');
  const [defaults, setDefaults] = useState<OnboardingDefaultsView>();
  const [detail, setDetail] = useState<OnboardingDetailView | null>(null);
  const [recordMissing, setRecordMissing] = useState(false);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [form, setForm] = useState<OnboardingFormState | null>(null);
  const [formError, setFormError] = useState('');
  const [uploadingKey, setUploadingKey] = useState<string | null>(null);
  const saveRequestIdRef = useRef(crypto.randomUUID());

  const [audits, setAudits] = useState<OnboardingAuditView[]>();
  const [auditsError, setAuditsError] = useState('');

  // 受审明文（仅内存态：隐藏、切换商家或刷新后立即清除，不写入任何持久存储）
  const [revealed, setRevealed] = useState<OnboardingRevealView | null>(null);
  const [revealOpen, setRevealOpen] = useState(false);
  const [revealReason, setRevealReason] = useState('');
  const [revealError, setRevealError] = useState('');

  const [adoptOpen, setAdoptOpen] = useState(false);
  const [adoptContractId, setAdoptContractId] = useState('');
  const [adoptReason, setAdoptReason] = useState('');
  const [adoptError, setAdoptError] = useState('');
  const adoptRequestIdRef = useRef(crypto.randomUUID());

  const [discardOpen, setDiscardOpen] = useState(false);
  const [discardReason, setDiscardReason] = useState('');
  const [discardError, setDiscardError] = useState('');
  const discardRequestIdRef = useRef(crypto.randomUUID());

  const [retryOpen, setRetryOpen] = useState(false);
  const [retryReason, setRetryReason] = useState('');
  const [retryCoopAssetId, setRetryCoopAssetId] = useState('');
  const [retryError, setRetryError] = useState('');
  const retryRequestIdRef = useRef(crypto.randomUUID());

  const sequence = useRef(0);

  function handleError(caught: unknown, fallback: string, setter: (msg: string) => void = setError) {
    if (caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED') {
      onAuthLost();
      return;
    }
    setter(describeOnboardingFailure(caught, fallback));
  }

  async function loadAudits(merchantId: string) {
    setAuditsError('');
    try {
      const rows = await client.listAudits(merchantId);
      setAudits(rows);
    } catch (caught) {
      if (caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED') {
        onAuthLost();
        return;
      }
      setAudits(undefined);
      setAuditsError(describeOnboardingFailure(caught, '读取审计记录失败'));
    }
  }

  // 写接口统一经这里套用返回的最新详情：明文立即清除，表单按最新 allowedActions 重建，审计重新拉取。
  function applyDetail(next: OnboardingDetailView, successNotice: string) {
    setDetail(next);
    setRecordMissing(false);
    setRevealed(null);
    setForm(next.allowedActions.includes('SAVE_PROFILE') && canWrite ? formFromDetail(next) : null);
    setNotice(successNotice);
    void loadAudits(next.merchantId);
  }

  async function runQuery() {
    const trimmed = merchantIdInput.trim();
    if (!isValidDecimalId(trimmed)) {
      setError('请输入有效的商家 ID（正整数 Snowflake ID）。');
      return;
    }
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    setNotice('');
    setForm(null);
    setFormError('');
    setAudits(undefined);
    setAuditsError('');
    setRevealed(null);
    setRevealOpen(false);
    setAdoptOpen(false);
    setDiscardOpen(false);
    setRetryOpen(false);
    try {
      const nextDefaults = await client.getDefaults(trimmed);
      let nextDetail: OnboardingDetailView | null = null;
      let missing = false;
      try {
        nextDetail = await client.getOnboarding(trimmed);
      } catch (caught) {
        if (caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED') {
          onAuthLost();
          return;
        }
        if (!(caught instanceof RequestFailure && caught.status === 404)) {
          if (call !== sequence.current) return;
          setDefaults(nextDefaults);
          setDetail(null);
          setRecordMissing(false);
          setError(describeOnboardingFailure(caught, '读取开通详情失败'));
          return;
        }
        missing = true;
      }
      if (call !== sequence.current) return;
      setDefaults(nextDefaults);
      setRecordMissing(missing);
      if (nextDetail) {
        setDetail(nextDetail);
        setForm(nextDetail.allowedActions.includes('SAVE_PROFILE') && canWrite ? formFromDetail(nextDetail) : null);
        void loadAudits(trimmed);
        setNotice(`已加载商家 ${trimmed} 的开通记录（状态：${ONBOARDING_STATUS_LABELS[nextDetail.status] ?? nextDetail.status}）。`);
      } else {
        setDetail(null);
        setForm(canWrite ? emptyFormFromDefaults(nextDefaults) : null);
        setNotice(`商家 ${trimmed} 暂无开通记录，已按「新开通」准备空白资料表单。`);
      }
    } catch (caught) {
      if (call !== sequence.current) return;
      handleError(caught, '查询商家收单开通信息失败');
      setDefaults(undefined);
      setDetail(null);
      setRecordMissing(false);
    } finally {
      if (call === sequence.current) setLoading(false);
    }
  }

  function updateForm<K extends keyof OnboardingFormState>(key: K, value: OnboardingFormState[K]) {
    setForm(prev => (prev ? { ...prev, [key]: value } : prev));
    saveRequestIdRef.current = crypto.randomUUID();
    setFormError('');
  }

  function updateAttachment(type: OnboardingAttachmentType, assetId: string) {
    setForm(prev => (prev ? { ...prev, attachments: { ...prev.attachments, [type]: assetId } } : prev));
    saveRequestIdRef.current = crypto.randomUUID();
    setFormError('');
  }

  async function doUpload(target: 'cooperation' | OnboardingAttachmentType, file: File) {
    if (!canWrite || busy) return;
    const merchantId = detail?.merchantId ?? defaults?.merchantId;
    if (!merchantId) {
      setFormError('请先查询商家后再上传附件。');
      return;
    }
    if (file.type !== 'image/jpeg' && file.type !== 'image/png') {
      setFormError('附件仅支持 jpg / png 格式。');
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      setFormError('附件大小不能超过 5MB。');
      return;
    }
    setUploadingKey(target);
    setFormError('');
    try {
      const uploaded = await client.uploadAsset(merchantId, file);
      if (target === 'cooperation') {
        setForm(prev => (prev ? { ...prev, cooperationAssetId: uploaded.assetId } : prev));
      } else {
        updateAttachment(target, uploaded.assetId);
      }
      setNotice(`附件上传成功（assetId ${uploaded.assetId}）。`);
    } catch (caught) {
      handleError(caught, '上传附件失败', setFormError);
    } finally {
      setUploadingKey(null);
    }
  }

  // 已有资料（详情存在）时敏感字段留空表示保持原值；结算人字段仅当库里已有结算人资料时才可留空。
  const keepable: KeepableSecrets = detail && !recordMissing
    ? {
        licenseNo: true,
        larName: true,
        larIdcard: true,
        larMobile: true,
        contactName: true,
        contactMobile: true,
        acctName: true,
        acctNo: true,
        settler: detail.settlement.acctIdType !== null && detail.settlement.acctIdType !== undefined,
      }
    : keepNothing;

  function buildSavePayload(expectedVersion: number): OnboardingSaveRequest {
    if (!form) throw new Error('form-missing');
    const settlerRequired = form.acctTypeCode === '58' && form.settlePersonDiffers;
    return {
      expectedVersion,
      merchant: {
        merRegName: form.merRegName.trim(),
        merBizName: form.merBizName.trim() || null,
        merRegDistCode: form.merRegDistCode.trim(),
        merRegAddr: form.merRegAddr.trim(),
        mccCode: form.mccCode.trim() || null,
        merBlisName: form.merBlisName.trim(),
        merBlisNo: form.merBlisNo.trim().toUpperCase() || null,
        merBlisStDt: form.merBlisStDt.trim(),
        merBlisExpDt: form.merBlisExpDt.trim(),
        larName: form.larName.trim() || null,
        larIdcard: form.larIdcard.trim().toUpperCase() || null,
        larIdcardStDt: form.larIdcardStDt.trim(),
        larIdcardExpDt: form.larIdcardExpDt.trim(),
        larMobile: form.larMobile.trim() || null,
        contactName: form.contactName.trim() || null,
        contactMobile: form.contactMobile.trim() || null,
      },
      settlement: {
        acctTypeCode: form.acctTypeCode,
        acctName: form.acctName.trim() || null,
        acctNo: form.acctNo.trim() || null,
        acctIdType: settlerRequired ? '01' : null,
        acctIdcard: settlerRequired ? form.acctIdcard.trim().toUpperCase() || null : null,
        acctIdExpDt: settlerRequired ? form.acctIdExpDt.trim() || null : null,
        openningBankCode: form.openningBankCode.trim(),
        openningBankName: form.openningBankName.trim(),
        clearingBankCode: form.clearingBankCode.trim(),
      },
      attachments: (() => {
        const record = {} as Record<OnboardingAttachmentType, string | null>;
        for (const type of ONBOARDING_ATTACHMENT_TYPES) record[type] = form.attachments[type].trim() || null;
        return record;
      })(),
      cooperationAssetId: form.cooperationAssetId.trim() || null,
      reason: form.reason.trim(),
    };
  }

  async function handleSave() {
    if (!form || !canWrite || busy) return;
    const merchantId = detail?.merchantId ?? defaults?.merchantId;
    if (!merchantId) {
      setFormError('请先查询商家后再保存资料。');
      return;
    }
    const validationError = validateOnboardingForm(form, keepable);
    if (validationError) {
      setFormError(validationError);
      return;
    }
    setBusy(true);
    setFormError('');
    try {
      const saved = await client.saveOnboarding(merchantId, buildSavePayload(detail?.version ?? 0), saveRequestIdRef.current);
      applyDetail(saved, `商家 ${saved.merchantId} 开通资料已保存（状态：${ONBOARDING_STATUS_LABELS[saved.status] ?? saved.status}，版本 ${saved.version}）。`);
    } catch (caught) {
      if (caught instanceof RequestFailure && caught.code === 'IDEMPOTENCY_KEY_CONFLICT') {
        saveRequestIdRef.current = crypto.randomUUID();
      }
      handleError(caught, '保存开通资料失败', setFormError);
    } finally {
      setBusy(false);
    }
  }

  async function runAction(
    fallback: string,
    call: (requestId: string) => Promise<OnboardingDetailView>,
    successNotice: (next: OnboardingDetailView) => string,
  ) {
    if (!detail || !canWrite || busy) return;
    setBusy(true);
    setError('');
    try {
      const next = await call(crypto.randomUUID());
      applyDetail(next, successNotice(next));
    } catch (caught) {
      handleError(caught, fallback);
    } finally {
      setBusy(false);
    }
  }

  async function submitReveal() {
    if (!detail || !canReadSensitive || busy) return;
    const trimmed = revealReason.trim();
    if (trimmed.length < 2 || trimmed.length > 256) {
      setRevealError('查看原因须为 2～256 字（将写入不可变审计日志）。');
      return;
    }
    setBusy(true);
    setRevealError('');
    try {
      const revealedView = await client.revealOnboarding(detail.merchantId, trimmed, crypto.randomUUID());
      setRevealed(revealedView);
      setRevealOpen(false);
      setRevealReason('');
      setNotice(`已按原因展示商家 ${detail.merchantId} 明文资料并记录审计日志；隐藏或离开页面后立即清除。`);
    } catch (caught) {
      handleError(caught, '查看明文失败', setRevealError);
    } finally {
      setBusy(false);
    }
  }

  async function submitAdopt() {
    if (!detail || !canWrite || busy) return;
    const contractId = adoptContractId.trim();
    const reason = adoptReason.trim();
    if (!contractId) {
      setAdoptError('请填写拉卡拉进件 ID（contractId）。');
      return;
    }
    if (reason.length < 2 || reason.length > 256) {
      setAdoptError('补录原因须为 2～256 字（写入审计日志）。');
      return;
    }
    setBusy(true);
    setAdoptError('');
    try {
      const next = await client.adoptContract(
        detail.merchantId,
        { expectedVersion: detail.version, contractId, reason },
        adoptRequestIdRef.current,
      );
      setAdoptOpen(false);
      setAdoptContractId('');
      setAdoptReason('');
      applyDetail(next, `商家 ${next.merchantId} 已补录进件 ID，正在按该进件查询渠道结果（状态：${ONBOARDING_STATUS_LABELS[next.status] ?? next.status}）。`);
    } catch (caught) {
      if (caught instanceof RequestFailure && caught.code === 'IDEMPOTENCY_KEY_CONFLICT') {
        adoptRequestIdRef.current = crypto.randomUUID();
      }
      handleError(caught, '补录进件 ID 失败', setAdoptError);
    } finally {
      setBusy(false);
    }
  }

  async function submitDiscard() {
    if (!detail || !canWrite || busy) return;
    const reason = discardReason.trim();
    if (reason.length < 2 || reason.length > 256) {
      setDiscardError('原因须为 2～256 字（写入审计日志）。');
      return;
    }
    setBusy(true);
    setDiscardError('');
    try {
      const next = await client.discardContract(
        detail.merchantId,
        { expectedVersion: detail.version, reason },
        discardRequestIdRef.current,
      );
      setDiscardOpen(false);
      setDiscardReason('');
      applyDetail(next, `商家 ${next.merchantId} 已确认拉卡拉未受理，回到草稿（保留资料，已清空本次进件结果）。`);
    } catch (caught) {
      if (caught instanceof RequestFailure && caught.code === 'IDEMPOTENCY_KEY_CONFLICT') {
        discardRequestIdRef.current = crypto.randomUUID();
      }
      handleError(caught, '确认拉卡拉未受理失败', setDiscardError);
    } finally {
      setBusy(false);
    }
  }

  async function submitRetry() {
    if (!detail || !canWrite || busy) return;
    const reason = retryReason.trim();
    if (reason && (reason.length < 2 || reason.length > 256)) {
      setRetryError('重新提交原因留空或为 2～256 字。');
      return;
    }
    setBusy(true);
    setRetryError('');
    try {
      const next = await client.retryOnboarding(
        detail.merchantId,
        {
          expectedVersion: detail.version,
          ...(retryCoopAssetId.trim() ? { cooperationAssetId: retryCoopAssetId.trim() } : {}),
          ...(reason ? { reason } : {}),
        },
        retryRequestIdRef.current,
      );
      setRetryOpen(false);
      setRetryReason('');
      setRetryCoopAssetId('');
      applyDetail(next, `商家 ${next.merchantId} 已重新提交渠道审核（状态：${ONBOARDING_STATUS_LABELS[next.status] ?? next.status}）。`);
    } catch (caught) {
      if (caught instanceof RequestFailure && caught.code === 'IDEMPOTENCY_KEY_CONFLICT') {
        retryRequestIdRef.current = crypto.randomUUID();
      }
      handleError(caught, '重新提交失败', setRetryError);
    } finally {
      setBusy(false);
    }
  }

  async function copySignUrl() {
    if (!detail?.ecSignUrl) return;
    try {
      await navigator.clipboard.writeText(detail.ecSignUrl);
      setNotice('签约链接已复制，请发给商家法人在微信内打开完成人脸签署。');
    } catch {
      setNotice('复制失败，请手动全选下方签约链接复制。');
    }
  }

  const merchantId = detail?.merchantId ?? defaults?.merchantId ?? '';
  const actions: OnboardingAllowedAction[] = detail?.allowedActions ?? [];
  const ineligibleReasonText = defaults && !defaults.eligible
    ? `商家未准入：${INELIGIBLE_REASON_LABELS[defaults.ineligibleReason ?? ''] ?? defaults.ineligibleReason ?? '未知原因'}`
    : '';
  const missingConfigText = defaults && !defaults.configReady
    ? `平台级配置缺失：${(defaults.missingConfig ?? []).map(key => MISSING_CONFIG_LABELS[key] ?? key).join('、')}`
    : '';
  const settlerRequired = (form?.acctTypeCode === '58' && form.settlePersonDiffers) || false;
  const uploadDisabled = busy || uploadingKey !== null || (defaults ? !defaults.eligible : false);
  const submitBlockReason = !canWrite
    ? '当前账号未授予 settlement.onboarding.write 权限'
    : defaults && !defaults.eligible
      ? ineligibleReasonText
      : defaults && !defaults.configReady
        ? '平台级配置缺失，须先补齐配置后再发起开通'
        : !detail
          ? '请先保存资料建档后再发起开通'
          : null;
  const showSubmitButton = Boolean(defaults) && canWrite && (!detail || actions.includes('SUBMIT'));
  const canSave = canWrite && form !== null && (!recordMissing || defaults?.eligible === true);

  const auditColumns: TableColumnsType<OnboardingAuditView> = [
    { title: '时间', dataIndex: 'createdAt', render: value => formatUtcDateTime(value), width: 180 },
    { title: '动作', dataIndex: 'actionType', render: value => <code>{value}</code> },
    { title: '操作者', render: (_, row) => row.operatorType === 'OPERATOR' && row.operatorId ? <code>{row.operatorId}</code> : row.operatorType },
    { title: '原因', dataIndex: 'reason', render: value => value ?? '—' },
    { title: '详情', render: (_, row) => row.detail ? <code>{JSON.stringify(row.detail)}</code> : '—' },
  ];

  const submitButton = showSubmitButton && (submitBlockReason
    ? <Tooltip title={submitBlockReason}><span style={{ display: 'inline-block' }}><Button type="primary" disabled>发起开通</Button></span></Tooltip>
    : <Popconfirm
      title="确认发起开通？"
      description="将校验商家准入与平台级配置、固化平台级参数快照，并向拉卡拉申请电子合同。"
      okText="确认发起"
      cancelText="取消"
      onConfirm={() => detail && void runAction(
        '发起开通失败',
        requestId => client.submitOnboarding(detail.merchantId, detail.version, requestId),
        next => `商家 ${next.merchantId} 已发起开通（状态：${ONBOARDING_STATUS_LABELS[next.status] ?? next.status}）。`,
      )}
    >
      <Button type="primary" loading={busy}>发起开通</Button>
    </Popconfirm>);

  return <PageContainer
    title="商家收单开通"
    hint="拉卡拉商家独立收单进件（Contract 81 / 44 号裁决补充）：运营代填进件资料并上传附件，按「电子合同 → 进件 → 开通分账 → 绑定」推进；平台级参数在发起开通时固化快照。详情默认脱敏展示，查看明文须填原因并写审计。"
  >
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); void runQuery(); }}>
      <label className="filter-item">
        商家 ID
        <Input
          style={{ width: 240 }}
          value={merchantIdInput}
          onChange={event => setMerchantIdInput(event.target.value)}
          placeholder="输入商家 Snowflake ID"
          inputMode="numeric"
        />
      </label>
      <Button type="primary" htmlType="submit" loading={loading}>查询</Button>
    </form>

    {error && <ErrorAlert>{error}</ErrorAlert>}
    {notice && <InlineStatus>{notice}</InlineStatus>}
    {loading && <InlineStatus>正在查询商家收单开通信息</InlineStatus>}

    {defaults && <>
      <Section title={`商家准入与平台级参数 · 商家 ${defaults.merchantId}`}>
        {ineligibleReasonText && <Alert type="error" showIcon style={{ marginBottom: 12 }} message={ineligibleReasonText} />}
        {missingConfigText && <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={`${missingConfigText}。补齐前「发起开通」不可用（保存资料不受影响）。`} />}
        <FactList column={2} items={[
          { label: '商家 ID', value: <code>{defaults.merchantId}</code> },
          { label: '准入状态', value: defaults.eligible
            ? <StatusTag label="已准入" status="ACTIVE" />
            : <StatusTag label={INELIGIBLE_REASON_LABELS[defaults.ineligibleReason ?? ''] ?? defaults.ineligibleReason ?? '未准入'} status="REJECTED" /> },
          { label: '商家名称', value: defaults.merchantName ?? '—' },
          { label: '商家类型', value: defaults.merchantTypeCode ?? '—' },
          { label: '城市码', value: defaults.cityCode ?? '—' },
          { label: '地区码（默认值，表单可改）', value: defaults.districtCode ?? '城市码未映射，须手工填写' },
          { label: '配置状态', value: defaults.configReady
            ? <StatusTag label="平台配置齐全" status="ENABLED" />
            : <StatusTag label="平台配置缺项" status="DISABLED" /> },
          { label: '配置缺项', value: defaults.configReady ? '—' : ((defaults.missingConfig ?? []).map(key => MISSING_CONFIG_LABELS[key] ?? key).join('、') || '—') },
        ]} />
        <h3 style={{ margin: '16px 0 8px' }}>平台级参数（只读，发起开通时固化快照）</h3>
        <FactList column={2} items={[
          { label: 'POS 终端类型 (posType)', value: defaults.platform.posType ?? '未配置' },
          { label: '经营内容 (merBusiContent)', value: defaults.platform.merBusiContent ?? '未配置' },
          { label: '结算周期 (settlePeriod)', value: defaults.platform.settlePeriod ?? '未配置' },
          { label: 'MCC 默认值（表单可改）', value: defaults.platform.mccCode ?? '未配置' },
          {
            label: '渠道费率 (feeRates)',
            value: defaults.platform.feeRates?.length
              ? defaults.platform.feeRates.map(rate => `${rate.feeRateTypeName} ${rate.feeRatePct}%`).join('；')
              : '未配置',
          },
          { label: '最低分账比例 (splitLowestRatio)', value: defaults.platform.splitLowestRatio ? `${defaults.platform.splitLowestRatio}%` : '未配置' },
          { label: '平台分账接收方', value: defaults.platform.platformReceiverConfigured ? '已配置' : '未配置' },
        ]} />
      </Section>

      {detail && <>
        <Section title="开通进度">
          {detail.rejectReason && <Alert type="error" showIcon style={{ marginBottom: 12 }} message={`渠道驳回原因：${detail.rejectReason}`} />}
          {detail.lastErrorCode && <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={`最近渠道错误：${detail.lastErrorCode}${detail.lastErrorMessage ? `（${detail.lastErrorMessage}）` : ''}`} />}
          {detail.status === 'EC_SIGNING' && detail.ecSignUrl && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message="电子合同待商家法人签署"
              description={<div>
                <p style={{ margin: '4px 0' }}>请将签约链接发给商家法人在微信内打开完成人脸签署。</p>
                <Space.Compact style={{ width: '100%', maxWidth: 640 }}>
                  <Input readOnly value={detail.ecSignUrl} aria-label="签约链接" />
                  <Button type="primary" onClick={() => void copySignUrl()}>复制签约链接</Button>
                </Space.Compact>
              </div>}
            />
          )}
          <Steps
            size="small"
            items={detail.steps.map(step => ({
              title: ONBOARDING_STEP_LABELS[step.step] ?? step.step,
              status: stepTimelineStatus(step.status),
              description: <span>
                {ONBOARDING_STEP_STATUS_LABELS[step.status] ?? step.status}
                {step.summary ? ` · ${step.summary}` : ''}
                {step.reason ? <><br />原因：{step.reason}</> : null}
              </span>,
            }))}
          />
          <FactList column={2} items={detail.steps.map(step => ({
            label: `${ONBOARDING_STEP_LABELS[step.step] ?? step.step}（提交/完成）`,
            value: `${formatUtcDateTime(step.submittedAt)} ～ ${formatUtcDateTime(step.finishedAt)}`,
          }))} />
        </Section>

        <Section title="当前开通资料（脱敏）">
          <h3 style={{ margin: '0 0 8px' }}>开通单</h3>
          <FactList column={2} items={[
            { label: '开通单 ID', value: <code>{detail.onboardingId}</code> },
            { label: '当前状态', value: <StatusTag label={ONBOARDING_STATUS_LABELS[detail.status] ?? detail.status} status={detail.status} /> },
            { label: '版本', value: String(detail.version) },
            { label: '提交时间', value: formatUtcDateTime(detail.submittedAt) },
            { label: '完成时间', value: formatUtcDateTime(detail.completedAt) },
            { label: '创建时间', value: formatUtcDateTime(detail.createdAt) },
            { label: '更新时间', value: formatUtcDateTime(detail.updatedAt) },
            { label: '电子合同号 (ecNo)', value: detail.ecNo ? <code>{detail.ecNo}</code> : '—' },
            { label: '进件 ID (contractId)', value: detail.contractId ? <code>{detail.contractId}</code> : '—' },
            { label: '拉卡拉商户号 (merCupNo)', value: detail.merCupNo ? <code>{detail.merCupNo}</code> : '—' },
            { label: '终端号 (termNo)', value: detail.termNo ? <code>{detail.termNo}</code> : '—' },
          ]} />
          <h3 style={{ margin: '16px 0 8px' }}>主体与法人</h3>
          <FactList column={2} items={[
            { label: '注册名称', value: detail.merchant.merRegName ?? '—' },
            { label: '经营名称', value: detail.merchant.merBizName ?? '—' },
            { label: '地区码', value: detail.merchant.merRegDistCode ?? '—' },
            { label: '注册地址', value: detail.merchant.merRegAddr ?? '—' },
            { label: '营业执照名称', value: detail.merchant.merBlisName ?? '—' },
            { label: '营业执照号（脱敏）', value: detail.merchant.merBlisNoMasked ?? '—' },
            { label: '营业执照有效期', value: `${detail.merchant.merBlisStDt ?? '—'} ～ ${detail.merchant.merBlisExpDt ?? '—'}` },
            { label: '法人姓名（脱敏）', value: detail.merchant.larName ?? '—' },
            { label: '法人证件号（脱敏）', value: detail.merchant.larIdcardMasked ?? '—' },
            { label: '法人证件有效期', value: `${detail.merchant.larIdcardStDt ?? '—'} ～ ${detail.merchant.larIdcardExpDt ?? '—'}` },
            { label: '法人手机号（脱敏）', value: detail.merchant.larMobileMasked ?? '—' },
            { label: '联系人（脱敏）', value: detail.merchant.contactName ?? '—' },
            { label: '联系人手机号（脱敏）', value: detail.merchant.contactMobileMasked ?? '—' },
            { label: 'MCC', value: detail.platform.mccCode ?? '—' },
          ]} />
          <h3 style={{ margin: '16px 0 8px' }}>结算账户</h3>
          <FactList column={2} items={[
            { label: '账户类型', value: detail.settlement.acctTypeCode === '57' ? '57 · 对公' : detail.settlement.acctTypeCode === '58' ? '58 · 对私' : (detail.settlement.acctTypeCode ?? '—') },
            { label: '结算户名（脱敏）', value: detail.settlement.acctName ?? '—' },
            { label: '结算账号（脱敏）', value: detail.settlement.acctNoMasked ?? '—' },
            { label: '账号末四位', value: detail.settlement.acctNoLast4 ?? '—' },
            { label: '结算人证件类型', value: detail.settlement.acctIdType ?? '同法人' },
            { label: '结算人证件号（脱敏）', value: detail.settlement.acctIdcardMasked ?? '—' },
            { label: '结算人证件有效期', value: detail.settlement.acctIdExpDt ?? '—' },
            { label: '开户行名称', value: detail.settlement.openningBankName ?? '—' },
            { label: '开户行行号', value: detail.settlement.openningBankCode ?? '—' },
            { label: '清算行行号', value: detail.settlement.clearingBankCode ?? '—' },
          ]} />
          <h3 style={{ margin: '16px 0 8px' }}>附件（素材 ID）</h3>
          <FactList column={2} items={[
            ...ONBOARDING_ATTACHMENT_TYPES.map(type => ({
              label: ONBOARDING_ATTACHMENT_LABELS[type],
              value: detail.attachments[type] ? <code>{detail.attachments[type]}</code> : '—',
            })),
            { label: '《分账合作协议》', value: detail.cooperationAssetId ? <code>{detail.cooperationAssetId}</code> : '—' },
          ]} />
        </Section>
      </>}

      <Section
        title="操作"
        extra={detail && canReadSensitive && (
          <Button onClick={() => { setRevealReason(''); setRevealError(''); setRevealOpen(true); }}>查看明文</Button>
        )}
      >
        {!canWrite && <Alert type="info" showIcon style={{ marginBottom: 12 }} message="当前账号未授予 settlement.onboarding.write，仅可查看。" />}
        {detail && actions.length === 0 && <Alert type="info" showIcon style={{ marginBottom: 12 }} message="当前状态无可用操作，等待系统自动推进或渠道回调。" />}
        <Space wrap>
          {submitButton}
          {detail && actions.includes('SYNC') && (
            <Button loading={busy} onClick={() => void runAction(
              '同步渠道状态失败',
              requestId => client.syncOnboarding(detail.merchantId, requestId),
              next => `已同步商家 ${next.merchantId} 渠道状态：${ONBOARDING_STATUS_LABELS[next.status] ?? next.status}。`,
            )}>同步</Button>
          )}
          {detail && actions.includes('RETRY') && (
            <Button loading={busy} onClick={() => {
              setRetryReason('');
              setRetryCoopAssetId(detail.cooperationAssetId ?? '');
              setRetryError('');
              retryRequestIdRef.current = crypto.randomUUID();
              setRetryOpen(true);
            }}>重新提交</Button>
          )}
          {detail && actions.includes('ADOPT_CONTRACT') && (
            <Button loading={busy} onClick={() => {
              setAdoptContractId('');
              setAdoptReason('');
              setAdoptError('');
              adoptRequestIdRef.current = crypto.randomUUID();
              setAdoptOpen(true);
            }}>补录进件 ID</Button>
          )}
          {detail && actions.includes('DISCARD_CONTRACT') && (
            <Button danger loading={busy} onClick={() => {
              setDiscardReason('');
              setDiscardError('');
              discardRequestIdRef.current = crypto.randomUUID();
              setDiscardOpen(true);
            }}>确认拉卡拉未受理</Button>
          )}
        </Space>
      </Section>

      {revealed && (
        <Section
          title="明文资料（临时显示，隐藏即清除）"
          extra={<Button onClick={() => setRevealed(null)}>隐藏明文</Button>}
        >
          <Alert type="warning" showIcon style={{ marginBottom: 12 }} message="本次查看已写入不可变审计日志；明文仅临时显示，不写入任何持久存储，隐藏或离开页面后立即清除。" />
          <FactList column={1} items={[
            { label: '营业执照号（明文）', value: <code>{revealed.merBlisNo ?? '—'}</code> },
            { label: '法人姓名（明文）', value: revealed.larName ?? '—' },
            { label: '法人身份证号（明文）', value: <code>{revealed.larIdcard ?? '—'}</code> },
            { label: '法人手机号（明文）', value: <code>{revealed.larMobile ?? '—'}</code> },
            { label: '联系人姓名（明文）', value: revealed.contactName ?? '—' },
            { label: '联系人手机号（明文）', value: <code>{revealed.contactMobile ?? '—'}</code> },
            { label: '结算户名（明文）', value: revealed.acctName ?? '—' },
            { label: '结算账号（明文）', value: <code>{revealed.acctNo ?? '—'}</code> },
            { label: '结算人证件类型', value: revealed.acctIdType ?? '—' },
            { label: '结算人证件号（明文）', value: <code>{revealed.acctIdcard ?? '—'}</code> },
          ]} />
        </Section>
      )}

      {form && canWrite && (
        <Section title={recordMissing ? `编辑开通资料（新开通 · 商家 ${merchantId}）` : `编辑开通资料（商家 ${merchantId}）`}>
          {recordMissing && defaults && !defaults.eligible && (
            <Alert type="error" showIcon style={{ marginBottom: 12 }} message={`${ineligibleReasonText}，禁止保存与发起开通。`} />
          )}
          {!recordMissing && <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="编辑已有资料：营业执照号、法人/联系人姓名与手机号、结算户名与卡号、结算人证件号等敏感字段留空表示保持原值，填写则覆盖。"
          />}
          <form onSubmit={event => { event.preventDefault(); void handleSave(); }}>
            <h3 style={{ margin: '0 0 8px' }}>主体信息</h3>
            <div className="mkt-form-grid">
              <div className="filter-item">
                <label htmlFor="ob-merRegName">注册名称（4～80 字，须与营业执照名称一致）</label>
                <Input id="ob-merRegName" value={form.merRegName} disabled={busy} maxLength={80} onChange={event => updateForm('merRegName', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-merBizName">经营名称（选填，4～64 字）</label>
                <Input id="ob-merBizName" value={form.merBizName} disabled={busy} maxLength={64} onChange={event => updateForm('merBizName', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-merRegDistCode">地区码（6～8 位数字）</label>
                <Input id="ob-merRegDistCode" value={form.merRegDistCode} disabled={busy} maxLength={8} onChange={event => updateForm('merRegDistCode', event.target.value)} />
                <span className="hint">默认取城市码映射，可手工修改。</span>
              </div>
              <div className="filter-item">
                <label htmlFor="ob-merRegAddr">注册地址（6～80 字，拉卡拉上限 80 字）</label>
                <Input id="ob-merRegAddr" value={form.merRegAddr} disabled={busy} maxLength={80} onChange={event => updateForm('merRegAddr', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-mccCode">MCC 编码（留空取平台映射，填写须 4 位数字）</label>
                <Input id="ob-mccCode" value={form.mccCode} disabled={busy} maxLength={4} onChange={event => updateForm('mccCode', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-merBlisName">营业执照名称（4～80 字）</label>
                <Input id="ob-merBlisName" value={form.merBlisName} disabled={busy} maxLength={80} onChange={event => updateForm('merBlisName', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-merBlisNo">营业执照编号（8～40 位数字与字母，编辑时留空保持原值）</label>
                <Input id="ob-merBlisNo" value={form.merBlisNo} disabled={busy} maxLength={40} onChange={event => updateForm('merBlisNo', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-merBlisStDt">营业执照有效期起（yyyy-MM-dd）</label>
                <Input id="ob-merBlisStDt" value={form.merBlisStDt} disabled={busy} placeholder="2020-01-01" onChange={event => updateForm('merBlisStDt', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-merBlisExpDt">营业执照有效期止（yyyy-MM-dd，不早于当日；长期执照填拉卡拉确认的换算日期）</label>
                <Input id="ob-merBlisExpDt" value={form.merBlisExpDt} disabled={busy} placeholder="2040-01-01" onChange={event => updateForm('merBlisExpDt', event.target.value)} />
              </div>
            </div>

            <h3 style={{ margin: '16px 0 8px' }}>法人信息</h3>
            <div className="mkt-form-grid">
              <div className="filter-item">
                <label htmlFor="ob-larName">法人姓名（2～20 个汉字）</label>
                <Input id="ob-larName" value={form.larName} disabled={busy} maxLength={20} onChange={event => updateForm('larName', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-larIdcard">法人身份证号（15/18 位，含校验位）</label>
                <Input id="ob-larIdcard" value={form.larIdcard} disabled={busy} maxLength={18} onChange={event => updateForm('larIdcard', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-larIdcardStDt">法人证件有效期起（yyyy-MM-dd）</label>
                <Input id="ob-larIdcardStDt" value={form.larIdcardStDt} disabled={busy} placeholder="1999-05-29" onChange={event => updateForm('larIdcardStDt', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-larIdcardExpDt">法人证件有效期止（yyyy-MM-dd，不早于当日）</label>
                <Input id="ob-larIdcardExpDt" value={form.larIdcardExpDt} disabled={busy} placeholder="2039-05-29" onChange={event => updateForm('larIdcardExpDt', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-larMobile">法人手机号（11 位）</label>
                <Input id="ob-larMobile" value={form.larMobile} disabled={busy} maxLength={11} onChange={event => updateForm('larMobile', event.target.value)} />
              </div>
            </div>

            <h3 style={{ margin: '16px 0 8px' }}>联系人</h3>
            <div className="mkt-form-grid">
              <div className="filter-item">
                <label htmlFor="ob-contactName">联系人姓名（2～20 个汉字）</label>
                <Input id="ob-contactName" value={form.contactName} disabled={busy} maxLength={20} onChange={event => updateForm('contactName', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-contactMobile">联系人手机号（11 位）</label>
                <Input id="ob-contactMobile" value={form.contactMobile} disabled={busy} maxLength={11} onChange={event => updateForm('contactMobile', event.target.value)} />
              </div>
            </div>

            <h3 style={{ margin: '16px 0 8px' }}>结算账户</h3>
            <div className="mkt-form-grid">
              <div className="filter-item">
                <label htmlFor="ob-acctTypeCode">账户类型</label>
                <Select
                  id="ob-acctTypeCode"
                  style={{ width: 220 }}
                  value={form.acctTypeCode}
                  disabled={busy}
                  onChange={value => {
                    updateForm('acctTypeCode', value);
                    if (value === '57') {
                      updateForm('settlePersonDiffers', false);
                      updateForm('acctIdcard', '');
                      updateForm('acctIdExpDt', '');
                    }
                  }}
                  options={[
                    { value: '58', label: '58 · 对私账户' },
                    { value: '57', label: '57 · 对公账户' },
                  ]}
                />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-acctName">结算户名（2～40 字；对公须等于注册名称）</label>
                <Input id="ob-acctName" value={form.acctName} disabled={busy} maxLength={40} onChange={event => updateForm('acctName', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-acctNo">结算账号（8～40 位数字）</label>
                <Input id="ob-acctNo" value={form.acctNo} disabled={busy} maxLength={40} onChange={event => updateForm('acctNo', event.target.value)} />
              </div>
              {form.acctTypeCode === '58' && (
                <div className="filter-item">
                  <label htmlFor="ob-settlePersonDiffers">结算人是否非法人（与法人不是同一人）</label>
                  <div>
                    <Switch
                      id="ob-settlePersonDiffers"
                      checked={form.settlePersonDiffers}
                      disabled={busy}
                      checkedChildren="非法人结算"
                      unCheckedChildren="结算人=法人本人"
                      onChange={checked => {
                        updateForm('settlePersonDiffers', checked);
                        if (!checked) {
                          updateForm('acctIdcard', '');
                          updateForm('acctIdExpDt', '');
                        }
                      }}
                    />
                  </div>
                  <span className="hint">非法人结算须补传结算人身份证正反面与非法人结算授权书三项附件。</span>
                </div>
              )}
              {form.acctTypeCode === '58' && form.settlePersonDiffers && <>
                <div className="filter-item">
                  <label htmlFor="ob-acctIdType">结算人证件类型（固定 01 · 居民身份证）</label>
                  <Input id="ob-acctIdType" value="01" disabled readOnly />
                </div>
                <div className="filter-item">
                  <label htmlFor="ob-acctIdcard">结算人证件号（15/18 位，不得与法人相同）</label>
                  <Input id="ob-acctIdcard" value={form.acctIdcard} disabled={busy} maxLength={18} onChange={event => updateForm('acctIdcard', event.target.value)} />
                </div>
                <div className="filter-item">
                  <label htmlFor="ob-acctIdExpDt">结算人证件有效期止（yyyy-MM-dd）</label>
                  <Input id="ob-acctIdExpDt" value={form.acctIdExpDt} disabled={busy} placeholder="2039-05-29" onChange={event => updateForm('acctIdExpDt', event.target.value)} />
                </div>
              </>}
              <div className="filter-item">
                <label htmlFor="ob-openningBankCode">开户行行号（6～20 位数字或字母）</label>
                <Input id="ob-openningBankCode" value={form.openningBankCode} disabled={busy} maxLength={20} onChange={event => updateForm('openningBankCode', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-openningBankName">开户行名称（2～40 字）</label>
                <Input id="ob-openningBankName" value={form.openningBankName} disabled={busy} maxLength={40} onChange={event => updateForm('openningBankName', event.target.value)} />
              </div>
              <div className="filter-item">
                <label htmlFor="ob-clearingBankCode">清算行行号（6～20 位数字或字母）</label>
                <Input id="ob-clearingBankCode" value={form.clearingBankCode} disabled={busy} maxLength={20} onChange={event => updateForm('clearingBankCode', event.target.value)} />
              </div>
            </div>

            <h3 style={{ margin: '16px 0 8px' }}>
              附件（仅 jpg / png，≤5MB；前六项必传{settlerRequired ? '，非法人结算须另传后三项' : '，结算人为法人本人时后三项必须留空'}）
            </h3>
            {ONBOARDING_ATTACHMENT_TYPES.map(type => {
              const settlerOnly = SETTLER_ATTACHMENT_TYPES.includes(type);
              const hidden = settlerOnly && !settlerRequired;
              return <div className="att-row" data-att-type={type} key={type} style={{ marginBottom: 8 }} hidden={hidden}>
                <Space align="baseline" wrap>
                  <span style={{ display: 'inline-block', minWidth: 220 }}>
                    {ONBOARDING_ATTACHMENT_LABELS[type]}
                    {REQUIRED_ATTACHMENT_TYPES.includes(type) ? '（必传）' : settlerOnly ? '（非法人结算必传）' : ''}
                  </span>
                  {form.attachments[type]
                    ? <code>assetId: {form.attachments[type]}</code>
                    : <span className="hint">{hidden ? '无需上传' : '未上传'}</span>}
                  <Upload
                    accept="image/jpeg,image/png"
                    showUploadList={false}
                    disabled={uploadDisabled}
                    beforeUpload={file => { void doUpload(type, file); return false; }}
                  >
                    <Button size="small" icon={<UploadOutlined />} loading={uploadingKey === type} disabled={uploadDisabled}>上传</Button>
                  </Upload>
                  {form.attachments[type] && (
                    <Button size="small" danger disabled={busy || uploadingKey !== null} onClick={() => updateAttachment(type, '')}>移除</Button>
                  )}
                </Space>
              </div>;
            })}

            <div className="filter-item" style={{ marginTop: 12, maxWidth: 720 }}>
              <label>《分账合作协议》附件（选填；绑定被驳回重新提交时可更换）</label>
              <div className="att-row" data-att-type="cooperation">
                <Space align="baseline" wrap>
                  {form.cooperationAssetId ? <code>assetId: {form.cooperationAssetId}</code> : <span className="hint">未上传</span>}
                  <Upload
                    accept="image/jpeg,image/png"
                    showUploadList={false}
                    disabled={uploadDisabled}
                    beforeUpload={file => { void doUpload(COOPERATION_KEY, file); return false; }}
                  >
                    <Button size="small" icon={<UploadOutlined />} loading={uploadingKey === COOPERATION_KEY} disabled={uploadDisabled}>上传合作协议</Button>
                  </Upload>
                  {form.cooperationAssetId && (
                    <Button size="small" danger disabled={busy || uploadingKey !== null} onClick={() => updateForm('cooperationAssetId', '')}>移除</Button>
                  )}
                </Space>
              </div>
            </div>

            <div className="filter-item" style={{ marginTop: 12, maxWidth: 720 }}>
              <label htmlFor="ob-reason">操作原因（必填，2～256 字，写入审计日志）</label>
              <Input.TextArea
                id="ob-reason"
                rows={2}
                maxLength={256}
                showCount
                value={form.reason}
                disabled={busy}
                onChange={event => updateForm('reason', event.target.value)}
              />
            </div>

            {formError && <ErrorAlert>{formError}</ErrorAlert>}
            <div style={{ marginTop: 12 }}>
              {canSave
                ? <Button type="primary" htmlType="submit" loading={busy}>保存资料</Button>
                : <Tooltip title={recordMissing && defaults && !defaults.eligible ? ineligibleReasonText : '当前状态不可保存资料'}>
                  <span style={{ display: 'inline-block' }}><Button type="primary" disabled htmlType="submit">保存资料</Button></span>
                </Tooltip>}
            </div>
          </form>
        </Section>
      )}

      {detail && (
        <Collapse
          style={{ marginTop: 16 }}
          items={[{
            key: 'audits',
            label: `审计记录${audits ? `（${audits.length} 条）` : ''}`,
            children: <>
              {auditsError && <ErrorAlert>{auditsError}</ErrorAlert>}
              {audits && <Table<OnboardingAuditView>
                size="small"
                rowKey={row => row.auditId}
                columns={auditColumns}
                dataSource={[...audits]}
                pagination={{ pageSize: 10, showSizeChanger: false }}
                scroll={{ x: 'max-content' }}
              />}
            </>,
          }]}
        />
      )}

      {/* 受审明文查看弹窗 */}
      <Modal
        title="查看明文（需填写审计原因）"
        open={revealOpen}
        okText="确认查看并记审计"
        cancelText="取消"
        confirmLoading={busy}
        onCancel={() => { setRevealOpen(false); setRevealError(''); }}
        onOk={() => void submitReveal()}
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="需要 settlement.onboarding.read-sensitive 权限；查看行为将写入不可变审计日志，明文临时显示，隐藏即清除。"
        />
        {revealError && <ErrorAlert>{revealError}</ErrorAlert>}
        <label className="field-label" htmlFor="ob-reveal-reason">查看原因（必填，2～256 字）</label>
        <Input.TextArea
          id="ob-reveal-reason"
          rows={3}
          maxLength={256}
          showCount
          value={revealReason}
          onChange={event => { setRevealReason(event.target.value); setRevealError(''); }}
          placeholder="例如：商家来电核对结算卡号"
        />
      </Modal>

      {/* 补录进件 ID 弹窗（CONTRACT_UNKNOWN） */}
      <Modal
        title={`补录拉卡拉进件 ID · 商家 ${merchantId || ''}`}
        open={adoptOpen}
        okText="确认补录"
        cancelText="取消"
        confirmLoading={busy}
        onCancel={() => { setAdoptOpen(false); setAdoptError(''); }}
        onOk={() => void submitAdopt()}
      >
        <Alert type="info" showIcon style={{ marginBottom: 12 }} message="仅用于「进件结果未知」状态：已与拉卡拉核实进件受理后补录，系统将按该进件 ID 查询并继续推进。" />
        {adoptError && <ErrorAlert>{adoptError}</ErrorAlert>}
        <label className="field-label" htmlFor="ob-adopt-contract-id">拉卡拉进件 ID（contractId，必填）</label>
        <Input
          id="ob-adopt-contract-id"
          value={adoptContractId}
          maxLength={64}
          onChange={event => { setAdoptContractId(event.target.value); setAdoptError(''); }}
          placeholder="例如 202104062502898611"
        />
        <label className="field-label" htmlFor="ob-adopt-reason">补录原因（必填，2～256 字）</label>
        <Input.TextArea
          id="ob-adopt-reason"
          rows={3}
          maxLength={256}
          showCount
          value={adoptReason}
          onChange={event => { setAdoptReason(event.target.value); setAdoptError(''); }}
          placeholder="例如：已与拉卡拉核实进件已受理"
        />
      </Modal>

      {/* 确认拉卡拉未受理弹窗（CONTRACT_UNKNOWN） */}
      <Modal
        title={`确认拉卡拉未受理 · 商家 ${merchantId || ''}`}
        open={discardOpen}
        okText="确认回到草稿"
        cancelText="取消"
        confirmLoading={busy}
        onCancel={() => { setDiscardOpen(false); setDiscardError(''); }}
        onOk={() => void submitDiscard()}
      >
        <Alert type="warning" showIcon style={{ marginBottom: 12 }} message="确认后回到草稿：保留已填资料，清空本次进件结果，可修改资料后重新发起开通。" />
        {discardError && <ErrorAlert>{discardError}</ErrorAlert>}
        <label className="field-label" htmlFor="ob-discard-reason">原因（必填，2～256 字）</label>
        <Input.TextArea
          id="ob-discard-reason"
          rows={3}
          maxLength={256}
          showCount
          value={discardReason}
          onChange={event => { setDiscardReason(event.target.value); setDiscardError(''); }}
          placeholder="例如：拉卡拉确认无此进件"
        />
      </Modal>

      {/* 重新提交弹窗（LEDGER_REJECTED / BIND_REJECTED） */}
      <Modal
        title={`重新提交渠道审核 · 商家 ${merchantId || ''}`}
        open={retryOpen}
        okText="确认重新提交"
        cancelText="取消"
        confirmLoading={busy}
        onCancel={() => { setRetryOpen(false); setRetryError(''); }}
        onOk={() => void submitRetry()}
      >
        <Alert type="info" showIcon style={{ marginBottom: 12 }} message={detail?.status === 'BIND_REJECTED'
          ? '绑定审核被驳回：可修复驳回原因后重新提交，并可同时更换《分账合作协议》附件。'
          : '开通分账审核被驳回：请先修复驳回原因，再重新提交渠道审核。'} />
        {retryError && <ErrorAlert>{retryError}</ErrorAlert>}
        {detail?.status === 'BIND_REJECTED' && <>
          <label className="field-label" htmlFor="ob-retry-coop">《分账合作协议》素材 ID（选填，可更换）</label>
          <Input
            id="ob-retry-coop"
            value={retryCoopAssetId}
            maxLength={64}
            onChange={event => { setRetryCoopAssetId(event.target.value); retryRequestIdRef.current = crypto.randomUUID(); setRetryError(''); }}
            placeholder="留空沿用现有合作协议素材"
          />
        </>}
        <label className="field-label" htmlFor="ob-retry-reason">重新提交原因（选填，2～256 字）</label>
        <Input.TextArea
          id="ob-retry-reason"
          rows={3}
          maxLength={256}
          showCount
          value={retryReason}
          onChange={event => { setRetryReason(event.target.value); retryRequestIdRef.current = crypto.randomUUID(); setRetryError(''); }}
          placeholder="例如：已更换合作协议 / 已修复驳回问题"
        />
      </Modal>
      </>}
    </PageContainer>;
}
