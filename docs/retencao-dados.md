# Retenção e exclusão de dados — KAN-65

A política vale para a versão 1.0. Os prazos estão no código; mudar algum
deles exige atualizar este documento e os testes citados.

## Prazos

| Dado | Onde fica | Prazo | Como sai |
| --- | --- | --- | --- |
| Conta (nome, e-mail, hash da senha) | `tb_user` | Enquanto a conta existir | Exclusão da conta pelo usuário |
| Foto de perfil | `tb_user_photo` | Enquanto a conta existir ou até ser removida | Remoção da foto ou exclusão da conta |
| Vagas, candidaturas e histórico de etapas | `tb_job`, `tb_application`, `tb_application_status_history` | Enquanto a conta existir ou até a exclusão da candidatura | Exclusão da candidatura ou da conta |
| Importação (URL ou descrição colada, texto coletado, proposta e evidências) | `tb_import_task` | 24 horas após a criação, em qualquer estado | Limpeza a cada hora, em lotes; descarte pelo usuário; exclusão da conta |
| Texto coletado e proposta de uma importação confirmada | `tb_import_task` | Até a confirmação | Apagados na confirmação; a linha sai no prazo de 24 horas |
| Token de redefinição de senha (hash) | `tb_password_reset_token` | Válido por 30 minutos; guardado até 24 horas após vencer | Limpeza a cada hora (`RetentionScheduler`); exclusão da conta |
| Sessão de login | Memória do servidor | 30 minutos sem uso | Expiração, logout, troca de senha ou exclusão da conta |
| Texto enviado à IA | Provedor configurado | Conforme os termos do provedor | Não guardado pela aplicação além da importação; no nível gratuito do Gemini o Google pode usar o conteúdo, por isso produção deve usar o plano pago |

Os tokens vencidos ficam 24 horas para permitir investigar pedidos abusivos de
redefinição; só o hash é guardado, nunca o link enviado.

## Exclusão da conta

`DELETE /api/users/me` remove a conta, e o banco apaga em cascata a foto, as
vagas, as candidaturas, o histórico, as importações (inclusive as em
processamento) e os tokens de redefinição. As sessões abertas deixam de valer.
Se uma importação estava em processamento, o resultado atrasado é descartado
e não recria dados.

## Logs

Os logs da importação e da extração registram só identificador, tipo, tentativa,
estado, código de falha, provedor, quantidade de campos e duração. URL,
descrição, texto coletado, proposta, título revisado e e-mail não são
registrados, nem com o log da aplicação em DEBUG. A recusa do provedor de IA
registra só a categoria do erro, sem a mensagem.

## Operação

- As duas limpezas rodam de hora em hora: `ImportWorker` (importações) e
  `RetentionScheduler` (tokens). Ambas podem ser desligadas por propriedade
  (`app.import-worker.scheduling-enabled` e `app.retention.scheduling-enabled`),
  o que só faz sentido em testes.
- Uma limpeza interrompida retoma na hora seguinte; o acesso aos dados vencidos
  já é negado pelo prazo, mesmo antes da remoção física.
- Backups seguem o prazo definido em [Operação](operacao.md). Dados apagados
  continuam nos backups até eles expirarem; a restauração de um backup deve ser
  seguida da execução das limpezas.

## Verificação

| Teste | O que prova |
| --- | --- |
| `ImportTaskQueueIntegrationTest.purgesExpiredTasksWithoutRemovingConfirmedJob` | A limpeza remove importações vencidas, inclusive concluídas, e mantém a vaga confirmada |
| `DataRetentionIntegrationTest.removesResetTokensOneDayAfterTheyExpire` | Tokens saem 24 horas após vencer; recentes e válidos ficam |
| `DataRetentionIntegrationTest.deletingTheAccountDuringAnActiveImportRemovesEveryAssociatedRecord` | Exclusão pela API com importação em processamento: nenhum registro da conta sobra, o worker atrasado não recria dados e outra conta não é afetada |
| `ImportLogPrivacyIntegrationTest` | Com DEBUG, descrição, URL, título e e-mail não aparecem nos logs da importação |
| `HttpJobExtractorTest.logsTheProviderErrorCategoryWithoutTheMessage` | Recusa do provedor registra só a categoria |
