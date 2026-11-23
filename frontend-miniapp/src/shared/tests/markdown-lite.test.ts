import test from 'node:test'
import assert from 'node:assert/strict'
import { parseMarkdownLite } from '../markdown-lite'

test('markdown-lite 解析标题/列表/段落/加粗剥离由调用方处理', () => {
  const blocks = parseMarkdownLite('# 大标题\n\n## 第二章 权利义务\n\n1. 第一条 服务范围\n2. 第二条 收费标准\n\n- 免责说明\n\n普通段落一行。')
  assert.deepEqual(blocks.map(b => b.kind), ['heading', 'heading', 'list', 'list', 'paragraph'])
  assert.equal(blocks[0].kind === 'heading' && blocks[0].level, 1)
  const list = blocks[2]
  assert.equal(list.kind === 'list' && list.ordered, true)
  assert.equal(list.kind === 'list' && list.items.length, 2)
  // 空串与纯空行安全
  assert.deepEqual(parseMarkdownLite(''), [])
  assert.deepEqual(parseMarkdownLite('\n\n \n').length, 0)
})
