#!/usr/bin/env python3
"""QA-005 admin web main-chain E2E probe driver (2026-10-16).

Drives the REAL backend (E2eCoreLoopServer round-4 extension) over loopback HTTP,
one JSONL transcript line per request, and prints a step summary at the end.

Chain under test (issue QA-005 operations-web main chain):
  S0  admin login (attempts -> login -> session -> permissions)
  S1  ADM-001 browse: merchant-applications list + services list/detail
  S2  contract-63 trade queries: orders list/detail/status-logs, verifications list/detail
  S4  A-004 aftersale final adjudication chain:
      admin list -> detail -> accept -> supplement-requests(USER)
      -> C evidence submit -> admin detail -> decide FULL_REFUND
      -> wait refund SUCCEEDED (original-route task execution)
  S5  C pre-service refund application (AUTO_FULL_BEFORE_SERVICE) -> wait refund SUCCEEDED
  S3  contract-63 v0.2 disposal attempts over REAL refunds/orders (channel-query/retry,
      abnormal-close, merchant disable) - dead-face finding evidence
  S6  contract-64 governance read face
  S7  contract-65 ops face attempts (encyclopedia/coupon/points/settings) - dead-face finding evidence

Usage: python3 admin_main_chain.py <runtime-dir>
  where <runtime-dir> holds server-metadata.txt written by E2eCoreLoopServer.
"""
import json
import socket
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

METADATA = None
TRANSCRIPT = None
ADMIN_TOKEN = None
C_TOKEN = None
RESULTS = {}


def log(*parts):
    print(*parts, flush=True)


def load_metadata(runtime_dir):
    meta = {}
    with open(f"{runtime_dir}/server-metadata.txt", encoding="utf-8") as handle:
        for line in handle:
            if "=" in line:
                key, value = line.rstrip("\n").split("=", 1)
                meta[key] = value
    return meta


def raw_request(endpoint, method, path, body=None, headers=None, timeout=45):
    """One HTTP/1.1 request over a fresh loopback socket (Connection: close)."""
    parsed = urllib.request.urlparse(endpoint)
    payload = b"" if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    head = f"{method} {path} HTTP/1.1\r\nHost: {parsed.hostname}:{parsed.port}\r\n"
    for name, value in (headers or {}).items():
        head += f"{name}: {value}\r\n"
    head += f"Content-Length: {len(payload)}\r\nConnection: close\r\n\r\n"
    with socket.create_connection((parsed.hostname, parsed.port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        sock.sendall(head.encode("iso-8859-1") + payload)
        chunks = []
        while True:
            piece = sock.recv(65536)
            if not piece:
                break
            chunks.append(piece)
    raw = b"".join(chunks).decode("iso-8859-1")
    split = raw.find("\r\n\r\n")
    status = int(raw[9:12])
    head_block = raw[:split]
    rest = raw[split + 4:]
    if "chunked" in head_block.lower():
        rest = dechunk(rest)
    return status, rest


def dechunk(chunked):
    out = []
    pos = 0
    while pos < len(chunked):
        line_end = chunked.find("\r\n", pos)
        if line_end < 0:
            break
        size = int(chunked[pos:line_end].split(";")[0].strip(), 16)
        if size == 0:
            break
        out.append(chunked[line_end + 2:line_end + 2 + size])
        pos = line_end + 2 + size + 2
    return "".join(out)


def call(step, endpoint, method, path, body=None, token=None, admin_headers=None,
         expect=None, note=None, retries=None):
    headers = {"Content-Type": "application/json", "X-Request-Id": str(uuid.uuid4())}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if admin_headers:
        headers.update(admin_headers)
    # Reads auto-retry transient 503s (the shared QA MySQL storms the Snowflake lane);
    # writes never auto-retry (a fresh X-Request-Id on a maybe-committed write is unsafe).
    if retries is None:
        retries = 2 if method == "GET" else 0
    entry = None
    for round_index in range(retries + 1):
        entry = call_once(step, endpoint, method, path, body, headers, expect, note)
        if entry["ok"] or round_index == retries:
            return entry
        if entry["status"] == 503:
            log(f"[{step}] transient 503, retry {round_index + 1}/{retries} in 20s")
            time.sleep(20)
        else:
            return entry
    return entry


def call_once(step, endpoint, method, path, body, headers, expect, note):
    started = time.time()
    error_text = None
    status, raw = None, None
    envelope = None
    try:
        status, raw = raw_request(endpoint, method, path, body, headers)
        try:
            envelope = json.loads(raw) if raw.strip() else {}
        except ValueError:
            envelope = {"_unparsed": raw[:400]}
    except Exception as failure:  # noqa: BLE001 - transcript keeps the failure
        error_text = repr(failure)
    ok = error_text is None and (expect is None or status == expect)
    entry = {
        "step": step, "method": method, "path": path, "status": status,
        "ok": ok, "latencyMs": round((time.time() - started) * 1000),
        "request": body, "response": envelope, "error": error_text, "note": note,
    }
    with open(TRANSCRIPT, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
    code = (envelope or {}).get("code")
    log(f"[{step}] {method} {path} -> {status} code={code} ok={ok}"
        + (f" ERR={error_text}" if error_text else ""))
    return entry


def rid():
    return str(uuid.uuid4())


# --------------------------------------------------------------------------- steps


def admin_login(endpoint, meta):
    origin = meta["adminOrigin"]
    # Raw attempt call: the attempt secret arrives as Set-Cookie and must be relayed verbatim.
    parsed = urllib.request.urlparse(endpoint)
    payload = b"{}"
    head = (f"POST /api/v1/admin/auth/attempts HTTP/1.1\r\nHost: {parsed.hostname}:{parsed.port}\r\n"
            f"Content-Type: application/json\r\nOrigin: {origin}\r\n"
            f"X-Request-Id: {rid()}\r\nContent-Length: {len(payload)}\r\nConnection: close\r\n\r\n")
    with socket.create_connection((parsed.hostname, parsed.port), timeout=30) as sock:
        sock.sendall(head.encode("iso-8859-1") + payload)
        chunks = []
        while True:
            piece = sock.recv(65536)
            if not piece:
                break
            chunks.append(piece)
    raw = b"".join(chunks).decode("iso-8859-1")
    set_cookie = None
    for line in raw.split("\r\n"):
        if line.lower().startswith("set-cookie:"):
            set_cookie = line.split(":", 1)[1].split(";", 1)[0].strip()
    attempt2 = json.loads(raw[raw.find("\r\n\r\n") + 4:])
    assert attempt2.get("code") == "SUCCESS" and attempt2.get("data"), raw[:300]
    data = attempt2["data"]
    with open(TRANSCRIPT, "a", encoding="utf-8") as handle:
        handle.write(json.dumps({
            "step": "S0-attempts", "method": "POST", "path": "/api/v1/admin/auth/attempts",
            "status": int(raw[9:12]), "ok": True, "latencyMs": None,
            "request": {}, "response": {"code": "SUCCESS",
                                        "attemptId": data["attemptId"]},
            "setCookieFlags": "HttpOnly/SameSite/Path relayed verbatim to login (token value redacted)",
            "error": None, "note": "raw-socket call so Set-Cookie reaches the login relay",
        }, ensure_ascii=False) + "\n")
    log(f"[S0-attempts] POST /api/v1/admin/auth/attempts -> {raw[9:12]} ok=True")
    login = call("S0-login", endpoint, "POST", "/api/v1/admin/auth/login",
                 {"attemptId": data["attemptId"], "account": meta["adminAccount"],
                  "password": meta["adminPassword"]},
                 admin_headers={"Origin": origin, "X-Auth-Attempt": data["attemptToken"],
                                "Cookie": set_cookie},
                 expect=200)
    assert login["ok"], f"admin login failed: {login['response']}"
    global ADMIN_TOKEN
    ADMIN_TOKEN = login["response"]["data"]["accessToken"]
    call("S0-session", endpoint, "GET", "/api/v1/admin/auth/session", token=ADMIN_TOKEN, expect=200)
    perms = call("S0-permissions", endpoint, "GET", "/api/v1/admin/auth/permissions",
                 token=ADMIN_TOKEN, expect=200)
    actions = perms["response"]["data"]["actionCodes"]
    assert "order.read" in actions and "aftersale.decide" in actions and "merchant.read" in actions, \
        f"superadmin grants incomplete: {actions}"
    RESULTS["S0"] = "PASS"
    return actions


def s1_adm001(endpoint, meta):
    app_list = call("S1-applications", endpoint, "GET",
                    "/api/v1/admin/merchant-applications?status=APPROVED&page=1&pageSize=20",
                    token=ADMIN_TOKEN, expect=200)
    items = app_list["response"]["data"]["items"]
    seeded = [item for item in items if item.get("applicationNo") == "SQ20261007E2ELOOP1"]
    assert seeded, f"seeded approved application missing: {items[:2]}"
    application_id = seeded[0]["applicationId"]
    call("S1-application-detail", endpoint, "GET",
         f"/api/v1/admin/merchant-applications/{application_id}", token=ADMIN_TOKEN, expect=200)
    svc_list = call("S1-services", endpoint, "GET",
                    "/api/v1/admin/services?page=1&pageSize=20", token=ADMIN_TOKEN, expect=200)
    svc_items = svc_list["response"]["data"]["items"]
    assert any(item.get("serviceId") == "710401" for item in svc_items), \
        f"seeded service 710401 missing: {svc_items[:2]}"
    call("S1-service-detail", endpoint, "GET", "/api/v1/admin/services/710401",
         token=ADMIN_TOKEN, expect=200)
    RESULTS["S1"] = "PASS"


def s2_trade_queries(endpoint, meta):
    completed = meta["adminChainCompletedOrderId"]
    disposition = meta["adminChainDispositionOrderId"]
    orders = call("S2-orders", endpoint, "GET",
                  "/api/v1/admin/trade/orders?page=1&pageSize=50", token=ADMIN_TOKEN, expect=200)
    ids = [item["orderId"] for item in orders["response"]["data"]["items"]]
    assert completed in ids and disposition in ids, f"seed orders missing from admin list: {ids}"
    pending = call("S2-orders-pending", endpoint, "GET",
                   "/api/v1/admin/trade/orders?displayStatus=PENDING_SERVICE&page=1&pageSize=50",
                   token=ADMIN_TOKEN, expect=200)
    assert disposition in [item["orderId"] for item in pending["response"]["data"]["items"]], \
        "disposition target not PENDING_SERVICE"
    detail = call("S2-order-detail", endpoint, "GET",
                  f"/api/v1/admin/trade/orders/{completed}", token=ADMIN_TOKEN, expect=200)
    assert detail["response"]["data"]["orderStage"] == "COMPLETED", detail["response"]["data"]
    # FA-5 finding (live, reproducible on every call, superadmin included): the status-logs and
    # verification read routes die in the authorization INPUT validation —
    # AdminActionCheckQuery's dotted-segment lexicon [a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+ rejects
    # the DEPLOYED hyphenated codes order.status-log.read / order.verification-log.read with an
    # IllegalArgumentException -> 400 COMMON_INVALID_ARGUMENT. Contract-63 read face: these two
    # routes can never answer. Asserted here as the finding evidence (not fixed in this run).
    logs = call("S2-order-status-logs", endpoint, "GET",
                f"/api/v1/admin/trade/orders/{completed}/status-logs",
                token=ADMIN_TOKEN, expect=400,
                note="FA5: hyphenated action code rejected by the dotted-segment lexicon")
    assert logs["status"] == 400 and (logs["response"] or {}).get("code") == "COMMON_INVALID_ARGUMENT"
    verifications = call("S2-verifications", endpoint, "GET",
                         f"/api/v1/admin/trade/verifications?orderId={completed}&page=1&pageSize=20",
                         token=ADMIN_TOKEN, expect=400,
                         note="FA5: order.verification-log.read hits the same lexicon rejection")
    assert verifications["status"] == 400 \
        and (verifications["response"] or {}).get("code") == "COMMON_INVALID_ARGUMENT"
    RESULTS["S2"] = "PASS"
    RESULTS["S2-FA5"] = "FINDING_STATUS_LOGS_AND_VERIFICATIONS_DEAD_400"


def c_login(endpoint):
    global C_TOKEN
    if C_TOKEN:
        return C_TOKEN
    attempt = call("C-login-attempts", endpoint, "POST", "/api/v1/c/auth/attempts",
                   {"purpose": "WECHAT_LOGIN"}, expect=201)
    data = attempt["response"]["data"]
    login = call("C-login", endpoint, "POST", "/api/v1/c/auth/wechat-login",
                 {"attemptId": data["attemptId"], "wechatCode": f"e2e-admin-{rid()[:8]}"},
                 admin_headers={"X-Auth-Attempt": data["attemptToken"]}, expect=200)
    C_TOKEN = login["response"]["data"]["accessToken"]
    return C_TOKEN


def s4_aftersale_chain(endpoint, meta):
    case_id = meta["adminChainAfterSaleId"]
    # The OPS list face is store-scoped by contract: merchantId+storeId are mandatory query
    # parameters (the console page mandates the same pair before querying).
    listing = call("S4-aftersale-list", endpoint, "GET",
                   f"/api/v1/admin/aftersales?page=1&pageSize=20"
                   f"&merchantId={meta['merchantId']}&storeId={meta['storeId']}",
                   token=ADMIN_TOKEN, expect=200)
    assert any(item["afterSaleId"] == case_id for item in listing["response"]["data"]["items"]), \
        f"seeded aftersale case missing: {listing['response']['data']}"
    detail = call("S4-aftersale-detail", endpoint, "GET",
                  f"/api/v1/admin/aftersales/{case_id}", token=ADMIN_TOKEN, expect=200)
    body = detail["response"]["data"]
    assert body["status"] == "PENDING", body
    accept = call("S4-accept", endpoint, "POST", f"/api/v1/admin/aftersales/{case_id}/accept",
                  {"expectedVersion": body["version"],
                   "newProblemAssessment": "E2E受理：凭证齐全，诉求成立，进入处理"}, token=ADMIN_TOKEN,
                  expect=200)
    assert accept["response"]["data"]["status"] == "PROCESSING", accept["response"]
    deadline = (datetime.now(timezone.utc) + timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    supplement = call("S4-supplement-request", endpoint, "POST",
                      f"/api/v1/admin/aftersales/{case_id}/supplement-requests",
                      {"expectedVersion": accept["response"]["data"]["version"],
                       "targetParty": "USER", "reason": "E2E补证：请补充服务质量说明",
                       "deadline": deadline}, token=ADMIN_TOKEN, expect=200)
    supp = supplement["response"]["data"]
    assert supp["status"] == "WAITING_SUPPLEMENT" and supp["supplementRequestId"], supp
    c_token = c_login(endpoint)
    evidence = call("S4-c-evidence", endpoint, "POST", f"/api/v1/c/aftersales/{case_id}/evidence",
                    {"expectedVersion": supp["version"],
                     "supplementRequestId": supp["supplementRequestId"],
                     "text": "E2E补证：宠物洗护后出现皮肤红肿，诉求全额退款，附图后补",
                     "evidenceAssetIds": []},
                    token=c_token, expect=200)
    assert evidence["response"]["data"]["status"] == "PROCESSING", evidence["response"]
    decision = call("S4-decide-full-refund", endpoint, "POST",
                    f"/api/v1/admin/aftersales/{case_id}/decisions",
                    {"expectedVersion": evidence["response"]["data"]["version"],
                     "decisionType": "FULL_REFUND", "refundAmount": "60.00",
                     "reason": "E2E终裁：服务质量成立，按原路全额退回"}, token=ADMIN_TOKEN, expect=200)
    decided = decision["response"]["data"]
    assert decided["status"] == "RESOLVED" and decided["refundOrderId"], decided
    refund_id = decided["refundOrderId"]
    RESULTS["S4"] = "PASS"
    return refund_id


def wait_refund_succeeded(endpoint, refund_id, label, deadline_s=420):
    started = time.time()
    last = None
    while time.time() - started < deadline_s:
        last = call(f"{label}-poll", endpoint, "GET",
                    f"/api/v1/admin/trade/refunds/{refund_id}", token=ADMIN_TOKEN, expect=200)
        if last["response"]["data"]["status"] == "SUCCESS":
            RESULTS[label] = "PASS"
            return last["response"]["data"]
        time.sleep(10)
    RESULTS[label] = f"TIMEOUT status={last['response']['data']['status'] if last else '?'}"
    return None


def s5_c_refund_application(endpoint, meta):
    target = meta["adminChainRefundTargetOrderId"]
    c_token = c_login(endpoint)
    applied = call("S5-refund-apply", endpoint, "POST",
                   f"/api/v1/c/orders/{target}/refund-applications",
                   {"reasonCode": "PLAN_CHANGED", "reasonText": "E2E：行程变化，服务前申请退款"},
                   token=c_token, expect=201)
    data = applied["response"]["data"]
    assert data["applicationStatus"] == "AUTO_APPROVED" and data["route"] == "AUTO_FULL_BEFORE_SERVICE", \
        f"pre-service auto-full ruling not in effect: {data}"
    # The refund_order projection rides the async approval consumer; under a shared-MySQL
    # storm it lags the 201 by minutes. Poll the admin list face instead of asserting cold.
    deadline = time.time() + 300
    items = []
    while time.time() < deadline:
        listing = call("S5-refund-list-by-order", endpoint, "GET",
                       f"/api/v1/admin/trade/refunds?orderId={target}&page=1&pageSize=20",
                       token=ADMIN_TOKEN, expect=200)
        items = (listing["response"] or {}).get("data", {}).get("items", [])
        if items:
            break
        time.sleep(15)
    assert items, "no refund_order projected for the auto-approved application within 300s"
    RESULTS["S5-apply"] = "PASS"
    return items[0]["refundOrderId"]


def s3_disposition_attempts(endpoint, meta):
    """contract-63 v0.2 disposal face attempts (finding evidence, expect 403 dead face)."""
    disposition = meta["adminChainDispositionOrderId"]
    merchant_id = meta["merchantId"]
    outcomes = []
    close = call("S3-abnormal-close", endpoint, "POST",
                 f"/api/v1/admin/orders/{disposition}/abnormal-close",
                 {"reasonCode": "MERCHANT_UNREACHABLE", "reason": "E2E处置：商家失联，异常关单"},
                 token=ADMIN_TOKEN)
    outcomes.append(("abnormal-close", close))
    for refund_id in S3_REFUNDS:
        cq = call("S3-refund-channel-query", endpoint, "POST",
                  f"/api/v1/admin/refunds/{refund_id}/channel-query", None, token=ADMIN_TOKEN)
        outcomes.append(("channel-query", cq))
        rt = call("S3-refund-retry", endpoint, "POST",
                  f"/api/v1/admin/refunds/{refund_id}/retry", None, token=ADMIN_TOKEN)
        outcomes.append(("retry", rt))
        break  # one refund id is enough for the finding evidence
    disable = call("S3-merchant-disable", endpoint, "POST",
                   f"/api/v1/admin/merchants/{merchant_id}/disable",
                   {"reason": "E2E处置：资质失效下线"}, token=ADMIN_TOKEN)
    outcomes.append(("merchant-disable", disable))
    dead = all(entry["status"] == 403
               and (entry["response"] or {}).get("code") == "COMMON_FORBIDDEN"
               for _, entry in outcomes)
    # 403 COMMON_FORBIDDEN on every disposal route with a valid superadmin token = the
    # security chain never permits these POSTs (AdminAuthSecurityConfiguration denyAll).
    RESULTS["S3"] = ("FINDING_DEAD_FACE_403" if dead
                     else "UNEXPECTED:" + ",".join(f"{name}={entry['status']}:{(entry['response'] or {}).get('code')}"
                                                   for name, entry in outcomes))


def s6_refund_queries(endpoint):
    listing = call("S6-refunds", endpoint, "GET",
                   "/api/v1/admin/trade/refunds?page=1&pageSize=50", token=ADMIN_TOKEN, expect=200)
    items = listing["response"]["data"]["items"]
    assert len(items) >= 2, f"expected application+aftersale refunds, got {len(items)}"
    sources = sorted({item["sourceType"] for item in items})
    assert "PRESTART_AUTO" in sources and "AFTERSALE_DECISION" in sources, \
        f"source types: {sources}"
    succeeded = call("S6-refunds-succeeded", endpoint, "GET",
                     "/api/v1/admin/trade/refunds?status=SUCCESS&page=1&pageSize=50",
                     token=ADMIN_TOKEN, expect=200)
    assert succeeded["response"]["data"]["total"] >= 2
    RESULTS["S6-query"] = "PASS"


def s6b_governance(endpoint, meta):
    merchant_id = meta["merchantId"]
    store_id = meta["storeId"]
    call("S6b-governance-merchants", endpoint, "GET",
         "/api/v1/admin/governance/merchants?page=1&pageSize=20", token=ADMIN_TOKEN, expect=200)
    detail = call("S6b-governance-merchant-detail", endpoint, "GET",
                  f"/api/v1/admin/governance/merchants/{merchant_id}", token=ADMIN_TOKEN, expect=200)
    assert detail["response"]["data"]["merchantId"] == merchant_id
    call("S6b-governance-staff", endpoint, "GET",
         f"/api/v1/admin/governance/merchants/{merchant_id}/staff?page=1&pageSize=20",
         token=ADMIN_TOKEN, expect=200)
    call("S6b-governance-staff-members", endpoint, "GET",
         f"/api/v1/admin/governance/merchants/{merchant_id}/staff-members?page=1&pageSize=20",
         token=ADMIN_TOKEN, expect=200)
    call("S6b-governance-invitations", endpoint, "GET",
         f"/api/v1/admin/governance/merchants/{merchant_id}/staff-members/invitations?page=1&pageSize=20",
         token=ADMIN_TOKEN, expect=200)
    windows = call("S6b-governance-windows", endpoint, "GET",
                   f"/api/v1/admin/governance/schedule/windows?storeId={store_id}&page=1&pageSize=20",
                   token=ADMIN_TOKEN, expect=200)
    assert windows["response"]["data"]["total"] >= 1, "no schedule windows visible"
    call("S6b-governance-staff-windows", endpoint, "GET",
         f"/api/v1/admin/governance/schedule/staff-windows?storeId={store_id}&page=1&pageSize=20",
         token=ADMIN_TOKEN, expect=200)
    call("S6b-governance-reservations", endpoint, "GET",
         f"/api/v1/admin/governance/schedule/reservations?storeId={store_id}&page=1&pageSize=20",
         token=ADMIN_TOKEN, expect=200)
    RESULTS["S6-governance"] = "PASS"


def s7_ops_attempts(endpoint):
    """contract-65 ops face attempts (finding evidence, expect 403 dead face)."""
    routes = [
        ("encyclopedia-categories", "/api/v1/admin/encyclopedia/categories?page=1&pageSize=20"),
        ("encyclopedia-breeds", "/api/v1/admin/encyclopedia/breeds?page=1&pageSize=20"),
        ("coupon-templates", "/api/v1/admin/coupon-templates?page=1&pageSize=20"),
        ("coupon-instances", "/api/v1/admin/coupon-instances?page=1&pageSize=20"),
        ("points-ledger", "/api/v1/admin/points/ledger?page=1&pageSize=20"),
        ("settings-runtime", "/api/v1/admin/settings/runtime"),
        ("points-task-config", "/api/v1/admin/points-task-config"),
    ]
    outcomes = []
    for name, path in routes:
        outcomes.append((name, call(f"S7-{name}", endpoint, "GET", path, token=ADMIN_TOKEN)))
    put = call("S7-points-task-config-put", endpoint, "PUT", "/api/v1/admin/points-task-config",
               {"signupReward": 5, "referralReward": 50}, token=ADMIN_TOKEN)
    outcomes.append(("points-task-config-put", put))
    dead = all(entry["status"] == 403
               and (entry["response"] or {}).get("code") == "COMMON_FORBIDDEN"
               for _, entry in outcomes)
    RESULTS["S7"] = ("FINDING_DEAD_FACE_403" if dead
                     else "UNEXPECTED:" + ",".join(f"{name}={entry['status']}:{(entry['response'] or {}).get('code')}"
                                                   for name, entry in outcomes))


S3_REFUNDS = []


def main():
    global METADATA, TRANSCRIPT
    runtime_dir = sys.argv[1]
    METADATA = load_metadata(runtime_dir)
    endpoint = METADATA["endpoint"]
    stamp = datetime.now().strftime("%H%M%S")
    TRANSCRIPT = f"{runtime_dir}/admin-chain-transcript-{stamp}.jsonl"
    log(f"transcript={TRANSCRIPT} endpoint={endpoint}")
    admin_login(endpoint, meta=METADATA)
    s1_adm001(endpoint, METADATA)
    s2_trade_queries(endpoint, METADATA)
    aftersale_refund = s4_aftersale_chain(endpoint, METADATA)
    wait_refund_succeeded(endpoint, aftersale_refund, "S4-refund")
    application_refund = s5_c_refund_application(endpoint, METADATA)
    wait_refund_succeeded(endpoint, application_refund, "S5-refund")
    S3_REFUNDS.extend([aftersale_refund, application_refund])
    s6_refund_queries(endpoint)
    s3_disposition_attempts(endpoint, METADATA)
    s6b_governance(endpoint, METADATA)
    s7_ops_attempts(endpoint)
    summary = {"startedAt": stamp, "results": RESULTS,
               "refundIds": {"aftersale": aftersale_refund, "application": application_refund},
               "meta": {k: v for k, v in METADATA.items() if "Password" not in k}}
    with open(f"{runtime_dir}/admin-chain-summary-{stamp}.json", "w", encoding="utf-8") as handle:
        json.dump(summary, handle, ensure_ascii=False, indent=2)
    log("SUMMARY " + json.dumps(RESULTS, ensure_ascii=False))


if __name__ == "__main__":
    main()
