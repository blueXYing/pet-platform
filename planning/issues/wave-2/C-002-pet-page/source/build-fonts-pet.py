"""Build the pet pages' font subsets with the same pipeline and sources as the approved PR25 profile subsets.

The delivered profile subsets cover the profile page's static copy only; the pet pages introduce many
new glyphs (疫苗/驱虫/品种/芯片/豆豆/咪咪...). This script re-derives subsets from the same pinned
sources — Google Roboto, Noto Sans JP (CJK-first, matching the identified Figma fallback) and the local
Noto Sans SC variable font (SC fallback) — for the pet pages' full static copy. Profile subset files are
NOT copied or re-imported; families use distinct names (C002 Pet ...) so nothing is loaded twice.
Never rasterizes text; glyph outlines stay vector.
"""
import base64
import hashlib
import io
import json
import re
import urllib.parse
import urllib.request
from pathlib import Path
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parents[5]
PAGE = ROOT / 'frontend-miniapp/src/consumer/pages/pet-archive'
OUT = PAGE / 'assets/fonts'
OUT.mkdir(parents=True, exist_ok=True)
DESIGN = Path('C:/Users/Administrator/Desktop/宠物平台V1.0/planning/issues/wave-2/C-002-design-inputs/handoff/pages')

words = ''
for node in ['78-2817', '78-3076', '95-1481', '95-1844']:
    spec = json.loads((DESIGN / node / 'spec.json').read_text(encoding='utf-8'))
    words += ''.join(e.get('text', '') for e in spec['elements'] if e.get('disposition') == 'TEXT')
for source in ['index.tsx', 'detail.tsx', 'form.tsx', 'app-tabbar.tsx', 'list.css', 'detail.css', 'form.css', 'tabbar.css', 'index.config.ts', 'detail.config.ts', 'form.config.ts']:
    body = (PAGE / source).read_text(encoding='utf-8')
    # Block comments never render; dropping them keeps the subset to real copy (line comments stay, over-inclusion is safe).
    body = re.sub(r'/\*[\s\S]*?\*/', ' ', body)
    words += body
words += (ROOT / 'frontend-miniapp/src/consumer/pet/model.ts').read_text(encoding='utf-8')
chinese = ''.join(sorted({c for c in words if ord(c) > 127 and c != '🐈'}))
# U+00B7 middle dot is declared Roboto in the source frames and must not fall through to the CJK face.
ascii_text = ''.join(chr(c) for c in range(32, 127)) + '·'

faces = []
records = []


def emit(font, family, weight, chars, origin, source_hash):
    options = subset.Options(); options.name_IDs = ['*']; options.name_legacy = True; options.name_languages = ['*']
    worker = subset.Subsetter(options=options); worker.populate(text=chars); worker.subset(font)
    for record in font['name'].names:
        if record.nameID in [1, 2, 4, 6, 16, 17]:
            label = {400: 'Regular', 500: 'Medium', 700: 'Bold'}[weight]
            value = family
            if record.nameID in [2, 17]: value = label
            if record.nameID == 4: value = family + ' ' + label
            if record.nameID == 6: value = family.replace(' ', '') + '-' + label
            record.string = value.encode(record.getEncoding())
    buffer = io.BytesIO(); font.save(buffer); data = buffer.getvalue()
    filename = family.lower().replace(' ', '-') + f'-{weight}.ttf'; (OUT / filename).write_bytes(data)
    faces.append(f"@font-face {{ font-family: '{family}'; font-style: normal; font-weight: {weight}; src: url(data:font/ttf;base64,{base64.b64encode(data).decode()}) format('truetype'); }}")
    records.append({'family': family, 'weight': weight, 'file': filename, 'source': origin, 'sourceSha256': source_hash, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data), 'codePoints': [f'U+{ord(c):04X}' for c in chars]})


# Same local Noto Sans SC variable font identified by glyph-outline comparison in the profile closure.
# The stack renders Pet CJK (JP forms) first; Pet SC only needs the glyphs JP lacks.
noto_path = Path('C:/Windows/Fonts/NotoSansSC-VF.ttf')
noto_hash = hashlib.sha256(noto_path.read_bytes()).hexdigest()

jp_available: set[str] = set()
for weight in [400, 500, 700]:
    request = 'https://fonts.googleapis.com/css2?' + urllib.parse.urlencode({'family': f'Noto Sans JP:wght@{weight}', 'text': chinese})
    definition = urllib.request.urlopen(request, timeout=30).read().decode()
    url = re.search(r'url\(([^)]+)\)', definition)[1]
    data = urllib.request.urlopen(url, timeout=30).read()
    font = TTFont(io.BytesIO(data))
    jp_available |= {c for c in chinese if ord(c) in font.getBestCmap()}
jp_only_sc = ''.join(c for c in chinese if c not in jp_available)

for weight in [400, 500, 700]:
    font = instantiateVariableFont(TTFont(noto_path), {'wght': weight}, inplace=True)
    buffer = io.BytesIO(); font.save(buffer); buffer.seek(0); font = TTFont(buffer)
    emit(font, 'C002 Pet SC', weight, jp_only_sc or ' ', 'local NotoSansSC-VF.ttf; Google Fonts Noto Sans SC', noto_hash)

css = urllib.request.urlopen('https://fonts.googleapis.com/css2?family=Roboto:wght@400;500;700&display=swap', timeout=30).read().decode()
for block in re.findall(r'@font-face\s*\{(.*?)\}', css, re.S):
    weight = int(re.search(r'font-weight:\s*(\d+)', block)[1]); url = re.search(r'url\(([^)]+)\)', block)[1]
    data = urllib.request.urlopen(url, timeout=30).read()
    emit(TTFont(io.BytesIO(data)), 'C002 Pet Roboto', weight, ascii_text, url, hashlib.sha256(data).hexdigest())

missing_by_weight = {}
for weight in [400, 500, 700]:
    request = 'https://fonts.googleapis.com/css2?' + urllib.parse.urlencode({'family': f'Noto Sans JP:wght@{weight}', 'text': chinese})
    definition = urllib.request.urlopen(request, timeout=30).read().decode()
    url = re.search(r'url\(([^)]+)\)', definition)[1]
    data = urllib.request.urlopen(url, timeout=30).read()
    font = TTFont(io.BytesIO(data))
    missing = [c for c in chinese if ord(c) not in font.getBestCmap()]
    available = ''.join(c for c in chinese if c not in missing)
    missing_by_weight[weight] = missing
    emit(font, 'C002 Pet CJK', weight, available, url, hashlib.sha256(data).hexdigest())

for name in ['notosanssc', 'notosansjp', 'roboto']:
    (OUT / (name + '-OFL.txt')).write_bytes(urllib.request.urlopen('https://raw.githubusercontent.com/google/fonts/main/ofl/' + name + '/OFL.txt', timeout=30).read())
(PAGE / 'fonts.css').write_text('\n'.join(faces) + '\n', encoding='utf-8')
(OUT / 'manifest.json').write_text(json.dumps({
    'scope': 'Pet pages static copy + ASCII, same pipeline and sources as the PR25 profile subsets. '
             'Arbitrary user-entered CJK outside the subset still uses the platform fallback; no full-CJK cross-device claim.',
    'designNodes': ['78:2817', '78:3076', '95:1481', '95:1844'],
    'fonts': records,
    'missingInNotoSansJP': {str(k): v for k, v in missing_by_weight.items()},
}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Font subsets:', len(records), 'bytes:', sum(r['bytes'] for r in records), 'Chinese code points:', len(chinese))
print('Missing in JP (rendered via SC fallback):', {k: ''.join(v) for k, v in missing_by_weight.items()})
