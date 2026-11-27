import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { decodeOrderDetail } from '../orders/model'
import { PAY_METHOD_WECHAT, paySummaryLabels } from '../components/pay-sheet/labels'

// 确认支付半屏（Figma 690:4837，43 号裁决 2026-11-27）：订单详情携带下单时的快照名称、固定的微信支付
// 一行（不做支付方式选择）、预约回执与订单详情两个入口都在当前页弹出。

const componentRoot = fileURLToPath(new URL('../components/pay-sheet/', import.meta.url))
const read = (file: string) => readFileSync(path.join(componentRoot, file), 'utf8')
const stripComments = (src: string) => src.replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, '')

const detail = {
  orderId: '900101001990000', orderNo: '2026100100001', displayStatus: 'PENDING_PAYMENT', payAmount: '88.00',
  appointmentStart: '2026-10-10T14:00:00.000+08:00', appointmentEnd: '2026-10-10T15:30:00.000+08:00',
}

test('order detail decodes the creation-time snapshot names', () => {
  const view = decodeOrderDetail({ ...detail, storeName: '萌宠之家宠物店', serviceName: '上门喂养' })
  assert.equal(view.storeName, '萌宠之家宠物店')
  assert.equal(view.serviceName, '上门喂养')
  // 列表项与无快照值的订单：键缺失与显式 null 都读作 null，不失败关闭。
  assert.equal(decodeOrderDetail(detail).storeName, null)
  assert.equal(decodeOrderDetail({ ...detail, storeName: null, serviceName: null }).serviceName, null)
})

test('order detail still fails closed on malformed snapshot names', () => {
  assert.throws(() => decodeOrderDetail({ ...detail, storeName: '' }), /INVALID_RESPONSE/)
  assert.throws(() => decodeOrderDetail({ ...detail, storeName: '   ' }), /INVALID_RESPONSE/)
  assert.throws(() => decodeOrderDetail({ ...detail, serviceName: 42 }), /INVALID_RESPONSE/)
  assert.throws(() => decodeOrderDetail({ ...detail, serviceName: 'x'.repeat(129) }), /INVALID_RESPONSE/)
})

test('summary labels fall back when the order carries no snapshot names', () => {
  assert.deepEqual(paySummaryLabels({ storeName: '萌宠之家宠物店', serviceName: '上门喂养' }),
    { store: '萌宠之家宠物店', service: '上门喂养' })
  assert.deepEqual(paySummaryLabels({ storeName: null, serviceName: null }),
    { store: '预约服务订单', service: '预约服务' })
  assert.equal(PAY_METHOD_WECHAT, '微信支付')
})

test('wechat icon is the original exported asset, byte for byte', () => {
  const manifest = JSON.parse(read('assets/manifest.json')) as {
    assets: { nodeId: string; file: string; sha256: string; bytes: number }[]
  }
  assert.ok(manifest.assets.length > 0)
  for (const asset of manifest.assets) {
    const relative = path.relative(path.resolve(fileURLToPath(new URL('../../../..', import.meta.url))),
      path.resolve(componentRoot, 'assets', path.basename(asset.file))).replace(/\\/g, '/')
    assert.equal(relative, asset.file, 'manifest file must point at the packaged asset')
    const bytes = readFileSync(path.join(componentRoot, 'assets', path.basename(asset.file)))
    assert.equal(createHash('sha256').update(bytes).digest('hex'), asset.sha256, asset.file)
    assert.equal(bytes.byteLength, asset.bytes, asset.file)
  }
})

test('pay sheet renders one fixed WeChat method row and no channel choice', () => {
  const sheet = stripComments(read('pay-sheet.tsx'))
  assert.doesNotMatch(sheet, /支付宝/)
  assert.equal((sheet.match(/className='bps-method'/g) ?? []).length, 1)
  // 支付方式行是只读展示（View），不是可切换的按钮。
  assert.match(sheet, /className='bps-method' ariaLabel=\{`支付方式 \$\{PAY_METHOD_WECHAT\}（已选中）`\}/)
})

test('both pay entries open the sheet in place instead of navigating to the pay page', () => {
  const bookingSheet = stripComments(readFileSync(path.join(componentRoot, '../booking-sheet/booking-sheet.tsx'), 'utf8'))
  const orderDetail = stripComments(readFileSync(path.join(componentRoot, '..', '..', 'pages', 'orders', 'detail.tsx'), 'utf8'))
  assert.doesNotMatch(bookingSheet, /booking\/pay/)
  assert.doesNotMatch(orderDetail, /booking\/pay/)
  assert.match(bookingSheet, /<PaySheet\b/)
  assert.match(orderDetail, /<PaySheet\b/)
  // 两个入口的按钮 id 保持不变（回执态「去支付」、详情页「去支付」）。
  assert.match(bookingSheet, /id=\{`\$\{idPrefix\}-go-pay`\}/)
  assert.match(orderDetail, /id='ord-go-pay'/)
})
