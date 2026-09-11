"""GOV-001 read-only baseline checks; not a replacement for Maven/ArchUnit."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / 'docs/08-engineering/evidence/GOV-001'


def run_checks():
    original = json.loads((EVIDENCE / 'original-sha256.json').read_text(encoding='utf-8'))
    changed = [name for name, digest in original.items()
               if not (ROOT / name).is_file()
               or hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest]
    # This Issue adds governance files and extends only the existing root README.
    unexpected = [name for name in changed if name != 'README.md']
    directories = ['backend', 'docs', '.ai', '.github', 'scripts', 'frontend-miniapp',
                   'frontend-admin', 'frontend-c', 'frontend-merchant', 'e2e']
    missing = [name for name in directories if not (ROOT / name).is_dir()]
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    modules = ET.parse(ROOT / 'backend/pom.xml').findall('m:modules/m:module', ns)
    missing_poms = [m.text for m in modules if not (ROOT / 'backend' / m.text / 'pom.xml').is_file()]
    check = subprocess.run([sys.executable, str(ROOT / 'backend/tools/check-module-deps.py')],
                           capture_output=True, text=True)
    # The existing dependency checker is exercised against a disposable copy, never tracked sources.
    with tempfile.TemporaryDirectory(prefix='gov001-negative-') as folder:
        fixture = Path(folder)
        shutil.copytree(ROOT / 'backend/tools', fixture / 'tools')
        for pom in (ROOT / 'backend').glob('pet-*-biz/pom.xml'):
            target = fixture / pom.parent.name / 'pom.xml'
            target.parent.mkdir()
            shutil.copy2(pom, target)
        pom = fixture / 'pet-refund-biz/pom.xml'
        tree = ET.parse(pom)
        dependencies = tree.getroot().find('m:dependencies', ns)
        dep = ET.SubElement(dependencies, '{%s}dependency' % ns['m'])
        for tag, value in [('groupId', 'com.petplatform'), ('artifactId', 'pet-order-biz'),
                           ('version', '0.1.0-SNAPSHOT')]:
            ET.SubElement(dep, '{%s}%s' % (ns['m'], tag)).text = value
        tree.write(pom, encoding='utf-8', xml_declaration=True)
        negative = subprocess.run([sys.executable, str(fixture / 'tools/check-module-deps.py')],
                                  capture_output=True, text=True)
    result = {
        'original_file_count': len(original),
        'changed_original_files': changed,
        'unexpected_original_changes': unexpected,
        'missing_directories': missing,
        'maven_module_count': len(modules),
        'missing_module_poms': missing_poms,
        'ARCH-001_static_positive': {'exit': check.returncode, 'output': check.stdout.strip()},
        'ARCH-001_static_negative': {'exit': negative.returncode, 'output': negative.stdout.strip()},
        'limitation': 'Static checker only. Full ARCH-001 Maven negative and ARCH-002 remain unpassed; see report.'
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return int(bool(unexpected or missing or missing_poms or len(modules) != 40
                    or check.returncode != 0 or negative.returncode != 1
                    or 'pet-refund-biz -> pet-order-biz' not in negative.stdout))


if __name__ == '__main__':
    raise SystemExit(run_checks())
