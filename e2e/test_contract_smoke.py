"""Prove document smoke rejects representative contract regressions."""
import copy
import unittest

import yaml
from contract_smoke import SPEC, check


class ContractSmokeRegressions(unittest.TestCase):
    def setUp(self):
        self.spec = yaml.safe_load(SPEC.read_text(encoding='utf-8'))

    def set_order_id(self, schema):
        self.spec['components']['schemas']['CreateOrderData']['properties']['orderId'] = schema

    def test_current_document_passes(self):
        result = check(self.spec)
        self.assertEqual(result['operations'], 16)
        self.assertEqual(result['writesWithRequestId'], 13)
        self.assertGreater(result['stringIdProperties'], 0)

    def test_local_reference_chain_and_allof_string_pass(self):
        self.spec['components']['schemas']['IdAlias'] = {'$ref': '#/components/schemas/PublicId'}
        self.set_order_id({'allOf': [
            {'$ref': '#/components/schemas/IdAlias'},
            {'type': 'string', 'description': 'Still a string intersection'},
        ]})
        check(self.spec)

    def test_inline_nullable_id_passes(self):
        self.set_order_id({'type': 'string', 'nullable': True})
        check(self.spec)

    def test_nullable_string_allof_passes(self):
        self.set_order_id({'type': 'string', 'nullable': True, 'allOf': [
            {'type': 'string', 'nullable': True},
        ]})
        check(self.spec)

    def test_broken_reference_rejected(self):
        self.spec['components']['schemas']['Broken'] = {'$ref': '#/components/schemas/Missing'}
        with self.assertRaises(KeyError):
            check(self.spec)

    def test_numeric_http_id_rejected(self):
        self.set_order_id({'type': 'integer'})
        with self.assertRaisesRegex(AssertionError, 'Non-string ID/amount/header: orderId'):
            check(self.spec)

    def test_numeric_id_behind_reference_rejected(self):
        self.spec['components']['schemas']['NumericId'] = {'type': 'integer', 'format': 'int64'}
        self.set_order_id({'$ref': '#/components/schemas/NumericId'})
        with self.assertRaisesRegex(AssertionError, 'Non-string ID/amount/header: orderId'):
            check(self.spec)

    def test_numeric_allof_branch_cannot_be_hidden_by_string(self):
        self.set_order_id({'type': 'string', 'allOf': [{'type': 'integer'}]})
        with self.assertRaisesRegex(AssertionError, 'Non-string ID/amount/header: orderId'):
            check(self.spec)

    def test_missing_type_allof_rejected(self):
        self.set_order_id({'allOf': [{'description': 'No string guarantee'}]})
        with self.assertRaisesRegex(AssertionError, 'Non-string ID/amount/header: orderId'):
            check(self.spec)

    def test_non_string_union_not_treated_as_string(self):
        self.set_order_id({'oneOf': [{'type': 'string'}, {'type': 'integer'}]})
        with self.assertRaisesRegex(AssertionError, 'Unsupported string composition: orderId'):
            check(self.spec)

    def test_ref_sibling_cannot_override_numeric_target(self):
        self.spec['components']['schemas']['NumericId'] = {'type': 'integer'}
        self.set_order_id({'$ref': '#/components/schemas/NumericId', 'type': 'string'})
        with self.assertRaisesRegex(AssertionError, r'Ignored \$ref siblings'):
            check(self.spec)

    def test_nullable_ref_sibling_rejected(self):
        self.set_order_id({'$ref': '#/components/schemas/PublicId', 'nullable': True})
        with self.assertRaisesRegex(AssertionError, r'Ignored \$ref siblings'):
            check(self.spec)

    def test_nullable_without_explicit_type_rejected(self):
        self.set_order_id({'allOf': [{'$ref': '#/components/schemas/PublicId'}], 'nullable': True})
        with self.assertRaisesRegex(AssertionError, 'Invalid nullable: orderId'):
            check(self.spec)

    def test_nullable_cannot_override_nonnullable_allof_reference(self):
        self.set_order_id({'type': 'string', 'nullable': True,
                           'allOf': [{'$ref': '#/components/schemas/PublicId'}]})
        with self.assertRaisesRegex(AssertionError, 'Conflicting nullable allOf: orderId'):
            check(self.spec)

    def test_nullable_must_be_boolean(self):
        self.set_order_id({'type': 'string', 'nullable': 'false'})
        with self.assertRaisesRegex(AssertionError, 'Invalid nullable: orderId'):
            check(self.spec)

    def test_numeric_id_array_item_rejected(self):
        prop = self.spec['components']['schemas']['CreateAftersaleRequest']['properties']['evidenceFileIds']
        prop['items'] = {'allOf': [{'type': 'integer'}]}
        with self.assertRaisesRegex(AssertionError, 'Non-string ID/amount/header: evidenceFileIds'):
            check(self.spec)

    def test_nullable_id_array_item_rejected(self):
        prop = self.spec['components']['schemas']['CreateAftersaleRequest']['properties']['evidenceFileIds']
        prop['items'] = {'type': 'string', 'nullable': True}
        with self.assertRaisesRegex(AssertionError, 'Invalid nullable: evidenceFileIds'):
            check(self.spec)

    def test_inline_nested_id_rejected(self):
        self.spec['components']['schemas']['Nested'] = {'type': 'object', 'properties': {
            'child': {'type': 'object', 'properties': {'ownerId': {'type': 'integer'}}},
        }}
        with self.assertRaisesRegex(AssertionError, 'Non-string ID/amount/header: ownerId'):
            check(self.spec)

    def test_numeric_path_id_rejected(self):
        self.spec['components']['parameters']['OrderId']['schema'] = {'type': 'integer'}
        with self.assertRaisesRegex(AssertionError, 'Non-string ID/amount/header: orderId'):
            check(self.spec)

    def test_nullable_path_id_rejected(self):
        self.spec['components']['parameters']['OrderId']['schema'] = {'type': 'string', 'nullable': True}
        with self.assertRaisesRegex(AssertionError, 'Invalid nullable: orderId'):
            check(self.spec)

    def test_cyclic_reference_rejected(self):
        self.spec['components']['schemas']['A'] = {'$ref': '#/components/schemas/B'}
        self.spec['components']['schemas']['B'] = {'$ref': '#/components/schemas/A'}
        with self.assertRaisesRegex(AssertionError, 'Cyclic reference'):
            check(self.spec)

    def test_cycle_through_allof_rejected(self):
        self.spec['components']['schemas']['A'] = {'allOf': [{'$ref': '#/components/schemas/A'}]}
        with self.assertRaisesRegex(AssertionError, 'Cyclic reference'):
            check(self.spec)

    def test_external_reference_rejected(self):
        self.set_order_id({'$ref': 'https://example.invalid/id.yaml'})
        with self.assertRaisesRegex(AssertionError, 'Unreviewed external reference'):
            check(self.spec)

    def test_escaped_json_pointer_passes(self):
        self.spec['components']['schemas']['Alias/With~Escape'] = {'type': 'string'}
        self.set_order_id({'$ref': '#/components/schemas/Alias~1With~0Escape'})
        check(self.spec)

    def test_amount_ref_still_requires_string(self):
        self.spec['components']['schemas']['NumericAmount'] = {'type': 'number'}
        self.spec['components']['schemas']['DecimalAmount'] = {'$ref': '#/components/schemas/NumericAmount'}
        with self.assertRaisesRegex(AssertionError, 'Non-string ID/amount/header: DecimalAmount'):
            check(self.spec)

    def test_request_id_ref_still_requires_string(self):
        self.spec['components']['parameters']['RequestId']['schema'] = {'allOf': [{'type': 'integer'}]}
        with self.assertRaisesRegex(AssertionError, 'Non-string ID/amount/header: X-Request-Id'):
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
