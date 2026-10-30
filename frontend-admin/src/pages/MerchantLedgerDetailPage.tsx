import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Button, Checkbox, Input } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  merchantStatusLabel, signingLabel, type MerchantLedgerDetail,
  type MerchantGovernanceClient, type MerchantStoreRow,
} from '../api/merchantGovernance';
import { type TradeQueryClient } from '../api/tradeQuery';
import { RequestFailure } from '../request';
import { GovernanceFailure, governanceFailureText } from '../api/governanceHttp';
import { DataTable } from '../ui/DataTable';
import { BackLink, PageContainer } from '../ui/PageContainer';
import { ErrorAlert, FactList, InlineStatus, Section, StatusTag } from '../ui/bits';

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
  const storeColumns: TableColumnsType<MerchantStoreRow> = [
    { title: '门店 ID', dataIndex: 'storeId' },
    { title: '名称', dataIndex: 'storeName' },
    { title: '地址', dataIndex: 'address' },
    { title: '状态', render: (_, store) => <StatusTag label={merchantStatusLabel(store.status)} status={store.status} /> },
  ];
  return <PageContainer
    title="商家详情"
    back={<BackLink to="/governance/merchants">返回商家台账</BackLink>}>
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {loading && <InlineStatus>正在查询商家详情</InlineStatus>}
    {detail && <>
      <Section title="基础档案">
        <FactList items={[
          { label: '商家 ID', value: detail.merchantId },
          { label: '名称', value: detail.merchantName },
          { label: '状态', value: merchantStatusLabel(detail.status) },
          { label: '类型 / 城市', value: `${detail.merchantTypeCode ?? '—'} / ${detail.cityCode ?? '—'}` },
          { label: '门店（经营/总数）', value: `${detail.activeStoreCount} / ${detail.storeCount}` },
          { label: '子商户号（脱敏）', value: detail.providerMerchantNoMasked ?? '—' },
          { label: '入驻（UTC）', value: detail.createdAt },
        ]} />
      </Section>
      <Section title="门店">
        <DataTable columns={storeColumns} rows={detail.stores} rowKey={store => store.storeId} />
      </Section>
      <Section title="入驻申请">
        {detail.application
          ? <FactList items={[
              { label: '申请编号', value: detail.application.applicationId },
              { label: '申请单号', value: detail.application.applicationNo ?? '—' },
              { label: '状态', value: ({ DRAFT: '草稿', REVIEWING: '审核中', APPROVED: '已通过', REJECTED: '已驳回' } as Record<string, string>)[detail.application.status] ?? detail.application.status },
              { label: '提交（UTC）', value: detail.application.submittedAt ?? '—' },
              { label: '审结（UTC）', value: detail.application.reviewedAt ?? '—' },
            ]} />
          : <p>无申请事实（存量商家）。</p>}
      </Section>
      <Section title="签约（SSOT §26 电子协议）">
        {detail.signing
          ? <FactList items={[
              { label: '状态', value: signingLabel(detail.signing.signingStatus) },
              { label: '协议版本', value: detail.signing.agreementVersion },
              { label: '签署（UTC）', value: detail.signing.acceptedAt ?? '—' },
            ]} />
          : <p>无当前协议版本。</p>}
      </Section>
      <section aria-label="商家停用">
      <Section title="商家停用（merchant.disable）">
        <p className="hint">对该商家执行立即下线：停止新订单，存量订单继续履约、存量退款/售后继续处理；
          有在途订单不拒绝本动作；下线后 C 端不再展示（SSOT §15，63 号 v0.2 内核）。仅"经营中"状态可下线。</p>
        {!canDisable && <p className="hint">当前账号未授予 merchant.disable，入口 fail-closed 不渲染提交。</p>}
        {canDisable && detail.status !== 'ACTIVE' && <p className="hint">当前状态为{merchantStatusLabel(detail.status)}，不满足下线条件；按钮停用不代表后端放行。</p>}
        {disableNotice && <InlineStatus>{disableNotice}</InlineStatus>}
        {canDisable && <>
          <label className="filter-item">下线原因（必填，10–500 字）
            <Input.TextArea style={{ width: 'min(100%, 720px)' }} rows={3} value={disableReason} onChange={event => setDisableReason(event.target.value)} disabled={disableBusy || detail.status !== 'ACTIVE'} />
          </label>
          <div><Checkbox checked={disableConfirmed} disabled={disableBusy || detail.status !== 'ACTIVE'}
            onChange={event => setDisableConfirmed(event.target.checked)}>我确认对该商家执行下线（同一人二次确认，非第二人审批）</Checkbox></div>
          <div className="decision-actions">
            <Button danger type="primary" disabled={disableBusy || detail.status !== 'ACTIVE' || !reasonOk || !disableConfirmed}
              onClick={() => void submitDisable()}>下线该商家</Button>
          </div>
        </>}
      </Section>
      </section>
    </>}
  </PageContainer>;
}
