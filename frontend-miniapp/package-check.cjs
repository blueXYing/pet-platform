// Raw output byte inventory checked against documented caps, not WeChat upload/package verification.
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const root = path.join(__dirname, 'dist')
const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
assert.deepEqual(app.pages, ['consumer/pages/shell/index', 'consumer/pages/diagnostics/index', 'consumer/pages/profile-edit/index'])
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(path.join(root, 'consumer/pages/profile-edit/index.' + extension)),
    'C-002 profile page artifact missing: ' + extension)
}
for (const page of ['index', 'detail', 'form']) {
  for (const extension of ['js', 'json', 'wxml', 'wxss']) {
    assert.ok(fs.existsSync(path.join(root, `consumer/pages/pet-archive/${page}.` + extension)),
      `C-002 pet page artifact missing: ${page}.${extension}`)
  }
}
const packages = app.subPackages || app.subpackages || []
assert.deepEqual(packages.map(p => ({ root: p.root, pages: p.pages })), [
  { root: 'merchant', pages: ['pages/workspace/index'] },
  { root: 'consumer/pages/pet-archive', pages: ['index', 'detail', 'form'] },
  { root: 'consumer/pages/merchant-application', pages: ['index'] },
], 'Merchant workspace, pet archive and merchant application must be registered in the single app')
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(path.join(root, 'consumer/pages/merchant-application/index.' + extension)),
    'MER-001 application page artifact missing: ' + extension)
}
assert.ok(packages.every(p => !p.independent), 'Only ordinary subpackages allowed')
for (const extension of ['js', 'json', 'wxml']) {
  assert.ok(fs.existsSync(path.join(root, 'merchant/pages/workspace/index.' + extension)),
    'Merchant page build artifact missing: ' + extension)
}
function walk(dir) { return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory()
  ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]) }
const files = walk(root).map(file => ({ file: path.relative(root, file).replaceAll('\\', '/'), bytes: fs.statSync(file).size }))
// Shared C navigation belongs to the main package; business visuals/fonts stay in their subpackage.
const sourceRoot = path.join(__dirname, 'src')
const componentRoot = path.join(sourceRoot, 'consumer/components')
const navigationAssets = 'consumer/assets/navigation/'
const navManifest = JSON.parse(fs.readFileSync(path.join(sourceRoot, navigationAssets, 'manifest.json'), 'utf8'))
const crypto = require('node:crypto')
const sharedNavigationCode = fs.readFileSync(path.join(root, 'common.js'), 'utf8')
for (const asset of navManifest.assets) {
  const file = path.join(__dirname, '..', asset.file)
  const bytes = fs.readFileSync(file)
  assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), asset.sha256, 'Navigation must use original image bytes')
  const outputPath = `${navigationAssets}${path.basename(file)}`
  // Taro inlines very small PNGs; those exact bytes must be in the shared main chunk.
  assert.ok(sharedNavigationCode.includes(outputPath) || sharedNavigationCode.includes(bytes.toString('base64')),
    `Navigation asset must be emitted or inlined in the main shared chunk: ${outputPath}`)
  for (const bundle of files.filter(file => file.file.endsWith('.js') && packages.some(pack => file.file.startsWith(pack.root + '/')))) {
    assert.ok(!fs.readFileSync(path.join(root, bundle.file), 'utf8').includes(bytes.toString('base64')),
      `Navigation icon bytes must not be duplicated in a subpackage: ${bundle.file}`)
  }
}
for (const file of ['navigation', 'page-layout'].flatMap(dir => walk(path.join(componentRoot, dir))).filter(file => /\.(tsx?|css)$/.test(file))) {
  const text = fs.readFileSync(file, 'utf8')
  for (const match of text.matchAll(/(?:from\s+|import\s*)['"]([^'"]+)['"]/g)) {
    if (!match[1].startsWith('.')) continue
    const dependency = path.resolve(path.dirname(file), match[1])
    assert.ok(dependency.startsWith(componentRoot + path.sep) || dependency.startsWith(path.join(sourceRoot, navigationAssets)),
      `Shared consumer component must not depend on business pages or subpackages: ${file} -> ${match[1]}`)
  }
}
assert.ok(!files.some(file => /\/assets\/tab-(home|services|balloon|messages|mine)\.png$/.test(file.file)), 'Duplicate pet tab icons must not be packaged')
assert.ok(!files.some(file => !file.file.startsWith('consumer/pages/pet-archive/') && /(?:strip-(?:main|form-bottom|detail-bottom)\.png|c002-pet-)/.test(file.file)),
  'Pet backgrounds and fonts must stay in the pet subpackage')
const sizes = Object.fromEntries(packages.map(p => [p.root, 0]))
let mainBytes = 0
for (const file of files) {
  const pack = packages.find(p => file.file.startsWith(p.root + '/'))
  if (pack) sizes[pack.root] += file.bytes
  else mainBytes += file.bytes
}
const totalBytes = files.reduce((n, f) => n + f.bytes, 0)
const report = { test: 'MINI-005 static inventory only', mainBytes, subpackages: sizes, totalBytes,
  ordinarySubpackageCount: packages.length, fileCount: files.length,
  internalBudgetBytes: 2 * 1024 * 1024,
  platformLimitVerified: true, platformLimitSource: 'https://developers.weixin.qq.com/miniprogram/dev/framework/subpackages.html',
  platformLimitCheckedOn: '2026-09-11', totalBudgetBytes: 20 * 1024 * 1024,
  totalBudgetReason: 'conservative service-provider cap; standard total cap is 30M', platformUploadVerified: false,
  deviceWindowsVerified: false, imageClarityVerified: false }
console.log(JSON.stringify(report, null, 2))
assert.ok(mainBytes < report.internalBudgetBytes, 'Internal main budget exceeded')
for (const bytes of Object.values(sizes)) assert.ok(bytes < report.internalBudgetBytes)
assert.ok(totalBytes < report.totalBudgetBytes, 'Total package budget exceeded')
