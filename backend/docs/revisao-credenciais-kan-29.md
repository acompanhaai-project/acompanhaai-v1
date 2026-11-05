# KAN-29 — Revisão inicial de configurações e permissões

Revisão em 03/11/2026, na branch chore/KAN-29-isolar-credenciais.
Escopo: arquivos versionados e tentativa de consulta de metadados no
PostgreSQL local. Nenhuma senha foi solicitada ou exibida. Arquivos privados
application-local.properties e .env não foram lidos. Nenhuma role, permissão,
senha ou dado de negócio foi alterado.

## Achados confirmados na configuração inicial

| ID  | Achado                                                                                                | Consequência e encaminhamento                                                                                                          |
| --- | ----------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| C01 | application.yml habilita Flyway sem datasource/usuário próprio para migração                          | Flyway usa o datasource da aplicação; separar a identidade de migração da identidade usada em operação                                 |
| C02 | compose.yaml define POSTGRES_USER como acompanhaai                                                    | Em volume novo, a imagem oficial cria esse usuário como superusuário; não usá-lo como identidade restrita da API                       |
| C03 | DB_PASSWORD é usada tanto na inicialização do PostgreSQL do Compose quanto no datasource padrão       | A configuração atual não distingue o segredo de administração do segredo da aplicação; separar variáveis e responsabilidades           |
| C04 | O usuário padrão da API é acompanhaai, mas o arquivo local privado pode sobrescrever o datasource     | O nome configurado no repositório não comprova a identidade efetivamente usada na máquina; confirmar localmente sem compartilhar senha |
| C05 | Hibernate usa ddl-auto: validate e Flyway está habilitado                                             | Há uma base para manter alterações de estrutura sob controle de migrações; não trocar para update para contornar falta de privilégios  |
| C06 | .env, variantes .env.*, application-local.properties, logs e relatórios locais são ignorados pelo Git | Proteção existente contra inclusão acidental; .env.example e o exemplo de properties contêm apenas placeholders                        |
| C07 | Perfis dev e postgres-local desabilitam cookie Secure para HTTP local                                 | Uso restrito ao desenvolvimento; não usar esses perfis em produção                                                                     |
| C08 | E-mail local aponta para loopback; Mailpit expõe SMTP/interface somente no loopback                   | Destino de teste separado de destinatários externos; adaptador SMTP atual não atende produção                                          |
| C09 | CI usa banco/credenciais descartáveis e o executor KAN-27 cria cluster próprio                        | Não são credenciais de produção; preservar o isolamento dos testes destrutivos                                                         |
| C10 | Não há procedimento completo de rotação e validação de privilégios versionado                         | Completar na implementação da KAN-29; a revisão inicial não encerra o cartão                                                           |

O achado C02 descreve o comportamento de inicialização da imagem, não uma
consulta das permissões do banco instalado no Windows. Alterar POSTGRES_USER
ou POSTGRES_PASSWORD no Compose não reconfigura automaticamente um volume
já inicializado. Não apagar o volume para tentar corrigir permissões.

## Consulta ao PostgreSQL local

Foi tentada uma conexão sem prompt de senha, com psql -X -w, ao banco
acompanhaai em 127.0.0.1:5433, usuário acompanhaai. Após liberar acesso local
do processo, o servidor respondeu **fe_sendauth: no password supplied**.

Isso confirma resposta do servidor PostgreSQL e exigência de autenticação
nessa tentativa. Não confirma que o usuário existe, que é o usuário da API,
que sua senha está correta nem quais permissões possui. Não foi tentada
adivinhação de senhas ou leitura das credenciais privadas.

| Verificação no banco real                                         | Estado                                                                                                     |
| ----------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| Username local da API                                             | postgres, conforme registro da configuração local; outras sobreposições de runtime não foram inspecionadas |
| Superusuário, criação de roles/bancos, replicação e bypass de RLS | Todos habilitados para postgres, conforme resultado do pgAdmin                                             |
| Memberships da consulta 3                                         | Nenhum vínculo retornado para as roles filtradas                                                           |
| Proprietários do banco, tabelas e sequências                      | postgres; schema public sob pg_database_owner                                                              |
| Privilégios efetivos                                              | postgres tem todos os privilégios consultados nas tabelas e sequências listadas                            |
| Escrita no histórico Flyway e privilégios de criação/truncate     | Permitidos para postgres; inadequados para a identidade de operação da API                                 |
| Concessões padrão adicionais da consulta 8                        | Nenhuma entrada retornada em pg_default_acl; os padrões nativos continuam existindo                        |

### Evidências das consultas no pgAdmin

Em 03/11/2026, as capturas das consultas 1–8 identificaram o banco acompanhaai,
PostgreSQL 18.6, porta 5433, servidor ::1 e conexão como postgres. A listagem
das roles fora de pg_* retornou somente postgres. A configuração local registrada utilizava spring.datasource.username=postgres,
sem exposição de senha.

Foram listadas oito tabelas em public, incluindo flyway_schema_history, e seis
sequências, todas pertencentes a postgres. As consultas 3 e 8 vazias não são
erros: não houve memberships para as roles filtradas nem concessões adicionais
em pg_default_acl. Isso não remove os privilégios de superusuário, propriedade
ou os privilégios padrão do PostgreSQL.

A tabela tb_password_reset_token, criada pela V6 versionada no projeto, não
aparece na captura da consulta 6. Antes de provisionar as novas identidades,
conferir o histórico Flyway nesse mesmo banco. A imagem isolada não comprova
se a V6 deixou de ser executada, se a captura é anterior ou se houve outra
divergência. Não editar/apagar o histórico nem recriar tabelas manualmente.

Consulta complementar, somente de leitura:

```sql
SELECT
    installed_rank,
    version,
    description,
    success,
    installed_on
FROM public.flyway_schema_history
ORDER BY installed_rank;
```

O histórico inicial registrava V1–V5 com sucesso. Após iniciar a API no banco
correto, uma nova captura confirmou a V6 aplicada com sucesso em
03/11/2026. A divergência de migração foi resolvida por esse procedimento,
sem recriação do banco nem edição de migrações antigas.

O [guia de credenciais](credenciais.md) documenta a implementação posterior:
scripts de provisionamento/adoção, callback, perfis separados e rotação.
O executor isolado aprovou 103 testes usando as identidades separadas.
A aplicação dos novos privilégios no banco local ainda depende do procedimento
manual com a conexão administrativa.

## Como concluir a auditoria pelo pgAdmin

1. Conectar ao servidor e ao banco acompanhaai usando a conexão que você já
   utiliza. A senha deve ser digitada somente na interface de autenticação.
2. Abrir Query Tool no banco correto e carregar
   [audit-database-permissions.sql](../scripts/sql/audit-database-permissions.sql).
3. Executar o arquivo inteiro. Ele abre transação READ ONLY, consulta somente
   metadados de roles/objetos/privilégios e encerra com ROLLBACK. Não consulta
   tabelas de negócio, hashes de senha, pg_authid ou parâmetros secretos.
4. Identificar qual role a API utiliza, conferindo o username da configuração
   local na sua máquina. A role da conexão do pgAdmin pode ser administrativa
   e diferente; não confundir os dois usuários.
5. Para acompanhar todos os resultados no Query Tool, executar também cada
   SELECT separadamente, caso a interface mostre apenas o último resultado.
   Os SELECTs não fazem alterações. Em erro durante a execução em transação,
   executar ROLLBACK para encerrar a transação aberta.
6. Compartilhar somente resultados de metadados necessários à interpretação,
   sem arquivo local de configuração, senha, cookie ou token. Nomes de outras
   bases/roles privadas podem ser ocultados antes de compartilhar.

A consulta lista roles com login e suas permissões nos schemas não internos
do banco selecionado, inclusive a role administrativa. É esperado que um
administrador tenha poderes amplos; a avaliação deve focar na identidade da API
e nas roles às quais ela pode ter acesso.

### Validação do roteiro SQL

As oito consultas foram executadas com ON_ERROR_STOP=1 em PostgreSQL 18.6,
no cluster descartável da KAN-27, banco acompanhaai_kan27, role acompanhaai_qa
e porta efêmera 50530. A execução terminou com código 0 e ROLLBACK; o cluster
de QA foi encerrado ao finalizar. A saída local ficou em
target/kan27-postgres-29c5bcc1e8ac46199498462891d83030/audit-validation.txt.

Essa verificação demonstra que o roteiro executa no PostgreSQL utilizado pelo
projeto. As permissões de acompanhaai_qa pertencem ao ambiente descartável e
não comprovam as permissões da role utilizada pela API no banco local.

## Critério para a próxima implementação

- Identidade administrativa: provisiona roles e banco, fora do runtime da API.
- Identidade de migração: controla os objetos necessários às migrações Flyway,
  sem ser superusuário; acesso ao segredo restrito à execução das migrações.
- Identidade da aplicação: CONNECT, USAGE no schema e permissões necessárias
  de leitura/escrita nas tabelas de negócio e uso das sequências, sem propriedade
  dos objetos, criação de tabelas ou alteração do histórico Flyway.
- Revisar herança, SET ROLE e PUBLIC; apenas remover a flag superuser não
  demonstra que a conta perdeu poderes de criação/alteração de estrutura.
- Preservar dados e ownership existente durante uma migração planejada; validar
  criação limpa, upgrade e CRUD com a nova divisão em banco descartável antes
  de aplicar mudanças no banco local com dados.

Separar spring.flyway.user/password já evita reutilizar a credencial da API,
mas manter esse segredo dentro do processo ainda permite que ele execute DDL
na inicialização. Para produção, decidir e documentar se as migrações serão
executadas em etapa separada, sem fornecer a credencial de migração ao runtime.

## Referências consultadas

- [PostgreSQL 18 — Privileges](https://www.postgresql.org/docs/18/ddl-priv.html):
  propriedade e concessões são mecanismos distintos.
- [PostgreSQL 18 — Funções de informação e privilégios](https://www.postgresql.org/docs/18/functions-info.html):
  funções has_* para consultar permissões efetivas.
- [Imagem oficial PostgreSQL](https://hub.docker.com/_/postgres):
  POSTGRES_USER e inicialização em diretório de dados vazio.
- [Spring Boot — Database Initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html):
  datasource próprio do Flyway e inicialização controlada por migrações.
