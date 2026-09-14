export default defineAppConfig({
  pages: ['consumer/pages/shell/index', 'consumer/pages/diagnostics/index'],
  window: { navigationBarTitleText: '工程验证', backgroundColor: '#f5f5f5' },
  // M-001 internal shell shares this AppID; C-End owns ordinary subpackage registration.
  subPackages: [{ root: 'merchant', pages: ['pages/workspace/index'] }],
})
