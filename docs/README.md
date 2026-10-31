# Documentação do Acompanhaai

Referência documental revisada em 30/10/2026. Os procedimentos abaixo não são
evidências de execução: registrar resultados somente após executar cada roteiro.

| Documento | Conteúdo | Situação |
|---|---|---|
| [Arquitetura](arquitetura.md) | Camadas, dados, sessão, importação e escolha do Gemini | Decisões registradas; frontend e importação planejados |
| [Modelagem](modelagem-banco.md) | Entidades, relacionamentos e migrações | Estrutura existente e extensões planejadas identificadas |
| [Ambiente local](ambiente-local.md) | H2, PostgreSQL, configuração e roteiro KAN-21 | Procedimento documentado; execução pendente |
| [Qualidade](qualidade.md) | Matriz de testes, dados, Bugs e evidências KAN-22 | Estratégia documentada; resultados atuais pendentes |
| [CI/CD](ci-cd.md) | Especificação do pipeline e critérios KAN-23 | Pipeline ainda não implementado |
| [Contrato OpenAPI](openapi.yaml) | Endpoints e estruturas disponíveis | Contrato versionado da API atual |
| [Integração frontend](integracao-frontend.md) | Sessão, CSRF, erros e candidaturas KAN-35 | Guia da API atual; extensões futuras pendentes |
| [Operação](operacao.md) | Release, backup, restore, rollback e incidentes | Procedimento planejado; infraestrutura não provisionada |
| [Release e retrospectiva](releases.md) | Uso atual, notas de versão e encerramento KAN-77 | Base documental; release 1.0 ainda não publicada |
| [Plano de entrega](delivery/README.md) | Sprints, DoR, DoD e rotina | Planejamento; datas dependem do planning |

## Como usar

1. Selecionar o cartão de execução no Jira; a Feature é o índice da entrega.
2. Consultar o guia correspondente e executar o que estiver no seu escopo.
3. Registrar comando, commit, ambiente, resultado e evidência sanitizada.
4. Revisar o PR e atender aos critérios de aceite antes de concluir o cartão.

Escrever a documentação não encerra KAN-21, KAN-22, KAN-23, KAN-35 ou KAN-77:
esses itens também exigem execução, implementação ou validação.

A [fixture Postman v1](../postman/fixtures/v1/README.md) é congelada. Novos
contratos/testes devem usar uma versão nova, sem alterar suas expectativas.
