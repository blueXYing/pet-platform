import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Input, Modal, Select } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  RECONCILE_DIFF_STATUSES, RECONCILE_DIFF_STATUS_LABELS, describeSettlementFailure,
  formatAmount, formatUtcDateTime,
  type ReconcileDiffView, type SettlementAdminClient, type SettlementPage,
} from '../api/settlementAdmin';
import { RequestFailure } from '../request';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

// 日对账差异与人工处理（Contract 80 §4.6）：
// - 列表读取需 settlement.report.read；
// - 标记已核销处理需 settlement.instruction.retry，强制填写 note（≤256 字），X-Request-Id 幂等；
// - 对账与标记处理绝不自动改账，不修改任何财务台账金额或状态。

const PAGE_SIZE = 20;

export default function SettlementReconcilePage({ client, canResolve, onAuthLost }: {
  client: SettlementAdminClient;
  canResolve: boolean;
  onAuthLost: () => void;
}) {
  const [reconcileDate, setReconcileDate] = useState('');
  const [diffType, setDiffType] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [result, setResult] = useState<SettlementPage<ReconcileDiffView>>();
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  // 人工处理弹窗
  const [resolvingDiff, setResolvingDiff] = useState<ReconcileDiffView | null>(null);
  const [resolveNote, setResolveNote] = useState('');
  const [modalError, setModalError] = useState('');
  const resolveRequestIdRef = useRef(crypto.randomUUID());

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

  async function load(
    page: number,
    nextDate = reconcileDate,
    nextDiffType = diffType,
    nextStatus = statusFilter,
  ) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.listReconcileDiffs({
        reconcileDate: nextDate || undefined,
        diffType: nextDiffType.trim() || undefined,
        status: nextStatus || undefined,
        page,
        size: PAGE_SIZE,
      });
      if (call !== sequence.current) return;
      setResult(data);
    } catch (caught) {
      if (call !== sequence.current) return;
      handleError(caught, '读取日对账差异列表失败');
    } finally {
      if (call === sequence.current) setLoading(false);
    }
  }

  async function submitResolve() {
    if (!resolvingDiff || !canResolve || busy) return;
    const trimmed = resolveNote.trim();
    if (!trimmed) {
      setModalError('请填写人工核查与处理说明（必填，≤256 字）。');
      return;
    }
    if (trimmed.length > 256) {
      setModalError('处理说明不能超过 256 字。');
      return;
    }
    setBusy(true);
    setModalError('');
    try {
      const updated = await client.resolveReconcileDiff(
        resolvingDiff.diffId,
        trimmed,
        resolveRequestIdRef.current,
      );
      setResolvingDiff(null);
      setResolveNote('');
      setNotice(`差异单 ${updated.diffId} 已标记人工核销处理（未自动修改任何资金台账）。`);
      await load(result?.page ?? 1);
    } catch (caught) {
      if (caught instanceof RequestFailure && caught.code === 'IDEMPOTENCY_KEY_CONFLICT') {
        resolveRequestIdRef.current = crypto.randomUUID();
      }
      handleError(caught, '标记对账差异已处理失败', setModalError);
    } finally {
      setBusy(false);
    }
  }

  const columns: TableColumnsType<ReconcileDiffView> = [
    { title: '差异 ID', dataIndex: 'diffId', render: value => <code>{value}</code> },
    { title: '对账日期', dataIndex: 'reconcileDate' },
    { title: '业务 / 差异类型', render: (_, row) => <>{row.bizType}<br /><small>{row.diffType}</small></> },
    { title: '商家 ID', render: (_, row) => row.merchantId ? <code>{row.merchantId}</code> : '—' },
    {
      title: '关联单号',
      render: (_, row) => (
        <>
          {row.settlementOrderId && <div>结算单：<code>{row.settlementOrderId}</code></div>}
          {row.instructionId && <div>指令：<code>{row.instructionId}</code></div>}
          {row.outSeparateNo && <div>商户号：<code>{row.outSeparateNo}</code></div>}
          {row.separateNo && <div>渠道号：<code>{row.separateNo}</code></div>}
          {!row.settlementOrderId && !row.instructionId && !row.outSeparateNo && !row.separateNo && '—'}
        </>
      ),
    },
    {
      title: '本地 / 渠道金额',
      render: (_, row) => <>{formatAmount(row.localAmount)}<br /><small>渠道 {formatAmount(row.channelAmount)}</small></>,
    },
    {
      title: '本地 / 渠道状态',
      render: (_, row) => <>{row.localStatus ?? '—'}<br /><small>渠道 {row.channelStatus ?? '—'}</small></>,
    },
    { title: '差异详情', render: (_, row) => row.detail ?? '—' },
    {
      title: '处理状态',
      render: (_, row) => (
        <StatusTag label={RECONCILE_DIFF_STATUS_LABELS[row.status] ?? row.status} status={row.status} />
      ),
    },
    {
      title: '处理记录',
      render: (_, row) => row.status === 'RESOLVED'
        ? <><div>{row.resolveNote ?? '—'}</div><small>运营 {row.resolvedByOperatorId ?? '—'} · {formatUtcDateTime(row.resolvedAt)}</small></>
        : '—',
    },
    {
      title: '操作',
      render: (_, row) => (
        canResolve && row.status !== 'RESOLVED' ? (
          <Button
            size="small"
            type="primary"
            disabled={busy}
            onClick={() => {
              setResolvingDiff(row);
              setResolveNote('');
              setModalError('');
              resolveRequestIdRef.current = crypto.randomUUID();
            }}
          >标记已处理</Button>
        ) : '—'
      ),
    },
  ];

  return <PageContainer
    title="日对账差异与异常处理"
    hint="汇总每日渠道分账/回退/提现与本地台账的比对差异，供运营与财务核查并记录人工处置结论。">
    <Alert
      type="warning"
      showIcon
      style={{ marginBottom: 16 }}
      message="不自动改账声明"
      description="日对账任务与「标记已处理」动作仅用于告警登记与人工核销留痕，绝不自动改账或直接修改任何资金、分账、退款或提现台账。"
    />

    <form className="filter-bar" onSubmit={event => { event.preventDefault(); void load(1); }}>
      <label className="filter-item">
        对账日期
        <input type="date" value={reconcileDate} onChange={event => setReconcileDate(event.target.value)} />
      </label>
      <label className="filter-item">
        差异类型
        <Input
          style={{ width: 200 }}
          value={diffType}
          onChange={event => setDiffType(event.target.value)}
          placeholder="如 AMOUNT_MISMATCH"
        />
      </label>
      <label className="filter-item">
        处理状态
        <Select
          style={{ width: 150 }}
          value={statusFilter}
          onChange={value => {
            setStatusFilter(value);
            void load(1, reconcileDate, diffType, value);
          }}
          options={[
            { value: '', label: '全部状态' },
            ...RECONCILE_DIFF_STATUSES.map(code => ({ value: code, label: RECONCILE_DIFF_STATUS_LABELS[code] ?? code })),
          ]}
        />
      </label>
      <Button type="primary" htmlType="submit" loading={loading}>查询</Button>
    </form>

    {error && <ErrorAlert>{error}</ErrorAlert>}
    {notice && <InlineStatus>{notice}</InlineStatus>}
    {loading && <InlineStatus>正在加载日对账差异</InlineStatus>}

    {result && (
      <DataTable
        columns={columns}
        rows={result.items}
        rowKey={row => row.diffId}
        loading={loading}
        page={result.page}
        pageSize={result.size || PAGE_SIZE}
        total={result.total}
        onPageChange={page => void load(page)}
      />
    )}

    <Modal
      title={resolvingDiff ? `标记对账差异已处理 · ${resolvingDiff.diffId}` : '标记对账差异已处理'}
      open={resolvingDiff !== null}
      okText="确认标记已处理"
      cancelText="取消"
      confirmLoading={busy}
      onCancel={() => { setResolvingDiff(null); setModalError(''); }}
      onOk={() => void submitResolve()}>
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message="本操作仅将差异单状态更新为 RESOLVED 并记录人工核查备注，不会自动改账。"
      />
      {modalError && <ErrorAlert>{modalError}</ErrorAlert>}
      <label className="field-label" htmlFor="reconcile-resolve-note">人工核查处理说明（必填，≤256 字）</label>
      <Input.TextArea
        id="reconcile-resolve-note"
        rows={3}
        maxLength={256}
        showCount
        value={resolveNote}
        onChange={event => {
          setResolveNote(event.target.value);
          resolveRequestIdRef.current = crypto.randomUUID();
          setModalError('');
        }}
        placeholder="请填写渠道侧核对结果、补单/重试记录或财务处置结论"
      />
    </Modal>
  </PageContainer>;
}
