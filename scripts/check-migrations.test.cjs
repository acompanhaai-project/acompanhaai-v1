'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { verifyMigrations } = require('./check-migrations.cjs');

function fixture() {
  const target = path.resolve(__dirname, '../target');
  fs.mkdirSync(target, { recursive: true });
  const root = fs.mkdtempSync(path.join(target, 'kan34-policy-test-'));
  const migrations = path.join(root, 'src/main/resources/db/migration');
  fs.mkdirSync(migrations, { recursive: true });
  fs.writeFileSync(path.join(migrations, 'V1__initial.sql'), 'SELECT 1;\n');
  function git(...args) {
    const result = spawnSync('git', ['-C', root, ...args], { encoding: 'utf8', windowsHide: true });
    assert.equal(result.status, 0, 'Git do repositorio descartavel deve funcionar.');
    return result.stdout.trim();
  }
  git('init', '-b', 'main');
  git('config', 'user.name', 'QA KAN34');
  git('config', 'user.email', 'qa.kan34@example.test');
  git('config', 'core.hooksPath', path.join(root, 'no-hooks'));
  git('add', '.');
  git('-c', 'commit.gpgsign=false', 'commit', '-m', 'fixture inicial');
  return { root, migrations, base: git('rev-parse', 'HEAD'), git };
}

test('Nova migracao adicionada e permitida', () => {
  const f = fixture();
  fs.writeFileSync(path.join(f.migrations, 'V2__next.sql'), 'SELECT 2;\n');
  f.git('add', '.');
  assert.equal(verifyMigrations(f.root, f.base).result, 'PASS');
});

test('Edicao de migracao existente e rejeitada mesmo sem stage', () => {
  const f = fixture();
  fs.writeFileSync(path.join(f.migrations, 'V1__initial.sql'), 'SELECT 99;\n');
  const report = verifyMigrations(f.root, f.base);
  assert.equal(report.result, 'FAIL');
  assert.equal(report.violations[0].status, 'M');
  f.git('add', '.');
  f.git('-c', 'commit.gpgsign=false', 'commit', '-m', 'edicao simulada');
  assert.equal(verifyMigrations(f.root, f.base).result, 'FAIL');
});

test('Exclusao e rejeitada', () => {
  const f = fixture();
  fs.unlinkSync(path.join(f.migrations, 'V1__initial.sql'));
  assert.equal(verifyMigrations(f.root, f.base).violations[0].status, 'D');
});

test('Renomear migracao e rejeitado como exclusao da versao original', () => {
  const f = fixture();
  const original = path.resolve(f.migrations, 'V1__initial.sql');
  const renamed = path.resolve(f.migrations, 'V2__renamed.sql');
  assert.ok([original, renamed].every((file) => file.startsWith(f.root + path.sep)));
  fs.renameSync(original, renamed);
  f.git('add', '.');
  assert.equal(verifyMigrations(f.root, f.base).result, 'FAIL');
});

test('Callbacks e documentacao podem evoluir sem reescrever migracoes', () => {
  const f = fixture();
  const callbacks = path.join(f.root, 'src/main/resources/db/callback');
  fs.mkdirSync(callbacks, { recursive: true });
  fs.writeFileSync(path.join(callbacks, 'afterMigrate__grants.sql'), 'SELECT 2;\n');
  f.git('add', '.');
  assert.equal(verifyMigrations(f.root, f.base).result, 'PASS');
});

test('Base invalida ou inexistente falha em vez de aprovar sem comparar', () => {
  const f = fixture();
  assert.throws(() => verifyMigrations(f.root, '--qualquer-coisa'), /SHA Git completo/);
  assert.throws(() => verifyMigrations(f.root, 'f'.repeat(40)), /Base Git indisponivel/);
});
