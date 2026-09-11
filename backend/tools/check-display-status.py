#!/usr/bin/env python3
"""ARCH-005 source gate. Order owns display derivation; consumers render server values.

Conservative review gate, not a proof of arbitrary program semantics. See README.
"""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
FACT = r"(?:orderStage|order_stage|paymentStatus|payment_status|refundApplicationStatus|refund_application_status|refundStatus|refund_status|afterSaleStatus|aftersaleStatus|aftersale_status|verificationStatus|verification_status)"
DISPLAY = r"(?:displayOrderStatus|displayStatus|DisplayOrderStatus)"
STATUSES = r"(?:PENDING_PAYMENT|PENDING_CONFIRM|PENDING_SERVICE|COMPLETED|CANCELED|REFUND_PENDING_CONFIRM|REFUNDING|REFUNDED|PARTIAL_REFUND|AFTERSALE)"
# Keep strings (including URL slashes) intact; strip only actual comments.
LEXEME = re.compile(r'''("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)|//[^\n]*|/\*[\s\S]*?\*/''')


def strip_comments(source):
    return LEXEME.sub(lambda m: m.group(1) or "\n" * m.group(0).count("\n"), source)


def violations(source, order_owned=False):
    if order_owned:
        return []
    code = strip_comments(source)
    reasons = []
    raw_read = re.search(r"\.\s*" + FACT + r"\b|\bget(?:OrderStage|PaymentStatus|RefundApplicationStatus|RefundStatus|AfterSaleStatus|VerificationStatus)\s*\(", code)
    # Also recognize destructuring and local parameters used in comparisons/switches.
    raw_branch = re.search(r"\b" + FACT + r"\b\s*(?:[!=]=|\)|\?|&&|\|\|)|\[\s*['\"]" + FACT + r"['\"]\s*\]", code)
    nested_fact = re.search(r"\b(?:refund|payment|active_refund_application|active_aftersale|verification)\s*\.\s*(?:status|type)\b", code)
    output = re.search(DISPLAY + r"|['\"]" + STATUSES + r"['\"]", code)
    decision = re.search(r"\b(?:if|switch)\s*\(|\?(?![.:])|==|!=|&&|\|\||\[[^\]\n]*(?:" + FACT + r"|\.\s*status)\b", code)
    direct_projection = re.search(DISPLAY + r"\s*[:=]\s*[^;\n]*(?:\.\s*" + FACT + r"\b|get(?:OrderStage|PaymentStatus|RefundStatus)\s*\()", code)
    # Contract DTO getters may legitimately carry both raw facts and server displayStatus.
    # Their declarations/field copies are not priority computation.
    if (raw_read or raw_branch or nested_fact) and output and (decision or direct_projection):
        reasons.append("raw order facts and display output coexist outside order; delegate derivation to order")
    if re.search(r"\b(?:calculate|compute|derive|resolve|map|build|to)(?:DisplayOrderStatus|DisplayStatus)\s*(?:=|\(|:)", code, re.I):
        reasons.append("display-status calculator declared outside order")
    # Returning/assigning a constant is a local status decision; pure pass-through stays legal.
    if re.search(r"(?:return\s+|\b" + DISPLAY + r"\s*[:=]\s*)(?:DisplayOrderStatus\s*\.\s*)" + STATUSES + r"\b", code):
        reasons.append("locally selecting DisplayOrderStatus constants outside order")
    return reasons


def check(root):
    backend = root / "backend"
    modules = [n.text.strip() for n in ET.parse(backend / "pom.xml").findall("m:modules/m:module", NS)]
    if not modules:
        raise ValueError("ARCH-COVERAGE: no reactor modules")
    findings, inventory = [], {}
    for module in modules:
        if module == "pet-architecture-test":
            continue
        folder = backend / module / "src/main/java"
        files = sorted(folder.rglob("*.java"))
        if not files:
            raise ValueError(f"ARCH-COVERAGE: no production Java sources: {module}")
        inventory[module] = len(files)
        for path in files:
            for reason in violations(path.read_text(encoding="utf-8-sig"), module == "pet-order-biz"):
                findings.append(f"ARCH-005 {path.relative_to(root)}: {reason}")
    for frontend in ("frontend-admin", "frontend-miniapp"):
        folder = root / frontend
        if not folder.is_dir():
            raise ValueError(f"ARCH-COVERAGE: missing {frontend} directory")
        files = sorted(p for p in (folder / "src").rglob("*") if p.suffix in {".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"})
        inventory[frontend] = len(files)
        if (folder / "package.json").exists() and not files:
            raise ValueError(f"ARCH-COVERAGE: {frontend} package exists but source scan is empty")
        for path in files:
            for reason in violations(path.read_text(encoding="utf-8-sig")):
                findings.append(f"ARCH-005 {path.relative_to(root)}: {reason}")
    return inventory, findings


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    try:
        inventory, findings = check(args.root)
        for name, count in inventory.items():
            print(f"ARCH-005 {name}: {count} source files" + (" (NOT_IMPLEMENTED; no runtime coverage)" if not count else ""))
        print("\n".join(findings) if findings else "PASS: no duplicate display derivation patterns in existing sources")
        raise SystemExit(bool(findings))
    except (ValueError, OSError, ET.ParseError) as error:
        print(error)
        raise SystemExit(1)
