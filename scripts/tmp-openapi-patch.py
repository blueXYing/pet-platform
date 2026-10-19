# -*- coding: utf-8 -*-
"""One-shot OpenAPI11 patch for the M-side finish slice (run from the worktree root)."""
import io

p = 'docs/04-api/11-OpenAPI-Core-v0.4.yaml'
s = io.open(p, encoding='utf-8').read()

with io.open('scripts/tmp-openapi-paths.yaml', encoding='utf-8') as f:
    paths = f.read()
with io.open('scripts/tmp-openapi-schemas.yaml', encoding='utf-8') as f:
    schemas = f.read()

anchor = '  /merchant/reviews:'
assert anchor in s, 'paths anchor missing'
s = s.replace(anchor, paths + anchor, 1)

anchor = '    MerchantOrderPageEnvelope:'
assert anchor in s, 'schemas anchor missing'
s = s.replace(anchor, schemas + anchor, 1)

io.open(p, 'w', encoding='utf-8').write(s)
print('openapi patched')
