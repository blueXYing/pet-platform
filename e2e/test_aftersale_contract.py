"""Public AFS contract invariants; this suite does not assert a delivered HTTP adapter."""
import copy
import re
import unittest
import yaml
from contract_smoke import SPEC, check


class AfterSaleContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spec = yaml.safe_load(SPEC.read_text(encoding='utf-8'))

    def test_every_aftersale_write_requires_identity_and_request_id(self):
        for path in ('/c/orders/{orderId}/aftersales',
                     '/admin/aftersales/{afterSaleId}/decision'):
            operation = self.spec['paths'][path]['post']
            self.assertEqual([{'bearerAuth': []}], operation['security'])
            self.assertIn({'$ref': '#/components/parameters/RequestId'}, operation['parameters'])
            self.assertTrue({'401', '403', '409', '503'} <= operation['responses'].keys())
            altered = copy.deepcopy(self.spec)
            altered['paths'][path]['post']['parameters'] = [
                p for p in operation['parameters']
                if p.get('$ref') != '#/components/parameters/RequestId']
            with self.assertRaises(AssertionError):
                check(altered)

    def test_only_approved_final_decisions_are_public(self):
        request = self.spec['components']['schemas']['AftersaleDecisionRequest']
        self.assertEqual({'FULL_REFUND', 'PARTIAL_REFUND', 'REJECT', 'RESERVICE', 'OTHER'},
                         set(request['properties']['decisionType']['enum']))
        self.assertTrue({'decisionType', 'reason'} <= set(request['required']))

    def test_refund_amount_is_decimal_text_without_precision_loss(self):
        amount = self.spec['components']['schemas']['AftersaleDecisionRequest']['properties']['refundAmount']
        self.assertEqual('string', amount['type'])
        expression = re.compile(amount['pattern'])
        for valid in ('0', '32.00', '128.00', '9999999999999999.99'):
            self.assertIsNotNone(expression.fullmatch(valid), valid)
        for invalid in ('-1', '0.001', '1e2', '10000000000000000', '32.00\n'):
            self.assertIsNone(expression.fullmatch(invalid), invalid)

    def test_evidence_references_are_public_ids_and_not_object_urls(self):
        request = self.spec['components']['schemas']['CreateAftersaleRequest']
        self.assertEqual({'$ref': '#/components/schemas/PublicId'},
                         request['properties']['evidenceFileIds']['items'])
        self.assertNotIn('objectKey', request['properties'])
        self.assertNotIn('downloadUrl', request['properties'])


if __name__ == '__main__':
    unittest.main()
