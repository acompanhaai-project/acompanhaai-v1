# Integração e entrega contínuas

Após a separação dos módulos, os comandos e caminhos relativos de backend
deste guia partem de `backend/`. O workflow fica na raiz em `.github/workflows/`;
ações de cache/upload usam caminhos `backend/...` relativos ao checkout.

Relacionamento: KAN-23, KAN-29 e KAN-34. Revisado em 05/11/2026.
**Estado: CI do backend implementado; deploy pendente.** O workflow
[Backend CI](../.github/workflows/backend-ci.yml) executa build e testes
em H2 e PostgreSQL, publica relatórios e disponibiliza o JAR após sucesso
do job H2. Não publica a aplicação em homologação ou produção.
Na KAN-34 foram acrescentados os checks de migrações imutáveis e regressão
Postman v2. A configuração e o executor foram validados localmente; a execução
remota dos novos jobs deve ser conferida no PR antes do aceite.

## Pipeline do backend

Fonte da configuração: `.github/workflows/backend-ci.yml`.

| Item      | Configuração implementada                                                 |
| --------- | ------------------------------------------------------------------------- |
| Provedor  | GitHub Actions                                                            |
| Gatilhos  | PR para `main`, push em `main` e execução manual                          |
| Runtime   | Amazon Corretto JDK 25 em `ubuntu-latest`                                 |
| Build     | Maven Wrapper versionado                                                  |
| Permissão | `contents: read`; checkout sem persistir credenciais                      |
| Bancos    | H2 em memória e quatro serviços PostgreSQL 18 efêmeros, em jobs separados |
| Limite    | 15 minutos por job; navegador 30 minutos; política de migrações 5 minutos |
| Retenção  | Relatórios, capturas e JAR mantidos por 7 dias                            |
| Resultado | Build/testes, relatórios e JAR identificados pelo commit                  |

Os três jobs Java fazem checkout, configuram Java com cache Maven, habilitam
a execução de `mvnw` no Linux e executam:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

O workflow acrescenta as propriedades de conexão específicas de cada banco.
A fase verify compila, executa os testes e empacota a aplicação.

| Job                       | Check publicado                   | Validação e artefatos                                                                                                                         |
| ------------------------- | --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `build-test`              | `backend-build-test`              | Suíte em H2; relatórios `testes-${{ github.sha }}` e JAR `backend-${{ github.sha }}`                                                          |
| `postgres-test`           | `backend-postgres-test`           | Suíte em PostgreSQL 18, incluindo migrações e testes específicos; relatórios `testes-postgres-${{ github.sha }}`                              |
| `database-role-isolation` | `backend-database-role-isolation` | Provisionamento, migrações e runtime restrito; relatórios `testes-permissoes-${{ github.sha }}`                                               |
| `migration-policy`        | `backend-migration-policy`        | Rejeita alteração/exclusão/renomeação de migrações existentes; `politica-migracoes-${{ github.sha }}`                                         |
| `postman-v2`              | `backend-postman-v2`              | API real, PostgreSQL próprio e coleção v2; resumos `testes-postman-v2-${{ github.sha }}`                                                      |
| `browser-qa`              | `backend-browser-qa`              | Jornadas Selenium no Chrome com Angular, API e PostgreSQL 18 reais; resumo no job, relatórios e capturas `testes-navegador-${{ github.sha }}` |

O H2 usa banco em memória com modo de compatibilidade PostgreSQL. Isso não
substitui a validação no PostgreSQL real. O outro job inicia um serviço
PostgreSQL 18 com healthcheck, banco descartável e porta mapeada dinamicamente.
As credenciais desse serviço são exclusivamente de CI, sem acesso ao banco real.

Os relatórios Surefire XML e texto são enviados com `if: always()`, inclusive
quando testes falham, desde que tenham sido gerados. O JAR
`target/acompanhaai-0.0.1-SNAPSHOT.jar` é publicado somente após sucesso do job H2.
Como os jobs são independentes, a existência desse JAR não comprova aprovação
dos demais jobs: conferir os seis checks antes de aprovar uma release.

O job de isolamento usa serviço PostgreSQL próprio, com autenticação trust
exclusivamente de teste e porta publicada no loopback do runner. A primeira
etapa valida adoção/instalação limpa como administrador de QA; a segunda usa
acompanhaai_app no datasource e acompanhaai_migrator no Flyway. Essa validação
de privilégios não testa senhas reais nem autenticação SCRAM do ambiente local.
Consultar [credenciais](../backend/docs/credenciais.md) para configuração, rotação e limitações.

Os jobs H2/PostgreSQL tradicionais não habilitam kan29.isolated. Os seis novos
testes condicionais só executam no job de isolamento, sem serem considerados
aprovados quando ignorados nos outros jobs.

O novo job Postman usa Java 25, Node.js 22, `npm ci` no pacote exclusivo da v2
e seu próprio PostgreSQL 18. Empacota o JAR com `-DskipTests`, pois os três jobs
Java continuam responsáveis pela suíte Java. O executor portável
`scripts/test-postman-v2-ci.cjs` confirma um banco vazio de QA, provisiona roles,
inicia sua API, aplica V1–V7 pelo Flyway e executa a coleção com o trigger de
rollback e a sentinela da KAN-33. Confere limpeza por IDs e encerra sua API em
`finally`. O serviço PostgreSQL é encerrado pelo GitHub ao terminar o job.

Esse job depende da aprovação de `migration-policy`. Publica somente resumos
JSON sanitizados com `if: always()`, sem ambiente exportado, relatório bruto do
Newman ou logs completos da API. Não usa POSTMAN_API_KEY nem publica recursos
em um workspace compartilhado. Ver [regressão KAN-33](../backend/docs/regressao-api-v2-kan-33.md)
e [matriz KAN-34](../backend/docs/ci-postgresql-kan-34.md).

O job de navegador instala o frontend com `npm ci`, sobe a API e o Angular
pela própria suíte e usa o Chrome do runner, com driver resolvido pelo Selenium
Manager. O PostgreSQL 18 é exclusivo do job, com autenticação trust somente de
teste. O resumo por cenário vai para a página do job; o artefato traz os
relatórios JUnit, as capturas sem senha e a versão do ambiente, sem o log do
Angular nem a saída completa da API. Detalhes no [executor de QA](../qa/browser/README.md).

Não copiar segredos locais ou dados de desenvolvimento para o runner.
Antes de compartilhar relatórios de falhas, revisar seu conteúdo: eles podem
incluir requisições e dados fictícios usados nos testes.
O perfil test suprime dumps automáticos do MockMvc e logs informativos do
GreenMail para reduzir exposição de dados de teste em falhas. Isso não garante
ausência de dados sensíveis em qualquer mensagem de erro futura.

## Consultar execuções e artefatos

1. No GitHub, abrir o PR e consultar os seis checks em Checks ou abrir
   Actions → Backend CI e selecionar a execução do commit desejado.
2. Abrir o job para consultar etapas, erros e resultado do Maven.
3. Na página da execução, consultar Artifacts para baixar relatórios e JAR.
4. Registrar URL da execução, commit e resultado na tarefa correspondente.

Para disparar manualmente, abrir Actions → Backend CI → Run workflow e
selecionar a branch. Um push apenas para uma branch de trabalho sem PR aberto
não corresponde ao gatilho push, que está limitado à main.

## Proteção da main

O registro de configuração indica proteção ativa, com backend-build-test
obrigatório. O workflow também publica backend-postgres-test; conferir se esse
check foi adicionado à regra para que falhas no PostgreSQL bloqueiem o merge.
A regra atual do GitHub não foi consultada nesta atualização documental.
Após aprovar a primeira execução do novo job, conferir também a exigência de
backend-database-role-isolation na proteção da main, conforme a política do projeto.
Após os novos checks da KAN-34 aparecerem e ficarem verdes, conferir também
`backend-migration-policy` e `backend-postman-v2`. A configuração da regra permanece como procedimento administrativo separado.

Continuar exigindo PR, resolução de conversas e bloqueio de force push e
exclusão. No trabalho individual, zero aprovações externas permite manter
a autorrevisão sem bloquear todos os PRs.

## Roteiro de validação do bloqueio de merge

1. Abrir PR com workflow e acompanhar uma execução bem-sucedida.
2. Registrar URL da execução, commit, resultado e artefatos sem segredos.
3. Em uma branch de demonstração, introduzir uma falha controlada em teste.
4. Confirmar execução vermelha e bloqueio de merge pelo check obrigatório.
5. Corrigir a falha antes de qualquer merge e demonstrar nova execução verde.
6. Revisar logs/artefatos para garantir que não incluam credenciais.

O teste falhando é um exercício de validação do gate, não um defeito real
do produto a cadastrar como Bug. Não deixá-lo na main.

## CI do frontend — KAN-41

O [workflow do frontend](../.github/workflows/frontend-ci.yml) executa o check
`frontend-build-test` em PRs para a `main`, em pushes à `main` e sob demanda.
Ele instala pelo lockfile e roda formatação, lint, testes Angular, build de
produção e verificação do bundle, nessa ordem. O cache do npm usa o lockfile
do frontend. O check roda em todos os PRs para poder ser exigido na proteção
da branch.

Após as verificações, o workflow publica `frontend-<sha>-<tentativa>` com os
arquivos de `frontend/dist/acompanhaai-web/browser`, por sete dias. Uma falha
anterior impede o upload. A verificação do bundle identifica padrões conhecidos
de chaves e senhas e arquivos como `.env`. O relatório informa arquivo e
categoria, sem imprimir o valor encontrado.

Para repetir os comandos do check na pasta `frontend/`, com Node.js 22:

```bash
npm ci --no-audit --no-fund
npm run format:check
npm run lint
npm run test:ci
npm run build
npm run check:bundle
```

Para conferir o frontend com a API, inicie o backend no perfil `dev` e o
Angular com `npm start` em outro terminal. O Angular encaminha `/api` para
`127.0.0.1:8080`. Veja os passos no [README do frontend](../frontend/README.md).
A validação Selenium com PostgreSQL segue o [executor de QA](../qa/browser/README.md).

Depois que o primeiro PR exibir o check, adicione `frontend-build-test` à
regra que exige checks na `main`. A regra é o que impede o merge se o build
falhar. Use o roteiro de falha controlada acima para validar o bloqueio.

## Evolução por etapa

- KAN-34: política imutável, regressão v2 no CI e validação local implementadas;
  comprovação da execução remota e aceite ainda pendentes.

- PostgreSQL efêmero, migrações e comportamento específico já estão incluídos
  no CI. Essa implementação não encerra automaticamente os critérios da KAN-34.
- KAN-41: workflow do frontend implementado. Execução remota e proteção da
  `main` aguardam validação.
- Homologação: artefato imutável promovido para ambiente separado.
- Produção: aprovação da release, migração, implantação, smoke e recuperação.

Não regenerar ou atualizar a fixture v1 no pipeline. Regredir o contrato novo
com v2 e registrar incompatibilidades observadas na v1 sem escondê-las.

## Entrega e segredos

Destino de hospedagem, domínio e contas de serviço ainda serão definidos.
Credenciais devem ficar nos mecanismos de segredo do ambiente de execução,
com permissões mínimas. Deploy não deve ser executado por PR não confiável.

Não há publicação automática implementada nem contratação de infraestrutura
autorizada por este documento. O procedimento futuro está em
[operação](operacao.md).

## Evidências de validação — KAN-23

Os itens abaixo registram a validação informada durante a KAN-23. Esta revisão
conferiu o arquivo do workflow, sem disparar uma nova execução remota ou
revalidar a proteção da main. A aprovação de cada novo PR depende de seus checks.
A KAN-29 registra validação local e smoke na API no guia de credenciais;
esses resultados não representam uma execução remota do novo job.

- Pipeline executado com sucesso no PR.
- Check backend-build-test configurado como obrigatório na main.
- Teste temporário introduzido para provocar falha controlada.
- Check vermelho e bloqueio de merge confirmados.
- Teste temporário removido.
- Nova execução aprovada após a remoção.
- Relatórios de testes e JAR disponíveis na execução bem-sucedida.
