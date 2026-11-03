# KAN-26 — Foto opcional do próprio usuário

Revisão iniciada em 03/11/2026 sobre main `306db5d` (merge da KAN-25, PR #13).
Branch: feature/KAN-26-validar-foto-perfil. Aceite depende de revisão e CI remoto.

## Implementação revisada

- UserPhoto: foto separada da entidade User; chave compartilhada por @MapsId.
- UserPhotoRepository e UserPhotoService: consulta por ID do principal, validação,
  substituição e remoção transacionais. Uma foto por conta.
- UserPhotoResource: GET/PUT/DELETE /api/users/me/photo; GET privado e no-store.
- ImageIO identifica JPEG/PNG pelo conteúdo, confere dimensões antes de ler a
  imagem completa e regrava o conteúdo antes de salvar.
- Tamanho máximo do arquivo recebido e do conteúdo regravado: 2097152 bytes,
  inclusive. Área máxima: 4000000 pixels, inclusive, usando produto long.
- V2: FK com ON DELETE CASCADE remove a foto na exclusão da conta.
- UserDTO retorna photoUrl explicitamente nulo quando não há foto; nunca retorna
  entidade ou senha/hash. O avatar padrão é responsabilidade do frontend futuro.

A validação e persistência já existiam. As lacunas iniciais eram testes de JPEG,
limites inclusivos, conteúdo efetivamente substituído, isolamento com fotos em
duas contas e multipart real. O Service e a migração V2 foram preservados.

## Critérios ligados aos testes

Classe principal: [UserPhotoIntegrationTest](../src/test/java/com/maiconamaral/acompanhaai/UserPhotoIntegrationTest.java).

| Critério                                  | Cenário e teste                                                                                                                                                                             |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| JPEG/PNG por conteúdo                     | detectsAllowedFormatFromContentInsteadOfFilenameOrDeclaredMime, parametrizado nos dois formatos, com nome .svg e MIME application/octet-stream                                              |
| Rejeitar conteúdo inválido                | invalidEmptyCorruptAndUnsupportedContentDoesNotOverwriteValidPhoto: vazio, SVG, texto, GIF, PNG/JPEG truncados e arquivo acima de 2 MiB declarados como JPEG                                |
| Limite inclusivo de bytes                 | acceptsExactlyTwoMiBAndReencodesDiscardingTrailingContent: 2 MiB aceitos, imagem regravada e marcador extra removido                                                                        |
| Limite inclusivo de pixels                | acceptsExactlyFourMillionPixelsAndRejectsLargerWithoutReplacingPhoto: 2000×2000 aceitos; 2001×2000 rejeitados preservando os bytes antigos                                                  |
| Substituição                              | replacementChangesActualContentAndFormatWithoutCreatingAnotherRecord: PNG substituído por JPEG de outras dimensões; somente um registro                                                     |
| Acesso apenas à própria foto              | twoAccountsReadReplaceAndDeleteOnlyTheirOwnPhoto: duas contas com fotos diferentes, userId de outra conta ignorado em PUT/DELETE; foto alheia preservada                                    |
| Autenticação e CSRF                       | anonymousAccessAndWritesWithoutValidCsrfDoNotChangePhoto: GET/PUT/DELETE anônimos; PUT/DELETE com CSRF ausente e inválido; foto preservada                                                  |
| Multipart sem arquivo                     | missingMultipartFileIsRejectedWithoutRemovingPhoto: 400 sem alteração                                                                                                                       |
| Remoção idempotente e foto opcional       | removalIsIdempotentAndProfileKeepsExplicitNullPhotoUrl: DELETE antes do primeiro upload e repetido após upload retorna 204; GET da foto ausente retorna 404; photoUrl existe com valor null |
| Exclusão da conta remove somente sua foto | deletingAccountCascadesOnlyItsPhotoAndPreservesOtherAccount: FK remove foto da conta excluída, foto da outra conta continua acessível                                                       |

O teste parametrizado soma duas execuções: onze cenários na classe principal.
Os testes anteriores em ProfileAndApplicationIntegrationTest foram preservados.

## HTTP real e correção de transporte

[UserPhotoHttpIntegrationTest](../src/test/java/com/maiconamaral/acompanhaai/UserPhotoHttpIntegrationTest.java)
inicia Tomcat em porta aleatória e usa HttpClient real com cookie de sessão,
login e renovação do CSRF. Gera as imagens em memória, sem fotos pessoais.
Confere upload de exatamente 2 MiB aceito, arquivo de 2 MiB + 1 byte e arquivo
de 3 MiB + 1 byte rejeitados com 413, preservando a foto existente e seu GET.

Após a revisão do diff, o teste também compara multipart com duas partes:
arquivo principal de 2 MiB e parte extra de aproximadamente 1 MiB. Há um
controle abaixo de 3 MiB aceito e outro acima de 3 MiB rejeitado, contando
os headers multipart, com cada arquivo individual dentro de 2 MiB.
Esse cenário isola o limite total da requisição. O arquivo único de 3 MiB
também excedia o limite individual e, sozinho, não comprovava essa proteção.

MockMvc não executa o parser multipart do servidor. Nesse ambiente, a rejeição
direta do Service é 422; via servidor real, limites de upload produzem 413.

A primeira execução HTTP mostrou erro de conexão com resposta chunked incompleta
em upload excessivo. Foi ajustado server.tomcat.max-swallow-size para 4MB,
mantendo os limites de aceitação de 2MB/arquivo e 3MB/requisição. O descarte
continua limitado: não se configurou leitura ilimitada de corpos rejeitados.
Requisições acima dessa margem ainda podem ter a conexão encerrada.

O cenário HTTP passou após a correção, em H2 e PostgreSQL, recebendo 413 completo
e preservando os bytes da foto. A falha de transporte foi retestada.
Não há alteração da lógica de formatos, autorização ou tamanho permitido.

## Ambiente e execução

Windows 11, Java 25.0.4.1 Amazon Corretto e Maven Wrapper 3.9.16, Spring Boot 4.0.9.
Testes usam perfil test, Flyway real e bases isoladas. Nenhuma credencial local
do PostgreSQL habitual foi lida. Relatórios são gerados em target/surefire-reports.

Comando H2:

```bash
./mvnw.cmd --batch-mode --no-transfer-progress verify "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan26;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

PostgreSQL temporário: loopback 127.0.0.1:55433, banco acompanhaai_kan26 e usuário
de testes acompanhaai_qa, com arquivos em target/kan26-postgres. Autenticação
trust limitada a essa instância local descartável; não é configuração de produção.

```bash
./mvnw.cmd --batch-mode --no-transfer-progress verify "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55433/acompanhaai_kan26" "-Dspring.datasource.username=acompanhaai_qa" "-Dspring.datasource.password="
```

Os comandos PostgreSQL exigem instância de testes isolada; a regressão limpa
registros e não deve ser apontada para bancos com dados que precisam ser preservados.

## Evidência PostgreSQL — 03/11/2026

```text
Tests run: 96, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 03:31 min
Finished at: 2026-11-03T00:45:57-03:00
```

São os 84 testes anteriores mais 12 novos de foto. V1–V6 foram aplicadas em
banco vazio; a regressão também exercitou a atualização V5→V6 já existente.
Os relatórios PostgreSQL foram preservados em target/kan26-postgres-reports
antes de executar a regressão H2. Essa pasta é ignorada pelo Git e não é
anexada a publicações automaticamente.

A sintaxe do OpenAPI e dos YAMLs foi lida com SnakeYAML, com 89 referências
locais do contrato resolvidas. Isso não substitui validação completa do padrão
OpenAPI nem os testes HTTP. git diff --check passou.

## Evidência H2 — 03/11/2026

```text
Tests run: 96, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
Total time: 03:18 min
Finished at: 2026-11-03T00:50:37-03:00
```

São 95 execuções aprovadas e um teste ignorado intencionalmente:
PostgresMigrationIntegrationTest, que exige PostgreSQL. Os doze cenários novos
de foto passaram nos dois bancos. H2 2.4.240 e Tomcat 11.0.27 foram confirmados
no log. Os avisos já existentes de compatibilidade H2/Flyway e agente Mockito
não impediram o build.

A instância PostgreSQL temporária foi encerrada após salvar os relatórios.
Nenhum banco habitual foi alterado e nenhuma migração existente foi editada.

## Correções da revisão do diff

- O teste HTTP foi ampliado para isolar o limite total de 3 MiB, conforme acima.
- A resposta 422 no OpenAPI preserva a descrição específica e voltou a declarar
  application/problem+json com referência ao schema Problem, alinhada ao
  ResourceExceptionHandler. O validador confirmou esse caminho e resolveu 90
  referências locais. Não se trata de validação completa do padrão OpenAPI.
- As evidências das regressões completas acima pertencem à versão anterior
  desses ajustes de teste/contrato; a revalidação direcionada está registrada abaixo.

```bash
./mvnw.cmd --batch-mode --no-transfer-progress verify "-Dtest=UserPhotoIntegrationTest,UserPhotoHttpIntegrationTest" "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan26-review;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

```text
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 02:28 min
Finished at: 2026-11-03T00:59:17-03:00
```

O controle abaixo de 3 MiB retornou 204 e o multipart acima desse limite,
com arquivos individualmente válidos, retornou 413 preservando a foto.
Após estes dois ajustes, foram reexecutados os testes de foto em H2; as
regressões completas e PostgreSQL não foram repetidos nesta rodada.

## Preservação e pendências

- Nenhuma alteração da fixture Postman v1, de seu executor ou lockfile.
- Nenhuma coleção Postman de imagens adicionada; verificações novas em JUnit.
- Sem imagem de usuário, segredo ou configuração privada no versionamento.
- Frontend e avatar padrão ainda não implementados; contrato null verificado no backend.
- Revisão de PR, CI remoto e aceite precisam de evidência antes de concluir o cartão.

## Referências

- [Tomcat 11 — maxSwallowSize](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html).
- [Spring Boot — configurações do servidor](https://docs.spring.io/spring-boot/appendix/application-properties/).
