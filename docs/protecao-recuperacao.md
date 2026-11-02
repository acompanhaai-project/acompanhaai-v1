# Proteção das rotas de recuperação — KAN-25

## Regras de admissão

As duas rotas possuem cotas independentes. Por padrão, cada rota admite até
10 solicitações por IP e 100 no total por janela fixa de 60 segundos, por processo.
O intervalo de emissão de 60 segundos por conta continua no Service/banco.
Ele é separado das cotas HTTP, que também contam e-mails desconhecidos.

| Rota | Quando excede o limite |
|---|---|
| POST /api/auth/password-reset/request | Mesmo 202 e mensagem genérica; pedido descartado sem consultar a conta |
| POST /api/auth/password-reset/confirm | 429 ProblemDetail com Retry-After em segundos, antes de consultar/consumir o token |

Os valores são configuráveis em `app.password-reset.limits.per-ip` e
`app.password-reset.limits.global`, ambos obrigatoriamente positivos.
As cotas são aplicadas após CSRF e validação do DTO; erros 400/403/422 continuam
com o comportamento anterior. Não substituem proteção de rede ou limite do
tamanho da requisição no proxy. Limitar a recuperação não bloqueia a conta nem login.

PasswordResetRateLimiter usa acesso sincronizado e relógio monotônico para
impedir que solicitações concorrentes ultrapassem a cota. Contadores e IPs
ficam somente em memória. O número de IPs armazenados em cada janela é limitado
pela cota global; os registros são descartados na primeira chamada da próxima
janela. As janelas iniciam na primeira chamada de cada rota.

IP vem de HttpServletRequest.getRemoteAddr. `server.forward-headers-strategy`
está explicitamente em `none`: X-Forwarded-For e Forwarded enviados pelo cliente
não substituem o IP da conexão. Ao implantar atrás de proxy, será necessário
configurar uma fronteira confiável; sem isso, usuários podem compartilhar o IP
do proxy e sua cota. Pessoas sob o mesmo NAT também compartilham a cota por IP.

## Resposta independente da consulta da conta

PasswordResetRequestDispatcher aplica a cota e tenta agendar um trabalho no
passwordResetRequestExecutor: duas threads e fila de 100 trabalhos.
O controller retorna o mesmo 202 após essa tentativa. O callback de fila
executa a consulta da conta, intervalo de emissão, transação e publicação de
evento. Depois do commit, outra fila realiza o envio SMTP.

Nenhum resultado de existência da conta ou de emissão retorna do worker para
a resposta HTTP. Fila cheia e falha de processamento mantêm a resposta genérica;
avisos não incluem e-mail, IP, token, senha ou exceções internas. Não se adicionou
sleep para atrasar threads HTTP. A resposta não aguarda consulta, bloqueio no
banco, geração de token ou envio do e-mail.

Essa arquitetura reduz o canal temporal ligado à consulta da conta. Não prova
latência constante: rede, validação, escalonamento, ocupação das filas e recursos
compartilhados ainda influenciam os tempos. Não foram executados experimentos
estatísticos de enumeração sob carga ou em infraestrutura de produção.

Filas e cotas são locais e voláteis. Reinício perde trabalhos e zera contadores;
múltiplas instâncias multiplicam cotas. Janela fixa admite rajadas nas fronteiras.
Antes de escalar, revisar limitador compartilhado/proxy, entrega durável e
monitoramento de abuso. Estas limitações não são garantias de segurança distribuída.

## Verificação

- PasswordResetRateLimiterTest: cotas por IP/globais, independência entre rotas,
  expiração exata da janela, Retry-After arredondado para cima, concorrência e
  rejeição de configuração inválida.
- PasswordResetDispatcherTest: callback não executado na chamada do controller,
  mesmo caminho para conta existente/desconhecida/repetida, supressão por cota,
  fila saturada sem execução na thread chamadora e falha do worker isolada.
- PasswordResetLimitsHttpIntegrationTest: cotas reais de 2/IP e 3 globais,
  202 idêntico, headers de proxy sem efeito, 429 com Retry-After e token válido
  preservado quando bloqueado, aceito por outro IP com cota disponível.
- Testes HTTP/SMTP anteriores aguardam explicitamente os workers antes de
  conferir efeitos no banco ou na caixa postal. Não assumem emissão no retorno 202.

O perfil test usa cotas amplas (1000/IP e 10000 globais) para a regressão funcional;
o teste específico sobrescreve com cotas pequenas. Nenhum teste desabilita o
limitador e nenhum teste usa valores de latência frágeis como prova de proteção.
Os testes unitários do dispatcher usam uma fila controlada, não SMTP. O fluxo
de entrega continua exercitado com JavaMailSender real e GreenMail local.

## Evidência de execução — 02/11/2026

```bash
./mvnw.cmd verify "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan25-protection;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

```text
Tests run: 83, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 01:48 min
Finished at: 2026-11-02T03:45:49-03:00
```

São nove testes novos (quatro do limitador, quatro do dispatcher e um HTTP),
mais os 74 anteriores. Regressão em H2 e SMTP local, sem PostgreSQL nem provedor
externo. CI, PR e aceite ainda não foram executados.

OpenAPI e configurações YAML lidos com SnakeYAML; 91 referências locais do
contrato resolvidas. Essa verificação não é uma validação completa do padrão OpenAPI.

## Referência

[OWASP — Forgot Password](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html)
orienta resposta genérica, proteção contra envios excessivos e processamento
assíncrono para reduzir diferenças temporais na recuperação.
