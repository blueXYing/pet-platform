#!/usr/bin/env python
"""Round-3 E2E probe: concurrent order creation racing the same coupon instance.

Scenario A-B (matrix): two parallel POST /api/v1/c/orders with the SAME
couponInstanceId=716012 on two different 60-minute slots. Expected: exactly one
create wins (coupon FROZEN), the other is rejected (fail-closed coupon
unavailable, CPN-004 lost the row-level race).
"""
import json, threading, urllib.request, uuid, sys

BASE = 'http://127.0.0.1:18081'
TOKEN = sys.argv[1] if len(sys.argv) > 1 else ''
COUPON = sys.argv[2] if len(sys.argv) > 2 else '716012'

def call(method, path, body=None, request_id=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    req.add_header('Authorization', 'Bearer ' + TOKEN)
    req.add_header('X-Request-Id', request_id or str(uuid.uuid4()))
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())

results = {}
def create(tag, start):
    body = {
        'storeId': '710302', 'serviceId': '710401', 'petId': '710200',
        'fulfillmentType': 'IN_STORE',
        'appointmentStart': start, 'appointmentEnd': start.replace('10:00', '11:00') if start.endswith('10:00:00+08:00') else None,
        'couponInstanceId': COUPON,
    }
    # compute end = start + 60min generically
    from datetime import datetime, timedelta
    s = datetime.fromisoformat(start)
    body['appointmentEnd'] = (s + timedelta(minutes=60)).isoformat()
    rid = str(uuid.uuid4())
    status, payload = call('POST', '/api/v1/c/orders', body, rid)
    results[tag] = {
        'http': status,
        'code': payload.get('code'),
        'message': payload.get('message'),
        'orderId': (payload.get('data') or {}).get('orderId'),
        'payAmount': (payload.get('data') or {}).get('payAmount'),
        'requestId': rid,
        'traceId': payload.get('traceId'),
    }

threads = [
    threading.Thread(target=create, args=('B1', '2026-10-16T15:00:00+08:00')),
    threading.Thread(target=create, args=('B2', '2026-10-16T16:00:00+08:00')),
]
for t in threads: t.start()
for t in threads: t.join()
print(json.dumps(results, ensure_ascii=False, indent=1))
