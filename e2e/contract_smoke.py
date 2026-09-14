"""Offline document smoke only: no public DTO generation or live API claims."""
import json
import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / 'docs/04-api/11-OpenAPI-Core-v0.4.yaml'

# S1's existing business surface remains an independently protected subset.
# Adding AUTH operations must not turn these assertions into a total-count check.
LEGACY_OPERATIONS = {
    'createOrder': ('post', '/c/orders'),
    'listMyOrders': ('get', '/c/orders'),
    'getMyOrder': ('get', '/c/orders/{orderId}'),
    'createOrderPayment': ('post', '/c/orders/{orderId}/payments'),
    'rescheduleOrder': ('post', '/c/orders/{orderId}/reschedule'),
    'applyRefund': ('post', '/c/orders/{orderId}/refund-applications'),
    'createAftersale': ('post', '/c/orders/{orderId}/aftersales'),
    'getReviewEligibility': ('get', '/c/orders/{orderId}/review-eligibility'),
    'createReview': ('post', '/c/orders/{orderId}/reviews'),
    'merchantConfirmOrder': ('post', '/merchant/orders/{orderId}/confirm'),
    'merchantRejectOrder': ('post', '/merchant/orders/{orderId}/reject'),
    'merchantApproveRefund': ('post', '/merchant/refund-applications/{applicationId}/approve'),
    'merchantRejectRefund': ('post', '/merchant/refund-applications/{applicationId}/reject'),
    'verifyPlatformOrder': ('post', '/merchant/orders/{orderId}/verification'),
    'decideAftersale': ('post', '/admin/aftersales/{afterSaleId}/decision'),
    'abnormalCloseOrder': ('post', '/admin/orders/{orderId}/abnormal-close'),
}
LEGACY_CREATES = {'createOrder', 'applyRefund', 'createAftersale', 'createReview'}
LEGACY_CREATE_SCHEMAS = {
    'createOrder': 'CreateOrderResponseEnvelope',
    'applyRefund': 'RefundApplicationResponseEnvelope',
}
AUTH_OPERATIONS = {
    'cAuthCreateAttempt': ('post', '/c/auth/attempts'),
    'cAuthWechatLogin': ('post', '/c/auth/wechat-login'),
    'cAuthSendSms': ('post', '/c/auth/sms-codes'),
    'cAuthSmsLogin': ('post', '/c/auth/sms-login'),
    'cAuthPasswordLogin': ('post', '/c/auth/password-login'),
    'cAccountResetPassword': ('post', '/c/account/password/reset'),
    'cAccountBindPhone': ('post', '/c/account/phone-binding'),
    'cAuthGetSession': ('get', '/c/auth/session'),
    'cAuthRefresh': ('post', '/c/auth/refresh'),
    'cAuthLogout': ('post', '/c/auth/logout'),
    'cAuthGetAttemptResult': ('get', '/c/auth/attempts/{attemptId}/result'),
    'cAuthGetSmsIntent': ('get', '/c/auth/attempts/{attemptId}/sms-intents/{requestId}'),
    'adminAuthCreateAttempt': ('post', '/admin/auth/attempts'),
    'adminAuthLogin': ('post', '/admin/auth/login'),
    'adminAuthGetRequirements': ('get', '/admin/auth/attempts/{attemptId}/requirements'),
    'adminAuthCreateCaptcha': ('post', '/admin/auth/captcha/challenges'),
    'adminAuthVerifyCaptcha': ('post', '/admin/auth/captcha/verify'),
    'adminAuthGetSession': ('get', '/admin/auth/session'),
    'adminAuthGetAttemptResult': ('get', '/admin/auth/attempts/{attemptId}/result'),
    'adminAuthLogout': ('post', '/admin/auth/logout'),
    'adminAuthActivity': ('post', '/admin/auth/activity'),
    'cAuthListMerchantMemberships': ('get', '/c/auth/merchant-memberships'),
    'merchantAuthCheckAdmission': ('get', '/merchant/auth/admission'),
    'adminAuthGetPermissions': ('get', '/admin/auth/permissions'),
    'adminListPermissionActions': ('get', '/admin/permission-actions'),
    'adminListOperatorAccounts': ('get', '/admin/operator-accounts'),
    'adminCreateOperatorAccount': ('post', '/admin/operator-accounts'),
    'adminUpdateOperatorAccount': ('put', '/admin/operator-accounts/{operatorId}'),
    'adminSetOperatorAuthorization': ('put', '/admin/operator-accounts/{operatorId}/authorization'),
    'adminDisableOperatorAccount': ('post', '/admin/operator-accounts/{operatorId}/disable'),
    'adminEnableOperatorAccount': ('post', '/admin/operator-accounts/{operatorId}/enable'),
    'adminResetOperatorPassword': ('post', '/admin/operator-accounts/{operatorId}/password-reset'),
    'adminListRoles': ('get', '/admin/roles'),
    'adminConfigureRole': ('put', '/admin/roles/{roleId}'),
    'adminDisableRole': ('post', '/admin/roles/{roleId}/disable'),
    'adminEnableRole': ('post', '/admin/roles/{roleId}/enable'),
}
ANONYMOUS_ATTEMPTS = {'cAuthCreateAttempt', 'adminAuthCreateAttempt'}
MINI_ATTEMPT_OPERATIONS = {
    'cAuthWechatLogin', 'cAuthSendSms', 'cAuthSmsLogin', 'cAuthPasswordLogin',
    'cAccountResetPassword', 'cAuthGetAttemptResult', 'cAuthGetSmsIntent',
}
WEB_ATTEMPT_OPERATIONS = {
    'adminAuthLogin', 'adminAuthGetRequirements', 'adminAuthCreateCaptcha',
    'adminAuthVerifyCaptcha', 'adminAuthGetAttemptResult',
}


def check_auth_security(spec, operation, parameters):
    """Check exact OAS OR/AND structure; body refresh is an explicit exception."""
    name = operation['operationId']
    assert operation.get('x-contract-status') == 'SYNC_CANDIDATE_NOT_IMPLEMENTED', f'AUTH implementation status changed: {name}'
    if name in ANONYMOUS_ATTEMPTS:
        expected = []
        assert operation.get('x-auth-mode') == 'anonymous-bootstrap', f'Missing bootstrap annotation: {name}'
        assert operation.get('x-secret-replay') == 'NEVER_BY_REQUEST_ID_ALONE', f'Bootstrap cannot replay secrets: {name}'
        assert '201' in operation['responses'] and '200' not in operation['responses'], f'Bootstrap must not promise public success replay: {name}'
    elif name == 'cAuthRefresh':
        expected = []
        assert operation.get('x-auth-mode') == 'refresh-body', 'Refresh must be credentialed body mode'
        assert operation.get('x-auth-credential') == 'body.refreshToken', 'Refresh credential location changed'
        body = dereference(spec, operation.get('requestBody', {}))
        assert body.get('required') is True, 'Refresh body must be required'
        assert body.get('content', {}).get('application/json', {}).get('schema') == {
            '$ref': '#/components/schemas/AuthRefreshRequest'}, 'Refresh body schema changed'
        schema = dereference(spec, spec['components']['schemas']['AuthRefreshRequest'])
        assert schema.get('type') == 'object' and schema.get('additionalProperties') is False
        assert schema.get('required') == ['refreshToken'], 'Refresh credential must be required'
        token = dereference(spec, schema['properties']['refreshToken'])
        assert token.get('type') == 'string' and token.get('minLength', 0) > 0 and not token.get('nullable'), 'Refresh credential cannot be empty or nullable'
    elif name in MINI_ATTEMPT_OPERATIONS:
        expected = [{'authAttempt': []}]
    elif name in WEB_ATTEMPT_OPERATIONS:
        expected = [{'authAttempt': [], 'adminBinding': []}]
    elif name == 'cAccountBindPhone':
        expected = [{'bearerAuth': []}, {'authAttempt': []}]
        assert operation.get('x-auth-mode') == 'phone-binding-alternatives', 'Phone binding mode changed'
    else:
        expected = [{'bearerAuth': []}]
    # Every AUTH operation declares its own security, including both empty exceptions.
    assert operation.get('security') == expected, f'AUTH security mismatch: {name}'
    if name in WEB_ATTEMPT_OPERATIONS | {'adminAuthCreateAttempt'}:
        resolved = [dereference(spec, p) for p in parameters]
        assert any(p.get('name') == 'Origin' and p.get('in') == 'header'
                   and p.get('required') is True for p in resolved), f'Missing required Origin: {name}'
    if name == 'adminCreateOperatorAccount':
        responses = operation['responses']
        assert {'200', '201'} <= responses.keys(), 'Operator account create must keep business replay'
        assert dereference(spec, responses['201'])['content'] == dereference(spec, responses['200'])['content']
        assert 'x-secret-replay' not in operation, 'Account creation is not anonymous secret bootstrap'


def local_reference(spec, ref):
    assert isinstance(ref, str) and ref.startswith('#/'), f'Unreviewed external reference: {ref}'
    target = spec
    for part in ref[2:].split('/'):
        part = part.replace('~1', '/').replace('~0', '~')
        target = target[int(part)] if isinstance(target, list) else target[part]
    assert isinstance(target, dict), f'Reference is not an object: {ref}'
    return target


def dereference(spec, value, active=()):
    """OpenAPI 3.0 Reference Objects do not merge sibling schema keywords."""
    assert isinstance(value, dict), 'Expected schema/reference object'
    if '$ref' not in value:
        return value
    ref = value['$ref']
    assert set(value) == {'$ref'}, f'Ignored $ref siblings: {ref}'
    assert ref not in active, f'Cyclic reference: {ref}'
    return dereference(spec, local_reference(spec, ref), (*active, ref))


def string_schema(spec, schema, name, allow_nullable=True):
    """Check the supported string intersection, not general OpenAPI validation."""
    parts = []

    def collect(value):
        value = dereference(spec, value)
        assert not any(key in value for key in ('oneOf', 'anyOf', 'not')), f'Unsupported string composition: {name}'
        parts.append(value)
        if 'allOf' in value:
            branches = value['allOf']
            assert isinstance(branches, list) and branches, f'Invalid allOf: {name}'
            for branch in branches:
                collect(branch)

    collect(schema)
    typed = [part for part in parts if 'type' in part]
    assert typed and all(part['type'] == 'string' for part in typed), f'Non-string ID/amount/header: {name}'
    for part in parts:
        if 'nullable' in part:
            assert type(part['nullable']) is bool, f'Invalid nullable: {name}'
        if part.get('nullable'):
            # In 3.0 nullable only applies when type is explicitly in this object.
            # allOf cannot make a non-nullable referenced string accept null.
            assert allow_nullable and part.get('type') == 'string', f'Invalid nullable: {name}'
            assert all(member.get('nullable', False) for member in typed), f'Conflicting nullable allOf: {name}'


def check(spec):
    assert spec['openapi'] == '3.0.3', 'Unexpected OpenAPI dialect'
    assert spec['paths'], 'No operations'
    refs = []
    def walk(value, active=(), count_refs=True):
        if isinstance(value, dict):
            if '$ref' in value:
                ref = value['$ref']
                assert set(value) == {'$ref'}, f'Ignored $ref siblings: {ref}'
                assert ref not in active, f'Cyclic reference: {ref}'
                target = local_reference(spec, ref)
                if count_refs:
                    refs.append(ref)
                walk(target, (*active, ref), count_refs=False)
                return
            for child in value.values():
                walk(child, active, count_refs)
        elif isinstance(value, list):
            for child in value:
                walk(child, active, count_refs)
    walk(spec)
    schemas = spec['components']['schemas']
    string_schema(spec, schemas['DecimalAmount'], 'DecimalAmount', allow_nullable=False)
    request = dereference(spec, spec['components']['parameters']['RequestId'])
    assert request['name'] == 'X-Request-Id' and request['in'] == 'header' and request['required']
    string_schema(spec, request['schema'], 'X-Request-Id', allow_nullable=False)
    operations = set()
    writes = 0
    legacy_seen = set()
    legacy_writes = 0
    legacy_creates = set()
    for path, item in spec['paths'].items():
        for method, operation in item.items():
            if method not in {'get', 'post', 'put', 'patch', 'delete', 'head', 'options'}:
                continue
            operation_id = operation['operationId']
            assert operation_id not in operations, f'Duplicate operationId {operation_id}'
            operations.add(operation_id)
            assert operation['responses'], f'Missing responses: {operation_id}'
            parameters = operation.get('parameters', []) + item.get('parameters', [])
            if operation_id in AUTH_OPERATIONS:
                assert (method, path) == AUTH_OPERATIONS[operation_id], f'AUTH operation moved: {operation_id}'
                check_auth_security(spec, operation, parameters)
            else:
                assert operation.get('security', spec.get('security')), f'Missing security: {operation_id}'
            if operation_id in LEGACY_OPERATIONS:
                legacy_seen.add(operation_id)
                assert (method, path) == LEGACY_OPERATIONS[operation_id], f'Legacy operation moved: {operation_id}'
                assert operation.get('security', spec.get('security')) == [{'bearerAuth': []}], f'Legacy security changed: {operation_id}'
                if method != 'get':
                    legacy_writes += 1
                    responses = operation['responses']
                    assert {'200', '401', '403', '409', '503'} <= responses.keys(), f'Legacy replay responses missing: {operation_id}'
                    assert '202' not in responses, f'Legacy success changed to acceptance: {operation_id}'
                    if '201' in responses:
                        legacy_creates.add(operation_id)
                        created = dereference(spec, responses['201']).get('content')
                        replayed = dereference(spec, responses['200']).get('content')
                        assert created == replayed, f'Legacy create replay schema changed: {operation_id}'
                        if operation_id in LEGACY_CREATE_SCHEMAS:
                            expected_ref = '#/components/schemas/' + LEGACY_CREATE_SCHEMAS[operation_id]
                            assert created == {'application/json': {'schema': {'$ref': expected_ref}}}, f'Legacy create schema missing: {operation_id}'
            if method in {'post', 'put', 'patch', 'delete'}:
                assert {'$ref': '#/components/parameters/RequestId'} in parameters, f'Missing request ID: {operation_id}'
                writes += 1
            for name in re.findall(r'\{([^}]+)\}', path):
                resolved = [dereference(spec, p) for p in parameters]
                assert any(p['name'] == name and p['in'] == 'path' and p.get('required') for p in resolved), f'Missing path parameter: {path}'
                if name.endswith(('Id', 'No')):
                    for parameter in resolved:
                        if parameter['name'] == name and parameter['in'] == 'path':
                            string_schema(spec, parameter['schema'], name, allow_nullable=False)
    assert legacy_seen == LEGACY_OPERATIONS.keys(), f'Legacy operations missing: {LEGACY_OPERATIONS.keys() - legacy_seen}'
    assert legacy_writes == 13, 'Legacy write surface changed'
    assert legacy_creates == LEGACY_CREATES, 'Legacy create surface changed'
    assert operations == LEGACY_OPERATIONS.keys() | AUTH_OPERATIONS.keys(), 'Unexpected or missing reviewed operations'
    schemes = spec['components']['securitySchemes']
    assert schemes['bearerAuth']['type'] == 'http' and schemes['bearerAuth']['scheme'] == 'bearer'
    for scheme, location, name in [('authAttempt', 'header', 'X-Auth-Attempt'),
                                   ('adminBinding', 'cookie', '__Host-pet-admin-attempt')]:
        assert all(schemes[scheme].get(k) == v for k, v in
                   [('type', 'apiKey'), ('in', location), ('name', name)]), f'AUTH security scheme changed: {scheme}'
    ids = 0

    def check_properties(value):
        nonlocal ids
        if isinstance(value, dict):
            for name, prop in value.get('properties', {}).items():
                if name.endswith(('Id', 'No')):
                    string_schema(spec, prop, name)
                    ids += 1
                elif name.endswith(('Ids', 'Nos')):
                    array = dereference(spec, prop)
                    assert array.get('type') == 'array', f'Non-array IDs: {name}'
                    string_schema(spec, array.get('items'), name, allow_nullable=False)
                    ids += 1
            for child in value.values():
                check_properties(child)
        elif isinstance(value, list):
            for child in value:
                check_properties(child)

    # Visit declarations once, including inline/nested schemas and allOf branches.
    check_properties(spec)
    return {'operations': len(operations), 'writesWithRequestId': writes,
            'legacyOperations': len(legacy_seen), 'legacyWrites': legacy_writes,
            'legacyCreates': len(legacy_creates), 'authOperations': len(operations & AUTH_OPERATIONS.keys()),
            'resolvedRefs': len(refs), 'stringIdProperties': ids}


if __name__ == '__main__':
    result = check(yaml.safe_load(SPEC.read_text(encoding='utf-8')))
    print(json.dumps({'status': 'PASS_OFFLINE_DOCUMENT_SMOKE', **result}, indent=2))
    print('NOT_EXECUTED: live HTTP, DTO serialization, semantic breaking-change approval, business E2E.')
    print('BLOCKED real session/RBAC integration: existing CCR-ACR-001 and CCR-PERM-001. No SDK generated.')
