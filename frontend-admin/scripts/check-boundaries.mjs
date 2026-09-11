import { readFileSync, readdirSync } from 'node:fs';
import { resolve, relative, dirname } from 'node:path';
import assert from 'node:assert/strict';
const root = process.cwd();
const files = directory => readdirSync(directory, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? files(resolve(directory, entry.name)) : [resolve(directory, entry.name)]);
const pkg = JSON.parse(readFileSync('package.json', 'utf8'));
for (const file of files(resolve('src')).filter(file => /\.(ts|tsx)$/.test(file))) {
  const source = readFileSync(file, 'utf8');
  assert(!/\b(?:wx|Taro)\s*\./.test(source), `Miniapp API in ${file}`);
  for (const [, specifier] of source.matchAll(/(?:from\s*|import\s*\()\s*['"]([^'"]+)['"]/g)) {
    if (specifier.startsWith('.')) assert(!relative(root, resolve(dirname(file), specifier)).startsWith('..'), `Out-of-scope import: ${specifier}`);
    else assert(Object.keys(pkg.dependencies).some(name => specifier === name || specifier.startsWith(name + '/')), `Unexpected dependency: ${specifier}`);
  }
}
const production = files(resolve('dist')).filter(file => file.endsWith('.js')).map(file => readFileSync(file, 'utf8')).join('\n');
for (const marker of ['__private_fixture__', 'sample.execute', '测试身份']) assert(!production.includes(marker), `Fixture leaked into production: ${marker}`);
console.log('PASS: frontend-admin source imports stay inside its scope; no miniapp platform APIs; default production JavaScript excludes private fixtures.');
console.log('Scope: A-001 frontend boundary check only. Does not replace backend ARCH-001..005 or integrated CI.');
