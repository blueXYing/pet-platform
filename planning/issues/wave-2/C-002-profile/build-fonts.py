"""Reproduce the pinned representative-page font subsets; never rasterize text.
Requires fontTools and the local Noto Sans SC variable font identified by glyph outline.
"""
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.request
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.recordingPen import RecordingPen

ROOT = Path(__file__).resolve().parents[4]
PAGE = ROOT / 'frontend-miniapp/src/consumer/pages/profile-edit'
OUT = ROOT / 'frontend-miniapp/src/consumer/assets/profile/fonts'
OUT.mkdir(parents=True, exist_ok=True)
source = Path(__file__).parent / 'source'
spec = json.loads((source / 'spec.json').read_text(encoding='utf-8'))
words = ''.join(e.get('text','') for e in spec['elements'] if e['disposition']=='TEXT')
words += (PAGE/'index.tsx').read_text(encoding='utf-8')
words += (ROOT/'frontend-miniapp/src/consumer/profile/model.ts').read_text(encoding='utf-8')
words += '新昵称失败后保留重试男女人测试'
chinese = ''.join(sorted({c for c in words if ord(c)>127 and c!='🐈'}))
ascii_text = ''.join(chr(c) for c in range(32,127))
faces=[];records=[]
def emit(font, family, weight, chars, origin, source_hash):
    options=subset.Options();options.name_IDs=['*'];options.name_legacy=True;options.name_languages=['*']
    worker=subset.Subsetter(options=options);worker.populate(text=chars);worker.subset(font)
    for record in font['name'].names:
        if record.nameID in [1,2,4,6,16,17]:
            label={400:'Regular',500:'Medium',700:'Bold'}[weight]
            value=family
            if record.nameID in [2,17]:value=label
            if record.nameID==4:value=family+' '+label
            if record.nameID==6:value=family.replace(' ','')+'-'+label
            record.string=value.encode(record.getEncoding())
    buffer=io.BytesIO();font.save(buffer);data=buffer.getvalue()
    filename=family.lower().replace(' ','-')+f'-{weight}.ttf';(OUT/filename).write_bytes(data)
    faces.append(f"@font-face {{ font-family: '{family}'; font-style: normal; font-weight: {weight}; src: url(data:font/ttf;base64,{base64.b64encode(data).decode()}) format('truetype'); }}")
    records.append({'family':family,'weight':weight,'file':filename,'source':origin,'sourceSha256':source_hash,'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'codePoints':[f'U+{ord(c):04X}' for c in chars]})

noto_path=Path('C:/Windows/Fonts/NotoSansSC-VF.ttf')
noto_hash=hashlib.sha256(noto_path.read_bytes()).hexdigest()
for weight in [400,500,700]:
    font=instantiateVariableFont(TTFont(noto_path),{'wght':weight},inplace=True)
    # Save/reload rounds TrueType coordinates as done by actual font consumption.
    buffer=io.BytesIO();font.save(buffer);buffer.seek(0);font=TTFont(buffer)
    if weight==400:
        pen=RecordingPen();font.getGlyphSet()[font.getBestCmap()[ord('头')]].draw(pen)
        (source/'noto-outline-identification.json').write_text(json.dumps({'font':str(noto_path),'sourceSha256':noto_hash,'weight':400,'unitsPerEm':font['head'].unitsPerEm,'headGlyphFirstCommands':pen.value[:4],'FigmaLabel':'127:1895','FigmaFirstPoint':[7.406,3.38],'scale':0.014,'fallbackBaseline':15,'firstPointMatches':abs(pen.value[0][1][0][0]*.014-7.406)<.0001 and abs(15-pen.value[0][1][0][1]*.014-3.38)<.0001,'qualification':'First contour and follow-up segments match; this identifies the Chinese fallback family, not whole-page visual acceptance'},ensure_ascii=False,indent=2),encoding='utf-8')
    emit(font,'C002 Noto SC',weight,chinese,'local NotoSansSC-VF.ttf; Google Fonts Noto Sans SC',noto_hash)

css=urllib.request.urlopen('https://fonts.googleapis.com/css2?family=Roboto:wght@400;500;700&display=swap',timeout=30).read().decode()
for block in re.findall(r'@font-face\s*\{(.*?)\}',css,re.S):
    weight=int(re.search(r'font-weight:\s*(\d+)',block)[1]);url=re.search(r'url\(([^)]+)\)',block)[1]
    data=urllib.request.urlopen(url,timeout=30).read()
    emit(TTFont(io.BytesIO(data)),'C002 Roboto',weight,ascii_text,url,hashlib.sha256(data).hexdigest())
# Figma's fallback uses the pan-CJK/Japanese glyph form for shared ideographs such as 像.
# The SC candidate matched 头 but not the full label; retain that failed audit in evidence.
# Keep SC as the fallback for simplified characters absent from the JP font (e.g. 头).
for weight in [400,500,700]:
    import urllib.parse
    request='https://fonts.googleapis.com/css2?'+urllib.parse.urlencode({'family':f'Noto Sans JP:wght@{weight}','text':chinese})
    definition=urllib.request.urlopen(request,timeout=30).read().decode()
    url=re.search(r'url\(([^)]+)\)',definition)[1]
    data=urllib.request.urlopen(url,timeout=30).read()
    font=TTFont(io.BytesIO(data))
    missing=[c for c in chinese if ord(c) not in font.getBestCmap()]
    available=''.join(c for c in chinese if c not in missing)
    emit(font,'C002 Noto CJK',weight,available,url,hashlib.sha256(data).hexdigest())
for name in ['notosanssc','notosansjp','roboto']:
    (OUT/(name+'-OFL.txt')).write_bytes(urllib.request.urlopen('https://raw.githubusercontent.com/google/fonts/main/ofl/'+name+'/OFL.txt',timeout=30).read())
(PAGE/'fonts.css').write_text('\n'.join(faces)+'\n',encoding='utf-8')
(OUT/'manifest.json').write_text(json.dumps({'scope':'Representative page static copy + ASCII. Arbitrary user-entered CJK outside the subset still uses the platform fallback; no full-CJK cross-device claim.','fonts':records},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Font subsets:',len(records),'bytes:',sum(r['bytes'] for r in records),'Chinese code points:',len(chinese))
