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
    # abnormalCloseOrder (draft POST /admin/orders/{orderId}/abnormal-close) graduated from this
    # family on 2026-10-15: implemented as adminAbnormalCloseTradeOrder inside the contract-63
    # v0.2 disposal face (ruling doc 33), so it is pinned by ADMIN_TRADE_OPERATIONS now.
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
# Verification HTTP face (47号§4 + 48号K2 slice, 2026-10-06): the two C-side credential routes
# leave NOT_IMPLEMENTED behind, and the contract-10 §4.7 merchant scan completion route gets a
# real receipt surface. Everything stays default off behind pet.verification.credential.http.enabled
# and pet.verification.completion.http.enabled. Failed code checks are committed business results
# returned as 200 receipts (resultCode with null tails), so the 4xx/5xx surface only covers
# transport, authorization and fail-closed state errors; the manual-refresh quota keeps its 429.
VERIFICATION_HTTP_OPERATIONS = {
    'getOrderVerificationCredential': (
        'get', '/c/orders/{orderId}/verification-code',
        ('200', '400', '401', '403', '409', '503')),
    'issueOrderVerificationCredential': (
        'post', '/c/orders/{orderId}/verification-code',
        ('200', '400', '401', '403', '409', '429', '503')),
    'verifyPlatformOrder': (
        'post', '/merchant/orders/{orderId}/verification',
        ('200', '400', '401', '403', '404', '409', '503')),
}
# Merchant manual order actions slice (contract 45 via contract-10 §4.2/§4.3, 2026-10-07): the
# two OWNER decision routes leave the contract-only REQUIRES_PROVIDERS spelling behind and pin
# the assembled default-off surface. Both share pet.order.merchant.http.enabled (the switch
# validation forces the kernel, refund worker and auto-confirm dependency stack with it); the
# production moderation provider and protection key stay explicit enablement prerequisites, so
# default-off remains the shipped state. The routes stay in LEGACY_OPERATIONS as well, exactly
# like verifyPlatformOrder after its promotion.
MERCHANT_ORDER_HTTP_OPERATIONS = {
    'merchantConfirmOrder': (
        'post', '/merchant/orders/{orderId}/confirm',
        ('200', '400', '401', '403', '409', '503')),
    'merchantRejectOrder': (
        'post', '/merchant/orders/{orderId}/reject',
        ('200', '400', '401', '403', '409', '503')),
}
# Merchant order list read side (contract 10 §4.1 supplement, the slice after contract 45's
# manual decisions): the OWNER store list GET joins the same pet.order.merchant.http.enabled
# switch family. It is a new operationId (not in LEGACY_OPERATIONS), stays default off, and
# pins the merchant-coordinate query, the §3.7 displayStatus vocabulary filter, the
# Page/PageSize bounds and the PageResult envelope with the minimal merchant summary.
MERCHANT_ORDER_LIST_OPERATIONS = {
    'merchantListOrders': (
        'get', '/merchant/orders',
        ('200', '400', '401', '403', '503')),
}
# Refund application C face (49号/10号 §3.9 slice, 2026-10-07): applyRefund leaves the draft
# family behind, implemented default-off behind pet.refund.application.http.enabled (plus
# pet.auth.c.enabled). The operation stays in LEGACY_OPERATIONS, so the path, the 201/200
# create-replay schema and the total operation count are unchanged; this family only pins the
# new implementation annotations, the strict request shape and the error surface.
REFUND_HTTP_OPERATIONS = {
    'applyRefund': (
        'post', '/c/orders/{orderId}/refund-applications',
        ('200', '201', '400', '401', '403', '409', '503')),
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

# CCR-C006-COUPON-POINTS-READ-001 P1 (approved 2026-10-06): session-scoped read-only C surface
# for "my coupons" and "my points". Read-only GETs only: default AVAILABLE bucket, three display
# buckets at most (D2 keeps FROZEN/RISK_FROZEN out of every reply), the anti-enumeration 404 on
# the detail route, and the fixed created_at DESC ledger ordering. No write operations exist in
# this family, so no RequestId requirement and no 201 replay surface.
COUPON_POINTS_READ_OPERATIONS = {
    'cListMyCoupons': ('get', '/c/coupons', ('200', '400', '401', '500')),
    'cGetMyCoupon': ('get', '/c/coupons/{couponId}', ('200', '400', '401', '404', '500')),
    'cGetPointsBalance': ('get', '/c/points/balance', ('200', '400', '401', '500')),
    'cListPointsLedger': ('get', '/c/points/ledger', ('200', '400', '401', '500')),
}

# Notification preferences (SSOT §16.4 slice, 2026-10-06): the two-switch C-side preference
# surface rides pet.auth.c.enabled like the inbox. Preference items are exactly the schema's
# interaction/external-push switches; the mandatory in-site kinds are not settable. The PUT is
# a supplement-23 bound command (same-requestId same-params replay, different-params 409); the
# GET reads the schema defaults when no row exists. No new error codes — the family pins the
# reused Error12 §2 set including the 409 pair.
NOTIFICATION_PREFERENCE_OPERATIONS = {
    'cGetNotificationPreferences': (
        'get', '/c/notification-preferences',
        ('200', '400', '401', '500', '503')),
    'cUpdateNotificationPreferences': (
        'put', '/c/notification-preferences',
        ('200', '400', '401', '409', '500', '503')),
}

# User address book (contract 59 / ruling-32 second batch, 2026-10-13): the C-side address CRUD
# family rides pet.auth.c.enabled like the pets/profile user-domain faces (no new assembly
# switch). The census pins the six routes, the owner-scoped default-off status, the 201/200
# create pair, the 20-address cap error (ADDRESS_LIMIT_EXCEEDED), the anti-enumeration 404
# (ADDRESS_NOT_FOUND) and the supplement-23 idempotency pair; orders keep the text-snapshot
# serviceAddress semantics untouched — the address book is only a C-side input source.
ADDRESS_OPERATIONS = {
    'cListMyAddresses': (
        'get', '/c/addresses',
        ('200', '400', '401', '500', '503')),
    'cCreateAddress': (
        'post', '/c/addresses',
        ('200', '201', '400', '401', '403', '409', '500', '503')),
    'cGetMyAddress': (
        'get', '/c/addresses/{addressId}',
        ('200', '400', '401', '404', '500', '503')),
    'cUpdateMyAddress': (
        'put', '/c/addresses/{addressId}',
        ('200', '400', '401', '403', '404', '409', '500', '503')),
    'cDeleteMyAddress': (
        'delete', '/c/addresses/{addressId}',
        ('200', '400', '401', '403', '404', '409', '500', '503')),
    'cSetDefaultAddress': (
        'post', '/c/addresses/{addressId}/set-default',
        ('200', '400', '401', '403', '404', '409', '500', '503')),
}

# HTTP10 §3.3.3/§3.3.4 (2026-10-19 C-wiring batch): the C catalog keyword search route rides the
# service-query assembly with the STR-D8 anonymous pair; the open-city directory is the
# store-catalog's anonymous companion (code/name pairs only, pure-anonymous security).
CATALOG_SEARCH_OPERATIONS = {
    'cSearchCatalogServices': (
        'get', '/c/services',
        ('200', '400', '401', '503')),
}
CITY_OPERATIONS = {
    'cListOpenCities': (
        'get', '/c/cities',
        ('200', '400', '503')),
}

# Contract 68 pet health records (2026-10-19 ruling; the same ruling overturns the registry
# §4.11 scope exclusion for the two add frames): owner-scoped list + idempotent create for the
# vaccine and deworming facets, the same supplement-23 frame as the address book, plus the
# metadata-only same-transaction audit.
PET_HEALTH_RECORD_OPERATIONS = {
    'cListVaccinationRecords': (
        'get', '/c/pets/{petId}/vaccination-records',
        ('200', '400', '401', '404', '500', '503')),
    'cCreateVaccinationRecord': (
        'post', '/c/pets/{petId}/vaccination-records',
        ('200', '201', '400', '401', '403', '404', '409', '500', '503')),
    'cListDewormingRecords': (
        'get', '/c/pets/{petId}/deworming-records',
        ('200', '400', '401', '404', '500', '503')),
    'cCreateDewormingRecord': (
        'post', '/c/pets/{petId}/deworming-records',
        ('200', '201', '400', '401', '403', '404', '409', '500', '503')),
}


def check_notification_preferences(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = NOTIFICATION_PREFERENCE_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Preference operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Preference security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Preference status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Preference must stay default off: {name}'
    assert operation.get('x-contract') == '10-HTTP-API-Contract-v0.4.md', f'Preference authority changed: {name}'
    assert operation.get('x-route-party') == 'USER' and operation.get('x-audience') == 'MINIAPP', f'Preference identity changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Preference response surface changed: {name}'
    for code in responses:
        response = dereference(spec, responses[code])
        cache = response['headers']['Cache-Control']['schema']
        assert cache == {'type': 'string', 'enum': ['no-store']}, f'Preference caching changed: {name} {code}'
        schema = response['content']['application/json']['schema']
        expected = {'$ref': '#/components/schemas/NotificationPreferenceErrorEnvelope'} if code != '200' \
            else {'$ref': '#/components/schemas/NotificationPreferenceEnvelope'}
        assert schema == expected, f'Preference envelope changed: {name} {code}'
    assert not [p for p in operation.get('parameters', []) if p.get('in') == 'query'], f'Preference query opened: {name}'
    if method == 'get':
        assert 'requestBody' not in operation, f'Preference GET must stay bodyless: {name}'
    else:
        body = dereference(spec, operation['requestBody'])
        assert body.get('required') is True, f'Preference PUT body must be required: {name}'
        request = dereference(spec, body['content']['application/json']['schema'])
        assert request == spec['components']['schemas']['NotificationPreferenceUpdate'] or \
            request is spec['components']['schemas']['NotificationPreferenceUpdate'], f'Preference PUT body changed: {name}'
    data = spec['components']['schemas']['NotificationPreferenceData']
    assert data.get('additionalProperties') is False, 'Preference projection opened'
    assert set(data['required']) == {'interactionEnabled', 'externalPushEnabled', 'version', 'updatedAt'}, 'Preference fields changed'
    assert set(data['properties']) == {'interactionEnabled', 'externalPushEnabled', 'version', 'updatedAt'}, 'Preference fields changed'
    assert data['properties']['interactionEnabled']['type'] == 'boolean', 'Interaction switch must stay boolean'
    assert data['properties']['externalPushEnabled']['type'] == 'boolean', 'Push switch must stay boolean'
    assert data['properties']['version']['pattern'] == r'^(0|[1-9][0-9]{0,18})(?![\s\S])$', 'Preference version width changed'
    assert data['properties']['updatedAt'].get('nullable') is True, 'updatedAt must stay nullable (unread default row)'
    update = spec['components']['schemas']['NotificationPreferenceUpdate']
    assert update.get('additionalProperties') is False, 'Preference update body opened'
    assert set(update['required']) == {'interactionEnabled', 'externalPushEnabled'}, 'Preference update must be a full two-switch PUT'


def check_baike_operations(spec, operation, method, path):
    # Contract 62 (PRD 5.1.4): anonymous-browse read trio. GET-only forever; the STR-D8-style
    # security pair ({} or bearerAuth) is what makes anonymous browsing real on the wire.
    name = operation['operationId']
    expected_method, expected_path, required_codes = BAIKE_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Encyclopedia operation moved: {name}'
    assert method == 'get', f'Encyclopedia face must stay read-only: {name}'
    assert operation.get('security') == [{}, {'bearerAuth': []}], f'Encyclopedia anonymous pair changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Encyclopedia status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Encyclopedia must stay default off: {name}'
    assert operation.get('x-contract') == '62-Baike-Content-Contract-v0.1.md', f'Encyclopedia authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.encyclopedia.query.enabled', f'Encyclopedia switch changed: {name}'
    assert operation.get('x-route-party') == 'USER' and operation.get('x-audience') == 'MINIAPP', f'Encyclopedia identity changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Encyclopedia response surface changed: {name}'
    envelopes = {
        'cListEncyclopediaCategories': 'BaikeCategoryListEnvelope',
        'cListEncyclopediaBreeds': 'BaikeBreedPageEnvelope',
        'cGetEncyclopediaBreed': 'BaikeBreedDetailEnvelope',
    }
    for code in required_codes:
        response = dereference(spec, responses[code])
        assert response['headers']['Cache-Control']['schema'] == {'type': 'string', 'enum': ['no-store']}, \
            f'Encyclopedia caching changed: {name} {code}'
        expected = {'$ref': '#/components/schemas/BaikeErrorEnvelope'} if code != '200' \
            else {'$ref': '#/components/schemas/' + envelopes[name]}
        assert response['content']['application/json']['schema'] == expected, f'Encyclopedia envelope changed: {name} {code}'
    assert 'requestBody' not in operation, f'Encyclopedia GET must stay bodyless: {name}'


def check_address_operations(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = ADDRESS_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Address operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Address security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Address status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Address must stay default off: {name}'
    assert operation.get('x-contract') == '59-User-Address-Contract-v0.1.md', f'Address authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.auth.c.enabled', f'Address assembly switch changed: {name}'
    assert operation.get('x-route-party') == 'USER' and operation.get('x-audience') == 'MINIAPP', f'Address identity changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Address response surface changed: {name}'
    success_envelopes = {
        'cListMyAddresses': 'AddressListEnvelope',
        'cCreateAddress': 'AddressEnvelope',
        'cGetMyAddress': 'AddressEnvelope',
        'cUpdateMyAddress': 'AddressEnvelope',
        'cDeleteMyAddress': 'AddressDeleteEnvelope',
        'cSetDefaultAddress': 'AddressEnvelope',
    }
    for code in responses:
        response = dereference(spec, responses[code])
        cache = response['headers']['Cache-Control']['schema']
        assert cache == {'type': 'string', 'enum': ['no-store']}, f'Address caching changed: {name} {code}'
        schema = response['content']['application/json']['schema']
        expected = {'$ref': '#/components/schemas/AddressErrorEnvelope'} if code not in {'200', '201'} \
            else {'$ref': '#/components/schemas/' + success_envelopes[name]}
        assert schema == expected, f'Address envelope changed: {name} {code}'
    assert not [p for p in operation.get('parameters', []) if p.get('in') == 'query'], f'Address query opened: {name}'
    resolved = [dereference(spec, p) for p in operation.get('parameters', [])]
    if '{addressId}' in path:
        assert any(p.get('name') == 'addressId' and p.get('in') == 'path' for p in resolved), f'AddressId path parameter missing: {name}'
    if method in {'get', 'delete'} or name == 'cSetDefaultAddress':
        # delete and set-default are bodyless (soft-delete receipt / default flip need no
        # business body, like the 45号/57号 decision routes); GET is bodyless by definition.
        assert 'requestBody' not in operation, f'Address {method} must stay bodyless: {name}'
    else:
        # create/update carry the strict five-field upsert body.
        body = dereference(spec, operation['requestBody'])
        assert body.get('required') is True, f'Address write body must be required: {name}'
        request = dereference(spec, body['content']['application/json']['schema'])
        assert request is spec['components']['schemas']['AddressUpsert'], f'Address write body changed: {name}'
    data = spec['components']['schemas']['AddressData']
    assert data.get('additionalProperties') is False, 'Address projection opened'
    assert set(data['required']) == {'addressId', 'contactName', 'contactPhone', 'region', 'detail', 'isDefault', 'status'}, 'Address fields changed'
    assert data['properties']['isDefault']['type'] == 'boolean', 'Address default flag must stay boolean'
    assert data['properties']['status']['enum'] == ['ACTIVE', 'DELETED'], 'Address status vocabulary changed'
    assert data['properties']['contactPhone']['pattern'] == '^1[3-9][0-9]{9}$', 'Address phone pattern changed'
    upsert = spec['components']['schemas']['AddressUpsert']
    assert upsert.get('additionalProperties') is False, 'Address upsert body opened'
    assert set(upsert['required']) == {'contactName', 'contactPhone', 'region', 'detail'}, 'Address upsert must keep isDefault optional'
    envelope = spec['components']['schemas']['AddressErrorEnvelope']
    assert 'ADDRESS_LIMIT_EXCEEDED' in envelope['properties']['code']['enum'], 'Address cap error code missing'
    assert 'ADDRESS_NOT_FOUND' in envelope['properties']['code']['enum'], 'Address anti-enumeration code missing'


# User settings hub / account deletion (contract 66, PRD 5.1.29; user ruling 2026-10-19
# "全做"; CCR-USR-DELETION-001 minimum): the precheck read + the idempotent deletion write,
# riding pet.usersettings.http.enabled (hard preconditions pet.auth.c.enabled and
# pet.merchant.application.enabled — the OWNER-membership surface). The blocking fact is
# composed in the user kernel from the order/aftersale leaf fact ports; the CANCELED flip is
# the SQL06 §1 word and login refusal / session death stay the auth slice's existing
# semantics (zero new code there). Synchronous cancel: no cooldown, no "applying" state,
# no purge AsyncTask (all CCR §2).
USER_SETTINGS_OPERATIONS = {
    'cGetDeletionPrecheck': (
        'get', '/c/account/deletion-precheck',
        ('200', '400', '401', '503')),
    'cDeleteAccount': (
        'post', '/c/account/deletion',
        ('200', '201', '400', '401', '403', '409', '500', '503')),
}

# Feedback tickets (contract 67, PRD 5.1.28 complaint class; CCR-CS-FEEDBACK-001 minimum):
# C-side create/list/get over the customer-service skeleton module's first business face,
# riding pet.feedback.http.enabled (hard precondition pet.auth.c.enabled). Frozen five-value
# status word with V1 permanently PENDING (no ops face); text-only tickets (image attachments
# are a separate asset-channel ruling); owner-scoped reads with one indistinguishable 404;
# the detail view carries the empty reply/resolution field slots.
FEEDBACK_HTTP_OPERATIONS = {
    'cCreateFeedbackTicket': (
        'post', '/c/feedback-tickets',
        ('200', '201', '400', '401', '403', '409', '429', '500', '503')),
    'cListFeedbackTickets': (
        'get', '/c/feedback-tickets',
        ('200', '400', '401', '503')),
    'cGetFeedbackTicket': (
        'get', '/c/feedback-tickets/{ticketId}',
        ('200', '400', '401', '404', '503')),
}


def check_user_settings(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = USER_SETTINGS_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Deletion operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Deletion security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Deletion status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Deletion must stay default off: {name}'
    assert operation.get('x-contract') == '66-User-Settings-Deletion-Contract-v0.1.md', f'Deletion authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.usersettings.http.enabled', f'Deletion assembly switch changed: {name}'
    assert operation.get('x-route-party') == 'USER' and operation.get('x-audience') == 'MINIAPP', f'Deletion identity changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Deletion response surface changed: {name}'
    success_envelopes = {
        'cGetDeletionPrecheck': 'DeletionPrecheckEnvelope',
        'cDeleteAccount': 'DeletionReceiptEnvelope',
    }
    for code in responses:
        response = dereference(spec, responses[code])
        cache = response['headers']['Cache-Control']['schema']
        assert cache == {'type': 'string', 'enum': ['no-store']}, f'Deletion caching changed: {name} {code}'
        schema = response['content']['application/json']['schema']
        expected = {'$ref': '#/components/schemas/DeletionErrorEnvelope'} if code not in {'200', '201'} \
            else {'$ref': '#/components/schemas/' + success_envelopes[name]}
        assert schema == expected, f'Deletion envelope changed: {name} {code}'
    blocker = spec['components']['schemas']['DeletionBlocker']
    assert blocker.get('additionalProperties') is False, 'Deletion blocker projection opened'
    assert blocker['properties']['code']['enum'] == ['USER_ORDERS_UNSETTLED', 'USER_AFTERSALES_OPEN',
        'OWNER_STORE_ORDERS_UNSETTLED', 'OWNER_STORE_AFTERSALES_OPEN'], 'Deletion blocker catalog changed'
    request = spec['components']['schemas']['DeletionRequest']
    assert request.get('additionalProperties') is False and request['required'] == ['acknowledged'], 'Deletion body opened'
    assert request['properties']['acknowledged']['enum'] == [True], 'Deletion acknowledgement loosened'
    envelope = spec['components']['schemas']['DeletionErrorEnvelope']
    assert 'DELETION_BLOCKED' in envelope['properties']['code']['enum'], 'Deletion blocked code missing'
    receipt = spec['components']['schemas']['DeletionReceiptEnvelope']['properties']['data']
    assert receipt['properties']['status']['enum'] == ['CANCELED'], 'Deletion outcome word changed'
    if method == 'get':
        assert 'requestBody' not in operation and not [p for p in operation.get('parameters', []) if p.get('in') == 'query'], 'Precheck query opened'


def check_feedback_http(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = FEEDBACK_HTTP_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Feedback operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Feedback security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Feedback status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Feedback must stay default off: {name}'
    assert operation.get('x-contract') == '67-Feedback-Ticket-Contract-v0.1.md', f'Feedback authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.feedback.http.enabled', f'Feedback assembly switch changed: {name}'
    assert operation.get('x-route-party') == 'USER' and operation.get('x-audience') == 'MINIAPP', f'Feedback identity changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Feedback response surface changed: {name}'
    success_envelopes = {
        'cCreateFeedbackTicket': 'FeedbackTicketCreateEnvelope',
        'cListFeedbackTickets': 'FeedbackTicketPageEnvelope',
        'cGetFeedbackTicket': 'FeedbackTicketDetailEnvelope',
    }
    for code in responses:
        response = dereference(spec, responses[code])
        cache = response['headers']['Cache-Control']['schema']
        assert cache == {'type': 'string', 'enum': ['no-store']}, f'Feedback caching changed: {name} {code}'
        schema = response['content']['application/json']['schema']
        expected = {'$ref': '#/components/schemas/FeedbackErrorEnvelope'} if code not in {'200', '201'} \
            else {'$ref': '#/components/schemas/' + success_envelopes[name]}
        assert schema == expected, f'Feedback envelope changed: {name} {code}'
    types = ['FEATURE_SUGGESTION', 'BUG_REPORT', 'SERVICE_COMPLAINT', 'OTHER']
    statuses = ['PENDING', 'PROCESSING', 'WAITING_SUPPLEMENT', 'RESOLVED', 'CLOSED']
    create = spec['components']['schemas']['FeedbackTicketCreate']
    assert create.get('additionalProperties') is False and create['required'] == ['type', 'content'], 'Feedback create body opened'
    assert create['properties']['type']['enum'] == types, 'Feedback type vocabulary changed'
    assert create['properties']['content']['maxLength'] == 500, 'Feedback content bound changed'
    summary = spec['components']['schemas']['FeedbackTicketSummary']
    assert summary['properties']['status']['enum'] == statuses, 'Feedback status vocabulary changed'
    assert 'contact' not in summary['properties'], 'Feedback summary leaked the contact field'
    detail = spec['components']['schemas']['FeedbackTicketDetailEnvelope']['properties']['data']
    assert detail['properties']['replies']['maxItems'] == 0, 'Feedback reply slot opened'
    assert detail['properties']['resolution']['enum'] == [None], 'Feedback resolution slot opened'
    envelope = spec['components']['schemas']['FeedbackErrorEnvelope']
    for code in ('FEEDBACK_TYPE_INVALID', 'FEEDBACK_TICKET_NOT_FOUND', 'COMMON_RATE_LIMITED', 'USER_FROZEN'):
        assert code in envelope['properties']['code']['enum'], f'Feedback error code missing: {code}'

# HTTP10 §3.3.3 C catalog keyword search (2026-10-19 ruling): the service-domain global route
# keeps the four-condition visibility inside the service module; the HTTP face only validates
# the keyword bounds and pages over the honest visible total. Same STR-D8 anonymous discipline
# and no-store caching as the rest of the catalog family.
def check_catalog_search(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = CATALOG_SEARCH_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Catalog search moved: {name}'
    assert method == 'get', f'Catalog search must stay read-only: {name}'
    assert operation.get('security') == ANONYMOUS_BROWSE_SECURITY, f'Catalog search security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Catalog search status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Catalog search must stay default off: {name}'
    assert operation.get('x-contract') == '10-HTTP-API-Contract-v0.4.md', f'Catalog search authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.service.query.enabled', f'Catalog search switch changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Catalog search response surface changed: {name}'
    for code in responses:
        response = dereference(spec, responses[code])
        assert response['headers']['Cache-Control']['schema'] == {'type': 'string', 'enum': ['no-store']}, \
            f'Catalog search caching changed: {name} {code}'
        assert response['content']['application/json']['schema'] == {'$ref': '#/components/schemas/MerErrorEnvelope'}, \
            f'Catalog search envelope changed: {name}'
    keyword = [dereference(spec, p) for p in operation.get('parameters', []) if dereference(spec, p).get('name') == 'keyword']
    assert len(keyword) == 1 and keyword[0].get('required') is True, 'Catalog search keyword must be a required query parameter'
    assert keyword[0]['schema']['maxLength'] == 30, 'Catalog search keyword bound changed'
    assert 'requestBody' not in operation, f'Catalog search GET must stay bodyless: {name}'


# HTTP10 §3.3.4 open-city directory (2026-10-19 ruling): pure-anonymous GET (a carried bearer is
# not even resolved — the route has no session semantics), code/name pairs of the trusted startup
# catalog, query forbidden, 503 fail-closed on an unconfigured catalog.
def check_city_directory(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = CITY_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'City directory moved: {name}'
    assert method == 'get', f'City directory must stay read-only: {name}'
    assert operation.get('security') == [{}], f'City directory must stay pure-anonymous: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'City directory status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'City directory must stay default off: {name}'
    assert operation.get('x-contract') == '10-HTTP-API-Contract-v0.4.md', f'City directory authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.store.query.enabled', f'City directory switch changed: {name}'
    assert not operation.get('parameters'), f'City directory must stay parameter-free: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'City directory response surface changed: {name}'
    for code in responses:
        response = dereference(spec, responses[code])
        assert response['headers']['Cache-Control']['schema'] == {'type': 'string', 'enum': ['no-store']}, \
            f'City directory caching changed: {name} {code}'
        expected = {'$ref': '#/components/schemas/MerErrorEnvelope'} if code != '200' \
            else {'$ref': '#/components/schemas/CityListEnvelope'}
        assert response['content']['application/json']['schema'] == expected, f'City directory envelope changed: {name}'
    envelope = dereference(spec, spec['components']['schemas']['CityListEnvelope'])
    items = dereference(spec, envelope['properties']['data']['items'])
    assert set(items['required']) == {'cityCode', 'cityName'}, 'City directory item fields changed'


# Contract 68 pet health records (2026-10-19 ruling): the same owner-scoped, anti-enumerated,
# supplement-23 idempotent frame as the address book, capped per pet per type, with the
# metadata-only audit written in the same transaction as the first submission.
def check_pet_health_records(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = PET_HEALTH_RECORD_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Health record operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Health record security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Health record status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Health record must stay default off: {name}'
    assert operation.get('x-contract') == '68-Pet-Health-Record-Contract-v0.1.md', f'Health record authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.auth.c.enabled', f'Health record switch changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Health record response surface changed: {name}'
    for code in responses:
        response = dereference(spec, responses[code])
        assert response['headers']['Cache-Control']['schema'] == {'type': 'string', 'enum': ['no-store']}, \
            f'Health record caching changed: {name} {code}'
        expected = {'$ref': '#/components/schemas/PetHealthRecordErrorEnvelope'} if code != '200' and code != '201' \
            else {'$ref': '#/components/schemas/PetHealthRecordEnvelope'}
        if code == '200' and method == 'get':
            expected = {'$ref': '#/components/schemas/PetHealthRecordListEnvelope'}
        assert response['content']['application/json']['schema'] == expected, f'Health record envelope changed: {name}'
    assert not [p for p in operation.get('parameters', []) if dereference(spec, p).get('in') == 'query'], \
        f'Health record query opened: {name}'
    if method == 'get':
        assert 'requestBody' not in operation, f'Health record GET must stay bodyless: {name}'
    else:
        body = dereference(spec, operation['requestBody'])
        assert body.get('required') is True, f'Health record write body must be required: {name}'
        request = dereference(spec, body['content']['application/json']['schema'])
        upsert = 'VaccinationRecordUpsert' if name == 'cCreateVaccinationRecord' else 'DewormingRecordUpsert'
        assert request is spec['components']['schemas'][upsert], f'Health record write body changed: {name}'
    error = spec['components']['schemas']['PetHealthRecordErrorEnvelope']
    assert 'PET_HEALTH_RECORD_LIMIT_EXCEEDED' in error['properties']['code']['enum'], 'Health record cap code missing'
    assert 'PET_NOT_FOUND' in error['properties']['code']['enum'], 'Health record anti-enumeration code missing'


# Staff invitation list (contract 54 §7 slice, 2026-10-07): the employee-side self-service
# list closes the offline-number-only gap left by the confirm channel. The session's verified
# account phone is matched server-side (never crossing the user module in plaintext); a
# non-matching session reads the same empty page — anti-enumeration is pinned by schema shape:
# the summary carries no phone field in any form. Read-only family: Error12 §2 subset without
# the idempotency/conflict pair; fixed id DESC ordering; query surface is exactly page/pageSize.
STAFF_INVITATION_LIST_OPERATIONS = {
    'cListMyStaffInvitations': (
        'get', '/c/staff/invitations',
        ('200', '400', '401', '503')),
}

# C-side order write face (contract 10 §3.5/§3.6 slice, 2026-10-07): the two POST routes leave
# the unimplemented legacy draft state behind and ride pet.auth.c.enabled like the §3.7 reads.
# The census pins the default-off status, the §3.x response surface (create keeps the
# 201-first/200-replay pair with identical CreateOrderResponseEnvelope bodies) and the fixed
# channel body. Booking eligibility, ownership, five-tuple idempotency and the payment window
# stay in the 38/40/41 kernels — no new error codes and no route changes here. The two
# operations stay in LEGACY_OPERATIONS as well, so the S1 shape guards there keep applying.
ORDER_WRITE_OPERATIONS = {
    'createOrder': ('post', '/c/orders', ('200', '201', '401', '403', '409', '503')),
    'createOrderPayment': ('post', '/c/orders/{orderId}/payments', ('200', '401', '403', '409', '503')),
}


REVIEW_HTTP_OPERATIONS = {
    'getReviewEligibility': (
        'get', '/c/orders/{orderId}/review-eligibility',
        ('200', '400', '401', '403', '404', '503')),
    'createReview': (
        'post', '/c/orders/{orderId}/reviews',
        ('200', '201', '400', '401', '403', '404', '409', '503')),
}

REVIEW_APPEAL_HTTP_OPERATIONS = {
    'merchantListReviews': (
        'get', '/merchant/reviews',
        ('200', '400', '401', '403', '503')),
    'merchantGetReview': (
        'get', '/merchant/reviews/{reviewId}',
        ('200', '400', '401', '403', '404', '503')),
    'merchantAppealReview': (
        'post', '/merchant/reviews/{reviewId}/appeal',
        ('200', '201', '400', '401', '403', '404', '409', '503')),
    'adminListReviewAppeals': (
        'get', '/admin/review-appeals',
        ('200', '400', '401', '403', '503')),
    'adminGetReviewAppeal': (
        'get', '/admin/review-appeals/{appealId}',
        ('200', '400', '401', '403', '404', '503')),
    'adminDecideReviewAppeal': (
        'post', '/admin/review-appeals/{appealId}/decision',
        ('200', '400', '401', '403', '404', '409', '503')),
}

RESCHEDULE_HTTP_OPERATIONS = {
    'rescheduleOrder': (
        'post', '/c/orders/{orderId}/reschedule',
        ('200', '400', '401', '403', '409', '503')),
}

# Merchant refund HTTP face (contract 57, 2026-10-10): the four M-side routes assemble the
# approved 49号 kernel decide semantics behind pet.refund.merchant.http.enabled. The two
# decision routes stay in LEGACY_OPERATIONS as well, so the S1 shape guards there keep
# applying; list/detail are new operationIds not in LEGACY_OPERATIONS. The census pins the
# default-off status, the read-side anti-enumeration surface (reads carry 403, never 404)
# and the fixed seven-field decision receipt; reads stay GET without X-Request-Id binding.
MERCHANT_REFUND_HTTP_OPERATIONS = {
    'merchantListRefundApplications': (
        'get', '/merchant/refund-applications',
        ('200', '400', '401', '403', '503')),
    'merchantReadRefundApplication': (
        'get', '/merchant/refund-applications/{applicationId}',
        ('200', '400', '401', '403', '503')),
    'merchantApproveRefund': (
        'post', '/merchant/refund-applications/{applicationId}/approve',
        ('200', '400', '401', '403', '404', '409', '503')),
    'merchantRejectRefund': (
        'post', '/merchant/refund-applications/{applicationId}/reject',
        ('200', '400', '401', '403', '404', '409', '503')),
}

# Favorite/footprint C face (contract 58, 2026-10-13, 32号裁决第二节批次): user-private
# SERVICE/STORE favorites and browse footprints behind pet.favtrack.http.enabled. The
# census pins the default-off status, the review-family 201/200 create split (only the very
# first create answers 201), the bodyless writes (no requestBody on any command), the fixed
# page bounds, and the shared catalog display projection on list rows.
FAVTRACK_HTTP_OPERATIONS = {
    'cListMyFavorites': (
        'get', '/c/favorites',
        ('200', '400', '401', '503')),
    'cAddFavorite': (
        'post', '/c/favorites/{targetType}/{targetId}',
        ('200', '201', '400', '401', '403', '404', '409', '503')),
    'cCancelFavorite': (
        'delete', '/c/favorites/{targetType}/{targetId}',
        ('200', '400', '401', '403', '409')),
    'cGetFavoriteStatus': (
        'get', '/c/favorites/{targetType}/{targetId}',
        ('200', '400', '401')),
    'cListMyFootprints': (
        'get', '/c/footprints',
        ('200', '400', '401', '503')),
    'cRecordFootprint': (
        'post', '/c/footprints/{targetType}/{targetId}',
        ('200', '201', '400', '401', '403', '404', '409', '503')),
    'cDeleteFootprint': (
        'delete', '/c/footprints/{footprintId}',
        ('200', '400', '401', '403', '409')),
}
# Points earn family (contract-58 / 32号 ruling second batch, 2026-10-13): sign-in + referral
# earn surface plus the ops-configured grant amounts. V1 points only accrue and get clawed
# back — no spend/exchange route exists in this family. The three POST commands are
# supplement-23 bound commands (same-requestId same-params replay, different-params 409);
# the daily re-sign with a fresh requestId is the 409 COMMON_CONFLICT pair. Reads carry no
# X-Request-Id; every reply is no-store.
POINTS_TASK_OPERATIONS = {
    'cGetSignInStatus': (
        'get', '/c/points/sign-in/status',
        ('200', '400', '401', '500', '503')),
    'cSignIn': (
        'post', '/c/points/sign-in',
        ('200', '400', '401', '409', '500', '503')),
    'cListPointsTasks': (
        'get', '/c/points/tasks',
        ('200', '400', '401', '500', '503')),
    'cClaimPointsReward': (
        'post', '/c/points/claims',
        ('200', '400', '401', '409', '500', '503')),
    'cBindReferralCode': (
        'post', '/c/points/referral-bindings',
        ('200', '400', '401', '409', '500', '503')),
    'adminGetPointsTaskConfig': (
        'get', '/admin/points-task-config',
        ('200', '400', '401', '403', '500', '503')),
    'adminUpdatePointsTaskConfig': (
        'put', '/admin/points-task-config',
        ('200', '400', '401', '403', '409', '500', '503')),
}

# Contract 62 encyclopedia content read face (PRD 5.1.4): anonymous-browse GET trio, read-only.
BAIKE_OPERATIONS = {
    'cListEncyclopediaCategories': (
        'get', '/c/encyclopedia/categories',
        ('200', '400', '401', '500')),
    'cListEncyclopediaBreeds': (
        'get', '/c/encyclopedia/breeds',
        ('200', '400', '401', '500')),
    'cGetEncyclopediaBreed': (
        'get', '/c/encyclopedia/breeds/{breedId}',
        ('200', '400', '401', '404', '500')),
}


# Contract 63 (2026-10-14 read face; 2026-10-15 v0.2 disposal extension, ruling doc 33):
# platform trade face. Seven GET-only reads + four high-privilege writes — channel-query/retry
# (HTTP10 section 5.2), abnormal-close (section 5.5) and merchant disable (section 5.6), all on
# the same pet.admin.trade.http.enabled face switch, writes bound to a terminal X-Request-Id.
ADMIN_TRADE_OPERATIONS = {
    'adminListTradeOrders': (
        'get', '/admin/trade/orders',
        ('200', '400', '401', '403', '503')),
    'adminGetTradeOrder': (
        'get', '/admin/trade/orders/{orderId}',
        ('200', '400', '401', '403', '404', '503')),
    'adminListTradeOrderStatusLogs': (
        'get', '/admin/trade/orders/{orderId}/status-logs',
        ('200', '400', '401', '403', '404', '503')),
    'adminListTradeRefunds': (
        'get', '/admin/trade/refunds',
        ('200', '400', '401', '403', '503')),
    'adminGetTradeRefund': (
        'get', '/admin/trade/refunds/{refundOrderId}',
        ('200', '400', '401', '403', '404', '503')),
    'adminListTradeVerifications': (
        'get', '/admin/trade/verifications',
        ('200', '400', '401', '403', '503')),
    'adminGetTradeVerification': (
        'get', '/admin/trade/verifications/{verificationId}',
        ('200', '400', '401', '403', '404', '503')),
    'adminChannelQueryTradeRefund': (
        'post', '/admin/refunds/{refundId}/channel-query',
        ('200', '400', '401', '403', '404', '409', '503')),
    'adminRetryTradeRefund': (
        'post', '/admin/refunds/{refundId}/retry',
        ('200', '400', '401', '403', '404', '409', '503')),
    'adminAbnormalCloseTradeOrder': (
        'post', '/admin/orders/{orderId}/abnormal-close',
        ('200', '400', '401', '403', '404', '409', '503')),
    'adminDisableTradeMerchant': (
        'post', '/admin/merchants/{merchantId}/disable',
        ('200', '400', '401', '403', '404', '409', '503')),
}


# Contract 64 (2026-10-15, ADM-001 gap slice / A-002 phase 2): platform governance read
# face. Eight GET-only operations over the merchant and schedule kernels; every error
# response reuses the shared ErrorEnvelope components. No write route exists in this
# family: merchant disable/freeze (HTTP10 section 5.6) and every staff disposition stay
# outside it (SSOT section 30 grants the operator no staff write action).
ADMIN_GOVERNANCE_OPERATIONS = {
    'adminListGovernanceMerchants': (
        'get', '/admin/governance/merchants',
        ('200', '400', '401', '403', '503')),
    'adminGetGovernanceMerchant': (
        'get', '/admin/governance/merchants/{merchantId}',
        ('200', '400', '401', '403', '404', '503')),
    'adminListGovernanceMerchantStaff': (
        'get', '/admin/governance/merchants/{merchantId}/staff',
        ('200', '400', '401', '403', '404', '503')),
    'adminListGovernanceStaffMembers': (
        'get', '/admin/governance/merchants/{merchantId}/staff-members',
        ('200', '400', '401', '403', '404', '503')),
    'adminListGovernanceStaffInvitations': (
        'get', '/admin/governance/merchants/{merchantId}/staff-members/invitations',
        ('200', '400', '401', '403', '404', '503')),
    'adminListGovernanceScheduleWindows': (
        'get', '/admin/governance/schedule/windows',
        ('200', '400', '401', '403', '503')),
    'adminListGovernanceScheduleStaffWindows': (
        'get', '/admin/governance/schedule/staff-windows',
        ('200', '400', '401', '403', '503')),
    'adminListGovernanceScheduleReservations': (
        'get', '/admin/governance/schedule/reservations',
        ('200', '400', '401', '403', '503')),
}


def check_order_write(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = ORDER_WRITE_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Order write operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Order write security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Order write status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Order write must stay default off: {name}'
    assert operation.get('x-contract') == '10-HTTP-API-Contract-v0.4.md', f'Order write authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.auth.c.enabled', f'Order write switch changed: {name}'
    assert operation.get('x-route-party') == 'USER' and operation.get('x-audience') == 'MINIAPP', f'Order write identity changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Order write response surface changed: {name}'
    if name == 'createOrder':
        assert responses['201']['content'] == responses['200']['content'], f'Order create replay changed: {name}'
        assert responses['200']['content']['application/json']['schema'] == {
            '$ref': '#/components/schemas/CreateOrderResponseEnvelope'}, f'Order create envelope changed: {name}'
    else:
        body = dereference(spec, operation['requestBody'])
        assert body.get('required') is True, f'Payment initiation body must be required: {name}'
        request = dereference(spec, body['content']['application/json']['schema'])
        assert set(request['required']) == {'channel'}, f'Payment channel body opened: {name}'
        assert request['properties']['channel']['enum'] == ['WECHAT_MINI_PROGRAM'], f'Payment channel enum opened: {name}'



def check_reschedule_http(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = RESCHEDULE_HTTP_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Reschedule HTTP operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Reschedule HTTP security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Reschedule HTTP status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Reschedule HTTP must stay default off: {name}'
    assert operation.get('x-contract') == '46-Order-Reschedule-Contract-v0.1.md', f'Reschedule HTTP authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.order.reschedule.http.enabled', f'Reschedule HTTP switch changed: {name}'
    assert operation.get('x-route-party') == 'USER' and operation.get('x-audience') == 'MINIAPP', f'Reschedule HTTP identity changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Reschedule HTTP response surface changed: {name}'
    success = responses['200']['content']['application/json']['schema']
    assert success['allOf'][1]['properties']['data'] == {'$ref': '#/components/schemas/RescheduleReceipt'}         and success['allOf'][0] == {'$ref': '#/components/schemas/BaseEnvelope'}, f'Reschedule receipt ref changed: {name}'
    for code in ('400', '401', '403', '409', '503'):
        assert dereference(spec, responses[code])['content']['application/json']['schema']             == {'$ref': '#/components/schemas/ErrorEnvelope'}, f'Reschedule HTTP error envelope changed: {name} {code}'
    request = spec['components']['schemas']['RescheduleRequest']
    assert request.get('additionalProperties') is False and request['required'] == ['expectedOrderVersion'],         'Reschedule body opened beyond the version and one branch'
    assert request['properties']['expectedOrderVersion']['pattern'] == r'^(0|[1-9][0-9]*)$',         'Reschedule version pattern changed'
    assert len(request['oneOf']) == 2, 'Reschedule oneOf branches changed'


def check_review_http(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = REVIEW_HTTP_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Review HTTP operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Review HTTP security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Review HTTP status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Review HTTP must stay default off: {name}'
    assert operation.get('x-contract') == '10-HTTP-API-Contract-v0.4.md', f'Review HTTP authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.review.http.enabled', f'Review HTTP switch changed: {name}'
    assert operation.get('x-route-party') == 'USER' and operation.get('x-audience') == 'MINIAPP', f'Review HTTP identity changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Review HTTP response surface changed: {name}'
    if name == 'createReview':
        body = dereference(spec, operation['requestBody'])
        request = dereference(spec, body['content']['application/json']['schema'])
        assert request.get('additionalProperties') is False, 'Review create body opened beyond the contract fields'
        assert set(request['required']) == {'storeScore', 'serviceScore', 'staffScore'},             'Review create must keep the three required dimensions'
        for dimension in ('storeScore', 'serviceScore', 'staffScore'):
            bound = request['properties'][dimension]
            assert bound.get('type') == 'integer' and bound.get('minimum') == 1 and bound.get('maximum') == 5,                 f'Review score bounds changed: {dimension}'
        assert request['properties']['content'].get('maxLength') == 2000, 'Review content bounds changed'
        assert responses['201']['content'] == responses['200']['content'] == {
            'application/json': {'schema': {'$ref': '#/components/schemas/CreateReviewResponseEnvelope'}}},             'Review create/replay schema changed'
        data = spec['components']['schemas']['CreateReviewData']
        assert data.get('additionalProperties') is False, 'Review receipt opened'
        assert set(data['required']) == {'reviewId', 'scoreIncluded'}, 'Review receipt fields changed'
        assert data['properties']['scoreIncluded'].get('type') == 'boolean', 'Review score fact must stay boolean'
    else:
        assert responses['200']['content'] == {
            'application/json': {'schema': {'$ref': '#/components/schemas/ReviewEligibilityResponseEnvelope'}}},             'Review eligibility success envelope changed'
    for code in ('400', '401', '403', '404', '503'):
        assert dereference(spec, responses[code])['content']['application/json']['schema']             == {'$ref': '#/components/schemas/ErrorEnvelope'}, f'Review HTTP error envelope changed: {name} {code}'


def check_review_appeal_http(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = REVIEW_APPEAL_HTTP_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Review appeal operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Review appeal security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Review appeal status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Review appeal must stay default off: {name}'
    assert operation.get('x-contract') == '56-Review-Appeal-Contract-v0.1.md', f'Review appeal authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.review.appeal.http.enabled', f'Review appeal switch changed: {name}'
    merchant_side = name.startswith('merchant')
    assert operation.get('x-route-party') == ('MERCHANT' if merchant_side else 'OPS'), f'Review appeal identity changed: {name}'
    assert operation.get('x-audience') == ('MINIAPP' if merchant_side else 'ADMIN_WEB'), f'Review appeal audience changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Review appeal response surface changed: {name}'
    for code in ('400', '401', '403', '404', '409', '503'):
        if code in responses:
            envelope = dereference(spec, responses[code])['content']['application/json']['schema']
            assert envelope == {'$ref': '#/components/schemas/ErrorEnvelope'}, f'Review appeal error envelope changed: {name} {code}'

def check_merchant_refund_http(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = MERCHANT_REFUND_HTTP_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Merchant refund HTTP operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Merchant refund HTTP security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Merchant refund HTTP status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Merchant refund HTTP must stay default off: {name}'
    assert operation.get('x-contract') == '57-Merchant-Refund-Application-Contract-v0.1.md', f'Merchant refund HTTP authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.refund.merchant.http.enabled', f'Merchant refund HTTP switch changed: {name}'
    assert operation.get('x-route-party') == 'MERCHANT' and operation.get('x-audience') == 'MINIAPP', f'Merchant refund HTTP identity changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Merchant refund HTTP response surface changed: {name}'
    for code in required_codes:
        if code != '200':
            assert dereference(spec, responses[code])['content']['application/json']['schema']                 == {'$ref': '#/components/schemas/ErrorEnvelope'}, f'Merchant refund HTTP error envelope changed: {name} {code}'
    success = responses['200']
    cache = success['headers']['Cache-Control']['schema']
    assert cache == {'type': 'string', 'enum': ['no-store']}, f'Merchant refund HTTP caching changed: {name}'
    if name == 'merchantListRefundApplications':
        assert success['content'] == {'application/json': {'schema': {'$ref': '#/components/schemas/MerchantRefundApplicationPageEnvelope'}}},             f'Merchant refund list envelope changed: {name}'
        summary = spec['components']['schemas']['MerchantRefundApplicationSummary']
        assert summary.get('additionalProperties') is False, 'Merchant refund summary opened'
        assert 'decidedAt' not in summary['properties'] and 'decisionId' not in summary['properties'],             'Merchant refund summary must not carry terminal decision fields'
    elif name == 'merchantReadRefundApplication':
        assert success['content'] == {'application/json': {'schema': {'$ref': '#/components/schemas/MerchantRefundApplicationDetailEnvelope'}}},             f'Merchant refund detail envelope changed: {name}'
        detail = spec['components']['schemas']['MerchantRefundApplicationDetail']
        assert detail.get('additionalProperties') is False, 'Merchant refund detail opened'
        for field in ('decidedAt', 'decisionId', 'refundOrderId'):
            terminal = detail['properties'][field]
            assert terminal.get('type') == 'string' and terminal.get('nullable') is True,                 f'Merchant refund detail terminal field must stay an inline nullable string: {field}'
    else:
        assert success['content'] == {'application/json': {'schema': {'$ref': '#/components/schemas/MerchantRefundDecisionEnvelope'}}},             f'Merchant refund decision envelope changed: {name}'
        receipt = spec['components']['schemas']['MerchantRefundDecisionReceipt']
        assert receipt.get('additionalProperties') is False, 'Merchant refund receipt opened'
        assert set(receipt['required']) == {'orderId', 'applicationId', 'applicationStatus',             'applicationVersion', 'merchantDeadline', 'decidedAt', 'decisionId'}, 'Merchant refund receipt fields changed'
        assert receipt['properties']['applicationStatus']['enum'] == ['APPROVED', 'REJECTED'],             'Merchant refund receipt status enum changed'
        if name == 'merchantApproveRefund':
            assert 'requestBody' not in operation, 'Merchant refund approve must stay business-body-free'
        else:
            body = dereference(spec, operation['requestBody'])
            assert body.get('required') is True, f'Merchant refund reject body must be required: {name}'
            request = dereference(spec, body['content']['application/json']['schema'])
            assert request.get('additionalProperties') is False, 'Merchant refund reject body opened'
            assert set(request['required']) == {'reasonText'}, 'Merchant refund reject must keep reasonText alone'
            reason = request['properties']['reasonText']
            assert reason.get('type') == 'string' and reason.get('minLength') == 1 and reason.get('maxLength') == 500,                 'Merchant refund reject reason bounds changed'


def check_favtrack_http(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = FAVTRACK_HTTP_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Favtrack HTTP operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Favtrack HTTP security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Favtrack HTTP status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Favtrack HTTP must stay default off: {name}'
    assert operation.get('x-contract') == '58-Favorite-Footprint-Contract-v0.1.md', f'Favtrack HTTP authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.favtrack.http.enabled', f'Favtrack HTTP switch changed: {name}'
    assert operation.get('x-route-party') == 'USER' and operation.get('x-audience') == 'MINIAPP', f'Favtrack HTTP identity changed: {name}'
    assert 'requestBody' not in operation, f'Favtrack routes must stay business-body-free: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Favtrack HTTP response surface changed: {name}'
    success_envelopes = {
        'cListMyFavorites': 'FavoritePageEnvelope',
        'cAddFavorite': 'FavoriteAddEnvelope',
        'cCancelFavorite': 'FavoriteCancelEnvelope',
        'cGetFavoriteStatus': 'FavoriteStatusEnvelope',
        'cListMyFootprints': 'FootprintPageEnvelope',
        'cRecordFootprint': 'FootprintRecordEnvelope',
        'cDeleteFootprint': 'FootprintDeleteEnvelope',
    }
    for code in responses:
        response = dereference(spec, responses[code])
        cache = response['headers']['Cache-Control']['schema']
        assert cache == {'type': 'string', 'enum': ['no-store']}, f'Favtrack caching changed: {name} {code}'
        schema = response['content']['application/json']['schema']
        expected = {'$ref': '#/components/schemas/FavTrackErrorEnvelope'} if code not in ('200', '201')             else {'$ref': f"#/components/schemas/{success_envelopes[name]}"}
        assert schema == expected, f'Favtrack envelope changed: {name} {code}'
    if '201' in responses:
        # Review-family create split: the first create and the replay share one receipt schema.
        assert responses['201']['content'] == responses['200']['content'], f'Favtrack create/replay receipt diverged: {name}'
    params = {p['name']: p for p in operation.get('parameters', []) if p.get('in') == 'query'}
    if name in ('cListMyFavorites', 'cListMyFootprints'):
        expected_query = {'page', 'pageSize'} if name == 'cListMyFootprints' else {'targetType', 'page', 'pageSize'}
        assert set(params) == expected_query, f'Favtrack list query opened: {name}'
        assert params['page']['schema'] == {'type': 'integer', 'minimum': 1, 'maximum': 10000, 'default': 1}, 'Favtrack page range changed'
        assert params['pageSize']['schema'] == {'type': 'integer', 'minimum': 1, 'maximum': 50, 'default': 20}, 'Favtrack page size changed'
        if name == 'cListMyFavorites':
            assert params['targetType']['schema']['enum'] == ['SERVICE', 'STORE'], 'Favtrack targetType filter opened'
    else:
        assert not params, f'Favtrack non-list query opened: {name}'
    for row in ('FavoriteItem', 'FootprintItem'):
        item = spec['components']['schemas'][row]
        assert item.get('additionalProperties') is False, f'Favtrack row opened: {row}'
        assert item['properties']['targetAvailable']['type'] == 'boolean', f'Favtrack availability flag changed: {row}'
        for nullable in ('title', 'categoryLabel', 'salePrice', 'coverUrl'):
            assert item['properties'][nullable].get('nullable') is True, f'Favtrack unavailable projection must stay nullable: {row}.{nullable}'
    status_data = spec['components']['schemas']['FavoriteStatusData']
    assert status_data['properties']['favoriteId'].get('nullable') is True, 'Favtrack status probe must answer favoriteId=null, never 404'
    add_data = spec['components']['schemas']['FavoriteAddData']
    assert add_data['properties']['favorited']['enum'] == [True], 'Favtrack add receipt must stay favorited=true'
def check_points_task(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = POINTS_TASK_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Points task operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Points task security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Points task status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Points task must stay default off: {name}'
    assert operation.get('x-contract') == '60-Points-Task-Contract-v0.1.md', f'Points task authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.points.task.http.enabled', f'Points task switch changed: {name}'
    admin_side = name.startswith('admin')
    assert operation.get('x-route-party') == ('OPS' if admin_side else 'USER'), f'Points task identity changed: {name}'
    assert operation.get('x-audience') == ('ADMIN_WEB' if admin_side else 'MINIAPP'), f'Points task audience changed: {name}'
    if admin_side:
        expected_actions = ['points.task.config.read' if method == 'get' else 'points.task.config.write']
        assert operation.get('x-required-actions') == expected_actions, f'Points task action gate changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Points task response surface changed: {name}'
    for code in required_codes:
        if code != '200':
            assert dereference(spec, responses[code])['content']['application/json']['schema']                 == {'$ref': '#/components/schemas/PointsTaskErrorEnvelope'}, f'Points task error envelope changed: {name} {code}'
    success = responses['200']
    cache = success['headers']['Cache-Control']['schema']
    assert cache == {'type': 'string', 'enum': ['no-store']}, f'Points task caching changed: {name}'
    envelope_by_name = {
        'cGetSignInStatus': 'SignInStatusEnvelope',
        'cSignIn': 'SignInReceiptEnvelope',
        'cListPointsTasks': 'PointsTasksEnvelope',
        'cClaimPointsReward': 'PointsClaimReceiptEnvelope',
        'cBindReferralCode': 'PointsBindingReceiptEnvelope',
        'adminGetPointsTaskConfig': 'PointsTaskConfigEnvelope',
        'adminUpdatePointsTaskConfig': 'PointsTaskConfigEnvelope',
    }
    assert success['content'] == {'application/json': {'schema': {'$ref':             '#/components/schemas/' + envelope_by_name[name]}}}, f'Points task success envelope changed: {name}'
    # No spend/exchange route can appear in this family: every grant is a positive accrual.
    tasks = spec['components']['schemas']['PointsTasksData']
    assert tasks.get('additionalProperties') is False, 'Points tasks projection opened'
    claim = spec['components']['schemas']['PointsTaskClaimRequest']
    assert claim['properties']['claimType']['enum'] == ['INVITE_PER_HEAD', 'FIRST_ORDER',         'SIGN_IN_STREAK_7', 'INVITE_MILESTONE_3', 'PROFILE_COMPLETE'], 'Points claim types changed'
    config_update = spec['components']['schemas']['PointsTaskConfigUpdate']
    assert set(config_update['required']) == {'signInDailyPoints', 'signInStreakBonusPoints',         'invitePerInviteePoints', 'inviteMonthlyRewardCap', 'inviteMilestoneBonusPoints',         'firstOrderPoints', 'profileCompletePoints'}, 'Points config full-put fields changed'


def check_admin_trade(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = ADMIN_TRADE_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Trade query operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Trade query security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Trade query status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Trade query must stay default off: {name}'
    assert operation.get('x-contract') == '63-Admin-Trade-Query-Contract-v0.1.md', f'Trade query authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.admin.trade.http.enabled', f'Trade query switch changed: {name}'
    assert operation.get('x-route-party') == 'OPS' and operation.get('x-audience') == 'ADMIN_WEB', f'Trade query identity changed: {name}'
    assert operation.get('x-required-actions') == [TRADE_ROUTE_ACTIONS[name]], f'Trade query action gate changed: {name}'
    if name in ADMIN_TRADE_DISPOSAL_ENVELOPES:
        check_admin_trade_disposal(spec, operation, name)
        return
    assert 'requestBody' not in operation, f'Trade query must stay bodyless: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Trade query response surface changed: {name}'
    for code in required_codes:
        if code != '200':
            assert dereference(spec, responses[code])['content']['application/json']['schema']                 == {'$ref': '#/components/schemas/ErrorEnvelope'}, f'Trade query error envelope changed: {name} {code}'
    success = responses['200']
    cache = success['headers']['Cache-Control']['schema']
    assert cache == {'type': 'string', 'enum': ['no-store']}, f'Trade query caching changed: {name}'
    envelope_by_name = {
        'adminListTradeOrders': 'AdminTradeOrderPageEnvelope',
        'adminGetTradeOrder': 'AdminTradeOrderDetailEnvelope',
        'adminListTradeOrderStatusLogs': 'AdminTradeStatusLogPageEnvelope',
        'adminListTradeRefunds': 'AdminTradeRefundPageEnvelope',
        'adminGetTradeRefund': 'AdminTradeRefundDetailEnvelope',
        'adminListTradeVerifications': 'AdminTradeVerificationPageEnvelope',
        'adminGetTradeVerification': 'AdminTradeVerificationDetailEnvelope',
    }
    assert success['content'] == {'application/json': {'schema': {'$ref':             '#/components/schemas/' + envelope_by_name[name]}}}, f'Trade query success envelope changed: {name}'


TRADE_ROUTE_ACTIONS = {
    'adminListTradeOrders': 'order.read',
    'adminGetTradeOrder': 'order.read',
    'adminListTradeOrderStatusLogs': 'order.status-log.read',
    'adminListTradeRefunds': 'refund.read',
    'adminGetTradeRefund': 'refund.read',
    'adminListTradeVerifications': 'order.verification-log.read',
    'adminGetTradeVerification': 'order.verification-log.read',
    'adminChannelQueryTradeRefund': 'refund.channel.query',
    'adminRetryTradeRefund': 'refund.retry',
    'adminAbnormalCloseTradeOrder': 'order.abnormal.close',
    'adminDisableTradeMerchant': 'merchant.disable',
}

GOVERNANCE_ROUTE_ACTIONS = {
    'adminListGovernanceMerchants': 'merchant.read',
    'adminGetGovernanceMerchant': 'merchant.read',
    'adminListGovernanceMerchantStaff': 'merchant.staff.read',
    'adminListGovernanceStaffMembers': 'merchant.staff.read',
    'adminListGovernanceStaffInvitations': 'merchant.staff.read',
    'adminListGovernanceScheduleWindows': 'schedule.read',
    'adminListGovernanceScheduleStaffWindows': 'schedule.read',
    'adminListGovernanceScheduleReservations': 'schedule.read',
}

GOVERNANCE_ENVELOPES = {
    'adminListGovernanceMerchants': 'AdminGovernanceMerchantPageEnvelope',
    'adminGetGovernanceMerchant': 'AdminGovernanceMerchantDetailEnvelope',
    'adminListGovernanceMerchantStaff': 'AdminGovernanceStaffPageEnvelope',
    'adminListGovernanceStaffMembers': 'AdminGovernanceStaffMemberPageEnvelope',
    'adminListGovernanceStaffInvitations': 'AdminGovernanceStaffInvitationPageEnvelope',
    'adminListGovernanceScheduleWindows': 'AdminGovernanceScheduleWindowPageEnvelope',
    'adminListGovernanceScheduleStaffWindows': 'AdminGovernanceScheduleStaffWindowPageEnvelope',
    'adminListGovernanceScheduleReservations': 'AdminGovernanceScheduleReservationPageEnvelope',
}


# Contract 65 (2026-10-16, A-005 ops console second phase): encyclopedia governance (nine
# routes over the community kernel), coupon template management + instance ledger (nine
# routes over the coupon kernel), points ledger and the read-only runtime switch view (two
# routes). One face switch pet.admin.ops.http.enabled; writes bound to a terminal
# X-Request-Id. The only instance-status writes are the v0.2 §4.3 grant channel
# (CCR-CPN-GRANT-001) and the v0.3 §4.4 void channel (user ruling 2026-10-16 doc 36,
# CCR-CPN-VOID-001); batch void/return/risk-freeze stay unimplemented
# (CCR-ADM-A005-OPS-001).
# M-side finish slice family (2026-10-19) + the deepening expansion (2026-10-19 second
# ruling): store profile GET/PUT (27 section 6.4, now with the store-facts/businessStatus/
# avatar fields), derived customer list/detail (69), the OWNER customer-note write (69
# expansion), stats overview/top/trend (70) + the service drill-down page and the schedule
# utilization read (70 expansion; CUSTOM from/to <=92 days rides the same GETs).
MERCHANT_CONSOLE_OPERATIONS = {
    'merchantGetCustomer': ('get', '/merchant/customers/{userId}', ('200', '400', '401', '403', '404', '503')),
    'merchantGetStoreProfile': ('get', '/merchant/store-profile', ('200', '400', '401', '404', '503')),
    'merchantListCustomers': ('get', '/merchant/customers', ('200', '400', '401', '403', '503')),
    'merchantStatsOverview': ('get', '/merchant/stats/overview', ('200', '400', '401', '403', '503')),
    'merchantStatsRevenueTrend': ('get', '/merchant/stats/revenue-trend', ('200', '400', '401', '403', '503')),
    'merchantStatsScheduleUtilization': ('get', '/merchant/stats/schedule-utilization', ('200', '400', '401', '403', '503')),
    'merchantStatsServiceOrders': ('get', '/merchant/stats/services/{serviceId}/orders', ('200', '400', '401', '403', '404', '503')),
    'merchantStatsServiceTop': ('get', '/merchant/stats/services/top', ('200', '400', '401', '403', '503')),
    'merchantUpdateCustomerNote': ('put', '/merchant/customers/{userId}/note', ('200', '201', '400', '401', '403', '404', '409', '503')),
    'merchantUpdateStoreProfile': ('put', '/merchant/store-profile', ('200', '201', '400', '401', '403', '404', '409', '503')),
}
ADMIN_OPS_OPERATIONS = {
    'adminListOpsEncyclopediaCategories': (
        'get', '/admin/encyclopedia/categories',
        ('200', '400', '401', '403', '503')),
    'adminUpdateOpsEncyclopediaCategory': (
        'put', '/admin/encyclopedia/categories/{categoryCode}',
        ('200', '400', '401', '403', '404', '409', '503')),
    'adminEnableOpsEncyclopediaCategory': (
        'post', '/admin/encyclopedia/categories/{categoryCode}/enable',
        ('200', '400', '401', '403', '404', '409', '503')),
    'adminDisableOpsEncyclopediaCategory': (
        'post', '/admin/encyclopedia/categories/{categoryCode}/disable',
        ('200', '400', '401', '403', '404', '409', '503')),
    'adminListOpsEncyclopediaBreeds': (
        'get', '/admin/encyclopedia/breeds',
        ('200', '400', '401', '403', '503')),
    'adminGetOpsEncyclopediaBreed': (
        'get', '/admin/encyclopedia/breeds/{breedId}',
        ('200', '400', '401', '403', '404', '503')),
    'adminUpdateOpsEncyclopediaBreed': (
        'put', '/admin/encyclopedia/breeds/{breedId}',
        ('200', '400', '401', '403', '404', '409', '503')),
    'adminPublishOpsEncyclopediaBreed': (
        'post', '/admin/encyclopedia/breeds/{breedId}/publish',
        ('200', '400', '401', '403', '404', '409', '503')),
    'adminOfflineOpsEncyclopediaBreed': (
        'post', '/admin/encyclopedia/breeds/{breedId}/offline',
        ('200', '400', '401', '403', '404', '409', '503')),
    'adminListCouponTemplates': (
        'get', '/admin/coupon-templates',
        ('200', '400', '401', '403', '503')),
    'adminGetCouponTemplate': (
        'get', '/admin/coupon-templates/{templateId}',
        ('200', '400', '401', '403', '404', '503')),
    'adminTrialCouponTemplate': (
        'get', '/admin/coupon-templates/{templateId}/trial-calculation',
        ('200', '400', '401', '403', '404', '503')),
    'adminCreateCouponTemplate': (
        'post', '/admin/coupon-templates',
        ('200', '400', '401', '403', '409', '503')),
    'adminUpdateCouponTemplate': (
        'put', '/admin/coupon-templates/{templateId}',
        ('200', '400', '401', '403', '404', '409', '503')),
    'adminEnableCouponTemplate': (
        'post', '/admin/coupon-templates/{templateId}/enable',
        ('200', '400', '401', '403', '404', '409', '503')),
    'adminOfflineCouponTemplate': (
        'post', '/admin/coupon-templates/{templateId}/offline',
        ('200', '400', '401', '403', '404', '409', '503')),
    'adminListCouponInstances': (
        'get', '/admin/coupon-instances',
        ('200', '400', '401', '403', '503')),
    'adminGetCouponInstance': (
        'get', '/admin/coupon-instances/{instanceId}',
        ('200', '400', '401', '403', '404', '503')),
    # Contract-65 v0.2 §4.3 grant channel (user ruling 2026-10-16, CCR-CPN-GRANT-001 minimum).
    # The route joins the ops face (x-assembly-switch); the kernel is additionally gated by
    # pet.coupon.grant.enabled (x-kernel-switch, default off → the route answers 503
    # fail-closed, 61号 provider-unassembled semantics).
    'adminGrantCouponInstance': (
        'post', '/admin/coupon-instances/grant',
        ('200', '400', '401', '403', '404', '409', '503')),
    # Contract-65 v0.3 §4.4 void channel (user ruling 2026-10-16 doc 36 minimal
    # authorization, CCR-CPN-VOID-001 minimum): one AVAILABLE instance per terminal
    # requestId; non-AVAILABLE states answer 409. Same face switch; kernel additionally
    # gated by pet.coupon.void.enabled (default off → 503 fail-closed).
    'adminVoidCouponInstance': (
        'post', '/admin/coupon-instances/{instanceId}/void',
        ('200', '400', '401', '403', '404', '409', '503')),
    'adminListPointsLedger': (
        'get', '/admin/points/ledger',
        ('200', '400', '401', '403', '503')),
    'adminGetRuntimeSettings': (
        'get', '/admin/settings/runtime',
        ('200', '400', '401', '403', '503')),
}


OPS_ROUTE_ACTIONS = {
    'adminListOpsEncyclopediaCategories': 'encyclopedia.read',
    'adminUpdateOpsEncyclopediaCategory': 'encyclopedia.manage',
    'adminEnableOpsEncyclopediaCategory': 'encyclopedia.manage',
    'adminDisableOpsEncyclopediaCategory': 'encyclopedia.manage',
    'adminListOpsEncyclopediaBreeds': 'encyclopedia.read',
    'adminGetOpsEncyclopediaBreed': 'breed.read',
    'adminUpdateOpsEncyclopediaBreed': 'breed.manage',
    'adminPublishOpsEncyclopediaBreed': 'encyclopedia.publish',
    'adminOfflineOpsEncyclopediaBreed': 'encyclopedia.publish',
    'adminListCouponTemplates': 'coupon.template.read',
    'adminGetCouponTemplate': 'coupon.template.read',
    'adminTrialCouponTemplate': 'coupon.template.read',
    'adminCreateCouponTemplate': 'coupon.template.manage',
    'adminUpdateCouponTemplate': 'coupon.template.manage',
    'adminEnableCouponTemplate': 'coupon.template.manage',
    'adminOfflineCouponTemplate': 'coupon.template.manage',
    'adminListCouponInstances': 'coupon.instance.read',
    'adminGetCouponInstance': 'coupon.instance.read',
    'adminGrantCouponInstance': 'coupon.instance.grant',
    'adminVoidCouponInstance': 'coupon.instance.void',
    'adminListPointsLedger': 'points.ledger.read',
    'adminGetRuntimeSettings': 'system.parameter.read',
}

OPS_ENVELOPES = {
    'adminListOpsEncyclopediaCategories': 'AdminOpsCategoryPageEnvelope',
    'adminUpdateOpsEncyclopediaCategory': 'AdminOpsCategoryMutationEnvelope',
    'adminEnableOpsEncyclopediaCategory': 'AdminOpsCategoryMutationEnvelope',
    'adminDisableOpsEncyclopediaCategory': 'AdminOpsCategoryMutationEnvelope',
    'adminListOpsEncyclopediaBreeds': 'AdminOpsBreedPageEnvelope',
    'adminGetOpsEncyclopediaBreed': 'AdminOpsBreedDetailEnvelope',
    'adminUpdateOpsEncyclopediaBreed': 'AdminOpsBreedMutationEnvelope',
    'adminPublishOpsEncyclopediaBreed': 'AdminOpsBreedMutationEnvelope',
    'adminOfflineOpsEncyclopediaBreed': 'AdminOpsBreedMutationEnvelope',
    'adminListCouponTemplates': 'AdminOpsTemplatePageEnvelope',
    'adminGetCouponTemplate': 'AdminOpsTemplateDetailEnvelope',
    'adminTrialCouponTemplate': 'AdminOpsTrialCalculationEnvelope',
    'adminCreateCouponTemplate': 'AdminOpsTemplateMutationEnvelope',
    'adminUpdateCouponTemplate': 'AdminOpsTemplateMutationEnvelope',
    'adminEnableCouponTemplate': 'AdminOpsTemplateMutationEnvelope',
    'adminOfflineCouponTemplate': 'AdminOpsTemplateMutationEnvelope',
    'adminListCouponInstances': 'AdminOpsInstancePageEnvelope',
    'adminGetCouponInstance': 'AdminOpsInstanceDetailEnvelope',
    'adminGrantCouponInstance': 'AdminOpsCouponGrantEnvelope',
    'adminVoidCouponInstance': 'AdminOpsCouponVoidEnvelope',
    'adminListPointsLedger': 'AdminOpsPointsLedgerPageEnvelope',
    'adminGetRuntimeSettings': 'AdminOpsRuntimeSettingsEnvelope',
}


def check_admin_ops(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = ADMIN_OPS_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Ops operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Ops security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Ops status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Ops must stay default off: {name}'
    assert operation.get('x-contract') == '65-Admin-Ops-Console-Contract-v0.1.md', f'Ops authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.admin.ops.http.enabled', f'Ops switch changed: {name}'
    assert operation.get('x-route-party') == 'OPS' and operation.get('x-audience') == 'ADMIN_WEB', f'Ops identity changed: {name}'
    assert operation.get('x-required-actions') == [OPS_ROUTE_ACTIONS[name]], f'Ops action gate changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Ops response surface changed: {name}'
    for code in required_codes:
        if code != '200':
            assert dereference(spec, responses[code])['content']['application/json']['schema'] == {'$ref': '#/components/schemas/ErrorEnvelope'}, f'Ops error envelope changed: {name} {code}'
    success = responses['200']
    cache = success['headers']['Cache-Control']['schema']
    assert cache == {'type': 'string', 'enum': ['no-store']}, f'Ops caching changed: {name}'
    assert success['content'] == {'application/json': {'schema': {'$ref': '#/components/schemas/' + OPS_ENVELOPES[name]}}}, f'Ops success envelope changed: {name}'

def check_admin_governance(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = ADMIN_GOVERNANCE_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Governance operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Governance security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Governance status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Governance must stay default off: {name}'
    assert operation.get('x-contract') == '64-Admin-Governance-Contract-v0.1.md', f'Governance authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.admin.governance.http.enabled', f'Governance switch changed: {name}'
    assert operation.get('x-route-party') == 'OPS' and operation.get('x-audience') == 'ADMIN_WEB', f'Governance identity changed: {name}'
    assert operation.get('x-required-actions') == [GOVERNANCE_ROUTE_ACTIONS[name]], f'Governance action gate changed: {name}'
    assert 'requestBody' not in operation, f'Governance must stay bodyless: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Governance response surface changed: {name}'
    for code in required_codes:
        if code != '200':
            assert dereference(spec, responses[code])['content']['application/json']['schema']                 == {'$ref': '#/components/schemas/ErrorEnvelope'}, f'Governance error envelope changed: {name} {code}'
    success = responses['200']
    cache = success['headers']['Cache-Control']['schema']
    assert cache == {'type': 'string', 'enum': ['no-store']}, f'Governance caching changed: {name}'
    assert success['content'] == {'application/json': {'schema': {'$ref':             '#/components/schemas/' + GOVERNANCE_ENVELOPES[name]}}}, f'Governance success envelope changed: {name}'


# Contract 63 v0.2 disposal writes: X-Request-Id bound; bodyless where the draft defines none.
ADMIN_TRADE_DISPOSAL_ENVELOPES = {
    'adminChannelQueryTradeRefund': ('AdminRefundDispositionEnvelope', None),
    'adminRetryTradeRefund': ('AdminRefundDispositionEnvelope', None),
    'adminAbnormalCloseTradeOrder': ('AdminAbnormalCloseEnvelope', 'AdminAbnormalCloseRequest'),
    'adminDisableTradeMerchant': ('AdminMerchantDisableEnvelope', 'AdminMerchantDisableRequest'),
}


def check_admin_trade_disposal(spec, operation, name):
    envelope, request = ADMIN_TRADE_DISPOSAL_ENVELOPES[name]
    parameters = operation.get('parameters', [])
    assert {'$ref': '#/components/parameters/RequestId'} in parameters, f'Disposal request ID missing: {name}'
    if request is None:
        assert 'requestBody' not in operation, f'Disposal route must stay bodyless: {name}'
    else:
        body = dereference(spec, operation['requestBody'])
        assert body.get('required') is True, f'Disposal body must be required: {name}'
        assert body['content']['application/json']['schema'] == {
            '$ref': '#/components/schemas/' + request}, f'Disposal body schema changed: {name}'
    responses = operation['responses']
    for code, expected in responses.items():
        if code == '200':
            success = responses[code]
            cache = success['headers']['Cache-Control']['schema']
            assert cache == {'type': 'string', 'enum': ['no-store']}, f'Disposal caching changed: {name}'
            assert success['content'] == {'application/json': {'schema': {
                '$ref': '#/components/schemas/' + envelope}}}, f'Disposal envelope changed: {name}'
        else:
            assert dereference(spec, responses[code])['content']['application/json']['schema']                 == {'$ref': '#/components/schemas/ErrorEnvelope'}, f'Disposal error envelope changed: {name} {code}'


def check_staff_invitation_list(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = STAFF_INVITATION_LIST_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Staff invitation list operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Staff invitation list security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Staff invitation list status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Staff invitation list must stay default off: {name}'
    assert operation.get('x-contract') == '54-Merchant-Staff-Binding-Contract-v0.1.md', f'Staff invitation list authority changed: {name}'
    assert operation.get('x-route-party') == 'USER' and operation.get('x-audience') == 'MINIAPP', f'Staff invitation list identity changed: {name}'
    assert 'requestBody' not in operation, f'Staff invitation list must stay bodyless: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Staff invitation list response surface changed: {name}'
    for code in responses:
        response = dereference(spec, responses[code])
        cache = response['headers']['Cache-Control']['schema']
        assert cache == {'type': 'string', 'enum': ['no-store']}, f'Staff invitation list caching changed: {name} {code}'
        schema = response['content']['application/json']['schema']
        expected = {'$ref': '#/components/schemas/StaffInvitationErrorEnvelope'} if code != '200'             else {'$ref': '#/components/schemas/StaffInvitationPageEnvelope'}
        assert schema == expected, f'Staff invitation list envelope changed: {name} {code}'
    params = {p['name']: p for p in operation.get('parameters', []) if p.get('in') == 'query'}
    assert set(params) == {'page', 'pageSize'}, f'Staff invitation list query opened: {name}'
    assert params['page']['schema'] == {'type': 'integer', 'minimum': 1, 'maximum': 10000, 'default': 1}, 'Staff invitation page range changed'
    assert params['pageSize']['schema'] == {'type': 'integer', 'minimum': 1, 'maximum': 50, 'default': 20}, 'Staff invitation page size changed'
    summary = spec['components']['schemas']['StaffInvitationSummary']
    assert summary.get('additionalProperties') is False, 'Staff invitation summary opened'
    assert set(summary['required']) == {'invitationId', 'merchantId', 'merchantName', 'storeId',
                                        'storeName', 'memberName', 'grantedActions', 'status',
                                        'invitedAt', 'updatedAt'}, 'Staff invitation summary fields changed'
    assert set(summary['properties']) == set(summary['required']), 'Staff invitation summary fields changed'
    assert not any('phone' in key for key in summary['properties']), 'Staff invitation summary must never carry a phone field'
    assert summary['properties']['status']['enum'] == ['INVITED', 'CANCELED', 'CONFIRMED'], 'Staff invitation status machine changed'
    assert summary['properties']['grantedActions']['items']['enum'] == ['merchant.order.verify'], 'Staff invitation action catalog changed'
    for field in ('invitedAt', 'updatedAt'):
        assert summary['properties'][field] == {'type': 'string', 'format': 'date-time', 'x-precision': 'milliseconds'}, f'Staff invitation time precision changed: {field}'
    page = spec['components']['schemas']['StaffInvitationPageData']
    assert page.get('additionalProperties') is False, 'Staff invitation page data opened'
    assert set(page['required']) == {'items', 'page', 'pageSize', 'total'}, 'Staff invitation page fields changed'
    assert page['properties']['pageSize'] == {'type': 'integer', 'minimum': 1, 'maximum': 50}, 'Staff invitation page size schema changed'
    envelope = spec['components']['schemas']['StaffInvitationErrorEnvelope']
    assert envelope['properties']['code']['enum'] == ['COMMON_INVALID_ARGUMENT', 'COMMON_UNAUTHORIZED',
                                                      'COMMON_INTERNAL_ERROR', 'COMMON_DEPENDENCY_UNAVAILABLE'], 'Staff invitation error codes changed'


def check_verification_http(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = VERIFICATION_HTTP_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Verification HTTP operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Verification HTTP security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Verification HTTP status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Verification HTTP must stay default off: {name}'
    assert operation.get('x-contract') in ('47-Verification-Credential-Contract-v0.1.md', '48-Verification-Completion-Contract-v0.1.md'), f'Verification HTTP authority changed: {name}'
    if name == 'verifyPlatformOrder':
        assert operation.get('x-route-party') == 'MERCHANT' and operation.get('x-audience') == 'MINIAPP', f'Verification HTTP identity changed: {name}'
    else:
        assert operation.get('x-contract') == '47-Verification-Credential-Contract-v0.1.md', f'Verification HTTP authority changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Verification HTTP response surface changed: {name}'
    success = dereference(spec, responses['200'])
    cache = dereference(spec, success['headers']['Cache-Control'])['schema']
    assert cache == {'type': 'string', 'enum': ['no-store']}, f'Verification HTTP caching changed: {name}'
    expected_data = 'OrderVerificationReceipt' if name == 'verifyPlatformOrder' \
        else 'VerificationCredentialView' if method == 'get' else 'VerificationCredentialReceipt'
    assert success['content'] == {'application/json': {'schema': {
        'allOf': [{'$ref': '#/components/schemas/BaseEnvelope'},
                  {'type': 'object', 'required': ['data'],
                   'properties': {'data': {'$ref': '#/components/schemas/' + expected_data}}}]}}}, \
        f'Verification HTTP success envelope changed: {name}'
    for code in ('400', '401', '403', '404', '409', '503'):
        if code in responses:
            assert dereference(spec, responses[code])['content']['application/json']['schema'] \
                == {'$ref': '#/components/schemas/ErrorEnvelope'}, f'Verification HTTP error envelope changed: {name} {code}'
    receipt = spec['components']['schemas']['OrderVerificationReceipt']
    assert receipt.get('additionalProperties') is False, 'Verification receipt opened'
    assert set(receipt['required']) == {'orderId', 'attemptId', 'resultCode', 'verificationId', 'verifiedAt', 'orderVersion'}, 'Verification receipt fields changed'
    for field in ('verificationId', 'verifiedAt', 'orderVersion'):
        assert receipt['properties'][field].get('nullable') is True, f'Verification failed-receipt tail must stay nullable: {field}'
    assert set(receipt['properties']['resultCode']['enum']) == {
        'VERIFIED', 'VERIFICATION_CODE_INVALID', 'VERIFICATION_CODE_EXPIRED', 'VERIFICATION_RISK_LOCKED'}, \
        'Verification receipt result codes changed'
    if name == 'verifyPlatformOrder':
        body = dereference(spec, operation['requestBody'])
        request = dereference(spec, body['content']['application/json']['schema'])
        assert request.get('additionalProperties') is False and set(request['required']) == {'verificationCode'}, \
            'Verification scan body opened beyond the scanned code'
        assert set(request['properties']) == {'verificationCode'} and request['properties']['verificationCode'].get('pattern') == r'^[0-9A-Z]{1,128}$', \
            'Verification scan body opened beyond the scanned code'


def check_merchant_order_http(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = MERCHANT_ORDER_HTTP_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Merchant order HTTP operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Merchant order HTTP security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Merchant order HTTP status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Merchant order HTTP must stay default off: {name}'
    assert operation.get('x-contract') == '45-Merchant-Order-Actions-Contract-v0.1.md', f'Merchant order HTTP authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.order.merchant.http.enabled', f'Merchant order HTTP switch changed: {name}'
    assert operation.get('x-route-party') == 'MERCHANT' and operation.get('x-audience') == 'MINIAPP', f'Merchant order HTTP identity changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Merchant order HTTP response surface changed: {name}'
    success = dereference(spec, responses['200'])
    cache = dereference(spec, success['headers']['Cache-Control'])['schema']
    assert cache == {'type': 'string', 'enum': ['no-store']}, f'Merchant order HTTP caching changed: {name}'
    assert success['content'] == {'application/json': {'schema': {'$ref': '#/components/schemas/MerchantOrderResponse'}}}, \
        f'Merchant order HTTP success envelope changed: {name}'
    for code in ('400', '401', '403', '409', '503'):
        assert dereference(spec, responses[code])['content']['application/json']['schema'] \
            == {'$ref': '#/components/schemas/ErrorEnvelope'}, f'Merchant order HTTP error envelope changed: {name} {code}'
    body = dereference(spec, operation['requestBody'])
    request = body['content']['application/json']['schema']
    expected_request = 'MerchantConfirmOrderRequest' if name == 'merchantConfirmOrder' else 'MerchantRejectOrderRequest'
    assert request == {'$ref': '#/components/schemas/' + expected_request}, f'Merchant order HTTP request body opened: {name}'
    receipt = spec['components']['schemas']['MerchantOrderReceipt']
    assert receipt.get('additionalProperties') is False, 'Merchant order receipt opened'
    assert set(receipt['required']) == {'orderId', 'decisionId', 'confirmRound', 'action', 'orderStageAtCommit', 'decidedAt', 'refundOrderId'}, \
        'Merchant order receipt fields changed'
    assert receipt['properties']['confirmRound']['enum'] == [0, 1], 'Merchant order round enum changed'
    assert receipt['properties']['action']['enum'] == ['CONFIRM', 'REJECT'], 'Merchant order action enum changed'
    assert receipt['properties']['orderStageAtCommit']['enum'] == ['PENDING_SERVICE', 'CANCELED'], 'Merchant order stage enum changed'
    assert receipt['properties']['refundOrderId'].get('nullable') is True, 'Confirm receipts must keep refundOrderId null'
    reject = spec['components']['schemas']['MerchantRejectOrderRequest']
    assert reject.get('additionalProperties') is False and set(reject['required']) == {'expectedConfirmRound', 'reasonCode', 'reasonText'}, \
        'Merchant reject request fields changed'
    assert set(reject['properties']['reasonCode']['enum']) == {
        'SCHEDULE_CONFLICT', 'STAFF_UNAVAILABLE', 'PET_NOT_MATCHED', 'TEMPORARY_CLOSURE', 'OTHER'}, \
        'Merchant reject reason codes changed'
    assert reject['properties']['reasonText']['minLength'] == 5 and reject['properties']['reasonText']['maxLength'] == 200, \
        'Merchant reject reason length bounds changed'
    confirm = spec['components']['schemas']['MerchantConfirmOrderRequest']
    assert confirm.get('additionalProperties') is False and set(confirm['required']) == {'expectedConfirmRound'}, \
        'Merchant confirm request fields changed'
    assert 'internalNote' in confirm['properties'] and confirm['properties']['internalNote']['maxLength'] == 200, \
        'Merchant confirm internal note bounds changed'


def check_merchant_order_list(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = MERCHANT_ORDER_LIST_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Merchant order list operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Merchant order list security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Merchant order list status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Merchant order list must stay default off: {name}'
    assert operation.get('x-contract') == '10-HTTP-API-Contract-v0.4.md', f'Merchant order list authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.order.merchant.http.enabled', f'Merchant order list switch changed: {name}'
    assert operation.get('x-route-party') == 'MERCHANT' and operation.get('x-audience') == 'MINIAPP', f'Merchant order list identity changed: {name}'
    assert 'requestBody' not in operation, f'Merchant order list must stay bodyless: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Merchant order list response surface changed: {name}'
    success = dereference(spec, responses['200'])
    cache = dereference(spec, success['headers']['Cache-Control'])['schema']
    assert cache == {'type': 'string', 'enum': ['no-store']}, f'Merchant order list caching changed: {name}'
    assert success['content'] == {'application/json': {'schema': {'$ref': '#/components/schemas/MerchantOrderPageEnvelope'}}},         f'Merchant order list success envelope changed: {name}'
    for code in ('400', '401', '403', '503'):
        assert dereference(spec, responses[code])['content']['application/json']['schema']             == {'$ref': '#/components/schemas/ErrorEnvelope'}, f'Merchant order list error envelope changed: {name} {code}'
    resolved = [dereference(spec, parameter) for parameter in operation['parameters']]
    names = [(parameter['in'], parameter['name'], parameter.get('required', False)) for parameter in resolved]
    assert names == [
        ('query', 'merchantId', True), ('query', 'storeId', True),
        ('query', 'displayStatus', False), ('query', 'page', False), ('query', 'pageSize', False)],         f'Merchant order list query surface changed: {name}'
    merchant = resolved[0]['schema']
    assert merchant == {'$ref': '#/components/schemas/PublicId'}, f'Merchant coordinate id shape changed: {name}'
    assert resolved[1]['schema'] == {'$ref': '#/components/schemas/PublicId'}, f'Store coordinate id shape changed: {name}'
    display = dereference(spec, resolved[2]['schema'])
    assert display['enum'] == [
        'PENDING_PAYMENT', 'PENDING_CONFIRM', 'PENDING_SERVICE', 'COMPLETED', 'CANCELED',
        'REFUND_PENDING_CONFIRM', 'REFUNDING', 'REFUNDED', 'PARTIAL_REFUND', 'AFTERSALE'],         'Merchant order list display vocabulary diverged from §3.7'
    page = resolved[3]['schema']
    assert page == {'type': 'integer', 'minimum': 1, 'default': 1}, f'Merchant order list page bounds changed: {name}'
    page_size = resolved[4]['schema']
    assert page_size == {'type': 'integer', 'minimum': 1, 'maximum': 100, 'default': 20},         f'Merchant order list pageSize bounds changed: {name}'
    envelope = dereference(spec, spec['components']['schemas']['MerchantOrderPageEnvelope'])
    data = dereference(spec, envelope['properties']['data'])
    assert envelope.get('additionalProperties') is False and data.get('additionalProperties') is False,         'Merchant order page opened'
    assert set(data['required']) == {'items', 'page', 'pageSize', 'total'}, 'Merchant order page fields changed'
    summary = dereference(spec, data['properties']['items']['items'])
    assert summary.get('additionalProperties') is False, 'Merchant order summary opened'
    assert set(summary['required']) == {
        'orderId', 'orderNo', 'displayStatus', 'payAmount', 'appointmentStart', 'appointmentEnd', 'paidAt'},         'Merchant order summary fields changed'
    for field in ('appointmentStart', 'appointmentEnd', 'paidAt'):
        assert summary['properties'][field].get('nullable') is True, f'Merchant summary tail must stay nullable: {field}'
    assert summary['properties']['payAmount']['pattern'] == r'^(0|[1-9][0-9]{0,15})\.[0-9]{2}$',         'Merchant summary amount format changed'


def check_refund_http(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = REFUND_HTTP_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Refund HTTP operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Refund HTTP security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Refund HTTP status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Refund HTTP must stay default off: {name}'
    assert operation.get('x-contract') == '49-Refund-Application-Contract-v0.1.md', f'Refund HTTP authority changed: {name}'
    assert operation.get('x-assembly-switch') == 'pet.refund.application.http.enabled', f'Refund HTTP switch changed: {name}'
    assert operation.get('x-route-party') == 'USER' and operation.get('x-audience') == 'MINIAPP', f'Refund HTTP identity changed: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Refund HTTP response surface changed: {name}'
    assert responses['201']['content'] == responses['200']['content'] == {
        'application/json': {'schema': {'$ref': '#/components/schemas/RefundApplicationResponseEnvelope'}}}, \
        f'Refund HTTP create/replay schema changed: {name}'
    for code in ('400', '401', '403', '409', '503'):
        assert dereference(spec, responses[code])['content']['application/json']['schema'] \
            == {'$ref': '#/components/schemas/ErrorEnvelope'}, f'Refund HTTP error envelope changed: {name} {code}'
    body = dereference(spec, operation['requestBody'])
    request = dereference(spec, body['content']['application/json']['schema'])
    assert request.get('additionalProperties') is False and request['required'] == ['reasonCode'], \
        'Refund apply body opened beyond code + optional text'
    assert request['properties']['reasonCode'] == {'type': 'string', 'minLength': 1, 'maxLength': 64}, \
        'Refund reason code bounds changed'
    assert request['properties']['reasonText'] == {'type': 'string', 'maxLength': 500}, \
        'Refund reason text bounds changed'
    data = spec['components']['schemas']['RefundApplicationData']
    assert data['properties']['applicationStatus']['enum'] == [
        'PENDING_MERCHANT', 'APPROVED', 'AUTO_APPROVED', 'REJECTED'], 'Refund status enum changed'
    assert data['properties']['route']['enum'] == [
        'AUTO_FULL_BEFORE_SERVICE', 'MERCHANT_CONFIRM_AFTER_SERVICE', 'AFTERSALE_DECISION'], 'Refund route enum changed'
    assert data['properties']['merchantDeadline'].get('nullable') is True, 'Refund deadline must stay nullable'
    assert data['properties']['refundOrderId'].get('nullable') is True, 'Refund receipt order id must stay nullable (durable task creates it)'


def check_coupon_points_read(spec, operation, method, path):
    name = operation['operationId']
    expected_method, expected_path, required_codes = COUPON_POINTS_READ_OPERATIONS[name]
    assert (method, path) == (expected_method, expected_path), f'Coupon/points read operation moved: {name}'
    assert operation.get('security') == [{'bearerAuth': []}], f'Coupon/points read security changed: {name}'
    assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', f'Coupon/points read status changed: {name}'
    assert operation.get('x-default-enabled') is False, f'Coupon/points read must stay default off: {name}'
    assert operation.get('x-contract') == '10-HTTP-API-Contract-v0.4.md', f'Coupon/points read authority changed: {name}'
    assert operation.get('x-route-party') == 'USER' and operation.get('x-audience') == 'MINIAPP', f'Coupon/points read identity changed: {name}'
    assert 'requestBody' not in operation, f'Coupon/points read must stay bodyless: {name}'
    responses = operation['responses']
    assert set(responses) == set(required_codes), f'Coupon/points read response surface changed: {name}'
    success_envelopes = {
        'cListMyCoupons': 'CouponInstancePageEnvelope',
        'cGetMyCoupon': 'CouponInstanceEnvelope',
        'cGetPointsBalance': 'PointsBalanceEnvelope',
        'cListPointsLedger': 'PointsLedgerPageEnvelope',
    }
    for code in responses:
        response = dereference(spec, responses[code])
        schema = response['content']['application/json']['schema']
        expected = {'$ref': '#/components/schemas/CouponPointsErrorEnvelope'} if code != '200' \
            else {'$ref': '#/components/schemas/' + success_envelopes[name]}
        assert schema == expected, f'Coupon/points read envelope changed: {name} {code}'
    params = {p.get('name'): p for p in operation.get('parameters', []) if p.get('in') == 'query'}
    allowed = {'status'} if name == 'cListMyCoupons' else set()
    if name in {'cListMyCoupons', 'cListPointsLedger'}:
        allowed = allowed | {'page', 'pageSize'}
        assert params['page']['schema'] == {'type': 'integer', 'minimum': 1, 'maximum': 10000, 'default': 1}, f'Coupon/points page range changed: {name}'
        assert params['pageSize']['schema'] == {'type': 'integer', 'minimum': 1, 'maximum': 50, 'default': 20}, f'Coupon/points page size changed: {name}'
    else:
        assert not params, f'Coupon/points read query opened: {name}'
    assert set(params) == allowed, f'Coupon/points read filters changed: {name}'
    if name == 'cListMyCoupons':
        assert params['status']['schema'].get('enum') == ['AVAILABLE', 'USED', 'EXPIRED'], 'Coupon buckets opened beyond the three display tabs'
        assert params['status']['schema'].get('default') == 'AVAILABLE', 'Coupon default bucket changed'
    if name == 'cGetMyCoupon':
        resolved = [dereference(spec, p) for p in operation.get('parameters', [])]
        assert any(p.get('name') == 'couponId' and p.get('in') == 'path' for p in resolved), 'CouponId path parameter missing'


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
        assert operation.get('x-public-refund-enabled') is True and operation.get('x-public-refund-switch') == 'pet.aftersale.refund.enabled', 'AFS ruling-32 refund switch changed'
        assert operation.get('x-executable-decision-types') == ['REJECT', 'RESERVICE', 'OTHER', 'FULL_REFUND', 'PARTIAL_REFUND'], 'AFS executable decision types changed'
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
                assert operation.get('x-implementation-status') == 'IMPLEMENTED_DEFAULT_OFF', 'Credential route status regressed'
                assert operation.get('x-default-enabled') is False, 'Credentials must remain default off'
                assert operation.get('security') == [{'bearerAuth': []}], 'Credential current session required'
                issue = spec['components']['schemas']['VerificationCredentialIssue']
                assert issue.get('additionalProperties') is False
                assert set(issue['required']) == {'expectedCredentialVersion', 'refreshKind'}
                assert issue['properties']['expectedCredentialVersion']['type'] == 'string'
                assert issue['properties']['refreshKind']['enum'] == ['INITIAL', 'AUTO', 'MANUAL']
            if operation_id in VERIFICATION_HTTP_OPERATIONS:
                check_verification_http(spec, operation, method, path)
            if operation_id in MERCHANT_ORDER_HTTP_OPERATIONS:
                check_merchant_order_http(spec, operation, method, path)
            if operation_id in MERCHANT_ORDER_LIST_OPERATIONS:
                check_merchant_order_list(spec, operation, method, path)
            if operation_id in REFUND_HTTP_OPERATIONS:
                check_refund_http(spec, operation, method, path)
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
            if operation_id in COUPON_POINTS_READ_OPERATIONS:
                check_coupon_points_read(spec, operation, method, path)
            if operation_id in NOTIFICATION_PREFERENCE_OPERATIONS:
                check_notification_preferences(spec, operation, method, path)
            if operation_id in ADDRESS_OPERATIONS:
                check_address_operations(spec, operation, method, path)
            if operation_id in USER_SETTINGS_OPERATIONS:
                check_user_settings(spec, operation, method, path)
            if operation_id in FEEDBACK_HTTP_OPERATIONS:
                check_feedback_http(spec, operation, method, path)
            if operation_id in STAFF_INVITATION_LIST_OPERATIONS:
                check_staff_invitation_list(spec, operation, method, path)
            if operation_id in ORDER_WRITE_OPERATIONS:
                check_order_write(spec, operation, method, path)
            if operation_id in RESCHEDULE_HTTP_OPERATIONS:
                check_reschedule_http(spec, operation, method, path)
            if operation_id in REVIEW_HTTP_OPERATIONS:
                check_review_http(spec, operation, method, path)

            if operation_id in REVIEW_APPEAL_HTTP_OPERATIONS:
                check_review_appeal_http(spec, operation, method, path)

            if operation_id in MERCHANT_REFUND_HTTP_OPERATIONS:
                check_merchant_refund_http(spec, operation, method, path)

            if operation_id in FAVTRACK_HTTP_OPERATIONS:
                check_favtrack_http(spec, operation, method, path)
            if operation_id in POINTS_TASK_OPERATIONS:
                check_points_task(spec, operation, method, path)
            if operation_id in BAIKE_OPERATIONS:
                check_baike_operations(spec, operation, method, path)
            if operation_id in ADMIN_TRADE_OPERATIONS:
                check_admin_trade(spec, operation, method, path)
            if operation_id in ADMIN_GOVERNANCE_OPERATIONS:
                check_admin_governance(spec, operation, method, path)
            if operation_id in ADMIN_OPS_OPERATIONS:
                check_admin_ops(spec, operation, method, path)
            if operation_id in CATALOG_SEARCH_OPERATIONS:
                check_catalog_search(spec, operation, method, path)
            if operation_id in CITY_OPERATIONS:
                check_city_directory(spec, operation, method, path)
            if operation_id in PET_HEALTH_RECORD_OPERATIONS:
                check_pet_health_records(spec, operation, method, path)

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
    assert legacy_writes == 10, 'Legacy write surface changed'
    assert legacy_creates == LEGACY_CREATES, 'Legacy create surface changed'

    assert operations == LEGACY_OPERATIONS.keys() | AUTH_OPERATIONS.keys() | MERCHANT_OPERATIONS.keys() | APPLICATION_OPERATIONS.keys() | PRIVATE_ASSET_OPERATIONS.keys() | SERVICE_CATALOG_OPERATIONS.keys() | STORE_CATALOG_OPERATIONS.keys() | SERVICE_WRITE_OPERATIONS.keys() | SCHEDULE_AVAILABILITY_OPERATIONS.keys() | SCHEDULE_WRITE_OPERATIONS.keys() | CREDENTIAL_OPERATIONS.keys() | AFTERSALE_OPERATIONS.keys() | COUPON_POINTS_READ_OPERATIONS.keys() | VERIFICATION_HTTP_OPERATIONS.keys() | NOTIFICATION_PREFERENCE_OPERATIONS.keys() | STAFF_INVITATION_LIST_OPERATIONS.keys() | ORDER_WRITE_OPERATIONS.keys() | REFUND_HTTP_OPERATIONS.keys() | MERCHANT_ORDER_LIST_OPERATIONS.keys() | RESCHEDULE_HTTP_OPERATIONS.keys() | REVIEW_HTTP_OPERATIONS.keys() | REVIEW_APPEAL_HTTP_OPERATIONS.keys() | MERCHANT_REFUND_HTTP_OPERATIONS.keys() | FAVTRACK_HTTP_OPERATIONS.keys() | ADDRESS_OPERATIONS.keys() | USER_SETTINGS_OPERATIONS.keys() | FEEDBACK_HTTP_OPERATIONS.keys() | POINTS_TASK_OPERATIONS.keys() | BAIKE_OPERATIONS.keys() | ADMIN_TRADE_OPERATIONS.keys() | ADMIN_GOVERNANCE_OPERATIONS.keys() | ADMIN_OPS_OPERATIONS.keys() | CATALOG_SEARCH_OPERATIONS.keys() | CITY_OPERATIONS.keys() | PET_HEALTH_RECORD_OPERATIONS.keys() | MERCHANT_CONSOLE_OPERATIONS.keys(), 'Unexpected or missing reviewed operations'

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
            'couponPointsReadOperations': len(operations & COUPON_POINTS_READ_OPERATIONS.keys()),
            'verificationHttpOperations': len(operations & VERIFICATION_HTTP_OPERATIONS.keys()),
            'merchantOrderHttpOperations': len(operations & MERCHANT_ORDER_HTTP_OPERATIONS.keys()),
            'merchantOrderListOperations': len(operations & MERCHANT_ORDER_LIST_OPERATIONS.keys()),
            'rescheduleHttpOperations': len(operations & RESCHEDULE_HTTP_OPERATIONS.keys()),
            'reviewHttpOperations': len(operations & REVIEW_HTTP_OPERATIONS.keys()),

            'reviewAppealHttpOperations': len(operations & REVIEW_APPEAL_HTTP_OPERATIONS.keys()),
            'merchantRefundHttpOperations': len(operations & MERCHANT_REFUND_HTTP_OPERATIONS.keys()),
            'favtrackHttpOperations': len(operations & FAVTRACK_HTTP_OPERATIONS.keys()),
            'pointsTaskOperations': len(operations & POINTS_TASK_OPERATIONS.keys()),
            'baikeOperations': len(operations & BAIKE_OPERATIONS.keys()),
            'refundHttpOperations': len(operations & REFUND_HTTP_OPERATIONS.keys()),
            'serviceWriteOperations': len(operations & SERVICE_WRITE_OPERATIONS.keys()),
            'storeCatalogOperations': len(operations & STORE_CATALOG_OPERATIONS.keys()),
            'staffInvitationListOperations': len(operations & STAFF_INVITATION_LIST_OPERATIONS.keys()),
            'orderWriteOperations': len(operations & ORDER_WRITE_OPERATIONS.keys()),
            'addressOperations': len(operations & ADDRESS_OPERATIONS.keys()),
            'userSettingsOperations': len(operations & USER_SETTINGS_OPERATIONS.keys()),
            'feedbackHttpOperations': len(operations & FEEDBACK_HTTP_OPERATIONS.keys()),
            'adminTradeOperations': len(operations & ADMIN_TRADE_OPERATIONS.keys()),
            'adminGovernanceOperations': len(operations & ADMIN_GOVERNANCE_OPERATIONS.keys()),
            'adminOpsOperations': len(operations & ADMIN_OPS_OPERATIONS.keys()),
            'merchantConsoleOperations': len(operations & MERCHANT_CONSOLE_OPERATIONS.keys()),
            'catalogSearchOperations': len(operations & CATALOG_SEARCH_OPERATIONS.keys()),
            'cityOperations': len(operations & CITY_OPERATIONS.keys()),
            'merchantConsoleOperations': len(operations & MERCHANT_CONSOLE_OPERATIONS.keys()),
            'petHealthRecordOperations': len(operations & PET_HEALTH_RECORD_OPERATIONS.keys()),
            'resolvedRefs': len(refs), 'stringIdProperties': ids}


if __name__ == '__main__':
    result = check(yaml.safe_load(SPEC.read_text(encoding='utf-8')))
    print(json.dumps({'status': 'PASS_OFFLINE_DOCUMENT_SMOKE', **result}, indent=2))
    print('NOT_EXECUTED: live HTTP, DTO serialization, semantic breaking-change approval, business E2E.')
    print('BLOCKED real session/RBAC integration: existing CCR-ACR-001 and CCR-PERM-001. No SDK generated.')
