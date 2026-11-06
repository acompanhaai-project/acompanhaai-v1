# KAN-39 — cliente HTTP, sessão e CSRF

## Estrutura

| Arquivo / serviço            | Responsabilidade                                                         |
| ---------------------------- | ------------------------------------------------------------------------ |
| core/http/api-interceptor.ts | Escopo da API, credenciais, CSRF e normalização de falhas                |
| core/http/csrf-store.ts      | Token em memória, obtido pelo backend HTTP sem recursão pelo interceptor |
| core/http/request-queue.ts   | Coordenação das escritas e transições de sessão                          |
| core/http/api-error.ts       | Modelo ApiError para ProblemDetail e status/error                        |
| core/auth/session-api.ts     | Cadastro, login, consulta da sessão e logout                             |
| core/auth/session-state.ts   | Signals de usuário/status/revisão, sem armazenamento persistente         |
| proxy.conf.json              | /api/** encaminhado para 127.0.0.1:8080 no desenvolvimento               |

O cliente usa `provideHttpClient` e interceptor funcional. O mecanismo Angular
baseado em cookie XSRF foi desabilitado porque a API usa CSRF de sessão obtido
por JSON, com header `X-CSRF-TOKEN`. Isso não desabilita o CSRF da API.

## Sequência e concorrência

1. Uma escrita entra na fila. Se faltar token, primeiro executa GET /api/auth/csrf.
2. O navegador preserva JSESSIONID; a aplicação não lê o cookie HttpOnly.
3. Login obtém token, envia credenciais, descarta o token anterior, busca outro
   e confirma a conta em GET /api/users/me antes de anunciar autenticação.
4. Escritas aguardam o término desse fluxo. Logout também passa pela fila,
   limpa o estado após 204 e prepara o CSRF para uma nova sessão anônima.
5. GET/HEAD/OPTIONS não recebem header CSRF; leituras comuns podem ser paralelas.

As escritas são serializadas neste incremento. A fila preserva eventos HTTP
e trata cancelamento: cancelar um item pendente não ultrapassa o item ativo;
uma falha libera a próxima operação. Não há repetição automática de escritas.
O contexto IN_SESSION_FLOW evita entrar na fila novamente durante um fluxo
que já está coordenado pelo SessionApi. É um detalhe interno, não uma opção
de autenticação ou autorização.

As consultas `GET me` feitas por `refresh()` e pela confirmação de login também
levam esse contexto. Nesses casos, o guard ou formulário trata o `401`. Outros
`401` privados publicam a expiração da revisão corrente da sessão, sem repetir
a operação. `SessionNavigation`, inicializado no componente raiz, redireciona
somente quando a rota ativa está marcada como privada.

O interceptor só modifica URLs da mesma origem cujo caminho normalizado seja
/api ou comece com /api/. URLs externas e assets não recebem credenciais ou
CSRF adicionados por ele. Não configurar serviços externos com esses headers.

## Estado de sessão

- `unknown`: ainda não consultado ou transição sem confirmação. Não tratar como autenticado.
- `authenticated`: conta confirmada no servidor.
- `anonymous`: servidor recusou acesso privado com 401 ou logout concluído.

`SessionApi.refresh()` consulta GET me, devolvendo null em 401; outras falhas
continuam sendo propagadas. Cadastro retorna o DTO, mas não autentica.
Um login que não consegue renovar CSRF ou confirmar a conta não anuncia sucesso.
O estado fica somente em memória e deve ser consultado novamente ao recarregar
as páginas privadas. Os guards agora consultam a API ao abrir `/conta` e ao
restaurar uma sessão ainda desconhecida na entrada. As demais páginas públicas
continuam acessíveis sem consulta automática da sessão.

Uma revisão de sessão impede que um 401 atrasado de uma requisição anterior
apague a nova autenticação. A sessão de outra aba pode mudar o cookie;
401/403 devem ser tratados normalmente. Nenhum segredo é sincronizado em storage.

## Uso nas próximas telas

Injetar SessionApi para acesso e SessionState para renderizar o estado.
Os métodos devolvem Observables frios: cada inscrição executa a operação.
Subscrever uma vez por envio e tratar erro/conclusão no formulário.

Para outros domínios, criar serviços tipados que usem HttpClient com URLs
relativas `/api/...`. O interceptor aplica o contrato compartilhado.
Não duplicar a lógica de cookie, CSRF ou repetição em cada componente.
Para FormData, deixar o navegador definir Content-Type e boundary.

As telas/guards de acesso pertencem à KAN-36; perfil/foto à KAN-37. Cadastro,
login e logout usam `SessionApi` e o interceptor de sessão/CSRF. Recuperação e
redefinição usam `PasswordResetApi`, com os mesmos mecanismos. Após redefinição,
o cliente limpa a sessão e o CSRF locais. A rota `/conta` é protegida e restaura
a identidade após recarregar. Falhas de verificação oferecem nova tentativa;
`401` em operações privadas redireciona ao login com destino interno validado.

## Erros para os formulários

| Status | ApiError.kind  | Tratamento                                                                    |
| ------ | -------------- | ----------------------------------------------------------------------------- |
| 0      | network        | Preservar dados; mostrar falha de conexão                                     |
| 401    | authentication | Em rota privada, estado anônimo; no login, mostrar credenciais inválidas      |
| 403    | forbidden      | Token local descartado; conferir sessão antes de uma nova tentativa explícita |
| 409    | conflict       | Reconsultar/reconciliar; code distingue duplicidade de URL quando presente    |
| 422    | validation     | Exibir fieldErrors quando houver; aceitar mensagem geral sem campos           |
| 429    | rate-limit     | Usar retryAfterSeconds quando disponível; nenhuma repetição é agendada        |
| 5xx    | unexpected     | Mensagem genérica; detalhes internos e HTML não são exibidos                  |

ApiError contém status, kind, message, fieldErrors, code e retryAfterSeconds.
Não contém a requisição, senha, cookie ou corpo bruto da resposta.
Mensagens devem ser renderizadas como texto; não usar innerHTML.
Em operações de dados, 403/409/422 não apagam a sessão autenticada conhecida. Um 403 descarta o
CSRF local; apenas uma nova ação solicita outro token antes de escrever.
Uma falha de rede não prova que uma escrita deixou de acontecer no servidor;
conferir os dados atuais antes de repetir uma operação cujo resultado é incerto.

## Execução local

Em um terminal na raiz:

```bash
cd backend
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Em outro terminal na raiz:

```bash
cd frontend
npm ci
npm start
```

Navegar em localhost:4200 e chamar /api pelo mesmo endereço. Não chamar
localhost:8080 diretamente a partir das telas, pois isso muda a origem.
O proxy não exige liberar CORS no Spring. Alterações no proxy exigem reiniciar
ng serve. Se a porta da API mudar, ajustar target em proxy.conf.json.

O proxy é exclusivo do servidor de desenvolvimento. Em produção será preciso
rotear /api para o backend pelo host/reverse proxy, usando HTTPS e cookies Secure;
o build Angular não provisiona esse roteamento. Nenhum CORS externo foi liberado.

## Verificação

```bash
npm run lint
npm run format:check
npm run test:ci
npm run build
```

Os testes usam HttpTestingController, incluindo a ordem de login/CSRF/me,
isolamento de URLs externas, cancelamento da fila e erros negativos.
Vitest 4.1.12 substitui 4.0.8: o runner antigo falhava antes dos testes devido
ao limite de inicialização de cinco segundos nesta máquina. Um npm 11 temporário
foi usado para atualizar o lockfile porque npm 10 falhou no resolvedor;
a instalação global de npm não foi alterada. O lockfile foi depois ajustado
com npm 10 para incluir dependências opcionais de plataforma, e `npm ci`
foi confirmado com npm 10.9.2, sem ignorar conflitos de peers.
As terminações de linha do frontend foram fixadas em LF para alinhar Git,
editor e Prettier no Windows. A proteção LF dos arquivos congelados do backend
preserva seus bytes originais e o manifesto, sem mudanças no contrato.

Referências oficiais: [interceptors](https://angular.dev/guide/http/interceptors),
[testes HTTP](https://angular.dev/guide/http/testing) e
[proxy de desenvolvimento](https://angular.dev/tools/cli/serve#proxying-to-a-backend-server).
O [contrato do projeto](../../docs/integracao-frontend.md) continua sendo a
referência dos endpoints, sessão e CSRF.
Resultados e limites estão na [evidência KAN-39](../../docs/evidencias/kan-39-cliente-http.md).
