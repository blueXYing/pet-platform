// Capture the actual simulator, retaining raw screenshots and the measured component canvas.
const automator = require('miniprogram-automator')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const out = path.resolve(__dirname, '../../../../planning/issues/wave-2/C-002-profile/evidence')
const timer = setTimeout(() => { console.error('Capture timed out; ensure the project window is restored, not minimized.'); process.exit(1) }, 40000)
let mini
const endpoint = process.env.WECHAT_WS || 'ws://127.0.0.1:19424'
async function screenshot(name) {
  // The tool can retain a stale webview capture target after reLaunch on one connection.
  mini.disconnect()
  mini = await automator.connect({ wsEndpoint: endpoint })
  await mini.screenshot({ path: path.join(out, name) })
}
async function content() {
  return mini.evaluate(() => {
    function read(n) { return n ? (n.v || '') + (n.cn || []).map(read).join('') : '' }
    return read(getCurrentPages().at(-1)?.data.root)
  })
}
async function launch(query, expected) {
  await mini.callWxMethod('reLaunch', { url: '/consumer/pages/profile-edit/index' + query })
  const deadline = Date.now() + 8000
  while (!(await content()).includes(expected)) {
    if (Date.now() > deadline) throw new Error('Page did not become ready: ' + expected)
    await new Promise(resolve => setTimeout(resolve, 150))
  }
  // Native rendering and font/image decoding follow the React data update.
  await new Promise(resolve => setTimeout(resolve, 1500))
}
;(async () => {
  try {
    mini = await automator.connect({ wsEndpoint: endpoint })
    fs.mkdirSync(out, { recursive: true })
    const environment = await mini.systemInfo()
    await launch('?preview=1&referenceCanvas=1', '个性签名')
    const bounds = await mini.evaluate(() => new Promise(resolve => {
      wx.createSelectorQuery().selectAll('.profile-page,.profile-header,.profile-card,.profile-save,.profile-tabbar').boundingClientRect().exec(resolve)
    }))
    assert.equal(bounds[0][0].width, 402)
    assert.ok(Math.abs(bounds[0][0].height - 812) < .05)
    fs.writeFileSync(path.join(out, 'reference-canvas-bounds-final.json'), JSON.stringify(bounds, null, 2))
    console.log('capture reference canvas')
    await screenshot('reference-canvas-final-raw.png')
    await launch('?preview=1', '个性签名')
    console.log('capture device viewport')
    await screenshot('normal-final-414.png')
    await launch('?preview=1&scenario=load-error', '加载失败')
    console.log('capture load failure')
    await screenshot('load-failure-final.png')
    await launch('?preview=1&scenario=expired', '登录已失效')
    console.log('capture expired state')
    await screenshot('expired-final.png')
    await launch('?preview=1&referenceCanvas=1', '个性签名')
    fs.writeFileSync(path.join(out, 'capture-final.json'), JSON.stringify({ status: 'CAPTURED', source: 'actual WeChat DevTools simulator', environment, canvas: { width: 402, height: 812, mode: 'same component in fixed design canvas; device APIs unmodified' }, visualAcceptance: 'REQUIRES_COMPARISON_AND_REVIEW' }, null, 2))
    console.log('Captured reference canvas, actual device viewport, load failure, and expired state.')
  } catch (error) { console.error(error); process.exitCode = 1 }
  finally { clearTimeout(timer); if (mini) mini.disconnect() }
})()
