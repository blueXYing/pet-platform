const automator = require('miniprogram-automator')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const out = path.resolve(__dirname, '../../../../planning/issues/wave-2/C-002-profile/evidence')
fs.mkdirSync(out, { recursive: true })
const report = { status: 'RUNNING', source: 'real WeChat DevTools simulator', dataMode: 'explicit in-memory visual preview; no backend session or save', eventMethod: 'native Taro page event handlers; no setData or mock wx', physicalInputVerified: false, tests: [], exceptions: [] }
const timeout = setTimeout(() => { report.status = 'TIMEOUT'; saveReport(); process.exit(1) }, 55000)
let mini
function saveReport() { fs.writeFileSync(path.join(out, 'platform.json'), JSON.stringify(report, null, 2)) }
function check(name) { report.tests.push(name); console.log(name); saveReport() }
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
async function snapshot() {
  return mini.evaluate(() => {
    const page = getCurrentPages().at(-1)
    const all = []
    function walk(n) { if (!n) return; all.push(n); (n.cn || []).forEach(walk) }
    walk(page.data.root)
    return all.map(n => ({ id: n.uid || n.id || n.sid, sid: n.sid, name: n.nn, text: n.v || '', cl: n.cl || '', value: n.value, phase: n['data-phase'] }))
  })
}
async function text() { return (await snapshot()).map(n => n.text).join('') }
async function waitFor(fragment) {
  const end = Date.now() + 7000
  while (!(await text()).includes(fragment)) { if (Date.now() > end) throw new Error('Missing text: ' + fragment); await delay(150) }
}
async function event(id, type, detail = {}) {
  await mini.evaluate((id, type, detail) => {
    const page = getCurrentPages().at(-1)
    const all = []
    function walk(n) { if (!n) return; all.push(n); (n.cn || []).forEach(walk) }
    walk(page.data.root)
    const node = all.find(n => n.uid === id || n.id === id)
    if (!node) throw new Error('Element not rendered: ' + id)
    const target = { id: node.sid, dataset: { sid: node.sid } }
    page.eh({ type, timeStamp: Date.now(), target, currentTarget: target, detail, touches: [], changedTouches: [] })
  }, id, type, detail)
  await delay(120)
}
async function launch(query) {
  await mini.callWxMethod('reLaunch', { url: '/consumer/pages/profile-edit/index' + query })
  await delay(350)
}
async function diagnosticAction(label) {
  await mini.evaluate(label => {
    const page = getCurrentPages().at(-1); const all = []
    function walk(n) { if (!n) return; all.push(n); (n.cn || []).forEach(walk) }
    function content(n) { return (n.v || '') + (n.cn || []).map(content).join('') }
    walk(page.data.root)
    const node = all.find(n => n.nn === '14' && content(n) === label)
    if (!node) throw new Error('Diagnostic action missing: ' + label)
    const target = { id: node.sid, dataset: { sid: node.sid } }
    page.eh({ type: 'tap', timeStamp: Date.now(), target, currentTarget: target, detail: {}, touches: [], changedTouches: [] })
  }, label)
  await delay(250)
}
async function capture(name) {
  if (process.env.C002_CAPTURE !== '1') return
  report.stage = 'capture-' + name; saveReport()
  await mini.screenshot({ path: path.join(out, name + '.png') })
}
;(async () => {
  try {
    mini = await automator.connect({ wsEndpoint: process.env.WECHAT_WS || 'ws://127.0.0.1:19422' })
    mini.on('exception', error => report.exceptions.push(String(error)))
    await launch('?preview=1')
    await waitFor('个性签名')
    report.environment = await mini.systemInfo()
    report.window = await mini.evaluate(() => wx.getWindowInfo())
    assert.doesNotMatch(await text(), /保密/)
    assert.match(await text(), /138\*\*\*\*5678/)
    assert.match(await text(), /10\/60/)
    await capture('normal')
    report.layout = await mini.evaluate(() => new Promise(resolve => {
      wx.createSelectorQuery().selectAll('.profile-header,.profile-title,.profile-card,.profile-avatar-image,.profile-nickname,.profile-signature,.profile-count,.profile-save,.profile-tabbar,.profile-tab image,.profile-tab text').fields({ rect: true, size: true, computedStyle: ['font-family','font-size','font-weight','line-height','color','background-color','border-radius'] }).exec(resolve)
    }))
    check('native initial render: two gender options, masked phone, source signature count')
    await event('profile-gender-FEMALE', 'tap')
    assert.ok((await snapshot()).find(n => n.id === 'profile-gender-FEMALE').cl.includes('is-selected'))
    await event('profile-nickname', 'input', { value: '新昵称' })
    await event('profile-signature', 'input', { value: '猫🐈' })
    await waitFor('2/60')
    await event('profile-save', 'tap')
    await waitFor('预览数据已更新')
    await capture('saved-preview')
    check('native input/gender/save callback updates preview; no backend success claim')
    await event('profile-nickname', 'input', { value: '' })
    await event('profile-save', 'tap')
    await waitFor('请填写昵称')
    await capture('validation')
    check('invalid nickname prevents save and keeps form visible')
    await launch('?preview=1&scenario=save-error')
    await waitFor('个性签名')
    await event('profile-signature', 'input', { value: '失败后保留' })
    await event('profile-save', 'tap')
    await waitFor('保存失败')
    await capture('save-failure')
    await event('profile-save', 'tap')
    await waitFor('预览数据已更新')
    check('failed save preserves draft; explicit retry succeeds in preview repository')
    await launch('?preview=1&scenario=load-error')
    await waitFor('加载失败')
    await capture('load-failure')
    await event('profile-retry-load', 'tap')
    await waitFor('个性签名')
    check('load failure and retry use real page state transitions')
    await launch('?preview=1&scenario=expired')
    await waitFor('登录已失效')
    assert.doesNotMatch(await text(), /138\*\*\*\*5678/)
    await capture('expired')
    await launch('')
    await waitFor('资料服务暂不可用')
    assert.doesNotMatch(await text(), /宠友小白/)
    check('expired and non-preview entry do not leak fixture or pretend real profile save')
    await launch('?preview=1')
    await waitFor('个性签名')
    const rects = (await mini.evaluate(() => new Promise(resolve => wx.createSelectorQuery().selectAll('.profile-card,.profile-save').boundingClientRect().exec(resolve))))[0]
    for (const r of rects) assert.ok(r.left >= 0 && r.right <= report.window.windowWidth, 'horizontal overflow')
    check('native measured form bounds stay inside actual viewport')
    await mini.callWxMethod('navigateTo', { url: '/consumer/pages/diagnostics/index' })
    await waitFor('内部上下文')
    await diagnosticAction('清除上下文和缓存')
    await mini.callWxMethod('navigateBack', {})
    await waitFor('登录已失效')
    assert.doesNotMatch(await text(), /138\*\*\*\*5678/)
    check('real shared scope invalidation clears the mounted profile and its draft')
    await mini.callWxMethod('navigateTo', { url: '/consumer/pages/diagnostics/index' })
    await waitFor('内部上下文')
    await diagnosticAction('重新注入样本上下文')
    await launch('?preview=1')
    await waitFor('个性签名')
    assert.equal(report.exceptions.length, 0)
    report.status = 'PASS_PREVIEW_INTERACTIONS_SINGLE_WINDOW'
  } catch (error) {
    report.status = 'FAIL'; report.error = String(error); process.exitCode = 1
  } finally { clearTimeout(timeout); saveReport(); if (mini) mini.disconnect(); console.log(report.status) }
})()
