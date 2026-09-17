"""Approved MER-001 document schemas; not live HTTP or business E2E."""
import copy
import hashlib
import unittest

import yaml

from contract_smoke import MERCHANT_OPERATIONS, SPEC, check
from test_auth_contract import InstanceMismatch, validate_instance


class MerchantContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = yaml.safe_load(SPEC.read_text(encoding='utf-8'))

    def setUp(self):
        self.spec = copy.deepcopy(self.original)

    def valid(self, name, value):
        validate_instance(self.spec, self.spec['components']['schemas'][name], value)

    def invalid(self, name, value):
        with self.assertRaises(InstanceMismatch):
            self.valid(name, value)

    def consent(self):
        return {'merchantId': '9007199254740993', 'agreementVersion': 'merchant-v1',
                'contentSha256': hashlib.sha256(b'fixture agreement, not production').hexdigest(),
                'accepted': True}

    def test_consent_requires_positive_user_gesture_and_exact_version_content(self):
        valid = self.consent()
        self.valid('MerConsentRequest', valid)
        for field, value in [('accepted', False), ('accepted', 1), ('accepted', None),
                             ('contentSha256', 'bad'), ('agreementVersion', ''),
                             ('merchantId', 9007199254740993)]:
            with self.subTest(field=field, value=value):
                self.invalid('MerConsentRequest', {**valid, field: value})
        for field in valid:
            bad = dict(valid)
            del bad[field]
            self.invalid('MerConsentRequest', bad)

    def test_client_cannot_assert_owner_staff_or_signing_time(self):
        for key in ['userId', 'operatorId', 'staffId', 'acceptedAt', 'signingStatus', 'role']:
            self.invalid('MerConsentRequest', {**self.consent(), key: '123'})

    def test_staff_profile_is_not_login_membership(self):
        staff = {'merchantId': '123', 'storeId': '456', 'staffName': 'Test',
                 'employmentStatus': 'ACTIVE', 'serviceEnabled': True}
        self.valid('MerStaffCreateRequest', staff)
        self.invalid('MerStaffCreateRequest', {**staff, 'userId': '789'})
        self.invalid('MerStaffCreateRequest', {**staff, 'employmentStatus': 'INACTIVE'})
        self.invalid('MerStaffCreateRequest', {**staff, 'staffName': '   '})
        self.valid('MerStaffCreateRequest', {**staff, 'employmentStatus': 'INACTIVE', 'serviceEnabled': False})

    def test_staff_update_rejects_undeclared_mutations_and_numeric_versions(self):
        update = {'merchantId': '123', 'storeId': '456', 'staffName': 'New', 'expectedVersion': '0'}
        self.valid('MerStaffUpdateRequest', update)
        self.valid('MerStaffUpdateRequest', {**update, 'phone': None})
        self.invalid('MerStaffUpdateRequest', {**update, 'serviceEnabled': True})
        self.invalid('MerStaffUpdateRequest', {**update, 'expectedVersion': 0})
        self.invalid('MerStaffUpdateRequest', {**update, 'expectedVersion': '-1'})

    def test_agreement_response_distinguishes_signed_from_not_signed(self):
        view = self.consent()
        del view['accepted']
        view.update(content='fixture agreement, not production', signingStatus='NOT_SIGNED')
        self.valid('MerAgreementView', view)
        self.invalid('MerAgreementView', {**view, 'signingStatus': 'SIGNED'})
        self.valid('MerAgreementView', {**view, 'signingStatus': 'SIGNED',
                   'acceptedVersion': 'merchant-v1', 'acceptedAt': '2026-09-17T08:00:00.000Z'})
        self.invalid('MerAgreementView', {**view, 'acceptedAt': '2026-09-17T08:00:00.000Z'})

    def test_errors_never_contain_old_success_data(self):
        error = {'success': False, 'code': 'COMMON_FORBIDDEN', 'message': 'Denied', 'data': None, 'traceId': 'fixture'}
        self.valid('MerErrorEnvelope', error)
        self.invalid('MerErrorEnvelope', {**error, 'data': self.consent()})
        self.invalid('MerErrorEnvelope', {**error, 'success': True})

    def test_reviewed_operations_cannot_move_or_drop_bearer_or_claim_implementation(self):
        self.assertEqual(check(self.spec)['merchantOperations'], 8)
        for name, (method, path) in MERCHANT_OPERATIONS.items():
            for field, value, message in [('security', [], 'security'),
                                           ('x-contract-status', 'IMPLEMENTED', 'implementation status')]:
                spec = copy.deepcopy(self.original)
                spec['paths'][path][method][field] = value
                with self.subTest(operation=name, field=field), self.assertRaisesRegex(AssertionError, message):
                    check(spec)

    def test_unreviewed_operation_is_still_rejected(self):
        self.spec['paths']['/merchant/unreviewed'] = {'get': copy.deepcopy(self.spec['paths']['/merchant/agreement']['get'])}
        self.spec['paths']['/merchant/unreviewed']['get']['operationId'] = 'merchantUnreviewed'
        with self.assertRaisesRegex(AssertionError, 'Unexpected or missing reviewed operations'):
            check(self.spec)

    def test_new_writes_keep_request_id_and_create_replay_contract(self):
        for name, (method, path) in MERCHANT_OPERATIONS.items():
            if method == 'get':
                continue
            spec = copy.deepcopy(self.original)
            op = spec['paths'][path][method]
            op['parameters'] = [p for p in op['parameters'] if p.get('$ref') != '#/components/parameters/RequestId']
            with self.subTest(operation=name), self.assertRaisesRegex(AssertionError, 'Missing request ID'):
                check(spec)
        for path in ['/merchant/staff', '/merchant/agreement/consent']:
            spec = copy.deepcopy(self.original)
            del spec['paths'][path]['post']['responses']['201']
            with self.assertRaisesRegex(AssertionError, 'Merchant create response missing'):
                check(spec)


if __name__ == '__main__':
    unittest.main()
