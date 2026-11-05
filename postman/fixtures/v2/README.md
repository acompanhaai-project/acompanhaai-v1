# Regressão API v2 — KAN-33

Coleção Postman 2.1 contra a API real do Acompanhaai. Não é um servidor mock.
Contém 97 requisições declaradas e scripts de teste. Não chama endpoints de imagem.
V1, seu executor, hashes, dados e dependências continuam congelados.

## Arquivos

- `dados.json`: dados-base fictícios e contrato dos detalhes da vaga.
- `build.cjs`: fonte declarativa dos cenários; gera scripts legíveis com o Prettier fixado.
- `acompanhaai-v2.postman_collection.json`: coleção gerada, importável no Postman.
- `local.postman_environment.json`: ambiente sem credenciais, somente alvo local e flag de falha SQL.
- `run.cjs`: executor Newman com validação de alvo, integridade v1, guardas e resumo sanitizado.
- `guards.test.cjs`: nove testes unitários de alvo permitido, IDs antigos e bloqueio de exclusão indevida.
- `package.json` e `package-lock.json`: dependências próprias da v2.
- `history-fault.sql`: trigger exclusivo do banco descartável, utilizado para testar rollback.
- `../../../scripts/test-postman-v2.ps1`: provisiona PostgreSQL/API exclusivos, executa e encerra.

Alterações de cenários devem ser feitas em `build.cjs` e `dados.json`. Gerar
novamente a coleção e executar a conferência antes de commitar:

```powershell
Push-Location postman/fixtures/v2
npm.cmd ci --ignore-scripts --no-audit --no-fund
npm.cmd run build
npm.cmd run check
npm.cmd run test:guards
Pop-Location
```

O check compara o artefato com as fontes e compila todos os scripts de eventos.
O roteiro completo também executa os nove testes de guardas antes de iniciar a API.
Newman 6.2.3 é fixado independentemente da v1; Prettier 3.9.10 é usado apenas
na geração/conferência. Dependências transitivas legadas do Newman emitem avisos
de depreciação; este roteiro não atualiza o pacote congelado da v1.

## Execução completa e reproduzível

Requisitos: Java 25, Node.js 22, PowerShell 7 e PostgreSQL 18 instalado.
Na raiz do repositório, após instalar as dependências da pasta v2:

```powershell
./scripts/test-postman-v2.ps1
```

Para outra instalação local do PostgreSQL:

```powershell
./scripts/test-postman-v2.ps1 -PostgresBin 'C:\Program Files\PostgreSQL\18\bin'
```

O executor:

1. Empacota a API com Maven, sem repetir a suíte Java existente.
2. Cria um cluster PostgreSQL descartável em `target/kan33-postgres-<runId>`
   e escolhe portas locais efêmeras para banco e HTTP.
3. Usa YAML versionado sem imports locais, app com usuário restrito e Flyway
   com migrator separado. Aplica V1–V7 e callback existentes.
4. Confirma no log que a API criada iniciou sua própria porta antes de chamar HTTP.
5. Instala o trigger de falha apenas nesse banco e para o e-mail da execução.
6. Cria uma conta sentinela, vaga, habilidade, histórico e tarefa que não pertencem à coleção.
7. Executa a coleção com falha SQL habilitada.
8. Confere por SQL somente os IDs das contas criadas, verifica a remoção de seus
   dados e confirma a preservação da sentinela.
9. Encerra a própria API e o próprio cluster em `finally`.

Executar diretamente no terminal, sem redirecionar a saída completa do script
no Windows. O roteiro imprime o diretório de evidências. Não recebe a URL de um
banco existente, não lê senhas locais e não para o serviço PostgreSQL do usuário.
Os clusters usam autenticação trust exclusivamente em loopback e ficam desligados
ao final; dados de teste permanecem somente no diretório ignorado de `target`.

## Dados e autenticação

- Ana QA V2: `qa.kan33.a+<runId>@example.test`.
- Bruno QA V2: `qa.kan33.b+<runId>@example.test`.
- `runId`: GUID de 32 caracteres hexadecimais novo a cada execução.
- Senha fictícia derivada: `FixtureV2!<runId>`. Nunca usar em conta real.
- Oito candidaturas iniciais de A, uma em cada etapa; B recebe uma candidatura
  com link equivalente ao de A para verificar escopo por conta.
- Vaga principal: salário 5000.25, BRL/mês, HYBRID, Porto Alegre/RS,
  hard skills Java/Spring Boot e soft skill Comunicação, escrita sem acento nos dados-base.
- Data de candidatura fictícia: 15/01/2026; SAVED usa data nula.

IDs são recebidos da API. Cookies e tokens CSRF são separados por conta e
renovados após login; o cookie jar de cada requisição está desativado.
Variáveis de IDs de execuções anteriores são apagadas na preparação.
Não há sessões, tokens ou senhas reais nos arquivos versionados.

## Cobertura e limites

| Pasta                        | Verificação                                                                                                             |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| 01 — Preparação              | Cadastro, login, CSRF e identidade de A/B                                                                               |
| 02 — Cadastro e consultas    | Oito etapas, detalhes, salário nulo, busca por título/empresa, filtros e paginação                                      |
| 03 — Validações              | Salário, moeda/período, habilidades, tamanhos, SAVED/data, enum, URL e duplicidade                                      |
| 04 — Edição e isolamento     | Substituição, versão antiga, conflitos sem alterações parciais, histórico, 401/403/404 e mesmo link em contas distintas |
| 05 — Rollback PostgreSQL     | Falha real ao inserir histórico após flush; estado completo reconsultado                                                |
| 06 — Substituição e exclusão | Observações sem evento, limpeza de campos, busca literal, exclusão e novo cadastro do link                              |
| 99 — Limpeza                 | Relogin, identidade confirmada, exclusão apenas das contas criadas e revogação das sessões                              |

O cenário de rollback usa `history-fault.sql`: o trigger encontra a candidatura
da conta A desta execução com observação `KAN33_SIMULATED_HISTORY_FAILURE` e
gera SQLSTATE 23514 no INSERT de histórico. A API já enviou suas alterações
por flush e precisa reverter salário, habilidades, notas, etapa e versão.
O SQL recusa execução sem o usuário `acompanhaai_qa_admin` ou com o marcador
de runId ainda não substituído por 32 caracteres hexadecimais.
Não há endpoint de falha, flag ou modificação no código de produção.
O SQL não deve ser aplicado a desenvolvimento compartilhado ou produção.

A coleção não testa concorrência simultânea nem colisão SHA-256 forçada.
Esses cenários continuam cobertos pelos testes Java da KAN-32.
Desempate com timestamps exatamente iguais é testado em Java na KAN-31;
a coleção verifica a ordenação e ausência de repetição nas páginas observadas.

## Limpeza e relatórios

Antes de qualquer DELETE, uma consulta auxiliar confirma ID e e-mail da sessão.
O alvo só pode ser uma conta marcada como criada com HTTP 201 nesta execução;
IDs recebidos anteriormente não são reutilizados. O teste de DELETE com outra
conta aponta apenas para uma candidatura criada pelo próprio cenário.

O Newman continua após falhas de asserção para alcançar a pasta de limpeza.
Ao finalizar, o executor tenta uma limpeza adicional apenas se a conta tiver
criação comprovada e ainda não tiver sido removida. Reautentica, confirma a
identidade e só então exclui; divergência bloqueia a exclusão.
Um processo encerrado abruptamente pode impedir essa tentativa. O roteiro
descartável não depende de limpar dados de um banco externo para encerrar.

Resumo: `postman/reports/v2/<runId>/summary.json`. Relatório do provisionamento,
verificação SQL e ciclo de vida: `target/kan33-postgres-<runId>/summary.json`.
Não exportar o ambiente nem o relatório bruto do Newman: contêm estado de sessão.
O executor salva somente métricas, estágios de falha e IDs/e-mails fictícios,
sem cookies, tokens, senhas ou corpos completos de resposta.

Há 97 requisições da coleção e 6 consultas auxiliares de identidade antes dos
DELETEs, totalizando 103 chamadas contadas pelo Newman no modo completo.
A primeira execução encontrou essa diferença de contabilização; as asserções
passaram e o executor foi corrigido para distinguir os dois tipos de chamada.

## Uso manual no Postman

Importar a coleção e o ambiente v2, apontar `baseUrl` para uma API local de teste
que você iniciou e executar a coleção inteira na ordem. Manter `v2_faultEnabled`
desativado nesse uso: a requisição que depende do trigger será pulada.
Esse modo cobre o contrato HTTP comum e a limpeza, mas não comprova rollback
após flush nem a preservação da sentinela no PostgreSQL.
Não habilitar a flag manualmente sem o banco descartável e o trigger correspondente.

Para executar só o contrato HTTP contra uma API local já iniciada:

```powershell
node postman/fixtures/v2/run.cjs --base-url http://127.0.0.1:18083
```

O executor recusa alvos remotos, HTTPS, porta implícita, caminho ou credenciais
na URL. A integração no CI é a KAN-34; esta entrega não altera os workflows.
