"""Produce auditable pixel comparisons for the pet pages; never turn a score into a VIS pass.

References: the detail page uses the official 1x frame render copied from the design handoff;
the list module region and both form frames use spec-composited references (build-reference.py)
because the package contains no official render for those nodes — this gap is registered.

Actuals: scroll-stitched 402-wide reference-canvas captures produced by pet-capture.cjs; the
stitch crops every tile at the measured canvas position, never rescales the simulator output,
and keeps raw tiles alongside the stitched image.
"""
from pathlib import Path
import hashlib
import json
import os
from PIL import Image, ImageChops, ImageEnhance
import numpy as np

HERE = Path(__file__).resolve().parent
SOURCE = HERE / 'source'
EVIDENCE = Path(os.environ.get('PET_EVIDENCE_DIR', str(HERE / 'evidence')))

STATES = [
    {'name': 'list', 'reference': 'reference-list-402x312.png', 'canvas': (402, 312),
     'regions': {'header': (40, 50, 360, 85), 'card1': (50, 95, 352, 164), 'card2': (50, 180, 352, 249), 'full': (0, 0, 402, 312)}},
    {'name': 'detail', 'reference': 'reference-detail-402x1252.png', 'canvas': (402, 1252), 'official': True,
     'regions': {'header': (0, 35, 402, 86), 'identity': (53, 105, 355, 213), 'basic-info': (68, 234, 355, 429),
                 'vaccine-records': (30, 486, 372, 770), 'deworm-records': (30, 828, 372, 1000),
                 'health-note': (30, 1060, 372, 1140), 'tabbar': (0, 1176, 402, 1252), 'full': (0, 0, 402, 1252)}},
    {'name': 'form-brother', 'reference': 'reference-form-brother-402x1066.png', 'canvas': (402, 1066),
     'regions': {'title': (29, 103, 380, 190), 'name-breed': (29, 213, 372, 357), 'sex': (29, 373, 372, 439),
                 'age-birth': (29, 455, 372, 601), 'weight-chip': (29, 617, 372, 761), 'note': (29, 777, 372, 881),
                 'buttons': (29, 907, 372, 947), 'tabbar': (0, 990, 402, 1066), 'full': (0, 0, 402, 1066)}},
    {'name': 'form-sister', 'reference': 'reference-form-sister-402x1067.png', 'canvas': (402, 1067),
     'regions': {'title': (29, 103, 380, 190), 'name-breed': (29, 213, 372, 357), 'sex': (29, 373, 372, 439),
                 'age-birth': (29, 455, 372, 601), 'weight-chip': (29, 617, 372, 761), 'note': (29, 777, 372, 881),
                 'buttons': (29, 907, 372, 947), 'tabbar': (0, 991, 402, 1067), 'full': (0, 0, 402, 1067)}},
]


def stitch(state_name, width, canvas_w, canvas_h):
    meta = json.loads((EVIDENCE / f'reference-{state_name}-raw-{width}-tiles.json').read_text(encoding='utf-8'))
    stitched = Image.new('RGB', (canvas_w, canvas_h), 'white')
    # DevTools 2.02 simulator_screenshot outputs the simulator panel at its fit scale (no 1:1
    # channel since the old CLI was retired with the update). Tiles are resampled from the panel
    # scale to the 402 design canvas; fixed device chrome (notch/status/capsule, top ~100 rows)
    # is skipped for every scrolled tile; recorded scrolls are the effective (clamped) offsets.
    for tile in meta['tiles']:
        image = Image.open(EVIDENCE / tile['file']).convert('RGB')
        shot_w = tile.get('screenshotWidth') or image.width
        # Panel px = CSS px * k (panel fit scale); canvas rows are 1:1 CSS in the webview, so the
        # crop is resampled by 1/k back to CSS rows and pasted at its CSS row. The canvas must fit
        # the window (402 <= windowWidth) — reference canvases are therefore captured at the 414
        # window; at 390 the viewport would clip the canvas's last 12 CSS columns.
        k = shot_w / meta['windowWidth']
        effective = tile['scrollTop']
        top_css = effective + tile['cropTopCss']
        rows_css = tile['cropBottomCss'] - tile['cropTopCss']
        top_shot = round(tile['canvasTopInScreenshot'] * k) + round(tile['cropTopCss'] * k)
        h_shot = round(rows_css * k)
        visible_w = min(canvas_w, meta['windowWidth'])
        crop = image.crop((0, top_shot, round(visible_w * k), top_shot + h_shot))
        crop = crop.resize((visible_w, round(h_shot / k)), Image.LANCZOS)
        stitched.paste(crop, (0, round(top_css)))
    out = EVIDENCE / f'reference-{state_name}-stitched-{width}.png'
    stitched.save(out)
    return stitched


def main(width):
    report = {'windowWidth': width, 'method': 'Crop-only comparison of the stitched reference canvas; raw tiles retained. '
              'Detail reference is the official handoff frame render; list and form references are spec composites '
              '(registered gap). Metrics locate differences; no similarity threshold defines a pass.',
              'visualAcceptance': 'PENDING_REVIEW', 'states': {}}
    for state in STATES:
        name = state['name']
        ref = Image.open(SOURCE / state['reference']).convert('RGB')
        actual = stitch(name, width, *state['canvas'])
        if actual.size != ref.size:
            raise SystemExit(f'{name}: actual {actual.size} != reference {ref.size}')
        prefix = f'{name}-{width}'
        Image.blend(ref, actual, .5).save(EVIDENCE / f'overlay-{prefix}.png')
        diff = ImageChops.difference(ref, actual)
        ImageEnhance.Contrast(diff).enhance(4).save(EVIDENCE / f'difference-x4-{prefix}.png')
        side = Image.new('RGB', (ref.width * 2 + 8, ref.height), 'white')
        side.paste(ref, (0, 0)); side.paste(actual, (ref.width + 8, 0))
        side.save(EVIDENCE / f'source-and-actual-{prefix}.png')
        a = np.asarray(actual, dtype=float)
        r = np.asarray(ref, dtype=float)
        delta = np.abs(a - r)
        # Device chrome (notch, status text, menu capsule at top; home indicator at bottom)
        # overlays the real canvas but does not exist in the design frame — excluded like
        # PR25's recorded-difference exclusion.
        mask = np.ones(delta.shape[:2], dtype=bool)
        mask[0:100, :] = False
        mask[delta.shape[0] - 48:delta.shape[0], :] = False
        # At the 390 window the 402-design canvas is clipped by the viewport: the columns beyond
        # the window width are never rendered, so they carry no signal.
        meta_path = EVIDENCE / f'reference-{name}-raw-{width}-tiles.json'
        if meta_path.exists():
            window_width = json.loads(meta_path.read_text(encoding='utf-8'))['windowWidth']
            if window_width < delta.shape[1]:
                mask[:, window_width:delta.shape[1]] = False
        regions = {}
        for region, (x0, y0, x1, y1) in state['regions'].items():
            d = delta[y0:y1, x0:x1][mask[y0:y1, x0:x1]]
            if d.size == 0:
                regions[region] = {'excluded': 'device-chrome rows'}
                continue
            regions[region] = {
                'meanAbsoluteChannelDifference': float(d.mean()),
                'fractionPixelsWithAnyChannelDifferenceOver16': float(np.mean(d.max(1) > 16)),
            }
        report['states'][name] = {
            'reference': state['reference'],
            'referenceIsOfficialFrameRender': bool(state.get('official')),
            'referenceSha256': hashlib.sha256((SOURCE / state['reference']).read_bytes()).hexdigest(),
            'stitchedActualSha256': hashlib.sha256((EVIDENCE / f'reference-{name}-stitched-{width}.png').read_bytes()).hexdigest(),
            'regions': regions,
        }
    (EVIDENCE / f'visual-comparison-{width}.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for name, data in report['states'].items():
        scored = {k: v for k, v in data['regions'].items() if 'meanAbsoluteChannelDifference' in v}
        worst = max(scored.items(), key=lambda kv: kv[1]['meanAbsoluteChannelDifference'])
        print(f"{name}: worst region {worst[0]} meanAbsDiff={worst[1]['meanAbsoluteChannelDifference']:.2f}")


if __name__ == '__main__':
    import sys
    main(sys.argv[1] if len(sys.argv) > 1 else '390')
