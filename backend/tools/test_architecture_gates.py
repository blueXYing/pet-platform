"""Mutation fixtures run outside the checkout; never change a real business module."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import shutil


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


deps = load("deps", "check-module-deps.py")
display = load("display", "check-display-status.py")
frontend = load("frontend", "run-frontend-gate.py")


class ModuleGateTest(unittest.TestCase):
    def model(self, root, dependency="", declared=True):
        (root / "pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modules>' +
            ('<module>pet-refund-biz</module>' if declared else '') + '</modules></project>', encoding="utf-8")
        (root / "pet-refund-biz").mkdir()
        (root / "pet-refund-biz/pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<artifactId>pet-refund-biz</artifactId>' + dependency + '</project>', encoding="utf-8")

    def test_arch001_allows_api(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.model(root, '<dependencies><dependency><artifactId>pet-order-api</artifactId></dependency></dependencies>')
            self.assertEqual([], deps.check(root)[2])

    def test_arch001_rejects_biz_in_normal_test_and_profile_dependencies(self):
        for scope, profile in (("compile", False), ("test", False), ("compile", True)):
            with self.subTest(scope=scope, profile=profile), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                edge = '<dependencies><dependency><groupId>${project.groupId}</groupId><artifactId>pet-order-biz</artifactId><scope>' + scope + '</scope></dependency></dependencies>'
                self.model(root, '<profiles><profile>' + edge + '</profile></profiles>' if profile else edge)
                self.assertIn("ARCH-001", deps.check(root)[2][0])

    def test_arch001_rejects_empty_reactor(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.model(root, declared=False)
            with self.assertRaisesRegex(ValueError, "ARCH-COVERAGE"):
                deps.check(root)

    def test_arch001_rejects_unlisted_module(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.model(root)
            (root / "pet-order-biz").mkdir()
            (root / "pet-order-biz/pom.xml").write_text('<project/>', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "mismatch"):
                deps.check(root)


class DisplayGateTest(unittest.TestCase):
    def test_arch005_scans_real_backend_and_both_frontend_paths(self):
        repository = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # Production sources only; never copy build outputs or test fixtures.
            shutil.copytree(repository / "backend", root / "backend",
                            ignore=shutil.ignore_patterns("target", "tools", "pet-architecture-test", "evidence"))
            for app in ("frontend-admin", "frontend-miniapp"):
                (root / app / "src").mkdir(parents=True)
            targets = [root / "backend/pet-refund-biz/src/main/java/Bad.java",
                       root / "frontend-admin/src/Bad.ts", root / "frontend-miniapp/src/Bad.ts"]
            for target in targets:
                target.write_text("const displayStatus = order.refundStatus === 'SUCCESS' ? 'REFUNDED' : 'PENDING_SERVICE';")
            inventory, findings = display.check(root)
            self.assertEqual(3, len(findings), findings)
            self.assertEqual(1, inventory["frontend-admin"])
            self.assertEqual(1, inventory["frontend-miniapp"])

    def test_arch005_rejects_present_frontend_with_empty_source_scan(self):
        repository = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            shutil.copytree(repository / "backend", root / "backend",
                            ignore=shutil.ignore_patterns("target", "tools", "pet-architecture-test", "evidence"))
            (root / "frontend-admin").mkdir()
            (root / "frontend-admin/package.json").write_text('{}')
            with self.assertRaisesRegex(ValueError, "source scan is empty"):
                display.check(root)

    def test_arch005_rejects_frontend_priority_ternary_switch_and_lookup(self):
        examples = [
            "const displayStatus = o.refundStatus === 'SUCCESS' ? 'REFUNDED' : 'PENDING_SERVICE';",
            "function status(o) { if (o.paymentStatus === 'PAID') return 'PENDING_CONFIRM'; return 'PENDING_PAYMENT'; }",
            "switch(refundStatus) { case 'SUCCESS': return 'REFUNDED'; }",
            "const statusMap = { SUCCESS: 'REFUNDED' }; const displayStatus = statusMap[o['refundStatus']];",
            "const calculateDisplayStatus = () => 'AFTERSALE';",
            "DisplayOrderStatus decide() { return DisplayOrderStatus.REFUNDED; }",
            "if (refund.type == PARTIAL && refund.status == SUCCESS) return 'PARTIAL_REFUND';",
            "if (order_stage == CANCELED) return 'CANCELED';",
            "const displayStatus = order.orderStage;",
        ]
        for code in examples:
            with self.subTest(code=code):
                self.assertTrue(display.violations(code))

    def test_arch005_allows_order_owner(self):
        self.assertEqual([], display.violations("return o.refundStatus == SUCCESS ? DisplayOrderStatus.REFUNDED : DisplayOrderStatus.PENDING_SERVICE;", True))

    def test_arch005_allows_contract_pass_through_labels_and_snapshot_fixture(self):
        for code in [
            "return order.displayStatus;",
            "const labels = { REFUNDED: '已退款', PENDING_PAYMENT: '待支付' }; return labels[order.displayStatus];",
            "if (order.displayStatus === 'REFUNDED') showRefundBadge();",
            "type Status = 'REFUNDED' | 'PENDING_SERVICE'; interface Order { displayStatus: Status; refundStatus: string; }",
            "const mock = { displayStatus: 'PENDING_PAYMENT', paymentStatus: 'UNPAID' };",
            "class OrderSnapshotDTO { String displayStatus; String refundStatus; String getRefundStatus() { return this.refundStatus; } String getDisplayStatus() { return this.displayStatus; } }",
            "// const calculateDisplayStatus = () => o.refundStatus;\nreturn order.displayStatus;",
        ]:
            with self.subTest(code=code):
                self.assertEqual([], display.violations(code))


class CapabilityGateTest(unittest.TestCase):
    def test_unknown_runtime_cannot_pass(self):
        for capability in ("web-runtime", "wechat-runtime", "visual"):
            with self.subTest(capability=capability), self.assertRaisesRegex(ValueError, "NOT_EXECUTED"):
                frontend.plan(Path("."), capability)

    def test_missing_app_cannot_pass(self):
        with tempfile.TemporaryDirectory() as tmp, self.assertRaisesRegex(ValueError, "NOT_IMPLEMENTED"):
            frontend.plan(Path(tmp), "web-build")

    def test_missing_script_cannot_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "frontend-admin").mkdir()
            (root / "frontend-admin/package.json").write_text('{"scripts":{"build":"vite build"}}')
            (root / "frontend-admin/package-lock.json").write_text('{}')
            with self.assertRaisesRegex(ValueError, "missing required scripts"):
                frontend.plan(root, "web-build")

    def test_miniapp_plan_never_uses_web_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "frontend-miniapp").mkdir()
            (root / "frontend-miniapp/package.json").write_text('{"scripts":{"typecheck":"tsc --noEmit","test":"tsx --test tests.ts","build:weapp":"taro build --type weapp","check:package":"node package-check.cjs"}}')
            (root / "frontend-miniapp/package-lock.json").write_text('{}')
            _, commands = frontend.plan(root, "miniapp-build")
            self.assertIn(["npm", "run", "build:weapp"], commands)
            self.assertNotIn(["npm", "run", "build"], commands)


if __name__ == "__main__":
    unittest.main(verbosity=2)
