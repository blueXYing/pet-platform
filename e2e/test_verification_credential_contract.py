"""Approved credential routes remain unavailable until the real HTTP adapter is delivered."""
import copy
import unittest
import yaml
from contract_smoke import SPEC, check


class VerificationCredentialContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.baseline = yaml.safe_load(SPEC.read_text(encoding='utf-8'))

    def test_unimplemented_route_cannot_be_claimed_enabled(self):
        spec = copy.deepcopy(self.baseline)
        spec['paths']['/c/orders/{orderId}/verification-code']['post']['x-default-enabled'] = True
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
