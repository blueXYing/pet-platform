"""Prove document smoke rejects representative contract regressions."""
import copy
import unittest

import yaml
from contract_smoke import SPEC, check


class ContractSmokeRegressions(unittest.TestCase):
    def setUp(self):
        self.spec = yaml.safe_load(SPEC.read_text(encoding='utf-8'))

    def test_broken_reference_rejected(self):
        self.spec['components']['schemas']['Broken'] = {'$ref': '#/components/schemas/Missing'}
        with self.assertRaises(KeyError):
            check(self.spec)

    def test_numeric_http_id_rejected(self):
        self.spec['components']['schemas']['CreateOrderData']['properties']['orderId']['type'] = 'integer'
        with self.assertRaisesRegex(AssertionError, 'Non-string ID'):
            check(self.spec)

    def test_write_without_request_id_rejected(self):
        self.spec['paths']['/c/orders']['post']['parameters'] = []
        with self.assertRaisesRegex(AssertionError, 'Missing request ID'):
            check(self.spec)

    def test_duplicate_operation_rejected(self):
        self.spec['paths']['/duplicate'] = copy.deepcopy(self.spec['paths']['/c/orders'])
        with self.assertRaisesRegex(AssertionError, 'Duplicate operationId'):
            check(self.spec)


if __name__ == '__main__':
    unittest.main()
