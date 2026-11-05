# KAN-24 — Matriz de critérios, testes e evidências

Tarefa: [Autenticar e gerenciar a própria conta com segurança](https://maikamaral123.atlassian.net/browse/KAN-24).
Revisão: 02/11/2026. Base inspecionada: `36561ef`.

## Como ler a matriz

- **Comprovado no cenário:** existe uma asserção para o comportamento e a
  classe passou na execução informada pelo usuário.
- **Parcial:** há evidência, mas falta verificar parte do requisito.
- **Pendente:** não há evidência de execução para o cenário indicado.

Um teste aprovado comprova as condições verificadas por suas asserções.
Uma lacuna de teste não demonstra, por si só, um defeito da aplicação.
Esta matriz não representa uma auditoria completa de segurança.

## Critérios de aceite do Jira

| ID  | Critério                                                                                                       |
| --- | -------------------------------------------------------------------------------------------------------------- |
| CA1 | Revalidar cadastro, login, logout, consulta, edição e exclusão da própria conta; e-mail duplicado retorna 409. |
| CA2 | Com login válido, renovar sessão e CSRF; sem sessão retornar 401 e escrita sem CSRF retornar 403.              |
| CA3 | Troca de senha exige senha atual e invalida outras sessões; respostas não expõem senha/hash.                   |

## Catálogo dos testes existentes

Classe: [UserAuthenticationIntegrationTest.java](../src/test/java/com/maiconamaral/acompanhaai/UserAuthenticationIntegrationTest.java).
T01 a T14 abaixo são identificadores locais desta matriz, não cartões Jira.

| ID  | Método                                                                       | Verificação principal                                                                                   |
| --- | ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| T01 | `registrationHashesPasswordNormalizesEmailAndDoesNotExposeSecrets`           | Cadastro 201, e-mail normalizado, campos de senha ausentes e hash válido no banco.                      |
| T02 | `registrationRejectsDuplicateEmailIgnoringCase`                              | Duplicidade por caixa do e-mail retorna 409; permanece uma conta.                                       |
| T03 | `invalidFieldsAndMalformedJsonAreRejected`                                   | Entrada inválida retorna 422, JSON malformado retorna 400 e não há gravação.                            |
| T04 | `protectedRoutesRequireAuthenticationAndWritesRequireCsrf`                   | Consulta anônima retorna 401; cadastro e edição sem CSRF retornam 403.                                  |
| T05 | `loginReturnsGenericErrorForWrongPasswordAndUnknownEmail`                    | Login inválido retorna 401 e mensagem genérica para conta conhecida ou desconhecida.                    |
| T06 | `realCsrfFlowRotatesSessionAndTokenOnLogin`                                  | Fluxo real de CSRF, rotação do ID da sessão, rejeição do token antigo e logout com token novo.          |
| T07 | `userCanReadAndUpdateOnlyOwnAccount`                                         | Consulta/edição sem senha/hash e tentativa de indicar o ID de B no JSON sem alterar B.                  |
| T08 | `updateRejectsAnotherUsersEmail`                                             | Edição para e-mail pertencente a outra conta retorna 409.                                               |
| T09 | `passwordChangeRequiresCurrentPasswordAndRevokesAllSessions`                 | Senha atual obrigatória, troca válida, revogação das sessões e login com a nova senha.                  |
| T10 | `deleteRemovesOwnAccountAndRevokesItsSessionsWithoutAffectingAnotherAccount` | A removida e suas sessões revogadas; B preservada com sessão ativa e novo login válido.                 |
| T11 | `loginWithoutCsrfIsRejectedAndDoesNotAuthenticate`                           | Credenciais válidas sem CSRF recebem 403; sessão anônima continua sem acesso.                           |
| T12 | `logoutWithoutCsrfIsRejectedAndKeepsSessionAuthenticated`                    | Logout sem CSRF recebe 403 e a sessão continua autenticada.                                             |
| T13 | `passwordChangeWithoutCsrfIsRejectedAndPreservesPasswordAndSessions`         | Troca sem CSRF recebe 403; hash, senha antiga e duas sessões preservados; senha proposta não autentica. |
| T14 | `accountDeletionWithoutCsrfIsRejectedAndPreservesAccountAndSession`          | Exclusão sem CSRF recebe 403; conta e sessão permanecem utilizáveis.                                    |

O auxiliar `login` verifica 200 e ausência de `password` e `passwordHash`.
O auxiliar `register` envia POST com JSON e CSRF. Cadastro, login, consulta e
edição usam `doesNotHaveJsonPath()`: os campos não podem existir nem com null.

## Matriz de rastreabilidade

| Critério/cenário                  | Testes                      | Evidência e resultado                                                                                                                              | Avaliação                            |
| --------------------------------- | --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| CA1 — cadastro válido             | T01                         | 201, Location correto, e-mail e data retornados; hash conferido no banco.                                                                          | Comprovado no cenário                |
| CA1 — login válido e inválido     | T05, T06 e auxiliar `login` | Login válido retorna 200 e permite consulta; inválidos retornam 401 genérico.                                                                      | Comprovado no cenário                |
| CA1 — logout                      | T06                         | Token antigo retorna 403; token novo permite logout 204 e sessão fica inválida.                                                                    | Comprovado no cenário                |
| CA1 — consulta da própria conta   | T07                         | Retorna 200 e e-mail de A usando a sessão de A.                                                                                                    | Comprovado no cenário                |
| CA1 — edição e isolamento         | T07, T08                    | A edita o próprio e-mail, mesmo enviando ID de B; B preserva o e-mail; duplicidade retorna 409.                                                    | Comprovado nos cenários existentes   |
| CA1 — exclusão da própria conta   | T10                         | A é removida, suas sessões são revogadas e novo login de A falha; B permanece no banco, consulta o perfil com a sessão existente e faz novo login. | Comprovado no cenário após L3        |
| CA1 — e-mail duplicado            | T02, T08                    | Cadastro com variação de caixa e edição conflitante retornam 409.                                                                                  | Comprovado nos cenários existentes   |
| CA2 — rotação da sessão e do CSRF | T06                         | ID muda após login; token anterior deixa de funcionar e token novo permite escrita.                                                                | Comprovado no cenário                |
| CA2 — acesso sem sessão           | T04                         | GET /api/users/me sem sessão retorna 401. Outros endpoints protegidos não são enumerados nesta classe.                                             | Comprovado no cenário representativo |
| CA2 — escrita sem CSRF            | T04, T11–T14                | Cadastro, edição, login, logout, troca de senha e exclusão retornam 403 sem CSRF. T11–T14 também verificam a ausência dos efeitos rejeitados.      | Comprovado nos cenários após L2      |
| CA3 — exigir senha atual          | T09                         | Senha atual errada retorna 401; correta permite troca 204.                                                                                         | Comprovado no cenário                |
| CA3 — revogar sessões após troca  | T09                         | Sessão atual inválida; segunda sessão recebe 401; senha antiga falha e nova funciona.                                                              | Comprovado no cenário                |
| CA3 — respostas sem senha/hash    | T01, T07 e auxiliar `login` | Cadastro, login, consulta e edição verificam explicitamente a ausência de password e passwordHash.                                                 | Comprovado nos cenários após L1      |
| Complementar — entradas inválidas | T03                         | 400/422 e ausência de gravação.                                                                                                                    | Comprovado no cenário                |

## Acompanhamento das lacunas de cobertura

### L1 — Ausência de segredos em todas as respostas de conta — resolvida

Relacionada ao CA3. Implementada e validada em 02/11/2026.

As respostas de login (auxiliar `login`), consulta e edição (T07) agora
verificam `password` e `passwordHash`. As verificações existentes no cadastro
(T01) também foram fortalecidas. Nenhum método de teste novo foi necessário.

Usamos `doesNotHaveJsonPath()` para exigir ausência da propriedade, inclusive
quando ela teria valor null. Resultado: os dez testes existentes passaram
com as novas asserções. Não foi necessário alterar o backend de produção.

O `UserDTO` continua contendo apenas id, nome, e-mail, data e URL de foto.

### L2 — CSRF nas demais operações de escrita de conta — resolvida

Relacionada ao CA2. Implementada e validada em 02/11/2026.

Foram adicionados quatro testes (T11–T14), preparando a sessão apropriada
e omitindo o CSRF, com resultado 403 para:

- POST /api/auth/login, usando a sessão anônima preparada pelo GET de CSRF.
- POST /api/auth/logout, usando conta autenticada.
- PUT /api/users/me/password, usando conta autenticada e payload válido.
- DELETE /api/users/me, usando conta autenticada.

Também foi comprovado que o login rejeitado não autentica, que logout e
exclusão rejeitados preservam a conta/sessão e que a troca rejeitada mantém
o hash, o acesso com a senha antiga e as duas sessões. A senha proposta
não permite login. Autenticação ausente e CSRF ausente são situações
distintas: preparar a sessão evita atribuir a falha à causa errada.

A configuração existente já protegia esses caminhos; não foi necessário
alterar o backend. Os novos testes completam a cobertura explícita.
A fixture Postman v1 foi preservada.

### L3 — Preservar outra conta após exclusão — resolvida

Relacionada ao CA1. Implementada e validada em 02/11/2026.

O teste T10 foi ampliado, sem adicionar um método novo: prepara contas A/B,
duas sessões de A e uma de B. Exclui A com CSRF válido e verifica ausência
de A, presença de B e total de uma conta remanescente. A sessão que excluiu
A é invalidada, a outra é rejeitada e novas credenciais de A recebem 401.

A sessão de B permanece válida, consulta seu perfil com 200 e o e-mail
correto, e B consegue fazer novo login. A classe continua com 14 testes.

Resultado comprovado no cenário: excluir a própria conta não remove nem
invalida B. A rota DELETE /api/users/me identifica o usuário pela sessão;
o teste não inventa um parâmetro para selecionar a conta excluída.
Nenhuma mudança no backend de produção foi necessária.

## Evidência de execução existente

Resultado fornecido pelo usuário em 02/11/2026, às 01:59:41 (-03:00):

```text
UserAuthenticationIntegrationTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
Time elapsed: 15.43 s
BUILD SUCCESS
Total time: 25.144 s
```

Comando orientado para essa rodada:

```bash
./mvnw.cmd test "-Dtest=UserAuthenticationIntegrationTest" "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan24;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

Ambiente previsto pelo comando: perfil test da classe e H2 em memória
isolado. O trecho final enviado comprova o resultado da classe, mas não
contém a URL do datasource. Em uma nova rodada, registrar também a conexão
efetiva e o commit executado. A base inspecionada acima não substitui o
registro do commit no momento da execução.

O resultado acima é a evidência anterior à implementação de L1.

### Nova execução após L1 — 02/11/2026

Base do repositório: `36561ef`, com as alterações de L1 ainda sem commit.
Execução realizada pelo agente; conexão efetiva confirmada no log como
`jdbc:h2:mem:acompanhaai-kan24-l1`, com perfil `test` e migrações V1 a V5.

```bash
./mvnw.cmd test "-Dtest=UserAuthenticationIntegrationTest" "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan24-l1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

```text
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
Time elapsed: 12.40 s
BUILD SUCCESS
Total time: 15.820 s
Finished at: 2026-11-02T02:33:26-03:00
```

A primeira tentativa no sandbox foi interrompida após bloqueio de acesso
ao pipe de attach do agente Java. A repetição autorizada fora do sandbox
concluiu com sucesso, sem alteração de configuração do produto.

Os avisos de versão H2/Flyway e carregamento dinâmico do agente Mockito
permanecem observações; não impediram esta rodada. Relatório local em
`target/surefire-reports/` não deve ser incluído no Git. A suíte completa
não foi reexecutada nesta etapa; esta evidência cobre a classe indicada.

### Nova execução após L2 — 02/11/2026

Base do repositório: `36561ef`, com L1 e L2 ainda sem commit.
Execução realizada pelo agente, fora do sandbox devido à restrição do
agente Java previamente identificada. Perfil `test`, conexão confirmada
em `jdbc:h2:mem:acompanhaai-kan24-l2`, com migrações V1 a V5.

```bash
./mvnw.cmd test "-Dtest=UserAuthenticationIntegrationTest" "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan24-l2;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

```text
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
Time elapsed: 13.39 s
BUILD SUCCESS
Total time: 18.860 s
Finished at: 2026-11-02T02:39:26-03:00
```

O resultado inclui os dez testes anteriores e os quatro novos testes de L2.
Os avisos H2/Flyway e Mockito permanecem observações. A suíte completa ainda
não foi reexecutada nesta etapa; a evidência cobre a classe indicada.

### Regressão completa após L3 — 02/11/2026

Base do repositório: `36561ef`, com L1, L2, L3 e documentação ainda sem commit.
Execução realizada pelo agente fora do sandbox, mantendo o banco de testes
isolado. Conexão efetiva: `jdbc:h2:mem:acompanhaai-kan24-final`, perfil `test`,
migrações V1 a V5 aplicadas.

```bash
./mvnw.cmd verify "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan24-final;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

| Classe                               | Executados | Falhas | Erros | Ignorados |
| ------------------------------------ | ---------- | ------ | ----- | --------- |
| DatabaseMappingIntegrationTest       | 6          | 0      | 0     | 0         |
| ProfileAndApplicationIntegrationTest | 10         | 0      | 0     | 0         |
| UserAuthenticationIntegrationTest    | 14         | 0      | 0     | 0         |
| Total                                | 30         | 0      | 0     | 0         |

```text
BUILD SUCCESS
Total time: 25.671 s
Finished at: 2026-11-02T02:44:30-03:00
```

O Maven gerou e reempacotou o JAR. Essa rodada confirma a regressão local
com as três lacunas cobertas e não equivale a execução do CI no GitHub.
Os avisos H2/Flyway e Mockito continuam registrados como observações.

## Limites e verificações posteriores

- PostgreSQL não foi comprovado por essa execução da classe. Migração e
  cadastro manual anteriores em PostgreSQL não equivalem à suíte completa.
- A matriz de segurança em PostgreSQL é uma entrega própria do KAN-27;
  não antecipar seu aceite com um resultado em H2.
- MockMvc não valida transporte de cookies e comportamento real do navegador.
- HTTPS, expiração real por inatividade, carga, recuperação de senha e
  cascatas de todos os dados associados não são comprovados por este resumo.
- A verificação do CSRF aqui se concentra nos endpoints de conta/autenticação;
  foto e candidatura têm suas próprias coberturas.

## Próximo passo e aceite

1. L1, L2 e L3 concluídas; matriz atualizada com testes e resultados.
2. Regressão local completa aprovada em H2 isolado: 30 testes.
3. Revisar as alterações, registrar o commit e abrir PR para main.
4. Confirmar o CI do PR e registrar seu link nas evidências.
5. Concluir o KAN-24 somente após revisão, merge e aceite; não antecipar
   homologação PostgreSQL, navegador ou produção.

Situação desta revisão: **L1, L2 e L3 resolvidas; 14 testes de autenticação
e 30 testes no total aprovados.** Nenhum defeito funcional foi demonstrado
nesta análise. PR, CI remoto e aceite permanecem pendentes.
