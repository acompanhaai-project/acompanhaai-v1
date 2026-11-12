# KAN-59 — Falhas de IA e repetição de confirmação

## Escopo e ambiente

- Base: `main` em `8c5cb5a` (KAN-57), Spring Boot 4.0.9.
- Suíte `ImportFailureSimulationIntegrationTest`: importação por
  `POST /api/import-tasks`, processamento pela fila (`ImportTaskQueue`) e pelo
  processador real (`JobImportProcessor`), revisão e confirmação pela API.
- Provedor simulado: servidor HTTP em `127.0.0.1` com porta aleatória, no
  formato `generateContent` do Gemini, com respostas roteirizadas por teste.
  Nenhuma chamada sai da máquina; a chave usada é fictícia.
- Proteções do provedor como em produção, com tempos reduzidos: timeout de 1s,
  2 tentativas, espera de 10 ms entre elas, sem limite diário e com limiar de
  pausa alto para os cenários não interferirem entre si.
- Contas fictícias em `example.com`, removidas antes de cada teste.
- Execução local: H2 em modo PostgreSQL. No CI, a mesma classe roda também em
  PostgreSQL 18 com o usuário de runtime restrito
  (`backend-database-role-isolation`) e no job `backend-postgres-test`.

## Falhas do provedor

Em todos os casos a tarefa termina em `READY_FOR_REVIEW`, sem `failure`. Com
falha da IA, a proposta fica só com `description`, e provedor e modelo não
são registrados na tarefa.

| Cenário | Resposta simulada | Chamadas | Resultado |
| --- | --- | --- | --- |
| JSON inválido | HTML com status 200 | 1 | Só a descrição, sem nova tentativa |
| Resposta vazia | `{}` | 1 | Só a descrição |
| JSON truncado | `{"title": "Desenvolvedor Java` | 1 | Só a descrição |
| Texto livre | Frase sem JSON | 1 | Só a descrição |
| Tipo errado | Lista em vez de objeto | 1 | Só a descrição |
| Corte por limite | `finishReason: MAX_TOKENS` com JSON incompleto | 1 | Só a descrição |
| Resposta parcial | Título com evidência, modalidade fora do enum, salário em texto, habilidade ausente do texto | 1 | Só título, evidência do título e habilidade citada |
| Instrução no texto | Modelo obedece à instrução e devolve salário, empresa, modalidade e habilidade citados só na instrução | 1 | Valores injetados descartados; título, local e habilidades reais mantidos; nada salvo sem confirmação |
| 429 persistente | 429, 429 | 2 | Só a descrição |
| 429 recuperado | 429, 200 | 2 | Proposta da IA |
| Timeout | Duas respostas após 3s | 2 | Só a descrição em menos de 10s |
| Indisponível | 503, 503 | 2 | Só a descrição |

O teste de base confirma que a chave vai só no cabeçalho `x-goog-api-key`,
nunca na URL, e que o conteúdo enviado não contém o e-mail da conta.

## Repetição e concorrência

`lateWorkerAndRepeatedConfirmationsCreateASingleApplication`:

1. O worker A obtém a tarefa e perde a posse (lease expirado).
2. A tarefa volta para a fila; o worker B a processa com o provedor simulado.
3. Três confirmações simultâneas pela API: respostas `201`, `200` e `200`,
   todas com o mesmo ID de candidatura.
4. O resultado atrasado do worker A e uma repetição do worker B são
   descartados (`finish` retorna `false`).
5. A recuperação de tarefas interrompidas não altera a tarefa concluída, e uma
   quarta confirmação devolve `200` com a mesma candidatura.

Resultado: uma vaga, uma candidatura, tarefa `COMPLETED` com a proposta
removida.

## Isolamento entre contas

`keepsEachAccountIsolatedThroughTheWholeImport`: a conta B recebe `404` ao
consultar, confirmar e excluir a importação da conta A; a tarefa de A segue
intacta e é confirmada por A. Depois disso, a listagem de candidaturas mostra
1 para A e 0 para B, e nenhuma chamada ao provedor contém o e-mail de A ou B.

## Integridade

Após os cenários de repetição e isolamento, consultas SQL confirmam:

- nenhuma vaga sem candidatura;
- nenhuma vaga com mais de uma candidatura;
- toda candidatura com evento inicial no histórico;
- nenhuma importação `COMPLETED` sem vaga.

O banco também impede esses estados por restrição: `job_id` único em
`tb_application`, chaves compostas de dono entre tarefa, vaga e candidatura e
`CHECK` de `COMPLETED` com vaga e data de revisão.

## Defeito encontrado e corrigido

A primeira execução do cenário de instrução no texto falhou. Com o contrato
anterior, a proposta aceitava salário de R$ 50.000, empresa "Google",
modalidade remota e Kubernetes: valores que só apareciam na frase injetada.
Eles passavam porque estavam escritos no texto, e a evidência apontava para a
própria instrução.

Correção em `JobExtractionContract`: um valor só é aceito se aparecer em
alguma frase sem marcadores de instrução ao modelo. Os testes do contrato
cobrem o descarte e a manutenção de um valor que também aparece fora da
instrução. O caso `injecao-de-instrucoes` do conjunto de avaliação v1
continua coberto.

## Fora do escopo

- E2E em navegador: a tela não muda com falha da IA. A revisão sem proposta é
  a mesma revisão manual, já coberta pelos testes do frontend (KAN-61).
- Provedor real: a avaliação ao vivo fica no KAN-57, sob demanda.

## Execução

```bash
./mvnw test -Dtest=ImportFailureSimulationIntegrationTest \
  "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" \
  "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

| Data | Banco | Testes da classe | Suíte backend |
| --- | --- | --- | --- |
| 11/11/2026 | H2 (modo PostgreSQL) | 15, 0 falhas | 416, 0 falhas, 8 ignorados |

Os 8 ignorados são as integrações que exigem PostgreSQL e a avaliação ao vivo.
A execução em PostgreSQL fica registrada nos artefatos dos jobs do CI do PR.
