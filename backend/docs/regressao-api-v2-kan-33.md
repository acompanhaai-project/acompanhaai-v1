# Regressão API v2 — KAN-33

## Objetivo

Consolidar o contrato entregue nas KAN-30/31/32 em uma nova coleção Postman,
preservando integralmente a v1, seu executor, manifesto e dependências.

Implementação e comandos estão no [guia da fixture v2](../postman/fixtures/v2/README.md).
A coleção usa dados fictícios, IDs retornados pela API e autenticação separada
por conta. Não chama endpoints de foto nem envia arquivos.

## Matriz de aceite

| Critério                    | Evidência                                                                       |
| --------------------------- | ------------------------------------------------------------------------------- |
| Coleção e dados v2 próprios | `postman/fixtures/v2`, gerador determinístico e lockfile independente           |
| V1 preservada               | 9 hashes SHA-256 verificados antes e depois de cada execução                    |
| Novos campos e sucesso      | Cadastro/consulta/edição de salário, modalidade, localização e habilidades      |
| Payload inválido            | Salário negativo/precisão, moeda/período, duplicatas, limites e URL             |
| Autorização                 | Sessões A/B, 401 sem sessão, 403 sem CSRF e 404 em acesso cruzado               |
| Versão antiga e duplicidade | 409, reconsulta do estado e histórico sem alterações parciais                   |
| Rollback de persistência    | Trigger restrito à conta da execução gera falha real no histórico após flush    |
| PostgreSQL de teste         | Cluster efêmero, API própria e roles app/migrator separadas                     |
| Limpeza restrita            | DELETE protegido por identidade, conferência SQL por IDs e sentinela preservada |

A suíte não substitui os testes Java de concorrência, colisão de hash ou
desempate determinístico. O trigger é ferramenta do banco descartável, sem
alterações ou rotas extras na API de produção.

## Definition of Done

Registrar versão, ambiente, resultado e resumo sanitizado após executar.
Revisão do PR, CI remoto e aceite do cartão continuam necessários para concluir.
Automatização da v2 no pipeline pertence à KAN-34.

## Evidência local — 05/11/2026

Execução final pelo roteiro `scripts/test-postman-v2.ps1`, com PostgreSQL 18.6,
Java Corretto 25.0.4.1, Spring Boot 4.0.9, Node.js 22.16.0 e Newman 6.2.3.
Dependências próprias instaladas e conferidas com `npm ci`; coleção regenerada
e comparada com as fontes.

| Verificação                          | Resultado                                                              |
| ------------------------------------ | ---------------------------------------------------------------------- |
| Guardas do executor e da coleção     | 9 testes aprovados                                                     |
| Requisições da coleção               | 97 executadas                                                          |
| Consultas auxiliares antes de DELETE | 6 executadas                                                           |
| Chamadas HTTP contadas pelo Newman   | 103                                                                    |
| Asserções                            | 251 aprovadas; zero falhas                                             |
| Rollback                             | Erro SQL real de histórico após flush; estado e histórico preservados  |
| Limpeza                              | As duas contas removidas, sem registros próprios nas consultas por IDs |
| Sentinela fora da coleção            | Conta, vaga, candidatura, histórico, habilidade e tarefa preservados   |
| Integridade v1                       | 9 hashes conferidos antes/depois; arquivos e dependências preservados  |
| Processos                            | API própria e cluster descartável encerrados                           |

Resumo sanitizado e hashes do artefato executado:
[kan-33-postgres-v2.json](evidencias/kan-33-postgres-v2.json).

A primeira rodada revelou um erro de contabilização do executor: consultas
auxiliares também entram nas estatísticas do Newman. As 251 asserções passaram,
a contagem foi corrigida para separar 97 itens e 6 guardas, e a versão final foi
reexecutada com sucesso. O código da API e as migrações não foram alterados.
O build foi empacotado com `-DskipTests`; a suíte Java existente não foi repetida
nesta tarefa, que valida a API real por HTTP e o banco descartável.

CI remoto, revisão do PR e aceite continuam pendentes.
