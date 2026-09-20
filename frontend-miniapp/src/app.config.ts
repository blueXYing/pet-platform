export default defineAppConfig({
  pages: ['consumer/pages/shell/index', 'consumer/pages/diagnostics/index', 'consumer/pages/profile-edit/index'],
  window: { navigationBarTitleText: '工程验证', backgroundColor: '#f5f5f5' },
  permission: { 'scope.userLocation': { desc: '用于选择入驻店铺的位置与地址' } },
  requiredPrivateInfos: ['chooseLocation'],
  // M-001 internal shell shares this AppID; C-End owns ordinary subpackage registration.
  // The pet archive pages ship as an ordinary subpackage so the 2x design strips stay within
  // the platform per-package limit; routes are unchanged.
  subPackages: [
    { root: 'merchant', pages: ['pages/workspace/index'] },
    { root: 'consumer/pages/pet-archive', pages: ['index', 'detail', 'form'] },
    { root: 'consumer/pages/merchant-application', pages: ['index'] },
  ],
})
