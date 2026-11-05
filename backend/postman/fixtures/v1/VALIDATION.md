# Registro da validacao inicial

Data: 28/10/2026, America/Sao_Paulo.

Alvo: API real Acompanhaai em `http://localhost:18082`, Java 25, Spring Boot 4.0.9, perfil `postgres-local` e PostgreSQL local na porta 5433. Nao foi usado servidor de API simulada nem H2 nesta validacao. O backend nao foi alterado para acomodar os testes.

| Execucao | Requisicoes | Assercoes | Falhas | Codigo de saida |
|---|---:|---:|---:|---:|
| Completa | 90 | 246 | 0 | 0 |
| Apenas preparacao (`--seed`) | 17 | 52 | 0 | 0 |
| Limpeza separada (`--cleanup`) | 12 | 36 | 0 | 0 |

Tambem foram verificados:

- Scripts da colecao compilam sem erro de sintaxe.
- Os dados incorporados na colecao sao identicos a `dados.json`.
- O bloqueio de destinos aceita duas URLs locais e rejeita destino externo, HTTPS externo e URL com caminho adicional (cinco casos).
- As contas criadas pela validacao inicial foram excluidas pelos proprios testes.
- Nenhuma requisicao de imagem foi executada.

Os relatorios detalhados e ambientes de execucao ficam em `postman/reports/`, ignorados pelo Git. O resumo da execucao completa foi preservado em `v1-full.summary.json`; o da preparacao, em `v1-seed.summary.json`. Esses relatorios sao resultados gerados e podem mudar; esta pasta v1 e o contrato congelado.

Este registro descreve a validacao inicial; nao promete que a API futura continuara compativel. Uma falha futura exige investigar a mudanca ou criar uma v2, preservando a v1.
