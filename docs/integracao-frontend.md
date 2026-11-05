# Guia de integração da API com o frontend

Relacionamento: KAN-35. Descreve a API existente; o frontend Angular ainda
será entregue. Os detalhes de salário e habilidades seguem o
[contrato KAN-30](vaga-manual-kan-30.md).

Fonte do contrato: [openapi.yaml](openapi.yaml). Conferir DTOs, Resources e
testes ao alterar o contrato. A coleção Postman v1 é um registro congelado,
não deve ser regenerada para acompanhar o OpenAPI. A v2 também tem scripts de
sessão e limpeza próprios: revisar sua compatibilidade a cada mudança, sem
substituí-la por uma coleção gerada. DTOs/Resources definem o comportamento
implementado; OpenAPI é o contrato publicado no repositório, atualizado junto
com mudanças de interface. A coleção fornece verificações executáveis.

## Endereço e autenticação

Desenvolvimento local: `http://localhost:8080`, com perfil que aceite HTTP.
Não há Bearer token ou JWT no fluxo atual: usar sessão HTTP e CSRF.

Preferir mesma origem, usando proxy no desenvolvimento do Angular. Não há
CORS para uma origem externa configurado atualmente. Se as origens forem
separadas no futuro, configurar cookies/CORS explicitamente e enviar
credenciais; não combinar origem wildcard com credenciais.

### Sequência de acesso

1. `GET /api/auth/csrf`: preservar o cookie recebido e ler `headerName/token`.
2. Para cadastrar, `POST /api/users` com cookie e header CSRF. Cadastro
   retorna 201, mas não autentica.
3. `POST /api/auth/login` com cookie, CSRF e credenciais da conta.
4. Login retorna a conta e renova a sessão. Preservar o cookie atualizado.
5. Consultar `/api/auth/csrf` novamente: o token anterior foi invalidado.
6. Consultar `/api/users/me` para obter o estado autenticado no servidor.
7. Enviar CSRF em todas as escritas. Logout também exige CSRF.
8. Após logout, obter um novo CSRF antes de um novo login.

Não armazenar senha ou identificador de sessão no localStorage. O cookie
HttpOnly é gerenciado pelo navegador. Token CSRF não substitui autenticação.
O cliente deve coordenar a renovação para não disparar escritas paralelas
com token antigo durante login/logout.

## Operações disponíveis

| Domínio      | Operações                                                                    |
| ------------ | ---------------------------------------------------------------------------- |
| Conta        | POST `/api/users`; GET/PUT/DELETE `/api/users/me`                            |
| Autenticação | GET `/api/auth/csrf`; POST `/api/auth/login` e `/api/auth/logout`            |
| Senha        | PUT `/api/users/me/password`, com senha atual e nova                         |
| Recuperação  | POST `/api/auth/password-reset/request` e `/api/auth/password-reset/confirm` |
| Foto         | GET/PUT/DELETE `/api/users/me/photo`                                         |
| Candidaturas | GET/POST `/api/applications`; GET/PUT/DELETE `/api/applications/{id}`        |
| Histórico    | GET `/api/applications/{id}/history`                                         |

Troca de senha e exclusão da conta encerram a sessão atual; outras sessões
são recusadas na próxima requisição. A inatividade máxima configurada é
30 minutos. Reinício do backend perde as sessões em memória.

Foto é opcional: usar avatar padrão quando `photoUrl` for nulo. Upload usa
multipart com campo `file`; não definir Content-Type manualmente ao enviar
FormData no navegador. O próprio navegador gera o boundary.
JPEG/PNG são validados pelo conteúdo: máximo de 2 MiB por arquivo e
4 milhões de pixels. Multipart completo: até 3 MiB. GET retorna bytes
privados com `Cache-Control: no-store`; sem foto retorna 404. DELETE retorna
204 mesmo quando já não há foto. Após upload/remoção, reconsultar a conta.

### Recuperação de senha

Obter CSRF mesmo sem login. Enviar `{ "email": "pessoa@example.test" }`
para `POST /api/auth/password-reset/request`. A resposta 202 é genérica:
não comprova existência da conta nem entrega do e-mail. O envio depende da
configuração SMTP; está desabilitado por padrão.

Em `POST /api/auth/password-reset/confirm`, enviar `token` e `newPassword`
no JSON, com CSRF. O token expira em até 30 minutos e é de uso único.
204 indica senha alterada; exigir novo login. Token inválido/expirado/usado
retorna 400. Limite excedido na confirmação retorna 429 com `Retry-After`
em segundos. Solicitação limitada continua retornando 202 sem processamento.
Não registrar token, senha ou cookie em logs, analytics ou URLs da API.

### Responsabilidades do futuro cliente Angular

- Usar URLs relativas `/api/...` e proxy local para o backend. O proxy deve
  encaminhar também cookies; confirmar isso nas ferramentas do navegador.
- Centralizar chamadas HTTP e tratamento dos dois formatos de erro em um
  serviço/interceptor. Não há cookie `XSRF-TOKEN`: o mecanismo padrão baseado
  nesse cookie não atende sozinho ao contrato atual.
- Ler `headerName` e `token` do GET de CSRF e manter em memória. Enviar o
  header apenas em escritas destinadas à API, nunca a serviços externos.
- Serializar login/logout e a renovação do CSRF antes de liberar novas
  escritas. Não interpretar cada novo valor mascarado de CSRF como logout.
- Em mesma origem o navegador envia o cookie. Se futuramente houver origens
  distintas, avaliar `withCredentials`, CORS, HTTPS e atributos do cookie
  juntos, antes de alterar o cliente. A API atual não libera essa integração.
- Em 401 numa rota privada, limpar o estado autenticado local e solicitar
  login. Em 403, preservar o formulário e verificar sessão/CSRF; não fazer
  repetição automática de POST/PUT/DELETE.

## Cadastro manual de candidatura

Exemplo de payload atual, sem dados pessoais:

```json
{
  "job": {
    "title": "Desenvolvedor Java",
    "companyName": "Empresa Exemplo",
    "sourceUrl": "https://example.com/jobs/123",
    "description": "Java e Spring Boot.",
    "location": "São Paulo",
    "workMode": "HYBRID",
    "salary": 6000.00,
    "salaryCurrency": "BRL",
    "salaryPeriod": "MONTH",
    "hardSkills": ["Java", "Spring Boot"],
    "softSkills": ["Comunicação"]
  },
  "status": "SAVED",
  "appliedOn": null,
  "notes": "Revisar os requisitos antes de enviar a inscrição."
}
```

`job.title` é obrigatório. Status omitido no cadastro assume APPLIED;
SAVED exige data nula. Datas futuras são inválidas. O link é armazenado,
não acessado. Salário único, habilidades, localização e modalidade estão
disponíveis nos DTOs conforme o [contrato KAN-30](vaga-manual-kan-30.md).
Ao editar, reenviar os detalhes que devem ser preservados: opcionais omitidos
são limpos e listas omitidas ou vazias removem as habilidades daquele tipo.
Salário ausente deve ir como null (ou ser omitido), junto de moeda e período
nulos/omitidos. Nunca buscar ou apresentar estimativa como salário da vaga.
Duplicatas de habilidades são recusadas sem diferenciar caixa, inclusive
entre os dois tipos. Existem oito etapas: SAVED, APPLIED, SCREENING,
INTERVIEW, OFFER, HIRED, REJECTED e WITHDRAWN.

Um link equivalente já registrado na própria conta retorna 409 com
`code: JOB_URL_ALREADY_EXISTS`; colisão de hash retorna
`JOB_URL_HASH_COLLISION`. Sem link podem existir diversas candidaturas.
Outras contas podem cadastrar o mesmo link. Não deduzir duplicidade por
título/empresa e não tentar unir registros automaticamente.

### Paginação e atualização

`GET /api/applications?status=SCREENING&page=0&size=20` retorna `content`,
`page`, `size`, `totalElements` e `totalPages`. Página começa em zero;
tamanho de 1 a 100. Ordenação: atualização e ID decrescentes.
O parâmetro opcional `q` busca um trecho literal do título ou empresa, sem
diferenciar caixa, com até 100 caracteres. Pode ser combinado com `status`:
`GET /api/applications?status=SCREENING&q=java&page=0&size=20`.
Texto vazio ou somente espaços desativa a busca textual; `%`, `_` e `!` são
literais. Ver [contrato KAN-31](acompanhamento-kan-31.md).

No PUT, enviar os campos editáveis completos e `version` da última leitura.
Opcionais omitidos ficam nulos. Trocar status registra histórico; alterar
somente observações não cria evento de etapa.

Não repetir PUT automaticamente após 409. Reconsultar, preservar a edição
local e pedir que o usuário reconcilie as diferenças.

## Erros e comportamento da interface

Os corpos abaixo são exemplos representativos. Campos opcionais de
ProblemDetail, como `instance`, podem variar; não depender da mensagem
exata para decidir regras do cliente.

```json
{"status": 401, "error": "Autenticação necessária."}
```

```json
{"status": 403, "error": "Acesso negado ou token CSRF inválido."}
```

```json
{"type": "about:blank", "title": "Conflict", "status": 409,
 "detail": "O registro foi alterado. Recarregue antes de salvar."}
```

```json
{"type": "about:blank", "title": "Unprocessable Entity", "status": 422,
 "detail": "Dados inválidos.",
 "errors": [{"field": "job.title", "message": "não deve estar em branco"}]}
```

| Status   | Ação esperada                                                                       |
| -------- | ----------------------------------------------------------------------------------- |
| 400      | Corrigir JSON, parâmetros ou tipos; não repetir sem correção                        |
| 401      | No login, apresentar erro; em rota privada, solicitar autenticação                  |
| 403      | Conferir sessão/CSRF; não reenviar escrita indefinidamente                          |
| 404      | Registro inexistente ou não pertencente à conta; não revelar outro proprietário     |
| 409      | Conflito de e-mail, link ou versão; orientar correção/reconciliação                 |
| 413      | Informar limite de upload                                                           |
| 422      | Destacar campos inválidos quando houver `errors`; também aceitar `detail` sem lista |
| 429      | Respeitar Retry-After na confirmação de recuperação, sem repetição imediata         |
| 5xx/rede | Preservar dados digitados e informar falha; não fingir sucesso                      |

Erros dos filtros de segurança usam `status/error`; erros tratados no
controller usam ProblemDetail. O frontend precisa aceitar ambos.

## Rastreabilidade e aceite

| Critério / história               | Contrato entregue                                         | Verificação existente / próxima revisão                            |
| --------------------------------- | --------------------------------------------------------- | ------------------------------------------------------------------ |
| KAN-24 — conta e sessão           | User, UserUpdate, rotas users/auth, CSRF                  | Testes de autenticação e preparação/autorização Postman v2         |
| KAN-25 — recuperação              | PasswordResetRequest/Confirmation, 202/204/400/429        | Testes de recuperação; não coberto pela coleção v2 de candidaturas |
| KAN-26 — foto privada             | Multipart, limites, resposta binária, remoção idempotente | Testes de foto; imagens não fazem parte da fixture v2              |
| KAN-30 — vaga manual              | JobInput/Job, salário BRL e habilidades                   | Validações positivas e negativas Postman v2                        |
| KAN-31 — acompanhamento           | Página, q/status, oito etapas, histórico e version        | Busca/etapas/conflito/rollback Postman v2                          |
| KAN-32 — isolamento e duplicidade | 404 por propriedade, códigos 409, exclusão                | Dois atores, links e limpeza Postman v2                            |
| KAN-35 — exemplos e integração    | OpenAPI e este guia                                       | Validação estática nesta tarefa; aceite frontend/QA pendente       |

As evidências de execução da v2 estão em
[KAN-33](regressao-api-v2-kan-33.md) e [KAN-34](ci-postgresql-kan-34.md).
Essas execuções validam HTTP/backend, não o futuro cliente Angular.

Roteiro para revisão com frontend/QA: cadastro → login → novo CSRF → GET me;
listar página vazia e com filtro; criar com/sem salário; editar com version;
provocar 401 sem sessão, 403 sem CSRF, 409 por versão/link e 422 com campo
inválido e com regra sem errors; confirmar preservação do formulário;
encerrar sessão e tentar nova escrita. Conferir também recuperação e foto.
Registrar versão do frontend/backend, navegador, resultado e link da evidência
sem cookies ou tokens. A revisão conjunta ainda não foi realizada.

Não há endpoint HTTP de importação por URL publicado neste contrato. Não
apresentar extração por IA como funcionalidade disponível.
