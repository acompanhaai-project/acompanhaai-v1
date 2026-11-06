# KAN-86 — Cadastro integrado à API

## Escopo e ambiente

- Cadastro real em `POST /api/users` por `SessionApi.register()`.
- Angular 21, Node.js 22.16.0 e npm 10.9.2.
- Java 25.0.4.1 e Spring Boot 4.0.9.
- API isolada na porta 18084 com H2 em memória e migrações V1 a V7.
- Frontend de teste na porta 14237, com proxy `/api` para a API isolada.
- Sem carregamento do arquivo de credenciais local; PostgreSQL não foi usado.

## Verificações automatizadas

```bash
cd frontend
npm run format:check
npm run lint
npm run test:ci
npm run build
```

Os 45 testes passaram. Nove verificações do cadastro cobrem payload tipado,
CSRF, bloqueio de envios concorrentes, limites, `409`, `422`, `403`, `429`,
`500`, falha de rede, tentativa manual e cancelamento ao destruir a tela.
Esses cenários usam `HttpTestingController`, não respostas de uma API real.

O build passou com os avisos já existentes de orçamento de CSS nas quatro
telas de acesso. O lint passou e a formatação foi conferida.

## Navegador contra a API real

Executado com Edge/Playwright, contas fictícias exclusivas e banco em memória.

| Largura | Tema | Cadastro | E-mail duplicado | E-mail corrigido | GET me após cadastro |
| --- | --- | --- | --- | --- | --- |
| 1440px | Claro | 201 | 409 | 201 | 401 |
| 1440px | Escuro | 201 | 409 | 201 | 401 |
| 390px | Claro | 201 | 409 | 201 | 401 |
| 390px | Escuro | 201 | 409 | 201 | 401 |

O navegador enviou somente `name`, `email` e `password`, com header CSRF.
A resposta não incluiu senha ou hash. O sucesso foi mostrado após `201` e
não prometeu e-mail de confirmação. Após `409`, o formulário foi liberado,
o e-mail ficou destacado e sua correção permitiu novo cadastro.

## Procedimento reproduzível

Iniciar uma API descartável a partir de `backend/`, em PowerShell:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--spring.config.import= --spring.datasource.url=jdbc:h2:mem:acompanhaai-cadastro-check;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE --spring.datasource.username=sa --spring.datasource.password= --server.port=8080"
```

Em outro terminal, executar `npm start` dentro de `frontend/` e abrir
`http://localhost:4200/cadastro`. Cadastrar um e-mail fictício exclusivo;
reabrir a tela, repetir o e-mail e depois corrigi-lo. Acompanhar os resultados
`201`, `409` e `201` em Network. Usar somente portas livres e encerrar a API
ao terminar; o banco em memória é descartado.

## Limites

O cadastro não autentica automaticamente. A integração de login, logout,
recuperação e rotas privadas continua pendente na KAN-36 / KAN-86. Esta
evidência não comprova esses fluxos nem valida o cadastro no PostgreSQL.
