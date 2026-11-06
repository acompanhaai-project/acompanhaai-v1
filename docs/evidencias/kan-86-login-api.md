# KAN-86 — Login e saída integrados à API

## Escopo e ambiente — 06/11/2026

- Entrada em duas etapas na rota `/entrar`: e-mail e senha.
- Login e logout por `SessionApi`, sem duplicação da lógica de sessão/CSRF na tela.
- Angular 21, Node.js 22.16.0 e npm 10.9.2.
- Java 25, Spring Boot 4.0.9 e H2 em memória na porta 18084.
- API iniciada sem carregar o arquivo de credenciais local.
- Frontend na porta 14237 com proxy para a API isolada.
- Backend e fixtures Postman preservados.

## Verificação automatizada

```bash
cd frontend
npm run format:check
npm run lint
npm run test:ci
npm run build
```

Os 61 testes passaram, incluindo 16 casos da interface de login/saída com
cliente real e `HttpTestingController`. O lint e a formatação passaram. O build
passou com os avisos de orçamento de CSS já existentes nas quatro telas de acesso.

Os testes novos cobrem avanço local do e-mail, validação da senha sem impor
complexidade de cadastro, payload sem transformar a senha, CSRF, cliques
repetidos e confirmação de acesso somente após a sequência inteira. Incluem
`401`, `403`, `422`, `429`, `500`, falha de rede, erro ao renovar CSRF, recusa
em `GET me`, cancelamento, alteração de e-mail e limpeza da senha.

A saída cobre `204`, falha no pedido de logout e falha apenas ao obter o CSRF
anônimo após `204`. Credenciais/tokens não foram gravados em storage ou console.
Os erros HTTP dessa suíte são simulados e não equivalem a falhas reais provocadas
no servidor.

## Navegador contra a API real

Edge/Playwright com contas fictícias exclusivas, criadas pelo cadastro integrado.

| Largura | Tema | Senha incorreta | Login / GET me | Logout / GET me após saída | Novo login |
| --- | --- | --- | --- | --- | --- |
| 1440px | Claro | 401 | 200 / 200 | 204 / 401 | 200 |
| 1440px | Escuro | 401 | 200 / 200 | 204 / 401 | 200 |
| 390px | Claro | 401 | 200 / 200 | 204 / 401 | 200 |
| 390px | Escuro | 401 | 200 / 200 | 204 / 401 | 200 |

A validação do e-mail não consultou a API. A etapa de senha recebeu foco e
funcionou com Enter e mostrar/ocultar senha. A senha foi limpa após `401` e
o formulário permitiu nova tentativa manual.

Na entrada válida, a ordem observada foi:

1. `GET /api/auth/csrf`.
2. `POST /api/auth/login`.
3. Novo `GET /api/auth/csrf`.
4. `GET /api/users/me`.

O cookie JSESSIONID mudou após o login. A interface mostrou **Acesso confirmado**
somente após `GET me`. LocalStorage e sessionStorage ficaram vazios nos contextos
de teste. O logout respondeu `204`, preparou outro CSRF e o acesso posterior a
`GET me` recebeu `401`. Um segundo login funcionou na mesma sessão do navegador.

## Conferência manual

Com frontend na porta 4200 e backend na porta 8080, cadastrar uma conta de teste,
seguir para o login, avançar com o e-mail e informar uma senha incorreta. Confirmar
o erro e a limpeza do campo. Usar a senha correta, observar a sequência HTTP e
acionar **Sair**. Repetir a entrada e conferir os temas claro/escuro e celular.
Não anexar senhas, cookies ou tokens às evidências.

## Limites

O cartão de confirmação fica em `/entrar`; não foi criado painel de candidaturas.
Recuperação/redefinição continuam simuladas. Rotas privadas, redirecionamento de
sessão expirada e restauração da interface após recarregar a página ainda estão
pendentes. Google/Apple permanecem indisponíveis e não simulam autenticação.

Essa validação usou H2, sem execução contra PostgreSQL, staging ou produção.
A KAN-36 / KAN-86 continua aberta até concluir os demais critérios e o aceite.
