# Acompanhamento de candidaturas — KAN-31

## Revisão da base

A base recebida da KAN-30 já possui os oito status, filtro por etapa,
paginação, ordenação por `updatedAt DESC, id DESC`, histórico transacional,
controle de versão e acesso por conta. A lacuna funcional desta história é a
busca limitada por título ou empresa. Os demais critérios são revalidados por
testes, incluindo falha na gravação do histórico.

## Contrato da listagem

```http
GET /api/applications?status=SCREENING&q=java&page=0&size=20
```

- Sessão autenticada obrigatória. A listagem e sua contagem incluem somente
  candidaturas da conta autenticada.
- `status` é opcional e aceita um dos oito valores do enum.
- `q` é opcional, com até 100 caracteres antes de remover espaços das extremidades.
  Busca um trecho do título **ou** do nome da empresa, usando `LOWER` no banco.
  Não remove acentos; a comparação segue a collation do banco.
- Termo omitido, vazio ou somente espaços desativa apenas o filtro textual.
  Espaços internos permanecem significativos. Não busca na descrição, habilidades
  ou observações.
- `%`, `_` e `!` são tratados literalmente, com escape `!` para o `LIKE`.
  O termo é vinculado por parâmetro; não é concatenado ao texto JPQL.
- Quando ambos existem, aplicar `status AND (título OR empresa)` junto com a
  condição obrigatória da conta, inclusive na consulta de contagem.
- Página começa em zero; tamanho padrão 20, mínimo 1 e máximo 100.
  Retorno mantém `content`, `page`, `size`, `totalElements` e `totalPages`.
- Ordenação fixa por atualização decrescente, com ID decrescente como desempate.
  Isso torna a paginação estável em uma base sem alterações entre as consultas;
  alterações concorrentes podem mover registros entre páginas.
- Termo acima do limite ou paginação inválida retorna 422; enum inválido retorna 400. Nenhuma correspondência retorna 200 com lista vazia.

## Etapas e histórico

Valores mantidos: `SAVED`, `APPLIED`, `SCREENING`, `INTERVIEW`, `OFFER`,
`HIRED`, `REJECTED` e `WITHDRAWN`. É permitido pular etapas e corrigir uma etapa,
inclusive depois de um desfecho. Não existe uma sequência obrigatória nesta versão.

POST cria o evento inicial com `fromStatus: null`. PUT exige o payload completo
e a versão recebida na consulta. Somente uma mudança de status cria novo evento;
alterar observações ou os dados da vaga mantém a quantidade de eventos.

Status e evento são gravados na mesma transação. Se a gravação do histórico
falhar, a alteração de status, versão e demais dados deve ser desfeita, mesmo
quando a atualização da candidatura já foi enviada ao banco pelo flush.

`GET /api/applications/{id}/history` mantém ordem crescente por data e ID,
incluindo o evento inicial. Consultar, alterar ou acessar histórico de uma
candidatura de outra conta retorna 404. PUT com versão antiga retorna 409.
`SAVED` com `appliedOn` informado retorna 422, sem alterar os dados existentes.

Os detalhes da vaga e a semântica de substituição do PUT seguem o
[contrato KAN-30](vaga-manual-kan-30.md). A estrutura do banco não muda nesta história.

## Matriz de critérios e testes

| Critério                                                       | Testes                                                                                                                                 |
| -------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| Criar, filtrar e atualizar os oito status                      | `createsFiltersAndRecordsEverySupportedStatus`, `supportsEveryDestinationAndDoesNotRecordUnchangedStatus` — enum parametrizado         |
| Combinar título/empresa, etapa e conta                         | `combinesTitleOrCompanySearchWithStatusWithoutLeakingOtherAccounts`                                                                    |
| Busca literal, tamanho e termos vazios                         | `searchesWildcardAndEscapeCharactersAsLiteralText`, `acceptsBlankAndMaximumQueryAndRejectsOversizedInput`                              |
| Paginação e desempate estáveis                                 | `paginationUsesIdAsTieBreakerAndSearchOnlyInspectsTitleAndCompany`                                                                     |
| Histórico estável, correção de etapas e observações sem evento | `allowsSkippingAndCorrectingStagesAndKeepsHistoryStableOnTimestampTies`                                                                |
| Falha no histórico reverte a atualização                       | `historyFailureRollsBackStatusVersionAndNotes` — falha simulada no repositório; persistência e rollback reais                          |
| SAVED não aceita data                                          | `savedWithApplicationDateIsRejectedWithoutChangingExistingData` e teste de criação existente em `ProfileAndApplicationIntegrationTest` |
| Versão antiga retorna 409 sem alterações                       | `staleUpdateIsRejectedWithoutChangingJobOrHistory` existente em `ProfileAndApplicationIntegrationTest`                                 |
| Acesso de outra conta retorna 404                              | Novo cenário de busca/isolamento e `statusFilterPaginationAndOwnershipAreEnforced` existente                                           |

Os novos testes estão em `ApplicationTrackingIntegrationTest`. A fixture Postman
v1 permanece congelada; coleção v2 pertence à KAN-33. Resultados devem ser
registrados após execução. CI remoto, revisão do PR e aceite permanecem pendentes.

## Compatibilidade H2/PostgreSQL

A primeira execução PostgreSQL identificou `LOWER(bytea)` quando a busca era
omitida: o parâmetro nulo recebeu um tipo inadequado no binding. A consulta
passou a receber sempre um padrão textual; sem termo, o serviço usa `%`.
Como o título é obrigatório e não nulo no banco, esse padrão mantém todos os
registros da conta que atendem ao filtro de etapa. Um `%` digitado pelo usuário
continua escapado e literal. A verificação inclui listagens sem termo, termos
vazios e busca combinada, nas duas bases.

## Reprodução e evidências

Ambiente local de 03/11/2026: Java Corretto 25.0.4.1, Spring Boot 4.0.9,
Maven Wrapper 3.9.16. As configurações de teste usam apenas YAML versionados,
sem importar os arquivos locais de credenciais.

Para H2, copiar `src/main/resources/application.yml` e
`src/test/resources/application-test.yml` para `target/kan31-h2-config/`, remover
apenas o bloco `spring.config.import` da cópia do primeiro arquivo e executar:

```powershell
./mvnw.cmd --batch-mode --no-transfer-progress verify "-Dspring.config.location=file:./target/kan31-h2-config/" "-DargLine=-Xms32m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m"
```

Para PostgreSQL 18.6 no Windows:

```powershell
./scripts/test-database-roles.ps1
```

O executor provisiona um cluster descartável em `target`, usa as contas
separadas da KAN-29 e encerra a instância ao final. Os relatórios de cada rodada
ficam no diretório exclusivo impresso pelo script. Executar diretamente no
terminal, sem redirecionar a saída completa do script no Windows.

Resultado da execução final, após corrigir o binding do parâmetro:

| Execução                            | Resultado                                                                                      |
| ----------------------------------- | ---------------------------------------------------------------------------------------------- |
| H2 2.4.240                          | 155 encontrados: 148 aprovados e 7 exclusivos de PostgreSQL ignorados; zero falhas/erros       |
| PostgreSQL 18.6 com roles separadas | 2 testes de migração/provisionamento + 152 de regressão aprovados; zero falhas/erros/ignorados |
| Novos cenários da KAN-31            | 25 aprovados em cada execução, usando MockMvc e persistência real                              |
| Fixture Postman v1                  | 9 hashes SHA-256 conferidos; coleção não executada nesta rodada                                |

O total 154 da execução PostgreSQL inclui um teste independente em H2 de
migração salarial; os outros 153 usam PostgreSQL. A falha do histórico é
simulada no repositório com Mockito, e o rollback é verificado na persistência.
As demais gravações e consultas dessa suíte usam os repositórios reais.

Resumo sanitizado: [evidência local](evidencias/kan-31-validacao-local.json).
As duas rodadas PostgreSQL encerraram seus clusters descartáveis. V1–V7 e a
fixture v1 permanecem intactas. CI remoto, revisão do PR e aceite ainda pendentes.
