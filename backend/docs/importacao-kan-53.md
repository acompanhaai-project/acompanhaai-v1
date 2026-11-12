# Importação assíncrona de vagas — KAN-53

O [OpenAPI de importação](openapi-importacao.yaml) define as rotas.
A V5 já contém a tabela de tarefas. A KAN-50 implementa a fila, o worker
e a limpeza. O `JobImportProcessor` faz a coleta da página e monta a proposta
inicial. As rotas de criação, consulta e cancelamento estão disponíveis.
A extração com IA será integrada nas próximas tarefas.
A KAN-55 implementa a confirmação da proposta revisada.

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
5. Para confirmar, envie `POST /api/import-tasks/{id}/confirm` com sessão,
   CSRF e o mesmo corpo do cadastro manual (`job`, `status`, `appliedOn`,
   `notes`), já com as correções. A resposta `201` traz a candidatura e
   `Location`. Uma repetição retorna `200` com a mesma candidatura. Em
   `409` ou `422`, a tarefa continua em revisão.
6. Para cancelar, envie `DELETE /api/import-tasks/{id}` com sessão e CSRF.
   Ao receber `204`, interrompa o acompanhamento e descarte o estado da tela.

## Confirmação

- Bloqueia a conta e depois a tarefa, na mesma ordem do cadastro manual.
  Confirmação e cancelamento disputam a mesma linha.
- Reaproveita as validações do cadastro manual: campos, salário com BRL e
  período, habilidades sem duplicidade e link único por conta. Um link já
  cadastrado gera `409` e não altera a candidatura existente.
- Na mesma transação, cria vaga, habilidades, candidatura e histórico,
  marca a tarefa como `COMPLETED`, registra `reviewed_at` e descarta texto
  coletado e proposta.
- Em importação por URL, `job.sourceUrl` omitido assume a URL da tarefa.

O OpenAPI traz exemplos de aceitação, consulta e erros. As regras de
extração com IA serão tratadas na tarefa responsável.

## Coleta e proposta inicial

- Só HTTP e HTTPS nas portas padrão, sem credenciais na URL.
- Cada endereço resolvido é validado no momento da conexão. Loopback, redes
  privadas, link-local, CGNAT, multicast, faixas reservadas e de documentação,
  IPv6 local e formas com IPv4 embutido geram `FAILED` com `SOURCE_NOT_ALLOWED`.
- Redirecionamentos são seguidos manualmente, até 5, e cada destino passa
  pela mesma validação. Redirecionamento para login gera `SOURCE_REQUIRES_LOGIN`.
- Casos hostis (literais e IPv6 mapeado, DNS com resposta mista ou trocada
  entre redirecionamentos, esquemas e portas indevidos, conteúdo ativo,
  JSON-LD malicioso e marcação patológica) estão em
  [KAN-51](../../docs/evidencias/kan-51-ssrf-coletor.md).
- Limites: 5 s para conectar, 10 s sem dados, 20 s no total
  (`app.import-collector.timeout`), terminando 5 s antes do fim da posse;
  2 MiB de corpo após descompressão (`app.import-collector.max-bytes`);
  100 cabeçalhos de até 16 KiB. Sem cookies, cache de autenticação ou
  reaproveitamento de conexão.
- Aceita `text/html`, `application/xhtml+xml` e `text/plain`. Outros tipos,
  corpo acima do limite ou 404 geram `SOURCE_NO_USABLE_CONTENT`. 401 gera
  `SOURCE_REQUIRES_LOGIN`; 403 e 429 geram `SOURCE_BLOCKED`. 5xx, timeout e
  falha de conexão devolvem a tarefa à fila.
- `JobPosting` em JSON-LD tem prioridade e preenche título, empresa,
  descrição, localização, modalidade remota e salário. O salário só entra com
  valor exato, moeda BRL e período explícito.
- Sem dados estruturados, o texto visível (sem scripts, estilos e comentários)
  vira a descrição proposta quando tiver pelo menos 200 caracteres. Abaixo
  disso: campo de senha gera `SOURCE_REQUIRES_LOGIN`, página com scripts gera
  `SOURCE_REQUIRES_JAVASCRIPT` e os demais casos, `SOURCE_NO_USABLE_CONTENT`.
- Entrada TEXT vira a descrição proposta após normalizar espaços e remover
  caracteres de controle, com limite de 30000 caracteres.

## Extração com IA

A extração completa a proposta quando há um provedor configurado. Ela é
opcional: sem provedor, ou em caso de falha, a tarefa segue com os dados
estruturados da página e a descrição.

| Propriedade | Padrão | Efeito |
| --- | --- | --- |
| `app.extraction.provider` | `none` | `none`, `gemini` ou `ollama`. Também lê `EXTRACTION_PROVIDER`. |
| `app.extraction.timeout` | `30s` | Tempo máximo da chamada, limitado pela posse da tarefa. |
| `app.extraction.max-input-chars` | `20000` | Texto máximo enviado ao provedor. |
| `app.extraction.max-output-tokens` | `1024` | Tokens máximos de resposta (`maxOutputTokens` / `num_predict`). |
| `app.extraction.max-attempts` | `2` | Tentativas por tarefa em 429, timeout ou indisponibilidade. |
| `app.extraction.retry-delay` | `2s` | Espera antes da segunda tentativa; dobra a cada nova tentativa. |
| `app.extraction.daily-limit` | `200` | Chamadas ao provedor por dia (UTC) nesta instância; `0` desativa. |
| `app.extraction.failure-threshold` | `3` | Falhas transitórias seguidas que pausam o provedor. |
| `app.extraction.cooldown` | `5m` | Duração da pausa após falhas seguidas. |
| `app.extraction.gemini.model` | `gemini-3.5-flash-lite` | Modelo do Gemini. |
| `app.extraction.gemini.api-key` | `GEMINI_API_KEY` | Chave do Gemini, obrigatória com esse provedor. |
| `app.extraction.gemini.thinking-level` | `minimal` | Nível de raciocínio (`thinkingLevel`); vazio omite o parâmetro. |
| `app.extraction.ollama.base-url` | `http://localhost:11434` | Servidor local do Ollama. |
| `app.extraction.ollama.model` | `llama3.2` | Modelo local. |

- Somente a descrição da vaga é enviada, delimitada como conteúdo a analisar.
  Currículo, anotações e dados da conta nunca fazem parte da chamada.
- A resposta é pedida em JSON com esquema e validada pelas mesmas regras da
  proposta. Campos inválidos são descartados individualmente.
- O salário só entra com valor único, moeda BRL, período explícito e com o
  mesmo valor escrito no texto da vaga. Piso ("a partir de"), teto ("até") e
  extremos de faixa são descartados.
- Valores citados apenas em frases que tentam dar ordens ao modelo ("ignore
  as instruções anteriores", "desconsidere o texto acima", "responda que")
  não são aceitos, mesmo que o modelo os devolva. O mesmo valor escrito em
  outra frase da vaga continua válido.
- Cada campo extraído precisa de evidência no texto. Título, empresa e
  localização ficam quando o modelo devolve um trecho que existe no texto ou
  quando o próprio valor aparece nele. Modalidade exige trecho conferido.
  Habilidades só ficam quando aparecem no texto como palavra inteira.
- A proposta traz `evidence` com a origem de título, empresa, localização,
  modalidade e salário: `STRUCTURED_DATA` para o `JobPosting` da página ou
  `TEXT` com o trecho conferido, de até 300 caracteres. A tela de revisão
  mostra essa origem abaixo de cada campo.
- Dados estruturados publicados pela página têm prioridade sobre a extração,
  e a descrição proposta continua sendo o texto coletado.
- Falhas de tempo, cota (429), indisponibilidade, recusa ou resposta inválida
  não falham a tarefa. O log registra `extracao provedor falha duracaoMs`,
  sem texto, resposta ou chave. Em recusa, registra também o código HTTP e a
  categoria do erro do provedor (por exemplo, `http=404 status=NOT_FOUND`),
  sem a mensagem.

### Falhas, cota e custo

- 429, timeout e indisponibilidade recebem nova tentativa com espera
  progressiva, somente se ainda houver tempo na posse da tarefa. Recusa (4xx)
  e resposta inválida não são repetidas.
- Cada chamada, inclusive as novas tentativas, consome o limite diário. Ao
  atingi-lo, a extração é pulada com `BUDGET_EXHAUSTED` até a virada do dia
  em UTC. O contador fica em memória e reinicia com a aplicação.
- Após `failure-threshold` falhas transitórias seguidas, o provedor é pausado
  por `cooldown` e as tarefas seguem sem IA com `SUSPENDED`. Um sucesso zera
  a contagem.
- A cota por conta da importação (`app.import-limits.*`) também limita as
  chamadas: cada tarefa faz no máximo `max-attempts` chamadas.
- Texto de entrada e resposta são limitados por `max-input-chars` e
  `max-output-tokens`.
- Para desligar a IA, use `app.extraction.provider=none` ou
  `EXTRACTION_PROVIDER=none`. A importação e o cadastro manual continuam
  disponíveis com qualquer falha do provedor.
- Novas tentativas e pausas também são registradas no log, sem conteúdo.
- Provedor e modelo usados ficam registrados na tarefa.

### Simulação de falhas

`ImportFailureSimulationIntegrationTest` percorre a importação pela API com
um provedor simulado em `127.0.0.1`, no formato do Gemini, e roda em toda
execução da suíte, com H2 e com PostgreSQL no CI. Cobre resposta inválida
(HTML, JSON truncado, texto livre, lista, `MAX_TOKENS`), resposta parcial,
instrução embutida no texto, 429 persistente e recuperado, timeout e 503: a
tarefa sempre chega à revisão, com a proposta da IA ou só com a descrição.
Também cobre worker atrasado, confirmações simultâneas e isolamento entre
contas, com verificação de integridade no banco. Resultados em
[KAN-59](../../docs/evidencias/kan-59-falhas-ia.md).

Para usar o Gemini localmente, defina em `application-local.properties`, que
é ignorado pelo Git:

```properties
app.extraction.provider=gemini
app.extraction.gemini.api-key=SUA_CHAVE
```

Para o Ollama, instale um modelo (`ollama pull llama3.2`) e use
`app.extraction.provider=ollama`.

## Avaliação da extração

O conjunto versionado fica em
`src/test/resources/extraction-eval/v1/cases.json`: 20 vagas fictícias com
gabarito de título, empresa, localização, modalidade, salário e habilidades.
Cobre salário exato (mensal, anual, por hora e bolsa), faixa, valor mínimo,
"a combinar", USD, EUR, anúncios em inglês, empresa confidencial, agência
recrutadora, habilidades desejáveis e instrução embutida no texto.

| Teste | Quando roda | O que verifica |
| --- | --- | --- |
| `ExtractionEvaluationTest` | Sempre, inclusive no CI | Formato e cobertura do conjunto, gabarito presente no texto, regras do contrato e cálculo das métricas. |
| `ExtractionEvaluationLiveTest` | Somente sob demanda | Chama o provedor configurado para cada caso e grava o relatório. |

Metas da v1: **nenhum salário inventado ou errado** e pelo menos 90% de
acerto em título e empresa explícitos. Localização, modalidade e habilidades
são registradas no relatório, sem meta.

Para avaliar o provedor real, com a chave em `application-local.properties`
ou em `GEMINI_API_KEY`:

```bash
./mvnw test -Dtest=ExtractionEvaluationLiveTest -Dextraction.eval=true \
  -Dapp.extraction.provider=gemini \
  "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" \
  "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

O relatório fica em `target/extraction-eval/report-v1-<provedor>.md`, sem
chave nem dados pessoais. `-Dextraction.eval.pause=4S` controla a espera
entre chamadas para respeitar a cota gratuita. Alterações no conjunto devem
criar uma nova versão (`v2`) para manter os resultados comparáveis.

## Limites e observabilidade

| Propriedade | Padrão | Efeito |
| --- | --- | --- |
| `app.import-worker.poll-delay` | `5s` | Intervalo entre ciclos do worker. Cada instância processa uma tarefa por vez. |
| `app.import-worker.lease` | `2m` | Prazo de posse de cada tentativa. |
| `app.import-worker.max-attempts` | `3` | Tentativas antes de `ATTEMPTS_EXHAUSTED`. |
| `app.import-collector.timeout` | `20s` | Tempo total da coleta, limitado pela posse. |
| `app.import-collector.max-bytes` | `2097152` | Corpo máximo da página após descompressão. |
| `app.import-limits.per-user-hourly` | `30` | Tarefas criadas por conta na última hora. |
| `app.import-limits.per-user-active` | `5` | Tarefas `PENDING` ou `PROCESSING` por conta. |
| `app.import-limits.global-active` | `200` | Tarefas `PENDING` ou `PROCESSING` em toda a aplicação. |

A criação bloqueia a linha da conta antes de contar, para que solicitações
paralelas não ultrapassem as cotas. A entrada é validada antes das cotas.

- Cota por hora: `429` com `IMPORT_QUOTA_EXCEEDED` e `Retry-After` até a
  tarefa mais antiga sair da janela.
- Tarefas ativas da conta: `429` com `IMPORT_ACTIVE_LIMIT` e `Retry-After: 30`.
- Fila global cheia: `503` com `IMPORT_OVERLOADED` e `Retry-After: 60`. O
  cadastro manual continua disponível.

Cada tentativa encerrada registra uma linha `importacao id tipo tentativa
status codigo duracaoMs`, e cada recusa registra `importacao recusada codigo`.
URL, descrição, texto coletado, proposta e dados da conta não entram nos logs.
