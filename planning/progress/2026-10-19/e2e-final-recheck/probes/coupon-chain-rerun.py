#!/usr/bin/env python
"""Coupon-chain re-run driver on the FRESH recheck DB (2026-10-19, after the E-5 reuse-DROP
lost the first DB). HTTP equivalent-drive of: book with coupon -> sandbox pay -> USED ->
pre-service refund apply (AUTO_FULL_BEFORE_SERVICE) -> worker execution -> coupon restore.
The UI-level equivalents of every step were evidenced on the first DB (REPORT.md 2.1)."""
import json, urllib.request, uuid, time, sys

BASE = 'http://127.0.0.1:18081'
OUT = open(r'D:/Temp/e2e-final-recheck/probes/coupon-chain-rerun.txt', 'a', encoding='utf-8')
def log(msg):
    print(msg, flush=True); OUT.write(msg + '\n'); OUT.flush()

def call(method, path, body=None, token=None, extra=None):
    h = {'Content-Type': 'application/json', 'X-Request-Id': str(uuid.uuid4())}
    if token: h['Authorization'] = 'Bearer ' + token
    h.update(extra or {})
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors='replace')
        try:
            return e.code, json.loads(raw)
        except Exception:
            log(f"NON-JSON {e.code} {path}: {raw[:200]}")
            return e.code, {'raw': raw[:200]}

def login():
    s, att = call('POST', '/api/v1/c/auth/attempts', {'purpose': 'WECHAT_LOGIN'})
    assert s == 201, (s, att)
    d = att['data']
    req = urllib.request.Request(BASE + '/api/v1/c/auth/wechat-login',
        data=json.dumps({'attemptId': d['attemptId'], 'wechatCode': 'e2e-rerun-driver'}).encode(), method='POST',
        headers={'Content-Type': 'application/json', 'X-Auth-Attempt': d['attemptToken'], 'X-Request-Id': str(uuid.uuid4())})
    with urllib.request.urlopen(req, timeout=20) as r:
        grant = json.loads(r.read().decode())['data']
    json.dump(grant, open(r'D:/Temp/e2e-final-recheck/c-grant.json', 'w'))
    log(f"login ok userId={grant['userId']}")
    return grant['accessToken']

tok = login()
# 1) book TOMORROW 10:00 with coupon 716011 (pre-service by construction)
import datetime
tmrw = (datetime.datetime.now() + datetime.timedelta(days=1)).strftime('%Y-%m-%d')
s, av = call('GET', f'/api/v1/c/services/710401/availability?storeId=710302&startDate={tmrw}&endDate={tmrw}', token=tok)
free = [it['start'] for it in av['data']['items'] if it['available'] and it['remainingCapacity'] > 0]
assert free, f'no free slot tomorrow: {av}'
start = free[0]
end = start[:11] + '15:00:00+08:00' if 'T14:00' in start else None
# compute end as start+60min generically
import datetime as _dt
end = start[:11] + f"{int(start[11:13]) + 1:02d}" + start[13:]
log(f"picked slot {start} ~ {end}")
oid = None
for attempt in range(1, 7):
    s, order = call('POST', '/api/v1/c/orders', {'storeId': '710302', 'serviceId': '710401', 'petId': '710200',
        'fulfillmentType': 'IN_STORE', 'appointmentStart': start, 'appointmentEnd': end,
        'couponInstanceId': '716011'}, token=tok)
    log(f"create order attempt {attempt} -> {s} {json.dumps(order)[:240]}")
    if s == 201:
        oid = order['data']['orderId']; break
    time.sleep(25)
assert oid, 'create order never succeeded'
json.dump({'orderId': oid}, open(r'D:/Temp/e2e-final-recheck/rerun-order.json', 'w'))
# 2) pay
s, pay = call('POST', f'/api/v1/c/orders/{oid}/payments', {'channel': 'WECHAT_MINI_PROGRAM'}, token=tok)
log(f"payment intent -> {s}")
deadline = time.time() + 180
stage = ''
while time.time() < deadline:
    s, d = call('GET', f'/api/v1/c/orders/{oid}', token=tok)
    stage = d['data'].get('orderStage', '')
    if 'PENDING_CONFIRM' in stage or 'PENDING_SERVICE' in stage or 'COMPLETED' in stage:
        break
    time.sleep(3)
log(f"order stage after sandbox pay: {stage}")
# 3) pre-service refund apply (auto full)
s, rfd = call('POST', f'/api/v1/c/orders/{oid}/refund-applications',
    {'reasonCode': 'SCHEDULE_CONFLICT', 'reasonText': 'E2E终检复验：服务前自动全额退款重跑（首个库被E-5自毁后）。'}, token=tok)
log(f"refund apply -> {s} {json.dumps(rfd)[:300]}")
print('ORDER_ID=' + oid)
