import { Table, type TableColumnsType } from 'antd';

/**
 * 服务端分页表格：包装 antd Table。
 * - 分页摘要文案保持旧版「第 X / Y 页 · 共 N 条」原样（验收脚本断言该文本）。
 * - 旧版分页上限 10000 页（服务端投影约束）在 total 上延续。
 * - tableLayout：不传则沿用 antd 默认（其余页面行为不变）。列宽需要被严格遵守、单元格内容靠省略号收尾的表
 *   （如营销中心）传 'fixed'——自动布局下不换行的长内容会把列撑开，列宽与省略号都不生效。
 *   固定布局只有在表格宽度是确定值时才严格遵守列宽：width: max-content 的表格照样会被不换行的内容撑开。
 *   所以固定布局且各列都给了数值宽度时，表格宽度取各列宽度之和（容器更宽时仍拉伸到 100%）。
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
  tableLayout?: 'fixed' | 'auto';
}) {
  const { columns, rows, rowKey, loading, page, pageSize, total, onPageChange, tableLayout } = props;
  const widths = columns.map(column => (column as { width?: unknown }).width);
  const fixedWidth = tableLayout === 'fixed' && widths.every(width => typeof width === 'number')
    ? widths.reduce((sum, width) => sum + Number(width), 0)
    : undefined;
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
    scroll={{ x: fixedWidth ?? 'max-content' }}
    tableLayout={tableLayout}
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
