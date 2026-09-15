"""Compare independently exported Figma glyph outlines to the shipped fallback fonts."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET
import numpy as np
from fontTools.ttLib import TTFont
from fontTools.pens.basePen import BasePen
from fontTools.pens.transformPen import TransformPen
from fontTools.svgLib.path import parse_path

class Points(BasePen):
    def __init__(self): super().__init__(None); self.points=[]; self.p=None
    def _moveTo(self,p): self.p=np.array(p); self.start=self.p; self.points.append(p)
    def _lineTo(self,p):
        p=np.array(p)
        for t in np.linspace(0,1,121): self.points.append(self.p*(1-t)+p*t)
        self.p=p
    def _curveToOne(self,a,b,c):
        a,b,c=map(np.array,(a,b,c))
        for t in np.linspace(0,1,121): self.points.append((1-t)**3*self.p+3*(1-t)**2*t*a+3*(1-t)*t*t*b+t**3*c)
        self.p=c
    def _qCurveToOne(self,a,b):
        a,b=map(np.array,(a,b))
        for t in np.linspace(0,1,121): self.points.append((1-t)**2*self.p+2*(1-t)*t*a+t*t*b)
        self.p=b
    def _closePath(self): self._lineTo(self.start)

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
FONT=ROOT/'frontend-miniapp/src/consumer/assets/profile/fonts'
expected=Points()
for node in ET.fromstring((HERE/'source/label-avatar-outline-true.svg').read_bytes()).findall('{http://www.w3.org/2000/svg}path'):
    parse_path(node.attrib['d'],expected)
fonts=[TTFont(FONT/name) for name in ['c002-noto-cjk-400.ttf','c002-noto-sc-400.ttf']]
actual=Points();selected=[]
for i,char in enumerate('头像'):
    font=next(f for f in fonts if ord(char) in f.getBestCmap())
    selected.append({'character':char,'family':font['name'].getDebugName(1)})
    font.getGlyphSet()[font.getBestCmap()[ord(char)]].draw(TransformPen(actual,(.014,0,0,-.014,i*14,15)))
a=np.array(actual.points);b=np.array(expected.points)
def distances(a,b):
    result=[]
    for i in range(0,len(a),80): result.extend(np.sqrt(((a[i:i+80,None,:]-b[None,:,:])**2).sum(-1).min(-1)))
    return np.array(result)
d=np.r_[distances(a,b),distances(b,a)]
result={'sourceNode':'127:1895','sourceVersion':'2397539525915641008','method':'Bidirectional dense outline sample distance, 14px size / baseline15; no screenshot manipulation.','selectedFonts':selected,'maxDistanceDesignPx':float(d.max()),'meanDistanceDesignPx':float(d.mean()),'p99DistanceDesignPx':float(np.percentile(d,99)),'scope':'Identifies source glyph fallback chain for the audited label, not all user text or raster equality.'}
(HERE/'source/font-outline-verification-final.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,ensure_ascii=False))
