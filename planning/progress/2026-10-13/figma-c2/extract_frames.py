#!/usr/bin/env python3
"""从 user-end-page1.raw (SSE) 提取目标画板 XML 树，按 id 缩进层级输出全文。"""
import json, re, sys, io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

RAW = r"D:/Temp/figma-mcp/user-end-page1.raw"
TARGETS = ["61:599", "690:6370", "690:6660", "97:7825", "101:3",
           "690:2025", "690:4205", "96:3328", "96:4355"]

# 拼接所有 data: 行的 text
chunks = []
with open(RAW, encoding='utf-8') as f:
    for line in f:
        if line.startswith('data: '):
            try:
                obj = json.loads(line[6:])
            except json.JSONDecodeError:
                continue
            for c in obj.get('result', {}).get('content', []):
                if c.get('type') == 'text':
                    chunks.append(c['text'])
full = "\n".join(chunks)
print(f"[total xml chars: {len(full)}]", file=sys.stderr)
with open('tree-full.xml', 'w', encoding='utf-8') as f:
    f.write(full)

# 逐 frame 提取（frame 标签起，到同缩进闭合）
lines = full.split('\n')
for tgt in TARGETS:
    tid = tgt.replace(':', '_')
    pat = re.compile(r'<frame id="' + re.escape(tgt) + r'"')
    for i, ln in enumerate(lines):
        if pat.search(ln):
            indent = len(ln) - len(ln.lstrip())
            out = [ln]
            for j in range(i + 1, len(lines)):
                l2 = lines[j]
                ind2 = len(l2) - len(l2.lstrip())
                if ind2 <= indent and l2.strip():
                    out.append(l2)
                    break
                out.append(l2)
            fname = f'frame-{tid}.xml'
            with open(fname, 'w', encoding='utf-8') as f:
                f.write('\n'.join(out))
            print(f"{tgt} -> {fname} ({len(out)} lines)", file=sys.stderr)
            break
    else:
        print(f"{tgt} NOT FOUND", file=sys.stderr)
