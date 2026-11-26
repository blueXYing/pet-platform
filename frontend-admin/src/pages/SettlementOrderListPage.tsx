import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Drawer, Input, Modal, Select, Space, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  INSTRUCTION_STATUS_LABELS, INSTRUCTION_TYPE_LABELS, SETTLEMENT_ORDER_STATUSES,
  SETTLEMENT_ORDER_STATUS_LABELS, describeSettlementFailure, formatAmount, formatPercentRate,
  formatUtcDateTime, isValidDecimalId,
  type SettlementAdminClient, type SettlementInstructionView, type SettlementOrderView,
  type SettlementPage,
} from '../api/settlementAdmin';
import { RequestFailure } from '../request';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, FactList, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

// 结算单与分账/回退指令台账（Contract 80 §4.3 / Contract 78）：
// - 只读查询需 settlement.report.read；
// - 恢复 ON_HOLD / 重试 SPLIT_FAILED / 重试失败分账回退 REVERSE_FAILED 需 settlement.instruction.retry，
//   全部经 X-Request-Id 幂等提交。
// - 页面仅展示后端结算单与渠道指令事实，不推导任何订单展示状态（ARCH-005）。

const PAGE_SIZE = 20;

type ActionModalState =
  | { kind: 'resume'; order: SettlementOrderView }
  | { kind: 'retry'; order: SettlementOrderView }
  | { kind: 'retry-fallback'; order: SettlementOrderView };

export default function SettlementOrderListPage({ client, canRetry, onAuthLost }: {
  client: SettlementAdminClient;
  canRetry: boolean;
  onAuthLost: () => void;
}) {
  const [merchantIdFilter, setMerchantIdFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [result, setResult] = useState<SettlementPage<SettlementOrderView>>();
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  // 详情与指令抽屉
  const [selectedOrder, setSelectedOrder] = useState<SettlementOrderView | null>(null);
  const [instructions, setInstructions] = useState<SettlementInstructionView[]>([]);
  const [drawerLoading, setDrawerLoading] = useState(false);

  // 异常处置弹窗（恢复 / 重试分账 / 重试回退）
  const [actionModal, setActionModal] = useState<ActionModalState | null>(null);
  const [actionReason, setActionReason] = useState('');
  const [refundOrderIdInput, setRefundOrderIdInput] = useState('');
  const [modalError, setModalError] = useState('');
  const actionRequestIdRef = useRef(crypto.randomUUID());

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
      const data = await client.listOrders({
        merchantId: nextMerchantId.trim() || undefined,
        status: nextStatus || undefined,
        page,
        size: PAGE_SIZE,
      });
      if (call !== sequence.current) return;
      setResult(data);
    } catch (caught) {
      if (call !== sequence.current) return;
      handleError(caught, '读取结算单台账失败');
    } finally {
      if (call === sequence.current) setLoading(false);
    }
  }

  async function openDrawer(settlementOrderId: string) {
    setDrawerLoading(true);
    setError('');
    try {
      const [orderView, instructionList] = await Promise.all([
        client.getOrder(settlementOrderId),
        client.listOrderInstructions(settlementOrderId),
      ]);
      setSelectedOrder(orderView);
      setInstructions(instructionList);
    } catch (caught) {
      handleError(caught, '读取结算单详情或指令列表失败');
    } finally {
      setDrawerLoading(false);
    }
  }

  function openAction(kind: ActionModalState['kind'], order: SettlementOrderView, prefillRefundOrderId = '') {
    setActionModal({ kind, order });
    setActionReason('');
    setRefundOrderIdInput(prefillRefundOrderId);
    setModalError('');
    actionRequestIdRef.current = crypto.randomUUID();
  }

  async function submitAction() {
    if (!actionModal || !canRetry || busy) return;
    if (actionReason.trim().length > 256) {
      setModalError('处理原因不能超过 256 字。');
      return;
    }
    if (actionModal.kind === 'retry-fallback' && !isValidDecimalId(refundOrderIdInput)) {
      setModalError('请填写有效的退款单 ID（正整数 Snowflake ID）。');
      return;
    }
    setBusy(true);
    setModalError('');
    try {
      let updated: SettlementOrderView;
      if (actionModal.kind === 'resume') {
        updated = await client.resumeOrder(actionModal.order.settlementOrderId, actionReason, actionRequestIdRef.current);
        setNotice(`结算单 ${updated.settlementOrderId} 已恢复（当前状态：${SETTLEMENT_ORDER_STATUS_LABELS[updated.status] ?? updated.status}）。`);
      } else if (actionModal.kind === 'retry') {
        updated = await client.retryOrder(actionModal.order.settlementOrderId, actionReason, actionRequestIdRef.current);
        setNotice(`结算单 ${updated.settlementOrderId} 已重新发起分账（当前状态：${SETTLEMENT_ORDER_STATUS_LABELS[updated.status] ?? updated.status}）。`);
      } else {
        updated = await client.retryFallback(
          actionModal.order.settlementOrderId,
          refundOrderIdInput,
          actionReason,
          actionRequestIdRef.current,
        );
        setNotice(`结算单 ${updated.settlementOrderId} 已重新发起分账回退（当前状态：${SETTLEMENT_ORDER_STATUS_LABELS[updated.status] ?? updated.status}）。`);
      }
      setActionModal(null);
      await load(result?.page ?? 1);
      if (selectedOrder && selectedOrder.settlementOrderId === updated.settlementOrderId) {
        await openDrawer(updated.settlementOrderId);
      }
    } catch (caught) {
      if (caught instanceof RequestFailure && caught.code === 'IDEMPOTENCY_KEY_CONFLICT') {
        actionRequestIdRef.current = crypto.randomUUID();
      }
      handleError(caught, '执行结算异常处理失败', setModalError);
    } finally {
      setBusy(false);
    }
  }

  const latestFailedFallbackRefundId = instructions.find(
    item => item.instructionType === 'FALLBACK' && item.status === 'FAILED' && item.refundOrderId,
  )?.refundOrderId ?? '';

  const columns: TableColumnsType<SettlementOrderView> = [
    { title: '结算单 ID', dataIndex: 'settlementOrderId', render: value => <code>{value}</code> },
    {
      title: '业务单 / 订单',
      render: (_, row) => (
        <>
          <div>{row.bizType}: <code>{row.bizNo}</code></div>
          {row.orderId && <small>订单 {row.orderId}</small>}
        </>
      ),
    },
    { title: '商家 ID', dataIndex: 'merchantId', render: value => <code>{value}</code> },
    {
      title: '实付 / 已退',
      render: (_, row) => (
        <>
          <div>{formatAmount(row.paidAmount)}</div>
          <small>已退 {formatAmount(row.refundedAmount)}</small>
        </>
      ),
    },
    {
      title: '分账基数 / 费率',
      render: (_, row) => (
        <>
          <div>{formatAmount(row.splitBase)}</div>
          <small>费率 {formatPercentRate(row.feeRateSnapshot)}</small>
        </>
      ),
    },
    { title: '商家份额', render: (_, row) => formatAmount(row.merchantShare) },
    { title: '实分金额', render: (_, row) => formatAmount(row.splitAmount) },
    { title: '平台补贴应付', render: (_, row) => formatAmount(row.platformSubsidyDue) },
    { title: '已回退额', render: (_, row) => formatAmount(row.reversedAmount) },
    { title: '到期分账时间', render: (_, row) => formatUtcDateTime(row.eligibleAt) },
    {
      title: '结算状态',
      render: (_, row) => (
        <Space direction="vertical" size={2}>
          <StatusTag label={SETTLEMENT_ORDER_STATUS_LABELS[row.status] ?? row.status} status={row.status} />
          {row.holdReason && <small>暂停原因：{row.holdReason}</small>}
        </Space>
      ),
    },
    {
      title: '操作',
      render: (_, row) => (
        <Space size="small" wrap>
          <Button size="small" onClick={() => void openDrawer(row.settlementOrderId)}>明细与指令</Button>
          {canRetry && row.status === 'ON_HOLD' && (
            <Button size="small" type="primary" disabled={busy} onClick={() => openAction('resume', row)}>恢复分账</Button>
          )}
          {canRetry && row.status === 'SPLIT_FAILED' && (
            <Button size="small" type="primary" disabled={busy} onClick={() => openAction('retry', row)}>重试分账</Button>
          )}
          {canRetry && row.status === 'REVERSE_FAILED' && (
            <Button size="small" danger disabled={busy} onClick={() => openAction('retry-fallback', row)}>重试回退</Button>
          )}
        </Space>
      ),
    },
  ];

  const instructionColumns: TableColumnsType<SettlementInstructionView> = [
    { title: '指令 ID', dataIndex: 'instructionId', render: value => <code>{value}</code> },
    { title: '类型 / 轮次', render: (_, item) => `${INSTRUCTION_TYPE_LABELS[item.instructionType] ?? item.instructionType} #${item.attemptNo}` },
    {
      title: '分账流水号',
      render: (_, item) => (
        <>
          <div>商户单号：<code>{item.outSeparateNo}</code></div>
          {item.separateNo && <div>渠道单号：<code>{item.separateNo}</code></div>}
        </>
      ),
    },
    { title: '关联退款单', render: (_, item) => item.refundOrderId ? <code>{item.refundOrderId}</code> : '—' },
    {
      title: '指令金额（总 / 商 / 平台）',
      render: (_, item) => `${formatAmount(item.totalAmount)} / ${formatAmount(item.merchantAmount)} / ${formatAmount(item.platformAmount)}`,
    },
    {
      title: '实分 / 渠道费',
      render: (_, item) => `${formatAmount(item.actualMerchantAmount)} / ${formatAmount(item.totalFeeAmount)}`,
    },
    {
      title: '状态',
      render: (_, item) => (
        <Space direction="vertical" size={2}>
          <StatusTag label={INSTRUCTION_STATUS_LABELS[item.status] ?? item.status} status={item.status} />
          {(item.channelFinalStatus || item.channelStatus) && (
            <small>渠道：{item.channelFinalStatus ?? item.channelStatus}</small>
          )}
        </Space>
      ),
    },
    { title: '渠道结果说明', render: (_, item) => item.accResultDesc ?? item.fallbackReason ?? '—' },
    { title: '发送 / 终态时间', render: (_, item) => <>{formatUtcDateTime(item.sentAt)}<br />{formatUtcDateTime(item.terminalAt)}</> },
  ];

  return <PageContainer
    title="结算单与分账指令台账"
    hint="查看订单 T+d 结算单、实分金额、平台补贴应付及拉卡拉 1293 分账 / 2395 回退指令流水。仅授予 settlement.instruction.retry 权限时可恢复 ON_HOLD 结算单、重试失败分账或重试失败分账回退。">
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
        结算状态
        <Select
          style={{ width: 180 }}
          value={statusFilter}
          onChange={value => {
            setStatusFilter(value);
            void load(1, merchantIdFilter, value);
          }}
          options={[
            { value: '', label: '全部状态' },
            ...SETTLEMENT_ORDER_STATUSES.map(code => ({ value: code, label: SETTLEMENT_ORDER_STATUS_LABELS[code] ?? code })),
          ]}
        />
      </label>
      <Button type="primary" htmlType="submit" loading={loading}>查询</Button>
    </form>

    {error && <ErrorAlert>{error}</ErrorAlert>}
    {notice && <InlineStatus>{notice}</InlineStatus>}
    {loading && <InlineStatus>正在加载结算单列表</InlineStatus>}

    {result && (
      <DataTable
        columns={columns}
        rows={result.items}
        rowKey={row => row.settlementOrderId}
        loading={loading}
        page={result.page}
        pageSize={result.size || PAGE_SIZE}
        total={result.total}
        onPageChange={page => void load(page)}
      />
    )}

    <Drawer
      title={selectedOrder ? `结算单详情与指令 · ${selectedOrder.settlementOrderId}` : '结算单详情与指令'}
      open={selectedOrder !== null || drawerLoading}
      width={920}
      onClose={() => { setSelectedOrder(null); setInstructions([]); }}
      extra={selectedOrder && canRetry && (
        <Space>
          {selectedOrder.status === 'ON_HOLD' && (
            <Button type="primary" disabled={busy} onClick={() => openAction('resume', selectedOrder)}>恢复分账</Button>
          )}
          {selectedOrder.status === 'SPLIT_FAILED' && (
            <Button type="primary" disabled={busy} onClick={() => openAction('retry', selectedOrder)}>重试分账</Button>
          )}
          {selectedOrder.status === 'REVERSE_FAILED' && (
            <Button danger disabled={busy} onClick={() => openAction('retry-fallback', selectedOrder, latestFailedFallbackRefundId)}>重试回退</Button>
          )}
        </Space>
      )}>
      {drawerLoading && <InlineStatus>正在加载结算单与渠道指令</InlineStatus>}
      {selectedOrder && <>
        {(selectedOrder.holdReason || selectedOrder.lastErrorMessage) && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message={selectedOrder.holdReason
              ? `暂停原因：${selectedOrder.holdReason}`
              : `最近错误 [${selectedOrder.lastErrorCode ?? 'ERROR'}]：${selectedOrder.lastErrorMessage}`}
          />
        )}
        <FactList column={2} items={[
          { label: '结算单 ID', value: <code>{selectedOrder.settlementOrderId}</code> },
          {
            label: '当前状态',
            value: <StatusTag label={SETTLEMENT_ORDER_STATUS_LABELS[selectedOrder.status] ?? selectedOrder.status} status={selectedOrder.status} />,
          },
          { label: '支付单 ID', value: <code>{selectedOrder.paymentId}</code> },
          { label: '订单 ID / 业务单号', value: `${selectedOrder.orderId ?? '—'} / ${selectedOrder.bizNo}` },
          { label: '商家 ID / 门店 ID', value: `${selectedOrder.merchantId} / ${selectedOrder.storeId ?? '—'}` },
          { label: 'T+d 快照 / 到期时间', value: `${selectedOrder.settleDaysSnapshot !== null ? `T+${selectedOrder.settleDaysSnapshot}` : '未触发'} · ${formatUtcDateTime(selectedOrder.eligibleAt)}` },
          { label: '原价 / 闪价优惠', value: `${formatAmount(selectedOrder.originalPrice)} / ${formatAmount(selectedOrder.flashDiscountAmount)}` },
          { label: '平台券优惠 / 商家优惠', value: `${formatAmount(selectedOrder.platformSubsidyAmount)} / ${formatAmount(selectedOrder.merchantDiscountAmount)}` },
          { label: '用户实付 / 累计已退', value: `${formatAmount(selectedOrder.paidAmount)} / ${formatAmount(selectedOrder.refundedAmount)}` },
          { label: '分账基数 / 对外费率快照', value: `${formatAmount(selectedOrder.splitBase)} / ${formatPercentRate(selectedOrder.feeRateSnapshot)}` },
          { label: '商家应得份额', value: formatAmount(selectedOrder.merchantShare) },
          { label: '渠道实分金额 / 平台留存', value: `${formatAmount(selectedOrder.splitAmount)} / ${formatAmount(selectedOrder.platformRetainedAmount)}` },
          { label: '平台补贴应付（系统外结算）', value: formatAmount(selectedOrder.platformSubsidyDue) },
          { label: '渠道实到商家 / 渠道分账费', value: `${formatAmount(selectedOrder.actualMerchantAmount)} / ${formatAmount(selectedOrder.channelSplitFee)}` },
          { label: '累计已回退金额', value: formatAmount(selectedOrder.reversedAmount) },
          { label: '核销完成时间', value: formatUtcDateTime(selectedOrder.verifiedAt) },
          { label: '创建时间', value: formatUtcDateTime(selectedOrder.createdAt) },
          { label: '更新时间', value: formatUtcDateTime(selectedOrder.updatedAt) },
        ]} />

        <h3 style={{ marginTop: 20 }}>分账与回退指令列表（{instructions.length} 条）</h3>
        <Table<SettlementInstructionView>
          size="small"
          rowKey={item => item.instructionId}
          columns={instructionColumns}
          dataSource={instructions}
          pagination={false}
          scroll={{ x: 'max-content' }}
        />
      </>}
    </Drawer>

    <Modal
      title={actionModal?.kind === 'resume'
        ? `恢复暂停结算单 · ${actionModal.order.settlementOrderId}`
        : actionModal?.kind === 'retry'
          ? `重试失败分账 · ${actionModal.order.settlementOrderId}`
          : actionModal
            ? `重试失败分账回退 · ${actionModal.order.settlementOrderId}`
            : ''}
      open={actionModal !== null}
      okText="确认提交"
      cancelText="取消"
      confirmLoading={busy}
      onCancel={() => { setActionModal(null); setModalError(''); }}
      onOk={() => void submitAction()}>
      {actionModal && <>
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message={actionModal.kind === 'resume'
            ? '确认阻塞条件（如接收方未生效、在途退款/售后等）已解除后，可将 ON_HOLD 结算单恢复至 WAITING 重新排队分账。'
            : actionModal.kind === 'retry'
              ? '仅当当前无在途或已成功分账指令时可重试 SPLIT_FAILED 结算单，重试请求携带唯一 X-Request-Id 幂等保护。'
              : '商家补足拉卡拉账户余额后，可针对指定退款单重新发起 2395 分账回退（180 天回退窗口内有效，平台不垫付）。'}
        />
        {modalError && <ErrorAlert>{modalError}</ErrorAlert>}
        {actionModal.kind === 'retry-fallback' && (
          <>
            <label className="field-label" htmlFor="settlement-retry-refund-id">退款单 ID（必填，Snowflake ID）</label>
            <Input
              id="settlement-retry-refund-id"
              value={refundOrderIdInput}
              onChange={event => {
                setRefundOrderIdInput(event.target.value);
                actionRequestIdRef.current = crypto.randomUUID();
                setModalError('');
              }}
              placeholder="输入对应退款单 ID"
              inputMode="numeric"
            />
          </>
        )}
        <label className="field-label" htmlFor="settlement-action-reason">处理原因（选填，≤256 字）</label>
        <Input.TextArea
          id="settlement-action-reason"
          rows={3}
          maxLength={256}
          showCount
          value={actionReason}
          onChange={event => {
            setActionReason(event.target.value);
            actionRequestIdRef.current = crypto.randomUUID();
            setModalError('');
          }}
          placeholder="填写人工核查结论与操作依据"
        />
      </>}
    </Modal>
  </PageContainer>;
}
