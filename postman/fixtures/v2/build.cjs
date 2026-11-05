'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const prettier = require('prettier');
const data = JSON.parse(fs.readFileSync(path.join(__dirname, 'dados.json'), 'utf8'));
const items = [];
let sequence = 0;

function script(listen, source) {
  new vm.Script(source);
  return { listen, script: { type: 'text/javascript', exec: source.trim().split('\n') } };
}

function folder(name) {
  const result = { name, item: [] };
  items.push(result);
  return result;
}

function request(group, name, method, route, actor, expected, body, checks = '', options = {}) {
  sequence++;
  const headers = actor ? [{ key: 'Cookie', value: `{{v2_${actor}_cookie}}` }] : [];
  if (body !== undefined) {
    headers.push({ key: 'Content-Type', value: 'application/json' });
  }
  if (actor && !['GET', 'HEAD'].includes(method) && !options.noCsrf) {
    headers.push({ key: 'X-CSRF-TOKEN', value: `{{v2_${actor}_csrf}}` });
  }
  const authenticated = actor && !options.anonymous;
  let pre = `pm.variables.set('v2_actor', ${JSON.stringify(actor || '')});\n`;
  pre += `const fail = message => {
    pm.environment.set('v2_guardFailures', Number(pm.environment.get('v2_guardFailures') || 0) + 1);
    pm.execution.skipRequest();
    throw new Error(message);
  };\n`;
  if (authenticated) {
    pre += `if (pm.environment.get('v2_${actor}_created') !== true) {
      fail('Conta nao criada nesta execucao.');
    }\n`;
  }
  for (const match of route.matchAll(/{{(v2_id_[A-Z_]+)}}/g)) {
    pre += `if (!/^[1-9][0-9]*$/.test(String(pm.environment.get('${match[1]}') || ''))) {
      fail('ID de fixture ausente.');
    }\n`;
  }
  if (options.fault) {
    pre += `if (pm.environment.get('v2_faultEnabled') !== true) {
      pm.execution.skipRequest();
    }\n`;
  }
  pre += options.pre || '';
  if (method === 'DELETE') {
    pre += `
      const expectedEmail = 'qa.kan33.${actor}+' + pm.environment.get('v2_runId') + '@example.test';
      if (pm.environment.get('v2_${actor}_email') !== expectedEmail) {
        fail('Email fora do escopo da fixture.');
      }
      pm.sendRequest({
        url: pm.environment.get('baseUrl') + '/api/users/me',
        method: 'GET',
        header: { Cookie: pm.environment.get('v2_${actor}_cookie') }
      }, (error, response) => {
        if (error || response.code !== 200) {
          return fail('Nao foi possivel confirmar a conta antes de remover.');
        }
        const owner = response.json();
        if (owner.email !== expectedEmail || owner.id !== Number(pm.environment.get('v2_${actor}_id'))) {
          fail('Conta divergente; exclusao bloqueada.');
        }
      });\n`;
  }
  if (actor) {
    pre += `pm.request.headers.upsert({ key: 'Cookie', value: pm.environment.get('v2_${actor}_cookie') || '' });\n`;
  }
  group.item.push({
    name: `${String(sequence).padStart(3, '0')} - ${name}`,
    protocolProfileBehavior: { disableCookies: true, followRedirects: false },
    request: {
      method,
      header: headers,
      url: '{{baseUrl}}' + route,
      ...(body === undefined ? {} : { body: { mode: 'raw', raw: JSON.stringify(body, null, 2) } })
    },
    event: [
      script('prerequest', pre),
      script(
        'test',
        `pm.test('HTTP ${expected}', () => pm.response.to.have.status(${expected}));\n${checks}`
      )
    ]
  });
}

function csrf(group, actor, anonymous = false, init = false) {
  request(
    group,
    `CSRF ${actor}`,
    'GET',
    '/api/auth/csrf',
    actor,
    200,
    undefined,
    `const body = pm.response.json();
     pm.test('CSRF disponivel', () => {
       pm.expect(body.headerName).to.eql('X-CSRF-TOKEN');
       pm.expect(body.token).to.be.a('string').and.not.empty;
     });
     pm.environment.set('v2_${actor}_csrf', body.token);`,
    {
      anonymous,
      pre: init
        ? `
      const supplied = pm.environment.get('v2_requestedRunId');
      const runId = supplied || pm.variables.replaceIn('{{$guid}}').replace(/-/g, '');
      if (!/^[a-f0-9]{32}$/.test(runId)) {
        fail('runId invalido.');
      }
      pm.environment.set('v2_runId', runId);
      pm.environment.set('v2_guardFailures', 0);
      Object.keys(pm.environment.toObject())
        .filter(key => key.startsWith('v2_id_'))
        .forEach(key => pm.environment.unset(key));
      ['a', 'b'].forEach(actor => {
        ['cookie', 'csrf', 'id'].forEach(key => pm.environment.unset('v2_' + actor + '_' + key));
        pm.environment.set('v2_' + actor + '_created', false);
        pm.environment.set('v2_' + actor + '_deleted', false);
        pm.environment.set('v2_' + actor + '_email', 'qa.kan33.' + actor + '+' + runId + '@example.test');
      });
      pm.environment.set('v2_password', 'FixtureV2!' + runId);
    `
        : ''
    }
  );
}

function login(group, actor) {
  request(group, `Login ${actor}`, 'POST', '/api/auth/login', actor, 200, {
    email: `{{v2_${actor}_email}}`,
    password: '{{v2_password}}'
  });
  csrf(group, actor);
}

function me(group, actor) {
  request(
    group,
    `Confirmar propria conta ${actor}`,
    'GET',
    '/api/users/me',
    actor,
    200,
    undefined,
    `const body = pm.response.json();
     pm.test('Identidade da fixture', () => {
       pm.expect(body.email).to.eql(pm.environment.get('v2_${actor}_email'));
       pm.expect(body.id).to.eql(Number(pm.environment.get('v2_${actor}_id')));
     });`
  );
}

function createJob(group, name, payload, variable, actor = 'a') {
  request(
    group,
    name,
    'POST',
    '/api/applications',
    actor,
    201,
    payload,
    `const body = pm.response.json();
     if (pm.response.code === 201) {
       pm.environment.set('${variable}', body.id);
     }
     pm.test('Registro criado', () => {
       pm.expect(body.id).to.be.a('number').and.above(0);
       pm.expect(body.version).to.eql(0);
       pm.expect(body.status).to.eql(${JSON.stringify(payload.status || 'APPLIED')});
     });`
  );
}

const setup = folder('01 - Preparacao');
for (const actor of ['a', 'b']) {
  csrf(setup, actor, true, actor === 'a');
  request(
    setup,
    `Cadastrar ${actor}`,
    'POST',
    '/api/users',
    actor,
    201,
    { name: data.users[actor], email: `{{v2_${actor}_email}}`, password: '{{v2_password}}' },
    `if (pm.response.code === 201) {
       const body = pm.response.json();
       pm.environment.set('v2_${actor}_id', body.id);
       pm.environment.set('v2_${actor}_created', true);
     }
     pm.test('Conta sem imagem', () => pm.expect(pm.response.json().photoUrl).to.eql(null));`,
    { anonymous: true }
  );
  login(setup, actor);
  me(setup, actor);
}

const CRUD = folder('02 - Cadastro, etapas e consultas');
for (const status of data.statuses) {
  const job =
    status === 'APPLIED'
      ? data.job
      : {
          title: `Java ${status} V2`,
          companyName: `Empresa ${status} Ficticia`,
          sourceUrl: `https://jobs.example.test/kan33/{{v2_runId}}/${status}`
        };
  createJob(
    CRUD,
    `Cadastrar ${status}`,
    {
      job,
      status,
      appliedOn: status === 'SAVED' ? null : data.date,
      notes: 'Fixture V2'
    },
    `v2_id_${status}`
  );
}
request(
  CRUD,
  'Consultar salario, modalidade e habilidades',
  'GET',
  '/api/applications/{{v2_id_APPLIED}}',
  'a',
  200,
  undefined,
  `const j = pm.response.json().job;
    pm.test('Detalhes persistidos', () => {
      pm.expect(j.salary).to.eql(5000.25);
      pm.expect(j.salaryCurrency).to.eql('BRL');
      pm.expect(j.salaryPeriod).to.eql('MONTH');
      pm.expect(j.workMode).to.eql('HYBRID');
      pm.expect(j.location).to.eql('Porto Alegre, RS');
      pm.expect(j.hardSkills).to.eql(['Java', 'Spring Boot']);
      pm.expect(j.softSkills).to.eql(['Comunicacao']);
    });`
);
request(
  CRUD,
  'Salario ausente permanece nulo',
  'GET',
  '/api/applications/{{v2_id_SAVED}}',
  'a',
  200,
  undefined,
  `const j = pm.response.json().job;
   pm.test('Ausencia sem inferencias', () => {
     ['salary', 'salaryCurrency', 'salaryPeriod'].forEach(key => pm.expect(j[key]).to.eql(null));
     pm.expect(j.hardSkills).to.eql([]);
     pm.expect(j.softSkills).to.eql([]);
   });`
);
for (const status of data.statuses) {
  request(
    CRUD,
    `Filtrar etapa ${status}`,
    'GET',
    `/api/applications?status=${status}&size=1`,
    'a',
    200,
    undefined,
    `const page = pm.response.json();
     pm.test('Etapa e contagem', () => {
       pm.expect(page.totalElements).to.eql(1);
       pm.expect(page.content[0].status).to.eql('${status}');
       pm.expect(page.content[0].id).to.eql(Number(pm.environment.get('v2_id_${status}')));
     });`
  );
}
request(
  CRUD,
  'Busca combinada por empresa e etapa',
  'GET',
  '/api/applications?q=%20aCmE%20&status=APPLIED',
  'a',
  200,
  undefined,
  `pm.test('Busca por empresa', () => {
    pm.expect(pm.response.json().totalElements).to.eql(1);
    pm.expect(pm.response.json().content[0].id).to.eql(Number(pm.environment.get('v2_id_APPLIED')));
  });`
);
request(
  CRUD,
  'Busca por titulo',
  'GET',
  '/api/applications?q=BACKEND',
  'a',
  200,
  undefined,
  `pm.test('Busca por titulo', () => pm.expect(pm.response.json().totalElements).to.eql(1));`
);
request(
  CRUD,
  'Busca vazia e paginacao',
  'GET',
  '/api/applications?q=%20%20&size=3&page=1',
  'a',
  200,
  undefined,
  `const page = pm.response.json();
   pm.test('Pagina limitada', () => {
     pm.expect(page.totalElements).to.eql(8);
     pm.expect(page.totalPages).to.eql(3);
     pm.expect(page.content.length).to.eql(3);
     pm.expect(page.page).to.eql(1);
   });`
);
request(
  CRUD,
  'Ordenacao consistente entre paginas',
  'GET',
  '/api/applications?size=3&page=0',
  'a',
  200,
  undefined,
  `pm.environment.set('v2_firstPage', JSON.stringify(pm.response.json().content.map(x => x.id)));
   pm.test('Ordem decrescente', () => {
     const rows = pm.response.json().content;
     for (let i = 1; i < rows.length; i++) {
       const before = Date.parse(rows[i - 1].updatedAt);
       const after = Date.parse(rows[i].updatedAt);
       pm.expect(before >= after).to.eql(true);
       if (before === after) {
         pm.expect(rows[i - 1].id).to.be.above(rows[i].id);
       }
     }
   });`
);
request(
  CRUD,
  'Pagina seguinte nao repete IDs',
  'GET',
  '/api/applications?size=3&page=1',
  'a',
  200,
  undefined,
  `pm.test('Sem repeticoes', () => {
    const previous = JSON.parse(pm.environment.get('v2_firstPage'));
    pm.response.json().content.forEach(row => pm.expect(previous).not.to.include(row.id));
  });`
);

const invalid = folder('03 - Validacoes e duplicidade');
const invalidCases = [
  ['Salario negativo', { salary: -1 }],
  ['Precisao salarial', { salary: 10.001 }],
  ['Moeda diferente de BRL', { salaryCurrency: 'USD' }],
  ['Salario sem periodicidade', { salaryPeriod: null }],
  ['Periodo sem salario', { salary: null }],
  ['Habilidades duplicadas', { hardSkills: ['Java', ' java '] }],
  ['Duplicidade entre tipos', { softSkills: ['JAVA'] }],
  ['Habilidade vazia', { hardSkills: [' '] }],
  ['Limite de habilidades', { hardSkills: Array.from({ length: 51 }, (_, n) => `Skill ${n}`) }],
  ['Localizacao extensa', { location: 'x'.repeat(256) }],
  ['URL com credenciais', { sourceUrl: 'https://usuario:senha-ficticia@example.test/job' }]
];
for (const [name, changes] of invalidCases) {
  request(invalid, name, 'POST', '/api/applications', 'a', 422, {
    job: { ...data.job, ...changes }
  });
}
request(invalid, 'SAVED com data', 'POST', '/api/applications', 'a', 422, {
  job: { title: 'Java' },
  status: 'SAVED',
  appliedOn: data.date
});
request(invalid, 'Etapa desconhecida', 'POST', '/api/applications', 'a', 400, {
  job: { title: 'Java' },
  status: 'UNKNOWN'
});
request(invalid, 'Limite da busca', 'GET', '/api/applications?q=' + 'x'.repeat(101), 'a', 422);
request(invalid, 'Limite da pagina', 'GET', '/api/applications?size=101', 'a', 422);
request(invalid, 'Pagina negativa', 'GET', '/api/applications?page=-1', 'a', 422);
for (const [name, url] of [
  ['Link identico', data.job.sourceUrl],
  [
    'Link equivalente',
    data.job.sourceUrl.replace('https://jobs.example.test', 'HTTPS://JOBS.EXAMPLE.TEST:443')
  ]
]) {
  request(
    invalid,
    name,
    'POST',
    '/api/applications',
    'a',
    409,
    { job: { ...data.job, sourceUrl: url } },
    `pm.test('Duplicidade identificada', () => pm.expect(pm.response.json().code).to.eql('JOB_URL_ALREADY_EXISTS'));`
  );
}
request(
  invalid,
  'Falhas nao criaram registros parciais',
  'GET',
  '/api/applications',
  'a',
  200,
  undefined,
  `pm.test('Contagem preservada', () => pm.expect(pm.response.json().totalElements).to.eql(8));`
);

const edit = folder('04 - Edicao e isolamento');
const editedJob = {
  ...data.job,
  salary: 75.1,
  salaryPeriod: 'HOUR',
  hardSkills: ['java', 'SQL'],
  softSkills: ['Organizacao']
};
request(
  edit,
  'Editar detalhes e etapa',
  'PUT',
  '/api/applications/{{v2_id_APPLIED}}',
  'a',
  200,
  { job: editedJob, status: 'INTERVIEW', version: 0, notes: 'Editada V2' },
  `pm.test('Edicao persistida', () => {
    const r = pm.response.json();
    pm.expect(r.version).to.eql(1);
    pm.expect(r.job.salary).to.eql(75.1);
    pm.expect(r.job.hardSkills).to.eql(['java', 'SQL']);
  });`
);
function unchanged(group, name, version = 1, salary = 75.1) {
  request(
    group,
    name,
    'GET',
    '/api/applications/{{v2_id_APPLIED}}',
    'a',
    200,
    undefined,
    `pm.test('Snapshot preservado', () => {
      const r = pm.response.json();
      pm.expect(r.status).to.eql('INTERVIEW');
      pm.expect(r.version).to.eql(${version});
      pm.expect(r.notes).to.eql('Editada V2');
      pm.expect(r.job.salary).to.eql(${salary});
      pm.expect(r.job.salaryPeriod).to.eql('HOUR');
      pm.expect(r.job.hardSkills).to.eql(['java', 'SQL']);
      pm.expect(r.job.softSkills).to.eql(['Organizacao']);
    });`
  );
}
request(edit, 'Versao antiga', 'PUT', '/api/applications/{{v2_id_APPLIED}}', 'a', 409, {
  job: { title: 'Nao deve persistir' },
  status: 'OFFER',
  version: 0
});
unchanged(edit, 'Versao antiga nao alterou dados');
request(
  edit,
  'Editar para link ja cadastrado',
  'PUT',
  '/api/applications/{{v2_id_APPLIED}}',
  'a',
  409,
  {
    job: { ...editedJob, sourceUrl: 'https://jobs.example.test/kan33/{{v2_runId}}/SAVED' },
    status: 'OFFER',
    version: 1
  }
);
unchanged(edit, 'Conflito de link nao alterou dados');
request(edit, 'Edicao invalida', 'PUT', '/api/applications/{{v2_id_APPLIED}}', 'a', 422, {
  job: { ...editedJob, hardSkills: ['Java', 'JAVA'] },
  status: 'OFFER',
  version: 1
});
unchanged(edit, 'Edicao invalida nao alterou dados');
request(
  edit,
  'Historico de alteracao',
  'GET',
  '/api/applications/{{v2_id_APPLIED}}/history',
  'a',
  200,
  undefined,
  `pm.test('Somente eventos confirmados', () => {
    const events = pm.response.json();
    pm.expect(events.length).to.eql(2);
    pm.expect(events[0].fromStatus).to.eql(null);
    pm.expect(events[0].toStatus).to.eql('APPLIED');
    pm.expect(events[1].fromStatus).to.eql('APPLIED');
    pm.expect(events[1].toStatus).to.eql('INTERVIEW');
  });`
);
createJob(edit, 'Mesma URL em outra conta', { job: data.job }, 'v2_id_B', 'b');
request(
  edit,
  'Listagem isolada B',
  'GET',
  '/api/applications',
  'b',
  200,
  undefined,
  `pm.test('Somente propria candidatura', () => {
    const p = pm.response.json();
    pm.expect(p.totalElements).to.eql(1);
    pm.expect(p.content[0].id).to.eql(Number(pm.environment.get('v2_id_B')));
  });`
);
request(
  edit,
  'Conta B nao consulta candidatura A',
  'GET',
  '/api/applications/{{v2_id_APPLIED}}',
  'b',
  404
);
request(
  edit,
  'Conta B nao consulta historico A',
  'GET',
  '/api/applications/{{v2_id_APPLIED}}/history',
  'b',
  404
);
request(edit, 'Conta B nao edita A', 'PUT', '/api/applications/{{v2_id_APPLIED}}', 'b', 404, {
  job: editedJob,
  status: 'OFFER',
  version: 1
});
request(edit, 'Conta B nao exclui A', 'DELETE', '/api/applications/{{v2_id_APPLIED}}', 'b', 404);
unchanged(edit, 'Isolamento preservou candidatura A');
request(edit, 'Sem autenticacao', 'GET', '/api/applications', null, 401);
request(
  edit,
  'Escrita sem CSRF',
  'PUT',
  '/api/applications/{{v2_id_APPLIED}}',
  'a',
  403,
  { job: editedJob, status: 'OFFER', version: 1 },
  '',
  { noCsrf: true }
);

const fault = folder('05 - Rollback de persistencia (PostgreSQL isolado)');
request(
  fault,
  'Falha SQL no historico depois do flush',
  'PUT',
  '/api/applications/{{v2_id_APPLIED}}',
  'a',
  409,
  {
    job: { ...editedJob, salary: 999, hardSkills: ['Python'] },
    status: 'OFFER',
    version: 1,
    notes: data.faultMarker
  },
  '',
  { fault: true }
);
unchanged(fault, 'Rollback real restaurou detalhes e versao');
request(
  fault,
  'Rollback nao adicionou historico',
  'GET',
  '/api/applications/{{v2_id_APPLIED}}/history',
  'a',
  200,
  undefined,
  `pm.test('Historico restaurado', () => pm.expect(pm.response.json().length).to.eql(2));`
);

const finish = folder('06 - Substituicao, busca literal e exclusao');
request(
  finish,
  'Alterar somente observacoes',
  'PUT',
  '/api/applications/{{v2_id_APPLIED}}',
  'a',
  200,
  { job: editedJob, status: 'INTERVIEW', version: 1, notes: 'Observacao V2' }
);
request(
  finish,
  'Observacoes nao geram evento',
  'GET',
  '/api/applications/{{v2_id_APPLIED}}/history',
  'a',
  200,
  undefined,
  `pm.test('Sem novo evento', () => pm.expect(pm.response.json().length).to.eql(2));`
);
request(
  finish,
  'Limpar salario e habilidades',
  'PUT',
  '/api/applications/{{v2_id_APPLIED}}',
  'a',
  200,
  {
    job: {
      title: 'Backend Java V2',
      sourceUrl: data.job.sourceUrl,
      salary: null,
      hardSkills: [],
      softSkills: []
    },
    status: 'INTERVIEW',
    version: 2
  },
  `pm.test('Dados opcionais removidos', () => {
    const j = pm.response.json().job;
    ['salary', 'salaryCurrency', 'salaryPeriod', 'location', 'workMode'].forEach(key => pm.expect(j[key]).to.eql(null));
    pm.expect(j.hardSkills).to.eql([]);
    pm.expect(j.softSkills).to.eql([]);
  });`
);
createJob(
  finish,
  'Vaga com caracteres literais',
  { job: { title: 'Java 100%_! literal V2' } },
  'v2_id_LITERAL'
);
for (const literal of ['%25', '_', '!']) {
  request(
    finish,
    `Buscar caractere literal ${literal}`,
    'GET',
    `/api/applications?q=${literal}`,
    'a',
    200,
    undefined,
    `pm.test('Caracteres sem curingas', () => {
      pm.expect(pm.response.json().totalElements).to.eql(1);
      pm.expect(pm.response.json().content[0].id).to.eql(Number(pm.environment.get('v2_id_LITERAL')));
    });`
  );
}
request(
  finish,
  'Excluir candidatura da fixture',
  'DELETE',
  '/api/applications/{{v2_id_SAVED}}',
  'a',
  204
);
request(finish, 'Candidatura removida', 'GET', '/api/applications/{{v2_id_SAVED}}', 'a', 404);
request(finish, 'Historico removido', 'GET', '/api/applications/{{v2_id_SAVED}}/history', 'a', 404);
request(
  finish,
  'Excluir novamente retorna 404',
  'DELETE',
  '/api/applications/{{v2_id_SAVED}}',
  'a',
  404
);
createJob(
  finish,
  'URL liberada apos exclusao',
  {
    job: {
      title: 'Java SAVED recriada',
      sourceUrl: 'https://jobs.example.test/kan33/{{v2_runId}}/SAVED'
    }
  },
  'v2_id_RECREATED'
);
request(
  finish,
  'Remover candidatura recriada',
  'DELETE',
  '/api/applications/{{v2_id_RECREATED}}',
  'a',
  204
);

const cleanup = folder('99 - Limpeza restrita');
for (const actor of ['a', 'b']) {
  csrf(cleanup, actor);
  login(cleanup, actor);
  me(cleanup, actor);
  request(
    cleanup,
    `Excluir somente conta ${actor} da execucao`,
    'DELETE',
    '/api/users/me',
    actor,
    204,
    undefined,
    `if (pm.response.code === 204) {
      pm.environment.set('v2_${actor}_deleted', true);
    }`
  );
  request(cleanup, `Sessao ${actor} revogada`, 'GET', '/api/users/me', actor, 401);
}

const collection = {
  info: {
    name: 'Acompanhaai - Regressao API v2 (SEM IMAGEM)',
    description:
      'API real com dados ficticios. Executar na ordem. Rollback SQL exige o executor PostgreSQL isolado. Cleanup limitado as contas criadas nesta execucao.',
    schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json'
  },
  variable: [{ key: 'baseUrl', value: 'http://localhost:18083' }],
  event: [
    script(
      'prerequest',
      `
      const base = pm.environment.get('baseUrl') || pm.collectionVariables.get('baseUrl') || '';
      if (!/^http:[/][/](localhost|127[.]0[.]0[.]1):[0-9]+$/.test(base)) {
        pm.execution.skipRequest();
        throw new Error('Alvo deve ser HTTP local com porta explicita.');
      }
      pm.environment.set('baseUrl', base);
    `
    ),
    script(
      'test',
      `
      const actor = pm.variables.get('v2_actor');
      const cookie = pm.response.headers.all().find(h => h.key.toLowerCase() === 'set-cookie' && h.value.startsWith('JSESSIONID='));
      if (actor && cookie) {
        pm.environment.set('v2_' + actor + '_cookie', cookie.value.split(';')[0]);
      }
      pm.test('Sem credenciais na resposta', () => {
        const text = pm.response.text();
        const password = pm.environment.get('v2_password');
        if (password) {
          pm.expect(text).not.to.include(password);
        }
        pm.expect(text).not.to.match(/"(?:password|passwordHash|password_hash)"[ ]*:/i);
      });
    `
    )
  ],
  item: items
};

async function main() {
  async function formatScripts(node) {
    for (const event of node.event || []) {
      const source = await prettier.format(event.script.exec.join('\n'), {
        parser: 'babel',
        tabWidth: 2,
        printWidth: 100,
        singleQuote: true,
        trailingComma: 'none'
      });
      new vm.Script(source);
      event.script.exec = source.trimEnd().split('\n');
    }
    for (const item of node.item || []) {
      await formatScripts(item);
    }
  }
  await formatScripts(collection);
  const file = path.join(__dirname, 'acompanhaai-v2.postman_collection.json');
  const serialized = JSON.stringify(collection, null, 2) + '\n';
  if (process.argv.includes('--check')) {
    if (
      !fs.existsSync(file) ||
      fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n') !== serialized
    ) {
      throw new Error(
        'Colecao v2 diverge de build.cjs/dados.json. Execute npm run build na pasta v2.'
      );
    }
  } else {
    fs.writeFileSync(file, serialized);
  }
  console.log(
    `V2: ${sequence} requisicoes, scripts compilados e colecao ${process.argv.includes('--check') ? 'conferida' : 'gerada'}.`
  );
}
main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
