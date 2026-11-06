# Sessão e rotas privadas — KAN-86 / KAN-36

## Rotas

`/conta` reutiliza o cartão de confirmação de acesso, com identidade recebida
da API e ação **Sair**. Não é a tela de edição de perfil nem o quadro de
candidaturas. Seu `canActivate` consulta `GET /api/users/me` antes de ativar
o conteúdo. Query e fragmento são preservados ao retornar após o login.

`/entrar` consulta a sessão quando o estado ainda é desconhecido. Se a sessão
existe, segue para `/conta`; se a API devolve `401`, mostra o formulário.
Cadastro, recuperação e redefinição continuam públicos.

## Verificação e falhas

`SessionAccess` compartilha a consulta em andamento e limita a espera a dez
segundos, incluindo eventual espera na fila de operações da sessão. A
verificação não se baseia em dados de localStorage/sessionStorage.

| Resultado de GET me | Comportamento |
| --- | --- |
| `200` | Restaura a identidade e permite a rota privada |
| `401` | Marca sessão anônima e direciona para entrada |
| Rede, timeout, `403` ou `5xx` | Mantém estado desconhecido, bloqueia conteúdo privado e oferece tentativa manual |

A tela não apresenta login como se a pessoa estivesse anônima quando não foi
possível verificar a sessão. **Tentar novamente** refaz apenas a consulta;
não repete cadastro, login, redefinição ou outras escritas.

Após login, o destino protegido também passa pelo guard. Portanto pode haver
uma nova consulta a `GET me` depois da confirmação do login. Cada ativação
privada é conferida no servidor; somente verificações simultâneas compartilham
uma requisição.

## Expiração e saída

Um `401` de operação privada limpa a sessão/CSRF e publica a revisão expirada.
`SessionNavigation` redireciona a rota privada ativa para `/entrar`, com aviso
de expiração e destino preservado. `403` não é tratado como sessão expirada.
Um `401` atrasado de revisão anterior não apaga a sessão mais recente.

As consultas de verificação e a confirmação do login são identificadas como
fluxos de sessão; seus guards/formulários tratam o resultado, evitando dois
redirecionamentos concorrentes. Logout e redefinição não disparam o aviso de
expiração de uma operação privada.

Após `204` no logout, a interface volta ao login com campos limpos. Se o pedido
de logout falhar, a saída não é anunciada como concluída; a conta pode ser
verificada novamente. Cookies permanecem sob controle do navegador.

Não há heartbeat nem sincronização de identidade por storage entre abas. Uma
saída em outra aba é percebida na consulta seguinte, navegação protegida ou
recarga. A API continua sendo a autoridade de autenticação e autorização.

## Destino seguro

`safeReturnUrl()` aceita somente `/conta`, com query/fragmento opcionais.
URLs externas, barras invertidas, controles, rotas públicas e sintaxes de
outlet/matriz são rejeitadas. O padrão é `/conta`, evitando redirecionamento
aberto e retorno em loop para login.

Para adicionar uma rota privada futura:

1. Aplicar `requireSession` e marcar `data.requiresAuth: true`.
2. Definir quando o guard deve rodar novamente na navegação.
3. Ampliar a lista de destinos aceitos em `return-url.ts`.
4. Testar acesso direto, recarga, expiração, retorno e autorização da API.

## Verificação

`session-access.spec.ts` cobre compartilhamento, estados, timeout e cancelamento.
`session-guards.spec.ts` cobre navegação real com `RouterTestingHarness`, retorno
seguro, falhas da API, expiração e respostas atrasadas. Esses testes usam
`HttpTestingController`; a validação no navegador usa uma API isolada com H2.
