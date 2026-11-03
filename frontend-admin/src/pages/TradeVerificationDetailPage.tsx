import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import type { TableColumnsType } from 'antd';
import { TradeQueryFailure, type TradeQueryClient, type VerificationDetail } from '../api/tradeQuery';
import { DataTable } from '../ui/DataTable';
import { BackLink, PageContainer } from '../ui/PageContainer';
import { ErrorAlert, FactList, InlineStatus, Section } from '../ui/bits';

const attemptColumns: TableColumnsType<VerificationDetail['attempts'][number]> = [
  { title: '尝试编号', dataIndex: 'attemptId' },
  { title: '结果', dataIndex: 'result' },
  { title: '失败原因', render: (_, attempt) => attempt.failReason ?? '—' },
  { title: '时间（UTC）', dataIndex: 'attemptedAt' },
];
const riskColumns: TableColumnsType<VerificationDetail['riskAttempts'][number]> = [
  { title: '风控尝试编号', dataIndex: 'riskAttemptId' },
  { title: '结果码', dataIndex: 'resultCode' },
  { title: '计入失败', render: (_, risk) => risk.countsFailure ? '是' : '否' },
  { title: '时间（UTC）', dataIndex: 'attemptedAt' },
];

/** 核销记录详情：尝试链与风控锁定事实（争议溯源）；凭证密文/查找码不展示。 */
export default function TradeVerificationDetailPage({ client, onAuthLost }: { client: TradeQueryClient; onAuthLost: () => void }) {
  const params = useParams();
  const verificationId = params.verificationId ?? '';
  const [detail, setDetail] = useState<VerificationDetail>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const sequence = useRef(0);
  useEffect(() => {
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    client.getVerification(verificationId)
      .then(data => { if (call === sequence.current) setDetail(data); })
      .catch((caught: unknown) => {
        if (call !== sequence.current) return;
        if (caught instanceof TradeQueryFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
        if (caught instanceof TradeQueryFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
        setError(caught instanceof TradeQueryFailure ? `核销详情加载失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '核销详情加载失败，请重试。');
      })
      .finally(() => { if (call === sequence.current) setLoading(false); });
    return () => { sequence.current++; };
  }, [verificationId]);
  return <PageContainer
    title="核销记录详情"
    back={<BackLink to="/trade/verifications">返回平台核销记录</BackLink>}>
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在加载核销详情</InlineStatus>}
    {detail && <>
      <FactList items={[
        { label: '核销流水号', value: detail.verificationNo },
        { label: '记录编号', value: detail.verificationId },
        { label: '订单编号', value: <>{detail.orderId} · <Link to={`/trade/orders/${detail.orderId}`}>订单详情</Link></> },
        { label: '门店编号', value: detail.storeId },
        { label: '核销方式', value: detail.verifyMethod === 'SCAN' ? '扫码' : '手动输入' },
        { label: '核销时间（UTC）', value: detail.verifiedAt },
        { label: '核销操作人（员工编号）', value: detail.operatorStaffId },
      ]} />
      <Section title="尝试链（争议溯源）">
        {detail.attempts.length === 0
          ? <p>无尝试记录。</p>
          : <DataTable columns={attemptColumns} rows={detail.attempts} rowKey={attempt => attempt.attemptId} />}
      </Section>
      <Section title="风控尝试与锁定">
        {detail.riskAttempts.length === 0
          ? <p>无风控尝试记录。</p>
          : <DataTable columns={riskColumns} rows={detail.riskAttempts} rowKey={risk => risk.riskAttemptId} />}
      </Section>
      <p className="hint">核销凭证密文与查找码按 47/48 号契约留存在核销域，不跨域回传。</p>
    </>}
  </PageContainer>;
}
