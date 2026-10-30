import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Button, Checkbox, Input, Select } from 'antd';
import type { TableColumnsType } from 'antd';
import { ABNORMAL_REASONS, TradeQueryFailure, displayStatusLabel, field, type AbnormalReason, type OrderDetail, type Page, type StatusLog, type TradeQueryClient } from '../api/tradeQuery';
import { DataTable } from '../ui/DataTable';
import { BackLink, PageContainer } from '../ui/PageContainer';
import { ErrorAlert, FactList, InlineStatus, Section } from '../ui/bits';

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
        if (caught instanceof TradeQueryFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
        if (caught instanceof TradeQueryFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
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
      if (caught instanceof TradeQueryFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof TradeQueryFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
      setActionError(caught instanceof TradeQueryFailure ? `操作失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '操作失败，请重试。');
    } finally { setBusy(false); }
  }
  const canClose = detail !== undefined && CLOSEABLE_STAGES.includes(field(detail, 'orderStage'))
    && field(detail, 'paymentStatus') === 'PAID' && detail.refundOrderId === null;
  const reasonOk = closeReason.trim().length >= 10 && closeReason.length <= 500;
  const disableOk = disableReason.trim().length >= 10 && disableReason.length <= 500;
  const rescheduleColumns: TableColumnsType<OrderDetail['rescheduleRecords'][number]> = [
    { title: '改期编号', dataIndex: 'rescheduleId' },
    { title: '原预约（UTC）', render: (_, record) => `${record.oldStart} ~ ${record.oldEnd}` },
    { title: '新预约（UTC）', render: (_, record) => `${record.newStart} ~ ${record.newEnd}` },
    { title: '改期时间（UTC）', dataIndex: 'rescheduledAt' },
  ];
  const logColumns: TableColumnsType<StatusLog> = [
    { title: '时间（UTC）', dataIndex: 'createdAt' },
    { title: '维度', dataIndex: 'dimension' },
    { title: '原状态', render: (_, log) => log.fromStatus ?? '—' },
    { title: '新状态', dataIndex: 'toStatus' },
    { title: '事件', dataIndex: 'eventType' },
    { title: '操作方', render: (_, log) => `${log.operatorType}${log.operatorId ? `/${log.operatorId}` : ''}` },
    { title: '备注', render: (_, log) => log.remark ?? '' },
  ];
  return <PageContainer
    title="订单详情"
    back={<BackLink to="/trade/orders">返回订单总览</BackLink>}>
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在加载订单详情</InlineStatus>}
    {notice && <InlineStatus>{notice}</InlineStatus>}
    {actionError && <ErrorAlert>{actionError}</ErrorAlert>}
    {detail && <>
      <Section title="履约层">
        <FactList items={[
          { label: '订单号', value: detail.orderNo },
          { label: '订单编号', value: detail.orderId },
          { label: '用户编号', value: detail.userId },
          { label: '展示状态', value: displayStatusLabel(field(detail, 'displayStatus')) },
          { label: '业务阶段', value: field(detail, 'orderStage') },
          { label: '支付状态', value: field(detail, 'paymentStatus') === 'PAID' ? '已支付' : '未支付' },
          { label: '预约时间（UTC）', value: `${detail.appointmentStart} ~ ${detail.appointmentEnd}` },
          { label: '改期次数', value: detail.rescheduleCount },
          { label: '创建（UTC）', value: detail.createdAt },
          ...(detail.paidAt ? [{ label: '支付（UTC）', value: detail.paidAt }] : []),
          ...(detail.confirmedAt ? [{ label: '确认（UTC）', value: detail.confirmedAt }] : []),
          ...(detail.verifiedAt ? [{ label: '核销（UTC）', value: detail.verifiedAt }] : []),
          ...(detail.canceledAt ? [{ label: '关闭（UTC）', value: detail.canceledAt }] : []),
        ]} />
      </Section>
      <Section title="服务快照">
        <FactList items={[
          { label: '服务名称', value: detail.serviceSnapshot.serviceName },
          { label: '类目', value: detail.serviceSnapshot.categoryName ?? '—' },
          { label: '商家', value: detail.serviceSnapshot.merchantName },
          { label: '门店', value: detail.serviceSnapshot.storeName },
          { label: '门店地址', value: detail.serviceSnapshot.storeAddress },
          { label: '服务价格', value: detail.serviceSnapshot.servicePrice },
          { label: '时长（分钟）', value: detail.serviceSnapshot.serviceDurationMinutes },
        ]} />
      </Section>
      <Section title="金额（只读）">
        <FactList items={[
          { label: '商品金额', value: detail.originalAmount },
          { label: '优惠金额', value: detail.discountAmount },
          { label: '实付金额', value: detail.payAmount },
          { label: '已退金额', value: detail.refundedAmount },
        ]} />
      </Section>
      <Section title="退款层">
        <p>退款单：<strong>{detail.refundOrderId ?? '无'}</strong>
          {field(detail, 'refundStatus') ? ` · ${field(detail, 'refundStatus')}` : ''}
          {detail.refundOrderId && <> · <Link to={`/trade/refunds?orderId=${detail.orderId}`}>查看该订单退款单</Link></>}
          {detail.refundOrderId && <> · <Link to={`/trade/refunds/${detail.refundOrderId}`}>退款单详情</Link></>}
        </p>
        <p>售后状态：{field(detail, 'aftersaleStatus') ?? '无'} · 申请状态：{field(detail, 'refundApplicationStatus') ?? '无'}</p>
      </Section>
      <Section title="核销">
        <p>核销状态：{field(detail, 'verificationStatus')} · <Link to={`/trade/verifications?orderId=${detail.orderId}`}>查看核销记录</Link></p>
      </Section>
      <Section title="异常关单（order.abnormal.close）">
        <p className="hint">固定语义：异常关闭 + 全额退款（按渠道实付原路退回）；不是正常运营代接单。
          仅待确认/待服务且已支付未核销、无在途售后与退款申请的订单可关闭；有在途售后时本动作拒绝而非使其失效。</p>
        {!canClose && <p className="hint">当前订单状态不满足异常关单条件，按钮停用不代表后端放行。</p>}
        <label className="filter-item">异常原因
          <Select style={{ width: 320 }} value={reasonCode} onChange={value => setReasonCode(value)} disabled={!canClose || busy}
            options={ABNORMAL_REASONS.map(code => ({ value: code, label: `${REASON_LABELS[code]}（${code}）` }))} />
        </label>
        <label className="filter-item">处置说明（必填，10–500 字）
          <Input.TextArea style={{ width: 'min(100%, 720px)' }} rows={3} value={closeReason} onChange={event => setCloseReason(event.target.value)} disabled={!canClose || busy} />
        </label>
        <div><Checkbox checked={closeConfirmed} onChange={event => setCloseConfirmed(event.target.checked)} disabled={!canClose || busy}>我确认对该订单执行异常关闭并全额退款（同一人二次确认，非第二人审批）</Checkbox></div>
        <div className="decision-actions">
          <Button danger type="primary" disabled={!canClose || busy || !reasonOk || !closeConfirmed}
            onClick={() => void run(async () => {
              const receipt = await client.abnormalCloseOrder(orderId, reasonCode, closeReason, crypto.randomUUID());
              setCloseReason(''); setCloseConfirmed(false);
              return `已异常关闭：订单 ${receipt.orderId} → CANCELED，退款单 ${receipt.refundOrderId}`;
            })}>异常关闭并全额退款</Button>
        </div>
      </Section>
      <Section title="商家下线（merchant.disable）">
        <p className="hint">对订单所属商家 {detail.merchantId}（{detail.serviceSnapshot.merchantName}）执行立即下线：停止新订单，
          存量订单继续履约、存量退款/售后继续处理；有在途订单不拒绝本动作。下线后 C 端不再展示。</p>
        <label className="filter-item">下线原因（必填，10–500 字）
          <Input.TextArea style={{ width: 'min(100%, 720px)' }} rows={3} value={disableReason} onChange={event => setDisableReason(event.target.value)} disabled={busy} />
        </label>
        <div><Checkbox checked={disableConfirmed} onChange={event => setDisableConfirmed(event.target.checked)} disabled={busy}>我确认对该商家执行下线（同一人二次确认，非第二人审批）</Checkbox></div>
        <div className="decision-actions">
          <Button danger type="primary" disabled={busy || !disableOk || !disableConfirmed}
            onClick={() => void run(async () => {
              const receipt = await client.disableMerchant(detail.merchantId, disableReason, crypto.randomUUID());
              setDisableReason(''); setDisableConfirmed(false);
              return `已下线：商家 ${receipt.merchantId} → OFFLINE`;
            })}>下线该商家</Button>
        </div>
      </Section>
      <Section title="改期记录">
        {detail.rescheduleRecords.length === 0
          ? <p>该订单没有改期记录。</p>
          : <DataTable columns={rescheduleColumns} rows={detail.rescheduleRecords} rowKey={record => record.rescheduleId} />}
      </Section>
      <Section title="状态时间轴">
        {logs && <DataTable columns={logColumns} rows={logs.items} rowKey={log => log.logId} />}
      </Section>
    </>}
  </PageContainer>;
}
