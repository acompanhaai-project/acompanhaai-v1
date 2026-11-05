# PostgreSQL e regressão no CI — KAN-34

## Implementação

Os três jobs Java existentes foram mantidos. A KAN-34 acrescenta dois checks
ao workflow `Backend CI`:

| Check                      | Responsabilidade                                                             |
| -------------------------- | ---------------------------------------------------------------------------- |
| `backend-migration-policy` | Testar e aplicar a política de migrações imutáveis contra a base Git correta |
| `backend-postman-v2`       | PostgreSQL 18 novo, API própria, coleção v2, rollback e limpeza por IDs      |

O job Postman depende do gate de migrações. Usa Node.js 22, Java 25, `npm ci`
somente no pacote v2 e cache identificado pelo lockfile v2. O PostgreSQL é um
serviço descartável do próprio job, publicado no loopback. Usar Ubuntu segue
o requisito de serviços Docker dos [runners do GitHub](https://docs.github.com/en/actions/tutorials/use-containerized-services/create-postgresql-service-containers).
Configuração de runtime/cache: [actions/setup-node](https://github.com/actions/setup-node).

## Executor compartilhado

`scripts/test-postman-v2-ci.cjs` é portável entre Windows e Linux. Recebe a porta
do PostgreSQL de QA e recusa banco com tabelas públicas existentes ou identidade
diferente de `acompanhaai_qa_admin` no banco `acompanhaai`. Não recebe uma URL de
banco remoto, senha real ou alvo HTTP externo.

O executor aplica os SQLs de roles, inicia seu próprio JAR com app e migrator
separados e aguarda a porta anunciada pelo processo criado. Usa cópia do YAML
sem imports privados e configurações explícitas de conexão. Em seguida aplica
os templates compartilhados de falha/sentinela, executa a v2 e verifica por SQL
os IDs fictícios. A API é encerrada no `finally`; o serviço do CI é encerrado
pelo GitHub. No Windows, o wrapper local encerra também seu cluster.

Templates `sentinel.sql` e `verify-cleanup.sql` são compartilhados com o roteiro
Windows padrão para evitar duas definições dos mesmos dados e da limpeza.
Os SQLs de roles e o trigger continuam restritos ao contexto de QA. O serviço
usa trust exclusivamente para teste; não valida autenticação SCRAM ou senhas
do ambiente de produção.

## Critérios e evidências

| Critério                          | Cobertura                                                                                        |
| --------------------------------- | ------------------------------------------------------------------------------------------------ |
| Banco vazio e migrações completas | `DatabaseRoleMigrationIntegrationTest` e startup Flyway do job v2                                |
| FKs e cascatas                    | `DatabaseMappingIntegrationTest`, `ApplicationConsistencyIntegrationTest` e sentinela da coleção |
| Decimal exato e faixas legadas    | `DatabaseMappingIntegrationTest`, `ManualJobDetailsIntegrationTest` e atualização V5→V7          |
| JSONB                             | Persistência de `ImportTask.extractedData` em `DatabaseMappingIntegrationTest`                   |
| Concorrência PostgreSQL           | Duas threads/sessões na criação de candidaturas e testes de consumo de token                     |
| Migrações imutáveis               | Gate novo, seis testes de Git e [política](migracoes.md)                                         |
| Preservação em atualização        | Usuário/candidatura V5 e salário legado mantidos nos testes de migração                          |
| Contrato HTTP v2                  | 97 itens + seis consultas auxiliares; 251 asserções, rollback SQL e limpeza                      |
| H2 complementar                   | Job `backend-build-test` mantido; não substitui os jobs PostgreSQL                               |

O job v2 empacota com `-DskipTests`; os testes Java continuam nos jobs próprios.
Não somar resultados unitários, HTTP e de migração como se fossem o mesmo tipo
de verificação. O conjunto de execução Java pode incluir testes unitários sem
banco e o teste independente de migração salarial em H2.

## Artefatos e falhas

- Política: `politica-migracoes-<sha>`, com resumo do diff e resultado.
- API v2: `testes-postman-v2-<sha>`, somente resumos sanitizados JSON.
- Relatórios Java e JAR continuam nos artefatos dos três jobs anteriores.
- Retenção de sete dias; relatórios publicados com `if: always()` quando existem.
- Logs completos da API, ambientes exportados, cookies, tokens e senhas não
  entram nos artefatos novos. O executor mantém diagnóstico detalhado apenas no
  diretório temporário local do runner, sem publicá-lo.

Falha de gate ou de coleção retorna código não zero. Não há publicação em
workspace Postman nem exigência de POSTMAN_API_KEY. O fluxo é de validação;
deploy continua separado.

## Reproduzir no Windows

Com as dependências v2 já instaladas:

```powershell
node --test scripts/check-migrations.test.cjs
$base = (git rev-parse main).Trim()
node scripts/check-migrations.cjs $base
./scripts/test-postman-v2.ps1 -UseCiRunner
./scripts/test-database-roles.ps1
```

O parâmetro `-UseCiRunner` cria um PostgreSQL novo pelo wrapper e invoca o mesmo
Node.js usado no job Ubuntu. Sem o parâmetro, o roteiro Windows da KAN-33
continua disponível e usa os mesmos SQLs de sentinela/limpeza.

Validação local do executor não comprova execução do serviço Docker nem dos
Actions no runner Ubuntu. Para fechar o aceite, abrir o PR, registrar o commit,
confirmar os cinco checks verdes e baixar os artefatos. Após os checks novos
aparecerem e serem aprovados, conferir sua exigência na proteção da main.
Nenhuma regra do GitHub foi alterada automaticamente.

## Evidência local — 05/11/2026

Ambiente Windows 11, Corretto 25.0.4.1, Spring Boot 4.0.9, Node.js 22.16.0 e
PostgreSQL 18.6. Resumo sanitizado: [kan-34-validacao-local.json](evidencias/kan-34-validacao-local.json).

| Execução                                     | Resultado                                                                                  |
| -------------------------------------------- | ------------------------------------------------------------------------------------------ |
| Política de migrações                        | 6 testes aprovados; alterações proibidas simuladas e rejeitadas; repositório real aprovado |
| Java com PostgreSQL e roles                  | 195 testes aprovados, zero falhas/erros/ignorados; atualização V5→V7 e instalação limpa    |
| Executor portável do CI, via wrapper Windows | 103 chamadas, 251 asserções aprovadas; limpeza, sentinela e parada da API confirmadas      |
| Modo Windows padrão após compartilhar SQLs   | 103 chamadas, 251 asserções aprovadas; limpeza e sentinela confirmadas                     |
| Guardas v2                                   | 9 testes aprovados em cada roteiro                                                         |
| Workflow                                     | YAML válido, gatilhos/permissões/referências revisados; execução remota pendente           |
| V1 e migrações V1–V7                         | Preservadas; 9 hashes da fixture conferidos antes/depois da v2                             |

O resultado local do executor portável não é um resultado observado do GitHub
Actions. Não foram executados Docker/Ubuntu nesta máquina nem alteradas regras
do repositório remoto. Os clusters locais foram encerrados pelos wrappers.
