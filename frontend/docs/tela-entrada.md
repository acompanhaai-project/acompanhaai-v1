# Tela de entrada

A rota `/entrar` preserva o padrão visual fornecido nos arquivos de interface.
A raiz e os caminhos desconhecidos redirecionam para ela.

## Fontes

- `src/app/app.component.ts`
- `src/app/app.routes.ts`
- `src/app/app.config.ts`
- `src/app/auth-page/auth-page.component.ts`, `.html` e `.scss`
- `src/app/header/header.component.ts`, `.html` e `.scss`
- `src/app/neon-cursor/neon-cursor.component.ts`
- `src/main.ts`, `src/index.html` e `src/styles.scss`

## Login real

1. Informar um e-mail e clicar em **Continuar**, ou pressionar Enter. Essa
   etapa apenas valida o endereço localmente; não consulta a existência da conta.
2. Informar a senha. É possível mostrar/ocultar a senha, alterar o e-mail ou
   seguir para recuperação. Alterar o e-mail limpa a senha.
3. Ao clicar em **Entrar**, a tela usa `SessionApi.login()` e bloqueia envios
   repetidos. O fluxo obtém CSRF, faz `POST /api/auth/login`, renova o token e
   consulta `GET /api/users/me` antes de mostrar **Acesso confirmado**.

O backend precisa estar em execução; o proxy local encaminha `/api` à porta
8080. Apenas espaços externos do e-mail são removidos. A senha é enviada sem
transformação, com limite de 128 caracteres e sem impor as regras de criação
de senha ao login. O e-mail tem limite de 254 caracteres.

Em `401`, a mensagem é genérica e não confirma se o e-mail está cadastrado.
Erros `400`/`422` com campos são associados ao e-mail ou senha. Falhas de rede,
`403`, `429` e `5xx` liberam o formulário, sem repetição automática do login.
A senha é limpa após sucesso ou falha e o fluxo pendente é cancelado ao sair da tela.

O cartão de confirmação ainda fica em `/entrar`; o painel de candidaturas
será uma entrega própria. A conta exibida vem da resposta autenticada de `GET me`.

## Saída

**Sair** chama `SessionApi.logout()`. Após `204`, o estado passa a anônimo e
um novo CSRF é preparado. A tela retorna à etapa de e-mail com os campos limpos.
Se apenas a obtenção do novo CSRF falhar após `204`, a saída continua confirmada;
se o pedido de logout falhar, a interface informa que não conseguiu confirmar a saída.

Credenciais e tokens não são persistidos em localStorage/sessionStorage nem
registrados no console. O cookie de sessão continua sob controle do navegador.
Após recarregar a página, a restauração da interface autenticada ainda depende
da futura implementação de inicialização/guards, mesmo que a sessão no servidor exista.

## Pendências

Google/Apple exibem aviso de indisponibilidade. Os documentos legais e links
institucionais ainda são placeholders. Recuperação e redefinição estão
[integradas à API](recuperacao-senha.md). Não há envio de código por e-mail no login.
