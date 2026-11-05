# Acompanhaai

Aplicação para acompanhar candidaturas, com backend Java 25/Spring Boot 4 e
frontend Angular/TypeScript.

## Estrutura

```text
backend/       API, Maven, banco, scripts e testes Postman
frontend/      Interface Angular e ferramentas próprias
docs/          Planejamento e documentação compartilhada
.github/       Workflows de CI e template de pull request
```

- [Backend: execução, configuração e endpoints](backend/README.md)
- [Frontend: execução e componentes](frontend/README.md)
- [Documentação e plano de entrega](docs/README.md)
- [Estrutura e migração dos diretórios](docs/estrutura-repositorio.md)
- [Contrato OpenAPI](backend/docs/openapi.yaml)
- [Guia de integração HTTP](docs/integracao-frontend.md)
- [Contribuição e convenções Git](CONTRIBUTING.md)

## Executar localmente

Backend, em um terminal na raiz do repositório:

```bash
cd backend
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

No Linux/macOS, usar `./mvnw`. Configurações privadas e arquivos de exemplo
agora ficam em `backend/`. A API local permanece em `http://localhost:8080`.
Para PostgreSQL e permissões separadas, consultar o guia do backend.

Frontend, em outro terminal na raiz:

```bash
cd frontend
npm ci
npm start
```

Abrir [http://localhost:4200](http://localhost:4200). O esboço atual ainda não
faz chamadas à API. O UX/UI definitivo será definido pelo usuário antes das
telas completas.

## Verificar

Executar Maven e scripts de QA dentro de `backend/`; executar npm dentro de
`frontend/`. Não há pom ou package.json agregador na raiz.
Consultar os guias de cada módulo para comandos e configuração dos testes.

Os workflows permanecem em `.github/workflows/`, com execução e artefatos do
backend em `backend/`. A fixture Postman v1 foi movida e preservada integralmente;
seus comandos históricos devem ser executados a partir de `backend/`.

Arquivos de credenciais continuam ignorados pelo Git. O `target/` antigo da
raiz guarda ferramentas e evidências locais; novos builds Maven e roteiros de
QA geram resultados em `backend/target/`.
