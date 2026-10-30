import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import type { ReactNode } from 'react';

dayjs.locale('zh-cn');

/** 运营工作台统一主题：延续既有工作台的静蓝主色（原 .workbench-nav 链接色）。 */
const theme = {
  token: {
    colorPrimary: '#2459a8',
    colorInfo: '#2459a8',
    borderRadius: 6,
  },
};

/** 全局 Provider 壳：生产 App 与 fixture 工程壳共用，保证组件观感一致。 */
export function AdminProviders({ children }: { children: ReactNode }) {
  return <ConfigProvider locale={zhCN} theme={theme}><AntApp>{children}</AntApp></ConfigProvider>;
}
