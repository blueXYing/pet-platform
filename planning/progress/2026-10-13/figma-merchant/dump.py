#!/usr/bin/env python3
"""Dump one top-level frame from ~/.zcode/figma-cache/merchant-full.json as a readable tree
with fills / strokes / text / corner radius. Usage: python dump.py 23:19357 [max_depth]"""
import json, sys

FULL = r'C:/Users/Administrator/.zcode/figma-cache/merchant-full.json'
_cache = None

def load():
    global _cache
    if _cache is None:
        with open(FULL, encoding='utf-8') as f:
            _cache = json.load(f)
    return _cache

def find(node, nid):
    if node.get('id') == nid:
        return node
    for c in node.get('children', []) or []:
        r = find(c, nid)
        if r is not None:
            return r
    return None

def color(paint):
    if not paint:
        return ''
    if paint.get('type') == 'SOLID':
        c = paint.get('color', {})
        r, g, b, a = round(c.get('r', 0) * 255), round(c.get('g', 0) * 255), round(c.get('b', 0) * 255), round(c.get('a', 1), 3)
        s = f'#{r:02x}{g:02x}{b:02x}'
        if a < 1:
            s += f'@{a}'
        op = paint.get('opacity')
        if op is not None and op < 1:
            s += f'~{round(op,2)}'
        return s
    t = paint.get('type')
    stops = paint.get('gradientStops') or []
    return t + '(' + ','.join(color({'type': 'SOLID', 'color': s.get('color', {}), 'opacity': None}) + f'@{round(s.get("position",0),2)}' for s in stops) + ')' if t and 'GRADIENT' in t else (t or '?')

def fills_line(n):
    parts = []
    fl = n.get('fills')
    if fl:
        parts.append('fill=' + '|'.join(color(p) for p in fl if p.get('visible', True)))
    st = n.get('strokes')
    if st:
        sw = n.get('strokeWeight')
        parts.append('stroke=' + '|'.join(color(p) for p in st if p.get('visible', True)) + (f'/{sw}' if sw else ''))
    if n.get('cornerRadius'):
        parts.append(f'r={n["cornerRadius"]}')
    rs = n.get('rectangleCornerRadii')
    if rs:
        parts.append(f'r={rs}')
    for k in ('rowGap', 'columnGap', 'itemSpacing', 'paddingLeft', 'paddingRight', 'paddingTop', 'paddingBottom'):
        if n.get(k) is not None:
            parts.append(f'{k[:3]}={n[k]}')
    lay = n.get('layoutMode')
    if lay:
        parts.append(f'{lay}')
    return ('  [' + ' '.join(parts) + ']') if parts else ''

def rel(n, parent_box):
    bb = n.get('absoluteBoundingBox')
    if not bb:
        return (0.0, 0.0, 0.0, 0.0)
    if parent_box:
        return (bb['x'] - parent_box['x'], bb['y'] - parent_box['y'], bb['width'], bb['height'])
    return (bb['x'], bb['y'], bb['width'], bb['height'])

def dump(n, out, depth=0, max_depth=99, parent_box=None):
    ind = '  ' * depth
    t = n.get('type', '?')
    name = n.get('name', '')
    x, y, w, h = rel(n, parent_box)
    extra = fills_line(n)
    line = f'{ind}{t} "{name}" ({x:.1f},{y:.1f} {w}x{h}){extra}'
    if t == 'TEXT':
        st = n.get('style', {}) or {}
        segs = []
        ch = n.get('characters')
        if ch is not None:
            segs.append(repr(ch))
        fs = st.get('fontSize')
        fw = st.get('fontWeight')
        lh = st.get('lineHeightPx')
        fam = st.get('fontFamily')
        if fs: line += f' fs={fs}'
        if fw: line += f' fw={fw}'
        if lh: line += f' lh={lh}'
        if fam: line += f' {fam}'
        for ps in n.get('characterStyleOverrides') or []:
            pass
        fills = n.get('fills')
        if fills:
            line += ' col=' + '|'.join(color(p) for p in fills if p.get('visible', True))
        if ch:
            line += f' text={ch!r}'
    out.write(line + '\n')
    if depth < max_depth:
        pb = n.get('absoluteBoundingBox')
        for c in n.get('children', []) or []:
            dump(c, out, depth + 1, max_depth, pb)

if __name__ == '__main__':
    nid = sys.argv[1].replace('-', ':')
    md = int(sys.argv[2]) if len(sys.argv) > 2 else 99
    doc = load()['document']
    node = find(doc, nid)
    if node is None:
        print('NOT FOUND', nid); sys.exit(1)
    out = open(f'tree-{nid.replace(":", "-")}.txt', 'w', encoding='utf-8')
    dump(node, out, 0, md)
    out.close()
    print('written tree-' + nid.replace(':', '-') + '.txt')
