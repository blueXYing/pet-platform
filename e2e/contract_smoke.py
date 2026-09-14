"""Offline document smoke only: no public DTO generation or live API claims."""
import json
import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / 'docs/04-api/11-OpenAPI-Core-v0.4.yaml'


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
    for path, item in spec['paths'].items():
        for method, operation in item.items():
            if method not in {'get', 'post', 'put', 'patch', 'delete', 'head', 'options'}:
                continue
            operation_id = operation['operationId']
            assert operation_id not in operations, f'Duplicate operationId {operation_id}'
            operations.add(operation_id)
            assert operation['responses'], f'Missing responses: {operation_id}'
            assert operation.get('security', spec.get('security')), f'Missing security: {operation_id}'
            parameters = operation.get('parameters', []) + item.get('parameters', [])
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
    return {'operations': len(operations), 'writesWithRequestId': writes, 'resolvedRefs': len(refs), 'stringIdProperties': ids}


if __name__ == '__main__':
    result = check(yaml.safe_load(SPEC.read_text(encoding='utf-8')))
    print(json.dumps({'status': 'PASS_OFFLINE_DOCUMENT_SMOKE', **result}, indent=2))
    print('NOT_EXECUTED: live HTTP, DTO serialization, semantic breaking-change approval, business E2E.')
    print('BLOCKED real session/RBAC integration: existing CCR-ACR-001 and CCR-PERM-001. No SDK generated.')
