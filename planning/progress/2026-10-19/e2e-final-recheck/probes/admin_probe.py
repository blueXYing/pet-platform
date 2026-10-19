#!/usr/bin/env python
"""Admin HTTP probe for the 2026-10-19 final recheck (QA-005): admin login (raw Set-Cookie
relay, same shape as the #181 probe) + arbitrary authenticated GETs. Usage:
  python admin_probe.py <mysql-less mode> "GET /api/v1/admin/trade/refunds/105505504552431616"
Prints each response envelope as JSON. Probe-only; not part of any deployable.
"""
import json, sys, urllib.request, uuid

BASE = 'http://127.0.0.1:18081'
ORIGIN = 'https://e2e.example.invalid'
ACCOUNT = 'e2e-round2-operator'
PASSWORD = 'E2E_Round2_Operator_91!'

def raw(method, path, body=None, headers=None):
    h = {'Content-Type': 'application/json', 'Origin': ORIGIN, 'X-Request-Id': str(uuid.uuid4())}
    h.update(headers or {})
    req = urllib.request.Request(BASE + path, data=body, method=method, headers=h)
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.status, r.read().decode(), (r.headers.get_all('Set-Cookie') or [])

def admin_login():
    s, body, setc = raw('POST', '/api/v1/admin/auth/attempts', b'{}')
    assert s == 201, (s, body[:200])
    attempt_cookie = [c for c in setc if c.startswith('__Host-pet-admin-attempt')][0].split(';', 1)[0].strip()
    d = json.loads(body)['data']
    s2, lbody, _ = raw('POST', '/api/v1/admin/auth/login',
        json.dumps({'attemptId': d['attemptId'], 'account': ACCOUNT, 'password': PASSWORD}).encode(),
        {'X-Auth-Attempt': d['attemptToken'], 'Cookie': attempt_cookie})
    assert s2 == 200, (s2, lbody[:300])
    tok = json.loads(lbody)['data']['accessToken']
    s3, sess, _ = raw('GET', '/api/v1/admin/auth/session', headers={'Authorization': 'Bearer ' + tok})
    assert s3 == 200, (s3, sess[:200])
    print('admin login ok (session 200)')
    return tok

def main():
    tok = admin_login()
    for spec in sys.argv[1:]:
        method, path = spec.split(' ', 1)
        try:
            s, b, _ = raw(method, path, json.dumps({}).encode() if method == 'POST' else None,
                          {'Authorization': 'Bearer ' + tok})
        except urllib.error.HTTPError as e:
            s, b = e.code, e.read().decode()
        print(f'--- {method} {path} -> {s}')
        try:
            print(json.dumps(json.loads(b), ensure_ascii=False, indent=1)[:2400])
        except Exception:
            print(b[:800])

if __name__ == '__main__':
    main()
