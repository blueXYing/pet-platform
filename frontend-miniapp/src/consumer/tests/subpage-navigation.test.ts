import test from 'node:test'
import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

// 2026-10-26 用户裁决：只有五个根 tab 页保留原生 tabBar；非根页实际产品不显示
// ConsumerBottomNavigation。preview/reference 设计验收通道保留自定义底栏用于 Figma 对照。
// 静态门禁：consumer/pages 下每一处 navigation prop 都必须以 `preview ?` 条件传入，
// 防止无条件的全局底栏回归。
const pagesRoot = fileURLToPath(new URL('../pages', import.meta.url))

function pageSources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) return pageSources(full)
    return entry.isFile() && entry.name.endsWith('.tsx') ? [full] : []
  })
}

test('non-root pages only pass the custom bottom navigation on preview routes', () => {
  const offenders: string[] = []
  for (const file of pageSources(pagesRoot)) {
    const source = readFileSync(file, 'utf8')
    for (const match of source.matchAll(/navigation=\{/g)) {
      const index = match.index ?? 0
      if (!/^navigation=\{\s*preview\s*\?/.test(source.slice(index))) {
        const line = source.slice(0, index).split('\n').length
        offenders.push(`${path.relative(pagesRoot, file)}:${line}`)
      }
    }
  }
  assert.deepEqual(offenders, [], `unconditional bottom navigation found:\n${offenders.join('\n')}`)
})
