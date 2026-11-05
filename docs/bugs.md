# Fluxo de Bugs — KAN-28

Relacionamento: [KAN-28 — Definir fluxo e modelo de Bug para triagem e reteste](https://maikamaral123.atlassian.net/browse/KAN-28).
Revisão documental: 03/11/2026. Origem: T09 do plano de entrega v1.

Este guia define o processo de QA para defeitos reais do Acompanhaai. Não cria
cartões, campos, status ou automações no Jira. As configurações atuais do Jira
devem ser conferidas ao aplicar o processo; o mapeamento abaixo usa as quatro
colunas registradas no planejamento.

## Quando abrir um Bug

Usar o tipo **Problema**, adotado como Bug no plano do projeto, quando houver
evidência de divergência entre um critério/contrato aceito e o comportamento
observado. A falha pode estar no produto, em um teste ou na infraestrutura;
registrar o componente e o impacto reais, sem tratar toda falha como segurança.

Antes de criar:

1. Conferir critério de aceite, contrato e versão do comportamento esperado.
2. Reproduzir em ambiente isolado com dados fictícios ou registrar a evidência
   disponível e a frequência, quando a falha for intermitente.
3. Procurar duplicatas na história relacionada e nos Bugs abertos do projeto.
4. Reutilizar o cartão existente se for a mesma causa/sintoma, acrescentando
   evidência e ambiente. Se forem problemas distintos, criar cartões separados.
5. Preencher o [modelo de Bug](templates/bug.md), relacionando história/tarefa,
   versão afetada e release de correção pretendida, quando já definida.

Melhoria desejada sem requisito anterior deve entrar no backlog como mudança
de escopo. Ausência de teste é uma lacuna de cobertura até que uma falha seja
demonstrada. Falha provocada deliberadamente para demonstrar o bloqueio do CI
é exercício; não abrir como defeito real do produto.

Uma falha intermitente não deve ser descartada só por não repetir na primeira
tentativa. Registrar tentativas, contexto, logs sanitizados e hipótese separada
dos fatos. Falta de dados leva a investigação, sem inventar uma causa.

## Severidade e prioridade

Severidade descreve o impacto observado. Prioridade define a ordem de trabalho,
considerando impacto, frequência, contorno disponível, alcance e proximidade
da release. Na triagem, o QA propõe a severidade, o desenvolvimento avalia a
causa e o responsável pelo produto confirma a prioridade.

| Severidade | Critério de impacto                                                                              | Exemplo hipotético, sem registro de Bug                                |
| ---------- | ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------- |
| Crítica    | Exposição de dados de outra conta, perda/corrupção relevante de dados ou indisponibilidade geral | Uma conta consegue ler dados privados de outra                         |
| Alta       | Jornada essencial impedida, sem contorno aceitável                                               | Login falha para contas válidas no ambiente de homologação             |
| Média      | Função parcialmente comprometida, com contorno viável                                            | Filtro específico falha, mas a consulta individual continua disponível |
| Baixa      | Problema visual ou textual sem impedir a tarefa                                                  | Mensagem apresenta erro de escrita                                     |

| Prioridade     | Ordem de tratamento                                                  |
| -------------- | -------------------------------------------------------------------- |
| P0 — Imediata  | Interromper o avanço da release, avaliar contenção e tratar primeiro |
| P1 — Próxima   | Próximo trabalho disponível, antes da liberação afetada              |
| P2 — Planejada | Selecionar em planning conforme impacto e capacidade                 |
| P3 — Backlog   | Reavaliar em refinamento, sem compromisso de sprint                  |

Críticos normalmente recebem P0 e altos P1; a decisão deve ser registrada,
sem calcular prioridade automaticamente pela severidade. Alterar prioridade
não reduz o impacto nem remove um bloqueio de release.

## Prazo didático de triagem

| Severidade | Meta de primeira triagem  |
| ---------- | ------------------------- |
| Crítica    | Até 4 horas úteis         |
| Alta       | Até 1 dia útil            |
| Média      | Até 3 dias úteis          |
| Baixa      | Até o próximo refinamento |

Essas metas são um SLA didático para o exercício do projeto, não uma promessa
de atendimento em produção. A janela de trabalho e a duração de um dia útil
devem ser combinadas no planning antes de medir cumprimento; não há plantão
24 horas presumido. O prazo começa no registro do Bug e conta somente dentro
da janela acordada. Dados ausentes devem ser solicitados dentro desse prazo,
com a pendência registrada, sem reiniciar o relógio por mudança de responsável.

Primeira triagem significa registrar responsável, impacto, classificação,
reprodução/evidência, vínculos, necessidade de contenção e próximo passo.
Não é prazo de correção. Estimativa de solução e eventual descumprimento
devem ser comunicados no cartão, com justificativa e nova ação.

## Fluxo e responsabilidades

Fluxo lógico: **reportado → triagem → correção → revisão → reteste → fechado**.
Se o defeito persistir no reteste: **reaberto → triagem**.

| Etapa     | Responsabilidade e critério para avançar                                                                          |
| --------- | ----------------------------------------------------------------------------------------------------------------- |
| Reportado | QA ou quem encontrou registra reprodução, esperado/obtido, ambiente, versão e evidência                           |
| Triagem   | QA, desenvolvimento e produto conferem duplicatas, impacto, prioridade, vínculos e responsável; registram decisão |
| Correção  | Desenvolvimento registra causa, implementa ajuste e verificação pertinente em branch/PR vinculados ao Bug         |
| Revisão   | Revisor confere diff, contrato, impacto, testes e CI; solicitar ajustes devolve à correção                        |
| Reteste   | QA identifica commit e ambiente, repete reprodução e regressão afetada, registrando resultado e evidência         |
| Fechado   | Correção integrada, reteste aprovado e vínculos/versões atualizados; responsáveis confirmam o resultado           |
| Reaberto  | QA registra o que ainda falha e a versão retestada; retorna à triagem, preservando o histórico                    |

Uma pessoa pode assumir os papéis em momentos diferentes, registrando qual
verificação realizou. Autorrevisão não deve ser descrita como revisão por
outra pessoa. PR com CI verde, isoladamente, não substitui reteste e aceite.

### Aplicação no quadro de quatro colunas

Não é necessário criar sete status para usar o fluxo. Enquanto o quadro usar
A fazer, Em Progresso, In Review e Concluído, manter no início da descrição
o campo textual **Etapa de QA**, atualizado junto da coluna:

| Coluna do planejamento | Etapa de QA registrada na descrição                                |
| ---------------------- | ------------------------------------------------------------------ |
| A fazer                | Reportado ou Triagem                                               |
| Em Progresso           | Correção; em reabertura, registrar Reaberto e encaminhar à triagem |
| In Review              | Revisão ou Reteste, explicitando qual está pendente                |
| Concluído              | Fechado, com decisão e evidência                                   |

Se depender de informação externa, manter a etapa atual e registrar pendência,
responsável e próxima revisão. Não mover para Concluído por estar bloqueado.
Caso os status reais sejam diferentes, adaptar esse mapeamento antes de usar,
preservando os checkpoints de revisão e reteste.

### Outras decisões de triagem

- Duplicado: vincular ao cartão principal e fechar com justificativa; o
  cartão principal continua responsável pela correção e pelo reteste.
- Não é defeito: citar o requisito que explica o comportamento; se houver
  demanda de melhoria, vinculá-la ao backlog e registrar essa decisão.
- Informação insuficiente: registrar exatamente o que falta e investigar;
  uma tentativa sem reprodução não comprova que o problema foi resolvido.

Não registrar esses encerramentos como "corrigido". Se o Jira não possuir
resolução específica, descrever a decisão explicitamente no cartão.

## Vínculos, versão e release

- Relacionar o Bug à história que contém o critério afetado. Para problemas
  técnicos ou de teste, relacionar à tarefa correspondente e ao componente.
- Usar o vínculo de bloqueio quando o Bug impedir o aceite da história ou
  release; informar também o motivo na descrição.
- Registrar commit/tag e ambiente afetados. Atualizar o commit da correção,
  PR, execução do CI e versão efetivamente retestada quando disponíveis.
- Preencher versão afetada e versão de correção no Jira se esses campos e
  versões existirem. Caso contrário, registrar esses dados na descrição.
- Se não houver release definida, usar "a definir" e revisar no planning,
  sem inventar versão publicada. Identificar a candidata antes do gate de release.

Críticos/altos abertos bloqueiam a liberação conforme a DoD do projeto. Uma
exceção exige decisão formal do responsável pelo produto, registrando risco,
contenção, responsável e prazo; não fechar o Bug nem esconder sua severidade.

## Reteste e fechamento

1. Confirmar que a versão com a correção está no ambiente indicado e que
   a massa de testes é isolada. Um teste contra a versão antiga não é reteste.
2. Repetir os passos originais e conferir o resultado esperado, sem afrouxar
   as asserções para acompanhar um defeito.
3. Exercitar cenários negativos e regressão afetada. Para falha intermitente,
   registrar quantidade de repetições e condições; uma única aprovação não
   deve ser descrita como prova de ausência definitiva de intermitência.
4. Registrar comando/passos, commit, ambiente, resultado, contagens,
   responsável, data e links de evidência sanitizada.
5. Fechar como corrigido somente após correção integrada e reteste aprovado;
   a publicação em produção é um passo de release separado.
6. Se persistir, reabrir o mesmo Bug com nova evidência. Um defeito diferente
   encontrado durante o reteste deve ter cartão próprio, vinculado ao original.

Preservar a fixture Postman v1, incluindo executor, dependências e hashes.
Novas verificações de API devem usar v2 ou testes de integração apropriados.
Não executar suítes destrutivas sobre dados pessoais ou produção.

## Evidências e revisão desta entrega

| Critério T09                                                        | Evidência documental                                                |
| ------------------------------------------------------------------- | ------------------------------------------------------------------- |
| Tipo Problema, passos, massa, esperado/obtido, ambiente e evidência | Seção Quando abrir um Bug e modelo copiável                         |
| Severidade separada de prioridade e SLA didático crítico/alto       | Tabelas de classificação e prazo de triagem                         |
| Fluxo, reabertura e vínculos com história/release                   | Fluxo, quadro de quatro colunas, vínculos e critérios de fechamento |
| Não criar defeitos fictícios                                        | Orientação explícita e exemplos identificados como hipotéticos      |

A [R01 da KAN-27](../backend/docs/matriz-kan-27.md) é um exemplo real documentado de falha
em teste, correção e reteste. Ela não prova uma vulnerabilidade da API e não
deve ser cadastrada retroativamente como Bug ainda aberto sem verificar seu
estado e possíveis duplicatas. Nenhum Bug fictício foi criado nesta entrega.

Para aceitar a KAN-28, revisar guia e template, combinar janela de triagem e
responsáveis, conferir o mapeamento com o Jira e registrar o aceite no cartão.
A existência dos documentos não comprova essas configurações ou decisões.
