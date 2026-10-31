# Ambiente local e perfis

Relacionamento: KAN-21. Roteiro baseado nos arquivos de configuração existentes.
Nenhuma conexão, migração ou suíte foi executada durante a escrita deste guia.

## Pré-requisitos

- JDK 25 disponível no terminal e selecionado na IDE.
- Maven Wrapper versionado: `mvnw`, `mvnw.cmd` e `.mvn/`.
- Acesso à internet para baixar Maven/dependências no primeiro uso.
- PostgreSQL para validar o comportamento real do banco, ou Docker Desktop
  caso seja usado o serviço definido em `compose.yaml`.
- Node.js 22 e npm para executar o cenário Postman v1, quando necessário.

Na raiz do repositório, em PowerShell:

```powershell
java -version
.\mvnw.cmd -version
```

## Matriz de ambientes

| Perfil | Banco configurado | Persistência | Cookie Secure |
|---|---|---|---|
| `dev` | H2 em `./data/acompanhaai` | Arquivo local | `false`, apenas local |
| `test` | H2 em memória | Vida do contexto de teste | `false`, apenas teste |
| `postgres-local` | PostgreSQL, porta padrão local 5433 | Servidor/volume | `false`, apenas local |
| Sem perfil | PostgreSQL, porta padrão local 5433 | Servidor/volume | `true` por padrão |

Produção ainda não está provisionada. O perfil padrão não transforma um banco
local em banco de produção. Não combinar `dev` e `postgres-local`.

## Configuração e precedência

`application.yml` importa opcionalmente `application-local.properties` na raiz.
O importado pode substituir valores do documento que o importou. Configurações
de perfil e sobrescritas externas também participam da resolução: conferir a
URL final antes de executar testes que excluem dados.

As variáveis `DB_URL`, `DB_PORT`, `DB_USERNAME` e `DB_PASSWORD` são referências
usadas pelos YAMLs. Se o arquivo privado define diretamente
`spring.datasource.password`, alterar apenas `DB_PASSWORD` pode não alterar
essa propriedade direta. Padronizar a origem da configuração por execução.

`DB_URL` prevalece sobre a montagem que utiliza `DB_PORT`. O Compose lê `.env`;
o Spring Boot atual não carrega esse arquivo automaticamente.

Para uma execução controlada, `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD` fornecem as
propriedades diretamente por ambiente. Argumentos da aplicação ou propriedades
de sistema dos testes podem voltar a sobrescrevê-las.

Não imprimir o datasource completo, a senha ou o conteúdo do arquivo privado
em evidências. Usar a raiz do projeto como diretório de trabalho.

## Iniciar com H2

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

API: `http://localhost:8080`. A primeira inicialização aplica as migrações no
banco escolhido; as seguintes validam/aplicam as migrações pendentes.

Se uma configuração privada ou externa estiver redirecionando a execução para
PostgreSQL, revisar a origem antes de continuar. Não excluir `data/` como
procedimento normal: ali estão as contas locais do H2.

## Iniciar com PostgreSQL nativo

Criar previamente um banco de desenvolvimento e um usuário apropriado.
Flyway cria tabelas, não o banco nem o usuário.

```powershell
$credencialLocal = Get-Credential -Message "Credenciais do banco de desenvolvimento"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5433/acompanhaai"
$env:SPRING_DATASOURCE_USERNAME = $credencialLocal.UserName
$env:SPRING_DATASOURCE_PASSWORD = $credencialLocal.GetNetworkCredential().Password
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres-local"
```

As credenciais continuam existindo na memória do processo. Não publicar dumps
de ambiente. Após parar a aplicação, remover as variáveis desta sessão:

```powershell
Remove-Item Env:SPRING_DATASOURCE_URL -ErrorAction SilentlyContinue
Remove-Item Env:SPRING_DATASOURCE_USERNAME -ErrorAction SilentlyContinue
Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
Remove-Variable credencialLocal -ErrorAction SilentlyContinue
```

## PostgreSQL com Compose

Copiar `.env.example` para `.env` somente se o arquivo ainda não existir.
Preencher a senha local sem colocá-la em exemplos versionados.

```powershell
docker compose up -d postgres
docker compose ps
```

O Compose expõe por padrão `127.0.0.1:5432`; o PostgreSQL nativo deste projeto
usa 5433. Para usar o container, adaptar a URL do bloco anterior para 5432
ou para a porta definida no seu `.env`. O serviço configura o banco e o
usuário `acompanhaai`; usar a senha escolhida no `.env`.

O usuário criado por `POSTGRES_USER` não representa a política de permissões
mínimas de produção. KAN-29 separará credenciais e permissões.

`docker compose down` para o serviço preservando o volume. Não usar
`down -v` como rotina: remove os dados. Alterar a senha no `.env` não muda
a senha de um volume já inicializado.

## Verificação reproduzível do KAN-21

1. Registrar JDK, commit, perfil e banco escolhido, sem credenciais.
2. Usar um banco novo e descartável de desenvolvimento para comprovar a
   aplicação das migrações V1 a V5. Não limpar o banco pessoal para isso.
3. Conferir tabelas e histórico do Flyway na ferramenta de banco.
4. Executar `GET /api/auth/csrf`; esperar 200 e preservar o cookie.
5. Cadastrar uma conta fictícia, autenticar e consultar `/api/users/me`.
6. Criar uma candidatura, reiniciar a aplicação e verificar persistência.
   Fazer novo login: a sessão atual fica em memória.
7. Executar testes somente no H2 em memória ou em PostgreSQL de testes
   separado, seguindo o [guia de qualidade](qualidade.md).
8. Conferir cookie local sem Secure. HTTPS e cookie Secure devem ser
   verificados no ambiente de homologação antes de produção.

## Diagnóstico

| Sintoma | Verificação |
|---|---|
| Conexão recusada | Serviço iniciado, porta correta e URL efetiva |
| Autenticação PostgreSQL recusada | Usuário/banco corretos e origem da senha; não colar a senha em logs |
| Tabelas não criadas | Aplicação chegou a iniciar? Flyway concluiu? Banco/usuário já existem? |
| Login local não persiste | Perfil local e cookie; Secure exige HTTPS |
| H2 inesperadamente abre PostgreSQL | Sobrescritas privadas, ambiente e parâmetros da IDE |
| Perfil não encontrado | Diretório de execução e grafia do argumento |

## Registro de execução

Estado inicial: **pendente**. Preencher commit, ambiente, procedimento,
resultado, divergências, evidência sanitizada e responsável após executar.
