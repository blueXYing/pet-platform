import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { TradeQueryFailure, displayStatusLabel, field, type OrderDetail, type Page, type StatusLog, type TradeQueryClient } from '../api/tradeQuery';

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><dt>{label}</dt><dd>{children}</dd></div>;
}

/** 运营 PRD 6.3.2 订单详情：履约层 + 退款层链接 + 核销链接 + 状态时间轴；结算层不在 V1 契约。 */
export default function TradeOrderDetailPage({ client, onAuthLost }: { client: TradeQueryClient; onAuthLost: () => void }) {
  const params = useParams();
  const orderId = params.orderId ?? '';
  const [detail, setDetail] = useState<OrderDetail>();
  const [logs, setLogs] = useState<Page<StatusLog>>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const sequence = useRef(0);
  useEffect(() => {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    (async () => {
      try {
        const data = await client.getOrder(orderId);
        const timeline = await client.listStatusLogs(orderId);
        if (call !== sequence.current) return;
        setDetail(data);
        setLogs(timeline);
      } catch (caught) {
        if (call !== sequence.current) return;
        if (caught instanceof TradeQueryFailure && ['UNAUTHENTICATED', 'FORBIDDEN'].includes(caught.code)) { onAuthLost(); return; }
        setError(caught instanceof TradeQueryFailure ? `订单详情加载失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '订单详情加载失败，请重试。');
      } finally { if (call === sequence.current) setLoading(false); }
    })();
    return () => { sequence.current++; };
  }, [orderId]);
  return <main className="aftersale-page">
    <p><Link to="/trade/orders">返回订单总览</Link></p>
    <h1>订单详情</h1>
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在加载订单详情</p>}
    {detail && <>
      <h2>履约层</h2>
      <dl className="snapshot">
        <Field label="订单号">{detail.orderNo}</Field>
        <Field label="订单编号">{detail.orderId}</Field>
        <Field label="用户编号">{detail.userId}</Field>
        <Field label="展示状态">{displayStatusLabel(field(detail, 'displayStatus'))}</Field>
        <Field label="业务阶段">{field(detail, 'orderStage')}</Field>
        <Field label="支付状态">{field(detail, 'paymentStatus') === 'PAID' ? '已支付' : '未支付'}</Field>
        <Field label="预约时间（UTC）">{detail.appointmentStart} ~ {detail.appointmentEnd}</Field>
        <Field label="改期次数">{detail.rescheduleCount}</Field>
        <Field label="创建（UTC）">{detail.createdAt}</Field>
        {detail.paidAt && <Field label="支付（UTC）">{detail.paidAt}</Field>}
        {detail.confirmedAt && <Field label="确认（UTC）">{detail.confirmedAt}</Field>}
        {detail.verifiedAt && <Field label="核销（UTC）">{detail.verifiedAt}</Field>}
        {detail.canceledAt && <Field label="关闭（UTC）">{detail.canceledAt}</Field>}
      </dl>
      <h2>服务快照</h2>
      <dl className="snapshot">
        <Field label="服务名称">{detail.serviceSnapshot.serviceName}</Field>
        <Field label="类目">{detail.serviceSnapshot.categoryName ?? '—'}</Field>
        <Field label="商家">{detail.serviceSnapshot.merchantName}</Field>
        <Field label="门店">{detail.serviceSnapshot.storeName}</Field>
        <Field label="门店地址">{detail.serviceSnapshot.storeAddress}</Field>
        <Field label="服务价格">{detail.serviceSnapshot.servicePrice}</Field>
        <Field label="时长（分钟）">{detail.serviceSnapshot.serviceDurationMinutes}</Field>
      </dl>
      <h2>金额（只读）</h2>
      <dl className="snapshot">
        <Field label="商品金额">{detail.originalAmount}</Field>
        <Field label="优惠金额">{detail.discountAmount}</Field>
        <Field label="实付金额">{detail.payAmount}</Field>
        <Field label="已退金额">{detail.refundedAmount}</Field>
      </dl>
      <h2>退款层</h2>
      <p>退款单：<strong>{detail.refundOrderId ?? '无'}</strong>
        {field(detail, 'refundStatus') ? ` · ${field(detail, 'refundStatus')}` : ''}
        {detail.refundOrderId && <> · <Link to={`/trade/refunds?orderId=${detail.orderId}`}>查看该订单退款单</Link></>}
        {detail.refundOrderId && <> · <Link to={`/trade/refunds/${detail.refundOrderId}`}>退款单详情</Link></>}
      </p>
      <p>售后状态：{field(detail, 'aftersaleStatus') ?? '无'} · 申请状态：{field(detail, 'refundApplicationStatus') ?? '无'}</p>
      <h2>核销</h2>
      <p>核销状态：{field(detail, 'verificationStatus')} · <Link to={`/trade/verifications?orderId=${detail.orderId}`}>查看核销记录</Link></p>
      <h2>改期记录</h2>
      {detail.rescheduleRecords.length === 0
        ? <p>该订单没有改期记录。</p>
        : <div className="table-scroll"><table>
            <thead><tr><th>改期编号</th><th>原预约（UTC）</th><th>新预约（UTC）</th><th>改期时间（UTC）</th></tr></thead>
            <tbody>{detail.rescheduleRecords.map(record => <tr key={record.rescheduleId}>
              <td>{record.rescheduleId}</td>
              <td>{record.oldStart} ~ {record.oldEnd}</td>
              <td>{record.newStart} ~ {record.newEnd}</td>
              <td>{record.rescheduledAt}</td>
            </tr>)}</tbody>
          </table></div>}
      <h2>状态时间轴</h2>
      {logs && <div className="table-scroll"><table>
        <thead><tr><th>时间（UTC）</th><th>维度</th><th>原状态</th><th>新状态</th><th>事件</th><th>操作方</th><th>备注</th></tr></thead>
        <tbody>{logs.items.map(log => <tr key={log.logId}>
          <td>{log.createdAt}</td>
          <td>{log.dimension}</td>
          <td>{log.fromStatus ?? '—'}</td>
          <td>{log.toStatus}</td>
          <td>{log.eventType}</td>
          <td>{log.operatorType}{log.operatorId ? `/${log.operatorId}` : ''}</td>
          <td>{log.remark ?? ''}</td>
        </tr>)}</tbody>
      </table></div>}
    </>}
  </main>;
}
