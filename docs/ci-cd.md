# Integração e entrega contínuas

Relacionamento: KAN-23 e evoluções de DevOps.
**Estado: especificação planejada.** Nesta revisão, `.github/` contém o
template de PR, mas não contém um workflow de CI. Este documento não cria
um pipeline nem demonstra build aprovado no GitHub.

## Pipeline inicial do backend

Implementar um workflow em `.github/workflows/` durante o KAN-23.

| Item | Decisão proposta |
|---|---|
| Provedor | GitHub Actions |
| Gatilhos | PR para `main` e push em `main` |
| Runtime | JDK 25 |
| Build | Maven Wrapper versionado |
| Permissão inicial | Leitura do conteúdo; ampliar somente se necessário |
| Banco inicial | H2 em memória, com datasource explícito de teste |
| Resultado | Build/testes, relatórios e JAR identificados pelo commit |

Etapas: checkout, preparação do JDK, cache de dependências, execução de
`verify`, publicação dos relatórios mesmo em falha e publicação do pacote
somente após sucesso. Usar o comando H2 do [guia de qualidade](qualidade.md),
adaptando `mvnw.cmd` para `./mvnw` em runner Linux.
Conferir também a permissão de execução do wrapper no checkout Linux.

Ao implementar, conferir versões e permissões das Actions e fixar suas
referências de acordo com a política do repositório. Não copiar segredos
locais, diretório H2 ou relatórios Postman para o runner.

## Proteção da main

A proteção foi informada como ativada pelo usuário. Durante a implementação
do CI, conferir a regra no GitHub e exigir o nome real do check que o workflow
publicar. Não exigir um check inexistente, pois o merge ficará bloqueado.

Continuar exigindo PR, resolução de conversas e bloqueio de force push e
exclusão. No trabalho individual, zero aprovações externas permite manter
a autorrevisão sem bloquear todos os PRs.

## Validação do KAN-23

1. Abrir PR com workflow e acompanhar uma execução bem-sucedida.
2. Registrar URL da execução, commit, resultado e artefatos sem segredos.
3. Em uma branch de demonstração, introduzir uma falha controlada em teste.
4. Confirmar execução vermelha e bloqueio de merge pelo check obrigatório.
5. Corrigir a falha antes de qualquer merge e demonstrar nova execução verde.
6. Revisar logs/artefatos para garantir que não incluam credenciais.

O teste falhando é um exercício de validação do gate, não um defeito real
do produto a cadastrar como Bug. Não deixá-lo na main.

## Evolução por etapa

- KAN-34: PostgreSQL efêmero no CI, migrações e comportamento específico.
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
