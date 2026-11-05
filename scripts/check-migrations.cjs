'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

function git(root, args) {
  const result = spawnSync('git', ['-C', root, ...args], {
    encoding: 'utf8',
    windowsHide: true
  });
  if (result.status !== 0) {
    throw new Error('Base Git indisponivel. O checkout do CI precisa de fetch-depth: 0.');
  }
  return result.stdout.trimEnd();
}

function verifyMigrations(root, requestedBase) {
  let base = requestedBase;
  if (!base || /^0+$/.test(base)) {
    base = git(root, ['rev-parse', 'HEAD^']);
  }
  if (!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/i.test(base)) {
    throw new Error('A base deve ser um SHA Git completo.');
  }
  git(root, ['cat-file', '-e', base + '^{commit}']);
  const diff = git(root, [
    'diff',
    '--no-renames',
    '--name-status',
    '--diff-filter=MDT',
    '-z',
    base,
    '--',
    'src/main/resources/db/migration'
  ]);
  const fields = diff.split('\0').filter(Boolean);
  const violations = [];
  for (let index = 0; index < fields.length; index += 2) {
    violations.push({ status: fields[index], file: fields[index + 1] });
  }
  return { baseSha: base, result: violations.length ? 'FAIL' : 'PASS', violations };
}

if (require.main === module) {
  const root = path.resolve(__dirname, '..');
  const reportRoot = path.join(root, 'target/migration-policy');
  fs.mkdirSync(reportRoot, { recursive: true });
  let report;
  try {
    report = verifyMigrations(root, process.argv[2] || process.env.MIGRATION_BASE_SHA);
  } catch (error) {
    report = { result: 'FAIL', error: error.message };
  }
  fs.writeFileSync(path.join(reportRoot, 'summary.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
  process.exitCode = report.result === 'PASS' ? 0 : 1;
}

module.exports = { verifyMigrations };
