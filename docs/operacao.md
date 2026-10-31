# Operação, homologação e produção

**Estado: plano de execução.** Não há ambiente, domínio, pipeline de deploy,
alertas, backup ou restauração comprovados por esta entrega documental.
Não usar este roteiro como registro de uma publicação realizada.

## Ambientes e decisões pendentes

| Ambiente | Finalidade | Dados |
|---|---|---|
| Desenvolvimento | Implementação e exploração local | Fictícios ou pessoais locais, fora do Git |
| Testes | Suítes que criam/excluem dados | Exclusivamente descartáveis |
| Homologação | Validar a release candidate | Massa controlada, separada de produção |
| Produção | Uso real da versão aprovada | Banco dedicado, acesso restrito |

Antes de provisionar, registrar provedor, custo, região, domínio, responsáveis,
credenciais de serviço, retenção e conectividade. Custos exigem decisão
explícita do responsável. Não publicar PostgreSQL diretamente na internet.

Preferir mesma origem para frontend/API via proxy HTTPS. Proteger variáveis
de ambiente e separar o usuário de migrações das permissões da aplicação.
Não usar os perfis HTTP locais em produção. Conferir cookie Secure, HttpOnly
e SameSite no navegador, inclusive após o proxy de terminação TLS.

As sessões atuais são locais à instância. Antes do rollout, escolher entre
persistência/compartilhamento de sessão ou logout controlado e comunicado.
Escalar instâncias sem essa decisão pode interromper sessões.

## Checklist de homologação

- [ ] Artefato associado a commit/tag imutável e build aprovado.
- [ ] Migrações testadas em PostgreSQL vazio e em cópia sanitizada de dados.
- [ ] Regressão, segurança, acessibilidade e desempenho verificados.
- [ ] Jornadas UAT aceitas e limitações conhecidas registradas.
- [ ] Sem defeitos críticos/altos em aberto para a liberação.
- [ ] Segredos ausentes do frontend, logs e relatórios compartilhados.
- [ ] Backup/restore e rollback ensaiados em ambiente separado.
- [ ] Critérios de indisponibilidade, aprovação e responsáveis definidos.

Metas iniciais do planejamento: RPO de 24 horas e RTO de 4 horas. São objetivos
propostos, não garantias de operação. Validar viabilidade e resultados antes
de comunicar compromisso aos usuários.

## Backup e restauração

O volume do banco preserva dados, mas não substitui backup. Definir agenda,
retenção, criptografia, armazenamento separado e monitoramento de falhas.
Não colocar dump de dados reais no repositório.

Ensaio obrigatório antes de produção:

1. Gerar backup de PostgreSQL em homologação por mecanismo aprovado.
2. Registrar versão do banco, horário, identificador e integridade do backup.
3. Restaurar em banco novo e isolado, sem apontar a aplicação para produção.
4. Conferir migrações, contagens, relacionamentos e amostra de jornadas.
5. Medir tempo de restauração e perda possível de dados; comparar RPO/RTO.
6. Registrar resultado e repetir o ensaio quando houver mudança relevante.

Comandos finais dependerão do provedor e das versões escolhidas. Não fornecer
um comando de restore destrutivo genérico sem destino validado.

## Procedimento de release

1. Confirmar versão candidata, evidências, aprovação e janela.
2. Conferir backup recuperável e plano de retorno.
3. Aplicar migrações compatíveis com a versão anterior quando possível.
4. Publicar o mesmo artefato homologado, sem recompilação por ambiente.
5. Executar smoke com conta sintética controlada, limpando seus registros.
6. Conferir HTTPS, cookies, cadastro/login, perfil e candidatura manual.
7. Testar importação somente quando implementada e habilitada.
8. Monitorar erros/latência e registrar decisão de manter ou retornar.
9. Registrar horário, versão, resultado e responsável.

Não executar a fixture v1 em produção: ela foi projetada para HTTP local.
O smoke de produção deve ser restrito e possuir procedimento próprio.

## Rollback

Definir gatilhos objetivos no planejamento da release, como falha de login,
perda de integridade ou aumento sustentado de erros. Preservar diagnóstico
sanitizado e interromper avanço antes de ampliar impacto.

Retornar o artefato somente se o schema continuar compatível. Não apagar o
histórico Flyway nem executar uma migração inversa improvisada. Mudanças
incompatíveis exigem plano específico, preferindo expandir/migrar/contrair.

Restore de backup pode perder escritas posteriores. Sua utilização exige
avaliação do impacto e aprovação do responsável, não é rollback automático.
Após retorno, repetir o smoke e registrar incidente e dados afetados.

## Observabilidade e incidentes

Planejar verificação de disponibilidade, taxa de erros, latência, recursos,
conexões de banco, falhas de backup e, no futuro, fila de importação e cotas IA.
Endpoints de saúde e métricas ainda precisam ser implementados/protegidos;
não presumir a existência de `/actuator/health`.

Definir destinatário dos alertas, limiares e ação esperada; exercitar pelo
menos um alerta em homologação. Logs devem permitir correlação sem gravar
senhas, cookies, tokens, conteúdo pessoal ou respostas privadas do provedor.

Em incidente: registrar início/versão/sintoma, conter impacto, avaliar retorno,
comunicar o responsável, verificar recuperação e abrir análise posterior.
Evitar alterações diretas sem evidência ou migração versionada.

## IA em produção

Antes de habilitar Gemini, rever modelo, cotas, termos e política de dados.
O uso gratuito não deve receber dados pessoais/confidenciais. Cadastro
manual permanece disponível em falha/cota; não ativar cobrança automaticamente.

## Registro de execução

Todos os checklists começam pendentes. Anexar evidências reais às tarefas de
homologação/produção. Completar [release e retrospectiva](releases.md) somente
após os gates aplicáveis, sem marcar tarefas concluídas pela existência do guia.
