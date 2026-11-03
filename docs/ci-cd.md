# Integração e entrega contínuas

Relacionamento: KAN-23 e evoluções de DevOps. Revisado em 03/11/2026.
**Estado: CI do backend implementado; deploy pendente.** O workflow
[Backend CI](../.github/workflows/backend-ci.yml) executa build e testes
em H2 e PostgreSQL, publica relatórios e disponibiliza o JAR após sucesso
do job H2. Não publica a aplicação em homologação ou produção.

## Pipeline do backend

Fonte da configuração: `.github/workflows/backend-ci.yml`.

| Item              | Configuração implementada                                |
| ----------------- | -------------------------------------------------------- |
| Provedor          | GitHub Actions                                           |
| Gatilhos          | PR para `main`, push em `main` e execução manual          |
| Runtime           | Amazon Corretto JDK 25 em `ubuntu-latest`                 |
| Build             | Maven Wrapper versionado                                 |
| Permissão         | `contents: read`; checkout sem persistir credenciais      |
| Bancos            | H2 em memória e PostgreSQL 18 efêmero, em jobs separados |
| Limite            | 15 minutos por job                                       |
| Retenção          | Relatórios e JAR mantidos por 7 dias                     |
| Resultado         | Build/testes, relatórios e JAR identificados pelo commit |

Os dois jobs fazem checkout, configuram Java com cache Maven, habilitam
a execução de `mvnw` no Linux e executam:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

O workflow acrescenta as propriedades de conexão específicas de cada banco.
A fase verify compila, executa os testes e empacota a aplicação.

| Job | Check publicado | Validação e artefatos |
| --- | --- | --- |
| `build-test` | `backend-build-test` | Suíte em H2; relatórios `testes-${{ github.sha }}` e JAR `backend-${{ github.sha }}` |
| `postgres-test` | `backend-postgres-test` | Suíte em PostgreSQL 18, incluindo migrações e testes específicos; relatórios `testes-postgres-${{ github.sha }}` |

O H2 usa banco em memória com modo de compatibilidade PostgreSQL. Isso não
substitui a validação no PostgreSQL real. O outro job inicia um serviço
PostgreSQL 18 com healthcheck, banco descartável e porta mapeada dinamicamente.
As credenciais desse serviço são exclusivamente de CI, sem acesso ao banco real.

Os relatórios Surefire XML e texto são enviados com `if: always()`, inclusive
quando testes falham, desde que tenham sido gerados. O JAR
`target/acompanhaai-0.0.1-SNAPSHOT.jar` é publicado somente após sucesso do job H2.
Como os jobs são independentes, a existência desse JAR não comprova aprovação
do job PostgreSQL: conferir ambos os checks antes de aprovar uma release.

O workflow não executa a coleção Postman; ela possui procedimento próprio em
[qualidade](qualidade.md). Os testes de integração Java fazem parte do verify.

Não copiar segredos locais ou dados de desenvolvimento para o runner.
Antes de compartilhar relatórios de falhas, revisar seu conteúdo: eles podem
incluir requisições e dados fictícios usados nos testes.

## Consultar execuções e artefatos

1. No GitHub, abrir o PR e consultar os dois checks em Checks ou abrir
   Actions → Backend CI e selecionar a execução do commit desejado.
2. Abrir o job para consultar etapas, erros e resultado do Maven.
3. Na página da execução, consultar Artifacts para baixar relatórios e JAR.
4. Registrar URL da execução, commit e resultado na tarefa correspondente.

Para disparar manualmente, abrir Actions → Backend CI → Run workflow e
selecionar a branch. Um push apenas para uma branch de trabalho sem PR aberto
não corresponde ao gatilho push, que está limitado à main.

## Proteção da main

A proteção foi informada como ativada pelo usuário, com backend-build-test
obrigatório. O workflow também publica backend-postgres-test; conferir se esse
check foi adicionado à regra para que falhas no PostgreSQL bloqueiem o merge.
A regra atual do GitHub não foi consultada nesta atualização documental.

Continuar exigindo PR, resolução de conversas e bloqueio de force push e
exclusão. No trabalho individual, zero aprovações externas permite manter
a autorrevisão sem bloquear todos os PRs.

## Roteiro de validação do bloqueio de merge

1. Abrir PR com workflow e acompanhar uma execução bem-sucedida.
2. Registrar URL da execução, commit, resultado e artefatos sem segredos.
3. Em uma branch de demonstração, introduzir uma falha controlada em teste.
4. Confirmar execução vermelha e bloqueio de merge pelo check obrigatório.
5. Corrigir a falha antes de qualquer merge e demonstrar nova execução verde.
6. Revisar logs/artefatos para garantir que não incluam credenciais.

O teste falhando é um exercício de validação do gate, não um defeito real
do produto a cadastrar como Bug. Não deixá-lo na main.

## Evolução por etapa

- PostgreSQL efêmero, migrações e comportamento específico já estão incluídos
  no CI. Essa implementação não encerra automaticamente os critérios da KAN-34.
- KAN-41: build, verificações e artefato Angular, quando o frontend existir.
- Homologação: artefato imutável promovido para ambiente separado.
- Produção: aprovação da release, migração, implantação, smoke e recuperação.

Não regenerar ou atualizar a fixture v1 no pipeline. Regredir o contrato novo
com v2 e registrar incompatibilidades observadas na v1 sem escondê-las.

## Entrega e segredos

Destino de hospedagem, domínio e contas de serviço ainda serão definidos.
Credenciais devem ficar nos mecanismos de segredo do ambiente de execução,
com permissões mínimas. Deploy não deve ser executado por PR não confiável.

Não há publicação automática implementada nem contratação de infraestrutura
autorizada por este documento. O procedimento futuro está em
[operação](operacao.md).

## Evidências de validação — KAN-23

Os itens abaixo registram a validação informada durante a KAN-23. Esta revisão
conferiu o arquivo do workflow, sem disparar uma nova execução remota ou
revalidar a proteção da main. A aprovação de cada novo PR depende de seus checks.

- Pipeline executado com sucesso no PR.
- Check backend-build-test configurado como obrigatório na main.
- Teste temporário introduzido para provocar falha controlada.
- Check vermelho e bloqueio de merge confirmados.
- Teste temporário removido.
- Nova execução aprovada após a remoção.
- Relatórios de testes e JAR disponíveis na execução bem-sucedida.
