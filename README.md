# Acompanhaai

Backend para acompanhar candidaturas a vagas de emprego. Implementa contas, autenticação, foto de perfil opcional e CRUD de candidaturas com cadastro manual da vaga.

## Perfil e candidaturas

A foto é opcional. `GET /api/users/me` retorna `photoUrl: null` quando não houver foto; caso contrário, retorna `/api/users/me/photo`, acessível somente com a sessão autenticada. A imagem fica em `tb_user_photo`, separada dos dados da conta, e é removida ao excluir a conta. Os bytes não são enviados no JSON do perfil.

| Método | Rota | Resultado |
|---|---|---|
| PUT | `/api/users/me/photo` | Envia/substitui foto via multipart, campo `file`; retorna 204 |
| GET | `/api/users/me/photo` | Retorna JPEG/PNG; sem foto retorna 404 |
| DELETE | `/api/users/me/photo` | Remove a foto; retorna 204 mesmo se já não houver foto |
| POST | `/api/applications` | Cria vaga manual e candidatura na mesma transação; retorna 201 |
| GET | `/api/applications` | Lista apenas suas candidaturas, com paginação e filtro por status |
| GET | `/api/applications/{id}` | Consulta uma candidatura própria |
| PUT | `/api/applications/{id}` | Substitui os dados manuais da vaga e do acompanhamento; exige `version` |
| GET | `/api/applications/{id}/history` | Consulta o histórico de etapas, incluindo o registro inicial |
| DELETE | `/api/applications/{id}` | Exclui a candidatura, sua vaga e histórico; retorna 204 |

As novas rotas exigem autenticação; operações de escrita também exigem CSRF. IDs de outros usuários retornam 404. Fotos: JPEG/PNG de até 2 MiB e 4 milhões de pixels. O servidor identifica o formato pelos bytes e regrava a imagem sem os metadados originais. Arquivo inválido retorna 422; requisição multipart acima do limite retorna 413.

### Etapas do acompanhamento

| Código na API | Nome na interface | Significado |
|---|---|---|
| SAVED | Salva | Interesse salvo antes de se inscrever |
| APPLIED | Candidatura realizada | Inscrição enviada |
| SCREENING | Triagem | Currículo ou perfil em avaliação |
| INTERVIEW | Entrevista | Entrevistas e avaliações do processo |
| OFFER | Proposta | Oferta recebida, ainda em decisão |
| HIRED | Contratado | Oferta aceita e contratação confirmada |
| REJECTED | Reprovado | Processo encerrado pela empresa |
| WITHDRAWN | Desistência | Processo encerrado pelo candidato |

No quadro, as quatro etapas centrais são Candidatura realizada, Triagem, Entrevista e Proposta. Salvas ficam antes do funil; os três desfechos podem ficar em uma área de finalizadas. É permitido pular etapas e corrigir status, sempre preservando o histórico. Alterar apenas os dados da vaga ou observações não cria evento de status. A etapa de testes técnicos pode ser registrada em observações durante Entrevista por enquanto.

`appliedOn` é opcional: não inventamos uma data para inscrições antigas. O estado SAVED exige `appliedOn: null`. Sem `status` no cadastro, o padrão é APPLIED. Não são aceitas datas de inscrição futuras.

### Exemplos de candidaturas

Após o login e a renovação do CSRF, envie este JSON para `POST /api/applications`:

```json
{
  "job": {
    "title": "Desenvolvedor Java Júnior",
    "companyName": "Empresa Exemplo",
    "sourceUrl": "https://example.com/jobs/123",
    "description": "Java, Spring Boot e SQL."
  },
  "status": "APPLIED",
  "appliedOn": "2026-10-28",
  "notes": "Candidatura enviada pelo portal."
}
```

`job.title` é obrigatório, com até 255 caracteres. Empresa, link e descrição são opcionais. Descrição aceita até 30.000 caracteres e observações até 5.000. O link é apenas armazenado nesta etapa; nenhuma página é acessada. O mesmo link é único por usuário quando informado; usuários diferentes podem registrar a mesma vaga. A API ainda não recebe os campos adicionais de salário e habilidades da modelagem.

Listagem: `GET /api/applications?status=SCREENING&page=0&size=20`. A página começa em zero, com tamanho entre 1 e 100. A resposta contém `content`, `page`, `size`, `totalElements` e `totalPages`, ordenada pela última atualização e ID decrescentes.

Para editar, envie todos os campos editáveis por `PUT /api/applications/{id}`, incluindo o objeto `job`, o novo `status`, `appliedOn`, `notes` e a `version` recebida na consulta. Campos opcionais omitidos ficam nulos. Uma edição com versão antiga retorna 409: consulte o registro atualizado e revise sua alteração antes de reenviar. A atualização do status e o registro no histórico são atômicos.

Upload da foto, com PowerShell 7 e a sessão/headers do exemplo de autenticação:

```powershell
Invoke-RestMethod "$baseUrl/api/users/me/photo" -Method Put -WebSession $webSession `
    -Headers $headers -Form @{ file = Get-Item "C:\caminho\foto.png" }
```

O banco mantém uma imagem por conta. Para o MVP isso evita uma dependência externa; a camada de serviço permite evoluir para armazenamento de objetos quando necessário.

## Stack

- Java 25 e Spring Boot 4.0.9.
- Maven, Spring MVC, Spring Data JPA, Bean Validation e Spring Security.
- PostgreSQL e Flyway; H2 em arquivo para desenvolvimento e em memória nos testes.
- Autenticação por sessão (`JSESSIONID`), proteção CSRF e hash de senha PBKDF2-HMAC-SHA256 com salt individual.

## Camadas

```text
src/main/java/com/maiconamaral/acompanhaai/
├── entities/           # Entidades JPA
├── repositories/       # Acesso ao banco
├── services/           # Regras de negócio
├── resources/          # Controllers REST e tratamento de erros
├── dto/                # Dados de entrada e saída (Java records)
├── config/             # Configuração do Spring Security
└── security/           # Usuário autenticado e validação das sessões
```

O fluxo é `Resource → Service → Repository → banco`. As entidades não são retornadas pela API. DTOs distintos evitam receber ID, hash de senha ou informações internas como campos editáveis.

## Executar localmente

Requer JDK 25. O Maven Wrapper baixa o Maven no primeiro uso; a resolução das dependências precisa de internet.

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Com Maven instalado, também é possível usar `mvn` no lugar de `.\mvnw.cmd`.

A API fica em `http://localhost:8080`. O perfil `dev` salva o banco H2 em `./data/`, preservando contas entre reinícios. As sessões ficam na memória da aplicação: reiniciar exige novo login. Não há usuário ou senha de conta pré-cadastrados.

## PostgreSQL

A execução sem perfil usa o PostgreSQL local na porta **5433**. É possível mudar
a porta com `DB_PORT` ou fornecer a URL completa em `DB_URL` (que tem prioridade).
O banco e as credenciais precisam existir; o Flyway cria as tabelas, não o banco
nem o usuário do PostgreSQL. Para a porta padrão de outra instalação, defina
`DB_PORT=5432` ou uma `DB_URL` explícita.

Para executar pela IDE sem depender de variáveis do terminal, preencha
`application-local.properties`, na raiz do projeto, com as propriedades
`spring.datasource.username` e `spring.datasource.password` do seu PostgreSQL.
Remova o `#` dessas duas linhas para ativá-las. Esse arquivo é carregado
automaticamente e está ignorado pelo Git. Há um modelo em
`application-local.properties.example`. Use a pasta do projeto como diretório de
trabalho da execução. Nunca preencha credenciais reais no arquivo `.example`.

Crie um banco `acompanhaai` e forneça as configurações por variáveis de ambiente:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5433/acompanhaai"
$env:DB_USERNAME = "acompanhaai"
$env:DB_PASSWORD = "sua-senha-do-banco"
.\mvnw.cmd spring-boot:run
```

O perfil padrão usa cookie `Secure` e exige HTTPS para o fluxo de sessão no navegador. Para testar PostgreSQL em HTTP local, use `$env:SESSION_COOKIE_SECURE = "false"`. Em uma publicação, use HTTPS e mantenha `true`. Não coloque senhas ou chaves no Git.

O Flyway cria o schema a partir de `src/main/resources/db/migration`. O Hibernate apenas valida a estrutura (`ddl-auto: validate`).

### PostgreSQL local com Docker Compose

Para a instalação nativa do PostgreSQL 18 neste Windows, a porta configurada é **5433**.
O perfil `postgres-local` usa essa porta por padrão:

```powershell
# Configure DB_USERNAME e DB_PASSWORD com as credenciais do seu PostgreSQL.
$env:DB_URL = "jdbc:postgresql://localhost:5433/acompanhaai"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres-local"
```

Na IDE, selecione o perfil ativo `postgres-local` ou adicione
`--spring.profiles.active=postgres-local` aos argumentos da aplicação.
Uma variável `DB_URL` definida anteriormente tem prioridade sobre a URL padrão do perfil;
confira se ela também aponta para a porta 5433. O banco `acompanhaai` e o usuário
configurado precisam existir nessa instância.

Para usar Docker Compose na porta 5432, siga as instruções abaixo.

Requer Docker Desktop com o mecanismo de containers iniciado. Na pasta do projeto:

```powershell
Copy-Item .env.example .env
notepad .env
# Preencha DB_PASSWORD no arquivo .env antes de continuar.
docker compose up -d postgres
docker compose ps

# Informe no terminal a mesma senha definida no .env.
$dbCredential = Get-Credential -UserName acompanhaai -Message "Senha local do PostgreSQL"
$env:DB_PASSWORD = $dbCredential.GetNetworkCredential().Password
$env:DB_USERNAME = "acompanhaai"
$env:DB_URL = "jdbc:postgresql://localhost:5432/acompanhaai"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres-local"
```

O Compose lê o arquivo `.env`; o Spring Boot recebe as variáveis do terminal.
Se alterar `DB_PORT`, ajuste também a porta em `DB_URL`.
O perfil `postgres-local` usa PostgreSQL e permite cookie de sessão em HTTP local.
O perfil `dev` continua usando H2. Não combine os dois perfis.
O volume `postgres_data` preserva os dados entre reinícios.
Alterar a senha no `.env` não altera a senha de um banco já inicializado no volume.
Para parar o banco preservando os dados, execute `docker compose down`.

A configuração do volume segue a [documentação da imagem oficial do PostgreSQL](https://github.com/docker-library/docs/blob/master/postgres/README.md), que usa `/var/lib/postgresql` para a versão 18.

### Entidades e migrações

O package `entities` contém `User`, `Job`, `JobSkill`, `Application`,
`ApplicationStatusHistory` e `ImportTask`; os enums ficam em `entities.enums`.
`BaseEntity` concentra ID e datas automáticas das entidades mutáveis.
Vaga e candidatura têm `@Version` para controle de edições concorrentes.
Os relacionamentos usam carregamento lazy e não propagam remoção para o usuário.

- V1: usuários, preservada.
- V2: fotos de usuários, criada na outra conversa.
- V3: vagas, candidaturas e histórico.
- V4: salário, detalhes da vaga, habilidades e constraints adicionais.
- V5: tarefas de importação com resultado JSONB.

As migrações criam FKs, índices e constraints, incluindo propriedade da vaga,
unicidade por usuário e validação de salário. Rodam automaticamente ao iniciar
a aplicação. Nenhum banco é criado apenas por compilar o código.

As entidades foram integradas com o CRUD de candidaturas criado na outra
conversa. A URL recebida é usada para gerar o hash de deduplicação; registros
anteriores à V4 precisam de preenchimento do hash. A confirmação de importação,
o worker e uma normalização mais abrangente das URLs continuam pendentes.
A exclusão de uma vaga remove suas tarefas vinculadas; a exclusão da conta
remove os dados próprios por cascata no banco.

Os testes de mapeamento usam H2 e verificam relacionamentos, JSON, precisão
monetária, constraints, isolamento e exclusão em cascata. Para executar a suíte
em um PostgreSQL de testes separado, passe as propriedades do datasource:

```powershell
# Use exclusivamente um banco de testes: a suíte cadastra e exclui registros.
$env:DB_PASSWORD = $dbCredential.GetNetworkCredential().Password
.\mvnw.cmd test "-Dspring.datasource.url=jdbc:postgresql://localhost:5432/acompanhaai_test" "-Dspring.datasource.username=acompanhaai" "-Dspring.datasource.password=$env:DB_PASSWORD"
```

Crie `acompanhaai_test` antes de executar esse comando. As propriedades
passadas pelo Maven substituem o datasource H2 do perfil de testes.

## API

O contrato está em [docs/openapi.yaml](docs/openapi.yaml).

| Método | Rota | Comportamento |
|---|---|---|
| GET | `/api/auth/csrf` | Obtém token CSRF e inicia uma sessão anônima |
| POST | `/api/users` | Cadastra uma conta; não realiza login automaticamente |
| POST | `/api/auth/login` | Autentica e renova o ID da sessão |
| GET | `/api/users/me` | Consulta a própria conta |
| PUT | `/api/users/me` | Substitui nome e e-mail da própria conta |
| PUT | `/api/users/me/password` | Troca senha mediante senha atual e encerra todas as sessões |
| DELETE | `/api/users/me` | Exclui a própria conta e encerra todas as sessões |
| POST | `/api/auth/logout` | Encerra a sessão atual |

Não há listagem pública ou acesso a contas por ID. A identidade vem da sessão. Todas as operações de escrita, inclusive cadastro e login, exigem o cookie da sessão e o header CSRF. Obtenha um novo token após login ou logout: o login invalida o token anterior. A sessão expira após 30 minutos de inatividade.

Nome: 2–100 caracteres após remover espaços nas extremidades. E-mail: válido, até 254 caracteres, convertido para minúsculas. Senha: 8–128 caracteres; espaços e caixa são preservados. O banco também garante unicidade do e-mail para requisições simultâneas.

Respostas: `201` no cadastro, `200` em consultas/login/edição, `204` em exclusão/troca de senha/logout, `400` para JSON inválido, `401` para autenticação ausente ou credenciais inválidas, `403` para CSRF ausente/inválido, `409` para e-mail duplicado e `422` para campos inválidos. Erros de negócio e validação usam `ProblemDetail`; erros interceptados pelo filtro de segurança retornam `status` e `error`. Nenhuma resposta inclui senha ou hash.

## Exemplo completo em PowerShell

Com a aplicação em execução no perfil `dev`:

```powershell
$baseUrl = "http://localhost:8080"
$csrf = Invoke-RestMethod "$baseUrl/api/auth/csrf" -SessionVariable webSession
$headers = @{ $csrf.headerName = $csrf.token }

$cadastro = @{
    name = "Usuario-exemplo"
    email = "maicon@example.com"
    password = "MinhaSenhaForte123!"
} | ConvertTo-Json

Invoke-RestMethod "$baseUrl/api/users" -Method Post -WebSession $webSession `
    -Headers $headers -ContentType "application/json" -Body $cadastro

$login = @{ email = "maicon@example.com"; password = "MinhaSenhaForte123!" } | ConvertTo-Json
Invoke-RestMethod "$baseUrl/api/auth/login" -Method Post -WebSession $webSession `
    -Headers $headers -ContentType "application/json" -Body $login

# O login renova a sessão e invalida o token CSRF anterior.
$csrf = Invoke-RestMethod "$baseUrl/api/auth/csrf" -WebSession $webSession
$headers = @{ $csrf.headerName = $csrf.token }

Invoke-RestMethod "$baseUrl/api/users/me" -WebSession $webSession

$edicao = @{ name = "Maicon"; email = "maicon@example.com" } | ConvertTo-Json
Invoke-RestMethod "$baseUrl/api/users/me" -Method Put -WebSession $webSession `
    -Headers $headers -ContentType "application/json" -Body $edicao

Invoke-RestMethod "$baseUrl/api/auth/logout" -Method Post -WebSession $webSession -Headers $headers
```

Ao integrar o frontend, prefira a mesma origem da API. Não há CORS habilitado para origens externas nesta etapa.

## Testes e pacote

O cenario completo de dados ficticios **sem imagem**, congelado como v1, esta em
[postman/fixtures/v1/README.md](postman/fixtures/v1/README.md). Ele inclui a colecao
do Postman, dois usuarios ficticios, oito etapas de candidatura, controle
automatico de sessao/CSRF e limpeza dos dados criados. Mudancas futuras devem
criar uma v2 e preservar a v1.

```powershell
.\mvnw.cmd verify
```

Os testes de integração usam MockMvc, Spring Security, o banco H2 e as migrações reais. Verificam cadastro, hash, e-mail duplicado, validação, CSRF, renovação da sessão, login inválido, edição, isolamento de contas, troca de senha, foto opcional e validação de imagens, CRUD de candidaturas, filtros, histórico, deduplicação por link, controle de versão e exclusão dos dados vinculados. O PostgreSQL exige uma verificação própria no ambiente que o disponibilizar.

O pacote executável é gerado em `target/acompanhaai-0.0.1-SNAPSHOT.jar`:

```powershell
java -jar target/acompanhaai-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

Recuperação de senha por e-mail, confirmação de e-mail, limitação de tentativas de login e perfis administrativos ficam para etapas futuras. A importação de vagas e a integração com IA ainda não fazem parte desta etapa.

## Contribuição

Consulte o [guia de contribuição](CONTRIBUTING.md) para conhecer
o fluxo de trabalho e os padrões de branches, commits e pull requests.