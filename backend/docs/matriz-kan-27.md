# KAN-27 — Matriz de segurança das contas em PostgreSQL

Cartão: [KAN-27](https://maikamaral123.atlassian.net/browse/KAN-27).
Revisão iniciada em 03/11/2026. Base: main `6653d00`, com os merges das
KAN-24, KAN-25 e KAN-26. Branch: test/KAN-27-matriz-seguranca-postgresql.
Os testes e o executor desta revisão são alterações locais sobre essa base.

## Critérios de aceite

1. Testar usuários A/B, múltiplas sessões, expiração, CSRF, e-mail duplicado e exclusão.
2. Usar banco de testes isolado, preservando os dados do banco de desenvolvimento.
3. Registrar relatórios, ambiente, versão e reprodução para as falhas encontradas.

Os testes existentes foram revisados e incluídos na regressão. Aprovação anterior
em outra tarefa não substitui a evidência desta rodada. Uma lacuna de cobertura
não demonstra por si só uma vulnerabilidade da aplicação.

## Matriz de rastreabilidade

Os nomes abreviados abaixo correspondem a classes em src/test/java/com/maiconamaral/acompanhaai.

| ID  | Critério/cenário                                                        | Testes que o demonstram                                                                                                                                                                                                                                                                                                                                                                |
| --- | ----------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| S01 | A/B: consulta e alteração apenas da própria conta                       | UserAuthenticationIntegrationTest.userCanReadAndUpdateOnlyOwnAccount; tentativa de indicar ID de B não altera B                                                                                                                                                                                                                                                                        |
| S02 | A/B: fotos e candidaturas privadas                                      | UserPhotoIntegrationTest.twoAccountsReadReplaceAndDeleteOnlyTheirOwnPhoto; ProfileAndApplicationIntegrationTest.statusFilterPaginationAndOwnershipAreEnforced                                                                                                                                                                                                                          |
| S03 | Múltiplas sessões: troca de senha e recuperação revogam sessões antigas | UserAuthenticationIntegrationTest.passwordChangeRequiresCurrentPasswordAndRevokesAllSessions; PasswordResetConsumptionIntegrationTest.resetRevokesAllOldOwnerSessionsPreservesOthersAndRequiresNewPasswordForLogin                                                                                                                                                                     |
| S04 | Logout invalida somente a sessão atual                                  | AccountSessionHttpIntegrationTest.loginRotatesSessionAndCsrfAndLogoutPreservesAnotherSessionOfSameAccount, com dois clientes da mesma conta                                                                                                                                                                                                                                            |
| S05 | Sessão expira por inatividade e outra conta permanece ativa             | AccountSessionHttpIntegrationTest.inactivityExpiresOnlyTheIdleSessionAndAllowsNormalLoginAgain                                                                                                                                                                                                                                                                                         |
| S06 | Token de recuperação expira e não pode ser reutilizado                  | PasswordResetConsumptionIntegrationTest.expiryBoundaryAndExpiredTokensAreRejected e reusedTokenCannotChangePasswordAgain; PasswordResetHttpIntegrationTest.invalidExpiredAndReusedTokensReturnSameGenericProblem                                                                                                                                                                       |
| S07 | CSRF: ausência, token inválido, rotação e vínculo com sessão            | UserAuthenticationIntegrationTest.loginWithoutCsrfIsRejectedAndDoesNotAuthenticate, logoutWithoutCsrfIsRejectedAndKeepsSessionAuthenticated, passwordChangeWithoutCsrfIsRejectedAndPreservesPasswordAndSessions e accountDeletionWithoutCsrfIsRejectedAndPreservesAccountAndSession; UserPhotoIntegrationTest.anonymousAccessAndWritesWithoutValidCsrfDoNotChangePhoto; teste HTTP S04 |
| S08 | E-mail duplicado no cadastro e edição, inclusive por caixa              | UserAuthenticationIntegrationTest.registrationRejectsDuplicateEmailIgnoringCase e updateRejectsAnotherUsersEmail                                                                                                                                                                                                                                                                       |
| S09 | Exclusão revoga sessões de A e preserva B                               | UserAuthenticationIntegrationTest.deleteRemovesOwnAccountAndRevokesItsSessionsWithoutAffectingAnotherAccount                                                                                                                                                                                                                                                                           |
| S10 | Exclusão remove dados vinculados                                        | ProfileAndApplicationIntegrationTest.deletingAccountCascadesPhotoJobsApplicationsAndHistory; UserPhotoIntegrationTest.deletingAccountCascadesOnlyItsPhotoAndPreservesOtherAccount; PasswordResetTokenIntegrationTest.deletingUserRemovesOwnTokensAndPreservesAnotherUsersTokens                                                                                                        |
| S11 | Respostas não contêm senha/hash                                         | Cadastro, login, GET/PUT de perfil em UserAuthenticationIntegrationTest e helper de login do novo teste HTTP                                                                                                                                                                                                                                                                           |
| S12 | Banco isolado e relatórios reproduzíveis                                | scripts/test-security-postgres.ps1: cluster e banco próprios, sem parâmetro para URL de banco existente, com resumo e relatórios por execução                                                                                                                                                                                                                                          |

## Lacunas encontradas e trabalho aplicado

Não havia cenário demonstrando expiração real da sessão por inatividade no
container. Revogação por authVersion e expiração do token de recuperação já
possuíam testes, mas são mecanismos diferentes da expiração da sessão HTTP.
Também faltava um cenário HTTP confirmando que logout preserva outra sessão
da mesma conta.

[AccountSessionHttpIntegrationTest](../src/test/java/com/maiconamaral/acompanhaai/AccountSessionHttpIntegrationTest.java)
adiciona dois cenários sobre Tomcat real, em porta aleatória no loopback:

- Confere timeout inicial de 1800 segundos. Apenas a sessão A é ajustada para
  um segundo com uma referência do container obtida por listener de testes.
  Após inatividade, GET de A retorna 401, B continua com 200, as contas são
  preservadas e A consegue fazer login novamente. Não chama invalidate manualmente.
- Confere rotação do ID no login, rejeição do CSRF anterior, rejeição de token
  pertencente à outra sessão e logout somente de A, preservando a segunda
  sessão da mesma conta e sua versão de autenticação.

O listener é importado exclusivamente nessa classe via TestConfiguration.
Não há alteração do timeout, listeners, endpoints ou regras de produção.
Não foi necessário esperar 30 minutos: o teste verifica a configuração padrão
e exercita o mecanismo de expiração com intervalo reduzido somente na sessão A.

### Verificação preliminar em H2

```bash
./mvnw.cmd --batch-mode --no-transfer-progress verify "-Dtest=AccountSessionHttpIntegrationTest" "-Dspring.config.import=" "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan27-session;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 51.691 s
Finished at: 2026-11-03T01:52:46-03:00
```

## Executor PostgreSQL isolado no Windows

Requisitos: Java 25, Maven Wrapper e binários PostgreSQL instalados. O caminho
padrão é C:\Program Files\PostgreSQL\18\bin; outra instalação pode ser informada.
Não requer Docker, pgAdmin, senha real de banco ou alteração de serviço existente.

Na raiz do projeto, em PowerShell:

```powershell
.\scripts\test-security-postgres.ps1
# Se os binários estiverem em outro diretório:
.\scripts\test-security-postgres.ps1 -PostgresBin 'C:\caminho\PostgreSQL\18\bin'
```

O executor cria target/kan27-postgres-<GUID>, inicializa um cluster UTF-8,
seleciona porta efêmera em 127.0.0.1 e cria acompanhaai_kan27 com usuário
acompanhaai_qa. O datasource é gerado pelo executor e passado diretamente ao
Maven. O executor copia os YAMLs versionados para a pasta config da execução,
removendo somente o import de application-local.properties. O parâmetro
spring.config.location aponta exclusivamente para essa pasta, substituindo
os locais padrão. additional-location fica vazio e spring.application.json
é definido como objeto vazio para evitar sobreposições externas desses canais.
O script para antes de iniciar o banco caso encontre imports não previstos.
As exclusões feitas pela suíte atingem apenas a massa fictícia da instância criada.

Autenticação trust é usada somente no cluster temporário, vinculado ao loopback;
essa configuração não deve ser usada em produção. Há uma pequena janela entre
selecionar e abrir a porta; se ela for ocupada por outro processo, o start deve
falhar antes de criar banco ou executar testes, sem reutilizar o serviço alheio.

O finally encerra o PostgreSQL do diretório exclusivo iniciado por essa execução,
inclusive se Maven falhar. Os arquivos permanecem em target para diagnóstico,
sem exclusão recursiva. Cada execução gera:

- postgres.log: inicialização e operação da instância de testes.
- maven.log: saída do build e testes.
- reports/: relatórios Surefire XML e texto atualizados na execução.
- summary.json: versão PostgreSQL, porta, banco, instantes, código de saída e
  contagens por classe. Relatórios antigos não são usados para declarar sucesso.
- config/: cópia da configuração versionada, sem import do arquivo local privado.

O resumo não inclui credenciais, cookies, tokens, corpos de respostas ou hashes
de senha. Logs e relatórios detalhados ficam locais e devem ser revisados antes
de anexar trechos, especialmente em caso de falha. Usar o resumo e esta matriz
como evidência compartilhável.

## Registro de falhas e reteste

Para cada falha real encontrada, registrar: versão/base, cenário Sxx, ambiente,
pré-condições fictícias, requisição ou comando reproduzível, resultado esperado,
resultado obtido, impacto e relatório correspondente. O bug só deve ser fechado
após reteste do cenário e regressão pertinente. Não incluir sessão/token real,
senha pessoal nem configuração do banco de desenvolvimento.

Os dois pontos iniciais desta tarefa eram lacunas de evidência. Não foram
classificados como bugs de segurança: os testes novos passaram na execução H2.

O primeiro executor usava spring.config.import vazio na linha de comando.
Embora a regressão já tivesse passado, a revisão substituiu essa abordagem
pela cópia sem import e por spring.config.location exclusivo. As evidências
finais devem corresponder à nova execução, com esse isolamento reforçado.

### R01 — Sincronização do teste de entrega assíncrona

- Ambiente: Windows 11, Java 25.0.4.1, Maven 3.9.16, PostgreSQL 18.6;
  base `6653d00` com alterações locais desta tarefa.
- Reprodução: executar `.\scripts\test-security-postgres.ps1` na raiz.
- Cenário: PasswordResetMailIntegrationTest.fullHttpFlowReceivesEmailConsumesTokenRevokesSessionAndLogsInWithNewPassword,
  relacionado a S03, S06 e S11.
- Esperado: receber uma mensagem, consumir o token uma vez, revogar sessões
  antigas e permitir login somente com a senha nova.
- Obtido: a espera SMTP de cinco segundos terminou antes do recebimento.
  A rodada `cdd828cccb57451e8df673c5b695457a` registrou 98 testes, uma falha,
  nenhum erro e nenhum ignorado. Os relatórios estão em
  target/kan27-postgres-cdd828cccb57451e8df673c5b695457a/reports.
- Diagnóstico: a resposta HTTP 202 antecede o trabalho das filas de emissão/commit
  e de SMTP. A espera antiga incluía ambos os estágios na mesma janela de cinco
  segundos. O log registra atividade de recebimento após essa janela; a falha
  isolada não demonstra falha de segurança ou perda definitiva da mensagem.
- Correção: receivedToken aguarda primeiro os executores, em ordem, com limite
  de dez segundos por estágio. A sincronização exige fila vazia, nenhuma tarefa
  ativa e contagem de tarefas concluídas igual à de tarefas recebidas. Só então
  verifica exatamente uma mensagem entregue. Uma fila travada ou ausência de
  entrega continua reprovando o teste, com o estágio identificado na falha.
- As verificações de destinatário, remetente, URL confiável, hash, expiração,
  consumo único, revogação e senha nova foram preservadas. Não houve aumento
  de timeout nem alteração da lógica de e-mail em produção.
- Reteste: aprovado na rodada completa abaixo; os cinco testes da classe de
  e-mail passaram, incluindo o fluxo que falhou anteriormente.

## Evidências finais — 03/11/2026

Execução na branch test/KAN-27-matriz-seguranca-postgresql, base `6653d00`, com
os testes, executor, correção R01 e a formatação solicitada ainda locais.
Os YAMLs de QA foram conferidos sem import externo. A fixture Postman v1 e
as dependências congeladas tiveram seus hashes SHA-256 conferidos e preservados.

| Item                    | Resultado                                                |
| ----------------------- | -------------------------------------------------------- |
| Sistema                 | Windows 11 amd64                                         |
| Java                    | Amazon Corretto 25.0.4.1                                 |
| Maven Wrapper           | 3.9.16                                                   |
| Spring Boot             | 4.0.9                                                    |
| PostgreSQL              | 18.6, cluster temporário UTF-8                           |
| Banco e endereço de QA  | acompanhaai_kan27, 127.0.0.1:51879                       |
| Perfil                  | test, com cópia versionada sem import local              |
| Migrações               | V1–V6; upgrade V5→V6 e reaplicação sem mudança aprovados |
| Resultado               | 98 testes, 0 falhas, 0 erros, 0 ignorados; 15 classes    |
| Build                   | BUILD SUCCESS; código de saída 0                         |
| Tempo Maven             | 2 minutos e 53 segundos                                  |
| Término Maven           | 03/11/2026 às 02:26:48, America/Sao_Paulo                |
| Encerramento do cluster | Confirmado pelo executor; postmaster.pid ausente         |
| Rodada                  | 29c5bcc1e8ac46199498462891d83030                         |

Comando executado:

```powershell
.\scripts\test-security-postgres.ps1
```

O [resumo sanitizado da execução](evidencias/kan-27-postgres.json) registra o
resultado por classe, sem corpos HTTP, tokens ou credenciais. A soma das classes
confere com o resultado agregado. Os relatórios completos e logs locais estão
em target/kan27-postgres-29c5bcc1e8ac46199498462891d83030.

Os cenários S01–S12 da matriz tiveram evidência local aprovada. Isso inclui
expiração real de sessão, logout com outra sessão preservada, isolamento A/B,
CSRF, e-mail duplicado, recuperação, exclusão e concorrência no PostgreSQL.
O cenário SMTP indisponível provoca um aviso esperado para verificar sua
resposta genérica; esse aviso não representa falha da rodada.

Esta rodada reexecutou toda a suíte em PostgreSQL. A evidência H2 desta tarefa
continua limitada aos dois testes HTTP preliminares descritos acima.
CI remoto, revisão do PR e aceite do cartão continuam pendentes.

## Preservação e aceite

- Fixture Postman v1 e seu executor preservados; nenhum mock antigo reescrito.
- Testes anteriores mantidos e incluídos na regressão.
- Nenhuma mudança de produção planejada para esta tarefa de QA.
- Execução local não representa CI remoto nem aceite do cartão.
- Concluir KAN-27 somente após revisão das evidências, CI do PR e aceite.

## Referência de configuração

[Spring Boot 4.0 — Externalized Configuration](https://docs.spring.io/spring-boot/4.0/reference/features/external-config.html):
spring.config.location substitui os locais padrão; imports são processados
quando o documento que os declara é descoberto.
