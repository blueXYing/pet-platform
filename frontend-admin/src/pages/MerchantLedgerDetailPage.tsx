import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  merchantStatusLabel, signingLabel, type MerchantLedgerDetail,
  type MerchantGovernanceClient,
} from '../api/merchantGovernance';
import { GovernanceFailure, governanceFailureText } from '../api/governanceHttp';

const ID = /^[1-9][0-9]{0,18}$/;

/** 运营 PRD 6.2.2 商家详情（只读切片）：档案+门店+申请+签约四组事实；资质原件/电话不出域。 */
export default function MerchantLedgerDetailPage({ client, onAuthLost }: { client: MerchantGovernanceClient; onAuthLost: () => void }) {
  const { merchantId = '' } = useParams();
  const [detail, setDetail] = useState<MerchantLedgerDetail>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);
  useEffect(() => {
    const call = ++sequence.current;
    if (!ID.test(merchantId)) { setError('商家编号不合法'); return; }
    setLoading(true);
    setError('');
    client.getMerchant(merchantId)
      .then(data => { if (call === sequence.current) setDetail(data); })
      .catch((caught: unknown) => {
        if (call !== sequence.current) return;
        if (caught instanceof GovernanceFailure && ['UNAUTHENTICATED', 'FORBIDDEN'].includes(caught.code)) { onAuthLost(); return; }
        setError(caught instanceof GovernanceFailure && caught.code === 'COMMON_NOT_FOUND' ? '商家不存在（或不在授权范围内）' : governanceFailureText(caught, '商家详情查询失败'));
      })
      .finally(() => { if (call === sequence.current) setLoading(false); });
    return () => { sequence.current++; };
  }, [client, merchantId, onAuthLost]);
  return <main className="aftersale-page">
    <p><Link to="/governance/merchants">返回商家台账</Link></p>
    <h1>商家详情</h1>
    {error && <p role="alert">{error}</p>}
    {loading && <p role="status">正在查询商家详情</p>}
    {detail && <>
      <section aria-label="基础档案">
        <h2>基础档案</h2>
        <dl className="facts">
          <dt>商家 ID</dt><dd>{detail.merchantId}</dd>
          <dt>名称</dt><dd>{detail.merchantName}</dd>
          <dt>状态</dt><dd>{merchantStatusLabel(detail.status)}</dd>
          <dt>类型 / 城市</dt><dd>{detail.merchantTypeCode ?? '—'} / {detail.cityCode ?? '—'}</dd>
          <dt>门店（经营/总数）</dt><dd>{detail.activeStoreCount} / {detail.storeCount}</dd>
          <dt>子商户号（脱敏）</dt><dd>{detail.providerMerchantNoMasked ?? '—'}</dd>
          <dt>入驻（UTC）</dt><dd>{detail.createdAt}</dd>
        </dl>
      </section>
      <section aria-label="门店">
        <h2>门店</h2>
        <div className="table-scroll"><table>
          <thead><tr><th>门店 ID</th><th>名称</th><th>地址</th><th>状态</th></tr></thead>
          <tbody>{detail.stores.map(store => <tr key={store.storeId}>
            <td>{store.storeId}</td><td>{store.storeName}</td><td>{store.address}</td>
            <td>{merchantStatusLabel(store.status)}</td>
          </tr>)}</tbody>
        </table></div>
      </section>
      <section aria-label="申请事实">
        <h2>入驻申请</h2>
        {detail.application
          ? <dl className="facts">
              <dt>申请编号</dt><dd>{detail.application.applicationId}</dd>
              <dt>申请单号</dt><dd>{detail.application.applicationNo ?? '—'}</dd>
              <dt>状态</dt><dd>{({ DRAFT: '草稿', REVIEWING: '审核中', APPROVED: '已通过', REJECTED: '已驳回' } as Record<string, string>)[detail.application.status] ?? detail.application.status}</dd>
              <dt>提交（UTC）</dt><dd>{detail.application.submittedAt ?? '—'}</dd>
              <dt>审结（UTC）</dt><dd>{detail.application.reviewedAt ?? '—'}</dd>
            </dl>
          : <p>无申请事实（存量商家）。</p>}
      </section>
      <section aria-label="签约事实">
        <h2>签约（SSOT §26 电子协议）</h2>
        {detail.signing
          ? <dl className="facts">
              <dt>状态</dt><dd>{signingLabel(detail.signing.signingStatus)}</dd>
              <dt>协议版本</dt><dd>{detail.signing.agreementVersion}</dd>
              <dt>签署（UTC）</dt><dd>{detail.signing.acceptedAt ?? '—'}</dd>
            </dl>
          : <p>无当前协议版本。</p>}
      </section>
    </>}
  </main>;
}
