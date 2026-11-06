# KAN-86 — Recuperação e redefinição integradas

## Ambiente — 06/11/2026

- Angular 21, Node.js 22.16.0 e npm 10.9.2.
- Java 25 e Spring Boot 4.0.9, sem alteração no código do backend.
- H2 em memória `acompanhaai-reset-e2e`, migrações V1 a V7 e API na porta 18084.
- Frontend na porta 14237, com proxy local para a API isolada.
- Mailpit v1.27.4 descartável: SMTP em 11025 e API de captura em 18025,
  ambos publicados somente em 127.0.0.1.
- Perfil `dev,mail-local`, sem carregar o arquivo de credenciais local.
- Cotas de teste: 100 confirmações por IP e 1000 globais por janela; não
  equivalem aos valores padrão de produção. `429` foi exercitado por mocks HTTP.

## Resultado automatizado

```bash
cd frontend
npm run format:check
npm run lint
npm run test:ci
npm run build
```

Os 90 testes passaram. Os 29 novos casos usam `HttpTestingController` com
cliente real, sessão/CSRF e rotas reais da aplicação. Cobrem:

- Solicitação, resposta genérica `202`, validação, concorrência e cancelamento.
- Reenvio manual, espera de 60 segundos e interpretação de `Retry-After`.
- Troca de e-mail mantendo a espera vigente e falhas de reenvio.
- Tokens ausentes, malformados ou ambíguos, fragmento e query legada.
- Remoção do token da URL e ausência de consulta de pré-validação.
- `204`, `400`, `422`, `403`, `429`, `500` e falha de rede.
- Limpeza de token/senhas, invalidação do estado local e descarte do CSRF após sucesso.
- Troca de token na mesma aba, inclusive cancelamento de um pedido anterior pendente.

Lint e formatação aprovados. Build aprovado com os avisos de tamanho de CSS
já existentes nas quatro telas de acesso. Código do backend e fixture Postman v1 não
foram alterados; apenas o guia de e-mail local do backend foi atualizado.

## Navegador, API real e SMTP capturado

Edge/Playwright criou contas fictícias exclusivas, autenticou uma sessão em
outro contexto e solicitou recuperação pela interface. O link foi lido somente
na memória do processo de teste, a partir da mensagem capturada pelo Mailpit.

| Largura | Tema | Solicitação | Redefinição | Sessão anterior | Senha antiga | Senha nova | Reuso do token |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1440px | Claro | 202 | 204 | 401 | 401 | 200 | 400 |
| 1440px | Escuro | 202 | 204 | 401 | 401 | 200 | 400 |
| 390px | Claro | 202 | 204 | 401 | 401 | 200 | 400 |
| 390px | Escuro | 202 | 204 | 401 | 401 | 200 | 400 |

Em desktop claro, o reenvio foi validado depois de decorrido um minuto desde
a captura do primeiro e-mail. Um novo link foi entregue e o anterior recebeu
`400`; o novo permitiu redefinição com `204`. A mesma aba recebeu os dois links.
Esse procedimento aguarda a emissão assíncrona do primeiro token, que pode
ocorrer depois da resposta HTTP `202`.

Uma conta inexistente também recebeu a confirmação genérica `202`, sem a
interface afirmar que a conta existe. A confirmação não comprova entrega.

O link usou `/reset-password#token=...`, compatível com o e-mail local. O token
saiu da barra de endereço; a página incluiu a política de referrer `no-referrer`.
LocalStorage e sessionStorage permaneceram vazios. Nenhum token, senha ou cookie
real é incluído nesta evidência.

## Procedimento reproduzível

Seguir [recuperação na interface](../../frontend/docs/recuperacao-senha.md) e
[e-mail local](../../backend/docs/email-local.md). Usar contas fictícias e um
ambiente descartável; abrir o link recebido no Mailpit, alterar a senha e testar
senha antiga, senha nova, reuso e sessão anterior. Para reenvio, aguardar também
o intervalo por conta contado a partir da emissão do token.

## Limites e pendências

A validade real é decidida pelo backend ao confirmar. Não foi aguardada a
expiração real de 30 minutos nesta execução; o estado de link inválido foi
exercitado por rejeição e reuso, e o tratamento de `400` tem cobertura HTTP.

Não houve execução em PostgreSQL, staging, SMTP externo ou produção. O adaptador
de e-mail atual é de testes local. Rotas privadas, restauração da interface
autenticada e aceite completo da KAN-36 / KAN-86 continuam pendentes.
