"""Produce an auditable pixel comparison; never turn the score into a VIS pass."""
from pathlib import Path
import json
import hashlib
from PIL import Image, ImageChops, ImageEnhance
import numpy as np
HERE=Path(__file__).resolve().parent
raw_path=HERE/'evidence/reference-canvas-final-raw.png'
source_path=HERE/'source/figma-reference-402x812.png'
reference=Image.open(source_path).convert('RGB')
raw=Image.open(raw_path).convert('RGB')
actual=raw.crop((0,0,402,812))
actual.save(HERE/'evidence/reference-canvas-crop.png')
Image.blend(reference,actual,.5).save(HERE/'evidence/overlay.png')
diff=ImageChops.difference(reference,actual)
ImageEnhance.Contrast(diff).enhance(4).save(HERE/'evidence/difference-x4.png')
side=Image.new('RGB',(804,812),'white');side.paste(reference,(0,0));side.paste(actual,(402,0));side.save(HERE/'evidence/source-and-actual.png')
a=np.asarray(actual,dtype=float);r=np.asarray(reference,dtype=float);delta=np.abs(a-r)
mask=np.ones((812,402),dtype=bool)
# User explicitly removed the '保密' option; this is a recorded design change, not an error exclusion chosen by score.
mask[238:265,223:274]=False
regions={'header':(0,0,402,49),'avatar':(29,65,372,147),'nickname':(29,163,372,209),'gender':(29,225,372,275),'phone':(29,291,372,337),'signature':(29,353,372,517),'save':(29,532,372,577),'tabbar':(0,735,402,812)}
results={}
for name,(x0,y0,x1,y1) in regions.items():
    d=delta[y0:y1,x0:x1][mask[y0:y1,x0:x1]]
    results[name]={'meanAbsoluteChannelDifference':float(d.mean()),'fractionPixelsWithAnyChannelDifferenceOver16':float(np.mean(d.max(1)>16))}
report={'sourceSha256':hashlib.sha256(source_path.read_bytes()).hexdigest(),'rawScreenshotSha256':hashlib.sha256(raw_path.read_bytes()).hexdigest(),'rawScreenshotSize':raw.size,'comparisonCanvas':[0,0,402,812],'comparisonMethod':'Crop only, no rescaling or stretching of the actual screenshot. Raw actual simulator screenshot retained.','approvedDifference':'User removed UNDISCLOSED/保密; no inferred gender default','regions':results,'visualAcceptance':'PENDING_REVIEW; metrics locate differences, do not define a relaxed similarity threshold'}
(HERE/'evidence/visual-comparison.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(results,ensure_ascii=False))
