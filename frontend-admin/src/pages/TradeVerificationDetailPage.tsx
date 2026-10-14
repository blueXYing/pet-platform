import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { TradeQueryFailure, type TradeQueryClient, type VerificationDetail } from '../api/tradeQuery';

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><dt>{label}</dt><dd>{children}</dd></div>;
}

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
        if (caught instanceof TradeQueryFailure && ['UNAUTHENTICATED', 'FORBIDDEN'].includes(caught.code)) { onAuthLost(); return; }
        setError(caught instanceof TradeQueryFailure ? `核销详情加载失败（${caught.code}）${caught.traceId ? ` · 追踪 ${caught.traceId}` : ''}` : '核销详情加载失败，请重试。');
      })
      .finally(() => { if (call === sequence.current) setLoading(false); });
    return () => { sequence.current++; };
  }, [verificationId]);
  return <main className="aftersale-page">
    <p><Link to="/trade/verifications">返回平台核销记录</Link></p>
    <h1>核销记录详情</h1>
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在加载核销详情</p>}
    {detail && <>
      <dl className="snapshot">
        <Field label="核销流水号">{detail.verificationNo}</Field>
        <Field label="记录编号">{detail.verificationId}</Field>
        <Field label="订单编号">{detail.orderId} · <Link to={`/trade/orders/${detail.orderId}`}>订单详情</Link></Field>
        <Field label="门店编号">{detail.storeId}</Field>
        <Field label="核销方式">{detail.verifyMethod === 'SCAN' ? '扫码' : '手动输入'}</Field>
        <Field label="核销时间（UTC）">{detail.verifiedAt}</Field>
        <Field label="核销操作人（员工编号）">{detail.operatorStaffId}</Field>
      </dl>
      <h2>尝试链（争议溯源）</h2>
      {detail.attempts.length === 0
        ? <p>无尝试记录。</p>
        : <div className="table-scroll"><table>
            <thead><tr><th>尝试编号</th><th>结果</th><th>失败原因</th><th>时间（UTC）</th></tr></thead>
            <tbody>{detail.attempts.map(attempt => <tr key={attempt.attemptId}>
              <td>{attempt.attemptId}</td>
              <td>{attempt.result}</td>
              <td>{attempt.failReason ?? '—'}</td>
              <td>{attempt.attemptedAt}</td>
            </tr>)}</tbody>
          </table></div>}
      <h2>风控尝试与锁定</h2>
      {detail.riskAttempts.length === 0
        ? <p>无风控尝试记录。</p>
        : <div className="table-scroll"><table>
            <thead><tr><th>风控尝试编号</th><th>结果码</th><th>计入失败</th><th>时间（UTC）</th></tr></thead>
            <tbody>{detail.riskAttempts.map(risk => <tr key={risk.riskAttemptId}>
              <td>{risk.riskAttemptId}</td>
              <td>{risk.resultCode}</td>
              <td>{risk.countsFailure ? '是' : '否'}</td>
              <td>{risk.attemptedAt}</td>
            </tr>)}</tbody>
          </table></div>}
      <p className="hint">核销凭证密文与查找码按 47/48 号契约留存在核销域，不跨域回传。</p>
    </>}
  </main>;
}
