/**
 * 轻量 Markdown 渲染（2026-11-20 用户裁决：协议采用 Markdown 轻标记——运营端纯文本编辑，
 * 小程序端渲染层次）。仅支持协议排版所需的极小子集：#/~三级标题、**加粗**、有序/无序列表、
 * 段落与换行；不解析链接/图片/HTML，天然免疫注入（富文本安全红线）。
 */

export type MarkdownBlock =
  | { kind: 'heading'; level: 1 | 2 | 3; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'list'; ordered: boolean; items: string[] }

/** 把 Markdown 文本解析为块级结构（内联加粗由样式层处理或按段保留原标记）。 */
export function parseMarkdownLite(source: string): MarkdownBlock[] {
  const blocks: MarkdownBlock[] = []
  if (!source) return blocks
  const lines = source.replace(/\r\n/g, '\n').split('\n')
  let paragraph: string[] = []
  let list: { ordered: boolean; items: string[] } | null = null
  const flushParagraph = () => {
    if (paragraph.length) {
      blocks.push({ kind: 'paragraph', text: paragraph.join('\n').trim() })
      paragraph = []
    }
  }
  const flushList = () => {
    if (list) {
      blocks.push({ kind: 'list', ordered: list.ordered, items: list.items })
      list = null
    }
  }
  for (const raw of lines) {
    const line = raw.trimEnd()
    if (!line.trim()) { flushParagraph(); flushList(); continue }
    const heading = /^(#{1,3})\s+(.*)$/.exec(line)
    if (heading) {
      flushParagraph(); flushList()
      blocks.push({ kind: 'heading', level: heading[1]!.length as 1 | 2 | 3, text: heading[2]!.trim() })
      continue
    }
    const ordered = /^\s*(\d+)[.、]\s+(.*)$/.exec(line)
    if (ordered) {
      flushParagraph()
      if (!list || !list.ordered) { flushList(); list = { ordered: true, items: [] } }
      list.items.push(ordered[2]!.trim())
      continue
    }
    const bullet = /^\s*[-*•]\s+(.*)$/.exec(line)
    if (bullet) {
      flushParagraph()
      if (!list || list.ordered) { flushList(); list = { ordered: false, items: [] } }
      list.items.push(bullet[1]!.trim())
      continue
    }
    flushList()
    paragraph.push(line)
  }
  flushParagraph(); flushList()
  return blocks
}
