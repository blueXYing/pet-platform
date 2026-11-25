import { useRef, useState } from 'react';
import { Alert, Button, Input, InputNumber, Switch } from 'antd';
import {
  describeSettlementFailure, formatPercentRate, formatUtcDateTime, isValidDecimalId,
  type SettlementAdminClient, type SettlementConfigView,
} from '../api/settlementAdmin';
import { RequestFailure } from '../request';
import { ErrorAlert, FactList, InlineStatus, Section, StatusTag } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

// 商家结算配置（Contract 80 §4.1 / 40 号 §2 & §5）：
// 按商家配置 T+d 结算天数（7..30，默认 7，核销完成时快照）与对外手续费率（百分比 4 位小数，
// 默认 0.6000，支付成功时快照）。读权限 settlement.report.read 或 settlement.config.write，
// 写权限 settlement.config.write，X-Request-Id 幂等。

function normalizeFeeRate(raw: string): string | null {
  const trimmed = raw.trim();
  if (!/^(?:0|[1-9][0-9]?)(?:\.[0-9]{1,4})?$/.test(trimmed)) return null;
  const [intPart, fracPart = ''] = trimmed.split('.');
  const intNum = Number(intPart);
  if (!Number.isInteger(intNum) || intNum < 0 || intNum > 99) return null;
  return `${intNum}.${fracPart.padEnd(4, '0')}`;
}

export default function SettlementConfigPage({ client, canWrite, onAuthLost }: {
  client: SettlementAdminClient;
  canWrite: boolean;
  onAuthLost: () => void;
}) {
  const [merchantIdInput, setMerchantIdInput] = useState('');
  const [config, setConfig] = useState<SettlementConfigView>();
  const [settleDays, setSettleDays] = useState<number>(7);
  const [feeRate, setFeeRate] = useState<string>('0.6000');
  const [enabled, setEnabled] = useState<boolean>(true);
  const [reason, setReason] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const sequence = useRef(0);
  const requestIdRef = useRef(crypto.randomUUID());

  function resetCommandKey() {
    requestIdRef.current = crypto.randomUUID();
    setNotice('');
    setError('');
  }

  function applyConfig(view: SettlementConfigView) {
    setConfig(view);
    setSettleDays(view.settleDays);
    setFeeRate(normalizeFeeRate(String(view.feeRate)) ?? String(view.feeRate));
    setEnabled(view.enabled);
    setReason('');
    requestIdRef.current = crypto.randomUUID();
  }

  async function lookup() {
    const trimmed = merchantIdInput.trim();
    if (!isValidDecimalId(trimmed)) {
      setError('请输入有效的商家 ID（正整数 Snowflake ID）。');
      return;
    }
    const call = ++sequence.current;
    setLoading(true);
    setError('');
    setNotice('');
    try {
      const view = await client.getConfig(trimmed);
      if (call !== sequence.current) return;
      applyConfig(view);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED') {
        onAuthLost();
        return;
      }
      setConfig(undefined);
      setError(describeSettlementFailure(caught, '查询商家结算配置失败'));
    } finally {
      if (call === sequence.current) setLoading(false);
    }
  }

  async function save() {
    if (!config || !canWrite || saving) return;
    if (!Number.isInteger(settleDays) || settleDays < 7 || settleDays > 30) {
      setError('结算天数 d 必须为 7～30 之间的整数（下限对齐核销后 7 天售后窗口）。');
      return;
    }
    const normalizedRate = normalizeFeeRate(feeRate);
    if (normalizedRate === null) {
      setError('对外手续费率必须为 0.0000 ～ 99.9999 之间的合法百分比（最多 4 位小数）。');
      return;
    }
    if (reason.trim().length > 256) {
      setError('变更原因不能超过 256 字。');
      return;
    }
    const call = sequence.current;
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const saved = await client.upsertConfig(
        config.merchantId,
        {
          settleDays,
          feeRate: normalizedRate,
          enabled,
          ...(reason.trim() ? { reason: reason.trim() } : {}),
        },
        requestIdRef.current,
      );
      if (call !== sequence.current) return;
      applyConfig(saved);
      setNotice(`商家 ${saved.merchantId} 结算配置已保存（版本 ${saved.version}）。新配置仅影响后续新订单，不溯及既有结算单快照。`);
    } catch (caught) {
      if (call !== sequence.current) return;
      if (caught instanceof RequestFailure && caught.code === 'UNAUTHENTICATED') {
        onAuthLost();
        return;
      }
      if (caught instanceof RequestFailure && caught.code === 'IDEMPOTENCY_KEY_CONFLICT') {
        requestIdRef.current = crypto.randomUUID();
      }
      setError(describeSettlementFailure(caught, '保存商家结算配置失败'));
    } finally {
      if (call === sequence.current) setSaving(false);
    }
  }

  return <PageContainer
    title="商家结算配置"
    hint="按商家配置 T+d 结算周期（T 为核销完成时间，d 范围 7～30 天）与商家对外手续费率（百分比，保留 4 位小数，默认 0.6000%）。支付成功时快照对外费率，核销完成时快照结算天数，修改配置不影响已生成快照的既有订单。">
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); void lookup(); }}>
      <label className="filter-item">
        商家 ID
        <Input
          style={{ width: 240 }}
          value={merchantIdInput}
          onChange={event => setMerchantIdInput(event.target.value)}
          placeholder="输入商家 Snowflake ID"
          inputMode="numeric"
        />
      </label>
      <Button type="primary" htmlType="submit" loading={loading}>查询配置</Button>
    </form>

    {error && <ErrorAlert>{error}</ErrorAlert>}
    {notice && <InlineStatus>{notice}</InlineStatus>}

    {!config && !loading && (
      <Alert
        type="info"
        showIcon
        style={{ marginTop: 16 }}
        message="请输入商家 ID 查询当前生效的结算配置；未单独配置的商家默认采用平台 T+7、0.6000% 口径。"
      />
    )}

    {config && <>
      <Section title={`当前生效快照 · 商家 ${config.merchantId}`}>
        <FactList column={2} items={[
          { label: '商家 ID', value: <code>{config.merchantId}</code> },
          {
            label: '配置来源',
            value: <StatusTag label={config.configured ? '已单独配置' : '平台默认值'} status={config.configured ? 'ACTIVE' : 'DRAFT'} />,
          },
          { label: '结算周期 (T+d)', value: `T + ${config.settleDays} 天` },
          { label: '对外手续费率', value: formatPercentRate(config.feeRate) },
          {
            label: '启用状态',
            value: <StatusTag label={config.enabled ? '已启用' : '已停用'} status={config.enabled ? 'ENABLED' : 'DISABLED'} />,
          },
          { label: '配置版本', value: String(config.version) },
          { label: '最近更新时间', value: formatUtcDateTime(config.updatedAt) },
        ]} />
      </Section>

      <Section title="修改结算配置">
        <form onSubmit={event => { event.preventDefault(); void save(); }}>
          <div className="filter-item">
            <label htmlFor="settlement-days">T+d 结算天数（7～30 天）</label>
            <InputNumber
              id="settlement-days"
              min={7}
              max={30}
              precision={0}
              step={1}
              style={{ width: 220 }}
              value={settleDays}
              disabled={!canWrite || saving}
              onChange={value => {
                if (typeof value === 'number') setSettleDays(value);
                resetCommandKey();
              }}
            />
            <span className="hint">下限 7 天与核销后 7 天售后窗口对齐；仅作用于后续未核销订单。</span>
          </div>

          <div className="filter-item">
            <label htmlFor="settlement-fee-rate">对外手续费率（%，0.0000 ～ 99.9999，保留 4 位小数）</label>
            <Input
              id="settlement-fee-rate"
              style={{ width: 220 }}
              value={feeRate}
              disabled={!canWrite || saving}
              placeholder="例如 0.6000"
              onChange={event => {
                setFeeRate(event.target.value);
                resetCommandKey();
              }}
            />
            <span className="hint">商家份额 = 分账基数 × (1 - 对外手续费率%)，按分向下取整；平台不另收佣金。</span>
          </div>

          <div className="filter-item">
            <label>启用状态</label>
            <div>
              <Switch
                checked={enabled}
                disabled={!canWrite || saving}
                checkedChildren="启用"
                unCheckedChildren="停用"
                onChange={checked => {
                  setEnabled(checked);
                  resetCommandKey();
                }}
              />
            </div>
          </div>

          <div className="filter-item">
            <label htmlFor="settlement-config-reason">变更原因（选填，≤256 字）</label>
            <Input.TextArea
              id="settlement-config-reason"
              rows={3}
              maxLength={256}
              showCount
              value={reason}
              disabled={!canWrite || saving}
              placeholder="记录费率或 T+d 调整依据（写入不可变审计日志）"
              onChange={event => {
                setReason(event.target.value);
                resetCommandKey();
              }}
            />
          </div>

          {canWrite ? (
            <Button type="primary" htmlType="submit" loading={saving}>保存配置</Button>
          ) : (
            <Alert type="warning" showIcon message="当前账号仅有查看权限，未授予 settlement.config.write，不可保存修改。" />
          )}
        </form>
      </Section>
    </>}
  </PageContainer>;
}
