// Real WeChat DevTools runtime only. Private fixture controls never imply backend authorization.
const automator = require('miniprogram-automator')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const evidence = path.resolve(__dirname, '../evidence')
fs.mkdirSync(evidence, { recursive: true })
const label = process.env.WECHAT_EVIDENCE_LABEL || 'merchant'
assert.match(label, /^[a-zA-Z0-9_-]+$/)
const artifact = name => path.join(evidence, `${label}-${name}`)
const captureScreenshots = process.env.WECHAT_CAPTURE_SCREENSHOTS !== '0'
const report = { status: 'RUNNING', source: 'WeChat DevTools simulator',
  eventMethod: 'App.evaluate invokes real Taro native tap callback; no setData or mocked wx API',
  physicalClickVerified: false, captureScreenshots, screenshots: [], checks: [], exceptions: [] }
let mini
function save() { fs.writeFileSync(artifact('platform.json'), JSON.stringify(report, null, 2)) }
function stage(name) { report.stage = name; save(); console.log(name) }
const timeout = setTimeout(() => { report.status = 'FAIL_TIMEOUT'; save(); process.exit(1) }, 110000)
const settle = (ms = 250) => new Promise(resolve => setTimeout(resolve, ms))
async function content() {
  return mini.evaluate(() => {
    function text(n) { return n ? (n.v || '') + (n.cn || []).map(text).join('') : '' }
    return text(getCurrentPages().at(-1)?.data?.root)
  })
}
async function waitText(fragment) {
  const until = Date.now() + 15000
  while (true) {
    // DevTools can reject read-only evaluation while its app service changes page.
    // Retry reads only; never replay the native event itself.
    try { if ((await content()).includes(fragment)) return } catch (error) {
      report.transientReadErrors = [...(report.transientReadErrors || []), String(error)]
    }
    if (Date.now() > until) throw new Error('Render timeout: ' + fragment)
    await settle()
  }
}
async function tap(label) {
  stage('tap: ' + label)
  await waitText(label)
  const eventResult = await mini.evaluate(label => {
    try {
    const page = getCurrentPages().at(-1)
    const nodes = []
    function visit(n) { nodes.push(n); (n.cn || []).forEach(visit) }
    function text(n) { return (n.v || '') + (n.cn || []).map(text).join('') }
    visit(page.data.root)
    const button = nodes.find(n => n.nn === '14' && text(n) === label)
    if (!button) throw new Error('Native Taro button missing: ' + label)
    const target = { id: button.sid, dataset: {} }
    page.eh({ type: 'tap', timeStamp: Date.now(), target, currentTarget: target,
      detail: {}, touches: [], changedTouches: [] })
    return { ok: true }
    } catch (error) { return { ok: false, error: String(error) } }
  }, label)
  assert.equal(eventResult.ok, true, eventResult.error)
  await settle(400)
}
const noSample = async () => assert.doesNotMatch(await content(), /INTERNAL_SAMPLE|128\.00|"inspect"/)
async function screenshot(name) {
  if (!captureScreenshots) return
  stage('capture-' + name)
  let timer
  try {
    await Promise.race([mini.screenshot({ path: artifact(name + '.png') }), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('Screenshot channel timed out: ' + name)), 20000)
    })])
  } finally { clearTimeout(timer) }
  report.screenshots.push(name + '.png')
}
async function layout(name) {
  const bounds = await mini.evaluate(() => new Promise(resolve => {
    wx.createSelectorQuery().selectAll('.shell, button').boundingClientRect()
      .selectViewport().scrollOffset().exec(resolve)
  }))
  assert.ok(bounds[0]?.length, 'Native layout bounds required')
  for (const rect of bounds[0]) assert.ok(rect.left >= -1 && rect.right <= report.environment.windowWidth + 1, 'horizontal overflow')
  report.layouts = { ...report.layouts, [name]: bounds }
  const info = report.environment
  const safeBottom = Math.min(info.windowHeight, info.safeArea.bottom - (info.screenHeight - info.windowHeight))
  assert.ok(bounds[0][0].bottom <= safeBottom, 'Engineering shell exceeds current safe viewport')
  report.safeViewportBottom = safeBottom
}
;(async () => {
  try {
    const project = path.resolve(__dirname, '../../..')
    const appBytes = fs.readFileSync(path.join(project, 'dist/app.json'))
    const app = JSON.parse(appBytes)
    const packages = app.subPackages || app.subpackages || []
    assert.equal(packages.length, 1)
    assert.equal(packages[0].root, 'merchant')
    assert.ok(!packages[0].independent)
    report.compiledApp = { sha256: crypto.createHash('sha256').update(appBytes).digest('hex'), config: app }
    report.compiledMerchantSha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(path.join(project, 'dist/merchant/pages/workspace/index.js'))).digest('hex')
    mini = await automator.connect({ wsEndpoint: process.env.WECHAT_WS || 'ws://127.0.0.1:19420' })
    mini.on('exception', error => report.exceptions.push(String(error)))
    const info = await mini.systemInfo()
    report.environment = Object.fromEntries(['SDKVersion', 'version', 'platform', 'model', 'system',
      'screenWidth', 'screenHeight', 'windowWidth', 'windowHeight', 'pixelRatio', 'safeArea'].map(k => [k, info[k]]))
    stage('consumer-to-ordinary-merchant-subpackage')
    await mini.callWxMethod('reLaunch', { url: '/consumer/pages/shell/index' })
    await settle(400)
    await tap('增加计数')
    await waitText('局部计数：1')
    await tap('打开隔离验证页')
    await tap('重新注入样本上下文')
    await tap('读取内部样本')
    await waitText('INTERNAL_SAMPLE')
    await tap('清除上下文和缓存')
    await waitText('已清除'); await noSample()
    await tap('重新注入样本上下文')
    await mini.callWxMethod('reLaunch', { url: '/consumer/pages/shell/index' })
    report.checks.push('Shared C-001 regression: native counter/navigation, sample display, clear and fixture reinjection')
    await tap('进入商家工作区（内部 fixture）')
    await waitText('内部样本：拒绝')
    assert.equal(await mini.evaluate(() => getCurrentPages().at(-1).route), 'merchant/pages/workspace/index')
    assert.match(await content(), /当前工作区：consumer/)
    report.checks.push('MINI-001/002 ordinary merchant subpackage loads through actual consumer navigateTo; default deny')
    stage('fixture-allow-and-real-react-sample')
    await tap('注入允许样本')
    await waitText('内部样本：允许')
    assert.match(await content(), /当前工作区：merchant/)
    await tap('读取延迟样本')
    await waitText('INTERNAL_SAMPLE')
    assert.match(await content(), /9007199254740993/)
    await layout('allowed')
    await screenshot('allowed')
    stage('deny-error-retry')
    await tap('注入拒绝样本')
    await waitText('内部样本：拒绝'); await noSample()
    assert.doesNotMatch(await content(), /样本门店：/)
    await screenshot('denied')
    await tap('注入查询失败')
    await waitText('查询失败，未放行'); await noSample()
    await tap('重新校验')
    await waitText('查询失败，未放行')
    assert.match(await content(), /当前工作区：consumer/)
    await layout('error')
    await screenshot('error')
    report.checks.push('MINI-002 allow renders only after injection; deny/error clear data; retry failure remains closed')
    stage('late-result-after-store-switch')
    await tap('注入允许样本')
    await tap('读取延迟样本')
    await waitText('等待内部样本')
    await tap('切换样本门店')
    await waitText('9007199254740997')
    await settle(1850); await noSample()
    stage('late-result-after-return-to-consumer')
    await tap('读取延迟样本')
    await waitText('等待内部样本')
    await tap('返回用户工作区')
    await waitText('C-001 工程示例')
    await settle(1850); await noSample()
    assert.equal(await mini.evaluate(() => getCurrentPages().at(-1).route), 'consumer/pages/shell/index')
    await layout('returned-consumer')
    report.checks.push('MINI-003 delayed native-page sample cannot appear after store change or return, waited beyond 1600ms')
    stage('forged-deep-link')
    const forged = '/merchant/pages/workspace/index?scenario=allow&allowed=true&merchantId=forged&storeId=forged'
    await mini.callWxMethod('reLaunch', { url: forged })
    await waitText('内部样本：拒绝'); await noSample()
    assert.match(await content(), /当前工作区：consumer/)
    assert.doesNotMatch(await content(), /样本门店：/)
    await tap('注入允许样本'); await waitText('内部样本：允许')
    await tap('注入拒绝样本'); await waitText('内部样本：拒绝')
    await tap('注入查询失败'); await waitText('查询失败，未放行')
    await tap('注入允许样本'); await waitText('内部样本：允许')
    await mini.callWxMethod('reLaunch', { url: forged })
    await waitText('内部样本：拒绝'); await noSample()
    await screenshot('deep-link')
    report.checks.push('MINI-002 forged deep-link query never grants admission; deep-link allow/deny/error controls recheck; fresh reentry resets to deny')
    assert.equal(report.exceptions.length, 0)
    report.status = captureScreenshots ? 'PASS_MERCHANT_REAL_SIMULATOR' : 'PASS_RUNTIME_SCREENSHOTS_NOT_CAPTURED'
  } catch (error) {
    report.status = 'FAIL'; report.error = String(error); process.exitCode = 1
  } finally {
    clearTimeout(timeout); save(); if (mini) mini.disconnect()
    console.log(JSON.stringify(report, null, 2))
  }
})()
