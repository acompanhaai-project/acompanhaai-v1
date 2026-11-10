# Importação assíncrona de vagas — KAN-53

O [OpenAPI de importação](openapi-importacao.yaml) define as rotas planejadas.
A V5 já contém a tabela de tarefas. A KAN-50 implementa a fila, o worker
e a limpeza. As rotas e o processador de coleta e extração serão integrados
nas próximas tarefas. A revisão e a confirmação fazem parte da KAN-55.

## Máquina de estados

| Estado atual | Próximo estado | Condição |
| --- | --- | --- |
| Nova tarefa | `PENDING` | Entrada URL ou TEXT validada e persistida para a conta autenticada. |
| `PENDING` | `PROCESSING` | Worker reivindica tarefa elegível com token e prazo de posse. |
| `PROCESSING` | `PENDING` | Falha transitória ou posse expirada, com tentativa restante. Libera a posse e agenda a próxima execução. |
| `PROCESSING` | `READY_FOR_REVIEW` | Extração gerou uma proposta válida, ainda sujeita à revisão. |
| `PROCESSING` | `NEEDS_INPUT` | A página é inacessível ou não fornece conteúdo útil e a descrição colada pode viabilizar a extração. |
| `PROCESSING` | `FAILED` | Falha definitiva ou tentativas esgotadas, inclusive após a expiração da última posse. |
| `READY_FOR_REVIEW` | `COMPLETED` | Confirmação pelo proprietário persiste a vaga revisada e conclui a tarefa na mesma transação. |

Qualquer transição fora da tabela deve ser recusada. `NEEDS_INPUT`,
`FAILED` e `COMPLETED` não voltam à fila. O fallback de `NEEDS_INPUT`
cria outra tarefa com `inputType: TEXT`, sem buscar a URL. O resultado
em `READY_FOR_REVIEW` é uma proposta. A confirmação repetida da mesma
tarefa não cria outra vaga. Um conflito de validação ou de persistência
durante a confirmação preserva a tarefa para revisão, enquanto vigente.

### Posse e concorrência

- A reivindicação usa `FOR UPDATE SKIP LOCKED`. Ela atualiza o estado, gera
  um novo token, registra o prazo e incrementa as tentativas na mesma
  transação. Só uma instância obtém a posse de cada tarefa.
- O worker executa uma tarefa por instância por vez e aguarda 5 segundos
  entre ciclos. Sem um `ImportTaskProcessor`, mantém as tarefas na fila
  e executa somente a recuperação e a limpeza. O processador deve
  respeitar o prazo recebido. A posse dura até 2 minutos e termina antes
  se a tarefa expirar.
- São permitidas 3 tentativas. O reagendamento aguarda 15 segundos após
  a primeira e 30 após a segunda.
- A gravação do worker exige tarefa existente e vigente, estado
  `PROCESSING`, token correspondente e posse vigente. Se a atualização
  afetar zero linhas, descarta o resultado. Transições que saem de
  `PROCESSING` limpam token e prazo de posse.
- A recuperação só altera uma posse vencida. Se houver tentativa restante,
  devolve a tarefa à fila. Se o limite tiver sido alcançado, registra
  `FAILED` com `ATTEMPTS_EXHAUSTED`.
- Confirmação e cancelamento disputam a mesma linha no banco. Se o
  cancelamento vencer, a confirmação não pode criar vaga. Se a
  confirmação vencer, o cancelamento recebe `409`.

## Retenção e exclusão

A política inicial da KAN-53 fixa a validade em **24 horas após a criação**
para qualquer estado. O prazo é absoluto e é retornado em `expiresAt`.
Consulta, cancelamento e confirmação usam o mesmo relógio do banco para
rejeitar tarefas cujo prazo acabou. O worker não reivindica tarefas
vencidas nem publica um resultado depois desse limite.

| Dados | Descarte |
| --- | --- |
| URL original ou descrição colada | Junto com a linha da tarefa. A restrição da V5 exige que a entrada permaneça enquanto a linha existir. |
| Texto coletado e proposta extraída | Na confirmação ou junto com a exclusão da tarefa, o que ocorrer primeiro. |
| Código e mensagem pública de falha | Junto com a exclusão da tarefa. A mensagem usa texto controlado, sem copiar resposta externa, HTML, descrição, tokens ou stack trace. |
| Vaga confirmada e acompanhamento | Seguem o ciclo de vida próprio. A limpeza da tarefa exclui somente a linha em `tb_import_task`. |

O prazo de acesso termina em `expiresAt`. A limpeza física roda a cada
hora, em lotes, com atraso de até uma hora em operação normal. Após
interrupção, retoma o backlog de vencidas. Cancelamento remove
imediatamente uma tarefa própria vigente, exceto em `COMPLETED`.
A exclusão da conta remove suas tarefas via FK. O worker deve descartar
o resultado se a linha ou a conta tiver sido removida.

URL original, descrição, texto coletado e proposta ficam fora dos logs.
A consulta retorna somente os campos públicos do contrato. Consultar
ou cancelar tarefa alheia, vencida ou inexistente retorna o mesmo `404`.

## Integração com o frontend

1. Envie `POST /api/import-tasks` com sessão e CSRF. Para URL, use
   `{"inputType":"URL","url":"https://example.test/vagas/java"}`.
   Para descrição, use
   `{"inputType":"TEXT","text":"Pessoa desenvolvedora Java. Trabalho remoto."}`.
   A API aceita URL de até 2048 caracteres e texto de até 30000 caracteres.
2. Ao receber `202`, consulte o caminho de `Location`. Faça polling
   enquanto o estado for `PENDING` ou `PROCESSING`, com intervalo
   crescente e limite de 10 segundos entre consultas. Pare ao sair da
   tela, ao receber `404`, ao atingir `expiresAt` ou ao receber outro estado.
3. Em `NEEDS_INPUT`, mostre `failure.message` e ofereça o campo para
   colar a descrição. Exemplo de código: `SOURCE_REQUIRES_LOGIN`.
   Uma URL bloqueada por política de segurança gera `FAILED` com
   `SOURCE_NOT_ALLOWED`. A coleta não contorna login, bloqueios do site
   ou exigência de JavaScript.
4. Em `READY_FOR_REVIEW`, exiba `result` e deixe o usuário completar
   os campos ausentes. Salário sem valor, moeda BRL ou período explícitos
   fica ausente. Em `FAILED`, exiba o motivo e permita nova tentativa por
   ação explícita. Em `COMPLETED`, use `jobId` para identificar a vaga
   vinculada.
5. Para cancelar, envie `DELETE /api/import-tasks/{id}` com sessão e CSRF.
   Ao receber `204`, interrompa o acompanhamento e descarte o estado da tela.

O OpenAPI traz exemplos de aceitação, consulta e erros. Os limites de
coleta, a estratégia de rede e as regras de extração serão tratados
nas tarefas responsáveis pela implementação.
