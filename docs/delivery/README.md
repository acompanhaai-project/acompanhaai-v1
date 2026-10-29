# Plano de entrega do Acompanhaai

Projeto Jira: KAN. Stack: Java 25, Spring Boot 4.0+, PostgreSQL, Angular e TypeScript.

## Premissas

- Planejamento didático para uma pessoa alternando PO, backend, frontend, QA e DevOps.
- Java 25, Spring Boot 4.0+, PostgreSQL, Angular e TypeScript.
- Cadência proposta: 14 dias por sprint. Datas e capacidade serão definidas no primeiro planning; nenhuma sprint será iniciada automaticamente.
- Produção exige ambiente separado do PostgreSQL local, credenciais próprias, HTTPS, backup e gate de aprovação.
- Fixture Postman v1 congelada: novas verificações em v2, preservando o contrato original.

## Sprints

| Sprint | Objetivo | Critério de saída |
|---|---|---|
| 1 — Fundação e processo | Conseguir executar o projeto de forma reproduzível e trabalhar com escopo, DoR/DoD, Git, revisão e CI definidos. | Clonar e executar com instruções verificadas; pipeline de testes verde; primeiro planning concluído. |
| 2 — Backend de acesso e perfil | Validar o acesso existente, completar recuperação de senha e confirmar segurança das contas em PostgreSQL. | Fluxos de conta e recuperação aprovados por QA, com sessão/CSRF e isolamento demonstrados. |
| 3 — Backend de vagas e candidaturas | Completar os dados manuais de vagas e consolidar filtros, concorrência e histórico do acompanhamento. | API documentada e validada em PostgreSQL com salário/habilidades, histórico e regressão v2. |
| 4 — Angular: acesso e perfil | Permitir cadastro, login, recuperação e manutenção do perfil pela interface Angular integrada à API. | Usuário completa acesso e perfil pelo navegador, incluindo estados de erro e sessão expirada. |
| 5 — Angular: quadro de candidaturas | Usar o fluxo manual completo no navegador, com quadro por etapas, pesquisa, filtros e histórico. | Uma candidatura é criada, consultada, movida, editada e excluída pelo Angular com evidência E2E. |
| 6 — Importação: coleta e processamento | Receber URL ou texto e processar tarefas duráveis com coleta segura, estados claros e tratamento de bloqueios. | Importação por URL/texto funciona com isolamento, limites, reexecução controlada e fallback manual. |
| 7 — Importação: extração com IA | Extrair título, empresa, habilidades e salário com IA e dados estruturados, exigindo revisão antes de salvar. | Conjunto de avaliação aprovado; campos sem evidência permanecem ausentes; confirmação é atômica e idempotente. |
| 8 — Angular: importar e revisar | Colar link ou descrição, acompanhar processamento, revisar campos e salvar a candidatura pela interface. | Fluxo integrado de importação aprovado em casos de sucesso, erro, cota excedida e página indisponível. |
| 9 — Homologação e preparação da release | Homologar o produto em staging com regressão, segurança, acessibilidade, desempenho e restauração de backup. | UAT aprovado; sem bugs críticos/altos abertos; restore e rollback ensaiados; release candidate identificada. |
| 10 — Produção e operação assistida | Publicar a versão aprovada em produção com HTTPS, permissões mínimas, observabilidade e procedimento de recuperação. | Smoke de produção aprovado, alertas exercitados, backup conferido e acompanhamento inicial registrado. |

## Organização no Jira

Épicos agrupam Histórias, Tarefas e Features. Neste projeto, Feature é um tipo padrão no mesmo nível de História; serão relacionados por links, sem inventar uma hierarquia Epic → Feature → Story não suportada. Subtasks detalham implementação e QA das Histórias.

Todos os itens começam a fazer. Código existente será identificado como baseline e precisa de revisão/evidência para encerrar o item. Nenhuma sprint será iniciada automaticamente.

## Rotina de cada sprint

1. Refinamento: revisar objetivo, aceite, riscos e dependências com PO/QA.
2. Planning: confirmar disponibilidade, selecionar escopo e estimar as histórias em conjunto. Reservar aproximadamente 20% da capacidade para correções e aprendizado; medir e ajustar após as primeiras sprints.
3. Execução: branch curta com chave KAN, atualização diária (feito/próximo/bloqueio), PR e revisão. Limitar trabalho em andamento a uma história por pessoa.
4. QA contínuo: testar cada incremento, reproduzir e priorizar Bugs reais, retestar correções.
5. Review: demonstrar o incremento funcionando e registrar evidência/aceite.
6. Retrospectiva: escolher uma ação concreta de melhoria para a próxima sprint. Replanejar itens incompletos sem alterar o histórico.

## Definition of Ready

Objetivo de usuário ou técnico claro; critérios testáveis; dependências conhecidas; abordagem de teste e massa definidas; tamanho compatível com a capacidade da sprint.

## Definition of Done

Implementação revisada; testes apropriados aprovados; evidência QA vinculada; critérios aceitos; contrato/documentação atualizados; sem segredos e sem Bug crítico/alto não tratado. Produção exige o gate de release, backup/restore e plano de rollback.

## Bugs e workflow

Usar o tipo **Problema** para defeitos reais, com: resumo, versão/ambiente, pré-condições, passos, esperado, obtido, frequência, severidade, prioridade, evidência sanitizada e vínculo com história/release. Fluxo: reportado → triagem → correção → revisão → reteste → fechado ou reaberto. Bug crítico/alto bloqueia liberação até correção/decisão formal.

O quadro atual tem A fazer → Em Progresso → In Review → Concluído. Revisão de código e QA devem ter checkpoints explícitos; o item T01 definirá o ajuste das colunas/status quando necessário, sem fechar histórias antes do aceite.

## Datas e estimativas

Cadência proposta de 14 dias, não compromisso de prazo. As 10 sprints são futuras, sem datas artificiais e sem story points atribuídos sem refinement. O usuário confirmará calendário e capacidade no primeiro planning. A liberação só acontece quando os gates forem atendidos.

## Escopo da execução deste planejamento

Criar itens e sprints no Jira, não implementar todas as funcionalidades nem contratar/publicar infraestrutura. Histórias de produção descrevem os passos e gates a executar futuramente. Credenciais, dados reais e fotos de usuários não serão enviados ao Jira.

