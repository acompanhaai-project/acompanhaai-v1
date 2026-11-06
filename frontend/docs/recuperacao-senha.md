# Recuperação de senha

As telas mantêm o padrão visual dos arquivos de interface e usam a API real.
`PasswordResetApi`, em `src/app/core/auth/`, centraliza os dois endpoints.
O serviço de demonstração foi removido.

## Solicitar o link

`/recuperar-senha` aceita `?email=...`, preenchido pelo link **Esqueci minha senha**.
Após validar o endereço (máximo de 254 caracteres), a tela envia
`POST /api/auth/password-reset/request` com `{ email }`, cookie de sessão e CSRF.

A resposta `202` apresenta a mesma confirmação para contas existentes ou não.
Ela não comprova entrega de mensagem: o backend processa o pedido de forma
assíncrona, aplica cotas e pode descartar pedidos sem revelar a existência da conta.
Não há consulta prévia de e-mail nem endereços especiais que simulem erros.

O reenvio aguarda 60 segundos. O prazo usa um instante final, de modo que uma
aba em segundo plano não acumule atraso na contagem. `429`, quando recebido,
respeita os segundos de `Retry-After` (ou 60 segundos na ausência de um valor
válido), bloqueando apenas novas tentativas manuais. A troca de e-mail não
remove a espera vigente. A interface não substitui os limites do servidor.

Pedidos concorrentes são bloqueados; usar outro e-mail fica desabilitado durante
um reenvio. `400`/`422` com erro de campo destacam o e-mail. Falhas de rede,
`403` e `5xx` não apresentam confirmação de sucesso nem repetem o POST.

## Ler o link e redefinir

O e-mail local usa `http://localhost:4200/reset-password#token=...`.
A interface aceita `/reset-password` e `/redefinir-senha`.

O token é lido do fragmento e removido da barra de endereço por `replaceState`,
sem nova requisição. Query `?token=...` é aceita por compatibilidade e também
removida; ela já pode ter sido enviada ao servidor na primeira requisição,
portanto o formato recomendado é o fragmento. Fontes e demais recursos seguem
`Referrer-Policy: no-referrer`, definida por meta no documento.

Tokens ausentes, ambíguos ou fora do formato emitido pelo backend (43 caracteres
Base64url) mostram o aviso de link inválido sem HTTP. Para os demais links,
a validade real é conferida somente ao confirmar a nova senha. Não existe
endpoint de pré-validação e a interface não simula essa consulta.

O formulário envia `POST /api/auth/password-reset/confirm` com somente `token`
e `newPassword`. A senha não é transformada; limite de 128 caracteres e regras
visuais do cadastro são mantidos. A confirmação da senha é local.

| Resultado | Comportamento |
| --- | --- |
| `204` | Sucesso, limpeza de token/senhas e estado local anônimo |
| `400` | Link inválido, expirado ou já utilizado; solicitar outro link |
| `422` no token | Aviso de link inválido |
| `422` em newPassword | Erro associado ao campo de senha |
| `429` | Espera conforme Retry-After, sem repetição automática |
| `403` ou `5xx` | Erro e possibilidade de tentativa manual |
| Falha de rede | Alteração não confirmada; orientar testar a nova senha antes de repetir |

Após `204`, o CSRF local é descartado. O próximo login obtém outro token, e
nenhuma sessão autenticada é criada automaticamente. A API recusa sessões com
a versão antiga da senha. O token é mantido somente em memória durante o fluxo,
sem storage ou console; ele e as senhas são limpos após sucesso ou rejeição do
link. Pedidos e intervalos são cancelados ao destruir a página.

Como o token sai do endereço, recarregar a página exige reabrir o link original
do e-mail. Não há token de demonstração: usar links realmente emitidos pela API.

## Teste local com e-mail capturado

A partir da raiz, em um terminal:

```bash
cd backend
docker compose -f compose.mail.yaml up -d
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev,mail-local"
```

Em outro terminal:

```bash
cd frontend
npm start
```

Abrir `/recuperar-senha` no frontend, solicitar para uma conta de teste e
consultar o Mailpit em `http://localhost:8025`. Abrir o link recebido, alterar
a senha e testar o login. Reabrir e reutilizar o link deve resultar em `400`.
O e-mail local só funciona com o perfil de envio e SMTP configurados.

O adaptador atual é SMTP de testes local. Provedor e entrega de e-mail de
produção continuam sendo trabalho de implantação. O prazo de 30 minutos é
controlado pelo backend; não pelo relógio do navegador.
