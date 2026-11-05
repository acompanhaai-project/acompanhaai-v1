# KAN-25 — Recuperação de senha

Tarefa: [Recuperar acesso por token de uso único](https://maikamaral123.atlassian.net/browse/KAN-25).
Estado em 02/11/2026: **modelo, Service, DTOs, endpoints e entrega SMTP local implementados**.
O KAN-25 ainda depende das verificações e pendências listadas ao final.

## Fluxo planejado

1. Receber e-mail e aplicar limites de frequência.
2. Retornar resposta genérica, exista ou não uma conta.
3. Para conta existente, gerar token criptograficamente aleatório e seu hash.
4. Persistir somente o hash e enviar o token pelo canal de e-mail de testes.
5. Receber token e nova senha; validar propriedade, prazo e uso anterior.
6. Consumir o token e trocar a senha na mesma transação, revogando sessões
   e os demais tokens de recuperação que não devem continuar válidos.
7. Exigir login normal com a nova senha; não autenticar automaticamente.

O Repository, a emissão e a redefinição no Service estão implementados.
Os DTOs e Resources seguem o [contrato OpenAPI](openapi.yaml), com testes HTTP.
O [guia de e-mail local](email-local.md) descreve ativação e limites da entrega de testes.
A fixture Postman v1 permanecerá congelada; novos cenários usarão v2.

## Estrutura implementada

Entidade: [PasswordResetToken](../src/main/java/com/maiconamaral/acompanhaai/entities/PasswordResetToken.java).
Migração: [V6](../src/main/resources/db/migration/V6__create_password_reset_tokens.sql).
Tabela: `tb_password_reset_token`.

| Campo      | Responsabilidade                                                 |
| ---------- | ---------------------------------------------------------------- |
| id         | Identificador interno do registro; não é o token de recuperação. |
| user_id    | Proprietário obrigatório, referenciando tb_user.                 |
| token_hash | Representação hexadecimal SHA-256, com unicidade global.         |
| created_at | Instante de emissão, informado na criação do objeto.             |
| expires_at | Expiração posterior à emissão e limitada a 30 minutos.           |
| used_at    | Nulo antes do uso; preenchido quando consumido.                  |
| version    | Versão de persistência para controle otimista de concorrência.   |

A entidade recebe um hash já calculado. Ela não gera o token nem calcula
o hash nesta etapa. Nenhum campo armazena o token original e a entidade
não deve ser retornada pela API ou escrita em logs.

O construtor valida hash hexadecimal minúsculo com 64 caracteres e a janela
de validade. O banco reforça unicidade, comprimento, referência ao usuário
e consistência das datas. O formato hexadecimal é validado em Java;
a restrição SQL de hash verifica apenas o comprimento.

Excluir um usuário remove seus tokens por FK com ON DELETE CASCADE.
Índices por usuário/data e expiração preparam consultas de emissão e limpeza.
Não há rotina de limpeza implementada nesta etapa.

## Métodos e limites

`isUsableAt(instant)` verifica que o registro ainda não foi usado e que o
instante está entre emissão inclusiva e expiração exclusiva.
`markUsed(instant)` registra uso somente se essas condições forem atendidas.
Uma segunda chamada no mesmo objeto é rejeitada.

Os instantes são parâmetros explícitos. O Service recebe Clock, permitindo
testes determinísticos sem esperar o prazo real de validade ou de frequência.

`@Version` participa da proteção contra atualizações concorrentes via JPA.
Ela não demonstra sozinha uso único no fluxo completo. O Service coordena
consumo e alteração de senha na mesma transação com bloqueio da conta.
Invalidação por outras mudanças relevantes da conta continua fora desta etapa.

Não foi adicionada uma relação de tokens dentro de User: a FK e a entidade
mantêm a propriedade sem carregar uma coleção em toda consulta de perfil.

## Verificações executadas

Classe nova: [PasswordResetTokenIntegrationTest](../src/test/java/com/maiconamaral/acompanhaai/PasswordResetTokenIntegrationTest.java).

- Persistência de proprietário, hash, datas e versão; uso persistido.
- Rejeição de hash duplicado.
- Exclusão em cascata somente dos tokens do proprietário excluído.
- SQL direto rejeitando validade negativa, zero e acima de 30 minutos.
- SQL direto rejeitando hash de comprimento incorreto.
- Validação de hash e prazo no construtor.
- Rejeição de uso antes da emissão, na expiração e após consumo.

O teste parametrizado de validade executa três cenários. A classe nova possui
nove execuções no total. A regressão completa executou 39 testes: os 30
anteriores e os nove desta etapa, com zero falhas, erros ou ignorados.

```bash
./mvnw.cmd verify "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan25-model;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

O log confirmou H2 em memória isolado, perfil test e aplicação da migração V6.
Resultado: BUILD SUCCESS, JAR gerado, duração total de 28,290 segundos,
conclusão em 02/11/2026 às 03:03:11 (-03:00).
Os avisos de compatibilidade H2/Flyway e agente Mockito permanecem observações.

Essa execução não comprova aplicação da V6 em PostgreSQL nem atualização de
um banco V5 com dados existentes. Essas verificações permanecem pendentes;
não aplicar a migração em produção como parte deste exercício.

## Repository e emissão no Service

- [PasswordResetTokenRepository](../src/main/java/com/maiconamaral/acompanhaai/repositories/PasswordResetTokenRepository.java):
  consulta por hash, busca a emissão mais recente por usuário e remove seus
  tokens não utilizados na reemissão.
- [PasswordResetService](../src/main/java/com/maiconamaral/acompanhaai/services/PasswordResetService.java):
  executa `issueToken(email)` em transação.
- [TimeConfig](../src/main/java/com/maiconamaral/acompanhaai/config/TimeConfig.java):
  disponibiliza um Clock UTC, substituído por relógio controlado nos testes.

### Contrato interno de emissão

1. Normaliza e-mail com a regra existente em UserService.
2. Busca a conta com bloqueio de escrita no banco, serializando emissões
   para o mesmo usuário até o término da transação.
3. Se não encontrar a conta, retorna Optional vazio sem criar registros.
4. Aplica intervalo mínimo inicial de 60 segundos por conta existente.
   Solicitações durante esse intervalo retornam vazio, preservando o token.
5. Gera 32 bytes com SecureRandom e codifica em Base64 URL-safe sem padding.
6. Calcula SHA-256 do token; somente o hash hexadecimal é persistido.
7. Define expiração em 30 minutos a partir do instante de emissão.
8. Remove tokens anteriores ainda não consumidos daquele usuário e grava
   o novo na mesma transação. Registros já utilizados são preservados.
9. Retorna internamente destinatário, token original e expiração para o
   envio de e-mail. Emitir não altera senha nem authVersion da conta.

O resultado IssuedToken não é um DTO de resposta HTTP. Seu toString omite
token e destinatário, e o token é ignorado na serialização JSON. O
Resource retorna a mesma resposta genérica para conta desconhecida,
emissão aceita ou solicitação suprimida pelo intervalo. Nunca retornar o
Optional ou o resultado interno diretamente ao cliente.

O token original existe transitoriamente em memória para entrega pelo canal
de e-mail. Não registrá-lo em logs ou métricas. `requestReset` publica o evento
na transação; o listener agenda a entrega somente após commit. Falhas SMTP
geram aviso genérico, sem dados pessoais ou conteúdo. Não há retry automático.

O intervalo de 60 segundos é uma decisão inicial de emissão por conta, não
uma proteção global contra abuso. Limites de tráfego, solicitações a e-mails
inexistentes e redução da diferença temporal agora são tratados pela
[proteção HTTP](protecao-recuperacao.md), fora do Service transacional.

A redefinição usa o mesmo bloqueio de conta da emissão. Assim, consumo e
substituição do token são serializados para o mesmo usuário.

### Verificação da emissão — 02/11/2026

Classe: [PasswordResetServiceIntegrationTest](../src/test/java/com/maiconamaral/acompanhaai/PasswordResetServiceIntegrationTest.java).
São dez testes novos, cobrindo:

- Token com 32 bytes, destinatário normalizado, hash persistido e prazo.
- Conta desconhecida e entrada ausente/vazia/longa sem criação de token.
- Bloqueio por intervalo e aceitação exatamente na fronteira de 60 segundos.
- Novo token aleatório e substituição do anterior ainda não utilizado.
- Preservação dos tokens de outra conta e dos registros já consumidos.
- Duas solicitações concorrentes gerando somente uma emissão.
- Ausência do token em JSON e no toString do resultado interno.
- Senha e versão de autenticação inalteradas após a emissão.

Regressão completa executada pelo agente em H2 isolado, base `c694574` mais
as alterações locais ainda sem commit:

```bash
./mvnw.cmd verify "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan25-issuance;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

```text
Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 01:15 min
Finished at: 2026-11-02T03:13:54-03:00
```

Conexão H2 em memória, perfil test e seis migrações confirmados no log.
Nenhum e-mail foi enviado nem serviço externo acessado nessa implementação.
Os avisos H2/Flyway e Mockito persistem como observações. A validação de
bloqueios e concorrência em PostgreSQL permanece pendente.

## Redefinição e consumo único no Service

`resetPassword(rawToken, newPassword)` é uma operação interna transacional.
Não há operação pública separada de validação: a validade é conferida dentro
da própria transação que altera a senha, evitando separar verificação e uso.

1. Rejeita token fora do formato emitido (43 caracteres Base64 URL-safe).
2. Valida a senha conforme a política atual: 8 a 128 caracteres, não vazia.
   A senha não é normalizada nem tem espaços removidos.
3. Calcula o hash e consulta somente o ID do proprietário, sem carregar o
   registro do token antes de obter o bloqueio.
4. Bloqueia a conta para escrita e consulta o token novamente após o bloqueio.
   Uma emissão ou redefinição anterior pode tê-lo removido ou consumido.
5. Confere uso anterior e janela de validade: emissão inclusiva, expiração
   exclusiva. Ausente, expirado ou consumido usa a mesma exceção genérica,
   `InvalidPasswordResetTokenException`, sem incluir token ou dados da conta.
6. Registra `usedAt`, codifica a senha com o PasswordEncoder existente e chama
   `User.changePassword`, incrementando `authVersion`.
7. Remove os demais tokens não utilizados da conta. O registro consumido e
   o histórico de outros consumos são preservados.

Consumo, senha, versão de autenticação e remoção de tokens participam da mesma
transação: uma falha impede confirmação parcial. O método não retorna usuário,
senha ou token e não autentica automaticamente.

O SessionValidationFilter existente rejeita as sessões com authVersion antiga
na próxima requisição e invalida a respectiva sessão HTTP. Isso não interrompe
requisições que já estavam em andamento. Sessões de outras contas permanecem
válidas; um novo login exige a nova senha.

### Verificação da redefinição

[PasswordResetConsumptionIntegrationTest](../src/test/java/com/maiconamaral/acompanhaai/PasswordResetConsumptionIntegrationTest.java)
adiciona 12 testes de integração, incluindo:

- Senha codificada, token consumido e versão incrementada.
- Tokens desconhecidos, malformados, anteriores à emissão, expirados ou usados.
- Aceitação imediatamente antes da expiração e rejeição na fronteira exata.
- Senha inválida sem consumo do token; link antigo rejeitado após reemissão.
- Remoção de outros tokens da conta e preservação de outra conta.
- Duas redefinições simultâneas: exatamente uma aceita e versão incrementada uma vez.
- Rollback da transação externa restaurando senha, versão e tokens após flush.
- Duas sessões antigas revogadas via HTTP, outra conta preservada, login antigo
  rejeitado e novo login aceito; ausência de autenticação automática.

Esses testes exercitam o Service diretamente e as rotas de autenticação já
existentes. Eles não verificam endpoints de recuperação nem entrega de e-mail.
Bloqueios e concorrência em PostgreSQL ainda precisam de validação própria.

Regressão completa executada em H2 isolado:

```bash
./mvnw.cmd verify "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan25-consumption;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

```text
Tests run: 61, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 01:30 min
Finished at: 2026-11-02T03:22:01-03:00
```

Resultado: 49 testes anteriores mais 12 da redefinição; seis migrações aplicadas.
Na execução dessa etapa do Service, ainda não havia endpoint de recuperação.
Nenhum e-mail foi enviado.

## Contrato HTTP e exemplos para Postman

Resource: [PasswordResetResource](../src/main/java/com/maiconamaral/acompanhaai/resources/PasswordResetResource.java).
Fonte do contrato: [openapi.yaml](openapi.yaml). Foram adicionados os DTOs
PasswordResetRequestDTO, PasswordResetConfirmationDTO e PasswordResetResponseDTO.
O DTO de confirmação omite token e senha em seu toString.

As duas rotas permitem acesso sem login, mas mantêm a proteção CSRF existente.
Primeiro execute `GET http://localhost:8080/api/auth/csrf`, preserve o cookie
JSESSIONID e envie o valor retornado em `X-CSRF-TOKEN`. Use Body → raw → JSON
e Content-Type application/json. O cookie precisa pertencer à mesma sessão
do token CSRF. O token de recuperação é outro segredo, independente do CSRF.

### Solicitar recuperação

`POST http://localhost:8080/api/auth/password-reset/request`

```json
{"email":"pessoa@example.test"}
```

Retorna `202 Accepted`, igualmente para conta existente, desconhecida e
solicitação suprimida pelo intervalo de 60 segundos:

```json
{"message":"Se houver uma conta para este e-mail, a solicitação de recuperação será processada."}
```

Somente o recebimento é confirmado. O endpoint aplica limites e tenta agendar
o pedido em uma fila limitada. O worker chama `requestReset`, que emite o token
e publica um evento transacional. Com `mail-local` ativo, a entrega SMTP
é agendada em memória após commit. Desabilitado por padrão; não há envio sem
ativação explícita. O token não pode ser obtido nessa resposta nem consultado
em outra rota. O teste de e-mail o extrai da mensagem recebida no SMTP local.

### Confirmar a nova senha

`POST http://localhost:8080/api/auth/password-reset/confirm`

```json
{"token":"TOKEN_RECEBIDO_PELO_CANAL_DE_ENTREGA","newPassword":"NovaSenhaExemplo123!"}
```

O exemplo do token é um marcador; substitua por um token emitido real.
Token e senha são enviados no corpo, nunca na URL. Sucesso retorna `204 No Content`.
Não autentica automaticamente; efetue novo login com a nova senha e obtenha
novo CSRF após o login. Sessões anteriores são rejeitadas na próxima requisição.

| Status | Significado                                                                                                    |
| ------ | -------------------------------------------------------------------------------------------------------------- |
| 400    | JSON ausente/inválido; na confirmação, também token inválido, desconhecido, expirado, substituído ou consumido |
| 403    | CSRF ausente/inválido; reobter CSRF para a mesma sessão                                                        |
| 422    | Campos obrigatórios, e-mail, comprimento ou senha inválidos; erros sem repetir valores enviados                |
| 429    | Somente na confirmação: cota por IP ou global atingida; aguardar os segundos informados em Retry-After         |

Erros de token retornam application/problem+json com `status: 400` e o mesmo
detail: `Token de recuperação inválido ou indisponível.` Não diferenciam
expiração de uso anterior. Campos ausentes/vazios ou com comprimento excedido
são rejeitados antes pelo DTO com 422. Senha mantém a política atual de 8 a
128 caracteres, sem remover espaços, e não pode ser composta só por espaços.
O token de confirmação é obrigatório, não vazio, com no máximo 128 caracteres;
seu formato real de 43 caracteres Base64 URL-safe é conferido no Service.

### Evidência HTTP — 02/11/2026

[PasswordResetHttpIntegrationTest](../src/test/java/com/maiconamaral/acompanhaai/PasswordResetHttpIntegrationTest.java)
possui oito testes cobrindo resposta genérica, conta desconhecida, intervalo,
ausência de segredos, CSRF ausente/inválido sem mutação, validação dos dois DTOs,
JSON inválido, token inválido/expirado/reutilizado/substituído, confirmação 204,
ausência de login automático, revogação e login com a nova senha.
Um cenário obtém CSRF real por GET e usa a mesma sessão anônima na confirmação.
Entrega de e-mail não foi exercitada; fixture Postman v1 preservada.

```bash
./mvnw.cmd verify "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan25-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

```text
Tests run: 69, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 01:37 min
Finished at: 2026-11-02T03:27:40-03:00
```

Sintaxe YAML do contrato lida com SnakeYAML; isso não equivale a uma validação
completa do padrão OpenAPI. As respostas foram verificadas por MockMvc.

## Pendências do KAN-25

| Área                                                                | Situação                                                                                                                 |
| ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| Entidade e V6                                                       | Implementadas e verificadas em H2                                                                                        |
| Repository e transação de emissão                                   | Implementados e verificados em H2                                                                                        |
| Geração segura e hash                                               | Implementados; token original somente no resultado interno                                                               |
| Limites de frequência e resposta genérica                           | Intervalo por conta, cotas por IP/globais por processo e consulta assíncrona implementados; ver limites em proteção HTTP |
| Envio de e-mail de testes e coordenação com KAN-29                  | SMTP local implementado; produção, segredos e entrega durável continuam pendentes                                        |
| DTOs, rotas e atualização OpenAPI                                   | Implementados; oito testes HTTP aprovados em H2                                                                          |
| Reset, revogação de sessões e invalidação dos tokens antigos        | Implementados no Service; testes em H2                                                                                   |
| Token inválido, expirado, repetido e concorrência no fluxo completo | Cobertos no Service e HTTP; concorrência no Service; fluxo HTTP → SMTP → confirmação → login verificado em H2            |
| PostgreSQL, CI do PR e aceite                                       | PostgreSQL 18.6 aprovado: 84 testes, incluindo V5→V6; PR, CI remoto e aceite pendentes                                   |

## Referência de segurança

As decisões seguem a orientação de tokens aleatórios, armazenados com
segurança, vinculados ao usuário, com expiração e uso único da
[OWASP — Forgot Password](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html).
O hash deve ser derivado de um token aleatório de alta entropia; não usar
e-mail, ID do usuário ou horário como segredo de recuperação.
