import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

/** 页面容器：保留 <main> 与 <h1> 语义（验收脚本依赖 heading/main 角色），extra 放页头动作区。 */
export function PageContainer({ title, hint, extra, back, children }: {
  title: string;
  hint?: ReactNode;
  extra?: ReactNode;
  back?: ReactNode;
  children: ReactNode;
}) {
  return <main className="page">
    {back && <div className="back-row">{back}</div>}
    <div className="page-head">
      <h1>{title}</h1>
      {extra && <div className="page-extra">{extra}</div>}
    </div>
    {hint && <p className="hint">{hint}</p>}
    {children}
  </main>;
}

/** 详情页顶部返回入口：保持 link 角色（脚本按 link 名字点击）。 */
export function BackLink({ to, children }: { to: string; children: ReactNode }) {
  return <p className="back-link" style={{ margin: 0 }}><Link to={to}>{children}</Link></p>;
}
