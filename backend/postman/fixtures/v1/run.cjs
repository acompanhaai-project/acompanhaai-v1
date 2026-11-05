'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const vm = require('node:vm');

const root = __dirname;
const postmanRoot = path.resolve(root, '../..');
const args = process.argv.slice(2);
const seed = args.includes('--seed');
const cleanup = args.includes('--cleanup');
if (seed && cleanup) throw new Error('Escolha --seed OU --cleanup.');
const baseIndex = args.indexOf('--base-url');
const reportRoot = path.join(postmanRoot, 'reports');
const runtimePath = path.join(reportRoot, 'last-run.environment.json');
const environment = JSON.parse(fs.readFileSync(cleanup ? runtimePath : path.join(postmanRoot, 'local.postman_environment.json'), 'utf8'));
let baseUrl = baseIndex < 0 ? environment.values.find(v => v.key === 'baseUrl').value : args[baseIndex + 1];
const parsed = new URL(baseUrl);
if (parsed.protocol !== 'http:' || !['localhost', '127.0.0.1'].includes(parsed.hostname)
    || !parsed.port || parsed.pathname !== '/' || parsed.search || parsed.hash || parsed.username || parsed.password) {
    throw new Error('Esta fixture aceita somente HTTP local com porta explicita.');
}
baseUrl = parsed.origin;
const entry = environment.values.find(v => v.key === 'baseUrl');
entry.value = baseUrl;
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'sha256.json'), 'utf8'));
for (const [file, expected] of Object.entries(manifest.files)) {
    const actual = crypto.createHash('sha256').update(fs.readFileSync(path.join(root, file))).digest('hex');
    if (actual !== expected) throw new Error(`Fixture v1 alterada: ${file}. Restaure a versao original; nao atualize o hash para esconder alteracoes.`);
}
fs.mkdirSync(reportRoot, { recursive: true });
const collection = path.join(root, 'acompanhaai-v1.postman_collection.json');
const collectionData = JSON.parse(fs.readFileSync(collection, 'utf8'));
function validateScripts(node) {
    for (const event of node.event || []) new vm.Script(event.script.exec.join('\n'));
    for (const item of node.item || []) validateScripts(item);
}
validateScripts(collectionData);
const newman = require('newman');
console.log(`Fixture v1: ${seed ? 'preparacao (mantem dados)' : cleanup ? 'limpeza da ultima execucao' : 'teste completo com limpeza'} em ${baseUrl}`);
newman.run({
    collection, environment,
    folder: seed ? '01 - Preparacao' : cleanup ? '99 - Limpeza' : undefined,
    reporters: ['cli', 'json'],
    reporter: { json: { export: path.join(reportRoot, 'last-run.report.json') } },
    timeoutRequest: 15000,
    timeoutScript: 5000,
    insecure: false
}, (error, summary) => {
    if (summary && summary.environment) {
        fs.writeFileSync(runtimePath, JSON.stringify(summary.environment.toJSON(), null, 2) + '\n');
    }
    if (error) {
        console.error(error.message);
        process.exitCode = 1;
        return;
    }
    const stats = summary.run.stats;
    const failures = summary.run.failures.length;
    const result = {
        fixture: '1.0.0', target: baseUrl, mode: seed ? 'seed' : cleanup ? 'cleanup' : 'full',
        requests: stats.requests.total, assertions: stats.assertions.total,
        failedAssertions: stats.assertions.failed, failures, runId: summary.environment.get('runId'),
        primaryEmail: summary.environment.get('a_email'), secondaryEmail: summary.environment.get('b_email')
    };
    fs.writeFileSync(path.join(reportRoot, 'last-run.summary.json'), JSON.stringify(result, null, 2) + '\n');
    console.log(JSON.stringify(result, null, 2));
    process.exitCode = failures ? 1 : 0;
});
