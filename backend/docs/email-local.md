# E-mail de recuperação no ambiente de testes — KAN-25

Implementado com Spring Mail, evento transacional e servidor SMTP local.
Desabilitado por padrão. Não exige conta de provedor nem credenciais de e-mail.
O perfil `mail-local` habilita o envio, combinável com `dev` ou `postgres-local`.
O adaptador desta etapa aceita somente SMTP em localhost/127.0.0.1/::1.
Ele não configura um provedor de e-mail de produção.

## Teste manual com Mailpit

Requer Docker com Compose instalado e em execução. Na raiz do repositório:

```bash
docker compose -f compose.mail.yaml up -d
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev,mail-local"
```

O Compose separado sobe somente Mailpit, sem exigir o PostgreSQL nem ler a
configuração do serviço de banco no compose.yaml principal.
SMTP: 127.0.0.1:1025. Caixa de entrada: http://localhost:8025.
As duas portas são publicadas somente no loopback. A imagem foi fixada em
axllent/mailpit:v1.27.4. Mailpit captura as mensagens; nenhum relay foi configurado.
Não exponha a caixa de testes, pois ela contém links de recuperação utilizáveis.

1. Cadastre uma conta de teste, por exemplo pessoa@example.test.
2. Obtenha CSRF em GET /api/auth/csrf e preserve JSESSIONID.
3. Faça POST /api/auth/password-reset/request com o header X-CSRF-TOKEN e
   corpo `{"email":"pessoa@example.test"}`. A resposta é sempre genérica.
4. Abra a mensagem no Mailpit. O link tem formato
   `http://localhost:4200/reset-password#token=...`.
5. Como a tela Angular ainda não existe, copie somente o valor após `#token=`.
   Não é necessário abrir o link. O fragmento foi escolhido para não enviar
   o token na URL de uma requisição ao servidor.
6. Faça POST /api/auth/password-reset/confirm com CSRF da mesma sessão e JSON
   contendo token e newPassword, conforme [contrato](recuperacao-senha.md).
7. Espere 204; confirme que o link não pode ser reutilizado e efetue login
   com a nova senha. Nenhum login é criado automaticamente pela recuperação.

Se preferir banco PostgreSQL local já configurado, substitua os perfis por
`postgres-local,mail-local`. Essa instrução não significa que a integração
com PostgreSQL foi verificada nesta etapa.

Para parar somente o capturador:

```bash
docker compose -f compose.mail.yaml down
```

## Coordenação com a transação e falhas

- O endpoint aplica limites e agenda o pedido em fila própria, sem consultar a
  conta na thread HTTP. O worker chama `requestReset`, que abre a transação,
  emite o token e publica IssuedToken.
- `@TransactionalEventListener(AFTER_COMMIT)` submete o trabalho somente após
  commit. Rollback não agenda nem entrega mensagem.
- O executor dedicado possui 1–2 threads e fila limitada a 100 trabalhos.
  Envio SMTP não bloqueia a requisição HTTP nem ocorre com bloqueio de conta ativo.
- SMTP tem timeouts de conexão, leitura e escrita de 2 segundos. Erro SMTP
  ou rejeição por fila cheia gera aviso genérico, sem destinatário, token,
  conteúdo ou exceção SMTP. A resposta HTTP permanece 202.
- O link usa URL configurada, nunca o header Host enviado pelo cliente.
  O endereço base aceita HTTPS ou HTTP local, sem credenciais, query ou fragmento.
  Remetente de testes: noreply@acompanhaai.test. Texto contém expiração UTC.

Esta fila é volátil: reiniciar o processo, saturar a fila ou falhar no SMTP
pode perder a entrega. Não há outbox persistente, status de entrega nem retry
automático. O token já confirmado continua no banco até ser substituído,
consumido ou expirar; após 60 segundos uma nova solicitação pode emitir outro.
Uma mensagem antiga pode chegar após reemissão e conter link já inválido.
202 não confirma entrega. Entrega durável é trabalho posterior antes de produção.
A consulta da conta também é assíncrona. Existem cotas por IP e globais por
processo, descritas no [guia de proteção HTTP](protecao-recuperacao.md).
Essa separação reduz diferenças dependentes da conta; não comprova igualdade
temporal absoluta entre respostas.

## Evidência automatizada

[PasswordResetMailIntegrationTest](../src/test/java/com/maiconamaral/acompanhaai/PasswordResetMailIntegrationTest.java)
usa GreenMail 2.1.15 embutido, porta SMTP aleatória no loopback, H2 isolado e
Clock controlado. Não requer Docker e não encaminha e-mails à internet.
Não foi usada simulação HTTP do Postman: a dependência desta etapa é SMTP.
A fixture Postman v1 foi preservada.

Cinco cenários verificam:

- Fluxo HTTP com CSRF real → SMTP → token do e-mail → confirmação → login.
  Inclui revogação da sessão antiga, senha antiga rejeitada e token reutilizado rejeitado.
- Nenhum envio/agendamento antes do commit e envio após commit.
- Rollback sem envio, agendamento ou token persistido.
- Conta desconhecida e intervalo de 60 segundos sem mensagens extras.
- SMTP parado mantendo resposta genérica; nova solicitação após intervalo
  entrega uma mensagem quando o servidor volta.

O teste usa o cliente JavaMailSender real e conexão SMTP real contra GreenMail;
isso comprova o comportamento local, não entrega em um provedor externo.
Docker não estava disponível no ambiente do agente; Mailpit não foi iniciado
nem validado manualmente nesta rodada.

Regressão completa executada em H2 isolado, com as seis migrações:

```bash
./mvnw.cmd verify "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan25-mail;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

```text
Tests run: 74, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 01:41 min
Finished at: 2026-11-02T03:35:55-03:00
```

São 69 testes anteriores e cinco novos de SMTP. Testes locais não substituem
validação no PostgreSQL, revisão de PR nem resultado do CI.

## Fontes

- [Spring Boot — Sending Email](https://docs.spring.io/spring-boot/reference/io/email.html).
- [Mailpit — Docker](https://mailpit.axllent.org/docs/install/docker/).
- [GreenMail — documentação e versões](https://greenmail-mail-test.github.io/greenmail/).
