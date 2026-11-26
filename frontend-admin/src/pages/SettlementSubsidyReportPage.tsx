import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Col, Input, Row, Statistic } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  describeSettlementFailure, formatAmount,
  type SettlementAdminClient, type SubsidyReportItem, type SubsidyReportResponse,
} from '../api/settlementAdmin';
import { RequestFailure } from '../request';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

// 平台补贴应付报表（Contract 80 §4.5 / 40 号 §3.2 / 22 号裁决）：
// 严格只读汇总报表！平台券导致商家份额超过渠道可分账金额的差额在系统外结算，
// 本页严禁提供任何「标记已打款 / 已结算」按钮或线下入账写动作。

const PAGE_SIZE = 20;

export default function SettlementSubsidyReportPage({ client, onAuthLost }: {
  client: SettlementAdminClient;
  onAuthLost: () => void;
}) {
  const [merchantIdFilter, setMerchantIdFilter] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [report, setReport] = useState<SubsidyReportResponse>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const sequence = useRef(0);

  useEffect(() => {
    void load(1);
    return () => { sequence.current++; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function load(page: number) {
    if (fromDate && toDate && fromDate > toDate) {
      setError('开始日期不能晚于结束日期。');
      return;
    }
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await client.getSubsidyReport({
        merchantId: merchantIdFilter.trim() || undefined,
        fromDate: fromDate || undefined,
        toDate: toDate || undefined,
        page,
        size: PAGE_SIZE,
      });
      if (call !== sequence.current) return;
      setReport(data);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED') {
        onAuthLost();
        return;
      }
      setError(describeSettlementFailure(caught, '读取平台补贴应付报表失败'));
    } finally {
      if (call === sequence.current) setLoading(false);
    }
  }

  const columns: TableColumnsType<SubsidyReportItem> = [
    { title: '商家 ID', dataIndex: 'merchantId', render: value => <code>{value}</code> },
    { title: '统计周期', dataIndex: 'period' },
    { title: '涉及订单数', dataIndex: 'orderCount' },
    { title: '用户实付总额', render: (_, row) => formatAmount(row.totalPaidAmount) },
    { title: '商家应得份额', render: (_, row) => formatAmount(row.totalMerchantShare) },
    { title: '渠道实分金额', render: (_, row) => formatAmount(row.totalSplitAmount) },
    { title: '累计已回退额', render: (_, row) => formatAmount(row.totalReversedAmount) },
    { title: '平台补贴应付差额', render: (_, row) => <strong>{formatAmount(row.totalSubsidyDue)}</strong> },
  ];

  return <PageContainer
    title="平台补贴应付报表"
    hint="按商家与账期统计因平台优惠券产生的分账差额（平台补贴应付 = 商家应得份额 − 渠道实分金额），仅运营后台可见。">
    <Alert
      type="info"
      showIcon
      style={{ marginBottom: 16 }}
      message="系统外结算只读声明（40 号 §3.2 / 22 号裁决）"
      description="本报表仅用于按商家与周期核对平台优惠券产生的应付补贴差额，相关差额在线下财务流程（系统外）结算。依据 22 号与 40 号裁决，系统严禁提供“标记已打款 / 标记已结算”或任何手工资金入账按钮。"
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
        起始日期
        <input type="date" value={fromDate} onChange={event => setFromDate(event.target.value)} />
      </label>
      <label className="filter-item">
        截止日期
        <input type="date" value={toDate} onChange={event => setToDate(event.target.value)} />
      </label>
      <Button type="primary" htmlType="submit" loading={loading}>查询</Button>
      <Button onClick={() => { setMerchantIdFilter(''); setFromDate(''); setToDate(''); }}>重置条件</Button>
    </form>

    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在加载平台补贴应付报表</InlineStatus>}

    {report && <>
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small">
            <Statistic title="涉及订单数" value={report.summary.totalOrderCount} suffix="笔" />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small">
            <Statistic title="商家应得份额汇总" value={report.summary.totalMerchantShare} prefix="¥" />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small">
            <Statistic title="渠道已分账汇总" value={report.summary.totalSplitAmount} prefix="¥" />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small">
            <Statistic title="平台补贴应付汇总（系统外结算）" value={report.summary.totalSubsidyDue} prefix="¥" />
          </Card>
        </Col>
      </Row>

      <DataTable
        columns={columns}
        rows={report.items}
        rowKey={row => `${row.merchantId}-${row.period}`}
        loading={loading}
        page={report.page}
        pageSize={report.size || PAGE_SIZE}
        total={report.total}
        onPageChange={page => void load(page)}
      />
    </>}
  </PageContainer>;
}
