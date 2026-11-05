# KAN-39 — cliente HTTP de sessão, CSRF e erros

Validação em 05/11/2026, Windows 11, Node.js 22.16.0, npm 10.9.2,
Angular 21.2.26, TypeScript 5.9.3 e Vitest 4.1.12.

## Critérios e evidências

| Critério                                      | Entrega                                                 | Verificação                                                            |
| --------------------------------------------- | ------------------------------------------------------- | ---------------------------------------------------------------------- |
| Comunicação centralizada com credenciais/CSRF | Interceptor, CsrfStore, RequestQueue e SessionApi       | Ordem de obtenção/renovação do token, fila de escritas e URLs externas |
| Mesma origem/proxy e política de CORS         | proxy.conf.json para /api/**, integrado a ng serve      | Proxy real no Edge para API isolada; nenhum CORS externo liberado      |
| ProblemDetail e erros de segurança            | ApiError com kind, mensagem, campos, code e Retry-After | Casos 401/403/409/422, rede, HTML/500 e 429                            |

## Suíte Angular

- 42 testes aprovados em quatro arquivos, sem falhas: oito anteriores e
  34 novos casos de HTTP, sessão, erros e coordenação.
- Login só anuncia autenticação depois de novo CSRF e GET me.
- Uma escrita pendente aguarda o fluxo completo de login e usa o novo token.
- Cadastro não autentica; logout prepara uma sessão anônima.
- 401 privado limpa o estado; um 401 atrasado não apaga uma nova autenticação.
- 403 não repete a escrita; a próxima ação explícita solicita novo token.
- 409/422 mantêm a sessão e entregam o erro ao consumidor.
- Falha de CSRF impede envio da escrita; resposta de token inválida é recusada.
- Cancelamento durante obtenção do token cancela o HTTP e libera a próxima
  operação sem enviar a escrita cancelada.
- URLs externas, assets e caminhos que normalizam para fora de /api não
  recebem credenciais/CSRF adicionados pelo interceptor.

Esses testes usam HttpTestingController, sem rede real. A suíte de navegação
anterior permanece incluída. Lint, formatação e build de produção aprovados;
bundle inicial de 270,82 kB, abaixo do limite de aviso de 500 kB.

## Proxy e navegador reais

API executada a partir do JAR local em um processo próprio, com H2 em memória,
porta temporária e configuração sem importações privadas. O proxy do Angular
usou a configuração versionada com somente o target trocado para essa porta.
Nenhum acesso ao PostgreSQL local ou a contas existentes.

Edge headless 156.0.4314.58: 20 verificações com requisições fetch feitas pelo
navegador à mesma origem do frontend. Conferidos 401 anônimo, cadastro sem
CSRF recusado, cadastro válido sem autenticação automática, login inválido e
válido, token antigo recusado, GET me, 422, criação/duplicidade 409, exclusão,
logout e remoção da conta fictícia.

Os headers Set-Cookie de sessão anônima e login foram observados, sem registrar
seus valores no relatório. A rotação do JSESSIONID foi confirmada. HttpOnly
foi conferido no header e a sessão não estava acessível em document.cookie
durante a autenticação. API e proxy de QA foram encerrados no final.

Resultado: [kan-39-proxy-browser.json](kan-39-proxy-browser.json).
Run ID: `910f88d61bc84bb189aefc62735f8f3b`.
Roteiro temporário e logs locais em target, ignorados pelo Git.

Essa verificação valida proxy/cookies/contrato no navegador. Ela não executa
o SessionApi por uma tela real: o fluxo do serviço foi validado separadamente
pela suíte Angular. E2E de telas de conta continua na KAN-40.

## Instalação e comandos

`npm ci` foi confirmado com npm 10.9.2. Vitest foi atualizado de 4.0.8 para
4.1.12 porque o runner antigo falhava na inicialização antes de executar os
testes. A atualização inicial usou npm 11 temporário devido a erro interno do
npm 10; o lockfile foi ajustado pelo npm 10 e a instalação limpa foi aprovada.
Nenhuma versão global foi alterada, e não foi usado legacy-peer-deps.

O frontend agora fixa LF via .gitattributes/.editorconfig. Também foi corrigida
a conversão CRLF no checkout Windows da fixture v1: os bytes originais foram
restaurados diretamente do HEAD, e os nove hashes do manifesto original
foram conferidos. O manifesto e o conteúdo versionado da v1 não mudaram.
Migrações e arquivos congelados passam a ter LF explícito no .gitattributes
da raiz, mantendo o checkout reproduzível entre Windows e Linux.

Na pasta frontend:

```bash
npm ci
npm run lint
npm run format:check
npm run test:ci
npm run build
```

O [guia do cliente](../../frontend/docs/cliente-http-kan-39.md) descreve como
iniciar backend/frontend e integrar as futuras telas. Para repetir o smoke
manualmente, obter CSRF pela origem do frontend, cadastrar uma conta fictícia,
entrar, obter outro CSRF, consultar me, conferir erros, sair e limpar os dados.
Recomenda-se ambiente descartável, pois o perfil dev padrão usa H2 em arquivo.

## Limites e próximo aceite

Nenhuma tela ou identidade visual foi modificada. UX/UI definitivo será
definido antes das telas completas. Não há guard/rota privada
entregue nesta tarefa nem consulta de sessão automática no esboço público.

Backend, contrato e fixtures Postman não foram alterados. Não houve nova
regressão Postman completa nesta tarefa. PR, CI remoto e aceite
continuam pendentes. Nenhum deploy foi realizado.
