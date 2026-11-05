# Consistência de candidaturas — KAN-32

## Revisão e decisões

A base já tinha unicidade por `(user_id, url_hash)`, versão otimista da
candidatura, transações e FKs com exclusão em cascata. Faltavam normalização
conservadora, comparação da URL diante de um hash igual, cenários simultâneos
e evidência de exclusão das tarefas vinculadas.

Cadastro, edição e exclusão de candidaturas agora bloqueiam a linha da própria
conta com `PESSIMISTIC_WRITE` antes de consultar ou alterar seus dados. No
isolamento `READ_COMMITTED` testado, uma segunda escrita dessa conta espera a
primeira concluir e então verifica os dados confirmados. Contas distintas
bloqueiam linhas distintas. A versão da candidatura continua sendo exigida no
PUT, e o índice único permanece como proteção adicional no banco.

O bloqueio dura até o fim da transação; não há chamada externa nesse intervalo.
Futuros fluxos de importação que gravem vagas devem respeitar o mesmo protocolo
de bloqueio e comparação de URLs. Não há worker de importação implementado nesta entrega.

## Política de URL

O link original, após remover espaços nas extremidades, continua sendo
retornado em `job.sourceUrl`. Internamente, `normalized_url` é separado do
original e seu SHA-256 em UTF-8 fica em `url_hash`.

- Aceitar HTTP/HTTPS, inclusive esquema em maiúsculas, com host válido.
- Reduzir esquema e host para minúsculas; preservar o identificador de zona
  quando houver IPv6 com escopo.
- Omitir porta 80 de HTTP e 443 de HTTPS. Preservar outras portas válidas.
- Representar caminho vazio por `/`.
- Preservar caminho, caixa do caminho, barra final, segmentos `.`/`..`, query
  completa e fragmento, inclusive suas representações percent-encoded.
- Não ordenar ou remover parâmetros, inclusive `utm_*`, nem decodificar
  identificadores, remover fragmentos, trocar HTTP por HTTPS ou unificar `www`.
- Rejeitar URLs malformadas, portas fora do intervalo 1–65535 e `userinfo`
  embutido, como `https://usuario:senha@host/vaga`, com 422 e mensagem genérica.
  Hosts Unicode precisam ser informados na forma ASCII/punycode aceita pelo parser.
- Ausência de link permanece nula e não participa da deduplicação.

A política usa um subconjunto conservador das equivalências de HTTP da
[RFC 9110, seção 4.2.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-4.2.3).
Os componentes brutos são obtidos por `java.net.URI`, conforme a
[documentação Java 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/URI.html),
para preservar escapes. Não há acesso à página, redirecionamentos ou extração de dados.

Exemplo de links equivalentes:

```text
HTTPS://JOBS.EXAMPLE.TEST:443/jobs/7?jobId=A%2FB#Job-1
https://jobs.example.test/jobs/7?jobId=A%2FB#Job-1
```

Trocar `jobId`, sua ordem na query, a caixa de `Job-1` ou o fragmento pode
identificar outra oportunidade e não será unificado automaticamente.

## Duplicidade, colisões e dados antigos

Antes de salvar, a aplicação consulta somente ID, link e hash das vagas com
link da própria conta, compara suas URLs normalizadas e ignora a própria vaga
durante a edição.

- URL normalizada igual: 409, `code: JOB_URL_ALREADY_EXISTS`.
- Mesmo hash armazenado para outra URL normalizada: 409,
  `code: JOB_URL_HASH_COLLISION`. Não unir, substituir ou reaproveitar a vaga.
- Mesmo link em contas diferentes: permitido.
- Um conflito não deixa vaga, candidatura, habilidades ou histórico parciais.
- A API não expõe hashes, IDs de outra conta ou o conteúdo de seu link em erros.

A consulta inclui registros legados com hash bruto antigo ou sem metadados.
As URLs antigas são normalizadas em memória para a comparação. Links legados
malformados são preservados e não bloqueiam URLs válidas não relacionadas.
Não há regravação automática nem migração nova nesta história; V1–V7 ficam intactas.
Uma edição explícita válida grava o novo par normalizado/hash.

Caso já existam duas vagas antigas equivalentes, elas são preservadas e devem
ser resolvidas explicitamente pelo usuário. O serviço não as mescla. A
comparação por conta é linear na quantidade de links dessa conta, usando uma
projeção que não carrega descrições ou habilidades. Uma futura otimização deve
tratar os conflitos legados antes de substituir essa comparação por uma busca
exclusiva no índice de hash.

O campo `code` é opcional em ProblemDetail: erros de versão e violações de
integridade detectadas diretamente pelo banco mantêm o tratamento anterior.

## Exclusão e transações

`DELETE /api/applications/{id}` continua exigindo sessão e CSRF:

| Registro                                     | Efeito                                             |
| -------------------------------------------- | -------------------------------------------------- |
| Candidatura solicitada                       | Removida                                           |
| Vaga pessoal da candidatura                  | Removida                                           |
| Histórico e habilidades dessa vaga           | Removidos                                          |
| Tarefas de importação vinculadas à vaga      | Removidas pela FK composta com `ON DELETE CASCADE` |
| Tarefas da conta sem vínculo com essa vaga   | Preservadas                                        |
| Outras candidaturas e dados de outras contas | Preservados                                        |

Primeira exclusão bem-sucedida retorna 204; repetição retorna 404. Outra conta
recebe 404 sem remover dados. Excluir libera o link para novo cadastro.
Se a remoção da vaga falhar depois do flush da exclusão da candidatura, a
transação restaura candidatura, histórico, habilidades e tarefas vinculadas.
Não há soft delete ou recuperação de registros nesta versão.

Excluir a própria conta remove também suas tarefas sem vaga. Os dados de outra
conta permanecem intactos. Os testes usam tarefas `PENDING` fictícias; não
validam cancelamento de um worker ou de operações externas, ainda não existentes.

## Critérios e testes

| Critério                                           | Evidência automatizada                                                                                                                                   |
| -------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Mesmo link equivalente simultâneo na mesma conta   | `concurrentEquivalentLinksInSameAccountProduceOneApplicationAndOneConflict`: duas sessões e threads, barreira de início, 201/409 e contagens persistidas |
| Mesmo link simultâneo em contas diferentes         | `concurrentSameLinkInDifferentAccountsIsAllowed`: 201/201 e proprietários distintos                                                                      |
| Preservação de identificadores e validação         | `JobUrlServiceTest`: 28 casos, incluindo fragmentos, query, escapes e portas                                                                             |
| Hash igual não implica mesma vaga                  | `duplicateAndForcedHashCollisionHaveDistinctCodesAndNeverMergeJobs`: hash forçado por Mockito, persistência real e códigos distintos                     |
| Compatibilidade com metadados antigos              | `detectsEquivalentLegacyLinkWithOldHashOrMissingMetadataWithoutRewritingIt`, `malformedLegacyLinkDoesNotBlockUnrelatedValidLink`                         |
| Edição, link opcional e novo cadastro após excluir | `editingOwnEquivalentLinkIsAllowedButAnotherJobsLinkDoesNotChangeData`, `absentLinksAreNotDeduplicatedAndDeletionReleasesUrlForNewRegistration`          |
| Exclusão completa e privacidade                    | `deletesOnlyLinkedDataAndAccountDeletionPreservesOtherAccount`                                                                                           |
| Falha na exclusão reverte o flush                  | `deleteFailureRestoresApplicationHistorySkillsAndLinkedTasks`: falha simulada em `JobRepository.delete`, rollback real                                   |

Os 13 casos de integração estão em `ApplicationConsistencyIntegrationTest`.
O teste antigo de edição conflitante da KAN-30 foi renomeado para
`conflictingUrlEditPreservesJobSkillsAndStatusHistory`: o conflito agora pode
ser detectado antes da escrita. O rollback após flush tem teste específico de
exclusão nesta história, além do teste de falha de histórico da KAN-31.

Fixture Postman v1 e dependências continuam congeladas. Novas verificações
Postman pertencem à v2/KAN-33. Registrar resultados após execução; CI remoto,
revisão do PR e aceite continuam pendentes.

## Execução local — 03/11/2026

Java Corretto 25.0.4.1, Spring Boot 4.0.9, Maven Wrapper 3.9.16.
Configurações de QA derivadas dos YAML versionados, sem importar arquivos
locais de credenciais. Resumo: [evidência sanitizada](evidencias/kan-32-validacao-local.json).

| Execução                            | Resultado                                                                                      |
| ----------------------------------- | ---------------------------------------------------------------------------------------------- |
| H2 2.4.240                          | 196 encontrados: 189 aprovados e 7 exclusivos de PostgreSQL ignorados; zero falhas/erros       |
| PostgreSQL 18.6 com roles separadas | 2 testes de provisionamento/migração + 193 de regressão aprovados; zero falhas/erros/ignorados |
| Novos cenários                      | 28 unitários + 13 de integração aprovados nas duas execuções                                   |
| Fixture Postman v1                  | 9 hashes SHA-256 conferidos; coleção não executada nesta rodada                                |

O total da execução com PostgreSQL inclui testes unitários sem banco e um teste
de migração salarial independente em H2. Os testes Spring da API, de bloqueio
e de permissões usam PostgreSQL com `acompanhaai_app` para o CRUD e migrator
separado para o Flyway. O cluster descartável foi encerrado pelo executor.

Para reproduzir H2, copiar `src/main/resources/application.yml` e
`src/test/resources/application-test.yml` para `target/kan32-h2-config/`, remover
apenas `spring.config.import` da cópia do primeiro arquivo e executar:

```powershell
./mvnw.cmd --batch-mode --no-transfer-progress verify "-Dspring.config.location=file:./target/kan32-h2-config/" "-DargLine=-Xms32m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m"
```

Para PostgreSQL descartável no Windows:

```powershell
./scripts/test-database-roles.ps1
```

CI remoto, revisão do PR e aceite ainda não foram executados nesta rodada.
