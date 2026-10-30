import { Alert, Card, Descriptions, Tag } from 'antd';
import type { ReactNode } from 'react';

/** 错误提示：保留 role="alert" 语义（验收脚本按 alert 角色断言），文案原样透传。 */
export function ErrorAlert({ children }: { children: ReactNode }) {
  return <Alert role="alert" type="error" showIcon message={children} style={{ margin: '12px 0' }} />;
}

/** 过程提示：保留 role="status" 语义与原 <p> 形态。 */
export function InlineStatus({ children }: { children: ReactNode }) {
  return <p role="status" className="inline-status">{children}</p>;
}

/** 事实清单：替代原 dl.snapshot，label/值文案保持不变。 */
export function FactList({ items, column = 2 }: { items: { label: string; value: ReactNode }[]; column?: number | { xs?: number; sm?: number; md?: number; lg?: number } }) {
  return <Descriptions
    bordered
    size="small"
    column={column}
    items={items.map((item, index) => ({ key: `${item.label}-${index}`, label: item.label, children: item.value }))}
  />;
}

/** 分区卡片：标题保持 <h2> heading 语义（验收脚本按 heading 名字断言），extra 放右侧动作。 */
export function Section({ title, extra, children }: { title: string; extra?: ReactNode; children: ReactNode }) {
  return <Card style={{ marginTop: 16 }}>
    <div className="section-head">
      <h2 className="section-title">{title}</h2>
      {extra && <div className="section-extra">{extra}</div>}
    </div>
    {children}
  </Card>;
}

/** 多行原文（处置说明/申诉理由等），保持 pre-wrap。 */
export function PreserveText({ children }: { children: ReactNode }) {
  return <div className="preserve-text">{children}</div>;
}

const SUCCESS = ['PAID', 'SUCCESS', 'ACTIVE', 'AVAILABLE', 'PUBLISHED', 'ENABLED', 'VERIFIED', 'COMPLETED', 'APPROVED', 'RESOLVED', 'PASSED', 'ONLINE', 'GRANTED', 'SETTLED'];
const WARNING = ['PENDING', 'WAITING', 'REVIEWING', 'INIT', 'DRAFT', 'SUBMITTED', 'GRACE', 'RESCHEDULING', 'PROCESSING', 'PROVING', 'REQUEST_CORRECTION', 'FROZEN'];
const DANGER = ['REJECTED', 'FAILED', 'CANCELED', 'CANCELLED', 'VOID', 'VOIDED', 'EXPIRED', 'REFUNDING', 'REFUNDED', 'OFFLINE', 'DISABLED', 'ABNORMAL'];

/** 状态 → 语义色；只管颜色，文案一律由页面原有 label 函数给出，不许改字。 */
export function statusColor(status: string): string {
  const upper = status.toUpperCase();
  if (DANGER.some(prefix => upper.startsWith(prefix))) return 'red';
  if (WARNING.some(prefix => upper.startsWith(prefix))) return 'orange';
  if (SUCCESS.some(prefix => upper.includes(prefix))) return 'green';
  return 'default';
}

export function StatusTag({ label, status }: { label: string; status?: string | null }) {
  return <Tag color={status ? statusColor(status) : 'default'}>{label}</Tag>;
}
