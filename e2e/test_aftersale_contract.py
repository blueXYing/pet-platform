"""Contract51 public HTTP document regressions; live runtime is tested separately."""
import copy
import re
import unittest
import yaml
from contract_smoke import SPEC, check, AFTERSALE_OPERATIONS


class AfterSaleContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spec = yaml.safe_load(SPEC.read_text(encoding='utf-8'))

    def reject(self, mutate, message=None):
        altered = copy.deepcopy(self.spec)
        mutate(altered)
        if message:
            with self.assertRaisesRegex(AssertionError, message):
                check(altered)
        else:
            with self.assertRaises(AssertionError):
                check(altered)

    def test_family_is_separate_from_remaining_legacy_and_default_closed(self):
        result = check(self.spec)
        self.assertEqual(23, result['aftersaleOperations'])
        self.assertEqual(14, result['legacyOperations'])
        path = '/c/aftersales'
        self.reject(lambda s: s['paths'][path]['get'].__setitem__('x-default-enabled', True), 'AFS default-off')
        self.reject(lambda s: s['paths'][path]['get'].__setitem__('x-implementation-status', 'INTERNAL_ONLY'), 'AFS default-off')

    def test_every_aftersale_write_requires_identity_and_request_id(self):
        for name, (method, path) in AFTERSALE_OPERATIONS.items():
            operation = self.spec['paths'][path][method]
            self.assertEqual([{'bearerAuth': []}], operation['security'])
            if method != 'post':
                continue
            self.assertIn({'$ref': '#/components/parameters/RequestId'}, operation['parameters'])
            self.reject(lambda s, p=path: s['paths'][p]['post'].__setitem__('parameters', [
                parameter for parameter in s['paths'][p]['post']['parameters']
                if parameter.get('$ref') != '#/components/parameters/RequestId']))

    def test_audiences_and_ops_action_are_pinned(self):
        self.reject(lambda s: s['paths']['/merchant/aftersales']['get'].__setitem__('x-route-party', 'USER'), 'AFS route identity')
        self.reject(lambda s: s['paths']['/admin/aftersales']['get'].__setitem__('security', []), 'security')
        self.reject(lambda s: s['paths']['/admin/aftersales/{afterSaleId}/accept']['post'].__setitem__('x-required-actions', ['aftersale.read']), 'AFS action')

    def test_only_three_final_decisions_execute_over_http(self):
        request = self.spec['components']['schemas']['AfterSaleDecisionRequest']
        self.assertEqual({'FULL_REFUND', 'PARTIAL_REFUND', 'REJECT', 'RESERVICE', 'OTHER'}, set(request['properties']['decisionType']['enum']))
        path = '/admin/aftersales/{afterSaleId}/decisions'
        self.reject(lambda s: s['paths'][path]['post'].__setitem__('x-public-refund-enabled', True), 'AFS public funding')
        self.reject(lambda s: s['paths'][path]['post']['x-executable-decision-types'].append('FULL_REFUND'), 'AFS public funding')
        self.assertNotIn('/admin/aftersales/{afterSaleId}/decision', self.spec['paths'])
        self.reject(lambda s: s['paths'].__setitem__('/admin/aftersales/{afterSaleId}/decision', s['paths'].pop(path)), 'AFS route')

    def test_money_uses_exact_two_decimal_text(self):
        amount = self.spec['components']['schemas']['AfterSaleDecisionRequest']['properties']['refundAmount']
        self.assertEqual('string', amount['type'])
        expression = re.compile(amount['pattern'])
        for valid in ('0.00', '32.00', '128.00', '9999999999999999.99'):
            self.assertIsNotNone(expression.search(valid), valid)
        for invalid in ('0', '32.0', '-1.00', '0.001', '1e2', '10000000000000000.00', '32.00\n'):
            self.assertIsNone(expression.search(invalid), invalid)
        self.reject(lambda s: s['components']['schemas']['AfterSaleDecisionRequest']['properties']['refundAmount'].__setitem__('pattern', '.*'), 'AFS exact decimal')

    def test_strict_body_assets_and_optimistic_version_cannot_disappear(self):
        self.reject(lambda s: s['components']['schemas']['AfterSaleEvidenceRequest'].__setitem__('additionalProperties', True), 'AFS strict DTO')
        self.reject(lambda s: s['components']['schemas']['AfterSaleEvidenceRequest']['required'].remove('expectedVersion'), 'AFS required fields')
        self.reject(lambda s: s['components']['schemas']['AfterSaleCreateRequest']['properties']['evidenceAssetIds'].__setitem__('nullable', True), 'AFS evidence bounds')
        self.reject(lambda s: s['components']['schemas']['AfterSaleCreateRequest']['properties'].__setitem__('objectUrl', {'type': 'string'}), 'AFS fields')
        self.reject(lambda s: s['components']['schemas']['AfterSaleSupplementRequest']['properties']['deadline'].__setitem__('pattern', '.*'), 'AFS deadline')

    def test_pagination_cannot_open_cross_store_or_unbounded_reads(self):
        self.reject(lambda s: s['paths']['/admin/aftersales']['get']['parameters'][-1].__setitem__('required', False), 'AFS global store scope')
        self.reject(lambda s: s['paths']['/c/aftersales']['get']['parameters'][1]['schema'].__setitem__('maximum', 1000), 'AFS page size')
        self.reject(lambda s: s['components']['schemas']['AfterSaleCaseSummary']['properties'].__setitem__('description', {'type': 'string'}), 'AFS summary scope')

    def test_create_and_upload_keep_same_schema_for_first_and_replayed_success(self):
        for path in ('/c/orders/{orderId}/aftersales', '/c/aftersale-evidence-assets'):
            self.reject(lambda s, p=path: s['paths'][p]['post']['responses'].pop('201'), 'AFS create replay')
            self.reject(lambda s, p=path: s['paths'][p]['post']['responses']['201'].__setitem__('content', {}), 'AFS create replay')
        self.reject(lambda s: s['components']['schemas']['AfterSaleReceiptEnvelope']['properties'].__setitem__('success', {'type': 'boolean'}), 'AFS envelope payload')
        self.reject(lambda s: s['components']['schemas']['AfterSaleErrorEnvelope']['properties']['data'].__setitem__('enum', [{}]), 'AFS failure data')

    def test_private_asset_read_remains_binary_and_single_use(self):
        path = '/merchant/aftersale-evidence-read-grants/{token}'
        self.reject(lambda s: s['paths'][path]['get']['responses'].pop('410'), 'AFS private image')
        self.reject(lambda s: s['paths'][path]['get']['responses']['200'].__setitem__('content', {'application/json': {}}), 'AFS private image')


if __name__ == '__main__':
    unittest.main()
