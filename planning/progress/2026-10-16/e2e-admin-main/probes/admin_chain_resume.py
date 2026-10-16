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

AFTERSALE_REFUND = "104486458681122816"
APPLICATION_REFUND = "104488242980077568"


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
    aftersale = poll_success(endpoint, AFTERSALE_REFUND, "S4-refund")
    application = poll_success(endpoint, APPLICATION_REFUND, "S5-refund")

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
    p.S3_REFUNDS.extend([AFTERSALE_REFUND, APPLICATION_REFUND])
    p.s3_disposition_attempts(endpoint, meta)
    p.s6b_governance(endpoint, meta)
    p.s7_ops_attempts(endpoint)
    summary = {"results": p.RESULTS,
               "refundIds": {"aftersale": AFTERSALE_REFUND, "application": APPLICATION_REFUND},
               "meta": {k: v for k, v in meta.items() if "Password" not in k}}
    out = sys.argv[3]
    with open(out, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, ensure_ascii=False, indent=2)
    print("SUMMARY " + json.dumps(p.RESULTS, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
