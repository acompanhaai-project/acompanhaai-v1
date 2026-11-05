# Política de migrações — KAN-34

## Regra

Arquivos existentes em `src/main/resources/db/migration` são imutáveis.
Uma correção de estrutura ou dados deve ser uma nova migração versionada,
por exemplo `V8__descricao_da_alteracao.sql`. Não editar, apagar, renomear ou
reformatar V1–V7 para adaptar um banco que já as executou.

Não usar `repair`, apagar `flyway_schema_history` ou atualizar checksums para
ocultar divergências. Uma falha deve ser investigada antes de aplicar qualquer
operação administrativa. Migrações novas precisam preservar registros existentes
e demonstrar instalação limpa e atualização de uma versão anterior.

Callbacks de privilégios em `db/callback` têm revisão própria e não são
protegidos pela comparação da pasta de migrações. A política atual cobre toda
a pasta `db/migration`, inclusive alterações de nome e tipo de arquivo.

## Gate no CI

`scripts/check-migrations.cjs` compara o estado atual com um SHA Git completo:

- PR: SHA da base do PR, incluindo todas as mudanças da branch.
- Push em main: SHA anterior ao push.
- Execução manual: input opcional `migration_base_sha`; sem ele, compara `HEAD^`,
  isto é, o commit anterior ao selecionado. Para avaliar uma branch inteira
  manualmente, informar explicitamente seu SHA base.

O checkout desse job usa `fetch-depth: 0`. Base ausente, inválida ou sem objeto
Git disponível falha; não produz aprovação sem comparação. O verificador
compara o hash Git de cada arquivo da base com o conteúdo atual, pelo mesmo
nome relativo. Reconhece tanto `src/main/resources/db/migration/` histórico
quanto `backend/src/main/resources/db/migration/`: somente a mudança de raiz
sem alteração de conteúdo é aceita. Edição, exclusão, renomeação de arquivo,
mudança de tipo ou duplicação nas duas raízes são rejeitadas.
Adições são permitidas. O Flyway e os testes continuam responsáveis por
versionamento válido, sintaxe, constraints e comportamento das novas migrações.

O gate publica `target/migration-policy/summary.json` e retorna código diferente
de zero ao encontrar alteração proibida. Não corrige nem modifica os arquivos.

## Validação local

```powershell
node --test scripts/check-migrations.test.cjs
$base = (git rev-parse main).Trim()
node scripts/check-migrations.cjs $base
```

Os dez testes usam repositórios Git descartáveis em `target`, com identidade
fictícia, hooks desativados e sem remoto. Simulam adição permitida, edição
antes e depois de commit, exclusão, renomeação, callback, base inválida/ausente
e a reorganização do backend, inclusive edição disfarçada e duplicação.
O repositório de trabalho não recebe commits desses testes.

## Atualização preservando dados

`DatabaseRoleMigrationIntegrationTest` demonstra V5→V7, preservando usuário,
versão de autenticação, valor salarial exato e faixa legada. Também instala
V1–V7 a partir de banco vazio, com migrator proprietário e app restrito, valida
o histórico e confirma que uma segunda execução não reaplica migrações.

`PostgresMigrationIntegrationTest`, no job PostgreSQL tradicional, preserva
usuário e candidatura V5, incluindo observações, etapa e versão. As asserções de
dinheiro, JSONB, FKs e concorrência estão nos testes de persistência e de fluxos
que executam no mesmo pipeline. Ver [matriz KAN-34](ci-postgresql-kan-34.md).

Esta checagem é uma regra de revisão e qualidade; não substitui backup,
homologação ou o planejamento de mudanças compatíveis para produção.
