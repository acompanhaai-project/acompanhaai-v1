# Uso, releases e encerramento da entrega

Relacionamento: KAN-77. **Base preparada; versão 1.0 não publicada.**
Data de referência documental: 30/10/2026.

## Uso da versão atual

O backend é utilizado por cliente HTTP, como Postman. A interface Angular
e a importação automática ainda estão planejadas.

1. Iniciar o backend seguindo [ambiente local](ambiente-local.md).
2. Obter CSRF e cadastrar uma conta fictícia.
3. Fazer login, preservar a sessão e renovar o CSRF.
4. Consultar/editar perfil e, se desejado, enviar uma foto.
5. Cadastrar uma vaga manualmente com seu acompanhamento.
6. Consultar listagem, filtrar etapa e atualizar usando a versão atual.
7. Consultar histórico, excluir registros próprios e encerrar a sessão.

Os endpoints e exemplos estão no [guia de integração](integracao-frontend.md)
e no [contrato OpenAPI](openapi.yaml). Senhas de exemplo são fictícias;
nenhuma conta vem pré-cadastrada automaticamente.

## Capacidades existentes

- Conta própria, autenticação por sessão e proteção CSRF.
- Consulta/edição de perfil, troca de senha e exclusão da conta.
- Foto opcional com limites e validação.
- CRUD manual de candidaturas, filtro por etapa, paginação e histórico.
- Migrações e entidades de detalhes de vaga/importação.

## Limitações conhecidas

- Modelo de persistência não significa que todos os campos estejam na API:
  salário/habilidades ainda não são expostos pelos DTOs atuais.
- Frontend, recuperação de senha por e-mail e processamento de importação
  ainda não estão implementados.
- Sessões ficam em memória e são perdidas após reinício do backend.
- A URL da vaga é armazenada; o CRUD atual não coleta seu conteúdo.
- Ambiente de produção, CI e recuperação operacional ainda precisam ser
  implementados e comprovados.
- A integração automática GitHub–Jira foi adiada; usar referência manual
  ao cartão de execução nos PRs.

## Notas de versão — modelo

Preencher uma entrada por release real, sem promover o estado atual a 1.0.

```text
Versão/tag:
Commit e artefato:
Data e ambiente:
Responsável e aprovação:
Funcionalidades entregues:
Correções e mudanças incompatíveis:
Migrações e ordem de aplicação:
Impacto em sessão e configuração:
Limitações conhecidas:
Evidências de QA e UAT:
Backup/restore e rollback:
Resultado do smoke:
```

## Review e retrospectiva

Na review, demonstrar as jornadas realmente entregues e comparar com os
critérios de aceite. Registrar pendências e ajustes explícitos de escopo.

Na retrospectiva, registrar:

- O que funcionou e qual evidência sustenta a avaliação.
- O que dificultou a entrega e sua causa provável.
- Uma ação de melhoria com responsável e momento de verificação.
- Itens pós-MVP, priorizados a partir de uso real.

No trabalho individual, alternar os papéis de produto, desenvolvimento e QA
sem tratar autorrevisão como revisão independente.

## Encerramento de KAN-77

Concluir somente após release real, notas preenchidas, documentação ajustada
ao produto entregue, review/retrospectiva realizadas e backlog pós-MVP
revisado. A criação deste modelo não satisfaz esses critérios de execução.
