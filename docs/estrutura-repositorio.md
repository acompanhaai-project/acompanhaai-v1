# Estrutura do repositório

Reorganização feita na KAN-38, antes do PR da base Angular.

## Módulos

| Local                                      | Responsabilidade                                                 |
| ------------------------------------------ | ---------------------------------------------------------------- |
| backend/src, backend/pom.xml, backend/.mvn | Código Java, recursos, migrações, testes e Maven                 |
| backend/scripts                            | Roteiros SQL, QA PostgreSQL e proteção de migrações              |
| backend/postman                            | Coleções, executores e pacotes próprios de testes da API         |
| backend/docs                               | Documentação técnica e evidências do backend                     |
| backend/compose*.yaml                      | Serviços locais PostgreSQL e Mailpit                             |
| backend/application-*.properties           | Configurações privadas locais, ignoradas pelo Git                |
| frontend                                   | Código, assets, ferramentas e documentação Angular               |
| docs                                       | Planejamento, arquitetura, qualidade e integração entre módulos  |
| .github                                    | Workflows e template de PR, mantidos na raiz exigida pelo GitHub |

O README da raiz é o índice dos módulos. O manual completo da API está em
[backend/README.md](../backend/README.md). Pacotes e lockfiles npm do Postman
permanecem independentes do pacote Angular.

## Diretório de execução

Comandos antigos de Maven, scripts, Compose e Postman continuam válidos
**dentro de backend/**. Da raiz, executar `cd backend` primeiro.
Os caminhos em evidências históricas representam suas execuções originais;
não foram reescritos para simular novas execuções.

Configurações privadas foram movidas para o backend sem alteração ou leitura
de seu conteúdo. As importações relativas do Spring continuam funcionando
quando o diretório de trabalho é `backend/`. Não executar Maven da raiz
usando apenas `-f backend/pom.xml` para iniciar a aplicação: prefira mudar
de diretório para preservar também os caminhos relativos de configuração e dados.

No IDE, importar `backend/pom.xml` como projeto Maven e configurar o diretório
de trabalho da API para `backend/`. A identidade dos pacotes Java não mudou.

O Angular continua usando `cd frontend` e `npm start`. O visual é provisório;
o usuário definirá UX/UI antes das telas completas.

## Dados locais e Docker Compose

Os diretórios locais `data/` e `bin/` foram movidos para o backend e continuam
ignorados. O `target/` antigo da raiz permanece como arquivo de ferramentas e
evidências locais; o Maven e os roteiros passam a gerar `backend/target/`.

Compose usa explicitamente o nome `acompanhaai`, o nome padrão anterior da
pasta do repositório. Isso evita trocar o nome padrão do volume do PostgreSQL
só por mover compose.yaml para backend. Não houve execução de Docker nem
alteração de volume. Um nome customizado via `-p` ou COMPOSE_PROJECT_NAME
deve continuar sendo fornecido pelo operador como antes.

## CI e arquivos protegidos

Os cinco nomes de checks foram preservados. O diretório padrão dos comandos
é `backend`; caches Maven/npm e uploads apontam para caminhos `backend/...`
relativos à raiz do checkout. Não é necessário alterar as regras da main
por causa dos nomes dos checks.

Migrações e fixture v1 foram movidas sem alterações de bytes ou hashes.
O gate de migrações compara o mesmo nome relativo nas raízes histórica e nova,
aceitando somente a relocação intacta. Edição, exclusão, renomeação interna,
mudança de tipo ou duplicação continuam rejeitadas. Foram adicionados cenários
específicos aos testes do gate. O manifesto SHA da v1 não foi regenerado.

Consultar os resultados em
[evidência da reorganização](evidencias/kan-38-reorganizacao.md).
