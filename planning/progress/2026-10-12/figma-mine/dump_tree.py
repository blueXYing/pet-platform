# -*- coding: utf-8 -*-
"""Dump Figma node JSON to readable indented tree with style details."""
import json, sys, io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def color_of(c):
    if not isinstance(c, dict):
        return str(c)
    r, g, b, a = c.get('r', 0), c.get('g', 0), c.get('b', 0), c.get('a', 1)
    hx = '#%02x%02x%02x' % (round(r*255), round(g*255), round(b*255))
    if a < 1:
        hx += ' a=%.2f' % a
    return hx

def paint(p):
    if not isinstance(p, dict):
        return str(p)
    t = p.get('type')
    visible = p.get('visible', True)
    op = p.get('opacity', 1)
    out = t
    if t == 'SOLID':
        out += ':' + color_of(p.get('color', {}))
        if op != 1: out += ' op=%.2f' % op
    elif t == 'GRADIENT_LINEAR':
        stops = p.get('gradientStops', [])
        out += ':[' + ','.join('%s@%.2f' % (color_of(s.get('color', {})), s.get('position', 0)) for s in stops) + ']'
        if 'gradientTransform' in p:
            gt = p['gradientTransform']
            # rotation heuristic from transform
            import math
            ang = math.degrees(math.atan2(gt[1][0] if len(gt)>1 else 0, gt[0][0] if gt else 1))
            out += ' ang~%.0f' % ang
    elif t == 'IMAGE':
        out += ':imgRef=%s' % p.get('imageRef', '?')
        if p.get('scaleMode'): out += '/%s' % p['scaleMode']
    elif t == 'DROP_SHADOW':
        e = p.get('effects', [])
        out += str(e)
    if not visible:
        out += ' [hidden]'
    return out

def fills_str(node):
    fills = node.get('fills')
    if fills == 'EMPTY':
        return 'none'
    if not isinstance(fills, list):
        return ''
    vis = [paint(f) for f in fills if isinstance(f, dict) and f.get('visible', True)]
    return ','.join(vis) if vis else ''

def strokes_str(node):
    st = node.get('strokes')
    if st == 'EMPTY':
        return ''
    if isinstance(st, list):
        vis = [paint(s) for s in st if isinstance(s, dict) and s.get('visible', True)]
        if vis:
            sw = node.get('strokeWeight')
            sa = node.get('strokeAlign', 'INSIDE')
            sd = node.get('strokeDashes')
            return 'stroke %s w=%s %s%s' % (','.join(vis), sw, sa, ' dash' if sd else '')
    return ''

def effects_str(node):
    eff = node.get('effects')
    if eff == 'EMPTY' or not isinstance(eff, list):
        return ''
    out = []
    for e in eff:
        if not isinstance(e, dict) or not e.get('visible', True):
            continue
        t = e.get('type')
        if t == 'DROP_SHADOW':
            out.append('shadow[%s,%s,%s,%s,%s%s]' % (e.get('offset',{}).get('x'), e.get('offset',{}).get('y'), e.get('radius'), color_of(e.get('color',{})), e.get('spread',0), ' inner' if e.get('inner') else ''))
        elif t == 'LAYER_BLUR':
            out.append('blur(%s)' % e.get('radius'))
        elif t == 'BACKGROUND_BLUR':
            out.append('bgblur(%s)' % e.get('radius'))
    return ' '.join(out)

def radius_str(node):
    parts = []
    if 'cornerRadius' in node and node['cornerRadius']:
        parts.append(str(node['cornerRadius']))
    rc = node.get('rectangleCornerRadii')
    if rc:
        parts.append('rect[%s]' % ','.join(str(x) for x in rc))
    return '/'.join(parts)

def abs_box(node):
    bb = node.get('absoluteBoundingBox')
    if not bb:
        return ''
    return '%.0fx%.0f@%.0f,%.0f' % (bb.get('width',0), bb.get('height',0), bb.get('x',0), bb.get('y',0))

def line(node, depth):
    t = node.get('type')
    name = node.get('name', '')
    parts = ['  '*depth + '%s %r' % (t, name)]
    box = abs_box(node)
    if box: parts.append(box)
    # layout
    if t == 'FRAME' or 'layoutMode' in node:
        lm = node.get('layoutMode')
        if lm:
            pad = node.get('paddingLeft'), node.get('paddingRight'), node.get('paddingTop'), node.get('paddingBottom')
            parts.append('auto:%s gap=%s pad[LRTB]=[%s,%s,%s,%s] align=%s/%s' % (lm, node.get('itemSpacing'), pad[0], pad[1], pad[2], pad[3], node.get('primaryAxisAlignItems','-'), node.get('counterAxisAlignItems','-')))
    f = fills_str(node)
    if f: parts.append('fill=' + f)
    s = strokes_str(node)
    if s: parts.append(s)
    r = radius_str(node)
    if r: parts.append('r=' + r)
    ef = effects_str(node)
    if ef: parts.append(ef)
    op = node.get('opacity')
    if op is not None and op != 1: parts.append('op=%.2f' % op)
    rot = node.get('rotation')
    if rot: parts.append('rot=%.1f' % rot)
    if t == 'TEXT':
        st = node.get('style', {})
        parts.append('text=%r' % node.get('characters', ''))
        parts.append('font=%s w=%s sz=%s lh=%s ls=%s' % (st.get('fontFamily'), st.get('fontWeight'), st.get('fontSize'), st.get('lineHeightPx'), st.get('letterSpacing')))
        tf = fills_str(node)
        if tf: parts.append('tcolor=' + tf)
        ta = st.get('textAlignHorizontal')
        if ta and ta != 'LEFT': parts.append('ta=%s' % ta)
        tt = node.get('characterStyleOverrides')
        if tt: parts.append('styleOverrides=%s' % tt[:8])
    lineheight = parts[0]
    for p in parts[1:]:
        lineheight += ' | ' + p
    return lineheight

def walk(node, depth, out):
    t = node.get('type')
    if t in ('VECTOR', 'STAR', 'LINE', 'ELLIPSE', 'REGULAR_POLYGON') and not node.get('children'):
        # summarize simple vectors compactly
        out.append(line(node, depth))
        return
    out.append(line(node, depth))
    for ch in node.get('children', []):
        walk(ch, depth+1, out)

def dump(frame_key, doc, fp):
    out = ['==== %s %r ====' % (frame_key, doc.get('name'))]
    walk(doc, 0, out)
    with open(fp, 'w', encoding='utf-8') as f:
        f.write('\n'.join(out))
    print('%s -> %s (%d lines)' % (frame_key, fp, len(out)))

data = json.load(open('nodes-all.json', encoding='utf-8'))
frames = {'126:675': None}
for k, v in data['nodes'].items():
    frames[k] = v.get('document')

single = json.load(open('node-126-675.json', encoding='utf-8'))
if '126:675' in single.get('nodes', {}):
    frames['126:675'] = single['nodes']['126:675'].get('document')

slug = {
    '126:675': 'tree-126-675-mine.txt',
    '127:1652': 'tree-127-1652-profile-edit.txt',
    '128:2078': 'tree-128-2078-coupons.txt',
    '129:8174': 'tree-129-8174-points.txt',
    '688:371': 'tree-688-371-pet-home.txt',
    '78:3076': 'tree-78-3076-pet-detail.txt',
    '95:1481': 'tree-95-1481-pet-form-male.txt',
    '95:1844': 'tree-95-1844-pet-form-female.txt',
}
for k, doc in frames.items():
    if doc:
        dump(k, doc, slug[k])
