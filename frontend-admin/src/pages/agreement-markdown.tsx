import { useMemo } from 'react';

/** 运营端协议预览：与小程序 markdown-lite 同一子集（# 标题 / **加粗** / 1. 列表 / 段落）。 */
export type MdBlock =
  | { kind: 'heading'; level: 1 | 2 | 3; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'list'; ordered: boolean; items: string[] };

export function markdownToBlocks(source: string): MdBlock[] {
  const blocks: MdBlock[] = [];
  if (!source) return blocks;
  let paragraph: string[] = [];
  let list: { ordered: boolean; items: string[] } | null = null;
  const flushP = () => { if (paragraph.length) { blocks.push({ kind: 'paragraph', text: paragraph.join('\n').trim() }); paragraph = []; } };
  const flushL = () => { if (list) { blocks.push({ kind: 'list', ...list }); list = null; } };
  for (const raw of source.replace(/\r\n/g, '\n').split('\n')) {
    const line = raw.trimEnd();
    if (!line.trim()) { flushP(); flushL(); continue; }
    const h = /^(#{1,3})\s+(.*)$/.exec(line);
    if (h) { flushP(); flushL(); blocks.push({ kind: 'heading', level: h[1]!.length as 1 | 2 | 3, text: h[2]! }); continue; }
    const o = /^\s*(\d+)[.、]\s+(.*)$/.exec(line);
    if (o) { flushP(); if (!list?.ordered) { flushL(); list = { ordered: true, items: [] }; } list.items.push(o[2]!); continue; }
    const b = /^\s*[-*•]\s+(.*)$/.exec(line);
    if (b) { flushP(); if (!list || list.ordered) { flushL(); list = { ordered: false, items: [] }; } list.items.push(b[1]!); continue; }
    flushL();
    paragraph.push(line);
  }
  flushP(); flushL();
  return blocks;
}

/** 行内加粗渲染（Web 端可真正加粗）。 */
function Bold({ text }: { text: string }) {
  const parts = text.split(/(\*\*[^*]+\*\*)/g).filter(Boolean);
  return <>{parts.map((part, i) => part.startsWith('**') && part.endsWith('**')
    ? <strong key={i}>{part.slice(2, -2)}</strong>
    : <span key={i}>{part}</span>)}</>;
}

export function AgreementMarkdownPreview({ source, compact }: { source: string; compact?: boolean }) {
  const blocks = useMemo(() => markdownToBlocks(source), [source]);
  return (
    <div style={compact ? {
      padding: 8, maxHeight: 120, overflowY: 'auto',
      display: 'flex', flexDirection: 'column', gap: 4,
      fontSize: 12, lineHeight: 1.5, color: '#374151',
    } : {
      marginTop: 12, padding: 16, border: '1px solid #e5e7eb', borderRadius: 8,
      background: '#fafbfd', maxHeight: 360, overflowY: 'auto',
      display: 'flex', flexDirection: 'column', gap: 8,
      fontSize: 13.5, lineHeight: 1.7, color: '#1f2937',
    }}>
      {!compact && <div style={{ fontSize: 12, color: '#8a94a6', marginBottom: 4 }}>—— 小程序端展示效果预览 ——</div>}
      {blocks.map((block, i) => block.kind === 'heading'
        ? <div key={i} style={{ fontWeight: 700, fontSize: compact ? (block.level === 1 ? 13.5 : block.level === 2 ? 12.5 : 12) : (block.level === 1 ? 17 : block.level === 2 ? 15.5 : 14.2), color: '#111827' }}><Bold text={block.text} /></div>
        : block.kind === 'list'
          ? <ol key={i} style={block.ordered ? { paddingLeft: 20, margin: 0 } : { paddingLeft: 16, margin: 0, listStyle: 'disc' }}>
              {block.items.map((item, j) => <li key={j}><Bold text={item} /></li>)}
            </ol>
          : <p key={i} style={{ margin: 0, whiteSpace: 'pre-wrap' }}><Bold text={block.text} /></p>)}
    </div>
  );
}
