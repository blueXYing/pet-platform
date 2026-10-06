"""Verification HTTP surface pins: the delivered default-off routes cannot silently regress."""
import copy
import unittest
import yaml
from contract_smoke import SPEC, check, VERIFICATION_HTTP_OPERATIONS


class VerificationCredentialContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.baseline = yaml.safe_load(SPEC.read_text(encoding='utf-8'))

    def operation(self, name):
        method, path = VERIFICATION_HTTP_OPERATIONS[name][:2]
        return self.spec['paths'][path][method]

    def test_default_off_route_cannot_be_claimed_enabled(self):
        for name in VERIFICATION_HTTP_OPERATIONS:
            with self.subTest(name=name):
                spec = copy.deepcopy(self.baseline)
                path, method = VERIFICATION_HTTP_OPERATIONS[name][1], VERIFICATION_HTTP_OPERATIONS[name][0]
                spec['paths'][path][method]['x-default-enabled'] = True
                with self.assertRaises(AssertionError):
                    check(spec)

    def test_implementation_status_cannot_fall_back_to_not_implemented(self):
        for name in VERIFICATION_HTTP_OPERATIONS:
            with self.subTest(name=name):
                spec = copy.deepcopy(self.baseline)
                path, method = VERIFICATION_HTTP_OPERATIONS[name][1], VERIFICATION_HTTP_OPERATIONS[name][0]
                spec['paths'][path][method]['x-implementation-status'] = 'NOT_IMPLEMENTED'
                with self.assertRaises(AssertionError):
                    check(spec)

    def test_refresh_cannot_drop_request_id(self):
        spec = copy.deepcopy(self.baseline)
        op = spec['paths']['/c/orders/{orderId}/verification-code']['post']
        op['parameters'] = [p for p in op['parameters'] if p.get('$ref') != '#/components/parameters/RequestId']
        with self.assertRaises(AssertionError):
            check(spec)

    def test_numeric_version_cannot_lose_bigint_precision(self):
        spec = copy.deepcopy(self.baseline)
        spec['components']['schemas']['VerificationCredentialIssue']['properties']['expectedCredentialVersion']['type'] = 'number'
        with self.assertRaises(AssertionError):
            check(spec)

    def test_failed_code_receipts_stay_null_tailed_business_results(self):
        spec = copy.deepcopy(self.baseline)
        receipt = spec['components']['schemas']['OrderVerificationReceipt']
        receipt['properties']['verificationId'].pop('nullable')
        with self.assertRaises(AssertionError):
            check(spec)

    def test_scan_body_cannot_open_beyond_the_scanned_code(self):
        spec = copy.deepcopy(self.baseline)
        op = spec['paths']['/merchant/orders/{orderId}/verification']['post']
        op['requestBody']['content']['application/json']['schema']['properties']['storeId'] = {'type': 'string'}
        with self.assertRaises(AssertionError):
            check(spec)
