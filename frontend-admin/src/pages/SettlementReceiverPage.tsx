import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Input, Modal, Select, Space, Switch } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  ATTACHMENT_TYPE_OPTIONS, RECEIVER_STATUSES, RECEIVER_STATUS_LABELS, RECEIVER_TYPE_LABELS,
  describeSettlementFailure, formatUtcDateTime, isValidDecimalId,
  type ReceiverAccountSummary, type ReceiverAccountView, type ReceiverAttachmentInput,
  type ReceiverSensitiveView, type ReceiverUpsertRequest, type SettlementAdminClient,
  type SettlementPage,
} from '../api/settlementAdmin';
import { RequestFailure } from '../request';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, FactList, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

// 分账接收方管理台账（Contract 80 §4.2 / 40 号 §4）：
// - 默认全脱敏展示；
// - 仅授予 settlement.receiver.read-sensitive 时开放「查看明文敏感信息」，强制填写原因并写审计；
// - 仅授予 settlement.receiver.write 时开放运营代填/创建/变更/绑定/同步（固定主动提款 settleType=01）；
// - 商家端严禁自助填写或修改接收方信息。

const PAGE_SIZE = 20;

type UpsertFormState = {
  mode: 'create' | 'edit';
  merchantId: string;
  submitToChannel: boolean;
  receiverName: string;
  receiverType: string;
  acctNo: string;
  acctName: string;
  acctTypeCode: string;
  acctCertificateType: string;
  acctCertificateNo: string;
  acctOpenBankCode: string;
  acctOpenBankName: string;
  acctClearBankCode: string;
  contactMobile: string;
  licenseNo: string;
  licenseName: string;
  legalPersonName: string;
  legalPersonCertificateType: string;
  legalPersonCertificateNo: string;
  attachments: ReceiverAttachmentInput[];
  reason: string;
};

function emptyUpsertForm(mode: 'create' | 'edit', seed?: ReceiverAccountView): UpsertFormState {
  return {
    mode,
    merchantId: seed?.merchantId ?? '',
    submitToChannel: true,
    receiverName: seed?.receiverName ?? '',
    receiverType: seed?.receiverType ?? 'PERSONAL',
    acctNo: '',
    acctName: seed?.acctName ?? '',
    acctTypeCode: seed?.acctTypeCode ?? '58',
    acctCertificateType: seed?.acctCertificateType ?? '17',
    acctCertificateNo: '',
    acctOpenBankCode: seed?.acctOpenBankCode ?? '',
    acctOpenBankName: seed?.acctOpenBankName ?? '',
    acctClearBankCode: seed?.acctClearBankCode ?? '',
    contactMobile: '',
    licenseNo: '',
    licenseName: seed?.licenseName ?? '',
    legalPersonName: seed?.legalPersonName ?? '',
    legalPersonCertificateType: seed?.legalPersonCertificateType ?? '',
    legalPersonCertificateNo: '',
    attachments: seed?.attachments?.length
      ? seed.attachments.map(item => ({ attType: item.attType, assetId: item.assetId }))
      : [
        { attType: 'ID_CARD_FRONT', assetId: '' },
        { attType: 'ID_CARD_BEHIND', assetId: '' },
        { attType: 'BANK_CARD', assetId: '' },
      ],
    reason: mode === 'create' ? '运营代填开通' : '运营变更接收方信息',
  };
}

export default function SettlementReceiverPage({ client, canWrite, canReadSensitive, onAuthLost }: {
  client: SettlementAdminClient;
  canWrite: boolean;
  canReadSensitive: boolean;
  onAuthLost: () => void;
}) {
  const [merchantIdFilter, setMerchantIdFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [result, setResult] = useState<SettlementPage<ReceiverAccountSummary>>();
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  // 详情抽屉与受审明文态（关闭抽屉或切换商家时立即销毁明文）
  const [detail, setDetail] = useState<ReceiverAccountView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [sensitive, setSensitive] = useState<ReceiverSensitiveView | null>(null);

  // 查看明文弹窗
  const [revealOpen, setRevealOpen] = useState(false);
  const [revealReason, setRevealReason] = useState('');
  const [revealError, setRevealError] = useState('');

  // 新增/变更接收方弹窗
  const [upsert, setUpsert] = useState<UpsertFormState | null>(null);
  const [upsertError, setUpsertError] = useState('');
  const upsertRequestIdRef = useRef(crypto.randomUUID());

  // 发起绑定弹窗
  const [bindMerchantId, setBindMerchantId] = useState<string | null>(null);
  const [cooperationAssetId, setCooperationAssetId] = useState('');
  const [bindRemark, setBindRemark] = useState('');
  const [bindError, setBindError] = useState('');
  const bindRequestIdRef = useRef(crypto.randomUUID());

  const sequence = useRef(0);

  useEffect(() => {
    void load(1);
    return () => { sequence.current++; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function handleError(caught: unknown, fallback: string, setter: (msg: string) => void = setError) {
    if (caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED') {
      onAuthLost();
      return;
    }
    setter(describeSettlementFailure(caught, fallback));
  }

  async function load(page: number, nextMerchantId = merchantIdFilter, nextStatus = statusFilter) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.listReceivers({
        merchantId: nextMerchantId.trim() || undefined,
        status: nextStatus || undefined,
        page,
        size: PAGE_SIZE,
      });
      if (call !== sequence.current) return;
      setResult(data);
    } catch (caught) {
      if (call !== sequence.current) return;
      handleError(caught, '读取分账接收方台账失败');
    } finally {
      if (call === sequence.current) setLoading(false);
    }
  }

  async function openDetail(merchantId: string) {
    setSensitive(null);
    setDetailLoading(true);
    setError('');
    try {
      const view = await client.getReceiver(merchantId);
      setDetail(view);
    } catch (caught) {
      handleError(caught, '读取接收方详情失败');
    } finally {
      setDetailLoading(false);
    }
  }

  function closeDetail() {
    setDetail(null);
    setSensitive(null);
    setRevealOpen(false);
    setRevealReason('');
    setRevealError('');
  }

  async function submitReveal() {
    if (!detail || !canReadSensitive || busy) return;
    const trimmed = revealReason.trim();
    if (!trimmed) {
      setRevealError('必须填写查看明文的业务原因（将写入不可变审计日志）。');
      return;
    }
    if (trimmed.length > 256) {
      setRevealError('查看原因不能超过 256 字。');
      return;
    }
    setBusy(true);
    setRevealError('');
    try {
      const revealed = await client.revealReceiver(detail.merchantId, trimmed);
      setSensitive(revealed);
      setRevealOpen(false);
      setRevealReason('');
      setNotice(`已按原因展示商家 ${detail.merchantId} 明文敏感字段并记录审计日志。`);
    } catch (caught) {
      handleError(caught, '查看明文敏感信息失败', setRevealError);
    } finally {
      setBusy(false);
    }
  }

  async function openEditModal(merchantId: string) {
    setUpsertError('');
    upsertRequestIdRef.current = crypto.randomUUID();
    if (detail && detail.merchantId === merchantId) {
      setUpsert(emptyUpsertForm('edit', detail));
      return;
    }
    setBusy(true);
    try {
      const view = await client.getReceiver(merchantId);
      setDetail(view);
      setSensitive(null);
      setUpsert(emptyUpsertForm('edit', view));
    } catch (caught) {
      handleError(caught, '加载待编辑接收方失败');
    } finally {
      setBusy(false);
    }
  }

  function updateUpsertField<K extends keyof UpsertFormState>(key: K, value: UpsertFormState[K]) {
    setUpsert(prev => (prev ? { ...prev, [key]: value } : prev));
    upsertRequestIdRef.current = crypto.randomUUID();
    setUpsertError('');
  }

  async function submitUpsert() {
    if (!upsert || !canWrite || busy) return;
    const targetMerchantId = upsert.merchantId.trim();
    if (!isValidDecimalId(targetMerchantId)) {
      setUpsertError('请输入有效的商家 ID（正整数 Snowflake ID）。');
      return;
    }
    if (!upsert.receiverName.trim() || !upsert.acctName.trim() || !upsert.acctNo.trim()
      || !upsert.acctCertificateNo.trim() || !upsert.acctOpenBankCode.trim()
      || !upsert.acctOpenBankName.trim() || !upsert.acctClearBankCode.trim()
      || !upsert.contactMobile.trim()) {
      setUpsertError('请完整填写接收方名称、收款户名、银行卡号、证件号、开户行/清算行信息与联系手机号。');
      return;
    }
    const attachments = upsert.attachments
      .map(item => ({ attType: item.attType.trim(), assetId: item.assetId.trim() }))
      .filter(item => item.attType && item.assetId);

    const payload: ReceiverUpsertRequest = {
      submitToChannel: upsert.submitToChannel,
      receiverName: upsert.receiverName.trim(),
      receiverType: upsert.receiverType,
      acctNo: upsert.acctNo.trim(),
      acctName: upsert.acctName.trim(),
      acctTypeCode: upsert.acctTypeCode.trim(),
      acctCertificateType: upsert.acctCertificateType.trim(),
      acctCertificateNo: upsert.acctCertificateNo.trim(),
      acctOpenBankCode: upsert.acctOpenBankCode.trim(),
      acctOpenBankName: upsert.acctOpenBankName.trim(),
      acctClearBankCode: upsert.acctClearBankCode.trim(),
      contactMobile: upsert.contactMobile.trim(),
      licenseNo: upsert.licenseNo.trim() || null,
      licenseName: upsert.licenseName.trim() || null,
      legalPersonName: upsert.legalPersonName.trim() || null,
      legalPersonCertificateType: upsert.legalPersonCertificateType.trim() || null,
      legalPersonCertificateNo: upsert.legalPersonCertificateNo.trim() || null,
      attachments,
      ...(upsert.reason.trim() ? { reason: upsert.reason.trim() } : {}),
    };

    setBusy(true);
    setUpsertError('');
    try {
      const saved = upsert.mode === 'create'
        ? await client.createReceiver(targetMerchantId, payload, upsertRequestIdRef.current)
        : await client.updateReceiver(targetMerchantId, payload, upsertRequestIdRef.current);
      setUpsert(null);
      setDetail(saved);
      setSensitive(null);
      setNotice(upsert.mode === 'create'
        ? `商家 ${saved.merchantId} 分账接收方已保存（当前状态：${RECEIVER_STATUS_LABELS[saved.status] ?? saved.status}）。`
        : `商家 ${saved.merchantId} 分账接收方变更已提交（当前状态：${RECEIVER_STATUS_LABELS[saved.status] ?? saved.status}）。`);
      await load(result?.page ?? 1);
    } catch (caught) {
      if (caught instanceof RequestFailure && caught.code === 'IDEMPOTENCY_KEY_CONFLICT') {
        upsertRequestIdRef.current = crypto.randomUUID();
      }
      handleError(caught, upsert.mode === 'create' ? '创建接收方失败' : '变更接收方失败', setUpsertError);
    } finally {
      setBusy(false);
    }
  }

  async function submitBind() {
    if (!bindMerchantId || !canWrite || busy) return;
    setBusy(true);
    setBindError('');
    try {
      const updated = await client.bindReceiver(
        bindMerchantId,
        {
          ...(cooperationAssetId.trim() ? { cooperationAssetId: cooperationAssetId.trim() } : {}),
          ...(bindRemark.trim() ? { remark: bindRemark.trim() } : {}),
        },
        bindRequestIdRef.current,
      );
      setBindMerchantId(null);
      setCooperationAssetId('');
      setBindRemark('');
      if (detail && detail.merchantId === updated.merchantId) {
        setDetail(updated);
        setSensitive(null);
      }
      setNotice(`商家 ${updated.merchantId} 分账关系绑定请求已提交（当前状态：${RECEIVER_STATUS_LABELS[updated.status] ?? updated.status}）。`);
      await load(result?.page ?? 1);
    } catch (caught) {
      if (caught instanceof RequestFailure && caught.code === 'IDEMPOTENCY_KEY_CONFLICT') {
        bindRequestIdRef.current = crypto.randomUUID();
      }
      handleError(caught, '发起分账关系绑定失败', setBindError);
    } finally {
      setBusy(false);
    }
  }

  async function runSync(merchantId: string) {
    if (!canWrite || busy) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const synced = await client.syncReceiver(merchantId);
      if (detail && detail.merchantId === synced.merchantId) {
        setDetail(synced);
        setSensitive(null);
      }
      setNotice(`已同步商家 ${synced.merchantId} 渠道状态：${RECEIVER_STATUS_LABELS[synced.status] ?? synced.status}。`);
      await load(result?.page ?? 1);
    } catch (caught) {
      handleError(caught, '同步渠道状态失败');
    } finally {
      setBusy(false);
    }
  }

  const columns: TableColumnsType<ReceiverAccountSummary> = [
    { title: '商家 ID', dataIndex: 'merchantId', render: value => <code>{value}</code> },
    { title: '接收方名称', dataIndex: 'receiverName' },
    { title: '类型', render: (_, row) => RECEIVER_TYPE_LABELS[row.receiverType] ?? row.receiverType },
    { title: '收款户名', dataIndex: 'acctName' },
    { title: '收款账号（脱敏）', render: (_, row) => row.acctNoMasked ?? '—' },
    { title: '开户行', render: (_, row) => row.acctOpenBankName ?? '—' },
    { title: '拉卡拉接收方号', render: (_, row) => row.receiverNo ? <code>{row.receiverNo}</code> : '—' },
    {
      title: '状态',
      render: (_, row) => <StatusTag label={RECEIVER_STATUS_LABELS[row.status] ?? row.status} status={row.status} />,
    },
    { title: '驳回原因', render: (_, row) => row.rejectReason ?? '—' },
    { title: '更新时间', render: (_, row) => formatUtcDateTime(row.updatedAt) },
    {
      title: '操作',
      render: (_, row) => (
        <Space size="small" wrap>
          <Button size="small" onClick={() => void openDetail(row.merchantId)}>详情</Button>
          {canWrite && <>
            <Button size="small" disabled={busy} onClick={() => void openEditModal(row.merchantId)}>变更</Button>
            {row.receiverNo && row.status !== 'ACTIVE' && (
              <Button
                size="small"
                type="primary"
                disabled={busy}
                onClick={() => {
                  setBindMerchantId(row.merchantId);
                  setCooperationAssetId('');
                  setBindRemark('');
                  setBindError('');
                  bindRequestIdRef.current = crypto.randomUUID();
                }}
              >绑定关系</Button>
            )}
            {row.status !== 'DRAFT' && (
              <Button size="small" disabled={busy} onClick={() => void runSync(row.merchantId)}>同步状态</Button>
            )}
          </>}
        </Space>
      ),
    },
  ];

  return <PageContainer
    title="分账接收方管理"
    hint="拉卡拉分账接收方开通与台账（40 号 §4）：由运营在后台代填收款账户、证件与附件资料并完成绑定（固定主动提款 settleType=01）；商家端仅只读查看状态，不可自助填写或修改。列表与详情默认脱敏展示。"
    extra={canWrite && (
      <Button
        type="primary"
        onClick={() => {
          setUpsertError('');
          upsertRequestIdRef.current = crypto.randomUUID();
          setUpsert(emptyUpsertForm('create'));
        }}
      >代填新增接收方</Button>
    )}>
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); void load(1); }}>
      <label className="filter-item">
        商家 ID
        <Input
          style={{ width: 220 }}
          value={merchantIdFilter}
          onChange={event => setMerchantIdFilter(event.target.value)}
          placeholder="Snowflake ID"
          inputMode="numeric"
        />
      </label>
      <label className="filter-item">
        接收方状态
        <Select
          style={{ width: 180 }}
          value={statusFilter}
          onChange={value => {
            setStatusFilter(value);
            void load(1, merchantIdFilter, value);
          }}
          options={[
            { value: '', label: '全部状态' },
            ...RECEIVER_STATUSES.map(code => ({ value: code, label: RECEIVER_STATUS_LABELS[code] ?? code })),
          ]}
        />
      </label>
      <Button type="primary" htmlType="submit" loading={loading}>查询</Button>
    </form>

    {error && <ErrorAlert>{error}</ErrorAlert>}
    {notice && <InlineStatus>{notice}</InlineStatus>}
    {loading && <InlineStatus>正在加载分账接收方台账</InlineStatus>}

    {result && (
      <DataTable
        columns={columns}
        rows={result.items}
        rowKey={row => row.merchantId}
        loading={loading}
        page={result.page}
        pageSize={result.size || PAGE_SIZE}
        total={result.total}
        onPageChange={page => void load(page)}
      />
    )}

    <Drawer
      title={detail ? `分账接收方详情 · 商家 ${detail.merchantId}` : '分账接收方详情'}
      open={detail !== null || detailLoading}
      width={720}
      onClose={closeDetail}
      extra={detail && (
        <Space>
          {canReadSensitive && (
            <Button onClick={() => { setRevealReason(''); setRevealError(''); setRevealOpen(true); }}>
              查看明文敏感信息
            </Button>
          )}
          {canWrite && <>
            <Button onClick={() => void openEditModal(detail.merchantId)}>变更资料</Button>
            {detail.receiverNo && detail.status !== 'ACTIVE' && (
              <Button
                type="primary"
                onClick={() => {
                  setBindMerchantId(detail.merchantId);
                  setCooperationAssetId('');
                  setBindRemark('');
                  setBindError('');
                  bindRequestIdRef.current = crypto.randomUUID();
                }}
              >绑定关系</Button>
            )}
            {detail.status !== 'DRAFT' && (
              <Button loading={busy} onClick={() => void runSync(detail.merchantId)}>同步状态</Button>
            )}
          </>}
        </Space>
      )}>
      {detailLoading && <InlineStatus>正在读取接收方详情</InlineStatus>}
      {detail && <>
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="默认脱敏展示；提款模式固定为主动提款（settleType=01）。接收方未处于「已生效」状态时，到期结算单自动暂停等待。"
        />
        {detail.rejectReason && (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 16 }}
            message={`渠道驳回原因：${detail.rejectReason}`}
          />
        )}
        <FactList column={2} items={[
          { label: '商家 ID', value: <code>{detail.merchantId}</code> },
          { label: '当前状态', value: <StatusTag label={RECEIVER_STATUS_LABELS[detail.status] ?? detail.status} status={detail.status} /> },
          { label: '接收方编号', value: detail.receiverNo ? <code>{detail.receiverNo}</code> : '—' },
          { label: '渠道申请单号', value: detail.applyId ? <code>{detail.applyId}</code> : '—' },
          { label: '接收方名称', value: detail.receiverName },
          { label: '接收方类型', value: RECEIVER_TYPE_LABELS[detail.receiverType] ?? detail.receiverType },
          { label: '收款户名', value: detail.acctName },
          { label: '收款账号（脱敏）', value: detail.acctNoMasked ?? '—' },
          { label: '账户类型码', value: detail.acctTypeCode ?? '—' },
          { label: '证件类型码', value: detail.acctCertificateType ?? '—' },
          { label: '证件号（脱敏）', value: detail.acctCertificateNoMasked ?? '—' },
          { label: '联系手机号（脱敏）', value: detail.contactMobileMasked ?? '—' },
          { label: '开户行名称', value: detail.acctOpenBankName ?? '—' },
          { label: '开户行行号', value: detail.acctOpenBankCode ?? '—' },
          { label: '清算行行号', value: detail.acctClearBankCode ?? '—' },
          { label: '营业执照号（脱敏）', value: detail.licenseNoMasked ?? '—' },
          { label: '营业执照名称', value: detail.licenseName ?? '—' },
          { label: '法人姓名', value: detail.legalPersonName ?? '—' },
          { label: '更新时间', value: formatUtcDateTime(detail.updatedAt) },
        ]} />

        {detail.attachments && detail.attachments.length > 0 && (
          <div style={{ marginTop: 16 }}>
            <h3>私有素材附件</h3>
            <Descriptions bordered size="small" column={1} items={detail.attachments.map((att, index) => ({
              key: `${att.attType}-${index}`,
              label: att.attType,
              children: <>assetId: <code>{att.assetId}</code>{att.attFileId ? <> · 渠道文件 ID: <code>{att.attFileId}</code></> : null}</>,
            }))} />
          </div>
        )}

        {sensitive && (
          <div style={{ marginTop: 16 }}>
            <Alert
              type="warning"
              showIcon
              message="明文敏感信息（本次查看已写入不可变审计日志，关闭抽屉后立即清除）"
              action={<Button size="small" onClick={() => setSensitive(null)}>隐藏明文</Button>}
              style={{ marginBottom: 12 }}
            />
            <FactList column={1} items={[
              { label: '收款银行卡号（明文）', value: <code>{sensitive.acctNo ?? '—'}</code> },
              { label: '证件号码（明文）', value: <code>{sensitive.acctCertificateNo ?? '—'}</code> },
              { label: '联系手机号（明文）', value: <code>{sensitive.contactMobile ?? '—'}</code> },
              { label: '营业执照号（明文）', value: <code>{sensitive.licenseNo ?? '—'}</code> },
            ]} />
          </div>
        )}
      </>}
    </Drawer>

    {/* 受审明文查看弹窗 */}
    <Modal
      title="查看明文敏感信息（需填写审计原因）"
      open={revealOpen}
      okText="确认查看并记审计"
      cancelText="取消"
      confirmLoading={busy}
      onCancel={() => { setRevealOpen(false); setRevealError(''); }}
      onOk={() => void submitReveal()}>
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message="需要 settlement.receiver.read-sensitive 权限；查看行为将同事务写入 settlement_receiver_audit 不可变审计表。"
      />
      {revealError && <ErrorAlert>{revealError}</ErrorAlert>}
      <label className="field-label" htmlFor="receiver-reveal-reason">查看原因（必填，≤256 字）</label>
      <Input.TextArea
        id="receiver-reveal-reason"
        rows={3}
        maxLength={256}
        showCount
        value={revealReason}
        onChange={event => { setRevealReason(event.target.value); setRevealError(''); }}
        placeholder="例如：核对商家开户卡号与渠道驳回原因"
      />
    </Modal>

    {/* 运营代填/创建/变更接收方弹窗 */}
    <Modal
      title={upsert?.mode === 'edit' ? `变更分账接收方 · 商家 ${upsert.merchantId}` : '运营代填新增分账接收方'}
      open={upsert !== null}
      width={780}
      okText={upsert?.submitToChannel ? '提交至拉卡拉渠道' : '仅保存草稿'}
      cancelText="取消"
      confirmLoading={busy}
      onCancel={() => { setUpsert(null); setUpsertError(''); }}
      onOk={() => void submitUpsert()}>
      {upsert && <>
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="接收方提款模式由服务端固定为 settleType=01（主动提款）；变更/提交将重新核验敏感字段与私有素材附件。"
        />
        {upsertError && <ErrorAlert>{upsertError}</ErrorAlert>}
        <div className="mkt-form-grid">
          <div className="filter-item">
            <label>商家 ID（必填）</label>
            <Input
              value={upsert.merchantId}
              disabled={upsert.mode === 'edit'}
              placeholder="正整数 Snowflake ID"
              onChange={event => updateUpsertField('merchantId', event.target.value)}
            />
          </div>
          <div className="filter-item">
            <label>提交渠道开关</label>
            <div>
              <Switch
                checked={upsert.submitToChannel}
                checkedChildren="立即提交渠道"
                unCheckedChildren="仅保存草稿"
                onChange={checked => updateUpsertField('submitToChannel', checked)}
              />
            </div>
          </div>
          <div className="filter-item">
            <label>接收方名称（必填）</label>
            <Input value={upsert.receiverName} onChange={event => updateUpsertField('receiverName', event.target.value)} placeholder="如：某某宠物店" />
          </div>
          <div className="filter-item">
            <label>接收方类型（必填）</label>
            <Select
              value={upsert.receiverType}
              onChange={value => updateUpsertField('receiverType', value)}
              options={[
                { value: 'PERSONAL', label: '个人 (PERSONAL)' },
                { value: 'ENTERPRISE', label: '企业/个体户 (ENTERPRISE)' },
              ]}
            />
          </div>
          <div className="filter-item">
            <label>收款户名（必填）</label>
            <Input value={upsert.acctName} onChange={event => updateUpsertField('acctName', event.target.value)} placeholder="与银行卡开户姓名一致" />
          </div>
          <div className="filter-item">
            <label>收款银行卡号（必填）</label>
            <Input value={upsert.acctNo} onChange={event => updateUpsertField('acctNo', event.target.value)} placeholder="完整银行卡/对公账号" />
          </div>
          <div className="filter-item">
            <label>账户类型码（必填）</label>
            <Select
              value={upsert.acctTypeCode}
              onChange={value => updateUpsertField('acctTypeCode', value)}
              options={[
                { value: '58', label: '58 · 对私账户' },
                { value: '57', label: '57 · 对公账户' },
              ]}
            />
          </div>
          <div className="filter-item">
            <label>证件类型码（必填）</label>
            <Select
              value={upsert.acctCertificateType}
              onChange={value => updateUpsertField('acctCertificateType', value)}
              options={[
                { value: '17', label: '17 · 居民身份证' },
                { value: '18', label: '18 · 临时身份证' },
                { value: '19', label: '19 · 港澳台通行证' },
                { value: '20', label: '20 · 护照' },
              ]}
            />
          </div>
          <div className="filter-item">
            <label>证件号码（必填）</label>
            <Input value={upsert.acctCertificateNo} onChange={event => updateUpsertField('acctCertificateNo', event.target.value)} placeholder="完整证件号码" />
          </div>
          <div className="filter-item">
            <label>联系手机号（必填）</label>
            <Input value={upsert.contactMobile} onChange={event => updateUpsertField('contactMobile', event.target.value)} placeholder="11 位手机号" />
          </div>
          <div className="filter-item">
            <label>开户行行号（必填）</label>
            <Input value={upsert.acctOpenBankCode} onChange={event => updateUpsertField('acctOpenBankCode', event.target.value)} placeholder="12 位联行号" />
          </div>
          <div className="filter-item">
            <label>开户行名称（必填）</label>
            <Input value={upsert.acctOpenBankName} onChange={event => updateUpsertField('acctOpenBankName', event.target.value)} placeholder="如：中国工商银行..." />
          </div>
          <div className="filter-item">
            <label>清算行行号（必填）</label>
            <Input value={upsert.acctClearBankCode} onChange={event => updateUpsertField('acctClearBankCode', event.target.value)} placeholder="12 位清算行号" />
          </div>
          <div className="filter-item">
            <label>营业执照号（企业选填）</label>
            <Input value={upsert.licenseNo} onChange={event => updateUpsertField('licenseNo', event.target.value)} />
          </div>
          <div className="filter-item">
            <label>营业执照名称（企业选填）</label>
            <Input value={upsert.licenseName} onChange={event => updateUpsertField('licenseName', event.target.value)} />
          </div>
          <div className="filter-item">
            <label>法人姓名（企业选填）</label>
            <Input value={upsert.legalPersonName} onChange={event => updateUpsertField('legalPersonName', event.target.value)} />
          </div>
          <div className="filter-item">
            <label>法人证件类型码（企业选填）</label>
            <Input value={upsert.legalPersonCertificateType} onChange={event => updateUpsertField('legalPersonCertificateType', event.target.value)} placeholder="如 17" />
          </div>
          <div className="filter-item">
            <label>法人证件号（企业选填）</label>
            <Input value={upsert.legalPersonCertificateNo} onChange={event => updateUpsertField('legalPersonCertificateNo', event.target.value)} />
          </div>

          <div className="filter-item mkt-span-2">
            <label>私有素材附件（attachments: attType + assetId）</label>
            {upsert.attachments.map((att, index) => (
              <Space key={index} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
                <Select
                  style={{ width: 260 }}
                  value={att.attType}
                  options={ATTACHMENT_TYPE_OPTIONS}
                  onChange={value => {
                    const next = upsert.attachments.map((item, idx) => (idx === index ? { ...item, attType: value } : item));
                    updateUpsertField('attachments', next);
                  }}
                />
                <Input
                  style={{ width: 240 }}
                  value={att.assetId}
                  placeholder="私有素材 assetId"
                  onChange={event => {
                    const next = upsert.attachments.map((item, idx) => (idx === index ? { ...item, assetId: event.target.value } : item));
                    updateUpsertField('attachments', next);
                  }}
                />
                <Button
                  size="small"
                  danger
                  onClick={() => updateUpsertField('attachments', upsert.attachments.filter((_, idx) => idx !== index))}
                >移除</Button>
              </Space>
            ))}
            <div>
              <Button
                size="small"
                onClick={() => updateUpsertField('attachments', [...upsert.attachments, { attType: 'BANK_CARD', assetId: '' }])}
              >添加附件行</Button>
            </div>
          </div>

          <div className="filter-item mkt-span-2">
            <label>操作原因（选填，写入审计日志）</label>
            <Input value={upsert.reason} onChange={event => updateUpsertField('reason', event.target.value)} maxLength={256} />
          </div>
        </div>
      </>}
    </Modal>

    {/* 绑定分账关系弹窗 */}
    <Modal
      title={bindMerchantId ? `发起分账关系绑定 (1290) · 商家 ${bindMerchantId}` : '发起分账关系绑定'}
      open={bindMerchantId !== null}
      okText="提交绑定申请"
      cancelText="取消"
      confirmLoading={busy}
      onCancel={() => { setBindMerchantId(null); setBindError(''); }}
      onOk={() => void submitBind()}>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="提交后将经拉卡拉 1084 上传合作协议附件并调用 1290 异步绑定，进入 BIND_APPLYING 并自动登记 1872 查单任务。"
      />
      {bindError && <ErrorAlert>{bindError}</ErrorAlert>}
      <label className="field-label" htmlFor="bind-coop-asset">分账合作协议私有素材 ID（选填）</label>
      <Input
        id="bind-coop-asset"
        value={cooperationAssetId}
        onChange={event => {
          setCooperationAssetId(event.target.value);
          bindRequestIdRef.current = crypto.randomUUID();
          setBindError('');
        }}
        placeholder="私有素材 assetId"
      />
      <label className="field-label" htmlFor="bind-remark">备注（选填）</label>
      <Input.TextArea
        id="bind-remark"
        rows={2}
        maxLength={256}
        value={bindRemark}
        onChange={event => {
          setBindRemark(event.target.value);
          bindRequestIdRef.current = crypto.randomUUID();
          setBindError('');
        }}
      />
    </Modal>
  </PageContainer>;
}
