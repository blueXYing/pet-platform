import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  merchantStatusLabel, signingLabel, type MerchantLedgerDetail,
  type MerchantGovernanceClient,
} from '../api/merchantGovernance';
import { type TradeQueryClient } from '../api/tradeQuery';
import { RequestFailure } from '../request';
import { GovernanceFailure, governanceFailureText } from '../api/governanceHttp';

const ID = /^[1-9][0-9]{0,18}$/;

/**
 * 运营 PRD 6.2.2 商家详情：档案+门店+申请+签约四组事实；资质原件/电话不出域。
 * 36 号裁决（2026-10-16 治理写动作最小授权）增"停用"治理入口：调用 63 号 v0.2 既有
 * merchant.disable 路由（内核与审计全在 63 号），带同一人二次确认；未授予动作码 fail-closed。
 */
export default function MerchantLedgerDetailPage({ client, trade, canDisable, onAuthLost }: {
  client: MerchantGovernanceClient;
  trade: TradeQueryClient;
  canDisable: boolean;
  onAuthLost: () => void;
}) {
  const { merchantId = '' } = useParams();
  const [detail, setDetail] = useState<MerchantLedgerDetail>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [disableReason, setDisableReason] = useState('');
  const [disableConfirmed, setDisableConfirmed] = useState(false);
  const [disableBusy, setDisableBusy] = useState(false);
  const [disableNotice, setDisableNotice] = useState('');
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
        if (caught instanceof GovernanceFailure && caught.code === 'UNAUTHENTICATED') { onAuthLost(); return; }
        if (caught instanceof GovernanceFailure && caught.code === 'FORBIDDEN') { setError('权限不足，无法执行该操作'); return; }
        setError(caught instanceof GovernanceFailure && caught.code === 'COMMON_NOT_FOUND' ? '商家不存在（或不在授权范围内）' : governanceFailureText(caught, '商家详情查询失败'));
      })
      .finally(() => { if (call === sequence.current) setLoading(false); });
    return () => { sequence.current++; };
  }, [client, merchantId, onAuthLost]);

  /** 63 号 v0.2 merchant.disable：立即下线（存量订单继续履约），在途订单不拒绝本动作。 */
  async function submitDisable() {
    if (!detail) return;
    const trimmed = disableReason.trim();
    const codePoints = [...trimmed].length;
    if (codePoints < 10 || codePoints > 500) { setError('下线原因必填（10–500 字）'); return; }
    if (!disableConfirmed) { setError('请先勾选二次确认'); return; }
    setDisableBusy(true);
    try {
      const receipt = await trade.disableMerchant(detail.merchantId, trimmed, crypto.randomUUID());
      setDisableNotice(`已下线：商家 ${receipt.merchantId} → 已下线（${receipt.occurredAt} UTC）`);
      setError('');
      setDisableReason('');
      setDisableConfirmed(false);
    } catch (caught) {
      if (caught instanceof RequestFailure && caught.message === 'UNAUTHENTICATED') { onAuthLost(); return; }
      if (caught instanceof RequestFailure && caught.status === 409) setError('下线冲突：商家状态已变化，请刷新后核对');
      else if (caught instanceof RequestFailure && caught.status === 403) setError('当前账号未授予 merchant.disable');
      else setError(caught instanceof Error ? `下线失败（${caught.message}）` : '下线失败');
    } finally {
      setDisableBusy(false);
    }
  }

  const reasonOk = (() => {
    const n = [...disableReason.trim()].length;
    return n >= 10 && n <= 500;
  })();
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
      <section aria-label="商家停用">
        <h2>商家停用（merchant.disable）</h2>
        <p className="hint">对该商家执行立即下线：停止新订单，存量订单继续履约、存量退款/售后继续处理；
          有在途订单不拒绝本动作；下线后 C 端不再展示（SSOT §15，63 号 v0.2 内核）。仅"经营中"状态可下线。</p>
        {!canDisable && <p className="hint">当前账号未授予 merchant.disable，入口 fail-closed 不渲染提交。</p>}
        {canDisable && detail.status !== 'ACTIVE' && <p className="hint">当前状态为{merchantStatusLabel(detail.status)}，不满足下线条件；按钮停用不代表后端放行。</p>}
        {disableNotice && <output role="status">{disableNotice}</output>}
        {canDisable && <>
          <label>下线原因（必填，10–500 字）
            <textarea value={disableReason} onChange={event => setDisableReason(event.target.value)} disabled={disableBusy || detail.status !== 'ACTIVE'} />
          </label>
          <label>
            <input type="checkbox" checked={disableConfirmed} disabled={disableBusy || detail.status !== 'ACTIVE'}
              onChange={event => setDisableConfirmed(event.target.checked)} />
            我确认对该商家执行下线（同一人二次确认，非第二人审批）
          </label>
          <div className="decision-actions">
            <button disabled={disableBusy || detail.status !== 'ACTIVE' || !reasonOk || !disableConfirmed}
              onClick={() => void submitDisable()}>下线该商家</button>
          </div>
        </>}
      </section>
    </>}
  </main>;
}
