'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const newman = require('newman');

const args = process.argv.slice(2);
const allowed = new Set(['--base-url', '--run-id', '--with-db-fault']);
for (let i = 0; i < args.length; i++) {
  if (!allowed.has(args[i])) {
    throw new Error('Argumento desconhecido: ' + args[i]);
  }
  if (args[i] !== '--with-db-fault') {
    i++;
  }
}
function option(name) {
  const index = args.indexOf(name);
  return index < 0 ? undefined : args[index + 1];
}

const baseArgument = option('--base-url');
if (!baseArgument) {
  throw new Error('Informe --base-url http://127.0.0.1:PORTA explicitamente.');
}
const target = new URL(baseArgument);
if (
  target.protocol !== 'http:' ||
  !['localhost', '127.0.0.1'].includes(target.hostname) ||
  !target.port ||
  target.pathname !== '/' ||
  target.search ||
  target.hash ||
  target.username ||
  target.password
) {
  throw new Error(
    'Executor exclusivo para HTTP local com porta explicita, sem caminho ou credenciais.'
  );
}
const baseUrl = target.origin;
const runId = option('--run-id') || crypto.randomUUID().replaceAll('-', '');
if (!/^[a-f0-9]{32}$/.test(runId)) {
  throw new Error('runId deve ter 32 caracteres hexadecimais minusculos.');
}
const withFault = args.includes('--with-db-fault');
const collectionFile = path.join(__dirname, 'acompanhaai-v2.postman_collection.json');
const collectionData = JSON.parse(fs.readFileSync(collectionFile, 'utf8'));
function requestsIn(node) {
  return (node.item || []).flatMap((item) => (item.request ? [item] : requestsIn(item)));
}
const collectionRequests = requestsIn(collectionData);
const expectedMainRequests = collectionRequests.length - (withFault ? 0 : 1);
const expectedGuardRequests = collectionRequests.filter(
  (item) => item.request.method === 'DELETE'
).length;
const check = spawnSync(process.execPath, [path.join(__dirname, 'build.cjs'), '--check'], {
  encoding: 'utf8'
});
if (check.status !== 0) {
  throw new Error('Colecao nao sincronizada com build.cjs/dados.json.');
}

const postmanRoot = path.resolve(__dirname, '../..');
function verifyV1() {
  const root = path.join(postmanRoot, 'fixtures/v1');
  const manifest = JSON.parse(fs.readFileSync(path.join(root, 'sha256.json'), 'utf8'));
  for (const [file, expected] of Object.entries(manifest.files)) {
    const actual = crypto
      .createHash('sha256')
      .update(fs.readFileSync(path.join(root, file)))
      .digest('hex');
    if (actual !== expected) {
      throw new Error('Integridade da fixture v1 divergente.');
    }
  }
  return Object.keys(manifest.files).length;
}
const v1Hashes = verifyV1();
const reports = path.join(postmanRoot, 'reports/v2');
fs.mkdirSync(reports, { recursive: true });
const reportRoot = path.join(reports, runId);
fs.mkdirSync(reportRoot);
const startedAt = new Date().toISOString();
const environment = {
  name: 'Acompanhaai v2 - execucao isolada',
  values: [
    { key: 'baseUrl', value: baseUrl, enabled: true },
    { key: 'v2_requestedRunId', value: runId, enabled: true },
    { key: 'v2_faultEnabled', value: withFault, enabled: true }
  ]
};

async function cleanupActor(actor, state) {
  if (state.get(`v2_${actor}_created`) !== true) {
    return { result: 'not-created' };
  }
  const id = Number(state.get(`v2_${actor}_id`));
  const email = `qa.kan33.${actor}+${runId}@example.test`;
  if (!Number.isSafeInteger(id) || id < 1 || state.get(`v2_${actor}_email`) !== email) {
    return { result: 'blocked-identity-mismatch' };
  }
  if (state.get(`v2_${actor}_deleted`) === true) {
    return { id, email, result: 'deleted-by-collection' };
  }
  let cookie = '';
  let token = '';
  async function call(method, route, body) {
    const response = await fetch(baseUrl + route, {
      method,
      redirect: 'error',
      signal: AbortSignal.timeout(10000),
      headers: {
        ...(cookie ? { Cookie: cookie } : {}),
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(token && method !== 'GET' ? { 'X-CSRF-TOKEN': token } : {})
      },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const session = response.headers
      .getSetCookie()
      .find((value) => value.startsWith('JSESSIONID='));
    if (session) {
      cookie = session.split(';')[0];
    }
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : null };
  }
  try {
    let response = await call('GET', '/api/auth/csrf');
    if (response.status !== 200) {
      return { id, email, result: 'failed-csrf' };
    }
    token = response.body.token;
    response = await call('POST', '/api/auth/login', { email, password: 'FixtureV2!' + runId });
    if (response.status !== 200) {
      return { id, email, result: 'failed-login' };
    }
    response = await call('GET', '/api/auth/csrf');
    if (response.status !== 200) {
      return { id, email, result: 'failed-refresh' };
    }
    token = response.body.token;
    response = await call('GET', '/api/users/me');
    if (response.status !== 200 || response.body.id !== id || response.body.email !== email) {
      return { id, email, result: 'blocked-identity-mismatch' };
    }
    response = await call('DELETE', '/api/users/me');
    if (response.status !== 204) {
      return { id, email, result: 'failed-delete' };
    }
    response = await call('GET', '/api/users/me');
    return {
      id,
      email,
      result: response.status === 401 ? 'deleted-by-fallback' : 'failed-revocation'
    };
  } catch {
    return { id, email, result: 'failed-network-or-response' };
  }
}

let lastState;
const run = newman.run(
  {
    collection: path.join(__dirname, 'acompanhaai-v2.postman_collection.json'),
    environment,
    reporters: [],
    timeoutRequest: 15000,
    timeoutScript: 15000,
    bail: false,
    insecure: false
  },
  async (error, summary) => {
    const state = summary?.environment || lastState;
    const accounts = {};
    for (const actor of ['a', 'b']) {
      accounts[actor] = state ? await cleanupActor(actor, state) : { result: 'unknown-state' };
    }
    const cleaned = Object.values(accounts).every((value) => value.result.startsWith('deleted-'));
    const stats = summary?.run.stats;
    let integrity = false;
    try {
      integrity = verifyV1() === v1Hashes;
    } catch {
      integrity = false;
    }
    const failures = summary?.run.failures || [];
    const result = {
      fixture: '2.0.0',
      runId,
      target: baseUrl,
      startedAt,
      finishedAt: new Date().toISOString(),
      databaseFaultEnabled: withFault,
      expectedCollectionRequests: expectedMainRequests,
      expectedIdentityRequests: expectedGuardRequests,
      requests: stats?.requests.total || 0,
      assertions: stats?.assertions.total || 0,
      failedAssertions: stats?.assertions.failed || 0,
      failures: failures.length,
      guardFailures: Number(state?.get('v2_guardFailures') || 0),
      v1HashesChecked: v1Hashes,
      v1IntegrityPreserved: integrity,
      accounts,
      cleanupComplete: cleaned,
      failureStages: failures.map((failure) => ({
        stage: failure.at || 'runtime',
        request: failure.source?.name || 'unknown',
        name: failure.error?.name || 'Error'
      }))
    };
    const passed =
      !error &&
      failures.length === 0 &&
      result.requests === expectedMainRequests + expectedGuardRequests &&
      result.failedAssertions === 0 &&
      result.guardFailures === 0 &&
      cleaned &&
      integrity;
    result.result = passed ? 'PASS' : 'FAIL';
    fs.writeFileSync(path.join(reportRoot, 'summary.json'), JSON.stringify(result, null, 2) + '\n');
    console.log(JSON.stringify(result, null, 2));
    process.exitCode = passed ? 0 : 1;
  }
);
run.on('item', (error, event) => {
  if (event?.environment) {
    lastState = event.environment;
  }
});
