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
  const repository = git(root, ['rev-parse', '--show-toplevel']);
  let base = requestedBase;
  if (!base || /^0+$/.test(base)) {
    base = git(root, ['rev-parse', 'HEAD^']);
  }
  if (!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/i.test(base)) {
    throw new Error('A base deve ser um SHA Git completo.');
  }
  git(root, ['cat-file', '-e', base + '^{commit}']);
  const directories = [
    'src/main/resources/db/migration/',
    'backend/src/main/resources/db/migration/'
  ];
  const tree = git(repository, ['ls-tree', '-r', '-z', base, '--', ...directories]);
  const violations = [];
  for (const entry of tree.split('\0').filter(Boolean)) {
    const [metadata, original] = entry.split('\t');
    const directory = directories.find((candidate) => original.startsWith(candidate));
    const relative = original.slice(directory.length);
    const candidates = directories
      .map((candidate) => candidate + relative)
      .filter((candidate) => fs.existsSync(path.join(repository, candidate)));

    if (candidates.length !== 1) {
      violations.push({ status: candidates.length ? 'T' : 'D', file: original });
      continue;
    }
    const current = candidates[0];
    if (!fs.lstatSync(path.join(repository, current)).isFile()) {
      violations.push({ status: 'T', file: current });
      continue;
    }
    const expectedHash = metadata.split(' ')[2];
    const actualHash = git(repository, ['hash-object', '--', current]);
    if (actualHash !== expectedHash) {
      violations.push({ status: 'M', file: current });
    }
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
