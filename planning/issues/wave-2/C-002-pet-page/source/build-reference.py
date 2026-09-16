"""Compose spec-faithful 1x reference images for the pet pages.

The design handoff provides an official 1x frame render only for 78:3076 (copied verbatim).
For 78:2817's pet-archive module region and the 95:1481/95:1844 form frames no official render
exists in the package, so this script composites them strictly from pinned inputs: geometry,
colors and text styles come from spec.json, rasters are the official 2x element exports, and
text is drawn with the delivered subset fonts. This is a comparison aid, not an official
design render; the gap is registered in HANDOFF.md and VISUAL_ACCEPTANCE.json.
"""
import json
import shutil
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[5]
PET = ROOT / 'frontend-miniapp/src/consumer/pages/pet-archive'
OUT = Path(__file__).resolve().parent
HANDOFF = Path('C:/Users/Administrator/Desktop/宠物平台V1.0/planning/issues/wave-2/C-002-design-inputs/handoff')
ASSETS = HANDOFF / 'assets'
FONTS = PET / 'assets/fonts'

_canonical = {}  # canonical hex color -> int RGBA


def color_int(c):
    r, g, b = round(c.get('r', 0) * 255), round(c.get('g', 0) * 255), round(c.get('b', 0) * 255)
    return (r, g, b, 255)


_font_cache = {}


def load_font(kind, weight, size):
    key = (kind, weight, round(size * 4))
    if key not in _font_cache:
        name = {('roboto', 400): 'c002-pet-roboto-400.ttf', ('roboto', 500): 'c002-pet-roboto-500.ttf',
                ('roboto', 700): 'c002-pet-roboto-700.ttf',
                ('cjk', 400): 'c002-pet-cjk-400.ttf', ('cjk', 500): 'c002-pet-cjk-500.ttf', ('cjk', 700): 'c002-pet-cjk-700.ttf',
                ('sc', 400): 'c002-pet-sc-400.ttf', ('sc', 500): 'c002-pet-sc-500.ttf', ('sc', 700): 'c002-pet-sc-700.ttf'}[(kind, weight)]
        _font_cache[key] = ImageFont.truetype(str(FONTS / name), size * 4)
    return _font_cache[key]


def glyph_ok(font, ch):
    try:
        return font.getmask(ch, mode='L').getbbox() is not None
    except Exception:
        return False


def pick_font(ch, weight, size):
    roboto = load_font('roboto', weight, size)
    if (ord(ch) < 127 or ch == '·') and glyph_ok(roboto, ch):
        return roboto
    cjk = load_font('cjk', weight, size)
    if glyph_ok(cjk, ch):
        return cjk
    return load_font('sc', weight, size)


def wrap_text(text, weight, size, max_width):
    lines, line, width = [], '', 0.0
    for ch in text:
        w = pick_font(ch, weight, size).getlength(ch) / 4
        if line and width + w > max_width:
            lines.append(line); line, width = ch, w
        else:
            line += ch; width += w
    if line:
        lines.append(line)
    return lines or ['']


def draw_text_node(draw, el, ox, oy, scale):
    ts = el['textStyle']
    st = el.get('style') or {}
    b = el['pageBounds']
    size = ts['fontSize']
    weight = ts.get('fontWeight') or 400
    line_height = ts.get('lineHeightPx') or size * 1.2
    color = (0, 0, 0, 255)
    for fill in st.get('fills') or []:
        if fill.get('type') == 'SOLID':
            color = color_int(fill['color'])
            break
    probe = load_font('cjk', weight, size)
    ascent, descent = probe.getmetrics()
    first_baseline = (b['y'] - oy) + (line_height - (ascent + descent) / 4) / 2 + ascent / 4
    lines = wrap_text(el['text'], weight, size, b['width']) if b['width'] and b['width'] < 340 and len(el['text']) > 24 else [el['text']]
    for index, line in enumerate(lines):
        baseline = (first_baseline + index * line_height) * scale * 4
        x = (b['x'] - ox) * scale * 4
        if ts.get('textAlignHorizontal') == 'CENTER':
            total = sum(pick_font(ch, weight, size).getlength(ch) for ch in line)
            x += (b['width'] * scale * 4 - total) / 2
        for ch in line:
            font = pick_font(ch, weight, size)
            draw.text((x, baseline), ch, font=font, fill=color, anchor='ls')
            x += font.getlength(ch)


def rounded(draw, box, radius, fill, outline, width):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def render(node_dir, region, out_name, exclude_nodes=()):
    spec = json.loads((node_dir / 'spec.json').read_text(encoding='utf-8'))
    assets = {a['nodeId']: a for a in json.loads((node_dir / 'assets.json').read_text(encoding='utf-8'))}
    els = spec['elements']
    by_id = {e['id']: e for e in els}
    ox, oy, x1, y1 = region[0], region[1], region[2], region[3]
    W, H = round((x1 - ox)), round((y1 - oy))
    img = Image.new('RGB', (W * 4, H * 4), (255, 255, 255))
    draw = ImageDraw.Draw(img)
    asset_children = set()
    for e in els:
        p = e.get('parentId')
        if p and p in by_id and by_id[p].get('disposition') == 'ASSET':
            asset_children.add(e['id'])
    pasted = set()
    for e in els:
        if e['id'] in asset_children or e['id'] in pasted:
            continue
        dis = e.get('disposition')
        if dis in ('EXCLUDED_HIDDEN_OR_OUTSIDE_CLIP',) or e['id'] in exclude_nodes:
            continue
        b = e.get('pageBounds')
        if not b:
            continue
        ex, ey = b['x'], b['y']
        if ex + b['width'] < ox or ex > x1 or ey + b['height'] < oy or ey > y1:
            continue
        if dis == 'ASSET' and e['id'] in assets:
            file = ASSETS / (assets[e['id']]['assetKey'] + '@2x.png')
            if file.exists():
                raster = Image.open(file).convert('RGBA')
                w_px = max(1, round(b['width'] * 4))
                h_px = max(1, round(b['height'] * 4))
                raster = raster.resize((w_px, h_px), Image.LANCZOS)
                img.paste(raster, (round((ex - ox) * 4), round((ey - oy) * 4)), raster)
                pasted.add(e['id'])
            continue
        st = e.get('style') or {}
        if e.get('type') == 'TEXT' and e.get('textStyle'):
            draw_text_node(draw, e, ox, oy, 1)
            continue
        box = [(ex - ox) * 4, (ey - oy) * 4, (ex - ox + b['width']) * 4, (ey - oy + b['height']) * 4]
        if e.get('type') == 'LINE' or (e.get('type') == 'VECTOR' and st.get('strokes') and not st.get('fills') and (b['width'] < 2 or b['height'] < 2)):
            stroke = next((color_int(s['color']) for s in st.get('strokes') or [] if s.get('type') == 'SOLID'), None)
            if stroke:
                draw.rectangle(box, fill=stroke)
            continue
        fill = next(((color_int(f['color']), None) for f in st.get('fills') or [] if f.get('type') == 'SOLID'), None)
        stroke = next((color_int(s['color']) for s in st.get('strokes') or [] if s.get('type') == 'SOLID'), None)
        radius = st.get('cornerRadius') or 0
        if isinstance(radius, list):
            radius = max(radius) if radius else 0
        max_r = min(box[2] - box[0], box[3] - box[1]) / 2
        radius = min(radius * 4, max_r)
        outline = stroke if stroke else None
        width = max(1, round((st.get('strokeWeight') or 1) * 4)) if outline else 0
        if fill or outline:
            rounded(draw, box, radius, fill[0] if fill else None, outline, width)
    img = img.resize((W, H), Image.LANCZOS)
    img.save(OUT / out_name)
    print(out_name, (W, H))


def main():
    # Official render for the detail page is copied verbatim from the handoff.
    official = HANDOFF.parent / 'reference-frames/78-3076@1x.png'
    shutil.copyfile(official, OUT / 'reference-detail-402x1252.png')
    print('reference-detail-402x1252.png (official frame render) copied')
    # List page: pet-archive module region of the home frame; the second home backdrop
    # segment (78:2838) is excluded from both implementation and reference (registered).
    render(HANDOFF / 'pages/78-2817', (0, 282, 402, 594), 'reference-list-402x312.png', exclude_nodes={'78:2838'})
    render(HANDOFF / 'pages/95-1481', (0, 0, 402, 1066), 'reference-form-brother-402x1066.png')
    render(HANDOFF / 'pages/95-1844', (0, 0, 402, 1067), 'reference-form-sister-402x1067.png')


if __name__ == '__main__':
    main()
