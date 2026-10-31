# Guia de integração da API com o frontend

Relacionamento: KAN-35. Descreve a API existente; o frontend Angular e as
extensões de salário/habilidades/recuperação/importação ainda serão entregues.

Fonte do contrato: [openapi.yaml](openapi.yaml). Conferir DTOs, Resources e
testes ao alterar o contrato. A coleção Postman v1 é um registro congelado,
não deve ser regenerada para acompanhar o OpenAPI.

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

| Domínio | Operações |
|---|---|
| Conta | POST `/api/users`; GET/PUT/DELETE `/api/users/me` |
| Autenticação | GET `/api/auth/csrf`; POST `/api/auth/login` e `/api/auth/logout` |
| Senha | PUT `/api/users/me/password`, com senha atual e nova |
| Foto | GET/PUT/DELETE `/api/users/me/photo` |
| Candidaturas | GET/POST `/api/applications`; GET/PUT/DELETE `/api/applications/{id}` |
| Histórico | GET `/api/applications/{id}/history` |

Troca de senha e exclusão da conta encerram a sessão atual; outras sessões
são recusadas na próxima requisição. A inatividade máxima configurada é
30 minutos. Reinício do backend perde as sessões em memória.

Foto é opcional: usar avatar padrão quando `photoUrl` for nulo. Upload usa
multipart com campo `file`; não definir Content-Type manualmente ao enviar
FormData no navegador. O próprio navegador gera o boundary.

## Cadastro manual de candidatura

Exemplo de payload atual, sem dados pessoais:

```json
{
  "job": {
    "title": "Desenvolvedor Java",
    "companyName": "Empresa Exemplo",
    "sourceUrl": "https://example.com/jobs/123",
    "description": "Java e Spring Boot."
  },
  "status": "SAVED",
  "appliedOn": null,
  "notes": "Revisar os requisitos antes de enviar a inscrição."
}
```

`job.title` é obrigatório. Status omitido no cadastro assume APPLIED;
SAVED exige data nula. Datas futuras são inválidas. O link é armazenado,
não acessado. Salário, habilidades e modalidade já aparecem na modelagem,
mas ainda não são campos do DTO HTTP atual.

### Paginação e atualização

`GET /api/applications?status=SCREENING&page=0&size=20` retorna `content`,
`page`, `size`, `totalElements` e `totalPages`. Página começa em zero;
tamanho de 1 a 100. Ordenação: atualização e ID decrescentes.
Busca por texto ainda não é parte do contrato atual.

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

| Status | Ação esperada |
|---|---|
| 400 | Corrigir JSON, parâmetros ou tipos; não repetir sem correção |
| 401 | No login, apresentar erro; em rota privada, solicitar autenticação |
| 403 | Conferir sessão/CSRF; não reenviar escrita indefinidamente |
| 404 | Registro inexistente ou não pertencente à conta; não revelar outro proprietário |
| 409 | Conflito de e-mail, link ou versão; orientar correção/reconciliação |
| 413 | Informar limite de upload |
| 422 | Destacar campos inválidos quando houver `errors`; também aceitar `detail` sem lista |
| 5xx/rede | Preservar dados digitados e informar falha; não fingir sucesso |

Erros dos filtros de segurança usam `status/error`; erros tratados no
controller usam ProblemDetail. O frontend precisa aceitar ambos.

## Pendências para concluir KAN-35

- Atualizar o contrato quando salário/habilidades forem expostos pelos DTOs.
- Sincronizar os exemplos e preparar regressão v2.
- Verificar sessão e CSRF pelo navegador real do Angular.
- Validar 401/403/409/422 com frontend e QA.
- Definir contratos de recuperação e importação nas respectivas histórias,
  sem apresentar rotas planejadas como endpoints disponíveis.

Este guia não substitui essas implementações nem comprova teste E2E.
