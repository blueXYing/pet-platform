import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Input, Select } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  WITHDRAWAL_STATUSES, WITHDRAWAL_STATUS_LABELS, describeSettlementFailure,
  formatAmount, formatUtcDateTime,
  type SettlementAdminClient, type SettlementPage, type WithdrawalOrderView,
} from '../api/settlementAdmin';
import { RequestFailure } from '../request';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

// 商家提现台账（Contract 80 §4.4 / 22 号裁决 / 40 号 §5.5）：
// 严格只读台账，仅需 settlement.report.read。严禁任何「代商家提现」或手工打款按钮。

const PAGE_SIZE = 20;

export default function SettlementWithdrawalListPage({ client, onAuthLost }: {
  client: SettlementAdminClient;
  onAuthLost: () => void;
}) {
  const [merchantIdFilter, setMerchantIdFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [result, setResult] = useState<SettlementPage<WithdrawalOrderView>>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const sequence = useRef(0);

  useEffect(() => {
    void load(1);
    return () => { sequence.current++; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function load(page: number, nextMerchantId = merchantIdFilter, nextStatus = statusFilter) {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.listWithdrawals({
        merchantId: nextMerchantId.trim() || undefined,
        status: nextStatus || undefined,
        page,
        size: PAGE_SIZE,
      });
      if (call !== sequence.current) return;
      setResult(data);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED') {
        onAuthLost();
        return;
      }
      setError(describeSettlementFailure(caught, '读取商家提现台账失败'));
    } finally {
      if (call === sequence.current) setLoading(false);
    }
  }

  const columns: TableColumnsType<WithdrawalOrderView> = [
    { title: '提现单 ID', dataIndex: 'withdrawalId', render: value => <code>{value}</code> },
    { title: '商户提现单号', dataIndex: 'merOrderNo', render: value => <code>{value}</code> },
    { title: '商家 ID', dataIndex: 'merchantId', render: value => <code>{value}</code> },
    { title: '接收方编号', dataIndex: 'receiverNo', render: value => <code>{value}</code> },
    { title: '提现金额', render: (_, row) => formatAmount(row.amount) },
    { title: '手续费', render: (_, row) => formatAmount(row.feeAmount) },
    { title: '实到金额', render: (_, row) => formatAmount(row.netAmount) },
    {
      title: '状态',
      render: (_, row) => (
        <StatusTag label={WITHDRAWAL_STATUS_LABELS[row.status] ?? row.status} status={row.status} />
      ),
    },
    { title: '渠道状态', render: (_, row) => row.channelState ?? '—' },
    { title: '拉卡拉流水号', render: (_, row) => row.drawJnl ? <code>{row.drawJnl}</code> : '—' },
    { title: '失败原因', render: (_, row) => row.failReason ?? '—' },
    { title: '发起人 (OWNER)', render: (_, row) => row.requestedByUserId ? <code>{row.requestedByUserId}</code> : '—' },
    { title: '发起时间', render: (_, row) => formatUtcDateTime(row.requestedAt ?? row.createdAt) },
    { title: '完成时间', render: (_, row) => formatUtcDateTime(row.finishedAt) },
  ];

  return <PageContainer
    title="商家提现台账"
    hint="只读查询商家主动提现记录（拉卡拉 D1 提现，40 号 §5.5 / 22 号裁决）。">
    <Alert
      type="info"
      showIcon
      style={{ marginBottom: 16 }}
      message="只读台账（22 号裁决）：商家提现仅限认证商家主账号（OWNER）在小程序商家端手动发起，运营端不提供代商家提现或手工标记到账功能。"
    />

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
        提现状态
        <Select
          style={{ width: 160 }}
          value={statusFilter}
          onChange={value => {
            setStatusFilter(value);
            void load(1, merchantIdFilter, value);
          }}
          options={[
            { value: '', label: '全部状态' },
            ...WITHDRAWAL_STATUSES.map(code => ({ value: code, label: WITHDRAWAL_STATUS_LABELS[code] ?? code })),
          ]}
        />
      </label>
      <Button type="primary" htmlType="submit" loading={loading}>查询</Button>
    </form>

    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在加载提现台账</InlineStatus>}

    {result && (
      <DataTable
        columns={columns}
        rows={result.items}
        rowKey={row => row.withdrawalId}
        loading={loading}
        page={result.page}
        pageSize={result.size || PAGE_SIZE}
        total={result.total}
        onPageChange={page => void load(page)}
      />
    )}
  </PageContainer>;
}
