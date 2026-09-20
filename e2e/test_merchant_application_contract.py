"""Offline MER-001 application/review contract checks; no live business implementation."""
import copy
import hashlib
import unittest

import yaml

from contract_smoke import SPEC
from test_auth_contract import InstanceMismatch, validate_instance


class MerchantApplicationContract(unittest.TestCase):
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

    @staticmethod
    def draft():
        return {
            'merchantName': '示例宠物医院', 'contactName': '张三',
            'contactPhone': '13800138000', 'email': 'owner@example.test',
            'merchantTypeCode': 'PET_HOSPITAL', 'cityCode': '310100',
            'address': '示例路1号', 'longitude': '121.4737000', 'latitude': '31.2304000',
            'introduction': '示例申请', 'storePhotoAssetIds': ['7001'],
            'businessLicenseAssetId': '7002', 'idCardFrontAssetId': '7003',
            'idCardBackAssetId': '7004', 'industryLicenseAssetId': '7005',
        }

    @staticmethod
    def manual_evidence(kind='DATED'):
        value = {
            'materialId': '7002',
            'materialSha256': hashlib.sha256(b'private fixture material').hexdigest(),
            'credentialType': 'CREDIT_CODE', 'subjectName': '示例宠物医院',
            'identifier': '91310000EXAMPLE001', 'validFrom': '2024-01-01',
            'validityKind': kind,
        }
        if kind == 'DATED':
            value['validTo'] = '2034-01-01'
        return value

    def test_draft_is_incomplete_full_replacement_but_rejects_unknown_or_unsafe_id_shapes(self):
        self.valid('AppDraftInput', {})
        self.valid('AppDraftInput', {'merchantName': None, 'storePhotoAssetIds': []})
        self.valid('AppDraftInput', self.draft())
        self.invalid('AppDraftInput', {**self.draft(), 'operatorId': '9001'})
        self.invalid('AppDraftInput', {**self.draft(), 'storePhotoAssetIds': ['7001'] * 2})
        self.invalid('AppDraftInput', {**self.draft(), 'storePhotoAssetIds': [str(i) for i in range(1, 8)]})
        self.invalid('AppDraftInput', {**self.draft(), 'businessLicenseAssetId': 7002})

    def test_save_and_submit_have_distinct_strict_shapes_and_no_client_identity_facts(self):
        save = {'expectedVersion': '0', 'draft': {}}
        submit = {'expectedVersion': '1', 'revisionId': '8001'}
        self.valid('AppSaveRequest', save)
        self.valid('AppSubmitRequest', submit)
        for field in ('userId', 'ownerUserId', 'operatorId', 'status', 'subjectVerificationStatus'):
            self.invalid('AppSaveRequest', {**save, field: '1'})
            self.invalid('AppSubmitRequest', {**submit, field: '1'})
        self.invalid('AppSubmitRequest', {**submit, 'draft': self.draft()})
        self.invalid('AppSubmitRequest', {**submit, 'expectedVersion': 1})
        self.invalid('AppSubmitRequest', {**submit, 'revisionId': '0'})

    def test_manual_verification_has_explicit_validity_and_cannot_accept_asserted_status_or_actor(self):
        dated = self.manual_evidence('DATED')
        permanent = self.manual_evidence('LONG_TERM')
        self.valid('AppManualEvidenceInput', dated)
        self.valid('AppManualEvidenceInput', permanent)
        self.invalid('AppManualEvidenceInput', {k: v for k, v in dated.items() if k != 'validTo'})
        self.invalid('AppManualEvidenceInput', {**permanent, 'validTo': '2034-01-01'})
        self.invalid('AppManualEvidenceInput', {**dated, 'validityKind': 'UNKNOWN'})
        self.invalid('AppManualEvidenceInput', {**dated, 'validTo': '2026-02-30'})
        for field in ('evidenceStatus', 'verifiedByOperatorId', 'lookupDigest', 'lookupKeyVersion'):
            self.invalid('AppManualEvidenceInput', {**dated, field: 'forged'})

        request = {'submissionRevisionId': '8001', 'expectedVersion': '2',
                   'expectedTaskVersion': '3', 'evidenceItems': [dated],
                   'reason': 'OCR识别失败，人工逐项核对原件', 'confirmed': True}
        self.valid('AppManualVerifyRequest', request)
        for mutation in ({'confirmed': False}, {'confirmed': 1}, {'expectedTaskVersion': 3},
                         {'reason': 'too short'}, {'operatorId': '9001'},
                         {'evidenceItems': []}):
            self.invalid('AppManualVerifyRequest', {**request, **mutation})

    def test_decision_branches_require_confirmation_and_applicant_opinion_only_when_needed(self):
        base = {'submissionRevisionId': '8001', 'expectedVersion': '2',
                'expectedTaskVersion': '3', 'confirmed': True}
        self.valid('AppDecisionRequest', {**base, 'decisionType': 'APPROVE'})
        self.valid('AppDecisionRequest', {**base, 'decisionType': 'APPROVE', 'opinion': None,
                                          'internalNote': '内部风险记录'})
        for decision in ('REJECT', 'REQUEST_CORRECTION'):
            self.valid('AppDecisionRequest', {**base, 'decisionType': decision,
                                              'opinion': '请补充清晰有效的证件原件'})
            self.invalid('AppDecisionRequest', {**base, 'decisionType': decision})
            self.invalid('AppDecisionRequest', {**base, 'decisionType': decision, 'opinion': '太短'})
        for mutation in ({'confirmed': False}, {'secondApproverId': '9'}, {'operatorId': '9'},
                         {'reviewerId': '9'}, {'evidenceStatus': 'VERIFIED'}):
            self.invalid('AppDecisionRequest', {**base, 'decisionType': 'APPROVE', **mutation})

    def test_application_number_is_business_number_not_numeric_public_id(self):
        number = 'SQ20260917ABCDEFGH'
        result = {'applicationId': '101', 'applicationNo': number, 'reservedMerchantId': '201',
                  'status': 'REVIEWING', 'version': '1', 'currentRevisionId': '301'}
        self.valid('AppResult', result)
        self.valid('AppResult', {**result, 'applicationNo': None, 'status': 'DRAFT'})
        self.invalid('AppResult', {**result, 'applicationNo': None})
        self.invalid('AppResult', {**result, 'status': 'DRAFT'})
        for bad in ('9007199254740993', 'SQ20260917ABCDEFG', 'sq20260917ABCDEFGH',
                    'SQ20260917ABCDEFGH\n'):
            self.invalid('AppResult', {**result, 'applicationNo': bad})

    def test_owner_and_admin_views_exclude_internal_notes_raw_credentials_and_unmasked_admin_contact(self):
        timestamp = '2026-09-17T10:00:00.000+08:00'
        owner = {
            'applicationId': '101', 'applicationNo': None, 'reservedMerchantId': '201',
            'status': 'DRAFT', 'version': '0', 'currentRevisionId': '301',
            'currentRevision': {'revisionId': '301', 'revisionNo': '1',
                                'draft': self.draft(), 'createdAt': timestamp},
            'submittedAt': None, 'reviewedAt': None, 'latestDecision': None,
            'subjectVerificationStatus': 'PENDING',
        }
        self.valid('AppOwnerDetail', owner)
        for field in ('internalNote', 'identifier', 'credentialNumber', 'rawOcrPayload',
                      'reviewerAccount', 'originalAssetUrl'):
            self.invalid('AppOwnerDetail', {**owner, field: 'secret'})

        review = {
            'applicationId': '101', 'applicationNo': 'SQ20260917ABCDEFGH',
            'reservedMerchantId': '201', 'status': 'REVIEWING', 'version': '1',
            'merchantName': '示例宠物医院', 'merchantTypeCode': 'PET_HOSPITAL',
            'cityCode': '310100', 'submittedRevisionId': '301', 'submittedAt': timestamp,
            'subjectVerificationStatus': 'PENDING',
            'submittedRevision': {
                'revisionId': '301', 'revisionNo': '1', 'createdAt': timestamp,
                'snapshot': {'merchantName': '示例宠物医院', 'merchantTypeCode': 'PET_HOSPITAL',
                             'cityCode': '310100', 'contactNameMasked': '张*',
                             'contactPhoneMasked': '138****8000',
                             'emailMasked': 'o***@example.test'},
            },
            'task': {'applicationId': '101', 'taskId': '401', 'submittedRevisionId': '301',
                     'status': 'AVAILABLE', 'version': '0', 'claimedByOperatorId': None},
            'latestDecision': None,
        }
        self.valid('AppReviewDetail', review)
        snapshot = review['submittedRevision']['snapshot']
        for field in ('contactName', 'contactPhone', 'email', 'identifier', 'internalNote',
                      'rawOcrPayload', 'privateAssetUrl'):
            changed = copy.deepcopy(review)
            changed['submittedRevision']['snapshot'][field] = 'secret'
            self.invalid('AppReviewDetail', changed)
        self.assertNotIn('internalNote', self.spec['components']['schemas']['AppApplicantDecision']['properties'])
        self.assertTrue({'contactNameMasked', 'contactPhoneMasked', 'emailMasked'} <= snapshot.keys())

    def test_admin_sensitive_routes_require_current_claimant_and_no_get_reveal_parameter(self):
        paths = self.spec['paths']
        detail = paths['/admin/merchant-applications/{applicationId}']['get']
        self.assertEqual(detail['x-required-actions'], ['merchant.application.read'])
        names = {parameter.get('name') for parameter in detail.get('parameters', [])
                 if isinstance(parameter, dict)}
        self.assertNotIn('reveal', names)
        self.assertNotIn('purpose', names)
        for suffix in ('release', 'manual-verification', 'decision'):
            operation = paths[f'/admin/merchant-applications/{{applicationId}}/{suffix}']['post']
            self.assertIs(operation.get('x-requires-current-claimant'), True)
        manual = paths['/admin/merchant-applications/{applicationId}/manual-verification']['post']
        self.assertEqual(manual['x-required-actions'],
                         ['merchant.application.decide', 'merchant.identity.reveal'])

    def test_reviewed_event_pairs_decision_and_status_without_private_fields(self):
        base = {
            'applicationId': '101', 'applicationNo': 'SQ20260917ABCDEFGH',
            'ownerUserId': '102', 'reservedMerchantId': '201',
            'submittedRevisionId': '301', 'reviewDecisionId': '501',
            'decidedAt': '2026-09-17T10:00:00.000+08:00',
        }
        approved = {**base, 'decisionType': 'APPROVE', 'applicationStatus': 'APPROVED',
                    'applicantVisibleOpinion': None}
        rejected = {**base, 'decisionType': 'REJECT', 'applicationStatus': 'REJECTED',
                    'applicantVisibleOpinion': '证件原件已过有效期，请更新后重提'}
        corrected = {**rejected, 'decisionType': 'REQUEST_CORRECTION'}
        for value in (approved, rejected, corrected):
            self.valid('MerchantApplicationReviewedPayload', value)
        self.invalid('MerchantApplicationReviewedPayload',
                     {**approved, 'applicationStatus': 'REJECTED'})
        self.invalid('MerchantApplicationReviewedPayload',
                     {**rejected, 'applicationStatus': 'APPROVED'})
        self.invalid('MerchantApplicationReviewedPayload',
                     {**rejected, 'applicantVisibleOpinion': '太短'})
        self.invalid('MerchantApplicationReviewedPayload',
                     {**rejected, 'applicantVisibleOpinion': None})
        self.invalid('MerchantApplicationReviewedPayload', {**approved, 'ownerUserId': 102})
        for private in ('internalNote', 'identifier', 'contactPhone', 'ocrPayload',
                        'decidedByOperatorId', 'privateAssetUrl'):
            self.invalid('MerchantApplicationReviewedPayload', {**approved, private: 'secret'})


if __name__ == '__main__':
    unittest.main()
