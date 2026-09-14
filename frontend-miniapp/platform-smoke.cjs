// Runs against a real WeChat DevTools automation endpoint, never a Node fixture substitute.
const automator = require('miniprogram-automator')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
// M-001 integration runs use src/merchant/evidence without overwriting C-001 history.
const evidence = path.resolve(__dirname, process.env.WECHAT_EVIDENCE_DIR || 'src/shared/evidence')
fs.mkdirSync(evidence, { recursive: true })
const label = process.env.WECHAT_EVIDENCE_LABEL || ''
if (!/^[a-zA-Z0-9_-]*$/.test(label)) throw new Error('Invalid evidence label')
const baselineWidth = process.env.WECHAT_BASELINE_WIDTH || (label === 'second-window' ? '390' : '')
if (baselineWidth && (!label || !Number.isFinite(Number(baselineWidth)))) throw new Error('Viewport comparison needs a valid width and distinct evidence label')
const artifact = name => path.join(evidence, name.replace(/(\.[^.]+)$/, `${label ? '-' + label : ''}$1`))
const report = { status: 'RUNNING', source: 'WeChat DevTools simulator',
  eventMethod: 'App.evaluate invokes the real Taro page native tap callback; no state replacement or mocked wx API',
  physicalClickVerified: false, checks: [], exceptions: [] }
let mini
const timeout = setTimeout(() => { report.status = 'FAILED_TIMEOUT'; save(); process.exit(1) }, 55000)
function save() { fs.writeFileSync(artifact('platform-smoke.json'), JSON.stringify(report, null, 2)) }
function stage(name) { report.stage = name; save(); console.log(name) }
const settle = () => new Promise(resolve => setTimeout(resolve, 400))
async function text() {
  return mini.evaluate(() => {
    function content(n) { return n ? (n.v || '') + (n.cn || []).map(content).join('') : '' }
    return content(getCurrentPages().at(-1).data.root)
  })
}
async function tap(label) {
  const deadline = Date.now() + 10000
  while (!(await text()).includes(label)) {
    if (Date.now() > deadline) throw new Error('Page render timeout: ' + label)
    await settle()
  }
  await mini.evaluate(label => {
    const page = getCurrentPages().at(-1)
    const nodes = []
    function visit(n) { nodes.push(n); (n.cn || []).forEach(visit) }
    function content(n) { return (n.v || '') + (n.cn || []).map(content).join('') }
    visit(page.data.root)
    const button = nodes.find(n => n.nn === '14' && content(n) === label)
    if (!button) throw new Error('Native Taro button not found: ' + label)
    const target = { id: button.sid, dataset: {} }
    page.eh({ type: 'tap', timeStamp: Date.now(), target, currentTarget: target,
      detail: {}, touches: [], changedTouches: [] })
  }, label)
  await settle()
}
async function waitText(fragment) {
  const deadline = Date.now() + 10000
  while (!(await text()).includes(fragment)) {
    if (Date.now() > deadline) throw new Error('Render timeout: ' + fragment)
    await settle()
  }
}
;(async () => {
  try {
    mini = await automator.connect({ wsEndpoint: process.env.WECHAT_WS || 'ws://127.0.0.1:19420' })
    mini.on('exception', error => report.exceptions.push(String(error)))
    const info = await mini.systemInfo()
    report.environment = Object.fromEntries(['SDKVersion','version','platform','model','system','windowWidth','windowHeight','pixelRatio','safeArea'].map(k => [k, info[k]]))
    if (baselineWidth) {
      report.requiredDifferentFromWidth = Number(baselineWidth)
      assert.notEqual(info.windowWidth, Number(baselineWidth), 'Actual simulator width has not changed; second-window verification refused')
    }
    await mini.callWxMethod('reLaunch', { url: '/consumer/pages/shell/index' })
    await settle()
    assert.match(await text(), /局部计数：0/)
    await tap('增加计数')
    assert.match(await text(), /局部计数：1/)
    report.checks.push('real simulator initial render and React local counter')
    stage('capture-shell')
    await mini.screenshot({ path: artifact('platform-shell.png') })
    stage('navigate-diagnostics')
    await tap('打开隔离验证页')
    await waitText('内部上下文')
    assert.equal(await mini.evaluate(() => getCurrentPages().at(-1).route), 'consumer/pages/diagnostics/index')
    await tap('重新注入样本上下文')
    await tap('读取内部样本')
    await waitText('9007199254740993')
    assert.match(await text(), /9007199254740993/)
    assert.match(await text(), /128.00/)
    await tap('清除上下文和缓存')
    await waitText('已清除')
    assert.match(await text(), /已清除/)
    assert.doesNotMatch(await text(), /9007199254740993/)
    await tap('重新注入样本上下文')
    await tap('读取内部样本')
    await waitText('INTERNAL_SAMPLE')
    assert.match(await text(), /INTERNAL_SAMPLE/)
    report.checks.push('real navigateTo, fixture sample rendering, context clear removes visible sample, reentry')
    stage('capture-diagnostics')
    await mini.screenshot({ path: artifact('platform-diagnostics.png') })
    const bounds = await mini.evaluate(() => new Promise(resolve => {
      wx.createSelectorQuery().selectAll('.shell, button').boundingClientRect().exec(resolve)
    }))
    report.bounds = bounds
    stage('verify-bounds')
    assert.ok(bounds[0]?.length > 0, 'No native layout bounds returned')
    for (const rect of bounds[0]) {
      assert.ok(rect.left >= -1 && rect.right <= info.windowWidth + 1, 'horizontal overflow')
    }
    report.checks.push('current simulator viewport horizontal bounds')
    assert.equal(report.exceptions.length, 0)
    report.status = 'PASS_SINGLE_SIMULATOR_WINDOW'
  } catch (error) {
    report.status = 'FAIL'; report.error = String(error); process.exitCode = 1
  } finally {
    clearTimeout(timeout); save(); if (mini) mini.disconnect()
    console.log(JSON.stringify(report, null, 2))
  }
})()
