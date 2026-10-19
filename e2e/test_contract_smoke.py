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
        # abnormalCloseOrder graduated to the contract-63 v0.2 disposal face (2026-10-15,
        # ruling doc 33); the remaining legacy surface is untouched.
        self.assertEqual(result['legacyOperations'], 13)
        self.assertEqual(result['legacyWrites'], 10)
        self.assertEqual(result['legacyCreates'], 3)
        # Address book family (contract 59, 2026-10-13): six routes — list/detail/create/
        # update/delete/set-default; the legacy 14/11/3 subset stays untouched.
        self.assertEqual(result['addressOperations'], 6)
        self.assertEqual(result['pointsTaskOperations'], 7)
        self.assertEqual(result['favtrackHttpOperations'], 7)
        # Encyclopedia read face (contract 62, 2026-10-14): anonymous-browse GET trio.
        self.assertEqual(result['baikeOperations'], 3)
        # Contract 63 admin trade face (2026-10-14 reads, 2026-10-15 v0.2 disposal writes,
        # ruling doc 33): seven GET reads + four disposal writes = eleven routes.
        self.assertEqual(result['adminTradeOperations'], 11)
        # Contract 64 admin governance read face (2026-10-15, ADM-001 gap slice): eight GET
        # routes over the merchant and schedule kernels (ledger/staff/members/invitations +
        # windows/staff-windows/reservations); read-only, no governance write action.
        self.assertEqual(result['adminGovernanceOperations'], 8)
        # Contract 65 ops console face (2026-10-16, A-005): encyclopedia governance (9) +
        # coupon template management/instance ledger (9) + points ledger (1) + runtime
        # settings view (1) + the v0.2 §4.3 grant channel (1, user ruling 2026-10-16,
        # CCR-CPN-GRANT-001 minimum; kernel switch pet.coupon.grant.enabled stays default off)
        # + the v0.3 §4.4 void channel (1, user ruling 2026-10-16 doc 36 minimal
        # authorization, CCR-CPN-VOID-001; kernel switch pet.coupon.void.enabled stays
        # default off) = twenty-two routes.
        self.assertEqual(result['adminOpsOperations'], 22)
        # C-wiring batch (2026-10-19 ruling): catalog keyword search (1) + open-city
        # directory (1) + contract-68 pet health records (4).
        self.assertEqual(result['catalogSearchOperations'], 1)
        self.assertEqual(result['cityOperations'], 1)
        self.assertEqual(result['petHealthRecordOperations'], 4)
        # Union 212 = 206 + 1 catalog search + 1 city directory + 4 health records
        # (206 = 163 baseline + 3 encyclopedia + 11 admin trade + 8 governance + 22 ops console).
        self.assertEqual(result['operations'], 212)
        self.assertGreater(result['stringIdProperties'], 0)
        # Contract 58 favorite/footprint face (2026-10-13): seven reviewed operations.
        self.assertEqual(result['favtrackHttpOperations'], 7)

    def test_favtrack_switch_change_rejected(self):
        self.spec['paths']['/c/favorites/{targetType}/{targetId}']['post']['x-assembly-switch'] = 'pet.other.enabled'
        with self.assertRaisesRegex(AssertionError, 'Favtrack HTTP switch changed'):
            check(self.spec)

    def test_favtrack_bodyless_write_rejected_when_body_added(self):
        self.spec['paths']['/c/favorites/{targetType}/{targetId}']['post']['requestBody'] = {
            'required': True, 'content': {'application/json': {'schema': {'type': 'object'}}}}
        with self.assertRaisesRegex(AssertionError, 'Favtrack routes must stay business-body-free'):
            check(self.spec)

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
        prop = self.spec['components']['schemas']['AfterSaleCreateRequest']['properties']['evidenceAssetIds']
        prop['items'] = {'allOf': [{'type': 'integer'}]}
        with self.assertRaisesRegex(AssertionError, 'Non-string ID/amount/header: evidenceAssetIds'):
            check(self.spec)

    def test_nullable_id_array_item_rejected(self):
        prop = self.spec['components']['schemas']['AfterSaleCreateRequest']['properties']['evidenceAssetIds']
        prop['items'] = {'type': 'string', 'nullable': True}
        with self.assertRaisesRegex(AssertionError, 'Invalid nullable: evidenceAssetIds'):
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

    def test_address_family_regression_rejected(self):
        # Contract 59 census: the cap error and the anti-enumeration code are pinned members
        # of the address error envelope; dropping either must fail the document smoke.
        codes = self.spec['components']['schemas']['AddressErrorEnvelope']['properties']['code']['enum']
        codes.remove('ADDRESS_LIMIT_EXCEEDED')
        with self.assertRaisesRegex(AssertionError, 'Address cap error code missing'):
            check(self.spec)


if __name__ == '__main__':
    unittest.main()
