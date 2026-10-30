import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Layout, Menu, type MenuProps } from 'antd';

const { Content, Header, Sider } = Layout;

/**
 * 工作台外壳：左侧分组菜单（按权限位裁剪，与原顶部导航一致）+ 顶栏身份/退出。
 * 菜单条目仍是路由跳转；无权限的入口不渲染，与旧版行为一致。
 */
export function AdminLayout({ menuItems, identity, signOut, pathname, children }: {
  menuItems: MenuProps['items'];
  identity?: React.ReactNode;
  signOut: () => void;
  pathname: string;
  children: React.ReactNode;
}) {
  const navigate = useNavigate();
  const [collapsed, setCollapsed] = useState(false);
  return <Layout style={{ minHeight: '100vh' }}>
    <Sider collapsible collapsed={collapsed} onCollapse={setCollapsed} theme="light" width={216}>
      <div className="brand"><strong>宠物平台 · 运营工作台</strong></div>
      <Menu
        mode="inline"
        items={menuItems}
        selectedKeys={[pathname]}
        onClick={({ key }) => { if (key !== pathname) void navigate(key); }}
        style={{ borderInlineEnd: 'none' }}
      />
    </Sider>
    <Layout>
      <Header className="topbar">
        <span className="topbar-identity">{identity}</span>
        <Button onClick={signOut}>退出</Button>
      </Header>
      <Content>{children}</Content>
    </Layout>
  </Layout>;
}
