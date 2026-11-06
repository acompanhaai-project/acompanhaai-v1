# Tela de cadastro

Interface de `/cadastro` baseada em `acompanha-ai-src.zip`, com componentes
standalone, `OnPush` e formulário reativo tipado. O login mantém o mesmo padrão
visual e inclui navegação para o cadastro. Os dois compartilham os estilos do
cartão e a diretiva de iluminação e inclinação.

## Comportamento

- Nome completo com nome e sobrenome e validação de e-mail.
- Senha com pelo menos oito caracteres, minúscula, maiúscula, número e símbolo.
- Indicador visual de força e checklist de requisitos.
- Confirmação de senha e botões para mostrar/ocultar os dois campos.
- Foco no primeiro campo inválido após envio.
- Campos bloqueados durante carregamento e apresentação de erro ou sucesso.
- Retorno para `/entrar` pelo link da tela e pela confirmação de sucesso.
- Limites de 100 caracteres no nome, 254 no e-mail e 128 na senha.
- A política visual de nome completo e complexidade da senha foi mantida;
  a API aceita nome de 2 a 100 caracteres e senha de 8 a 128 caracteres.

## Integração com a API

`SignupPageComponent` usa `SessionApi.register()` para enviar `POST /api/users`.
O payload contém somente `name`, `email` e `password`; a confirmação da senha
é validada localmente. Nome e e-mail têm espaços externos removidos, enquanto
a senha é enviada sem transformação.

O interceptor obtém o token em `GET /api/auth/csrf` e preserva o cookie de sessão.
O proxy local encaminha `/api/**` ao backend na porta 8080. Frontend e backend
devem estar em execução para testar o cadastro; usar o endereço do frontend.

O sucesso é exibido após a resposta da API. O backend devolve `201` e um DTO
sem credenciais. A tela limpa os campos de senha e orienta a entrar, sem simular
envio de confirmação ou autenticação automática. O link **Ir para o login**
abre o [fluxo real de entrada](tela-entrada.md).

| Resultado | Comportamento |
| --- | --- |
| `201` | Conta criada; senha e confirmação são limpas |
| `409` | E-mail destacado para correção |
| `400` / `422` com `errors` | Campos `name`, `email` e `password` recebem os erros da API |
| `403` | Erro de segurança apresentado; sem repetição automática |
| `429` | Mensagem de limite de solicitações |
| Falha de rede ou `5xx` | Formulário liberado para revisão e tentativa manual |

Os campos ficam bloqueados durante o envio e cliques repetidos não disparam
novos cadastros. Ao sair da tela, a inscrição pendente é cancelada. Erros não
autenticam a conta nem repetem escritas automaticamente.

## Verificação

`signup-page.component.spec.ts` usa o cliente real e o interceptor com
`HttpTestingController`, verificando payload, CSRF, limites, erros, cancelamento
e ausência de login automático. Os testes de HTTP não substituem a validação
do cadastro com a API e banco em execução.

Para conferir manualmente, cadastrar um e-mail fictício exclusivo e observar
`201` no navegador. Reabrir `/cadastro` e repetir o e-mail deve produzir `409`.
Corrigir o e-mail permite uma nova tentativa. Não usar dados pessoais nos testes.
