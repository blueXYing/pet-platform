"""Negative document checks for the approved private-material boundary; no live HTTP claim."""
import copy
import unittest
import yaml
from contract_smoke import SPEC, check, PRIVATE_ASSET_OPERATIONS


class PrivateAssetContractRegressions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.baseline = yaml.safe_load(SPEC.read_text(encoding='utf-8'))

    def setUp(self):
        self.spec = copy.deepcopy(self.baseline)

    def operation(self, name):
        method, path = PRIVATE_ASSET_OPERATIONS[name]
        return self.spec['paths'][path][method]

    def reject(self, mutate):
        mutate()
        with self.assertRaises((AssertionError, KeyError, StopIteration)):
            check(self.spec)

    def test_current_surface_independent_counts(self):
        result = check(self.spec)
        self.assertEqual(result['operations'], 74)
        self.assertEqual(result['privateAssetOperations'], 3)
        self.assertEqual(result['legacyOperations'], 16)
        self.assertEqual(result['authOperations'], 36)
        self.assertEqual(result['merchantOperations'], 8)
        self.assertEqual(result['applicationOperations'], 11)

    def test_all_private_operations_require_current_bearer_audience_and_default_off(self):
        for name in PRIVATE_ASSET_OPERATIONS:
            for field, value in [('security', []), ('x-audience', 'PUBLIC'), ('x-default-enabled', True)]:
                with self.subTest(name=name, field=field):
                    self.spec = copy.deepcopy(self.baseline)
                    self.reject(lambda: self.operation(name).__setitem__(field, value))

    def test_upload_location_owner_and_non_ready_fields_rejected(self):
        for field in ['url', 'objectKey', 'ownerUserId']:
            with self.subTest(field=field):
                self.spec = copy.deepcopy(self.baseline)
                self.reject(lambda: self.spec['components']['schemas']['PrivateAssetUploadResult']['properties'].__setitem__(field, {'type': 'string'}))
        self.spec = copy.deepcopy(self.baseline)
        self.reject(lambda: self.spec['components']['schemas']['PrivateAssetUploadResult']['properties']['status']['enum'].append('SCANNING'))

    def test_upload_closed_payload_binary_and_purpose_required(self):
        for field, value in [('additionalProperties', True), ('required', ['file'])]:
            self.spec = copy.deepcopy(self.baseline)
            body = self.operation('uploadPrivateAsset')['requestBody']['content']['multipart/form-data']['schema']
            self.reject(lambda: body.__setitem__(field, value))
        self.spec = copy.deepcopy(self.baseline)
        body = self.operation('uploadPrivateAsset')['requestBody']['content']['multipart/form-data']['schema']
        self.reject(lambda: body['properties']['file'].__setitem__('format', 'byte'))

    def test_upload_hash_bytes_and_mime_limits_cannot_weaken(self):
        for field, change in [('bytes', {'type': 'integer', 'format': 'int64', 'minimum': 0, 'maximum': 99999999}), ('objectSha256', {'type': 'string'}), ('mediaType', {'type': 'string', 'enum': ['image/png', 'image/jpeg', 'image/svg+xml']})]:
            self.spec = copy.deepcopy(self.baseline)
            self.reject(lambda: self.spec['components']['schemas']['PrivateAssetUploadResult']['properties'].__setitem__(field, change))

    def test_creation_replay_and_request_key_required(self):
        self.reject(lambda: self.operation('uploadPrivateAsset')['responses'].pop('200'))
        self.spec = copy.deepcopy(self.baseline)
        self.reject(lambda: self.operation('uploadPrivateAsset')['parameters'].clear())

    def test_terminal_upload_rejection_must_be_distinct_from_generic_validation(self):
        self.reject(lambda: self.operation('uploadPrivateAsset')['responses'].pop('422'))
        self.spec = copy.deepcopy(self.baseline)
        self.reject(lambda: self.spec['components']['schemas']['PrivateAssetErrorEnvelope']['properties']['code']['enum'].remove('PRIVATE_ASSET_REJECTED'))

    def test_sensitive_errors_cannot_return_material_or_grant(self):
        self.reject(lambda: self.spec['components']['schemas']['PrivateAssetErrorEnvelope']['properties'].__setitem__('data', {'$ref': '#/components/schemas/PrivateAssetUploadResult'}))

    def test_grant_requires_purpose_reason_confirmation_and_revision(self):
        for field in ['reason', 'purposeCode', 'confirmed', 'submissionRevisionId']:
            self.spec = copy.deepcopy(self.baseline)
            self.reject(lambda: self.spec['components']['schemas']['PrivateAssetGrantRequest']['required'].remove(field))

    def test_grant_never_returns_absolute_storage_url(self):
        self.reject(lambda: self.spec['components']['schemas']['PrivateAssetGrantResult']['properties'].__setitem__('readUrl', {'type': 'string', 'format': 'uri'}))

    def test_grant_once_ttl_and_current_authorization_are_hard_gates(self):
        for name in ['issuePrivateAssetReadGrant', 'consumePrivateAssetReadGrant']:
            for key, value in [('x-single-use', False), ('x-grant-ttl-seconds', 3600), ('x-recheck-current-authorization', False)]:
                self.spec = copy.deepcopy(self.baseline)
                self.reject(lambda: self.operation(name).__setitem__(key, value))

    def test_claimant_and_revision_authorization_cannot_disappear(self):
        self.reject(lambda: self.operation('issuePrivateAssetReadGrant').pop('x-authorization'))

    def test_proxy_no_cache_nosniff_jpeg_png_and_consumed_failure_required(self):
        for header in ['Cache-Control', 'Pragma', 'X-Content-Type-Options']:
            self.spec = copy.deepcopy(self.baseline)
            self.reject(lambda: self.operation('consumePrivateAssetReadGrant')['responses']['200']['headers'].pop(header))
        for content in [
            {'image/png': {'schema': {'type': 'string', 'format': 'binary'}}},
            {'image/jpeg': {'schema': {'type': 'string', 'format': 'binary'}}},
            {
                'image/png': {'schema': {'type': 'string', 'format': 'binary'}},
                'image/jpeg': {'schema': {'type': 'string', 'format': 'binary'}},
                'image/gif': {'schema': {'type': 'string', 'format': 'binary'}},
            },
        ]:
            with self.subTest(content=content):
                self.spec = copy.deepcopy(self.baseline)
                self.reject(lambda: self.operation('consumePrivateAssetReadGrant')['responses']['200'].__setitem__('content', content))
        self.spec = copy.deepcopy(self.baseline)
        self.reject(lambda: self.operation('consumePrivateAssetReadGrant')['responses'].pop('410'))

    def test_private_route_removed_or_unknown_operation_not_whitelisted(self):
        self.reject(lambda: self.spec['paths'].pop('/c/private-assets'))
        self.spec = copy.deepcopy(self.baseline)
        self.reject(lambda: self.operation('uploadPrivateAsset').__setitem__('operationId', 'newUnreviewedUpload'))


if __name__ == '__main__':
    unittest.main()
