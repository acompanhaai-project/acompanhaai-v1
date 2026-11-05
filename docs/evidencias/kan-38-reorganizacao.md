# KAN-38 — separação de backend e frontend

Validação local em 05/11/2026, Windows 11, Java Corretto 25.0.4.1,
Maven Wrapper 3.9.16, Node.js 22.16.0 e PostgreSQL 18.6.

## Alterações

- Backend movido para `backend/`: src, Maven, scripts, Postman, Compose,
  exemplos, configurações locais e documentação técnica.
- Frontend mantido em `frontend/`. Planejamento, documentação compartilhada,
  convenções e workflows permanecem na raiz.
- Caminhos de execução, cache e upload dos cinco checks de backend atualizados.
- Gate de migrações adaptado para reconhecer a raiz histórica e a nova,
  verificando conteúdo pelo mesmo nome relativo, sem relaxar a imutabilidade.
- Nome Compose `acompanhaai` explicitado para preservar o nome padrão anterior.
- Links relativos e instruções de execução/IDE atualizados.

## Resultados

| Verificação                                     | Resultado                                                                                          |
| ----------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| Maven verify/H2, executado dentro de backend    | BUILD SUCCESS; 196 casos, 189 executados, sete ignorados por condição, zero falhas/erros           |
| Testes do gate de migrações                     | Dez aprovados, incluindo relocação intacta, edição disfarçada e duplicação                         |
| Comparação do gate com main e com HEAD anterior | PASS, sem violações                                                                                |
| Build/check da coleção v2                       | 97 requisições da coleção e scripts conferidos                                                     |
| Guardas do executor v2                          | Nove aprovadas                                                                                     |
| Regressão HTTP com PostgreSQL descartável       | 103 requisições, 251 asserções, zero falhas                                                        |
| Falha de histórico injetada                     | Rollback confirmado pelo fluxo completo                                                            |
| Limpeza de dados e isolamento                   | Registros da fixture removidos; sentinela não relacionada preservada                               |
| Ciclo de vida de QA                             | API e cluster PostgreSQL encerrados pelo roteiro                                                   |
| Integridade pré/pós-movimento                   | 17 arquivos protegidos idênticos byte a byte; nove hashes do manifesto v1 conferidos pelo executor |
| Configurações privadas e gerados                | Ignorados pelo Git nos novos caminhos                                                              |
| Workflows                                       | YAML válido, cinco jobs, cache Maven em backend/pom.xml e uploads em backend/...                   |
| Documentação e OpenAPI                          | Links locais conferidos; contrato válido no novo caminho                                           |

Maven gerou o JAR em `backend/target/acompanhaai-0.0.1-SNAPSHOT.jar`.
A execução final utilizou banco H2 explícito, sem acessar o PostgreSQL local.

## Reproduzir

A partir da raiz, em PowerShell 7:

```powershell
cd backend
node --test scripts/check-migrations.test.cjs
node scripts/check-migrations.cjs (git rev-parse main)
./mvnw.cmd --batch-mode --no-transfer-progress verify "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-layout;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
./scripts/test-postman-v2.ps1 -UseCiRunner
```

O último comando requer PostgreSQL nativo e dependências npm da v2 já
instaladas, conforme seu README. Cria um cluster próprio; não aponta para
o PostgreSQL de desenvolvimento. Não redirecionar toda a saída do wrapper,
pois processos nativos podem herdar pipes de redirecionamento.

Run ID da regressão: `eaabda2e9d0a482e87744742362e7efb`.
Resumo sanitizado: [kan-38-reorganizacao-postgres.json](kan-38-reorganizacao-postgres.json).
Logs e relatórios completos permanecem ignorados em backend/target e
backend/postman/reports. Não houve leitura de conteúdo das configurações privadas.

## Limites

Nenhum volume Docker foi acessado. A manutenção do nome Compose foi revisada
estaticamente; Docker não está disponível neste ambiente. Os checks remotos
do GitHub Actions devem ser consultados no PR. Esta evidência registra a
validação local, sem deploy.
O Angular não recebeu alterações funcionais nesta etapa; suas oito verificações
anteriores estão na [evidência da base](kan-38-base-angular.md).
