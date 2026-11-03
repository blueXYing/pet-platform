import { Table, type TableColumnsType } from 'antd';

/**
 * 服务端分页表格：包装 antd Table。
 * - 分页摘要文案保持旧版「第 X / Y 页 · 共 N 条」原样（验收脚本断言该文本）。
 * - 旧版分页上限 10000 页（服务端投影约束）在 total 上延续。
 */
export function DataTable<Row>(props: {
  columns: TableColumnsType<Row>;
  rows: readonly Row[] | undefined;
  rowKey: (row: Row) => string;
  loading?: boolean;
  page?: number;
  pageSize?: number;
  total?: number;
  onPageChange?: (page: number) => void;
}) {
  const { columns, rows, rowKey, loading, page, pageSize, total, onPageChange } = props;
  const paged = page !== undefined && pageSize !== undefined && total !== undefined && onPageChange !== undefined;
  const pages = page !== undefined && pageSize !== undefined && total !== undefined
    ? Math.max(1, Math.ceil(Math.min(total, 10000 * pageSize) / pageSize))
    : 1;
  return <Table<Row>
    size="middle"
    rowKey={row => rowKey(row)}
    columns={columns}
    dataSource={rows ? [...rows] : []}
    loading={loading}
    scroll={{ x: 'max-content' }}
    style={{ marginTop: 16 }}
    pagination={paged ? {
      current: page,
      pageSize,
      total: Math.min(total, 10000 * pageSize),
      showSizeChanger: false,
      onChange: next => onPageChange(next),
      showTotal: () => `第 ${page} / ${pages} 页 · 共 ${total} 条`,
    } : false}
  />;
}
