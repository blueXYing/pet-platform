"""Enforce decision 22: executable production SQL belongs in Mapper XML.

Spring transaction/DataSource plumbing and JDBC metadata inspection are allowed.
Test fixtures and migration .sql files are outside this production-source gate.
"""
from pathlib import Path
import re
import sys

TOKENS = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
SQL = re.compile(r'\b(?:SELECT\s+(?:[\w*`(])|INSERT\s+(?:IGNORE\s+)?INTO\b|UPDATE\s+[`\w.]+\s+SET\b|DELETE\s+FROM\b|REPLACE\s+INTO\b|SET\s+(?:SESSION\b|time_zone\b|innodb_)|WITH\s+\w+\s+AS\s*\()', re.I)
EXECUTORS = re.compile(r'\b(?:JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient|JdbcOperations|NamedParameterJdbcOperations|R2dbcEntityTemplate)\b|\.(?:prepareStatement|prepareCall|createStatement)\s*\(|\borg\.apache\.ibatis\.jdbc\b|@(?:[\w.]+\.)?(?:Select|Insert|Update|Delete)(?:Provider)?\s*\(')


def violations(source):
    code = []
    strings = []
    end = 0
    previous_end = None
    for token in TOKENS.finditer(source):
        code.append(source[end:token.start()])
        value = token.group()
        if value.startswith('"'):
            content = value[3:-3] if value.startswith('"""') else value[1:-1]
            # Detect adjacent literal concatenation such as "SEL" + "ECT id".
            if previous_end is not None and re.fullmatch(r'\s*\+\s*', source[previous_end:token.start()]):
                strings[-1] += content
            else:
                strings.append(content)
            previous_end = token.end()
        else:
            previous_end = None
        code.append(' ' * len(value))
        end = token.end()
    code.append(source[end:])
    findings = []
    if EXECUTORS.search(''.join(code)):
        findings.append('JDBC executor or annotation/provider SQL is forbidden')
    if any(SQL.search(value) for value in strings):
        findings.append('SQL literal must move to owner Mapper XML')
    return findings


def check(root):
    root = Path(root)
    files = sorted(root.glob('*/src/main/java/**/*.java'))
    if not files:
        return ['PERSISTENCE-COVERAGE: no production Java files found']
    errors = []
    for path in files:
        errors.extend(f'PERSISTENCE-XML {path.relative_to(root)}: {item}'
                      for item in violations(path.read_text(encoding='utf-8')))
    for path in root.glob('*/src/main/resources/mapper/**/*.xml'):
        source = re.sub(r'<!--[\s\S]*?-->', '', path.read_text(encoding='utf-8'))
        if '${' in source:
            errors.append(f'PERSISTENCE-XML {path.relative_to(root)}: raw SQL substitution is forbidden; use bound parameters and XML branches')
    return errors


if __name__ == '__main__':
    errors = check(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1])
    print('\n'.join(errors) if errors else 'PASS: production SQL uses MyBatis Mapper XML')
    sys.exit(bool(errors))
