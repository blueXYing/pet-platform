import { useEffect, useRef, useState } from 'react';
import { Checkbox } from 'antd';
import type { TableColumnsType } from 'antd';
import type { LedgerAdminClient, RuntimeSettings } from '../api/ledgerAdmin';
import { DataTable } from '../ui/DataTable';
import { ErrorAlert, InlineStatus } from '../ui/bits';
import { PageContainer } from '../ui/PageContainer';

/**
 * Contract-65 系统设置页 (PRD 6.11.1 系统参数台账·开关视图 / 6.13, A-005): a read-only view of
 * the deployed pet.* assembly switches and their live state (the 21号《部署开关组合清单》
 * registry). There is no runtime configuration mechanism in V1 — the page cannot write, and
 * says so, because the wire has no such route (CCR-ADM-A005-OPS-001).
 */
export default function SettingsRuntimePage({ client, onAuthLost }: {
  client: LedgerAdminClient;
  onAuthLost: () => void;
}) {
  const [settings, setSettings] = useState<RuntimeSettings>();
  const [error, setError] = useState('');
  const [enabledOnly, setEnabledOnly] = useState(false);
  const sequence = useRef(0);

  useEffect(() => {
    const call = ++sequence.current;
    void (async () => {
      try {
        const data = await client.runtimeSettings();
        if (call !== sequence.current) return;
        setSettings(data);
      } catch (caught) {
        if (call !== sequence.current) return;
        if (caught instanceof Error && caught.message === 'UNAUTHENTICATED') { onAuthLost(); return; }
        setError(caught instanceof Error ? `读取失败（${caught.message}）` : '读取失败');
      }
    })();
    return () => { sequence.current++; };
  }, [client]);

  const rows = (settings?.switches ?? []).filter(row => !enabledOnly || row.enabled);
  const columns: TableColumnsType<RuntimeSettings['switches'][number]> = [
    { title: '开关', dataIndex: 'key' },
    { title: '状态', render: (_, row) => row.enabled ? '开启' : '关闭' },
    { title: '域', dataIndex: 'phase' },
  ];

  return <PageContainer
    title="系统设置 · 运行时开关"
    hint={<>只读视图：部署开关组合清单（21 号）登记的 pet.* 装配开关及其实时状态。
      V1 配置仅部署期环境变量，本页不可写、也无运行时配置中心（CCR-ADM-A005-OPS-001）；
      积分量等已批准可配置参数走「积分任务配置」页。</>}>
    <form className="filter-bar" onSubmit={event => event.preventDefault()}>
      <Checkbox checked={enabledOnly} onChange={event => setEnabledOnly(event.target.checked)}>仅显示已开启</Checkbox>
    </form>
    {error && <ErrorAlert>{error}</ErrorAlert>}
    {settings === undefined
      ? <InlineStatus>加载中</InlineStatus>
      : <DataTable columns={columns} rows={rows} rowKey={row => row.key} />}
    <p className="hint">共 {rows.length} 项（登记 {settings?.switches.length ?? 0} 项）</p>
  </PageContainer>;
}
