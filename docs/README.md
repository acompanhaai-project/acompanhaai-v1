# Documentação do Acompanhaai

Referência documental revisada em 30/10/2026. Os procedimentos abaixo não são
evidências de execução: registrar resultados somente após executar cada roteiro.

| Documento                                                  | Conteúdo                                                    | Situação                                                                                     |
| ---------------------------------------------------------- | ----------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| [Arquitetura](arquitetura.md)                              | Camadas, dados, sessão, importação e escolha do Gemini      | Decisões registradas; frontend e importação planejados                                       |
| [Modelagem](modelagem-banco.md)                            | Entidades, relacionamentos e migrações                      | Estrutura existente e extensões planejadas identificadas                                     |
| [Ambiente local](ambiente-local.md)                        | H2, PostgreSQL, configuração e roteiro KAN-21               | Procedimento documentado; execução pendente                                                  |
| [Qualidade](qualidade.md)                                  | Matriz de testes, dados, Bugs e evidências KAN-22           | Estratégia documentada; resultados atuais pendentes                                          |
| [Matriz KAN-24](matriz-kan-24.md)                          | Critérios de conta/autenticação, testes e lacunas           | L1–L3 resolvidas; 14 testes de conta e 30 no total aprovados localmente; PR/aceite pendentes |
| [Matriz KAN-26](matriz-kan-26.md)                          | Conteúdo, limites e privacidade da foto de perfil           | PostgreSQL: 96 aprovados; H2: 95 aprovados e 1 ignorado; PR/aceite pendentes                 |
| [Matriz KAN-27](matriz-kan-27.md)                          | A/B, sessões, expiração, CSRF e execução PostgreSQL isolada | PostgreSQL: 98 aprovados, sem falhas; R01 corrigida; CI/PR/aceite pendentes                  |
| [Recuperação de senha](recuperacao-senha.md)               | Modelo, V6, Service, DTOs e endpoints KAN-25                | HTTP e SMTP local implementados; aceite pendente                                             |
| [E-mail local](email-local.md)                             | Mailpit, entrega após commit e testes SMTP KAN-25           | GreenMail verificado; Mailpit manual pendente                                                |
| [Proteção da recuperação](protecao-recuperacao.md)         | Cotas por IP/globais e processamento assíncrono KAN-25      | Proteção local implementada; validação de produção pendente                                  |
| [Validação PostgreSQL KAN-25](validacao-postgres-kan25.md) | Migração limpa, V5→V6, concorrência e CI                    | 84 testes aprovados em PostgreSQL 18.6; CI remoto pendente                                   |
| [CI/CD](ci-cd.md)                                          | Checks H2/PostgreSQL, isolamento de roles e artefatos       | CI implementado; novo check de permissões aguardando execução remota; deploy pendente        |
| [Contrato OpenAPI](openapi.yaml)                           | Endpoints e estruturas disponíveis                          | Contrato versionado da API atual                                                             |
| [Integração frontend](integracao-frontend.md)              | Sessão, CSRF, erros e candidaturas KAN-35                   | Guia da API atual; extensões futuras pendentes                                               |
| [Operação](operacao.md)                                    | Release, backup, restore, rollback e incidentes             | Procedimento planejado; infraestrutura não provisionada                                      |
| [Release e retrospectiva](releases.md)                     | Uso atual, notas de versão e encerramento KAN-77            | Base documental; release 1.0 ainda não publicada                                             |
| [Plano de entrega](delivery/README.md)                     | Sprints, DoR, DoD e rotina                                  | Planejamento; datas dependem do planning                                                     |

## Como usar

O [contrato da vaga manual — KAN-30](vaga-manual-kan-30.md) registra salário único
em BRL, localização, modalidade e habilidades, incluindo substituição e validações.

O [fluxo de Bugs — KAN-28](bugs.md) e o [modelo copiável](templates/bug.md)
definem severidade, prioridade, prazos didáticos de triagem, revisão e reteste.
Processo documentado; janela de trabalho, mapeamento no Jira e aceite pendentes.

O [guia de credenciais — KAN-29](credenciais.md) explica provisionamento,
adoção dos objetos, configuração, rotação e validação com usuário restrito.

A [revisão inicial de credenciais — KAN-29](revisao-credenciais-kan-29.md)
registra a configuração e as permissões fornecidas pelo pgAdmin. A V6 e o
provisionamento local foram executados, e o smoke da API foi aprovado;
CI do PR e aceite continuam pendentes.

1. Selecionar o cartão de execução no Jira; a Feature é o índice da entrega.
2. Consultar o guia correspondente e executar o que estiver no seu escopo.
3. Registrar comando, commit, ambiente, resultado e evidência sanitizada.
4. Revisar o PR e atender aos critérios de aceite antes de concluir o cartão.

Escrever a documentação não encerra KAN-21, KAN-22, KAN-23, KAN-35 ou KAN-77:
esses itens também exigem execução, implementação ou validação.

A [fixture Postman v1](../postman/fixtures/v1/README.md) é congelada. Novos
contratos/testes devem usar uma versão nova, sem alterar suas expectativas.
