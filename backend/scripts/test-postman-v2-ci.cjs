'use strict';

const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const crypto = require('node:crypto');
const { pathToFileURL } = require('node:url');
const { spawn, spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const args = process.argv.slice(2);
const options = {};
for (let index = 0; index < args.length; index += 2) {
  if (
    !['--postgres-port', '--postgres-bin', '--run-id'].includes(args[index]) ||
    !args[index + 1]
  ) {
    throw new Error('Argumentos invalidos para o executor de QA.');
  }
  options[args[index]] = args[index + 1];
}
const port = Number(options['--postgres-port']);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error('Informe a porta do servico PostgreSQL descartavel.');
}
const runId = options['--run-id'] || crypto.randomUUID().replaceAll('-', '');
if (!/^[a-f0-9]{32}$/.test(runId)) {
  throw new Error('Identificador de QA invalido.');
}
const work = path.join(root, 'target', 'kan34-postgres-' + runId);
fs.mkdirSync(work, { recursive: false });
const psqlName = process.platform === 'win32' ? 'psql.exe' : 'psql';
const psql = options['--postgres-bin'] ? path.join(options['--postgres-bin'], psqlName) : psqlName;
const psqlArgs = [
  '-X',
  '-w',
  '-h',
  '127.0.0.1',
  '-p',
  String(port),
  '-U',
  'acompanhaai_qa_admin',
  '-d',
  'acompanhaai',
  '-v',
  'ON_ERROR_STOP=1',
  '-Atq'
];
const psqlEnv = {
  ...process.env,
  PGPASSWORD: '',
  PGCONNECT_TIMEOUT: '5',
  PGPASSFILE: path.join(work, 'no-pgpass')
};
delete psqlEnv.PGSERVICE;
delete psqlEnv.PGSERVICEFILE;
let api;
let child;
let phase = 'preflight';
let interrupted = false;
const startedAt = new Date().toISOString();
const result = {
  task: 'KAN-34',
  runId,
  result: 'FAIL',
  apiStopped: false,
  fixtureRowsRemoved: false,
  unrelatedSentinelPreserved: false
};

function sql(statement, file = false) {
  const command = spawnSync(psql, [...psqlArgs, file ? '-f' : '-c', statement], {
    cwd: root,
    env: psqlEnv,
    encoding: 'utf8',
    windowsHide: true,
    timeout: 30000
  });
  if (command.status !== 0) {
    fs.writeFileSync(
      path.join(work, 'psql-error.log'),
      command.stderr || command.error?.code || 'psql failed'
    );
    throw new Error('PSQL_FAILED');
  }
  return command.stdout.trim();
}

function template(file, replacements) {
  let source = fs.readFileSync(path.join(root, 'postman/fixtures/v2', file), 'utf8');
  for (const [key, value] of Object.entries(replacements)) {
    source = source.replaceAll(key, String(value));
  }
  if (/__(?:RUN_ID|A_ID|B_ID)__/.test(source)) {
    throw new Error('SQL_PLACEHOLDER_NOT_RESOLVED');
  }
  const output = path.join(work, file);
  fs.writeFileSync(output, source);
  return output;
}

async function command(commandArgs) {
  if (interrupted) {
    throw new Error('INTERRUPTED');
  }
  await new Promise((resolve, reject) => {
    child = spawn(process.execPath, commandArgs, {
      cwd: root,
      stdio: 'inherit',
      windowsHide: true
    });
    child.once('error', () => reject(new Error('NODE_START_FAILED')));
    child.once('exit', (code) => {
      child = undefined;
      if (code === 0) {
        resolve();
      } else {
        reject(new Error('NODE_CHECK_FAILED'));
      }
    });
  });
}

async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const selected = server.address().port;
      server.close((error) => (error ? reject(error) : resolve(selected)));
    });
  });
}

async function stopApi() {
  if (!api || !api.pid || api.exitCode !== null || api.signalCode !== null) {
    result.apiStopped = true;
    return;
  }
  await new Promise((resolve) => {
    const timeout = setTimeout(() => api.kill('SIGKILL'), 10000);
    api.once('exit', () => {
      clearTimeout(timeout);
      resolve();
    });
    api.kill('SIGTERM');
  });
  result.apiStopped = true;
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, () => {
    interrupted = true;
    child?.kill('SIGTERM');
    api?.kill('SIGTERM');
  });
}

async function main() {
  try {
    const identity = sql('SELECT session_user, current_database()').split('|');
    if (identity[0] !== 'acompanhaai_qa_admin' || identity[1] !== 'acompanhaai') {
      throw new Error('QA_DATABASE_IDENTITY_MISMATCH');
    }
    if (
      sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'") !== '0'
    ) {
      throw new Error('QA_DATABASE_MUST_BE_EMPTY');
    }
    result.postgres = sql('SELECT version()');
    phase = 'collection-check';
    await command(['postman/fixtures/v2/build.cjs', '--check']);
    await command(['--test', 'postman/fixtures/v2/guards.test.cjs']);
    phase = 'roles';
    for (const file of ['create-database-roles.sql', 'adopt-database-objects.sql']) {
      sql(path.join(root, 'scripts/sql', file), true);
    }
    phase = 'start-api';
    const config = path.join(work, 'config');
    fs.mkdirSync(config);
    const source = fs.readFileSync(path.join(root, 'src/main/resources/application.yml'), 'utf8');
    const importPattern =
      /^  config:\r?\n    import: optional:file:\.\/application-local\.properties\r?\n/gm;
    if ([...source.matchAll(importPattern)].length !== 1) {
      throw new Error('LOCAL_IMPORT_CHANGED');
    }
    const base = source.replace(importPattern, '');
    if (/^\s*import:/m.test(base)) {
      throw new Error('UNEXPECTED_IMPORT');
    }
    fs.writeFileSync(path.join(config, 'application.yml'), base);
    const apiPort = await freePort();
    const baseUrl = 'http://127.0.0.1:' + apiPort;
    const logPath = path.join(work, 'api.log');
    const stdout = fs.openSync(logPath, 'w');
    const stderr = fs.openSync(path.join(work, 'api-error.log'), 'w');
    try {
      api = spawn(
        'java',
        [
          '-Xms32m',
          '-Xmx384m',
          '-XX:MaxMetaspaceSize=192m',
          '-XX:ReservedCodeCacheSize=96m',
          '-jar',
          path.join(root, 'target/acompanhaai-0.0.1-SNAPSHOT.jar'),
          '--spring.config.location=' + pathToFileURL(config + path.sep).href,
          '--spring.config.import=',
          '--spring.config.additional-location=',
          '--spring.config.name=application',
          '--spring.application.json={}',
          '--spring.profiles.active=',
          '--server.address=127.0.0.1',
          '--server.port=' + apiPort,
          '--server.servlet.session.cookie.secure=false',
          '--spring.datasource.url=jdbc:postgresql://127.0.0.1:' + port + '/acompanhaai',
          '--spring.datasource.username=acompanhaai_app',
          '--spring.datasource.password=',
          '--spring.flyway.user=acompanhaai_migrator',
          '--spring.flyway.password=',
          '--spring.flyway.locations=classpath:db/migration,classpath:db/callback'
        ],
        {
          cwd: root,
          stdio: ['ignore', stdout, stderr],
          windowsHide: true
        }
      );
      api.once('error', () => {
        interrupted = true;
      });
    } finally {
      fs.closeSync(stdout);
      fs.closeSync(stderr);
    }
    let ready = false;
    for (let attempt = 0; attempt < 120; attempt++) {
      if (interrupted || api.exitCode !== null || api.signalCode !== null) {
        throw new Error('API_EXITED_BEFORE_READY');
      }
      if (fs.readFileSync(logPath, 'utf8').includes('Tomcat started on port ' + apiPort + ' ')) {
        try {
          const response = await fetch(baseUrl + '/api/auth/csrf', {
            redirect: 'error',
            signal: AbortSignal.timeout(2000)
          });
          await response.arrayBuffer();
          if (response.status === 200) {
            ready = true;
            break;
          }
        } catch {
          // The owned API can still be completing its startup.
        }
      }
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    if (!ready) {
      throw new Error('API_NOT_READY');
    }
    result.apiTarget = baseUrl;
    result.schemaVersion = sql(
      'SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1'
    );
    phase = 'sql-fixtures';
    sql(template('history-fault.sql', { __RUN_ID__: runId }), true);
    sql(template('sentinel.sql', { __RUN_ID__: runId }), true);
    phase = 'postman-v2';
    let collectionFailed = false;
    try {
      await command([
        'postman/fixtures/v2/run.cjs',
        '--base-url',
        baseUrl,
        '--run-id',
        runId,
        '--with-db-fault'
      ]);
    } catch {
      collectionFailed = true;
    }
    const summary = path.join(root, 'postman/reports/v2', runId, 'summary.json');
    result.fixture = JSON.parse(fs.readFileSync(summary, 'utf8'));
    const a = Number(result.fixture.accounts.a.id);
    const b = Number(result.fixture.accounts.b.id);
    if (![a, b].every((id) => Number.isSafeInteger(id) && id > 0) || a === b) {
      throw new Error('FIXTURE_IDS_NOT_CONFIRMED');
    }
    phase = 'cleanup-verification';
    const rows = sql(
      template('verify-cleanup.sql', { __RUN_ID__: runId, __A_ID__: a, __B_ID__: b }),
      true
    ).split(/\r?\n/);
    result.fixtureRowsRemoved = rows.slice(0, 6).join(',') === '0,0,0,0,0,0';
    result.unrelatedSentinelPreserved = rows.slice(6).join(',') === '1,1,1,1,1,1';
    if (
      collectionFailed ||
      result.fixture.result !== 'PASS' ||
      !result.fixture.databaseFaultEnabled ||
      rows.length !== 12 ||
      !result.fixtureRowsRemoved ||
      !result.unrelatedSentinelPreserved
    ) {
      throw new Error('API_REGRESSION_FAILED');
    }
    result.result = 'PASS';
  } catch (error) {
    result.failurePhase = phase;
    result.failureCode = /^[A-Z_]+$/.test(error.message) ? error.message : 'EXECUTION_FAILED';
  } finally {
    await stopApi();
    result.startedAtUtc = startedAt;
    result.finishedAtUtc = new Date().toISOString();
    fs.writeFileSync(path.join(work, 'summary.json'), JSON.stringify(result, null, 2) + '\n');
  }
  console.log(JSON.stringify(result, null, 2));
  process.exitCode = result.result === 'PASS' && result.apiStopped ? 0 : 1;
}

main().catch(() => {
  console.error('Falha ao finalizar o executor CI; consultar o diretorio de QA em target.');
  process.exitCode = 1;
});
