#!/usr/bin/env python3
"""Resume driver for the QA-005 admin main-chain probe (2026-10-16).

Run 4 completed through S5-apply (both refund_orders created, source AFTERSALE_DECISION and
PRESTART_AUTO) but the two durable refund tasks were in RETRY_WAIT behind a shared-MySQL
Snowflake storm when the probe's fixed budgets expired. This driver:
  1. polls both refunds through the admin face until SUCCESS (or budget out),
  2. asserts the S5 refund projection (poll, not immediate),
  3. runs the remaining chain: S6 refund queries, S3 disposal attempts (FA-1 evidence),
     S6b governance, S7 ops attempts (FA-3 evidence),
  4. writes the final summary JSON alongside the main transcript.
"""
import json
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0] if "/" in __file__ else ".")
import admin_main_chain as p  # noqa: E402

AFTERSALE_REFUND = "104498021626454016"  # fresh-db run 2026-10-16 16:36
APPLICATION_REFUND = "PENDING_S5_APPLY"  # filled by poll_placeholder below


def poll_success(endpoint, refund_id, label, budget_s=1500):
    started = time.time()
    last_status = None
    while time.time() - started < budget_s:
        entry = p.call(f"{label}-poll", endpoint, "GET",
                       f"/api/v1/admin/trade/refunds/{refund_id}", token=p.ADMIN_TOKEN,
                       expect=200)
        if entry["response"] and entry["response"].get("data"):
            last_status = entry["response"]["data"]["status"]
            if last_status == "SUCCESS":
                p.RESULTS[label] = "PASS"
                print(f"[{label}] refund SUCCEEDED after {round(time.time()-started)}s", flush=True)
                return entry["response"]["data"]
        time.sleep(15)
    p.RESULTS[label] = f"TIMEOUT status={last_status}"
    return None


def main():
    meta = p.load_metadata(sys.argv[1])
    endpoint = meta["endpoint"]
    p.TRANSCRIPT = sys.argv[2]
    p.admin_login(endpoint, meta)
    # S1 tail: the application detail face (the probe run started before the fixture material
    # patch landed in this database).
    p.call("S1b-application-detail", endpoint, "GET",
           "/api/v1/admin/merchant-applications/710600", token=p.ADMIN_TOKEN, expect=200)
    aftersale = poll_success(endpoint, AFTERSALE_REFUND, "S4-refund")
    # Resolve the application refund id from the fresh database state (the probe run may not
    # have reached S5 yet; the driver applies only if no application exists yet).
    listing = p.call("S5-resolve", endpoint, "GET",
                     f"/api/v1/admin/trade/refunds?orderId={meta['adminChainRefundTargetOrderId']}"
                     f"&page=1&pageSize=20", token=p.ADMIN_TOKEN, expect=200)
    items = (listing["response"] or {}).get("data", {}).get("items", [])
    application_id = items[0]["refundOrderId"] if items else None
    if application_id is None:
        c_token = p.c_login(endpoint)
        applied = p.call("S5-refund-apply", endpoint, "POST",
                         f"/api/v1/c/orders/{meta['adminChainRefundTargetOrderId']}/refund-applications",
                         {"reasonCode": "PLAN_CHANGED", "reasonText": "E2E：行程变化，服务前申请退款"},
                         token=c_token, expect=None)
        data = (applied["response"] or {}).get("data") or {}
        print("S5 apply:", applied["status"], data.get("applicationStatus"), data.get("route"))
        for _ in range(20):
            listing = p.call("S5-resolve", endpoint, "GET",
                             f"/api/v1/admin/trade/refunds?orderId={meta['adminChainRefundTargetOrderId']}"
                             f"&page=1&pageSize=20", token=p.ADMIN_TOKEN, expect=200)
            items = (listing["response"] or {}).get("data", {}).get("items", [])
            if items:
                application_id = items[0]["refundOrderId"]
                break
            time.sleep(15)
    assert application_id, "application refund never projected"
    application = poll_success(endpoint, application_id, "S5-refund")

    # S5 refund projection via the admin list face (poll-style assertion).
    listing = p.call("S5-refund-list-by-order", endpoint, "GET",
                     f"/api/v1/admin/trade/refunds?orderId={meta['adminChainRefundTargetOrderId']}"
                     f"&page=1&pageSize=20", token=p.ADMIN_TOKEN, expect=200)
    items = (listing["response"] or {}).get("data", {}).get("items", [])
    if any(item["refundOrderId"] == APPLICATION_REFUND for item in items):
        p.RESULTS["S5-apply"] = "PASS"
    else:
        p.RESULTS["S5-apply"] = f"MISSING_PROJECTION items={items}"

    if aftersale and application:
        p.s6_refund_queries(endpoint)
    p.S3_REFUNDS.extend([AFTERSALE_REFUND, application_id])
    APPLICATION_REFUND_RESOLVED = application_id
    p.s3_disposition_attempts(endpoint, meta)
    p.s6b_governance(endpoint, meta)
    p.s7_ops_attempts(endpoint)
    summary = {"results": p.RESULTS,
               "refundIds": {"aftersale": AFTERSALE_REFUND, "application": application_id},
               "meta": {k: v for k, v in meta.items() if "Password" not in k}}
    out = sys.argv[3]
    with open(out, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, ensure_ascii=False, indent=2)
    print("SUMMARY " + json.dumps(p.RESULTS, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
