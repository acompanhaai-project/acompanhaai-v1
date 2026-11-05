# Credenciais e permissões — KAN-29

Objetivo: separar administração, migração e operação sem recriar o banco.
Consultar a [auditoria inicial](revisao-credenciais-kan-29.md) e os resultados
do executor isolado antes de aplicar no banco local com dados.

## Identidades

| Role                                                | Responsabilidade                              | Permissões                                                                                                     |
| --------------------------------------------------- | --------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| postgres, ou administrador provisionado no ambiente | Criar banco/roles e adotar objetos existentes | Administração; nunca usar no datasource da API                                                                 |
| acompanhaai_migrator                                | Flyway                                        | CONNECT, USAGE/CREATE em public e propriedade das tabelas conhecidas; sem administração do cluster             |
| acompanhaai_app                                     | CRUD da API                                   | CONNECT, USAGE em public, SELECT/INSERT/UPDATE/DELETE nas tabelas de negócio e USAGE nas sequências vinculadas |

As duas contas novas não são superusuárias, não criam bancos/roles, não têm
replicação, bypass de RLS ou memberships. A conta da API não possui objetos,
não pode criar/alterar tabelas, truncá-las, assumir a role migrator ou acessar
flyway_schema_history. O usuário administrativo continua administrando o banco.

O banco deve ser dedicado ao Acompanhaai. O provisionamento remove CONNECT,
TEMP e outros privilégios de PUBLIC no banco, além dos privilégios de PUBLIC
no schema public. Não executar em banco compartilhado com outras aplicações.

## Preparar o banco local existente

1. Parar a API e identificar a conexão: acompanhaai, PostgreSQL 18, porta 5433.
2. Gerar um backup recuperável antes de alterar ownership/privilégios e
   conferir o destino. Não versionar o backup nem apagar volumes.
3. No pgAdmin, conectado como postgres ao banco acompanhaai, executar
   [create-database-roles.sql](../scripts/sql/create-database-roles.sql).
4. Executar [adopt-database-objects.sql](../scripts/sql/adopt-database-objects.sql)
   na mesma base. Ele altera ownership somente das tabelas conhecidas e suas
   sequências vinculadas; não executa REASSIGN OWNED, DROP ou exclusão de dados.
5. Conferir as mensagens e só continuar se cada script terminar com COMMIT.
   Em falha no Query Tool, executar ROLLBACK e investigar antes de repetir.

Os scripts conferem o nome do banco. O parâmetro interno expected_database
é usado pelo teste isolado para validar uma segunda base descartável; não
alterá-lo no procedimento manual para contornar uma conexão incorreta.

Roles inexistentes são criadas com LOGIN, sem senha. Definir as senhas antes
de tentar autenticar por SCRAM. Em repetição, as senhas não são substituídas;
atributos administrativos ou memberships inesperados fazem o script abortar.
Objetos desconhecidos em public fazem a adoção abortar para revisão de escopo.

## Definir senhas sem colocá-las no SQL

Abrir um terminal interativo e conectar com psql. No PowerShell:

```powershell
& 'C:\Program Files\PostgreSQL\18\bin\psql.exe' -X -h localhost -p 5433 -U postgres -d acompanhaai -W
```

Dentro do psql:

```text
\password acompanhaai_app
\password acompanhaai_migrator
\q
```

O psql solicita as senhas interativamente, sem colocá-las no comando digitado.
Usar senhas diferentes para cada identidade. Não executar ALTER ROLE com uma
senha literal no Query Tool, não enviar as senhas ao chat e não exportar a
configuração preenchida para o Git ou relatórios. Não mudar pg_hba.conf para
trust para evitar a autenticação do ambiente local com dados.

## Configurar a inicialização com Flyway separado

O arquivo [application-local.properties.example](../application-local.properties.example)
mostra as chaves; os valores são placeholders. Atualizar somente o arquivo
privado application-local.properties, sem sobrescrever outros ajustes locais:

```properties
spring.datasource.username=acompanhaai_app
spring.datasource.password=SENHA_EXCLUSIVA_DA_APLICACAO
```

Copiar [application-migrations.properties.example](../application-migrations.properties.example)
para o arquivo privado application-migrations.properties e preencher:

```properties
spring.flyway.user=acompanhaai_migrator
spring.flyway.password=SENHA_EXCLUSIVA_DAS_MIGRACOES
spring.flyway.locations=classpath:db/migration,classpath:db/callback
```

Substituir os placeholders apenas nos arquivos privados. As credenciais Flyway
não devem ficar no arquivo application-local.properties importado globalmente:
application-migrations.properties é importado somente pelo perfil postgres-local.
Assim o H2 não herda o usuário PostgreSQL nem força um datasource Flyway separado.
Conferir também variáveis
de ambiente e argumentos que possam sobrescrever as propriedades. A senha
deve ser escapada conforme o formato properties, se contiver caracteres especiais.

Executar com o perfil local já utilizado:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=postgres-local'
```

Alternativamente, o perfil postgres-separated usa APP_DB_PASSWORD e
MIGRATION_DB_PASSWORD do ambiente e configura as identidades/callback. O Compose
lê .env, mas o Spring Boot executado no terminal não o carrega automaticamente.
Não misturar configuração privada antiga com outra fonte sem conferir as
propriedades efetivas; o datasource deve usar acompanhaai_app.

O callback afterMigrate concede acesso somente às tabelas conhecidas após
o Flyway concluir as migrações, inclusive em instalação limpa, e mantém o
histórico Flyway inacessível ao runtime. Não foi criada uma V7 artificial para
provisionar roles; as migrações V1–V6 permanecem intactas.

Não há concessão automática de CRUD em qualquer tabela futura. Ao criar uma
tabela de negócio, atualizar a lista do callback, revisar a adoção se necessário
e testar as permissões. O comportamento padrão é negar acesso ao objeto novo.

## Operação sem segredo de migração

Na inicialização com postgres-local, as duas credenciais ficam no processo da API,
embora sejam usadas por conexões diferentes. Isso não equivale a remover
a credencial privilegiada do runtime.

Após uma etapa de migração controlada e concluída, o perfil postgres-runtime
usa apenas APP_DB_PASSWORD e desabilita Flyway. Não combiná-lo com
postgres-separated ou postgres-local, que carrega o arquivo de migração.
Remover MIGRATION_DB_PASSWORD do ambiente e propriedades spring.flyway.user/password
eventualmente definidas em outras fontes desse processo. Para smoke HTTP local,
sobrescrever somente server.servlet.session.cookie.secure=false; manter Secure
em produção com HTTPS.

Para produção, publicar um processo de migração separado e fornecer seu segredo
somente a essa etapa. O pipeline de deploy e essa etapa ainda não foram
implementados. Não iniciar runtime com schema desatualizado; ddl-auto permanece
validate, de modo que divergências não sejam corrigidas silenciosamente.

## Compose e ambientes

Em volume novo, o Compose cria acompanhaai_admin usando POSTGRES_ADMIN_PASSWORD.
Essa credencial administrativa não é a senha da API nem a do Flyway. Depois
do bootstrap, provisionar as contas e definir suas senhas pelo procedimento
acima, ajustando o host/porta do psql para a instância do Compose.

Em volume já existente, as variáveis de inicialização não trocam usuários nem
senhas. Preservar a identidade administrativa original e ajustar a configuração
de inicialização/healthcheck conforme a auditoria. Não excluir o volume para
forçar uma reinicialização. Antes de rodar Compose com um .env antigo, atualizar
POSTGRES_ADMIN_PASSWORD; a antiga DB_PASSWORD não preenche essa variável.

| Ambiente               | Credenciais e destinatários                                                                                |
| ---------------------- | ---------------------------------------------------------------------------------------------------------- |
| Desenvolvimento        | Arquivo privado ignorado ou variáveis locais; SMTP local/Mailpit                                           |
| Testes isolados KAN-29 | Cluster temporário no loopback, roles de QA e autenticação trust exclusivamente nesse cluster              |
| CI atual               | Credenciais descartáveis do serviço PostgreSQL; não reutilizar segredos locais                             |
| Homologação/produção   | Segredos próprios por ambiente, acesso restrito e adaptador de e-mail apropriado; provisionamento pendente |

O adaptador SMTP atual aceita apenas loopback, sem autenticação ou relay
externo. Usar endereços fictícios e Mailpit/GreenMail; não enviar testes para
destinatários reais. O envio de produção continua fora desta implementação.

## Rotação e verificação

1. Identificar a conta e o ambiente, seus consumidores, a janela de manutenção
   e o local privado que fornece o segredo. Não copiar o valor para o cartão.
2. Para a conta da API, parar/drainar a instância conforme a janela: alterar
   senha não encerra automaticamente conexões já autenticadas.
3. Trocar a senha com \password em sessão administrativa interativa.
4. Atualizar o segredo somente no ambiente correspondente, reiniciar o
   consumidor para renovar o pool e executar smoke com conta sintética.
5. Confirmar cadastro/login/perfil/candidatura e revisar falhas de autenticação,
   sem imprimir o segredo. Em migração, validar o histórico com a identidade
   autorizada e garantir que o runtime continue sem acesso de escrita a ele.
6. Registrar identidade, ambiente, data, responsável e resultado. Não registrar
   senha anterior/nova. Se houver falha, restaurar o serviço com um segredo
   válido por procedimento controlado; não conceder superusuário à API.

Revisar logs/artefatos antes de compartilhar, inclusive relatórios Surefire que
podem conter dados fictícios, propriedades de teste e requisições em falhas.
Manter senhas, cookies e tokens fora de logs; não habilitar SMTP debug nem
registrar eventos de recuperação com seu token. Compartilhar o resumo sanitizado,
não arquivos privados ou dumps de banco. O teste isolado usa senhas vazias
somente porque seu cluster de QA tem autenticação trust no loopback.

## Validação reproduzível

```powershell
.\scripts\test-database-roles.ps1
```

O executor não aceita URL de banco existente. Cria cluster em target/kan29-postgres-GUID,
porta efêmera, banco acompanhaai e conta administrativa acompanhaai_qa_admin.
Copia somente YAMLs versionados, removendo o import da configuração privada.

Primeiro executa DatabaseRoleMigrationIntegrationTest: adoção V5→V6 preservando
um usuário fictício, repetição sem migrações pendentes e criação limpa em uma
segunda base descartável. Depois executa a suíte da API com datasource
acompanhaai_app e Flyway acompanhaai_migrator, mais quatro testes de privilégios.

O teste PostgresMigrationIntegrationTest não integra essa segunda fase porque
seu preparo cria schemas administrativos, privilégio que a API não deve ter.
O upgrade com identidades separadas é exercitado na primeira fase. Essa seleção
é explícita, sem alterar as expectativas do teste antigo ou da fixture v1.

O executor encerra sua instância inclusive em falha e mantém provision.log,
maven.log, reports e summary.json em target. O resumo registra contagens por
classe, instantes, versão e código de saída, sem credenciais ou corpos HTTP.
O heap dos testes é limitado a 384 MiB por padrão; TestHeapMiB permite ajustar
o limite sem alterar o código. Aplicar no banco local, validar autenticação
com as senhas escolhidas, CI e aceite continuam necessários.

## Evidências de execução — 03/11/2026

Base local 53350e9, com alterações ainda não commitadas da KAN-29. Ambiente:
Windows 11, Corretto 25.0.4.1, Maven 3.9.16, Spring Boot 4.0.9 e PostgreSQL 18.6.
Os testes usaram somente configurações versionadas copiadas sem import privado.

| Rodada                               | Resultado                                                                             | Limite da evidência                                                    |
| ------------------------------------ | ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| PostgreSQL com identidades separadas | 103 aprovados, sem falhas/erros/ignorados; 2 de provisionamento e 101 na segunda fase | Autenticação trust de QA; não comprova senhas SCRAM no banco local     |
| Runtime sem credencial Flyway        | 2 testes HTTP aprovados com postgres-runtime ativo e sem inicialização Flyway         | Reexecução focada dos cenários de sessão, após schema já migrado       |
| H2 após correção da configuração     | 97 aprovados, 7 ignorados; total reportado 104; sem falhas/erros                      | Ignorados: 6 testes condicionais KAN-29 e 1 teste exclusivo PostgreSQL |

A rodada PostgreSQL 234a4b2ebacc42669bc4118c86d19f1d usou porta efêmera 63002,
com Maven da segunda fase concluído às 14:26:06, America/Sao_Paulo.
O [resumo por classe](evidencias/kan-29-postgres.json) corresponde às duas fases.
O cluster foi encerrado pelo executor. A repetição dos dois scripts foi
aprovada no mesmo ambiente descartável durante a checagem complementar.

Runtime e H2 estão registrados no [resumo complementar](evidencias/kan-29-complementar.json).
Os logs locais estão em target/kan29-checks-188e19241db94258bbb6353b47a319c3:
runtime.log e h2-retest.log. O reteste H2 terminou às 14:44:27 com BUILD SUCCESS.
Os processos de banco temporários foram encerrados.

### Falhas encontradas e reteste

1. A primeira tentativa complementar não iniciou a JVM de testes por falta de
   memória/arquivo de paginação do Windows. Foi repetida com heap inicial de
   32 MiB e máximo de 384 MiB, sem encerrar processos do usuário. A falha ocorreu
   antes de executar os testes e não foi contabilizada como aprovação.
2. Ao forçar credenciais Flyway próprias também em H2, a suíte apresentou
   6 falhas e 2 erros, com causa interna database has been closed na avaliação
   de CHECK. A pilha é compatível com o [problema de sessões do H2 2.4.240](https://github.com/h2database/h2database/issues/4320).
   A configuração passou a manter as credenciais PostgreSQL de migração em
   arquivo privado específico do perfil postgres-local. O H2 voltou a usar
   o datasource compartilhado, sem remover CHECKs, alterar migrações ou
   enfraquecer testes. O reteste completo aprovou os 97 cenários aplicáveis.

O perfil test também desabilita dumps automáticos MockMvc e logs informativos
do GreenMail. A revisão dos logs complementares não identificou dumps de
JSESSIONID, cabeçalhos CSRF ou mensagens de senha de usuário do GreenMail.
Isso é uma conferência desses artefatos, não uma garantia para qualquer falha
futura. Relatórios completos permanecem locais e exigem revisão antes de compartilhar.

### Smoke da API local após provisionamento

O usuário executou os scripts no banco local, configurou as contas e forneceu
log de inicialização com V1–V6 validadas e callback afterMigrate concluído.
O pgAdmin mostrou conexões JDBC com nome iniciado por acompanhaai_a; a coluna
estava truncada na captura. O smoke HTTP abaixo demonstra o funcionamento da
API nessa configuração, mas sozinho não identifica a role efetiva da conexão
nem substitui a auditoria SQL de privilégios.

As primeiras tentativas válidas excederam o prazo de resposta. A verificação
do Windows confirmou postgresql-x64-18 parado e porta 5433 sem resposta.
O usuário restabeleceu o serviço; não foi alterada senha ou permissão para
contornar esse problema. Não se tratava de evidência de credencial inválida.

Com o banco disponível, foi executado:

```powershell
.\scripts\smoke-account-applications.ps1
```

Resultado em 03/11/2026 às 15:21:23, America/Sao_Paulo: **15 requisições e
35 verificações aprovadas**, em http://localhost:8080. Foram conferidos CSRF,
cadastro/login, consulta/edição de perfil, cadastro/consulta/edição/exclusão de
candidatura, mudança APPLIED→INTERVIEW, histórico e filtro. Após exclusão, a
candidatura retornou 404; a conta de teste foi excluída e a sessão retornou 401.

O script usa cookies próprios, senha aleatória em memória e e-mail fictício
único em example.test. Não carrega credenciais de banco nem cookies do usuário,
não executa a fixture completa e só permite origem HTTP no loopback, sem
redirecionamentos. Antes de modificar/excluir o perfil, confere a identidade
fictícia. Em falha, tenta autenticar novamente para limpar somente essa conta.
Limpeza não confirmada é reportada explicitamente, sem exclusões em massa.

O [relatório sanitizado](evidencias/kan-29-smoke-local.json) contém somente
status, caminhos, contagens e identificação da massa fictícia, sem senhas,
cookies, tokens ou corpos HTTP. A conta e a candidatura da rodada aprovada
foram removidas. Os dois e-mails das tentativas interrompidas foram conferidos
separadamente: não havia contas preexistentes; as contas criadas nessa checagem
também foram excluídas. A evidência local dessa conferência está em
target/kan29-live-diagnostics/orphan-check.json.

O smoke não altera o status de CI ou o aceite no Jira e não executa testes
destrutivos contra contas pessoais.

O workflow acrescenta backend-database-role-isolation para reproduzir as duas
fases no CI. Sua configuração foi revisada localmente; nenhuma execução remota
foi observada nesta entrega. Fixture v1 e migrações V1–V6 foram preservadas.
