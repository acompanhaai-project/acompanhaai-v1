'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { spawnSync } = require('node:child_process');
const collection = JSON.parse(
  fs.readFileSync(path.join(__dirname, 'acompanhaai-v2.postman_collection.json'), 'utf8')
);
const requests = collection.item.flatMap((folder) => folder.item);
const runId = 'a'.repeat(32);

function harness(overrides = {}, identity = { id: 2, email: `qa.kan33.a+${runId}@example.test` }) {
  const values = new Map(
    Object.entries({
      baseUrl: 'http://127.0.0.1:18083',
      v2_requestedRunId: runId,
      v2_runId: runId,
      v2_a_created: true,
      v2_a_id: 2,
      v2_a_email: `qa.kan33.a+${runId}@example.test`,
      v2_a_cookie: 'JSESSIONID=ficticio',
      v2_id_APPLIED: 8,
      ...overrides
    })
  );
  const result = { skipped: false, auxiliaryRequests: 0, values };
  result.context = {
    pm: {
      environment: {
        get: (key) => values.get(key),
        set: (key, value) => values.set(key, value),
        unset: (key) => values.delete(key),
        toObject: () => Object.fromEntries(values)
      },
      variables: { set: () => undefined, replaceIn: () => runId },
      collectionVariables: { get: () => 'http://127.0.0.1:18083' },
      execution: {
        skipRequest() {
          result.skipped = true;
        }
      },
      request: { headers: { upsert: () => undefined } },
      sendRequest(request, callback) {
        result.auxiliaryRequests++;
        callback(null, { code: 200, json: () => identity });
      }
    }
  };
  return result;
}

function beforeRequest(item, state) {
  const source = item.event.find((event) => event.listen === 'prerequest').script.exec.join('\n');
  return new vm.Script(source).runInNewContext(state.context, { timeout: 1000 });
}

test('Preparacao remove IDs herdados antes de aceitar novos registros', () => {
  const state = harness({ v2_id_APPLIED: 900, v2_id_RECREATED: 901 });
  beforeRequest(requests[0], state);
  assert.equal(state.values.has('v2_id_APPLIED'), false);
  assert.equal(state.values.has('v2_id_RECREATED'), false);
  assert.equal(state.values.get('v2_a_created'), false);
  assert.equal(state.values.has('v2_a_id'), false);
});

test('Sem criacao comprovada nenhuma exclusao ou consulta auxiliar e enviada', () => {
  const state = harness({ v2_a_created: false });
  const item = requests.find((item) => item.name.includes('Excluir somente conta a'));
  assert.throws(() => beforeRequest(item, state), /Conta nao criada/);
  assert.equal(state.skipped, true);
  assert.equal(state.auxiliaryRequests, 0);
});

test('ID de candidatura ausente bloqueia exclusao antes de consultar a sessao', () => {
  const state = harness({ v2_id_SAVED: undefined });
  const item = requests.find((item) => item.name.includes('Excluir candidatura da fixture'));
  assert.throws(() => beforeRequest(item, state), /ID de fixture ausente/);
  assert.equal(state.skipped, true);
  assert.equal(state.auxiliaryRequests, 0);
});

test('Sessao de outra identidade bloqueia DELETE mesmo com variaveis numericas', () => {
  const state = harness({}, { id: 999, email: 'outra-conta@example.test' });
  const item = requests.find((item) => item.name.includes('Excluir somente conta a'));
  assert.throws(() => beforeRequest(item, state), /Conta divergente/);
  assert.equal(state.skipped, true);
  assert.equal(state.values.get('v2_guardFailures'), 1);
});

for (const target of [
  'https://example.invalid:18083',
  'http://example.invalid:18083',
  'http://localhost',
  'http://usuario:ficticio@127.0.0.1:18083',
  'http://127.0.0.1:18083/api'
]) {
  test(`CLI rejeita alvo fora do formato permitido: ${target}`, () => {
    const result = spawnSync(
      process.execPath,
      [path.join(__dirname, 'run.cjs'), '--base-url', target],
      {
        encoding: 'utf8',
        timeout: 10000
      }
    );
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Executor exclusivo/);
  });
}
