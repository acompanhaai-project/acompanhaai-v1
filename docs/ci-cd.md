# Integração e entrega contínuas

Relacionamento: KAN-23 e evoluções de DevOps. Revisado em 03/11/2026.
**Estado: CI implementado; deploy pendente.** A configuração está em
[Backend CI](../.github/workflows/backend-ci.yml). Os jobs de H2 e PostgreSQL
já existem; a KAN-29 acrescenta isolamento de privilégios, cuja execução remota
ainda depende do PR. A documentação não comprova a aprovação do commit atual.

## Pipeline do backend

Fonte: `.github/workflows/backend-ci.yml`.

| Item              | Configuração implementada                                |
| ----------------- | -------------------------------------------------------- |
| Provedor          | GitHub Actions                                           |
| Gatilhos          | PR para main, push em main e execução manual             |
| Runtime           | Amazon Corretto JDK 25, ubuntu-latest                    |
| Build             | Maven Wrapper versionado                                 |
| Permissão inicial | Leitura do conteúdo; ampliar somente se necessário       |
| Bancos            | H2 em memória e PostgreSQL 18 efêmero, em jobs separados |
| Resultado         | Build/testes, relatórios e JAR identificados pelo commit |

Os três jobs fazem checkout sem persistir credenciais, configuram Java com
cache Maven, habilitam execução de mvnw e executam verify com datasource
explícito. Cada job tem limite de 15 minutos; os artefatos têm retenção de 7 dias.

| Job                     | Check publicado                 | Validação e artefatos                                                              |
| ----------------------- | ------------------------------- | ---------------------------------------------------------------------------------- |
| build-test              | backend-build-test              | Suíte H2; relatórios testes-COMMIT e JAR backend-COMMIT                            |
| postgres-test           | backend-postgres-test           | Suíte PostgreSQL, inclusive migrações; relatórios testes-postgres-COMMIT           |
| database-role-isolation | backend-database-role-isolation | Provisionamento, migrações e runtime restrito; relatórios testes-permissoes-COMMIT |

COMMIT representa github.sha no nome dos artefatos. Relatórios Surefire XML e
texto são publicados com if: always(), se tiverem sido gerados. O JAR
target/acompanhaai-0.0.1-SNAPSHOT.jar é publicado somente após sucesso do job H2.
Os jobs são independentes: esse artefato não comprova aprovação dos outros checks.

O job de isolamento usa um serviço PostgreSQL próprio com autenticação trust
exclusivamente de teste e porta publicada no loopback do runner. A primeira
etapa valida adoção/instalação limpa como administrador de QA; a segunda usa
acompanhaai_app no datasource e acompanhaai_migrator no Flyway. Essa validação
de privilégios não testa senhas reais nem autenticação SCRAM do ambiente local.
Consultar [credenciais](credenciais.md) para configuração, rotação e limitações.

Os jobs H2/PostgreSQL tradicionais não habilitam kan29.isolated. Os seis novos
testes condicionais só executam no job de isolamento, sem serem considerados
aprovados quando ignorados nos outros jobs. A coleção Postman não é executada
por esse workflow e segue seu [procedimento de qualidade](qualidade.md).

## Consultar execuções

No PR, abrir Checks e conferir os três resultados. Em Actions → Backend CI,
selecionar a execução do commit para consultar logs e baixar Artifacts.
Para executar manualmente, usar Run workflow e selecionar a branch. Um push
isolado para branch de trabalho sem PR não aciona o gatilho push limitado à main.
Registrar no cartão URL da execução, commit, resultado e evidência sanitizada.

Não copiar segredos locais, diretório H2 ou relatórios Postman para o runner.
O perfil test suprime dumps automáticos do MockMvc e logs informativos do
GreenMail para reduzir exposição de dados de teste em falhas. Revisar os
relatórios antes de compartilhar; isso não garante ausência de dados sensíveis
em qualquer mensagem de erro futura.

## Proteção da main

A proteção foi informada como ativada pelo usuário com backend-build-test
obrigatório. Conferir se backend-postgres-test também é exigido e, após a primeira
execução aprovada do novo job, incluir backend-database-role-isolation conforme
a política de proteção. As regras atuais do GitHub não foram consultadas aqui.

Continuar exigindo PR, resolução de conversas e bloqueio de force push e
exclusão. No trabalho individual, zero aprovações externas permite manter
a autorrevisão sem bloquear todos os PRs.

## Validação do KAN-23

1. Abrir PR com workflow e acompanhar uma execução bem-sucedida.
2. Registrar URL da execução, commit, resultado e artefatos sem segredos.
3. Em uma branch de demonstração, introduzir uma falha controlada em teste.
4. Confirmar execução vermelha e bloqueio de merge pelo check obrigatório.
5. Corrigir a falha antes de qualquer merge e demonstrar nova execução verde.
6. Revisar logs/artefatos para garantir que não incluam credenciais.

O teste falhando é um exercício de validação do gate, não um defeito real
do produto a cadastrar como Bug. Não deixá-lo na main.

## Evolução por etapa

- PostgreSQL efêmero, migrações e comportamento específico já estão no workflow;
  isso não encerra automaticamente todos os critérios da KAN-34.
- KAN-41: build, verificações e artefato Angular, quando o frontend existir.
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

Histórico informado na execução da KAN-23; não substitui os checks de novos
PRs. A alteração da KAN-29 foi validada localmente, sem execução remota nesta revisão.

- Pipeline executado com sucesso no PR.
- Check backend-build-test configurado como obrigatório na main.
- Teste temporário introduzido para provocar falha controlada.
- Check vermelho e bloqueio de merge confirmados.
- Teste temporário removido.
- Nova execução aprovada após a remoção.
- Relatórios de testes e JAR disponíveis na execução bem-sucedida.
