# KAN-86 — Proteção de rotas e restauração da sessão

## Ambiente — 06/11/2026

- Angular 21, Node.js 22.16.0 e npm 10.9.2.
- Java 25 e Spring Boot 4.0.9, sem alteração no backend.
- API isolada na porta 18084, perfil `dev` e H2 em memória
  `acompanhaai-session-e2e`, sem importar configurações locais com credenciais.
- Frontend na porta 14237, com proxy para essa API.
- Microsoft Edge automatizado com Playwright, em 1440 e 390 pixels,
  nos temas claro e escuro.

## Implementação

- `/conta` protegida por consulta à API antes de exibir a identidade.
- Restauração da sessão existente ao abrir `/entrar` ou recarregar `/conta`.
- Destino interno preservado após login, incluindo query e fragmento.
- Destinos externos rejeitados; destino padrão `/conta`.
- Estado de carregamento e tentativa manual quando a consulta falha.
- Redirecionamento após `401` privado, respeitando a revisão da sessão.
- Saída confirmada retorna ao login; falha de logout não anuncia sucesso.
- Design de acesso preservado. `/conta` reutiliza o cartão de confirmação;
  ainda não representa edição de perfil ou quadro de candidaturas.

## Verificações automatizadas

```bash
cd frontend
npm run format:check
npm run lint
npm run test:ci
npm run build
```

Resultado: 116 testes passaram em 10 arquivos. Foram adicionados 5 casos de
verificação de sessão e 21 casos de navegação/destino seguro. Os testes usam
`HttpTestingController` e `RouterTestingHarness`, cobrindo compartilhamento de
consulta, timeout, cancelamento, `200`, `401`, `503`, retorno após login,
expiração privada, `403` e respostas atrasadas de uma sessão anterior.

Lint, formatação e build passaram. O build mantém os avisos já existentes de
orçamento de SCSS nas quatro páginas de acesso, sem ultrapassar o limite de erro.

## Navegador com API real

Nas quatro combinações de largura/tema:

1. Abrir `/conta?aba=perfil#dados` sem sessão: conteúdo privado não aparece
   enquanto a consulta está pendente; `401` direciona ao login.
2. Cadastrar e entrar: voltar ao destino original com query e fragmento.
3. Recarregar a área privada: restaurar identidade sem novo envio de login.
4. Abrir `/entrar` em outra aba com a mesma sessão: seguir para a área privada.
5. Sair nessa outra aba e recarregar a primeira: voltar ao login; nova
   autenticação retorna ao destino preservado.
6. Conferir ausência de identidade/tokens em localStorage e sessionStorage.

Também foi validado o bloqueio de `returnUrl` externo após login real.
Não foram observados erros JavaScript durante esses cenários.

## Falhas simuladas e limites

Um `503` foi simulado no navegador para a consulta de sessão: a tela exibiu
falha de verificação, ocultou o formulário e permitiu tentativa manual. Após
remover a simulação, a consulta e o login usaram a API real e preservaram o destino.

O redirecionamento de `401` recebido por uma operação privada ativa foi
verificado nos testes Angular. No navegador, a expiração foi exercitada pela
recarga após logout em outra aba. Não há heartbeat nem sincronização imediata
entre abas; a próxima consulta, navegação protegida ou recarga verifica a sessão.

Esta evidência cobre a implementação. Aceite e encerramento dos cartões
KAN-36/KAN-86 e a validação de QA permanecem sujeitos à revisão do fluxo.

Contrato e procedimento de extensão: [sessão e rotas](../../frontend/docs/sessao-e-rotas.md).
