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
    'getReviewEligibility': ('get', '/c/orders/{orderId}/review-eligibility'),
    'createReview': ('post', '/c/orders/{orderId}/reviews'),
    'merchantConfirmOrder': ('post', '/merchant/orders/{orderId}/confirm'),
    'merchantRejectOrder': ('post', '/merchant/orders/{orderId}/reject'),
    'merchantApproveRefund': ('post', '/merchant/refund-applications/{applicationId}/approve'),
    'merchantRejectRefund': ('post', '/merchant/refund-applications/{applicationId}/reject'),
    'verifyPlatformOrder': ('post', '/merchant/orders/{orderId}/verification'),
    'abnormalCloseOrder': ('post', '/admin/orders/{orderId}/abnormal-close'),
}
LEGACY_CREATES = {'createOrder', 'applyRefund', 'createReview'}
LEGACY_CREATE_SCHEMAS = {
    'createOrder': 'CreateOrderResponseEnvelope',
    'applyRefund': 'RefundApplicationResponseEnvelope',
}
# Contract51 promotes two unimplemented AFS draft operations into a separately pinned family.
AFTERSALE_OPERATIONS = {
    'getAftersaleOptions': ('get', '/c/aftersale-options'),
    'createAftersale': ('post', '/c/orders/{orderId}/aftersales'),
    'getAftersaleEligibility': ('get', '/c/orders/{orderId}/aftersale-eligibility'),
    'cListAftersales': ('get', '/c/aftersales'),
    'merchantListAftersales': ('get', '/merchant/aftersales'),
    'adminListAftersales': ('get', '/admin/aftersales'),
    'cGetAftersale': ('get', '/c/aftersales/{afterSaleId}'),
    'merchantGetAftersale': ('get', '/merchant/aftersales/{afterSaleId}'),
    'adminGetAftersale': ('get', '/admin/aftersales/{afterSaleId}'),
    'cAddAftersaleEvidence': ('post', '/c/aftersales/{afterSaleId}/evidence'),
    'merchantAddAftersaleEvidence': ('post', '/merchant/aftersales/{afterSaleId}/evidence'),
    'withdrawAftersale': ('post', '/c/aftersales/{afterSaleId}/withdraw'),
    'submitAftersaleOpinion': ('post', '/merchant/aftersales/{afterSaleId}/opinion'),
    'acceptAftersale': ('post', '/admin/aftersales/{afterSaleId}/accept'),
    'requestAftersaleSupplement': ('post', '/admin/aftersales/{afterSaleId}/supplement-requests'),
    'closeDuplicateAftersale': ('post', '/admin/aftersales/{afterSaleId}/close-duplicate'),
    'decideAftersale': ('post', '/admin/aftersales/{afterSaleId}/decisions'),
    'uploadAftersaleEvidenceAsset': ('post', '/c/aftersale-evidence-assets'),
}
for _audience in ('c', 'merchant', 'admin'):
    AFTERSALE_OPERATIONS[_audience + 'IssueAftersaleEvidenceGrant'] = (
        'post', '/' + _audience + '/aftersales/{afterSaleId}/evidence-batches/{batchId}/assets/{assetId}/read-grants')
    AFTERSALE_OPERATIONS[_audience + 'ConsumeAftersaleEvidenceGrant'] = (
        'get', '/' + _audience + '/aftersale-evidence-read-grants/{token}')

AFTERSALE_REQUEST_SHAPES = {
    'AfterSaleCreateRequest': ({'typeCode', 'demandCode', 'description', 'evidenceAssetIds'}, {'requestedAmount', 'newProblemStatement'}),
    'AfterSaleEvidenceRequest': ({'expectedVersion', 'evidenceAssetIds'}, {'supplementRequestId', 'text'}),
    'AfterSaleOpinionRequest': ({'expectedVersion', 'opinionCode', 'explanation', 'evidenceAssetIds'}, {'supplementRequestId'}),
    'AfterSaleWithdrawRequest': ({'expectedVersion'}, set()),
    'AfterSaleAcceptRequest': ({'expectedVersion'}, {'newProblemAssessment', 'expectedFinalSetVersion'}),
    'AfterSaleSupplementRequest': ({'expectedVersion', 'targetParty', 'reason', 'deadline'}, set()),
    'AfterSaleCloseDuplicateRequest': ({'expectedVersion', 'priorFinalCaseId', 'reason'}, set()),
    'AfterSaleDecisionRequest': ({'expectedVersion', 'decisionType', 'reason'}, {'refundAmount'}),
    'AfterSaleAssetGrantRequest': ({'reason'}, set()),
}

CREDENTIAL_OPERATIONS = {
    'getOrderVerificationCredential': ('get', '/c/orders/{orderId}/verification-code'),
    'issueOrderVerificationCredential': ('post', '/c/orders/{orderId}/verification-code'),
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
MERCHANT_OPERATIONS = {
    'merchantListStaff': ('get', '/merchant/staff'),
    'merchantCreateStaff': ('post', '/merchant/staff'),
    'merchantGetStaff': ('get', '/merchant/staff/{staffId}'),
    'merchantUpdateStaff': ('put', '/merchant/staff/{staffId}'),
    'merchantEnableStaff': ('post', '/merchant/staff/{staffId}/enable'),
    'merchantDisableStaff': ('post', '/merchant/staff/{staffId}/disable'),
    'merchantGetAgreement': ('get', '/merchant/agreement'),
    'merchantConsentAgreement': ('post', '/merchant/agreement/consent'),
}
APPLICATION_OPERATIONS = {
    'cListMerchantApplicationCities': ('get', '/c/merchant-application-cities'),
    'cGetCurrentMerchantApplication': ('get', '/c/merchant-applications/current'),
    'cCreateMerchantApplication': ('post', '/c/merchant-applications'),
    'cSaveMerchantApplicationDraft': ('put', '/c/merchant-applications/{applicationId}/draft'),
    'cSubmitMerchantApplication': ('post', '/c/merchant-applications/{applicationId}/submit'),
    'adminListMerchantApplications': ('get', '/admin/merchant-applications'),
    'adminGetMerchantApplication': ('get', '/admin/merchant-applications/{applicationId}'),
    'adminClaimMerchantApplication': ('post', '/admin/merchant-applications/{applicationId}/claim'),
    'adminReleaseMerchantApplication': ('post', '/admin/merchant-applications/{applicationId}/release'),
    'adminVerifyMerchantApplication': ('post', '/admin/merchant-applications/{applicationId}/manual-verification'),
    'adminDecideMerchantApplication': ('post', '/admin/merchant-applications/{applicationId}/decision'),
}
MINI_ATTEMPT_OPERATIONS = {
    'cAuthWechatLogin', 'cAuthSendSms', 'cAuthSmsLogin', 'cAuthPasswordLogin',
    'cAccountResetPassword', 'cAuthGetAttemptResult', 'cAuthGetSmsIntent',
}
PRIVATE_ASSET_OPERATIONS = {
    'uploadPrivateAsset': ('post', '/c/private-assets'),
    'issuePrivateAssetReadGrant': ('post', '/admin/merchant-applications/{applicationId}/private-assets/{assetId}/read-grants'),
    'consumePrivateAssetReadGrant': ('get', '/admin/private-asset-read-grants/{token}'),
}
SERVICE_CATALOG_OPERATIONS = {
    'cListStoreServices': ('get', '/c/stores/{storeId}/services'),
    'cGetService': ('get', '/c/services/{serviceId}'),
}
# ADM-001 service write slice (CCR-W2-API-001): workbench commands + admin review, implemented
# default-off behind pet.service.command.enabled. The census pins the family exactly like the
# application family; the force-offline action code is the AUTH-lexicon spelling service.force.offline.
SERVICE_WRITE_OPERATIONS = {
    'merchantListServices': ('get', '/merchant/services'),
    'merchantCreateService': ('post', '/merchant/services'),
    'merchantGetService': ('get', '/merchant/services/{serviceId}'),
    'merchantUpdateService': ('put', '/merchant/services/{serviceId}'),
    'merchantSubmitServiceOnline': ('post', '/merchant/services/{serviceId}/online'),
    'merchantTakeServiceOffline': ('post', '/merchant/services/{serviceId}/offline'),
    'merchantListServiceCategories': ('get', '/merchant/service-categories'),
    'adminListServices': ('get', '/admin/services'),
    'adminGetService': ('get', '/admin/services/{serviceId}'),
    'adminDecideServiceReview': ('post', '/admin/services/{serviceId}/decision'),
    'adminForceOfflineService': ('post', '/admin/services/{serviceId}/force-offline'),
}

# STR-D8 (store-read ruling 2026-09-22): the four C-end browse routes allow
# anonymous GET with optional bearer; see docs/04-api/10 §3.3 and CCR-W2-API-001 v0.2.
ANONYMOUS_BROWSE_SECURITY = [{}, {'bearerAuth': []}]
STORE_CATALOG_OPERATIONS = {
    'cListStores': ('get', '/c/stores'),
    'cGetStore': ('get', '/c/stores/{storeId}'),
}

# SCH-001 schedule availability (CCR-W2-API-001, ruling 2026-09-23): implemented default-off
# behind pet.schedule.query.enabled. Mandatory session (SCH-D1, OUTSIDE the STR-D8 anonymous
# browse family). SCH-002 now assembles real staff/capability/availability facts; missing or
# unreadable providers still fail closed. This surface is not booking/hold authority.
SCHEDULE_AVAILABILITY_OPERATIONS = {
    'cGetServiceAvailability': ('get', '/c/services/{serviceId}/availability'),
}

# Contract53 (SCH-004 merchant schedule write side, PRD29/SSOT §29 + SCHW/SCHC rulings):
# owner-gated service-window / staff-availability / capability maintenance plus the merchant
# workbench reads, default off behind pet.schedule.command.*. Response codes are pinned per
# operation and every error reply must use the merchant error envelope; creates answer 201
# first-commit / 200 same-params replay with identical bodies (API23).
SCHEDULE_WRITE_OPERATIONS = {
    'merchantListScheduleWindows': (
        'get', '/merchant/stores/{storeId}/availability-windows',
        ('200', '400', '401', '403', '503')),
    'merchantCreateScheduleWindow': (
        'post', '/merchant/stores/{storeId}/availability-windows',
        ('200', '201', '400', '401', '403', '404', '409', '503')),
    'merchantUpdateScheduleWindow': (
        'put', '/merchant/stores/{storeId}/availability-windows/{windowId}',
        ('200', '400', '401', '403', '404', '409', '503')),
    'merchantCloseScheduleWindow': (
        'post', '/merchant/stores/{storeId}/availability-windows/{windowId}/close',
        ('200', '400', '401', '403', '404', '409', '503')),
    'merchantOpenScheduleWindow': (
        'post', '/merchant/stores/{storeId}/availability-windows/{windowId}/open',
        ('200', '400', '401', '403', '404', '409', '503')),
    'merchantBatchCloseScheduleWindows': (
        'post', '/merchant/stores/{storeId}/availability-windows/batch-close',
        ('200', '400', '401', '403', '409', '503')),
    'merchantListStaffAvailabilityWindows': (
        'get', '/merchant/staff/{staffId}/availability-windows',
        ('200', '400', '401', '403', '503')),
    'merchantCreateStaffAvailabilityWindow': (
        'post', '/merchant/staff/{staffId}/availability-windows',
        ('200', '201', '400', '401', '403', '404', '409', '503')),
    'merchantUpdateStaffAvailabilityWindow': (
        'put', '/merchant/staff/{staffId}/availability-windows/{windowId}',
        ('200', '400', '401', '403', '404', '409', '503')),
    'merchantCloseStaffAvailabilityWindow': (
        'post', '/merchant/staff/{staffId}/availability-windows/{windowId}/close',
        ('200', '400', '401', '403', '404', '409', '503')),
    'merchantOpenStaffAvailabilityWindow': (
        'post', '/merchant/staff/{staffId}/availability-windows/{windowId}/open',
        ('200', '400', '401', '403', '404', '409', '503')),
    'merchantGetStaffServiceCapabilities': (
        'get', '/merchant/staff/{staffId}/service-capabilities',
        ('200', '400', '401', '403', '404', '503')),
    'merchantReplaceStaffServiceCapabilities': (
        'put', '/merchant/staff/{staffId}/service-capabilities',
        ('200', '400', '401', '403', '404', '409', '503')),
}


def check_private_assets(spec, operation):
    name = operation['operationId']
    assert operation.get('security') == [{'bearerAuth': []}], f'Private asset security changed: {name}'
    assert operation.get('x-default-enabled') is False, 'Private asset must remain default off'
    assert operation.get('x-contract-status') == 'APPROVED_IMPLEMENTATION_CANDIDATE'
    assert operation.get('x-audience') == ('MINIAPP' if name == 'uploadPrivateAsset' else 'ADMIN_WEB')
    if name != 'uploadPrivateAsset':
        assert operation.get('x-single-use') is True, 'Private grant must be single use'
        assert operation.get('x-grant-ttl-seconds') == 300, 'Private grant TTL changed'
        assert operation.get('x-recheck-current-authorization') is True, 'Private grant must recheck current authorization'
    schemas = spec['components']['schemas']

    def strict(schema, fields):
        value = dereference(spec, schema)
        assert value.get('type') == 'object' and value.get('additionalProperties') is False, 'Private asset object must be closed'
        assert set(value.get('required', [])) == set(fields) and set(value.get('properties', {})) == set(fields), 'Private asset fields changed'
        assert not any(key in value for key in ('allOf', 'oneOf', 'anyOf', 'nullable')), 'Private asset schema composition changed'
        return value['properties']

    result = strict(schemas['PrivateAssetUploadResult'], ['assetId', 'status', 'objectSha256', 'mediaType', 'bytes'])
    assert result['assetId'] == {'$ref': '#/components/schemas/PublicId'}
    assert result['status'] == {'type': 'string', 'enum': ['READY']}
    assert result['objectSha256'] == {'type': 'string', 'pattern': '^[0-9a-f]{64}$'}
    assert result['mediaType'] in ({'type': 'string', 'enum': ['image/png', 'image/jpeg']}, {'type': 'string', 'enum': ['image/jpeg', 'image/png']})
    assert result['bytes'] == {'type': 'integer', 'format': 'int64', 'minimum': 1, 'maximum': 10485760}
    grant = strict(schemas['PrivateAssetGrantRequest'], ['submissionRevisionId', 'purposeCode', 'reason', 'confirmed'])
    assert grant['submissionRevisionId'] == {'$ref': '#/components/schemas/PublicId'}
    assert grant['purposeCode'] == {'type': 'string', 'pattern': '^[A-Z][A-Z0-9_]{0,63}$'}
    assert grant['reason'] == {'type': 'string', 'minLength': 10, 'maxLength': 500}
    assert grant['confirmed'] == {'type': 'boolean', 'enum': [True]}
    grant_result = strict(schemas['PrivateAssetGrantResult'], ['readUrl', 'expiresAt'])
    assert grant_result['readUrl'] == {'type': 'string', 'pattern': '^/api/v1/admin/private-asset-read-grants/[A-Za-z0-9_-]+$'}
    assert grant_result['expiresAt'] == {'type': 'string', 'format': 'date-time'}
    for envelope, data in [('PrivateAssetUploadEnvelope', 'PrivateAssetUploadResult'), ('PrivateAssetGrantEnvelope', 'PrivateAssetGrantResult'), ('PrivateAssetErrorEnvelope', None)]:
        fields = strict(schemas[envelope], ['success', 'code', 'message', 'data', 'traceId'])
        assert fields['success'] == {'type': 'boolean', 'enum': [data is not None]}
        assert fields['message'] == fields['traceId'] == {'type': 'string', 'minLength': 1}
        if data:
            assert fields['code'] == {'type': 'string', 'enum': ['SUCCESS']}
            assert fields['data'] == {'$ref': '#/components/schemas/' + data}
        else:
            assert fields['data'] == {'type': 'object', 'nullable': True, 'enum': [None]}, 'Private error must not expose data'
            assert set(fields['code']) == {'type', 'enum'} and fields['code']['type'] == 'string'
            assert set(fields['code'].get('enum', [])) == {'COMMON_INVALID_ARGUMENT', 'COMMON_UNAUTHORIZED', 'COMMON_FORBIDDEN', 'COMMON_NOT_FOUND', 'COMMON_CONFLICT', 'IDEMPOTENCY_KEY_CONFLICT', 'COMMON_DEPENDENCY_UNAVAILABLE', 'PRIVATE_ASSET_NOT_READY', 'PRIVATE_ASSET_REJECTED', 'PRIVATE_ASSET_GRANT_GONE'}
    responses = operation['responses']
    successes = {'200', '201'} if name == 'uploadPrivateAsset' else {'200'}
    errors = {'400', '401', '403', '404', '409', '503'} | ({'413', '415', '422'} if name == 'uploadPrivateAsset' else {'410'})
    assert set(responses) == successes | errors, 'Private asset response status changed'
    for status in errors:
        assert dereference(spec, responses[status])['content'] == {'application/json': {'schema': {'$ref': '#/components/schemas/PrivateAssetErrorEnvelope'}}}
    for status in successes:
        response = dereference(spec, responses[status])
        cache = dereference(spec, response['headers']['Cache-Control'])['schema']
        assert cache in ({'type': 'string', 'enum': ['no-store, private']}, {'type': 'string', 'example': 'no-store, private'}), 'Private asset caching changed'
        if name == 'consumePrivateAssetReadGrant':
            assert response['content'] == {
                'image/png': {'schema': {'type': 'string', 'format': 'binary'}},
                'image/jpeg': {'schema': {'type': 'string', 'format': 'binary'}},
            }, 'Private proxy must expose exactly watermarked JPEG and PNG'
            for header, value in [('Pragma', 'no-cache'), ('X-Content-Type-Options', 'nosniff')]:
                assert response['headers'][header]['schema'] == {'type': 'string', 'enum': [value]}
        else:
            expected = 'PrivateAssetUploadEnvelope' if name == 'uploadPrivateAsset' else 'PrivateAssetGrantEnvelope'
            assert response['content'] == {'application/json': {'schema': {'$ref': '#/components/schemas/' + expected}}}
    if name == 'uploadPrivateAsset':
        assert 'PRIVATE_ASSET_REJECTED' in responses['422']['description'] and 'new UUID' in responses['422']['description'], 'Private upload terminal rejection semantics changed'
        body = dereference(spec, operation['requestBody'])
        assert body.get('required') is True and set(body['content']) == {'multipart/form-data'}
        fields = strict(body['content']['multipart/form-data']['schema'], ['purpose', 'file'])
        assert fields['purpose'] == {'type': 'string', 'enum': ['MERCHANT_APPLICATION_MATERIAL']}
        assert fields['file'].get('type') == 'string' and fields['file'].get('format') == 'binary'
        assert set(fields['file']) <= {'type', 'format', 'description'}
    elif name == 'issuePrivateAssetReadGrant':
        assert operation['requestBody'] == {'required': True, 'content': {'application/json': {'schema': {'$ref': '#/components/schemas/PrivateAssetGrantRequest'}}}}
        for boundary in ['Current CLAIMED task claimant', 'current submission revision', 'merchant.identity.reveal + merchant.application.decide', 'scope', 'final same-transaction check']:
            assert boundary in operation.get('x-authorization', ''), 'Private grant authorization changed'
    else:
        for boundary in ['current session generation, actions, scope, claimant and linked material', 'TTL five minutes', 'Consumed grant cannot replay', 'Never log raw token path', 'No OSS URL']:
            assert boundary in operation.get('description', ''), 'Private grant consumption semantics changed'
        token = next(dereference(spec, p) for p in operation['parameters'] if dereference(spec, p).get('name') == 'token')
        assert token == {'name': 'token', 'in': 'path', 'required': True, 'schema': {'type': 'string', 'minLength': 32, 'maxLength': 512, 'pattern': '^[A-Za-z0-9_-]+$'}}
        assert 'requestBody' not in operation
WEB_ATTEMPT_OPERATIONS = {
    'adminAuthLogin', 'adminAuthGetRequirements', 'adminAuthCreateCaptcha',
    'adminAuthVerifyCaptcha', 'adminAuthGetAttemptResult',
}
WEB_IMPLEMENTATION_CANDIDATES = {
    'adminAuthCreateAttempt', 'adminAuthLogin', 'adminAuthGetRequirements',
    'adminAuthCreateCaptcha', 'adminAuthVerifyCaptcha', 'adminAuthGetSession',
    'adminAuthGetAttemptResult', 'adminAuthLogout', 'adminAuthActivity',
    'adminAuthGetPermissions',
}


def check_auth_security(spec, operation, parameters):
    """Check exact OAS OR/AND structure; body refresh is an explicit exception."""
    name = operation['operationId']
    expected_status = ('CONTRACT_SYNCED_IMPLEMENTATION_CANDIDATE' if name in WEB_IMPLEMENTATION_CANDIDATES
                       else 'SYNC_CANDIDATE_NOT_IMPLEMENTED')
    assert operation.get('x-contract-status') == expected_status, f'AUTH implementation status changed: {name}'
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


def check_aftersale_schemas(spec):
    schemas = spec['components']['schemas']
    for name, (required, optional) in AFTERSALE_REQUEST_SHAPES.items():
        schema = schemas[name]
        assert schema.get('type') == 'object' and schema.get('additionalProperties') is False, f'AFS strict DTO changed: {name}'
        assert set(schema.get('required', [])) == required, f'AFS required fields changed: {name}'
        assert set(schema['properties']) == required | optional, f'AFS fields changed: {name}'
        for field in optional:
            assert schema['properties'][field].get('nullable') is True, f'AFS optional nullable changed: {name}.{field}'
        if 'evidenceAssetIds' in required:
            assets = schema['properties']['evidenceAssetIds']
            assert assets.get('type') == 'array' and assets.get('maxItems') == 6 and assets.get('uniqueItems') is True and not assets.get('nullable'), 'AFS evidence bounds changed'
            string_schema(spec, assets['items'], 'evidenceAssetIds', allow_nullable=False)
    for name, field in [('AfterSaleCreateRequest', 'requestedAmount'), ('AfterSaleDecisionRequest', 'refundAmount')]:
        prop = schemas[name]['properties'][field]
        assert prop['type'] == 'string' and prop['pattern'] == r'^(0|[1-9][0-9]{0,15})\.[0-9]{2}(?![\s\S])', 'AFS exact decimal changed'
    assert schemas['AfterSaleSupplementRequest']['properties']['deadline']['pattern'] == r'^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z(?![\s\S])', 'AFS deadline precision changed'
    assert set(schemas['AfterSaleDecisionRequest']['properties']['decisionType']['enum']) == {'REJECT', 'RESERVICE', 'OTHER', 'FULL_REFUND', 'PARTIAL_REFUND'}, 'AFS decision type changed'
    summary = schemas['AfterSaleCaseSummary']
    assert set(summary['properties']) == {'afterSaleId', 'orderId', 'merchantId', 'storeId', 'status', 'version', 'sourceStage', 'typeCode', 'demandCode', 'requestedAmount', 'createdAt', 'deadline'}, 'AFS summary scope changed'
    assert schemas['AfterSaleCasePage']['properties']['items']['maxItems'] == 50, 'AFS page bound changed'
    options = schemas['AfterSaleOptions']
    assert options.get('additionalProperties') is False and set(options.get('required', [])) == {'typeOptions', 'demandOptions'} and set(options['properties']) == {'typeOptions', 'demandOptions'}, 'AFS catalog fields changed'
    for field in ('typeOptions', 'demandOptions'):
        entries = options['properties'][field]
        assert entries.get('type') == 'array' and entries.get('minItems') == 1 and entries.get('maxItems') == 100 and entries.get('items') == {'$ref': '#/components/schemas/AfterSaleOption'}, 'AFS catalog bounds changed'
        assert entries.get('uniqueItems') is True and entries.get('x-unique-by') == 'code' and entries.get('x-sort-order') == 'ASCII_CODE_ASC', 'AFS catalog uniqueness/order changed'
    option = schemas['AfterSaleOption']
    assert option.get('additionalProperties') is False and set(option.get('required', [])) == {'code', 'label'} and set(option['properties']) == {'code', 'label'}, 'AFS option fields changed'
    assert option['properties']['code'].get('type') == 'string' and option['properties']['code'].get('pattern') == r'^[A-Z][A-Z0-9_]{0,63}(?![\s\S])', 'AFS catalog code changed'
    label = option['properties']['label']
    assert label.get('type') == 'string' and label.get('minLength') == 1 and label.get('maxLength') == 64, 'AFS catalog label bounds changed'
    assert label.get('x-length-unit') == 'UNICODE_CODE_POINTS' and label.get('x-surrogate-policy') == 'REJECT_UNPAIRED' and label.get('x-edge-whitespace-policy') == 'ECMASCRIPT_STRING_TRIM', 'AFS catalog Unicode label policy changed'
    for name in ('AfterSaleOptions', 'AfterSaleReceipt', 'AfterSaleEligibility', 'AfterSaleCasePage', 'AfterSaleCaseDetail', 'AfterSaleAssetUpload', 'AfterSaleAssetGrant'):
        payload = schemas[name]
        assert payload.get('additionalProperties') is False and set(payload['required']) == set(payload['properties']), f'AFS response incomplete: {name}'
        envelope = schemas[name + 'Envelope']
        assert envelope.get('additionalProperties') is False and set(envelope['required']) == {'code', 'message', 'data', 'traceId'}, f'AFS envelope changed: {name}'
        assert set(envelope['properties']) == {'code', 'message', 'data', 'traceId'} and envelope['properties']['data'] == {'$ref': '#/components/schemas/' + name}, f'AFS envelope payload changed: {name}'
        assert envelope['properties']['code']['enum'] == ['SUCCESS'] and envelope['properties']['message']['enum'] == ['ok'], 'AFS success code changed'
    error = schemas['AfterSaleErrorEnvelope']
    assert error.get('additionalProperties') is False and set(error['properties']) == {'code', 'message', 'data', 'traceId'} and error['properties']['data']['enum'] == [None], 'AFS failure data unsafe'
    assert {'AFTERSALE_VERSION_CONFLICT', 'AFTERSALE_FINAL_SET_CONFLICT', 'COMMON_CONFLICT', 'IDEMPOTENCY_KEY_CONFLICT'} <= set(error['properties']['code']['enum']), 'AFS definite/busy conflict distinction changed'


def check_aftersale_operation(spec, operation, method, path):
    name = operation['operationId']
    assert (method, path) == AFTERSALE_OPERATIONS[name], f'AFS route changed: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'AFS security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF' and operation.get('x-default-enabled') is False, f'AFS default-off changed: {name}'
    assert operation.get('x-contract') == '51-AfterSale-Http-Contract-v0.1.md', f'AFS authority contract changed: {name}'
    party = 'OPS' if path.startswith('/admin/') else 'MERCHANT' if path.startswith('/merchant/') else 'USER'
    assert operation.get('x-route-party') == party and operation.get('x-audience') == ('ADMIN_WEB' if party == 'OPS' else 'MINIAPP'), f'AFS route identity changed: {name}'
    if party == 'OPS':
        action = 'aftersale.decide' if path.endswith('/decisions') else 'aftersale.handle' if path.endswith(('/accept', '/supplement-requests', '/close-duplicate')) else 'aftersale.read'
        assert operation.get('x-required-actions') == [action], f'AFS action changed: {name}'
    responses = operation['responses']
    expected_responses = {'200', '400', '401', '403', '503'} if name == 'getAftersaleOptions' else {'200', '400', '401', '403', '404', '409', '503'}
    assert expected_responses <= responses.keys() and '202' not in responses, f'AFS responses changed: {name}'
    if name in {'createAftersale', 'uploadAftersaleEvidenceAsset'}:
        assert '201' in responses and responses['201']['content'] == responses['200']['content'], f'AFS create replay changed: {name}'
    else:
        assert '201' not in responses, f'AFS unexpected create status: {name}'
    for status in responses:
        if int(status) >= 400:
            response = dereference(spec, responses[status])
            assert response['content']['application/json']['schema'] == {'$ref': '#/components/schemas/AfterSaleErrorEnvelope'}, f'AFS error envelope changed: {name}'
    if name.endswith('ListAftersales'):
        params = {p['name']: p for p in operation['parameters'] if p.get('in') == 'query'}
        assert set(params) == ({'page', 'pageSize', 'status', 'orderId'} | ({'merchantId', 'storeId'} if party != 'USER' else set())), f'AFS list filters changed: {name}'
        assert params['page']['schema'] == {'type': 'integer', 'minimum': 1, 'maximum': 10000, 'default': 1}, 'AFS page range changed'
        assert params['pageSize']['schema'] == {'type': 'integer', 'minimum': 1, 'maximum': 50, 'default': 20}, 'AFS page size changed'
        if party != 'USER':
            assert params['merchantId'].get('required') is True and params['storeId'].get('required') is True, 'AFS global store scope opened'
    if name == 'getAftersaleOptions':
        assert not operation.get('parameters') and 'requestBody' not in operation, 'AFS catalog query/body opened'
        assert operation.get('x-query-policy') == 'FORBIDDEN_INCLUDING_EMPTY' and operation.get('x-request-body-policy') == 'FORBIDDEN', 'AFS catalog query/body policy changed'
        assert operation.get('x-catalog-source') == 'SAME_REASON_POLICY_AS_CREATE' and operation.get('x-incomplete-catalog-error') == 'COMMON_DEPENDENCY_UNAVAILABLE', 'AFS catalog authority/failclosed changed'
        assert responses['200']['content']['application/json']['schema'] == {'$ref': '#/components/schemas/AfterSaleOptionsEnvelope'}, 'AFS catalog response changed'
    if name == 'decideAftersale':
        assert operation.get('x-public-refund-enabled') is False and operation.get('x-executable-decision-types') == ['REJECT', 'RESERVICE', 'OTHER'], 'AFS public funding opened'
    if 'ConsumeAftersaleEvidenceGrant' in name:
        assert '410' in responses and set(responses['200']['content']) == {'image/jpeg', 'image/png'}, 'AFS private image response changed'
    if method == 'post' and name != 'uploadAftersaleEvidenceAsset':
        body = operation['requestBody']
        assert body.get('required') is True and set(body['content']) == {'application/json'}, f'AFS JSON body changed: {name}'
        request = dereference(spec, body['content']['application/json']['schema'])
        assert request.get('additionalProperties') is False, f'AFS request strictness changed: {name}'


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
    check_aftersale_schemas(spec)
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
            if operation_id in CREDENTIAL_OPERATIONS:
                assert (method, path) == CREDENTIAL_OPERATIONS[operation_id], 'Credential operation moved'
                assert operation.get('x-implementation-status') == 'NOT_IMPLEMENTED', 'Credential route is not delivered'
                assert operation.get('x-default-enabled') is False, 'Credentials must remain default off'
                assert operation.get('security') == [{'bearerAuth': []}], 'Credential current session required'
                issue = spec['components']['schemas']['VerificationCredentialIssue']
                assert issue.get('additionalProperties') is False
                assert set(issue['required']) == {'expectedCredentialVersion', 'refreshKind'}
                assert issue['properties']['expectedCredentialVersion']['type'] == 'string'
                assert issue['properties']['refreshKind']['enum'] == ['INITIAL', 'AUTO', 'MANUAL']
            if operation_id in PRIVATE_ASSET_OPERATIONS:
                assert (method, path) == PRIVATE_ASSET_OPERATIONS[operation_id], f'Private asset operation moved: {operation_id}'
                check_private_assets(spec, operation)
            if operation_id in SERVICE_CATALOG_OPERATIONS:
                assert (method, path) == SERVICE_CATALOG_OPERATIONS[operation_id], f'Service catalog operation moved: {operation_id}'
                assert operation.get('x-contract-status') == 'ACCEPTED_CONTRACT_NOT_IMPLEMENTED', f'Service catalog contract status changed: {operation_id}'
                assert operation.get('security') == ANONYMOUS_BROWSE_SECURITY, f'Service catalog security changed: {operation_id}'
            if operation_id in SERVICE_WRITE_OPERATIONS:
                assert (method, path) == SERVICE_WRITE_OPERATIONS[operation_id], f'Service write operation moved: {operation_id}'
                assert operation.get('security') == [{'bearerAuth': []}], f'Service write security changed: {operation_id}'
                assert operation.get('x-contract-status') == 'IMPLEMENTED_DEFAULT_OFF_REQUIRES_PROVIDERS', f'Service write implementation status changed: {operation_id}'
                assert operation.get('x-default-enabled') is False, f'Service write must remain default off: {operation_id}'
                expected_audience = 'ADMIN_WEB' if operation_id.startswith('admin') else 'MINIAPP'
                assert operation.get('x-audience') == expected_audience, f'Service write audience changed: {operation_id}'
                responses = operation['responses']
                if operation_id.startswith('admin'):
                    expected_actions = {
                        'adminListServices': ['service.review.read'],
                        'adminGetService': ['service.review.read'],
                        'adminDecideServiceReview': ['service.review.decide'],
                        'adminForceOfflineService': ['service.force.offline'],
                    }[operation_id]
                    assert operation.get('x-required-actions') == expected_actions, f'Service write action gate changed: {operation_id}'
                    required = {'200', '400', '401', '403', '409', '503'} if method != 'get' else {'200', '400', '401', '403', '503'}
                    if operation_id == 'adminGetService':
                        required = {'200', '401', '403', '404', '503'}
                elif operation_id == 'merchantListServiceCategories':
                    required = {'200', '400', '401', '503'}
                elif method == 'get':
                    required = {'200', '400', '401', '404', '503'}
                else:
                    required = {'200', '400', '401', '404', '409', '503'}
                    if operation_id == 'merchantCreateService':
                        assert '201' in responses and responses['201'].get('description'), f'Service write create replay changed: {operation_id}'
                assert required <= responses.keys(), f'Service write responses missing: {operation_id} {sorted(required - responses.keys())}'
            if operation_id in STORE_CATALOG_OPERATIONS:
                assert (method, path) == STORE_CATALOG_OPERATIONS[operation_id], f'Store catalog operation moved: {operation_id}'
                assert operation.get('x-contract-status') == 'ACCEPTED_CONTRACT_NOT_IMPLEMENTED', f'Store catalog contract status changed: {operation_id}'
                assert operation.get('security') == ANONYMOUS_BROWSE_SECURITY, f'Store catalog security changed: {operation_id}'
            if operation_id in AUTH_OPERATIONS:
                assert (method, path) == AUTH_OPERATIONS[operation_id], f'AUTH operation moved: {operation_id}'
                check_auth_security(spec, operation, parameters)
            else:
                assert operation.get('security', spec.get('security')), f'Missing security: {operation_id}'
            if operation_id in AFTERSALE_OPERATIONS:
                check_aftersale_operation(spec, operation, method, path)
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
            if operation_id in MERCHANT_OPERATIONS:
                assert (method, path) == MERCHANT_OPERATIONS[operation_id], f'Merchant operation moved: {operation_id}'
                assert operation.get('security') == [{'bearerAuth': []}], f'Merchant security changed: {operation_id}'
                if operation_id in {'merchantGetAgreement', 'merchantConsentAgreement'}:
                    expected_status = 'IMPLEMENTED_DEFAULT_OFF_REQUIRES_PROVIDERS'
                elif operation_id in {'merchantListStaff', 'merchantGetStaff', 'merchantCreateStaff',
                                     'merchantUpdateStaff', 'merchantEnableStaff'}:
                    expected_status = 'IMPLEMENTED_DEFAULT_OFF'
                else:
                    expected_status = 'IMPLEMENTATION_BLOCKED'  # disable still needs ORDER/SCH protection
                assert operation.get('x-contract-status') == expected_status, f'Merchant implementation status changed: {operation_id}'
                responses = operation['responses']
                assert {'200', '400', '401', '403', '404', '409', '503'} <= responses.keys(), f'Merchant responses missing: {operation_id}'
                for code in ('400', '401', '403', '404', '409', '503'):
                    assert dereference(spec, responses[code])['content']['application/json']['schema'] == {'$ref': '#/components/schemas/MerErrorEnvelope'}, f'Merchant error data unsafe: {operation_id}'
                if operation_id in {'merchantCreateStaff', 'merchantConsentAgreement'}:
                    assert '201' in responses, f'Merchant create response missing: {operation_id}'
                    assert responses['201']['content'] == responses['200']['content'], f'Merchant replay changed: {operation_id}'
            if operation_id in APPLICATION_OPERATIONS:
                assert (method, path) == APPLICATION_OPERATIONS[operation_id], f'Application operation moved: {operation_id}'
                assert operation.get('security') == [{'bearerAuth': []}], f'Application security changed: {operation_id}'
                assert operation.get('x-contract-status') == 'IMPLEMENTED_DEFAULT_OFF_REQUIRES_PROVIDERS', f'Application implementation status changed: {operation_id}'
                expected_audience = 'ADMIN_WEB' if operation_id.startswith('admin') else 'MINIAPP'
                assert operation.get('x-audience') == expected_audience, f'Application audience changed: {operation_id}'
                if operation_id.startswith('admin'):
                    expected_actions = ['merchant.application.read'] if method == 'get' else ['merchant.application.decide']
                    if operation_id == 'adminVerifyMerchantApplication':
                        expected_actions.append('merchant.identity.reveal')
                    assert operation.get('x-required-actions') == expected_actions, f'Application action gate changed: {operation_id}'
                if operation_id in {'adminReleaseMerchantApplication', 'adminVerifyMerchantApplication', 'adminDecideMerchantApplication'}:
                    assert operation.get('x-requires-current-claimant') is True, f'Application claimant gate missing: {operation_id}'
                responses = operation['responses']
                assert {'200', '400', '401', '403', '404', '409', '503'} <= responses.keys(), f'Application response missing: {operation_id}'
                if operation_id == 'cCreateMerchantApplication':
                    assert '201' in responses and responses['201']['content'] == responses['200']['content'], 'Application create replay changed'
            if operation_id in SCHEDULE_WRITE_OPERATIONS:
                expected_method, expected_path, required_codes = SCHEDULE_WRITE_OPERATIONS[operation_id]
                assert (method, path) == (expected_method, expected_path), f'Schedule write operation moved: {operation_id}'
                assert operation.get('security') == [{'bearerAuth': []}], f'Schedule write security changed: {operation_id}'
                assert operation.get('x-contract-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Schedule write status changed: {operation_id}'
                assert operation.get('x-audience') == 'MINIAPP', f'Schedule write audience changed: {operation_id}'
                responses = operation['responses']
                assert set(responses) == set(required_codes), f'Schedule write response surface changed: {operation_id}'
                for code in ('400', '401', '403', '404', '409', '503'):
                    if code in required_codes:
                        assert dereference(spec, responses[code])['content']['application/json']['schema'] == {'$ref': '#/components/schemas/MerErrorEnvelope'}, f'Schedule write error data unsafe: {operation_id}'
                if '201' in responses:
                    assert responses['201']['content'] == responses['200']['content'], f'Schedule write replay changed: {operation_id}'
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
    assert legacy_writes == 11, 'Legacy write surface changed'
    assert legacy_creates == LEGACY_CREATES, 'Legacy create surface changed'
    assert operations == LEGACY_OPERATIONS.keys() | AUTH_OPERATIONS.keys() | MERCHANT_OPERATIONS.keys() | APPLICATION_OPERATIONS.keys() | PRIVATE_ASSET_OPERATIONS.keys() | SERVICE_CATALOG_OPERATIONS.keys() | STORE_CATALOG_OPERATIONS.keys() | SERVICE_WRITE_OPERATIONS.keys() | SCHEDULE_AVAILABILITY_OPERATIONS.keys() | SCHEDULE_WRITE_OPERATIONS.keys() | CREDENTIAL_OPERATIONS.keys() | AFTERSALE_OPERATIONS.keys(), 'Unexpected or missing reviewed operations'
    schemes = spec['components']['securitySchemes']
    assert schemes['bearerAuth']['type'] == 'http' and schemes['bearerAuth']['scheme'] == 'bearer'
    for scheme, location, name in [('authAttempt', 'header', 'X-Auth-Attempt'),
                                   ('adminBinding', 'cookie', '__Host-pet-admin-attempt')]:
        assert all(schemes[scheme].get(k) == v for k, v in
                   [('type', 'apiKey'), ('in', location), ('name', name)]), f'AUTH security scheme changed: {scheme}'
    ids = 0

    # Event08 ruling (2026-09-22): submissionNo is a JSON integer inside ServiceReviewedPayload,
    # the only sanctioned non-string *No field in the spec (declared exactly once).
    EVENT_INTEGER_FIELDS = {'submissionNo'}

    def check_properties(value):
        nonlocal ids
        if isinstance(value, dict):
            for name, prop in value.get('properties', {}).items():
                if name.endswith(('Id', 'No')):
                    if name in EVENT_INTEGER_FIELDS:
                        assert prop == {'type': 'integer', 'format': 'int64', 'minimum': 1}, f'Event integer field changed: {name}'
                    else:
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
            'aftersaleOperations': len(operations & AFTERSALE_OPERATIONS.keys()),
            'legacyOperations': len(legacy_seen), 'legacyWrites': legacy_writes,
            'legacyCreates': len(legacy_creates), 'authOperations': len(operations & AUTH_OPERATIONS.keys()),
            'merchantOperations': len(operations & MERCHANT_OPERATIONS.keys()),
            'applicationOperations': len(operations & APPLICATION_OPERATIONS.keys()),
            'privateAssetOperations': len(operations & PRIVATE_ASSET_OPERATIONS.keys()),
            'serviceCatalogOperations': len(operations & SERVICE_CATALOG_OPERATIONS.keys()),
            'scheduleAvailabilityOperations': len(operations & SCHEDULE_AVAILABILITY_OPERATIONS.keys()),
            'scheduleWriteOperations': len(operations & SCHEDULE_WRITE_OPERATIONS.keys()),
            'serviceWriteOperations': len(operations & SERVICE_WRITE_OPERATIONS.keys()),
            'storeCatalogOperations': len(operations & STORE_CATALOG_OPERATIONS.keys()),
            'resolvedRefs': len(refs), 'stringIdProperties': ids}


if __name__ == '__main__':
    result = check(yaml.safe_load(SPEC.read_text(encoding='utf-8')))
    print(json.dumps({'status': 'PASS_OFFLINE_DOCUMENT_SMOKE', **result}, indent=2))
    print('NOT_EXECUTED: live HTTP, DTO serialization, semantic breaking-change approval, business E2E.')
    print('BLOCKED real session/RBAC integration: existing CCR-ACR-001 and CCR-PERM-001. No SDK generated.')
