"""Offline AUTH regression checks using only the existing PyYAML CI dependency.

The instance checker below implements the explicitly supported OpenAPI 3.0
Schema Object subset. Unsupported validation keywords fail the tests; they are
never silently skipped. This is not a replacement for full OpenAPI document
validation, live authentication, provider integration or transaction tests.
"""
import copy
import datetime
from decimal import Decimal
import json
import re
import unittest

import yaml

from contract_smoke import (
    ANONYMOUS_ATTEMPTS, AUTH_OPERATIONS, LEGACY_OPERATIONS, ROOT, SPEC, WEB_ATTEMPT_OPERATIONS,
    check, dereference,
)


class InstanceMismatch(AssertionError):
    """An instance fails a supported schema constraint (without logging data)."""


ANNOTATIONS = {
    'title', 'description', 'default', 'example', 'deprecated', 'readOnly',
    'writeOnly', 'discriminator', 'xml', 'externalDocs',
}
CONSTRAINTS = {
    'type', 'nullable', 'enum', 'allOf', 'oneOf', 'anyOf', 'not', 'properties',
    'required', 'additionalProperties', 'minProperties', 'maxProperties',
    'items', 'minItems', 'maxItems', 'uniqueItems', 'minLength', 'maxLength',
    'pattern', 'format', 'minimum', 'maximum', 'exclusiveMinimum',
    'exclusiveMaximum', 'multipleOf',
}
TYPES = {'object', 'array', 'string', 'integer', 'number', 'boolean'}


def json_equal(left, right):
    if isinstance(left, bool) or isinstance(right, bool):
        return type(left) is type(right) and left == right
    if isinstance(left, dict) and isinstance(right, dict):
        return left.keys() == right.keys() and all(json_equal(left[k], right[k]) for k in left)
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(json_equal(a, b) for a, b in zip(left, right))
    return left == right


def assert_supported_schema(spec, schema):
    schema = dereference(spec, schema)
    unknown = set(schema) - ANNOTATIONS - CONSTRAINTS
    unknown = {key for key in unknown if not key.startswith('x-')}
    assert not unknown, f'Unsupported OAS3 validation keywords: {sorted(unknown)}'
    if 'type' in schema:
        assert schema['type'] in TYPES, 'OAS3 type must be a supported scalar type name'
    if 'nullable' in schema:
        assert type(schema['nullable']) is bool, 'OAS3 nullable must be boolean'
    for key in ('exclusiveMinimum', 'exclusiveMaximum', 'uniqueItems'):
        if key in schema:
            assert type(schema[key]) is bool, f'OAS3 {key} must be boolean'
    for key in ('allOf', 'oneOf', 'anyOf'):
        if key in schema:
            assert isinstance(schema[key], list) and schema[key], f'Empty {key}'
            for child in schema[key]:
                assert_supported_schema(spec, child)
    for child in schema.get('properties', {}).values():
        assert_supported_schema(spec, child)
    for key in ('items', 'not'):
        if key in schema:
            assert_supported_schema(spec, schema[key])
    additional = schema.get('additionalProperties', True)
    assert isinstance(additional, (bool, dict)), 'Invalid additionalProperties'
    if isinstance(additional, dict):
        assert_supported_schema(spec, additional)
    if 'format' in schema:
        assert schema['format'] in {'uuid', 'date-time', 'date', 'password', 'int32', 'int64',
                                    'float', 'double', 'byte', 'binary'}, 'Unsupported format assertion'


def validate_instance(spec, schema, instance, path='$'):
    """Validate this OAS3 subset, preserving nullable and oneOf semantics."""
    assert_supported_schema(spec, schema)
    _validate_instance(spec, schema, instance, path)


def _validate_instance(spec, schema, value, path):
    schema = dereference(spec, schema)

    def require(condition, keyword):
        if not condition:
            raise InstanceMismatch(f'{path}: {keyword}')

    kind = schema.get('type')
    if kind is not None:
        accepted = {
            'object': isinstance(value, dict),
            'array': isinstance(value, list),
            'string': isinstance(value, str),
            'boolean': type(value) is bool,
            'integer': type(value) is int or (type(value) is float and value.is_integer()),
            'number': type(value) in (int, float),
        }[kind]
        require(accepted or (value is None and schema.get('nullable') is True), 'type/nullable')
    if 'enum' in schema:
        require(any(json_equal(value, item) for item in schema['enum']), 'enum')
    for branch in schema.get('allOf', []):
        _validate_instance(spec, branch, value, path)
    for composition in ('oneOf', 'anyOf'):
        if composition in schema:
            matches = 0
            for branch in schema[composition]:
                try:
                    _validate_instance(spec, branch, value, path)
                    matches += 1
                except InstanceMismatch:
                    pass
            require(matches == 1 if composition == 'oneOf' else matches > 0, composition)
    if 'not' in schema:
        try:
            _validate_instance(spec, schema['not'], value, path)
        except InstanceMismatch:
            pass
        else:
            raise InstanceMismatch(f'{path}: not')
    if isinstance(value, dict):
        require(set(schema.get('required', [])) <= value.keys(), 'required')
        require(len(value) >= schema.get('minProperties', 0), 'minProperties')
        require(len(value) <= schema.get('maxProperties', float('inf')), 'maxProperties')
        properties = schema.get('properties', {})
        additional = schema.get('additionalProperties', True)
        for name, item in value.items():
            if name in properties:
                _validate_instance(spec, properties[name], item, f'{path}.{name}')
            elif additional is False:
                raise InstanceMismatch(f'{path}: additionalProperties')
            elif isinstance(additional, dict):
                _validate_instance(spec, additional, item, f'{path}.*')
    elif isinstance(value, list):
        require(len(value) >= schema.get('minItems', 0), 'minItems')
        require(len(value) <= schema.get('maxItems', float('inf')), 'maxItems')
        if schema.get('uniqueItems'):
            require(not any(json_equal(item, earlier) for i, item in enumerate(value)
                            for earlier in value[:i]), 'uniqueItems')
        if 'items' in schema:
            for i, item in enumerate(value):
                _validate_instance(spec, schema['items'], item, f'{path}[{i}]')
    elif isinstance(value, str):
        require(len(value) >= schema.get('minLength', 0), 'minLength')
        require(len(value) <= schema.get('maxLength', float('inf')), 'maxLength')
        if 'pattern' in schema:
            require(re.search(schema['pattern'], value) is not None, 'pattern')
        if schema.get('format') == 'uuid':
            require(re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', value) is not None, 'uuid')
        if schema.get('format') in {'date', 'date-time'}:
            try:
                if schema['format'] == 'date':
                    datetime.date.fromisoformat(value)
                else:
                    require(re.fullmatch(r'\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:[Zz]|[+-]\d{2}:\d{2})', value) is not None, 'date-time')
                    parsed = datetime.datetime.fromisoformat(value.upper().replace('Z', '+00:00'))
                    require(parsed.tzinfo is not None, 'date-time offset')
            except ValueError:
                raise InstanceMismatch(f'{path}: calendar date') from None
    elif type(value) in (int, float):
        if 'minimum' in schema:
            require(value > schema['minimum'] if schema.get('exclusiveMinimum') else value >= schema['minimum'], 'minimum')
        if 'maximum' in schema:
            require(value < schema['maximum'] if schema.get('exclusiveMaximum') else value <= schema['maximum'], 'maximum')
        if 'multipleOf' in schema:
            require(Decimal(str(value)) % Decimal(str(schema['multipleOf'])) == 0, 'multipleOf')


class OasSubsetCheckerRegressions(unittest.TestCase):
    def valid(self, schema, value):
        validate_instance({}, schema, value)

    def invalid(self, schema, value):
        with self.assertRaises(InstanceMismatch):
            self.valid(schema, value)

    def test_required_unknown_and_null_are_distinct(self):
        schema = {'type': 'object', 'required': ['id'], 'additionalProperties': False,
                  'properties': {'id': {'type': 'string', 'nullable': True}}}
        self.valid(schema, {'id': None})
        self.invalid(schema, {})
        self.invalid(schema, {'id': '1', 'extra': True})
        self.invalid(schema, {'id': 1})

    def test_nullable_does_not_override_composed_or_enum_constraints(self):
        self.invalid({'type': 'string', 'nullable': True, 'enum': ['A']}, None)
        self.invalid({'type': 'string', 'nullable': True,
                      'allOf': [{'type': 'string'}]}, None)
        self.valid({'type': 'string', 'nullable': True, 'enum': ['A', None]}, None)

    def test_oneof_requires_exactly_one_anyof_needs_at_least_one(self):
        branches = [{'type': 'string'}, {'enum': ['A']}]
        self.invalid({'oneOf': branches}, 'A')
        self.valid({'anyOf': branches}, 'A')
        self.valid({'oneOf': branches}, 'B')
        self.invalid({'oneOf': branches}, 1)

    def test_boolean_is_not_number_and_numeric_equality_is_json_semantics(self):
        self.invalid({'type': 'integer'}, True)
        self.invalid({'enum': [1]}, True)
        self.valid({'type': 'integer'}, 1.0)
        self.invalid({'type': 'array', 'uniqueItems': True}, [1, 1.0])
        self.valid({'type': 'array', 'uniqueItems': True}, [True, 1])

    def test_pattern_search_and_bounds(self):
        self.valid({'type': 'string', 'pattern': 'A'}, 'xAy')
        self.invalid({'type': 'string', 'pattern': r'^A(?![\s\S])'}, 'A\n')
        self.invalid({'type': 'integer', 'minimum': 1, 'maximum': 3}, 0)
        self.invalid({'type': 'integer', 'minimum': 1, 'exclusiveMinimum': True}, 1)
        self.invalid({'type': 'array', 'maxItems': 1}, ['a', 'b'])

    def test_unknown_dialect_keywords_and_ref_siblings_fail_loudly(self):
        with self.assertRaisesRegex(AssertionError, 'Unsupported OAS3'):
            self.valid({'type': 'object', 'unevaluatedProperties': False}, {})
        # Without a local type nullable has no effect; it cannot loosen allOf.
        self.invalid({'nullable': True, 'allOf': [{'type': 'string'}]}, None)
        with self.assertRaisesRegex(AssertionError, 'Ignored'):
            validate_instance({}, {'$ref': '#/anything', 'nullable': True}, None)

    def test_date_time_requires_offset_and_real_calendar(self):
        self.valid({'type': 'string', 'format': 'date-time'}, '2026-09-14T15:00:00.000+08:00')
        self.invalid({'type': 'string', 'format': 'date-time'}, '2026-02-30T15:00:00Z')
        self.invalid({'type': 'string', 'format': 'date-time'}, '2026-09-14T15:00:00')


class AuthSurfaceRegressions(unittest.TestCase):
    def setUp(self):
        self.spec = yaml.safe_load(SPEC.read_text(encoding='utf-8'))

    def operation(self, name):
        method, path = AUTH_OPERATIONS[name]
        return self.spec['paths'][path][method]

    def rejected(self, pattern):
        return self.assertRaisesRegex(AssertionError, pattern)

    def test_reviewed_surface_is_named_and_independently_counted(self):
        result = check(self.spec)
        self.assertEqual(len(LEGACY_OPERATIONS), 16)
        self.assertEqual(len(AUTH_OPERATIONS), 36)
        self.assertEqual(result['legacyOperations'], 16)
        self.assertEqual(result['legacyWrites'], 13)
        self.assertEqual(result['legacyCreates'], 4)
        self.assertEqual(result['authOperations'], 36)

    def test_business_security_cannot_be_cleared(self):
        self.spec['paths']['/c/orders']['post']['security'] = []
        with self.rejected('Missing security|Legacy security'):
            check(self.spec)

    def test_missing_legacy_operation_cannot_be_replaced_by_auth_count(self):
        del self.spec['paths']['/c/orders']['get']
        with self.rejected('Legacy operations missing'):
            check(self.spec)

    def test_legacy_create_replay_response_cannot_be_removed(self):
        del self.spec['paths']['/c/orders']['post']['responses']['200']
        with self.rejected('Legacy replay responses missing'):
            check(self.spec)

    def test_legacy_concrete_create_schemas_cannot_both_be_erased(self):
        responses = self.spec['paths']['/c/orders']['post']['responses']
        del responses['200']['content']
        del responses['201']['content']
        with self.rejected('Legacy create schema missing'):
            check(self.spec)

    def test_legacy_operation_cannot_move_under_a_different_path(self):
        operation = self.spec['paths']['/c/orders'].pop('get')
        self.spec['paths']['/unreviewed-orders'] = {'get': operation}
        with self.rejected('Legacy operation moved'):
            check(self.spec)

    def test_web_attempt_cookie_is_and_not_or(self):
        for name in WEB_ATTEMPT_OPERATIONS:
            with self.subTest(operation=name):
                original = copy.deepcopy(self.operation(name)['security'])
                self.operation(name)['security'] = [{'authAttempt': []}, {'adminBinding': []}]
                with self.rejected('AUTH security mismatch'):
                    check(self.spec)
                self.operation(name)['security'] = original

    def test_only_bootstrap_and_credentialed_refresh_use_empty_security(self):
        self.operation('adminAuthGetPermissions')['security'] = []
        with self.rejected('AUTH security mismatch'):
            check(self.spec)

    def test_anonymous_or_branch_cannot_bypass_attempt_proof(self):
        self.operation('cAuthWechatLogin')['security'].append({})
        with self.rejected('AUTH security mismatch'):
            check(self.spec)

    def test_web_attempt_origin_cannot_be_optional(self):
        operation = self.operation('adminAuthLogin')
        operation['parameters'] = [p for p in operation.get('parameters', [])
                                   if dereference(self.spec, p).get('name') != 'Origin']
        with self.rejected('Missing required Origin'):
            check(self.spec)

    def test_business_bearer_does_not_satisfy_an_attempt_endpoint(self):
        self.operation('cAuthSmsLogin')['security'] = [{'bearerAuth': []}]
        with self.rejected('AUTH security mismatch'):
            check(self.spec)

    def test_phone_binding_keeps_two_distinct_authentication_alternatives(self):
        self.operation('cAccountBindPhone')['security'] = [{'bearerAuth': [], 'authAttempt': []}]
        with self.rejected('AUTH security mismatch'):
            check(self.spec)

    def test_undefined_security_does_not_inherit_anonymous(self):
        self.spec['security'] = []
        del self.operation('cAuthCreateAttempt')['security']
        with self.rejected('AUTH security mismatch'):
            check(self.spec)

    def test_refresh_cannot_drop_body_credential_or_claim_bootstrap(self):
        refresh = self.operation('cAuthRefresh')
        refresh['x-auth-mode'] = 'anonymous-bootstrap'
        with self.rejected('Refresh must be credentialed body'):
            check(self.spec)

    def test_refresh_body_and_token_are_required(self):
        self.operation('cAuthRefresh')['requestBody']['required'] = False
        with self.rejected('Refresh body must be required'):
            check(self.spec)

    def test_attempt_creation_does_not_inherit_business_201_replay(self):
        for name in ANONYMOUS_ATTEMPTS:
            with self.subTest(operation=name):
                operation = self.operation(name)
                operation['responses']['200'] = copy.deepcopy(operation['responses']['201'])
                with self.rejected('Bootstrap must not promise public success replay'):
                    check(self.spec)
                del operation['responses']['200']

    def test_operator_account_creation_does_keep_201_business_replay(self):
        self.operation('adminCreateOperatorAccount')['x-secret-replay'] = 'NEVER_BY_REQUEST_ID_ALONE'
        with self.rejected('Account creation is not anonymous secret bootstrap'):
            check(self.spec)

    def test_refresh_credential_cannot_be_nullable_or_unrequired(self):
        schema = self.spec['components']['schemas']['AuthRefreshRequest']
        schema['required'] = []
        with self.rejected('Refresh credential must be required'):
            check(self.spec)

    def test_attempt_security_scheme_cannot_be_a_public_id_header(self):
        self.spec['components']['securitySchemes']['authAttempt']['name'] = 'X-Attempt-Id'
        with self.rejected('AUTH security scheme changed'):
            check(self.spec)

    def test_auth_operation_missing_or_renamed_is_not_counted_as_covered(self):
        self.operation('cAuthLogout')['operationId'] = 'unreviewedLogout'
        with self.rejected('Unexpected or missing reviewed operations'):
            check(self.spec)

    def test_added_operations_do_not_claim_backend_implementation(self):
        self.operation('cAuthLogout')['x-contract-status'] = 'IMPLEMENTED'
        with self.rejected('AUTH implementation status changed'):
            check(self.spec)

    def test_active_schema_has_no_removed_mfa_fields_or_states(self):
        def inspect(node):
            if isinstance(node, dict):
                for name in node.get('properties', {}):
                    self.assertNotIn('mfa', name.lower(), 'Removed MFA field in active schema')
                for value in node.get('enum', []):
                    if isinstance(value, str):
                        self.assertNotIn('MFA', value.upper(), 'Removed MFA state in active schema')
                for child in node.values():
                    inspect(child)
            elif isinstance(node, list):
                for child in node:
                    inspect(child)
        inspect(self.spec['components']['schemas'])

    def test_document_schemas_stay_within_the_exercised_oas3_subset(self):
        for name, schema in self.spec['components']['schemas'].items():
            with self.subTest(schema=name):
                assert_supported_schema(self.spec, schema)

    def test_declared_schema_and_media_examples_validate_as_instances(self):
        def inspect_schema(schema):
            if '$ref' in schema:
                return 0  # Each declared target is visited separately below.
            count = 0
            if 'example' in schema:
                validate_instance(self.spec, schema, schema['example'])
                count += 1
            for child in schema.get('properties', {}).values():
                count += inspect_schema(child)
            for key in ('allOf', 'oneOf', 'anyOf'):
                for child in schema.get(key, []):
                    count += inspect_schema(child)
            for key in ('items', 'not'):
                if key in schema:
                    count += inspect_schema(schema[key])
            return count

        for name, schema in self.spec['components']['schemas'].items():
            with self.subTest(schema=name):
                inspect_schema(schema)
        for name in AUTH_OPERATIONS:
            operation = self.operation(name)
            containers = [dereference(self.spec, r) for r in operation['responses'].values()]
            if 'requestBody' in operation:
                containers.append(dereference(self.spec, operation['requestBody']))
            for container in containers:
                for media in container.get('content', {}).values():
                    if 'schema' not in media:
                        continue
                    with self.subTest(operation=name):
                        if 'example' in media:
                            validate_instance(self.spec, media['schema'], media['example'])
                        for example in media.get('examples', {}).values():
                            example = dereference(self.spec, example)
                            self.assertIn('value', example, 'External examples are not exercised offline')
                            validate_instance(self.spec, media['schema'], example['value'])


class AuthSchemaInstances(unittest.TestCase):
    """Positive/negative JSON instances against the real checked-in OAS schemas."""
    @classmethod
    def setUpClass(cls):
        cls.spec = yaml.safe_load(SPEC.read_text(encoding='utf-8'))

    def valid(self, name, value):
        validate_instance(self.spec, self.spec['components']['schemas'][name], value)

    def invalid(self, name, value):
        with self.assertRaises(InstanceMismatch):
            self.valid(name, value)

    def example(self, number, data=False):
        value = copy.deepcopy(self.spec['components']['examples'][f'AuthApprovedExample{number}']['value'])
        return value['data'] if data else value

    def request_schema(self, operation_id):
        method, path = AUTH_OPERATIONS[operation_id]
        operation = self.spec['paths'][path][method]
        return operation['requestBody']['content']['application/json']['schema']

    def test_accepted_examples_match_source_and_real_operation_schemas(self):
        source = (ROOT / 'planning/ccr/AUTH-001/examples.md').read_text(encoding='utf-8')
        examples = [json.loads(block) for block in re.findall(r'(?ms)^```json\s*\n(.*?)^```', source)]
        self.assertEqual(len(examples), 10)
        bindings = {
            1: ('cAuthWechatLogin', None), 2: ('cAuthWechatLogin', '200'),
            3: ('cAuthWechatLogin', '200'), 4: ('merchantAuthCheckAdmission', '200'),
            5: ('adminAuthLogin', '200'), 6: ('adminAuthGetSession', '200'),
            7: ('adminAuthGetPermissions', '200'), 8: ('adminSetOperatorAuthorization', None),
            9: ('adminAuthGetPermissions', '403'), 10: ('cAuthGetSmsIntent', '200'),
        }
        self.assertEqual(set(bindings), set(range(1, 11)))
        for number, (operation_id, status) in bindings.items():
            with self.subTest(example=number, operation=operation_id):
                value = self.example(number)
                self.assertEqual(value, examples[number - 1])
                if status is None:
                    schema = self.request_schema(operation_id)
                else:
                    method, path = AUTH_OPERATIONS[operation_id]
                    response = dereference(self.spec, self.spec['paths'][path][method]['responses'][status])
                    schema = response['content']['application/json']['schema']
                validate_instance(self.spec, schema, value)

    def test_all_24_auth_writes_have_positive_and_unknown_field_negative_instances(self):
        proof = {'attemptId': '9007199254740993', 'challengeId': '9007199254740994',
                 'phone': '19900000000', 'code': '123456'}
        command = {'expectedVersion': '7', 'reason': 'EXAMPLE_ONLY_REASON', 'confirmed': True}
        scope = {'mode': 'MERCHANT', 'cityCodes': [], 'merchantIds': ['2001']}
        fixtures = {
            'cAuthCreateAttempt': {'purpose': 'WECHAT_LOGIN'},
            'cAuthWechatLogin': self.example(1),
            'cAuthSendSms': {'attemptId': proof['attemptId'], 'phone': proof['phone'], 'purpose': 'LOGIN'},
            'cAuthSmsLogin': proof,
            'cAuthPasswordLogin': {'attemptId': proof['attemptId'], 'phone': proof['phone'], 'password': 'EXAMPLE_ONLY_password_1!'},
            'cAccountResetPassword': {**proof, 'newPassword': 'EXAMPLE_ONLY_password_1!'},
            'cAccountBindPhone': {'phoneCode': 'EXAMPLE_ONLY_PHONE_CODE'},
            'cAuthRefresh': {'refreshToken': 'EXAMPLE_ONLY_REFRESH'},
            'cAuthLogout': {}, 'adminAuthCreateAttempt': {},
            'adminAuthLogin': {'attemptId': '4001', 'account': 'example-operator', 'password': 'EXAMPLE_ONLY_password_1!'},
            'adminAuthCreateCaptcha': {'attemptId': '4001'},
            'adminAuthVerifyCaptcha': {'attemptId': '4001', 'captchaId': '4002', 'answer': 'EXAMPLE'},
            'adminAuthLogout': {}, 'adminAuthActivity': {},
            'adminCreateOperatorAccount': {'account': 'example-operator', 'displayName': 'Example',
                'initialPassword': 'EXAMPLE_ONLY_password_1!', 'roleIds': ['5002'],
                'extraActionCodes': [], 'dataScope': scope, 'reason': command['reason'], 'confirmed': True},
            'adminUpdateOperatorAccount': {'displayName': 'Example', 'expectedVersion': '7', 'reason': command['reason']},
            'adminSetOperatorAuthorization': self.example(8),
            'adminDisableOperatorAccount': command, 'adminEnableOperatorAccount': command,
            'adminResetOperatorPassword': {**command, 'newPassword': 'EXAMPLE_ONLY_password_1!'},
            'adminConfigureRole': {**command, 'displayName': 'Example', 'actionCodes': ['refund.read']},
            'adminDisableRole': command, 'adminEnableRole': command,
        }
        expected = {name for name, (method, _) in AUTH_OPERATIONS.items() if method != 'get'}
        self.assertEqual(set(fixtures), expected)
        self.assertEqual(len(fixtures), 24)
        for operation_id, value in fixtures.items():
            with self.subTest(operation=operation_id):
                schema = self.request_schema(operation_id)
                validate_instance(self.spec, schema, value)
                with self.assertRaises(InstanceMismatch):
                    validate_instance(self.spec, schema, {**value, 'unapprovedField': 'EXAMPLE'})
                with self.assertRaises(InstanceMismatch):
                    validate_instance(self.spec, schema, None)

    def test_session_audiences_credentials_and_id_types_are_separate(self):
        mini, web = self.example(3, True), self.example(5, True)
        self.valid('AuthMiniSessionGrant', mini)
        self.valid('AuthWebSessionGrant', web)
        self.invalid('AuthWebSessionGrant', mini)
        self.invalid('AuthMiniSessionGrant', web)
        for field in ('userId', 'refreshToken', 'mfaVerified'):
            with self.subTest(field=field):
                self.invalid('AuthWebSessionGrant', {**web, field: 'EXAMPLE'})
        self.invalid('AuthMiniSessionGrant', {**mini, 'sessionId': 9007199254740993})
        self.invalid('AuthMiniSessionGrant', {**mini, 'accessToken': ''})
        self.invalid('AuthMiniSessionGrant', {k: v for k, v in mini.items() if k != 'refreshToken'})

    def test_attempt_progress_cannot_leak_secrets_or_restore_mfa(self):
        progress = self.example(2, True)
        self.valid('AuthAttemptProgress', progress)
        self.invalid('AuthAttemptProgress', {**progress, 'attemptToken': 'EXAMPLE_ONLY_SECRET'})
        self.invalid('AuthAttemptProgress', {**progress, 'accessToken': 'EXAMPLE_ONLY_SECRET'})
        self.invalid('AuthAttemptProgress', {**progress, 'nextStep': 'MFA'})
        self.invalid('AuthWebAttemptResult', progress)  # VERIFY_PHONE is miniapp-only.

    def test_attempt_creation_and_sms_purposes_are_not_interchangeable(self):
        for purpose in ('WECHAT_LOGIN', 'SMS_LOGIN', 'PASSWORD_LOGIN', 'PASSWORD_RESET'):
            self.valid('AuthMiniAttemptRequest', {'purpose': purpose})
        self.invalid('AuthMiniAttemptRequest', {'purpose': 'RESET_PASSWORD'})
        send = {'attemptId': '1', 'phone': '19900000000', 'purpose': 'RESET_PASSWORD'}
        self.valid('AuthSmsSendRequest', send)
        self.invalid('AuthSmsSendRequest', {**send, 'purpose': 'PASSWORD_RESET'})
        self.invalid('AuthSmsSendRequest', {**send, 'purpose': 'MFA'})
        # Matching an issued challenge to an actual attempt is server-side, not an OAS claim.

    def test_sms_phone_and_otp_shape_and_missing_proof(self):
        login = {'attemptId': '1', 'challengeId': '2', 'phone': '19900000000', 'code': '123456'}
        self.valid('AuthSmsLoginRequest', login)
        for code in ('12345', '1234567', '１２３４５６', '12345\n', 123456, None):
            with self.subTest(code_shape=type(code).__name__):
                self.invalid('AuthSmsLoginRequest', {**login, 'code': code})
        self.invalid('AuthSmsLoginRequest', {**login, 'phone': '+8619900000000'})
        self.invalid('AuthSmsLoginRequest', {k: v for k, v in login.items() if k != 'challengeId'})

    def test_phone_binding_union_rejects_forged_subject_fields(self):
        self.valid('AuthPhoneBindingRequest', {'phoneCode': 'EXAMPLE'})
        self.valid('AuthPhoneBindingRequest', {'phoneCode': 'EXAMPLE', 'attemptId': '1'})
        for extra in ({'attemptId': None}, {'staffId': '1'}, {'userId': '1'}, {'workspace': 'merchant'}):
            self.invalid('AuthPhoneBindingRequest', {'phoneCode': 'EXAMPLE', **extra})
        self.invalid('AuthPhoneBindingRequest', {})

    def test_refresh_requires_its_own_nonempty_secret(self):
        self.valid('AuthRefreshRequest', {'refreshToken': 'EXAMPLE_ONLY_REFRESH'})
        for value in ({}, {'refreshToken': ''}, {'refreshToken': None}, {'accessToken': 'EXAMPLE'},
                      {'refreshToken': 'EXAMPLE', 'audience': 'ADMIN_WEB'}):
            self.invalid('AuthRefreshRequest', value)

    def test_sms_intent_status_and_next_action_are_a_discriminated_union(self):
        request_id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
        for state in ('PENDING', 'UNKNOWN'):
            value = {'requestId': request_id, 'status': state, 'nextAction': 'QUERY_SAME_REQUEST'}
            self.valid('AuthSmsIntentStatus', value)
            self.invalid('AuthSmsIntentStatus', {**value, 'challengeId': '1'})
            self.invalid('AuthSmsIntentStatus', {**value, 'nextAction': 'START_NEW_ATTEMPT'})
        self.valid('AuthSmsIntentStatus', {'requestId': request_id, 'status': 'REJECTED', 'nextAction': 'START_NEW_ATTEMPT'})
        accepted = {'requestId': request_id, 'status': 'ACCEPTED', 'nextAction': 'ENTER_CODE',
                    'challengeId': '1', 'expiresAt': '2026-09-14T15:05:00.000+08:00',
                    'resendAfterAt': '2026-09-14T15:01:00.000+08:00'}
        self.valid('AuthSmsIntentStatus', accepted)
        for field in ('challengeId', 'expiresAt', 'resendAfterAt'):
            self.invalid('AuthSmsIntentStatus', {k: v for k, v in accepted.items() if k != field})
        self.invalid('AuthSmsIntentStatus', {**accepted, 'requestId': '1'})

    def test_attempt_result_kind_cannot_carry_another_kind_of_receipt(self):
        result = {'attemptId': '1', 'nextStep': 'COMPLETED', 'expiresAt': '2026-09-14T15:10:00.000+08:00',
                  'commandResult': {'requestId': 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
                                    'kind': 'PASSWORD_RESET', 'data': {'updated': True}}}
        self.valid('AuthMiniAttemptResult', result)
        self.invalid('AuthWebAttemptResult', result)
        bad = copy.deepcopy(result)
        bad['commandResult']['data'] = self.example(3, True)
        self.invalid('AuthMiniAttemptResult', bad)
        bad['commandResult']['kind'] = 'SESSION_GRANT'
        self.valid('AuthMiniAttemptResult', bad)
        self.invalid('AuthWebAttemptResult', bad)
        self.invalid('AuthPasswordUpdated', {'updated': True, 'accessToken': 'EXAMPLE'})

    def test_scope_modes_have_disjoint_fields_and_none_is_response_only(self):
        all_scope = {'mode': 'ALL', 'cityCodes': [], 'merchantIds': []}
        city = {'mode': 'CITY', 'cityCodes': ['310100'], 'merchantIds': []}
        merchant = {'mode': 'MERCHANT', 'cityCodes': [], 'merchantIds': ['2001']}
        for value in (all_scope, city, merchant):
            self.valid('AuthDataScopeRequest', value)
            self.valid('AuthDataScope', value)
        none = {'mode': 'NONE', 'cityCodes': [], 'merchantIds': []}
        self.valid('AuthDataScope', none)
        self.invalid('AuthDataScopeRequest', none)
        for bad in ({**merchant, 'merchantIds': []}, {**all_scope, 'cityCodes': ['310100']},
                    {**city, 'merchantIds': ['2001']}, {**merchant, 'merchantIds': ['2001', '2001']},
                    {**merchant, 'merchantIds': [2001]}, {**city, 'cityCodes': [str(i) for i in range(101)]}):
            self.invalid('AuthDataScopeRequest', bad)

    def test_grant_changes_require_confirmation_reason_versions_and_unique_roles(self):
        grant = self.example(8)
        self.valid('AuthSetAuthorizationRequest', grant)
        mutations = {'confirmed': False, 'reason': '   ', 'expectedVersion': '01', 'roleIds': [],
                     'extraActionCodes': ['refund.retry', 'refund.retry'], 'secondApprover': '1'}
        for key, value in mutations.items():
            with self.subTest(field=key):
                self.invalid('AuthSetAuthorizationRequest', {**grant, key: value})
        self.invalid('AuthSetAuthorizationRequest', {**grant, 'roleIds': ['5002'] * 2})
        self.invalid('AuthSetAuthorizationRequest', {k: v for k, v in grant.items() if k != 'extraActionCodes'})

    def test_membership_owner_does_not_invent_staff_identity(self):
        member = {'merchantId': '2001', 'merchantName': 'Example', 'storeId': '3001',
                  'storeName': 'Example', 'membershipKind': 'OWNER'}
        self.valid('AuthMerchantMembership', member)
        self.invalid('AuthMerchantMembership', {**member, 'staffId': '1'})
        self.valid('AuthMerchantMembership', {**member, 'membershipKind': 'STAFF', 'staffId': '1'})

    def test_admission_schema_preserves_unknown_and_owner_staff_fact_distinction(self):
        admission = self.example(4, True)
        self.valid('AuthMerchantAdmission', admission)
        self.assertEqual(admission['facts']['signing']['status'], 'UNKNOWN')
        self.assertEqual(admission['allowedActions'], [])
        self.invalid('AuthMerchantAdmission', {**admission, 'staffId': '1'})
        wrong = copy.deepcopy(admission)
        wrong['facts']['staffEnabled'] = True
        self.invalid('AuthMerchantAdmission', wrong)
        staff = copy.deepcopy(admission)
        staff['membershipKind'] = 'STAFF'
        staff['facts']['staffEnabled'] = False
        self.valid('AuthMerchantAdmission', staff)
        staff['facts']['staffEnabled'] = None
        self.invalid('AuthMerchantAdmission', staff)
        # OAS shape is not a Provider or business admission decision engine.

    def admission_case(self, membership, state):
        """Synthetic schema inputs, never evidence of a real signing Provider."""
        value = self.example(4, True)
        value['membershipKind'] = membership
        value['admission'] = state
        value['facts']['staffEnabled'] = True if membership == 'STAFF' else None
        if state == 'ALLOWED':
            value['facts']['signing']['status'] = 'SIGNED'
            value['reasonCodes'] = []
            value['allowedActions'] = ['merchant.order.read']
            value['nextSteps'] = []
        elif state == 'LIMITED':
            # UNKNOWN stays UNKNOWN; only an independently established read
            # exception is represented, without authorizing frozen write actions.
            value['facts']['merchantStatus'] = 'OFFLINE'
            value['reasonCodes'] = ['SIGNING_UNKNOWN', 'MERCHANT_OFFLINE']
            value['allowedActions'] = ['merchant.order.read']
            value['nextSteps'] = [{'type': 'VIEW_EXISTING_ORDERS'}]
        return value

    def test_admission_allowed_requires_complete_active_facts_for_owner_and_staff(self):
        for membership in ('OWNER', 'STAFF'):
            value = self.admission_case(membership, 'ALLOWED')
            with self.subTest(membership=membership, branch='ALLOWED'):
                self.valid('AuthMerchantAdmission', value)
                self.invalid('AuthMerchantAdmission', {**value, 'reasonCodes': ['SIGNING_UNKNOWN']})
            invalid_facts = {
                'application': [None, {'status': 'DRAFT'}, {'status': 'REVIEWING'}, {'status': 'REJECTED'}],
                'signing': [{'status': state} for state in ('NOT_SIGNED', 'SIGNING', 'FAILED', 'UNKNOWN')],
                'storeStatus': ['FROZEN', 'OFFLINE'],
                'merchantStatus': ['APPLYING', 'FROZEN', 'OFFLINE', 'CANCELED'],
                'staffEnabled': [False, None] if membership == 'STAFF' else [True, False],
            }
            for field, alternatives in invalid_facts.items():
                for alternative in alternatives:
                    with self.subTest(membership=membership, field=field, fact=alternative):
                        invalid = copy.deepcopy(value)
                        invalid['facts'][field] = alternative
                        self.invalid('AuthMerchantAdmission', invalid)

    def test_admission_limited_needs_reason_actions_and_enabled_staff(self):
        for membership in ('OWNER', 'STAFF'):
            value = self.admission_case(membership, 'LIMITED')
            with self.subTest(membership=membership, branch='LIMITED'):
                self.valid('AuthMerchantAdmission', value)
                self.assertEqual(value['facts']['signing']['status'], 'UNKNOWN')
                self.invalid('AuthMerchantAdmission', {**value, 'reasonCodes': []})
                self.invalid('AuthMerchantAdmission', {**value, 'allowedActions': []})
                for field in ('reasonCodes', 'allowedActions'):
                    self.invalid('AuthMerchantAdmission', {k: v for k, v in value.items() if k != field})
                if membership == 'STAFF':
                    for disabled in (False, None):
                        invalid = copy.deepcopy(value)
                        invalid['facts']['staffEnabled'] = disabled
                        self.invalid('AuthMerchantAdmission', invalid)

    def test_admission_denied_needs_reason_and_cannot_advertise_allowed_actions(self):
        for membership in ('OWNER', 'STAFF'):
            value = self.admission_case(membership, 'DENIED')
            with self.subTest(membership=membership, branch='DENIED'):
                self.valid('AuthMerchantAdmission', value)
                self.invalid('AuthMerchantAdmission', {**value, 'reasonCodes': []})
                self.invalid('AuthMerchantAdmission', {**value, 'allowedActions': ['merchant.order.read']})
                self.invalid('AuthMerchantAdmission', {k: v for k, v in value.items() if k != 'reasonCodes'})
                if membership == 'STAFF':
                    disabled = copy.deepcopy(value)
                    disabled['facts']['staffEnabled'] = False
                    disabled['reasonCodes'] = ['STAFF_DISABLED']
                    self.valid('AuthMerchantAdmission', disabled)

    def test_error_envelope_rejects_any_old_sensitive_data(self):
        error = self.example(9)
        self.valid('AuthErrorEnvelope', error)
        for code in ('COMMON_UNAUTHORIZED', 'COMMON_FORBIDDEN', 'COMMON_DEPENDENCY_UNAVAILABLE'):
            self.valid('AuthErrorEnvelope', {**error, 'code': code})
        for data in ({}, {'accessToken': 'EXAMPLE'}, [], ''):
            self.invalid('AuthErrorEnvelope', {**error, 'data': data})
        self.invalid('AuthErrorEnvelope', {**error, 'code': 'SUCCESS'})

    def test_every_auth_error_response_binds_the_strict_error_schema(self):
        error = self.example(9)
        for name, (method, path) in AUTH_OPERATIONS.items():
            operation = self.spec['paths'][path][method]
            self.assertNotIn('202', operation['responses'], 'No generic asynchronous success was approved')
            statuses = {status for status in operation['responses'] if status.startswith(('4', '5'))}
            self.assertTrue({'400', '401', '403', '409', '503'} <= statuses, name)
            for status in statuses:
                with self.subTest(operation=name, status=status):
                    response = dereference(self.spec, operation['responses'][status])
                    schema = response['content']['application/json']['schema']
                    self.assertEqual(schema, {'$ref': '#/components/schemas/AuthErrorEnvelope'})
                    validate_instance(self.spec, schema, error)
                    with self.assertRaises(InstanceMismatch):
                        validate_instance(self.spec, schema, {**error, 'data': {'accessToken': 'EXAMPLE'}})

    def test_page_limits_and_current_session_do_not_allow_client_auth_facts(self):
        page = {'items': [], 'page': 1, 'pageSize': 20, 'total': 0}
        self.valid('AuthMembershipPage', page)
        for changes in ({'page': 0}, {'pageSize': 101}, {'total': -1}, {'total': 9007199254740992}):
            self.invalid('AuthMembershipPage', {**page, **changes})
        session = self.example(6, True)
        self.valid('AuthWebCurrentSession', session)
        self.invalid('AuthWebCurrentSession', {**session, 'mfaVerified': True})
        self.invalid('AuthWebCurrentSession', {**session, 'refreshToken': 'EXAMPLE'})

    def test_auth_timestamp_and_uuid_request_ids_have_different_shapes(self):
        self.valid('AuthTimestamp', '2026-09-14T15:00:00.000+08:00')
        for value in ('2026-09-14T15:00:00+08:00', '2026-09-14T15:00:00.000', '2026-02-30T15:00:00.000Z'):
            self.invalid('AuthTimestamp', value)
        sms = self.example(10, True)
        self.valid('AuthSmsIntentStatus', sms)
        self.invalid('AuthSmsIntentStatus', {**sms, 'requestId': '9007199254740993'})
        self.valid('PublicId', '9007199254740993')
        self.invalid('PublicId', 9007199254740993)


if __name__ == '__main__':
    unittest.main()
