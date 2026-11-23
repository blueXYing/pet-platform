import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

// 2026-11-19 用户裁决：审核中/已通过只展示等待审核卡片与单号，不再渲染（禁用的）表单，
// 避免用户误认为需要重新填写资料；仅 DRAFT 与 REJECTED 展示可编辑表单。
test('reviewing and approved applications hide the editable form', () => {
  const view = readFileSync(new URL('../pages/merchant-application/view.tsx', import.meta.url), 'utf8')
  assert.ok(view.includes("const formVisible = !p.loading && (!p.result || p.result.status === 'DRAFT' || p.result.status === 'REJECTED')"))
  assert.ok(view.includes("{formVisible && <View className='application-form'>"))
  assert.ok(view.includes("</View>}"))
  assert.ok(view.includes("{formVisible && <Button id='application-save'"))
  assert.ok(view.includes("<Button id='application-reload'"))
})

test('status card still covers reviewing, approved and rejected outcomes', () => {
  const view = readFileSync(new URL('../pages/merchant-application/view.tsx', import.meta.url), 'utf8')
  assert.ok(view.includes("p.result && p.result.status !== 'DRAFT'"))
  assert.ok(view.includes('申请审核中，请耐心等待'))
  assert.ok(view.includes('申请已审核通过，请阅读并签署商家协议'))
  assert.ok(view.includes('申请需修改，请根据审核意见完善后重新提交'))
  assert.ok(view.includes("id='application-signing-entry'"))
})