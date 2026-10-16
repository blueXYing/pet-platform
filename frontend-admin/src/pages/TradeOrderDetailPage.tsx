import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ABNORMAL_REASONS, TradeQueryFailure, displayStatusLabel, field, type AbnormalReason, type OrderDetail, type Page, type StatusLog, type TradeQueryClient } from '../api/tradeQuery';

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><dt>{label}</dt><dd>{children}</dd></div>;
}

const REASON_LABELS: Record<AbnormalReason, string> = {
  STORE_OFFLINE: '门店已下线',
  QUALIFICATION_INVALID: '资质失效',
  MERCHANT_UNREACHABLE: '商家失联',
  OTHER: '其他被授权的异常履约场景',
};

const CLOSEABLE_STAGES = ['PENDING_CONFIRM', 'PENDING_SERVICE'];

/** 运营 PRD 6.3.2 订单详情：履约层 + 退款层链接 + 核销链接 + 状态时间轴；结算层不在 V1 契约。
 *  63号 v0.2 处置（33号裁决）：异常关单 + 商家下线，同一人二次确认，后端动作码另行鉴权。 */
export default function TradeOrderDetailPage({ client, onAuthLost }: { client: TradeQueryClient; onAuthLost: () => void }) {
  const params = useParams();
  const orderId = params.orderId ?? '';
  const [detail, setDetail] = useState<OrderDetail>();
  const [logs, setLogs] = useState<Page<StatusLog>>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState('');
  const [actionError, setActionError] = useState('');
  const [reasonCode, setReasonCode] = useState<AbnormalReason>('MERCHANT_UNREACHABLE');
  const [closeReason, setCloseReason] = useState('');
  const [closeConfirmed, setCloseConfirmed] = useState(false);
  const [disableReason, setDisableReason] = useState('');
  const [disableConfirmed, setDisableConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
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
  async function run(action: () => Promise<string>) {
    setBusy(true);
    setActionError('');
    setNotice('');
    try {
      setNotice(await action());
      const call = ++sequence.current;
      const data = await client.getOrder(orderId);
      if (call === sequence.current) setDetail(data);
    } catch (caught) {
      if (caught instanceof TradeQueryFailure && ['UNAUTHENTICATED', 'FORBIDDEN'].includes(caught.code)) { onAuthLost(); return; }
      setActionError(caught instanceof TradeQueryFailure ? `操作失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '操作失败，请重试。');
    } finally { setBusy(false); }
  }
  const canClose = detail !== undefined && CLOSEABLE_STAGES.includes(field(detail, 'orderStage'))
    && field(detail, 'paymentStatus') === 'PAID' && detail.refundOrderId === null;
  const reasonOk = closeReason.trim().length >= 10 && closeReason.length <= 500;
  const disableOk = disableReason.trim().length >= 10 && disableReason.length <= 500;
  return <main className="aftersale-page">
    <p><Link to="/trade/orders">返回订单总览</Link></p>
    <h1>订单详情</h1>
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在加载订单详情</p>}
    {notice && <p role="status">{notice}</p>}
    {actionError && <p role="alert">{actionError}</p>}
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
      <section>
        <h2>异常关单（order.abnormal.close）</h2>
        <p className="hint">固定语义：异常关闭 + 全额退款（按渠道实付原路退回）；不是正常运营代接单。
          仅待确认/待服务且已支付未核销、无在途售后与退款申请的订单可关闭；有在途售后时本动作拒绝而非使其失效。</p>
        {!canClose && <p className="hint">当前订单状态不满足异常关单条件，按钮停用不代表后端放行。</p>}
        <label>异常原因
          <select value={reasonCode} onChange={event => setReasonCode(event.target.value as AbnormalReason)} disabled={!canClose || busy}>
            {ABNORMAL_REASONS.map(code => <option key={code} value={code}>{REASON_LABELS[code]}（{code}）</option>)}
          </select>
        </label>
        <label>处置说明（必填，10–500 字）
          <textarea value={closeReason} onChange={event => setCloseReason(event.target.value)} disabled={!canClose || busy} />
        </label>
        <label>
          <input type="checkbox" checked={closeConfirmed} onChange={event => setCloseConfirmed(event.target.checked)} disabled={!canClose || busy} />
          我确认对该订单执行异常关闭并全额退款（同一人二次确认，非第二人审批）
        </label>
        <div className="decision-actions">
          <button disabled={!canClose || busy || !reasonOk || !closeConfirmed}
            onClick={() => void run(async () => {
              const receipt = await client.abnormalCloseOrder(orderId, reasonCode, closeReason, crypto.randomUUID());
              setCloseReason(''); setCloseConfirmed(false);
              return `已异常关闭：订单 ${receipt.orderId} → CANCELED，退款单 ${receipt.refundOrderId}`;
            })}>异常关闭并全额退款</button>
        </div>
      </section>
      <section>
        <h2>商家下线（merchant.disable）</h2>
        <p className="hint">对订单所属商家 {detail.merchantId}（{detail.serviceSnapshot.merchantName}）执行立即下线：停止新订单，
          存量订单继续履约、存量退款/售后继续处理；有在途订单不拒绝本动作。下线后 C 端不再展示。</p>
        <label>下线原因（必填，10–500 字）
          <textarea value={disableReason} onChange={event => setDisableReason(event.target.value)} disabled={busy} />
        </label>
        <label>
          <input type="checkbox" checked={disableConfirmed} onChange={event => setDisableConfirmed(event.target.checked)} disabled={busy} />
          我确认对该商家执行下线（同一人二次确认，非第二人审批）
        </label>
        <div className="decision-actions">
          <button disabled={busy || !disableOk || !disableConfirmed}
            onClick={() => void run(async () => {
              const receipt = await client.disableMerchant(detail.merchantId, disableReason, crypto.randomUUID());
              setDisableReason(''); setDisableConfirmed(false);
              return `已下线：商家 ${receipt.merchantId} → OFFLINE`;
            })}>下线该商家</button>
        </div>
      </section>
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
